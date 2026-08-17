"""POST /api/v1/sync/push request shape. api-contract.md section 6.1.

`records` is deliberately `list[dict[str, Any]]`, not a typed sub-model. A single malformed
record must not fail Pydantic validation for the whole envelope: "one bad record does not fail
the batch" (section 6.1) has to hold even when the record is missing a required key or carries
the wrong type for one, and that only works if per-record shape is checked by hand in
app/services/sync.py, where a failure becomes a `rejected` result rather than a 400.

The response shape is not a schema here: app/services/sync.py builds it as a plain dict and
passes it straight through app.schemas.common.envelope, the same way every other route's success
body is built, and the stored `sync_batches.response_json` has to be that exact dict so a replay
can return it verbatim.
"""

from __future__ import annotations

from datetime import datetime
from typing import Any

from pydantic import Field

from app.schemas.common import StrictModel

BATCH_ID_MAX = 36
DEVICE_ID_MAX = 64
MAX_RECORDS = 500
MAX_BODY_BYTES = 5 * 1024 * 1024


class SyncPushEnvelope(StrictModel):
    batch_id: str = Field(min_length=1, max_length=BATCH_ID_MAX)
    device_id: str = Field(min_length=1, max_length=DEVICE_ID_MAX)
    client_time: datetime
    records: list[dict[str, Any]]
