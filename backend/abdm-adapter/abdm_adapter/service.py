"""Orchestration for the P0 vertical slice: session start through profile retrieval.

Every state-changing step here follows the same shape: check not expired, validate the
transition, call ABDM through client.py (whose per-endpoint classifier already applied D2), fail
loudly through `_fail` on any non-ok result, or advance the transaction and write exactly one
audit row through the single Phase 4 appender (`app.services.audit.append`) on success. No second
chain implementation, matching this session's own D7/D6 rules and the Phase 4 precedent they cite.

Every SUCCESS path runs on the caller's request-scoped session (`app.db.session.session_scope`):
the transaction row's new state and its audit row commit together, matching app/services/sync.py's
push() reasoning for its own writes.

The FAILURE path (`_fail`, below) is different and uses `app.db.session.write_out_of_band`. This
was found, not assumed, by this session's own tests: an early version of `_fail` flushed
`txn.state = FAILED` on the request session and then raised `SamdError`. `session_scope` rolls
back the whole request transaction on any propagating exception, so that flush was silently
undone every time, and the transaction was left forever stuck at whatever state it was in before
the failed call, never actually marked FAILED. This is the exact failure-path trap
`write_out_of_band`'s own docstring names as having shipped twice already (auth's failed-login row
in Phase 1, kernel's call-log rows in Phase 3); it shipped a third time here before the tests in
`tests/test_abha.py` caught it. Every state-changing function in this module is also written so it
never flushes an intermediate write to the transaction row before the ABDM call whose result
decides whether `_fail` gets called: `write_out_of_band`'s deadlock rule requires the caller's
session to hold no lock on that row when the out-of-band write starts, and the only way to
guarantee that here is to not write anything until the outcome is known.
"""

from __future__ import annotations

import json
import re
import uuid
from datetime import timedelta
from typing import Any, NoReturn

from app.config import Settings, abdm_consent_block
from app.db.base import utcnow
from app.db.session import write_out_of_band
from app.deps import CurrentWorker
from app.errors import ErrorCode, SamdError
from app.middleware.request_id import current_request_id
from app.models.abha import AbhaTransaction
from app.models.enums import AbhaTransactionKind, AuditAction, AuditOrigin
from app.models.enums import AbhaTransactionState as State
from app.services import audit as audit_service
from sqlalchemy.ext.asyncio import AsyncSession

from abdm_adapter import client, mapping
from abdm_adapter.crypto import encrypt_oaep_sha1, fetch_public_key_pem
from abdm_adapter.errors import AbdmResult
from abdm_adapter.request_context import new_correlation_id
from abdm_adapter.transaction import (
    check_not_expired,
    mobile_verification_needed,
    validate_transition,
)

# Not specified by any of the ground-truth sources read for Phase A/B; chosen to match ABDM's own
# X-token TTL (1800s = 30 minutes) so a session does not outlive the token it will eventually
# hold. A local, adjustable choice, not an ABDM contract value.
SESSION_TTL_MINUTES = 30

_MASKED_MOBILE_RE = re.compile(r"ending with (\*+\d+)")


def _extract_masked_mobile(message: str) -> str | None:
    """D4: no structured masked-mobile field exists; this is a regex pull out of ABDM's own
    free-text message, fragile against ABDM changing the wording. Live-activation checklist item,
    recorded in docs/requirements/abha-internal-contract.md."""
    match = _MASKED_MOBILE_RE.search(message)
    return match.group(1) if match else None


async def _audit(
    session: AsyncSession,
    worker: CurrentWorker,
    txn: AbhaTransaction,
    action: AuditAction,
    *,
    extra: dict[str, Any] | None = None,
) -> None:
    await audit_service.append(
        session,
        action=action.value,
        facility_id=worker.facility_id,
        actor_id=worker.worker_id,
        actor_role=worker.role,
        device_id=worker.device_id,
        request_id=current_request_id(),
        origin=AuditOrigin.SERVER,
        payload=json.dumps(
            {"session_id": txn.local_transaction_id, **(extra or {})},
            separators=(",", ":"),
            sort_keys=True,
        ),
    )


async def _fail(
    session: AsyncSession, worker: CurrentWorker, txn: AbhaTransaction, result: AbdmResult
) -> NoReturn:
    """Mark the transaction FAILED and raise.

    Writes out of band (see this module's own docstring for why: `session_scope` rolls back the
    whole request transaction once `SamdError` propagates, so a flush on the request session here
    would silently vanish). Re-fetches the row inside the out-of-band session rather than reusing
    `txn` (an object bound to the request session's identity map) so the write happens on its own
    connection, per `write_out_of_band`'s own contract. Every caller of `_fail` in this module is
    required to not have flushed any mutation to this row earlier in the same call, so this
    out-of-band write never contends with a lock the request session is still holding.

    `result.external_message` is already a bounded, ABDM-sourced string (errors.py truncates to
    500 chars and never copies a raw body), safe to store and to put in the client-facing `detail`.
    """
    code = result.error_code or ErrorCode.ABHA_UPSTREAM_ERROR
    message = result.external_message
    session_id = txn.local_transaction_id

    async def _write(out_of_band_session: AsyncSession) -> None:
        row = await out_of_band_session.get(AbhaTransaction, session_id)
        if row is None:
            return
        row.state = State.FAILED.value
        row.last_error_code = code.value
        row.last_error_detail = message
        await audit_service.append(
            out_of_band_session,
            action=AuditAction.ABHA_SESSION_FAILED.value,
            facility_id=worker.facility_id,
            actor_id=worker.worker_id,
            actor_role=worker.role,
            device_id=worker.device_id,
            request_id=current_request_id(),
            origin=AuditOrigin.SERVER,
            payload=json.dumps(
                {"session_id": session_id, "code": code.value},
                separators=(",", ":"),
                sort_keys=True,
            ),
        )

    await write_out_of_band(_write, context=f"abha.session_failed.{session_id}")
    raise SamdError(code, detail=message or "The ABDM request failed.")


async def load_transaction(
    session: AsyncSession, worker: CurrentWorker, session_id: str
) -> AbhaTransaction:
    txn = await session.get(AbhaTransaction, session_id)
    if txn is None or txn.facility_id != worker.facility_id:
        raise SamdError(ErrorCode.ABHA_SESSION_NOT_FOUND)
    return txn


async def start_session(session: AsyncSession, worker: CurrentWorker) -> AbhaTransaction:
    txn = AbhaTransaction(
        local_transaction_id=str(uuid.uuid4()),
        kind=AbhaTransactionKind.REGISTRATION.value,
        state=State.STARTED.value,
        facility_id=worker.facility_id,
        worker_id=worker.worker_id,
        correlation_id=new_correlation_id(),
        expires_at=utcnow() + timedelta(minutes=SESSION_TTL_MINUTES),
    )
    session.add(txn)
    await session.flush()
    await _audit(session, worker, txn, AuditAction.ABHA_SESSION_STARTED)
    return txn


async def submit_identity(
    session: AsyncSession,
    worker: CurrentWorker,
    txn: AbhaTransaction,
    *,
    aadhaar_number: str,
    settings: Settings,
) -> str | None:
    """Returns the masked mobile, if ABDM's message could be parsed for one. Advances
    STARTED -> IDENTITY_SUBMITTED -> OTP_REQUESTED within this one call, matching
    api-contract.md section 8's own response shape (`POST .../identity` returns
    `"state": "OTP_REQUESTED"` directly). Both edges are validated before the ABDM call, but
    neither is flushed to the row until the call succeeds: an early flush here previously left
    the row locked on the request session while `_fail` (which writes out of band, on a different
    connection) tried to update the same row, exactly the deadlock `write_out_of_band`'s own
    docstring warns about. Found by this module's own test suite, not by inspection.
    """
    check_not_expired(state=State(txn.state), expires_at=txn.expires_at)
    validate_transition(current=State(txn.state), target=State.IDENTITY_SUBMITTED)
    validate_transition(current=State.IDENTITY_SUBMITTED, target=State.OTP_REQUESTED)

    gateway_token = await client.fetch_gateway_session_token(
        mode=settings.abdm_mode,
        session_url=settings.abdm_session_url,
        client_id=settings.abdm_client_id,
        client_secret=settings.abdm_client_secret,
        timeout_seconds=settings.abdm_timeout_seconds,
    )
    cert_pem = await fetch_public_key_pem(
        mode=settings.abdm_mode,
        cert_url=settings.abdm_cert_url,
        gateway_token=gateway_token,
        timeout_seconds=settings.abdm_timeout_seconds,
    )
    encrypted_aadhaar = encrypt_oaep_sha1(aadhaar_number, cert_pem)

    result = await client.send_otp(
        mode=settings.abdm_mode,
        txn_id="",
        scope=["abha-enrol"],
        login_hint="aadhaar",
        encrypted_login_id=encrypted_aadhaar,
        otp_system="aadhaar",
        base_url=settings.abdm_base_url,
        timeout_seconds=settings.abdm_timeout_seconds,
    )
    if not result.ok:
        await _fail(session, worker, txn, result)

    txn.external_txn_id = str(result.body["txnId"])
    txn.state = State.OTP_REQUESTED.value
    await session.flush()
    await _audit(session, worker, txn, AuditAction.ABHA_IDENTITY_SUBMITTED)

    return _extract_masked_mobile(str(result.body.get("message", "")))


async def verify_otp(
    session: AsyncSession,
    worker: CurrentWorker,
    txn: AbhaTransaction,
    *,
    otp: str,
    mobile_number: str,
    settings: Settings,
) -> str:
    """Returns the resulting state, either ENROLLED or MOBILE_VERIFICATION_REQUIRED."""
    check_not_expired(state=State(txn.state), expires_at=txn.expires_at)
    validate_transition(current=State(txn.state), target=State.OTP_VERIFIED)

    gateway_token = await client.fetch_gateway_session_token(
        mode=settings.abdm_mode,
        session_url=settings.abdm_session_url,
        client_id=settings.abdm_client_id,
        client_secret=settings.abdm_client_secret,
        timeout_seconds=settings.abdm_timeout_seconds,
    )
    cert_pem = await fetch_public_key_pem(
        mode=settings.abdm_mode,
        cert_url=settings.abdm_cert_url,
        gateway_token=gateway_token,
        timeout_seconds=settings.abdm_timeout_seconds,
    )
    encrypted_otp = encrypt_oaep_sha1(otp, cert_pem)

    consent = abdm_consent_block(settings)
    result = await client.enrol_by_aadhaar(
        mode=settings.abdm_mode,
        txn_id=txn.external_txn_id or "",
        otp_plain=otp,
        encrypted_otp=encrypted_otp,
        communication_mobile=mobile_number,
        consent_code=consent["code"],
        consent_version=consent["version"],
        base_url=settings.abdm_base_url,
        timeout_seconds=settings.abdm_timeout_seconds,
    )
    if not result.ok:
        await _fail(session, worker, txn, result)

    txn.state = State.OTP_VERIFIED.value
    await session.flush()
    await _audit(session, worker, txn, AuditAction.ABHA_OTP_VERIFIED)

    profile = result.body["ABHAProfile"]
    tokens = result.body["tokens"]
    txn.external_token_encrypted = str(tokens["token"])
    txn.external_token_expires_at = utcnow() + timedelta(seconds=int(tokens["expiresIn"]))
    txn.abha_number = mapping.strip_abha_number_dashes(str(profile["ABHANumber"]))
    txn.abha_address = (profile.get("phrAddress") or [None])[0]
    txn.abha_status = profile.get("abhaStatus")
    txn.abha_type = profile.get("abhaType")

    validate_transition(current=State.OTP_VERIFIED, target=State.ENROLLED)
    txn.state = State.ENROLLED.value
    await session.flush()
    await _audit(session, worker, txn, AuditAction.ABHA_ENROLLED)

    enrolled_masked_mobile = str(profile.get("mobile", ""))
    if mobile_verification_needed(
        submitted_mobile=mobile_number, enrolled_masked_mobile=enrolled_masked_mobile
    ):
        validate_transition(current=State.ENROLLED, target=State.MOBILE_VERIFICATION_REQUIRED)
        txn.state = State.MOBILE_VERIFICATION_REQUIRED.value
        await session.flush()

    return txn.state


async def verify_mobile_otp(
    session: AsyncSession,
    worker: CurrentWorker,
    txn: AbhaTransaction,
    *,
    otp: str,
    settings: Settings,
) -> str:
    check_not_expired(state=State(txn.state), expires_at=txn.expires_at)
    validate_transition(current=State(txn.state), target=State.MOBILE_VERIFIED)

    gateway_token = await client.fetch_gateway_session_token(
        mode=settings.abdm_mode,
        session_url=settings.abdm_session_url,
        client_id=settings.abdm_client_id,
        client_secret=settings.abdm_client_secret,
        timeout_seconds=settings.abdm_timeout_seconds,
    )
    cert_pem = await fetch_public_key_pem(
        mode=settings.abdm_mode,
        cert_url=settings.abdm_cert_url,
        gateway_token=gateway_token,
        timeout_seconds=settings.abdm_timeout_seconds,
    )
    encrypted_otp = encrypt_oaep_sha1(otp, cert_pem)

    result = await client.verify_mobile_otp(
        mode=settings.abdm_mode,
        txn_id=txn.external_txn_id or "",
        otp_plain=otp,
        encrypted_otp=encrypted_otp,
    )
    if not result.ok:
        await _fail(session, worker, txn, result)

    txn.state = State.MOBILE_VERIFIED.value
    await session.flush()
    await _audit(session, worker, txn, AuditAction.ABHA_MOBILE_VERIFIED)
    return txn.state


async def fetch_profile(
    session: AsyncSession, worker: CurrentWorker, txn: AbhaTransaction, settings: Settings
) -> dict[str, Any]:
    check_not_expired(state=State(txn.state), expires_at=txn.expires_at)
    validate_transition(current=State(txn.state), target=State.PROFILE_RETRIEVED)

    if not txn.external_token_encrypted:
        raise SamdError(
            ErrorCode.ABHA_INVALID_STATE, detail="No verified session token available yet."
        )

    result = await client.get_profile(mode=settings.abdm_mode, x_token=txn.external_token_encrypted)
    if not result.ok:
        await _fail(session, worker, txn, result)

    txn.state = State.PROFILE_RETRIEVED.value
    await session.flush()

    identity = mapping.profile_to_abha_identity(result.body)

    validate_transition(current=State.PROFILE_RETRIEVED, target=State.COMPLETED)
    txn.state = State.COMPLETED.value
    # Phase 1's own comment on this column: "cleared the moment the session reaches a terminal
    # state." Nothing about the token needs to survive past this point.
    txn.external_token_encrypted = None
    txn.external_token_expires_at = None
    await session.flush()
    await _audit(session, worker, txn, AuditAction.ABHA_PROFILE_RETRIEVED)

    return identity
