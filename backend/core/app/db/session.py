"""Async engine and session factory."""

from __future__ import annotations

from collections.abc import AsyncIterator, Awaitable, Callable

from sqlalchemy.ext.asyncio import (
    AsyncEngine,
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)

from app.config import Settings, get_settings
from app.logging import get_logger

logger = get_logger(__name__)

_engine: AsyncEngine | None = None
_sessionmaker: async_sessionmaker[AsyncSession] | None = None


def build_engine(settings: Settings) -> AsyncEngine:
    return create_async_engine(
        settings.database_url,
        echo=settings.db_echo,
        pool_size=settings.db_pool_size,
        max_overflow=settings.db_max_overflow,
        pool_pre_ping=True,
    )


def get_engine() -> AsyncEngine:
    global _engine
    if _engine is None:
        _engine = build_engine(get_settings())
    return _engine


def get_sessionmaker() -> async_sessionmaker[AsyncSession]:
    global _sessionmaker
    if _sessionmaker is None:
        _sessionmaker = async_sessionmaker(
            bind=get_engine(),
            expire_on_commit=False,
            autoflush=False,
        )
    return _sessionmaker


async def dispose_engine() -> None:
    """Close the pool on shutdown. Called from the app lifespan."""
    global _engine, _sessionmaker
    if _engine is not None:
        await _engine.dispose()
    _engine = None
    _sessionmaker = None


async def session_scope() -> AsyncIterator[AsyncSession]:
    """Request-scoped session. Commits on success, rolls back on any exception.

    Handlers do not call commit themselves. One transaction per request means a handler that
    raises halfway through cannot leave a half-written clinical record behind.
    """
    factory = get_sessionmaker()
    async with factory() as session:
        try:
            yield session
        except Exception:
            await session.rollback()
            raise
        else:
            await session.commit()


async def write_out_of_band(
    work: Callable[[AsyncSession], Awaitable[None]],
    *,
    context: str,
) -> None:
    """Run `work` in a fresh session, independent of the caller's request transaction, and
    commit it immediately.

    1. WHY THIS EXISTS. session_scope (above) rolls back the WHOLE request transaction on any
       exception. Anything that must survive a failure path — a log row, an audit row — cannot be
       written on the request session and left there: if a SamdError is about to propagate, the
       row goes down with the transaction that produced it. This has been the same bug twice now:
       app.api.v1.auth's failed-login audit row in Phase 1, and app.services.kernel's
       kernel_call_log/kernel_assessments rows for a failed or successful kernel call in Phase 3.
       Phase 4 sync push has the identical failure-path shape, so this is a named helper now
       instead of a pattern re-derived a third time.

    2. THE DEADLOCK RULE. Never call this with `work` touching a row the CALLER's still-open
       transaction holds a lock on. This is not theoretical: Phase 1's refresh-token reuse path
       revoked a token chain in the request session and then tried to re-touch the same rows out
       of band before the request session released its locks, self-deadlocking the connection
       pool. The fix there (and the pattern to follow) is to finish and commit any recovery work
       needed on the SAME rows in its own explicit session first, then call this helper only for
       the audit/log write that does not need those rows.

    3. IT SWALLOWS ITS OWN EXCEPTIONS, BY DESIGN. If `work` raises, that is logged at ERROR with
       `context` and NOT re-raised. A failure to record an event must never mask the clinical
       error already propagating past the call site — the caller is almost always already inside
       an `except` block or about to raise, and an exception from here replacing that one would
       hide the real failure. Losing one audit row is recoverable; losing the reason a request
       failed is not.

    4. CALL SITES (both existing ones migrated onto this helper, behaviour unchanged):
       - app.api.v1.auth: the failed-login audit row and the refresh-reuse-detected audit row.
       - app.services.kernel: the kernel_call_log/audit_events row pair on a failed call, and the
         kernel_call_log/kernel_assessments/audit_events row triple on a successful call.
    """
    factory = get_sessionmaker()
    try:
        async with factory() as session:
            await work(session)
            await session.commit()
    except Exception:
        logger.error("write_out_of_band_failed", context=context, exc_info=True)
