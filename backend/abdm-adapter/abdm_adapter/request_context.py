"""ABDM request headers, generated centrally, never per call.

Two distinct ids exist in this flow and must not be conflated:

- `correlation_id`: SaMD's own id for one registration session, used for audit and logs. Generated
  once, when the session starts, and reused for every ABDM call and every audit row that session
  produces.
- `REQUEST-ID`: the ABDM header, a fresh UUID4 per outbound ABDM call (every recorded Postman
  request uses `{{$guid}}` or `{{$randomUUID}}`, a new value each time, not one reused per
  transaction). Never the same value as `correlation_id` and never derived from it.
"""

from __future__ import annotations

import uuid
from datetime import UTC, datetime


def new_correlation_id() -> str:
    return str(uuid.uuid4())


def new_request_id() -> str:
    return str(uuid.uuid4())


def abdm_timestamp() -> str:
    """ISO 8601 UTC with milliseconds, matching every recorded example
    (`{{$isoTimestamp}}` in the Postman collection, e.g. "2023-05-09T21:10:36.177Z")."""
    return datetime.now(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def abdm_headers(*, gateway_token: str | None = None, x_token: str | None = None) -> dict[str, str]:
    """The REQUEST-ID/TIMESTAMP pair every ABDM call carries, plus whichever bearer header this
    specific call needs. At most one of `gateway_token`/`x_token` should be set: the gateway
    session token (cert fetch only, in this P0 slice) and the per-transaction X-token (profile
    fetch only) are never the same header on the same call. See crypto.py's module docstring and
    the Phase A contract's "two structurally different tokens" finding.
    """
    headers = {"REQUEST-ID": new_request_id(), "TIMESTAMP": abdm_timestamp()}
    if gateway_token is not None:
        headers["Authorization"] = f"Bearer {gateway_token}"
    if x_token is not None:
        headers["X-token"] = f"Bearer {x_token}"
    return headers
