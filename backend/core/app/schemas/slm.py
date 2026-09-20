"""SLM readback proxy request model. See docs/backend/api-contract.md section 11.

The device sends what only the device can know, and the backend supplies every invariant the
service contract fixes. That split is the reason this model is short:

    from the device : case_token, prompt, model_id, prompt_template_version, max_tokens, seed
    from the backend: temperature = 0, do_sample = false, stop = []

temperature, do_sample and stop are NOT accepted from the device. They are the determinism
guarantee ("two readbacks of the same approved record produce the same text"), and a guarantee a
caller can vary is not one. The service rejects a non-zero temperature with a 422 anyway; making
the field unsendable means the 422 cannot be reached from here at all, which is one fewer
reachable failure rather than one more defended one.

model_id IS accepted from the device, deliberately, and it is the only one of the three
self-describing fields that travels outward. The device's sanitizer holds a pinned target model
id and compares the SERVED identity in the response against it; letting the device state which
artifact it is built for means a mismatch surfaces as an explicit 409 from the service rather
than as text the device silently refuses later. The pin is the device's, so the claim is the
device's to make.

prompt has a length bound here as well as on the device (MAX_PROMPT_CHARS = 6000, measured in
SlmScopeGate.kt) and at the service, where the real tokenizer lives and the authoritative bound
is in tokens. Three bounds, not two redundant ones: the device's is a free refusal with no
network call, this one stops a drifted or hostile client from pushing a megabyte through an
authenticated hop, and the service's is the truth. Each is strictly tighter than the one outside
it, so the common case refuses locally and a 413 from the service means something has drifted.
"""

from __future__ import annotations

from pydantic import Field

from app.schemas.common import StrictModel

# Matches MAX_PROMPT_CHARS in app/src/main/java/com/example/samdapp/domain/slm/SlmScopeGate.kt.
# Not tighter: a prompt the device was willing to assemble must not be refused here, or the
# device's own refusal stops being the one a worker sees.
MAX_PROMPT_CHARS = 6000

# The device's output ceiling (slm-service-contract.md section 4.3's arithmetic: 512 tokens
# against a 40 s service cap). The service applies min(requested, its own ceiling); this bound
# only stops a caller asking for a generation nobody sized the budget for.
MAX_OUTPUT_TOKENS = 512


class SlmReadbackRequest(StrictModel):
    """POST /api/v1/slm/readback."""

    # The caller's real case_record_id, as on the kernel routes. Resolved and facility-scoped
    # server side; never sent onward, in any form.
    case_token: str = Field(min_length=1, max_length=36)
    # The assembled prompt, complete. Not a messages array and not a template plus values: the
    # single-turn rule is enforced by there being no field a transcript could arrive on, on the
    # device, here and at the service alike.
    prompt: str = Field(min_length=1, max_length=MAX_PROMPT_CHARS)
    model_id: str = Field(min_length=1, max_length=80)
    prompt_template_version: str = Field(min_length=1, max_length=40)
    max_tokens: int = Field(ge=1, le=MAX_OUTPUT_TOKENS)
    seed: int = Field(ge=0)
