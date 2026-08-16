"""Shared response envelope and base request model.

Every 2xx response body from every endpoint has one shape. GET /health is the single exception
and returns a bare object, because healthcheck tooling should not have to parse an envelope.
"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, ConfigDict

API_VERSION = "v1"


class StrictModel(BaseModel):
    """Base for every request model.

    extra="forbid" without exception. An unexpected field is a 422, not a silent pass-through.
    This is the first half of the structural PHI guarantee on the kernel boundary in Phase 3
    (REQ-HAN-06, hazard H-10): a field that is not declared cannot arrive.
    """

    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)


class ResponseMeta(BaseModel):
    request_id: str
    timestamp: str
    api_version: str = API_VERSION


def envelope(data: Any, *, request_id: str, timestamp: str) -> dict[str, Any]:
    """Wrap a payload in the success envelope.

    data is an object or an array, never a bare scalar and never null. An empty result is {} or
    [], so a client never has to distinguish "absent" from "empty".
    """
    return {
        "success": True,
        "data": data,
        "meta": {
            "request_id": request_id,
            "timestamp": timestamp,
            "api_version": API_VERSION,
        },
    }
