"""The PHI boundary guard for the SLM readback hop, and an honest account of its limits.

READ THIS BEFORE TRUSTING IT. The kernel's guard (app/adapters/kernel/phi_guard.py) checks
28 denied KEY NAMES against the top-level keys of a parsed body. Measured against the readback
payload, that guard is vacuous: the outbound body's keys are prompt, model_id,
prompt_template_version, max_tokens, temperature, do_sample, seed and stop, and not one of them
is on the denylist, so assert_no_identity_fields cannot fire on this shape no matter what the
request contains. It is still called on the INBOUND body by app/services/slm.py, for the same
reason the kernel calls it: extra="forbid" rejects an undeclared field, and the denylist is what
catches a future edit that adds a denied name AS a declared field. It is a guard against the next
edit, not against this payload.

WHAT ACTUALLY CROSSES THIS HOP. One string. The prompt, assembled on the device from five
whitelisted snapshot fields and the worker's typed question: the physician's free-text working
diagnosis, the prescription lines (generic name, brand, strength, route, frequency, duration,
quantity), a three-valued decision code, one referral bit, and the question. The device's
ApprovedRecordSnapshot has no Patient-typed field and does not interpolate the case id, so the
structural exclusions hold on the device side. What has no structure at all is the diagnosis: a
physician types into it, and nothing anywhere strips or validates it.

SO, PLAINLY: a key-name denylist catches none of the risk on this hop, and no guard in this file
can catch a name, a village, a relative's name or a landmark written into a free-text diagnosis
or a worker's question. That residual is the drafted hazard H-28's subject and it is not closed
here. What IS enforced below is the part that can be enforced structurally:

1. The real case_record_id must not appear in the prompt. The device's contract is that it never
   interpolates it (measured in buildPrompt); this makes that contract enforceable from the side
   that owns the boundary, so a future device change cannot quietly start sending it. The
   service's request schema has no identifier field at all, so unlike the kernel hop there is no
   pseudonym to substitute: the correct state is the absence of the id, not a replacement for it.

2. No long national-identifier-shaped digit sequence. Aadhaar is 12 digits, an ABHA number is 14,
   an Indian mobile is 10, and all three are commonly written in groups. Clinical prose does not
   contain such runs: a dose is "500", a duration is "5", a date component is at most 4 digits.
   This is a narrow, high-confidence pattern check, and its narrowness is deliberate; a broader
   heuristic on clinical text would refuse legitimate readbacks, and a refusal a worker learns to
   route around is worse than no check.

Both are structural, both are cheap, and neither is a claim that the prompt is de-identified.
"""

from __future__ import annotations

import re

from app.errors import ErrorCode, SamdError

# A maximal chain of digit groups joined by a single space or hyphen, where every group is at
# least 3 digits. "1234 5678 9012" is one chain of 12; "2026-09-12 38" is not a chain at all,
# because "09" and "38" are two digits, so a date beside a temperature does not trip this.
_DIGIT_CHAIN = re.compile(r"(?<!\d)\d{3,}(?:[ -]\d{3,})*(?!\d)")

# 10 is the shortest of the three targets (an Indian mobile number). Below it sit quantities,
# strengths, durations and years, none of which reach ten digits even chained.
IDENTIFIER_DIGIT_THRESHOLD = 10


def assert_prompt_is_identifier_free(prompt: str, *, case_record_id: str) -> None:
    """Raise SAMD-SLM-8014 if the prompt carries the case id or an identifier-shaped digit run.

    The detail names WHICH check fired and never any part of the prompt. Naming the check does
    not leak; echoing the match would leak exactly the value the check exists to stop.
    """
    if case_record_id and case_record_id.lower() in prompt.lower():
        raise SamdError(
            ErrorCode.SLM_IDENTITY_LEAK_BLOCKED,
            detail="The readback prompt contains the case record identifier.",
            log_context={"identity_check": "case_record_id_in_prompt"},
        )

    for match in _DIGIT_CHAIN.finditer(prompt):
        digits = sum(character.isdigit() for character in match.group())
        if digits >= IDENTIFIER_DIGIT_THRESHOLD:
            raise SamdError(
                ErrorCode.SLM_IDENTITY_LEAK_BLOCKED,
                detail=(
                    "The readback prompt contains a "
                    f"{digits}-digit sequence, which is the shape of a national identifier."
                ),
                log_context={"identity_check": "identifier_digit_run", "digit_count": digits},
            )
