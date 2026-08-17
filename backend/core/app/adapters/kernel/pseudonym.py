"""HMAC case pseudonym (decision D-7, api-contract.md section 5.2).

The kernel needs a stable identifier to correlate a request with its response, but must not
receive the real case_record_id. The substitution:

    case_token = HMAC-SHA256(case_record_id, CASE_TOKEN_KEY)[:16 hex chars]

The mapping back to case_record_id is not stored as a reverse index and is not meant to be
invertible from case_token alone. The backend already knows the real case_record_id when it
mints the token (it is the value it is substituting), so correlation is "look at the row you
just wrote," never "recover the id from the token." kernel_call_log stores both values side by
side for exactly that reason.

Deterministic, not random: the same case_record_id always produces the same case_token, which is
what lets an operator recompute it by hand from a case_record_id during an investigation, without
adding a lookup table.
"""

from __future__ import annotations

import hashlib
import hmac

CASE_TOKEN_LENGTH = 16


def case_token_for(case_record_id: str, *, key: str) -> str:
    """A wire-boundary control that keeps the raw case primary key off the kernel network hop.
    NOT de-identification: the mapping is stored next to the real case_record_id
    (kernel_call_log) and is reversible by design, deliberately, not a gap."""
    digest = hmac.new(
        key.encode("utf-8"), case_record_id.encode("utf-8"), hashlib.sha256
    ).hexdigest()
    return digest[:CASE_TOKEN_LENGTH]
