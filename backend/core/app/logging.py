"""structlog configuration: JSON to stdout, one line per event, PHI redacted.

Redaction is a processor rather than a convention at call sites, so a new call site cannot
forget it. Request and response bodies are never logged, at any level, in any environment. The
Android side gates its body logging behind BuildConfig.ENABLE_NETWORK_LOGGING for exactly this
reason; the backend does not offer the equivalent switch at all.
"""

from __future__ import annotations

import logging
import sys
from typing import Any

import structlog
from structlog.types import EventDict, Processor

from app.config import REDACTED_KEYS, get_settings

REDACTED = "[redacted]"
_MAX_DEPTH = 6


def _redact(value: Any, depth: int = 0) -> Any:
    """Recursively mask any mapping key in REDACTED_KEYS.

    Depth-limited so a cyclic or pathologically nested structure cannot stall a log write. A
    structure deeper than _MAX_DEPTH is replaced wholesale rather than partially inspected,
    because a half-walked tree is exactly where an unredacted field would hide.
    """
    if depth > _MAX_DEPTH:
        return REDACTED
    if isinstance(value, dict):
        return {
            key: (REDACTED if str(key).lower() in REDACTED_KEYS else _redact(item, depth + 1))
            for key, item in value.items()
        }
    if isinstance(value, list | tuple):
        return [_redact(item, depth + 1) for item in value]
    if isinstance(value, str) and value.lstrip().startswith("-----BEGIN"):
        # ABDM's V3 public-key PEM (crypto.py's fetch_public_key_pem) has no fixed key name at
        # every call site; this catches it, and any other PEM block, by value rather than by key,
        # the same "belt and braces" reasoning as _drop_body_keys below.
        return REDACTED
    return value


def redaction_processor(_logger: Any, _method_name: str, event_dict: EventDict) -> EventDict:
    """Drop PHI and secrets from every emitted event."""
    redacted: EventDict = _redact(dict(event_dict))
    return redacted


def _drop_body_keys(_logger: Any, _method_name: str, event_dict: EventDict) -> EventDict:
    """Hard stop on request and response bodies reaching a log line.

    Belt and braces alongside redaction_processor: redaction masks known field names, this drops
    whole-body carriers regardless of what is inside them.
    """
    for key in ("body", "request_body", "response_body", "payload", "raw"):
        event_dict.pop(key, None)
    return event_dict


def configure_logging() -> None:
    """Install the structlog pipeline. Idempotent; safe to call from lifespan and from tests."""
    settings = get_settings()
    level = getattr(logging, settings.log_level, logging.INFO)

    logging.basicConfig(format="%(message)s", stream=sys.stdout, level=level, force=True)
    # Uvicorn's own access log duplicates what the request-id middleware already emits, with no
    # request_id on it. One structured line per request beats two unstructured ones.
    logging.getLogger("uvicorn.access").disabled = True

    processors: list[Processor] = [
        structlog.contextvars.merge_contextvars,
        structlog.stdlib.add_log_level,
        structlog.processors.TimeStamper(fmt="iso", utc=True),
        _drop_body_keys,
        redaction_processor,
        structlog.processors.StackInfoRenderer(),
        structlog.processors.format_exc_info,
        structlog.processors.JSONRenderer(),
    ]

    structlog.configure(
        processors=processors,
        wrapper_class=structlog.make_filtering_bound_logger(level),
        logger_factory=structlog.PrintLoggerFactory(file=sys.stdout),
        cache_logger_on_first_use=True,
    )


def get_logger(name: str) -> structlog.stdlib.BoundLogger:
    return structlog.get_logger(name)  # type: ignore[no-any-return]
