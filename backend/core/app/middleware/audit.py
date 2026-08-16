"""Audit middleware: the safety net, not the primary path.

Two kinds of audit row exist, and the distinction is deliberate:

1. **Handler-written rows.** A handler calls services.audit.append inside the request's own
   transaction. Those rows commit or roll back with the operation they describe, so an audit row
   can never claim something happened that did not. Every clinically meaningful action uses this
   path.

2. **Middleware-written rows.** After the response is produced, this middleware writes a generic
   request_completed row for any mutating request that did not declare its own action. It uses
   its own session, so it is best-effort operational coverage, not a transactional guarantee.

A request_completed row appearing for a clinical route is a signal that the handler should be
declaring a specific action, not evidence that the coverage is fine.

Handlers control this through request.state:
    state.audit_action        set by services.audit callers, suppresses the fallback row
    state.audit_patient_id    carried onto the fallback row when present
    state.audit_case_id       carried onto the fallback row when present
    state.audit_skip          suppresses the fallback row entirely
"""

from __future__ import annotations

import json

from sqlalchemy.exc import SQLAlchemyError
from starlette.requests import Request
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from app.db.session import get_sessionmaker
from app.logging import get_logger
from app.middleware.request_id import current_request_id
from app.models.enums import AuditAction, AuditOrigin
from app.services import audit as audit_service

logger = get_logger(__name__)

MUTATING_METHODS = frozenset({"POST", "PUT", "PATCH", "DELETE"})


class AuditMiddleware:
    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        request = Request(scope)
        status_holder = {"status": 500}

        async def send_wrapper(message: Message) -> None:
            if message["type"] == "http.response.start":
                status_holder["status"] = message["status"]
            await send(message)

        await self.app(scope, receive, send_wrapper)
        await self._maybe_write(request, status_holder["status"])

    async def _maybe_write(self, request: Request, status: int) -> None:
        state = request.state

        if getattr(state, "audit_skip", False):
            return
        # The handler already wrote a specific, transactional row. Do not add a vague one on top.
        if getattr(state, "audit_action", None) is not None:
            return
        if request.method not in MUTATING_METHODS:
            return

        worker = getattr(state, "worker", None)
        if worker is None:
            # Unauthenticated mutations are login attempts and malformed requests. The auth
            # handler logs those itself with the outcome, which is the part worth keeping.
            return

        payload = json.dumps(
            {
                "method": request.method,
                "path": request.url.path,
                "status": status,
            },
            separators=(",", ":"),
            sort_keys=True,
        )

        try:
            factory = get_sessionmaker()
            async with factory() as session:
                await audit_service.append(
                    session,
                    action=AuditAction.REQUEST_COMPLETED.value,
                    facility_id=worker.facility_id,
                    actor_id=worker.worker_id,
                    actor_role=worker.role,
                    device_id=worker.device_id,
                    request_id=current_request_id(),
                    origin=AuditOrigin.SERVER,
                    patient_id=getattr(state, "audit_patient_id", None),
                    case_record_id=getattr(state, "audit_case_id", None),
                    payload=payload,
                )
                await session.commit()
        except SQLAlchemyError:
            # The response has already been sent. Failing to write the fallback row must not
            # rewrite history for the client, and it must not be silent either.
            logger.error("audit_fallback_write_failed", exc_info=True)
