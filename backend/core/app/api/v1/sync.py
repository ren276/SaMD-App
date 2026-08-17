"""POST /api/v1/sync/push. See api-contract.md section 6.1 and app/services/sync.py.

The request body is read and parsed by hand here rather than through a typed `body: Model`
parameter. That is deliberate: a typed body parameter routes every shape failure through
FastAPI's generic RequestValidationError handler, which this service (like every other route)
answers with 422 SAMD-PAT-3003. This endpoint's own contract fixes malformed JSON and a malformed
envelope at 400 SAMD-SYS-9001 instead, which only holds if parsing happens here, under this
route's own control, before FastAPI's automatic body validation ever runs.
"""

from __future__ import annotations

import json
from typing import Any

from fastapi import APIRouter, Request
from pydantic import ValidationError

from app.deps import ActiveWorkerDep, SessionDep
from app.errors import ErrorCode, SamdError
from app.middleware.request_id import current_request_id, current_timestamp
from app.models.enums import AuditAction
from app.schemas.sync import MAX_BODY_BYTES, SyncPushEnvelope
from app.services import sync as sync_service

router = APIRouter(prefix="/api/v1", tags=["sync"])


@router.post("/sync/push", summary="Apply one batch of device-captured rows")
async def push_sync_batch(
    request: Request,
    worker: ActiveWorkerDep,
    session: SessionDep,
) -> dict[str, Any]:
    raw_body = await request.body()
    if len(raw_body) > MAX_BODY_BYTES:
        raise SamdError(ErrorCode.SYNC_BATCH_TOO_LARGE, detail="Request body exceeds 5 MB.")

    try:
        parsed = json.loads(raw_body)
    except json.JSONDecodeError as exc:
        raise SamdError(
            ErrorCode.SYS_MALFORMED_REQUEST, detail="Request body is not valid JSON."
        ) from exc

    try:
        envelope_body = SyncPushEnvelope.model_validate(parsed)
    except ValidationError as exc:
        raise SamdError(
            ErrorCode.SYS_MALFORMED_REQUEST, detail="The sync envelope is malformed."
        ) from exc

    response, replayed = await sync_service.push(
        session,
        worker,
        envelope_body,
        request_id=current_request_id(),
        timestamp=current_timestamp(),
    )

    if replayed:
        # Idempotent replay touches nothing (api-contract.md section 6.1). No audit row, not even
        # the generic middleware fallback, matches "stop" being literal.
        request.state.audit_skip = True
    else:
        # Suppresses the audit middleware's generic request_completed fallback: push() already
        # wrote a specific sync_batch_received row in the same transaction.
        request.state.audit_action = AuditAction.SYNC_BATCH_RECEIVED.value

    return response
