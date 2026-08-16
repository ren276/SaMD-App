"""Authentication: PIN verification, JWT minting, refresh rotation with reuse detection.

Decision D-8: HS256. One process signs and one process verifies. RS256 earns its keep when a
second verifier exists, and switching later is a config change plus a key rollover, not a
redesign.
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from typing import Any, Literal

import bcrypt
from jose import jwt
from jose.exceptions import ExpiredSignatureError, JWTError
from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import Settings
from app.db.base import utcnow
from app.errors import ErrorCode, SamdError
from app.models.enums import UserRole
from app.models.user import Device, RefreshToken, UserAccount

BCRYPT_ROUNDS = 12
# bcrypt truncates at 72 bytes. PINs are 6 to 12 characters so this cannot bite, but a silent
# truncation in a credential path is exactly the kind of thing that is discovered late.
BCRYPT_MAX_BYTES = 72

TokenType = Literal["access", "refresh"]

# Role to permission map. Informational for the client's UI; enforcement is per route.
#
# Decision D-1: all four roles may submit to the kernel. The kernel output is never autonomous.
# It is gated by the liability acknowledgement on KernelAssessmentScreen and by the mandatory
# doctor AGREE/MODIFY/REJECT step. Role does not change that safety argument, and restricting it
# would break the shipped ASHA worker and nurse flows, which reach SendingViewModel with no role
# gate anywhere on the path.
_FIELD_PERMISSIONS = [
    "patient:read",
    "patient:write",
    "encounter:write",
    "kernel:submit",
    "sync:push",
    "sync:pull",
    "abha:manage",
]
PERMISSIONS_BY_ROLE: dict[UserRole, list[str]] = {
    UserRole.ASHA_WORKER: list(_FIELD_PERMISSIONS),
    UserRole.NURSE: list(_FIELD_PERMISSIONS),
    UserRole.COMPOUNDER: list(_FIELD_PERMISSIONS),
    UserRole.DOCTOR: [*_FIELD_PERMISSIONS, "encounter:read", "audit:read"],
}


@dataclass(frozen=True)
class TokenClaims:
    worker_id: str
    role: str
    facility_id: str
    device_id: str
    jti: str
    token_type: TokenType
    must_change_pin: bool
    expires_at: datetime


def permissions_for(role: str) -> list[str]:
    try:
        return PERMISSIONS_BY_ROLE[UserRole(role)]
    except ValueError:
        return []


# ---------------------------------------------------------------------------
# PIN hashing
# ---------------------------------------------------------------------------


def hash_pin(pin: str) -> str:
    encoded = pin.encode("utf-8")
    if len(encoded) > BCRYPT_MAX_BYTES:
        raise ValueError("PIN exceeds the 72 byte bcrypt limit")
    return bcrypt.hashpw(encoded, bcrypt.gensalt(rounds=BCRYPT_ROUNDS)).decode("utf-8")


def verify_pin(pin: str, pin_hash: str) -> bool:
    encoded = pin.encode("utf-8")
    if len(encoded) > BCRYPT_MAX_BYTES:
        return False
    try:
        return bcrypt.checkpw(encoded, pin_hash.encode("utf-8"))
    except ValueError:
        # A malformed stored hash must never read as a successful match.
        return False


# ---------------------------------------------------------------------------
# JWT
# ---------------------------------------------------------------------------


def _mint(
    settings: Settings,
    *,
    account: UserAccount,
    device_id: str,
    token_type: TokenType,
    ttl_seconds: int,
    jti: str,
) -> tuple[str, datetime]:
    issued_at = utcnow()
    expires_at = issued_at + timedelta(seconds=ttl_seconds)
    payload: dict[str, Any] = {
        "sub": account.worker_id,
        "role": account.role,
        "facility_id": account.facility_id,
        "device_id": device_id,
        "jti": jti,
        # Checked on every verification. Without it the two token types are interchangeable and
        # the one hour access lifetime becomes decorative.
        "typ": token_type,
        "pnd": account.must_change_pin,
        "iat": int(issued_at.timestamp()),
        "exp": int(expires_at.timestamp()),
        "iss": settings.jwt_issuer,
        "aud": settings.jwt_audience,
    }
    token = jwt.encode(payload, settings.jwt_secret_key, algorithm=settings.jwt_algorithm)
    return token, expires_at


def decode_token(settings: Settings, token: str, *, expected_type: TokenType) -> TokenClaims:
    """Decode and validate. Raises SamdError, never a raw JWTError."""
    try:
        payload = jwt.decode(
            token,
            settings.jwt_secret_key,
            algorithms=[settings.jwt_algorithm],
            issuer=settings.jwt_issuer,
            audience=settings.jwt_audience,
        )
    except ExpiredSignatureError as exc:
        raise SamdError(ErrorCode.AUTH_TOKEN_EXPIRED) from exc
    except JWTError as exc:
        raise SamdError(ErrorCode.AUTH_TOKEN_INVALID) from exc

    if payload.get("typ") != expected_type:
        raise SamdError(
            ErrorCode.AUTH_TOKEN_INVALID,
            detail="Token is not valid for this endpoint.",
        )

    required = ("sub", "role", "facility_id", "device_id", "jti", "exp")
    if any(payload.get(field) is None for field in required):
        raise SamdError(ErrorCode.AUTH_TOKEN_INVALID, detail="Token is missing required claims.")

    return TokenClaims(
        worker_id=str(payload["sub"]),
        role=str(payload["role"]),
        facility_id=str(payload["facility_id"]),
        device_id=str(payload["device_id"]),
        jti=str(payload["jti"]),
        token_type=expected_type,
        must_change_pin=bool(payload.get("pnd", False)),
        expires_at=datetime.fromtimestamp(int(payload["exp"]), tz=UTC),
    )


async def issue_token_pair(
    session: AsyncSession,
    settings: Settings,
    *,
    account: UserAccount,
    device_id: str,
) -> tuple[str, str, int]:
    """Mint an access and refresh pair and persist the refresh jti."""
    access, _ = _mint(
        settings,
        account=account,
        device_id=device_id,
        token_type="access",
        ttl_seconds=settings.access_token_ttl_seconds,
        jti=str(uuid.uuid4()),
    )
    refresh_jti = str(uuid.uuid4())
    refresh, refresh_expires = _mint(
        settings,
        account=account,
        device_id=device_id,
        token_type="refresh",
        ttl_seconds=settings.refresh_token_ttl_seconds,
        jti=refresh_jti,
    )
    session.add(
        RefreshToken(
            jti=refresh_jti,
            worker_id=account.worker_id,
            device_id=device_id,
            expires_at=refresh_expires,
        )
    )
    await session.flush()
    return access, refresh, settings.access_token_ttl_seconds


async def revoke_chain(
    session: AsyncSession, *, worker_id: str, device_id: str, reason: str
) -> None:
    """Revoke every live refresh token for one worker on one device."""
    await session.execute(
        update(RefreshToken)
        .where(
            RefreshToken.worker_id == worker_id,
            RefreshToken.device_id == device_id,
            RefreshToken.revoked_at.is_(None),
        )
        .values(revoked_at=utcnow(), revoked_reason=reason)
    )


# ---------------------------------------------------------------------------
# Login
# ---------------------------------------------------------------------------


async def authenticate(
    session: AsyncSession,
    settings: Settings,
    *,
    worker_id: str,
    pin: str,
) -> UserAccount:
    """Verify credentials, applying the lockout policy.

    The failure message is identical for an unknown worker_id and a wrong PIN, so the endpoint is
    not a worker-id oracle.
    """
    account = (
        await session.execute(select(UserAccount).where(UserAccount.worker_id == worker_id))
    ).scalar_one_or_none()

    if account is None:
        # Still costs a bcrypt round so response timing does not distinguish the two cases.
        bcrypt.checkpw(b"timing-equaliser", bcrypt.hashpw(b"x", bcrypt.gensalt(rounds=4)))
        raise SamdError(ErrorCode.AUTH_INVALID_CREDENTIALS)

    now = utcnow()
    if account.locked_until is not None and account.locked_until > now:
        raise SamdError(
            ErrorCode.SYS_RATE_LIMITED,
            detail="Too many failed attempts. Try again later.",
            headers={"Retry-After": str(int((account.locked_until - now).total_seconds()))},
        )

    if not account.is_active:
        raise SamdError(ErrorCode.AUTH_ACCOUNT_DISABLED)

    if not verify_pin(pin, account.pin_hash):
        account.failed_attempts += 1
        if account.failed_attempts >= settings.login_max_failed_attempts:
            account.locked_until = now + timedelta(seconds=settings.login_lockout_seconds)
            account.failed_attempts = 0
        # Committed here, not flushed. Raising below unwinds the request transaction, and a
        # lockout counter that rolls back on every failed attempt is not a lockout counter.
        # Nothing else has been written at this point, so the partial commit is exactly the
        # bookkeeping and nothing more.
        await session.commit()
        raise SamdError(ErrorCode.AUTH_INVALID_CREDENTIALS)

    account.failed_attempts = 0
    account.locked_until = None
    account.last_login_at = now
    await session.flush()
    return account


async def touch_device(
    session: AsyncSession,
    *,
    worker_id: str,
    device_id: str,
    app_version: str | None,
    environment: str | None,
) -> None:
    """Record or refresh the device binding."""
    device = (
        await session.execute(
            select(Device).where(Device.device_id == device_id, Device.worker_id == worker_id)
        )
    ).scalar_one_or_none()
    if device is None:
        session.add(
            Device(
                device_id=device_id,
                worker_id=worker_id,
                app_version=app_version,
                environment=environment,
            )
        )
    else:
        device.last_seen_at = utcnow()
        if app_version:
            device.app_version = app_version
        if environment:
            device.environment = environment
    await session.flush()


# ---------------------------------------------------------------------------
# Refresh
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class RefreshOutcome:
    account: UserAccount
    access_token: str
    refresh_token: str
    expires_in: int


async def rotate_refresh(
    session: AsyncSession,
    settings: Settings,
    *,
    refresh_token: str,
    device_id: str,
) -> RefreshOutcome:
    """Exchange a refresh token for a new pair, detecting reuse.

    Presenting an already-revoked refresh token revokes the entire chain for that
    (worker_id, device_id) pair. That is what makes a stolen refresh token survivable without a
    Redis blacklist.
    """
    claims = decode_token(settings, refresh_token, expected_type="refresh")

    if claims.device_id != device_id:
        raise SamdError(ErrorCode.AUTH_DEVICE_MISMATCH)

    stored = (
        await session.execute(select(RefreshToken).where(RefreshToken.jti == claims.jti))
    ).scalar_one_or_none()

    if stored is None:
        # Signature valid but the jti is unknown: the row was pruned, or the key was rotated
        # under a token that outlived it. Either way, not usable.
        raise SamdError(ErrorCode.AUTH_REFRESH_REVOKED)

    if stored.revoked_at is not None:
        # Revoked for a mundane reason (logout, PIN change) is not an attack. Only a token that
        # was rotated away and then presented again is reuse. Without this distinction a routine
        # PIN change raises a security-alert-grade audit event, and false alarms of that grade
        # are worse than none.
        if stored.revoked_reason != "ROTATED":
            raise SamdError(
                ErrorCode.AUTH_REFRESH_REVOKED,
                detail="This session has ended. Sign in again.",
            )
        # Deliberately does NOT revoke here. This function is about to raise, which unwinds the
        # request transaction, so any UPDATE written now would be rolled back anyway; worse, the
        # rows would still be row-locked by this open transaction while the caller opened a
        # second session to redo the revocation durably, and the two would deadlock on each
        # other. The caller performs the revocation out of band using log_context.
        raise SamdError(
            ErrorCode.AUTH_REFRESH_REVOKED,
            detail="Refresh token reuse detected. Sign in again.",
            log_context={"worker_id": stored.worker_id, "jti": stored.jti},
        )

    if stored.expires_at <= utcnow():
        raise SamdError(ErrorCode.AUTH_TOKEN_EXPIRED)

    account = (
        await session.execute(select(UserAccount).where(UserAccount.worker_id == stored.worker_id))
    ).scalar_one_or_none()
    if account is None or not account.is_active:
        raise SamdError(ErrorCode.AUTH_ACCOUNT_DISABLED)

    access, new_refresh, expires_in = await issue_token_pair(
        session, settings, account=account, device_id=device_id
    )
    new_jti = decode_token(settings, new_refresh, expected_type="refresh").jti

    stored.revoked_at = utcnow()
    stored.revoked_reason = "ROTATED"
    stored.replaced_by = new_jti
    await session.flush()

    return RefreshOutcome(
        account=account,
        access_token=access,
        refresh_token=new_refresh,
        expires_in=expires_in,
    )


# ---------------------------------------------------------------------------
# PIN change (decision D-3)
# ---------------------------------------------------------------------------


async def change_pin(
    session: AsyncSession,
    *,
    account: UserAccount,
    current_pin: str,
    new_pin: str,
) -> None:
    if not verify_pin(current_pin, account.pin_hash):
        raise SamdError(ErrorCode.AUTH_INVALID_CREDENTIALS)
    if current_pin == new_pin:
        raise SamdError(
            ErrorCode.PAT_VALIDATION_FAILED,
            detail="The new PIN must differ from the current one.",
            errors=[{"field": "new_pin", "message": "Must differ from the current PIN."}],
        )
    account.pin_hash = hash_pin(new_pin)
    account.must_change_pin = False
    await session.flush()
    # Every existing refresh chain for this worker is revoked on a PIN change, across all
    # devices. A PIN change that leaves old sessions alive is not a credential rotation.
    await session.execute(
        update(RefreshToken)
        .where(RefreshToken.worker_id == account.worker_id, RefreshToken.revoked_at.is_(None))
        .values(revoked_at=utcnow(), revoked_reason="PIN_CHANGED")
    )
