"""FastAPI application factory, exception handlers, and lifespan.

Middleware order, outermost first:

    RequestIdMiddleware -> HttpsEnforcementMiddleware -> AuditMiddleware -> routes

Request-ID is outermost so an HTTPS rejection still carries a request_id. Audit is innermost so
it observes the final status.
"""

from __future__ import annotations

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Any

from abdm_adapter.router import router as abha_routes
from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from sqlalchemy.exc import SQLAlchemyError
from starlette.exceptions import HTTPException as StarletteHTTPException

from app.adapters.kernel.circuit_breaker import KernelCircuitBreakers
from app.adapters.kernel.client import build_kernel_client
from app.api import admin as admin_routes
from app.api.v1 import auth as auth_routes
from app.api.v1 import encounters as encounter_routes
from app.api.v1 import health as health_routes
from app.api.v1 import kernel as kernel_routes
from app.api.v1 import patients as patient_routes
from app.api.v1 import sync as sync_routes
from app.config import get_settings
from app.db.session import dispose_engine
from app.errors import (
    PROBLEM_CONTENT_TYPE,
    ErrorCode,
    SamdError,
    http_status_for,
    problem_body,
)
from app.logging import configure_logging, get_logger
from app.middleware.audit import AuditMiddleware
from app.middleware.request_id import (
    HEADER,
    HttpsEnforcementMiddleware,
    RequestIdMiddleware,
    current_request_id,
    current_timestamp,
)

logger = get_logger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    settings = get_settings()
    configure_logging()
    logger.info(
        "startup",
        environment=settings.environment,
        version=settings.app_version,
        git_sha=settings.git_sha,
        abdm_mode=settings.abdm_mode,
    )

    # One kernel client for the app's lifetime (app/adapters/kernel/client.py). Created here, not
    # per request, so connections and keep-alive are actually reused.
    app.state.kernel_client = build_kernel_client(
        base_url=settings.kernel_base_url,
        connect_timeout_seconds=settings.kernel_connect_timeout_seconds,
        read_timeout_seconds=settings.kernel_read_timeout_seconds,
    )
    app.state.kernel_breakers = KernelCircuitBreakers(
        threshold=settings.kernel_circuit_threshold,
        cooldown_seconds=settings.kernel_circuit_cooldown_seconds,
    )

    try:
        yield
    finally:
        await app.state.kernel_client.aclose()
        await dispose_engine()
        logger.info("shutdown")


def _problem_response(
    code: ErrorCode,
    *,
    detail: str,
    instance: str,
    errors: list[dict[str, str]] | None = None,
    headers: dict[str, str] | None = None,
) -> JSONResponse:
    request_id = current_request_id()
    response_headers = {HEADER: request_id}
    if headers:
        response_headers.update(headers)
    return JSONResponse(
        status_code=http_status_for(code),
        content=problem_body(
            code,
            detail=detail,
            instance=instance,
            request_id=request_id,
            timestamp=current_timestamp(),
            errors=errors,
        ),
        media_type=PROBLEM_CONTENT_TYPE,
        headers=response_headers,
    )


def _validation_errors(exc: RequestValidationError) -> list[dict[str, str]]:
    """Flatten Pydantic errors into the contract's errors[] shape.

    Only the field path and a generic message survive. The submitted value is deliberately
    dropped: a validation message must never echo PHI back over the wire, and Pydantic's own
    messages happily include the input.
    """
    out: list[dict[str, str]] = []
    for error in exc.errors():
        location = [str(part) for part in error.get("loc", ()) if part not in ("body", "query")]
        out.append(
            {
                "field": ".".join(location) or "body",
                "message": str(error.get("msg", "Invalid value.")),
            }
        )
    return out


def create_app() -> FastAPI:
    settings = get_settings()
    configure_logging()

    app = FastAPI(
        title="SaMD Core API",
        version=settings.app_version,
        description=(
            "Backend for the PHC Patient Care Android app. Contract: docs/backend/api-contract.md"
        ),
        lifespan=lifespan,
        # Docs are useful in dev and are attack surface in production.
        docs_url="/docs" if settings.is_dev else None,
        redoc_url=None,
        openapi_url="/openapi.json" if settings.is_dev else None,
    )

    # Innermost added first: Starlette applies middleware in reverse order of addition.
    app.add_middleware(AuditMiddleware)
    app.add_middleware(HttpsEnforcementMiddleware, require_https=settings.require_https)
    app.add_middleware(RequestIdMiddleware)

    app.include_router(health_routes.router)
    app.include_router(admin_routes.router)
    app.include_router(auth_routes.router)
    app.include_router(patient_routes.router)
    app.include_router(encounter_routes.router)
    app.include_router(kernel_routes.router)
    app.include_router(sync_routes.router)
    app.include_router(abha_routes)

    @app.exception_handler(SamdError)
    async def handle_samd_error(request: Request, exc: SamdError) -> JSONResponse:
        log = logger.warning if exc.status < 500 else logger.error
        log("samd_error", code=exc.code.value, status=exc.status, **exc.log_context)
        return _problem_response(
            exc.code,
            detail=exc.detail,
            instance=request.url.path,
            errors=exc.errors,
            headers=exc.headers,
        )

    @app.exception_handler(RequestValidationError)
    async def handle_validation_error(
        request: Request, exc: RequestValidationError
    ) -> JSONResponse:
        return _problem_response(
            ErrorCode.PAT_VALIDATION_FAILED,
            detail="One or more fields are invalid.",
            instance=request.url.path,
            errors=_validation_errors(exc),
        )

    @app.exception_handler(StarletteHTTPException)
    async def handle_http_exception(request: Request, exc: StarletteHTTPException) -> JSONResponse:
        mapping = {
            404: ErrorCode.SYS_NOT_FOUND,
            405: ErrorCode.SYS_METHOD_NOT_ALLOWED,
            415: ErrorCode.SYS_UNSUPPORTED_MEDIA_TYPE,
            400: ErrorCode.SYS_MALFORMED_REQUEST,
        }
        code = mapping.get(exc.status_code, ErrorCode.SYS_INTERNAL)
        return _problem_response(
            code,
            detail="The request could not be routed."
            if code is ErrorCode.SYS_NOT_FOUND
            else str(exc.detail),
            instance=request.url.path,
        )

    @app.exception_handler(SQLAlchemyError)
    async def handle_database_error(request: Request, _exc: SQLAlchemyError) -> JSONResponse:
        logger.error("database_error", exc_info=True)
        return _problem_response(
            ErrorCode.SYS_DATABASE_UNAVAILABLE,
            detail="The request could not be completed.",
            instance=request.url.path,
        )

    @app.exception_handler(Exception)
    async def handle_unexpected(request: Request, _exc: Exception) -> JSONResponse:
        # Anything reaching here is a bug. The client gets no detail at all, so an unexpected
        # stack trace can never leak internals or PHI to a field device. The operator gets the
        # traceback in the log, correlated by request_id.
        logger.error("unhandled_exception", exc_info=True)
        return _problem_response(
            ErrorCode.SYS_INTERNAL,
            detail="An unexpected error occurred.",
            instance=request.url.path,
        )

    return app


app: Any = create_app()
