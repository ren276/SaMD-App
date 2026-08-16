"""Auth request and response models. See api-contract.md section 2."""

from __future__ import annotations

from pydantic import BaseModel, Field

from app.schemas.common import StrictModel

WORKER_ID_PATTERN = r"^[0-9a-f]{16}$"
DEVICE_ID_PATTERN = r"^[A-Za-z0-9._:-]{8,64}$"
PIN_MIN_LENGTH = 6
PIN_MAX_LENGTH = 12


class LoginRequest(StrictModel):
    """Login credentials.

    role is deliberately absent. The role is a property of the account on the server, not a claim
    the client asserts; a client-asserted role is a privilege-escalation field.

    pin is not optional. A login accepting only worker_id and device_id would carry the H-06 hole
    across to the server and dress it in server-side authority. See api-contract.md section 2.1.
    """

    worker_id: str = Field(pattern=WORKER_ID_PATTERN)
    pin: str = Field(min_length=PIN_MIN_LENGTH, max_length=PIN_MAX_LENGTH)
    device_id: str = Field(pattern=DEVICE_ID_PATTERN)
    app_version: str | None = Field(default=None, max_length=32)
    environment: str | None = Field(default=None, max_length=16)


class RefreshRequest(StrictModel):
    refresh_token: str = Field(min_length=16, max_length=4096)
    device_id: str = Field(pattern=DEVICE_ID_PATTERN)


class LogoutRequest(StrictModel):
    refresh_token: str = Field(min_length=16, max_length=4096)


class ChangePinRequest(StrictModel):
    """Forced on first login (decision D-3), and available voluntarily thereafter."""

    current_pin: str = Field(min_length=PIN_MIN_LENGTH, max_length=PIN_MAX_LENGTH)
    new_pin: str = Field(min_length=PIN_MIN_LENGTH, max_length=PIN_MAX_LENGTH)


class WorkerSummary(BaseModel):
    worker_id: str
    display_name: str
    role: str
    facility_id: str
    facility_name: str


class TokenPair(BaseModel):
    access_token: str
    refresh_token: str
    token_type: str = "Bearer"
    expires_in: int


class LoginResponse(TokenPair):
    worker: WorkerSummary
    # True until the initial administrator-issued PIN has been changed. While true, every route
    # except change-pin, me, and logout returns SAMD-AUTH-1008.
    must_change_pin: bool


class MeResponse(BaseModel):
    worker_id: str
    display_name: str
    role: str
    facility_id: str
    facility_name: str
    must_change_pin: bool
    # Derived from the role by the server. Informational, letting the UI hide actions the caller
    # cannot perform. It is not the enforcement point; enforcement is server-side per route.
    permissions: list[str]


class RevokedResponse(BaseModel):
    revoked: bool
