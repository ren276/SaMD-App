"""Health response model. Bare object, no envelope. See api-contract.md section 1."""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel


class HealthResponse(BaseModel):
    # Always "ok" in a 200. Failure is expressed as a 503, not a 200 with a sad field.
    status: Literal["ok"] = "ok"
    version: str
    git_sha: str
    environment: str
    uptime_seconds: int
    database: Literal["ok", "degraded"]
    # Result of the last kernel call, cached, not a live probe. A health endpoint that fans out
    # to a downstream on every hit is a self-inflicted outage. "unknown" until Phase 3 wires the
    # proxy that updates it.
    kernel: Literal["ok", "degraded", "unknown"]
    abdm_mode: Literal["stub", "live"]
    timestamp: str
