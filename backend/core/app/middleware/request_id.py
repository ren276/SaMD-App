"""Request-ID middleware.

Adopts a valid inbound UUID4 X-Request-ID or mints one, binds it to the structlog context so
every log line in the request carries it, and echoes it on the response header including on
errors.

That same request_id lands on the audit row and in every error body. A worker reporting "it
failed" plus a screenshot is then enough to find the exact request.
"""

from __future__ import annotations

import time
import uuid
from contextvars import ContextVar

import structlog
from starlette.requests import Request
from starlette.responses import JSONResponse
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from app.db.base import to_iso, utcnow
from app.errors import PROBLEM_CONTENT_TYPE, ErrorCode, http_status_for, problem_body
from app.logging import get_logger

HEADER = "X-Request-ID"

_request_id: ContextVar[str] = ContextVar("request_id", default="")
_request_timestamp: ContextVar[str] = ContextVar("request_timestamp", default="")

logger = get_logger(__name__)


def current_request_id() -> str:
    """The active request's id, or a fresh one outside a request (background tasks, tests)."""
    value = _request_id.get()
    return value or str(uuid.uuid4())


def current_timestamp() -> str:
    """The timestamp stamped at request start, so every part of one response agrees."""
    return _request_timestamp.get() or to_iso(utcnow())


def _is_uuid4(value: str) -> bool:
    try:
        return uuid.UUID(value).version == 4
    except (ValueError, AttributeError):
        return False


class RequestIdMiddleware:
    """Pure ASGI middleware.

    Not BaseHTTPMiddleware: that wraps the response in a streaming layer, which interferes with
    exception handlers and makes the error envelope harder to guarantee.
    """

    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        request = Request(scope)
        inbound = request.headers.get(HEADER, "")
        request_id = inbound if _is_uuid4(inbound) else str(uuid.uuid4())
        timestamp = to_iso(utcnow())

        id_token = _request_id.set(request_id)
        ts_token = _request_timestamp.set(timestamp)
        structlog.contextvars.bind_contextvars(
            request_id=request_id,
            method=request.method,
            path=request.url.path,
        )

        started = time.perf_counter()
        status_holder = {"status": 500}

        async def send_wrapper(message: Message) -> None:
            if message["type"] == "http.response.start":
                status_holder["status"] = message["status"]
                key = HEADER.lower().encode()
                # Replace rather than append. The error handlers set this header too, and two
                # copies arrive at the client as one comma-joined value, which no parser wants.
                headers = [
                    (name, value)
                    for name, value in message.get("headers", [])
                    if name.lower() != key
                ]
                headers.append((key, request_id.encode()))
                message["headers"] = headers
            await send(message)

        try:
            await self.app(scope, receive, send_wrapper)
        finally:
            duration_ms = int((time.perf_counter() - started) * 1000)
            # One structured line per request. Never the body, at any level, in any environment.
            logger.info(
                "request_completed",
                status=status_holder["status"],
                duration_ms=duration_ms,
            )
            structlog.contextvars.unbind_contextvars("request_id", "method", "path")
            _request_id.reset(id_token)
            _request_timestamp.reset(ts_token)


class HttpsEnforcementMiddleware:
    """Refuse a plaintext request when REQUIRE_HTTPS is on.

    Mirrors the Android posture, where cleartext lives only in src/dev/AndroidManifest.xml
    (REQ-SEC-05). A misconfigured proxy then fails loudly instead of serving PHI in the clear.
    """

    def __init__(self, app: ASGIApp, *, require_https: bool) -> None:
        self.app = app
        self.require_https = require_https

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if not self.require_https or scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        request = Request(scope)
        # /health is exempt: the orchestrator probes it on the internal network, where there is
        # no TLS terminator in front of the container.
        if request.url.path == "/health":
            await self.app(scope, receive, send)
            return

        forwarded = request.headers.get("x-forwarded-proto", "").lower()
        if forwarded != "https" and request.url.scheme != "https":
            request_id = current_request_id()
            body = problem_body(
                ErrorCode.SYS_MALFORMED_REQUEST,
                detail="HTTPS is required in this environment.",
                instance=request.url.path,
                request_id=request_id,
                timestamp=current_timestamp(),
            )
            response = JSONResponse(
                status_code=http_status_for(ErrorCode.SYS_MALFORMED_REQUEST),
                content=body,
                media_type=PROBLEM_CONTENT_TYPE,
                headers={HEADER: request_id},
            )
            await response(scope, receive, send)
            return

        await self.app(scope, receive, send)
