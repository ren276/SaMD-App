"""ABDM M1 adapter (stub mode), integration. api-contract.md section 8,
docs/requirements/abha-internal-contract.md.

Real PostgreSQL (tests/conftest.py, same as every other phase). ABDM_MODE=stub is the default
(app.config.Settings.abdm_mode), never overridden here: this suite proves the stub path end to
end, not a live call.
"""

from __future__ import annotations

from typing import Any

import httpx
import pytest
from fastapi import FastAPI
from httpx import AsyncClient
from sqlalchemy import select, text
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import Settings
from app.deps import settings_dep
from app.errors import ErrorCode
from app.models.abha import AbhaTransaction
from app.models.audit import AuditEvent
from app.models.enums import AuditAction
from app.models.sync import SyncLogEntry

AADHAAR = "234567890123"
OTP_VALID = "654321"
OTP_EXPIRED = "111111"
OTP_INCORRECT = "222222"
MOBILE_SAME_AS_AADHAAR = (
    "8446650903"  # ends 0903, matches the stub's masked suffix: skips mobile step
)
MOBILE_DIFFERENT = "9876543210"  # does not end 0903: triggers MOBILE_VERIFICATION_REQUIRED


async def _start(client: AsyncClient, headers: dict[str, str]) -> str:
    response = await client.post("/api/v1/abha/registration-sessions", headers=headers)
    assert response.status_code == 200
    data = response.json()["data"]
    assert data["state"] == "STARTED"
    return str(data["session_id"])


async def _submit_identity(
    client: AsyncClient, headers: dict[str, str], session_id: str
) -> dict[str, Any]:
    response = await client.post(
        f"/api/v1/abha/registration-sessions/{session_id}/identity",
        json={"aadhaar_number": AADHAAR},
        headers=headers,
    )
    assert response.status_code == 200
    return dict(response.json()["data"])


# ---------------------------------------------------------------------------
# Full walk, no mobile verification needed
# ---------------------------------------------------------------------------


async def test_full_session_started_to_completed_no_mobile_step(
    client: AsyncClient, auth_headers: dict[str, str], session: AsyncSession
) -> None:
    session_id = await _start(client, auth_headers)

    identity_data = await _submit_identity(client, auth_headers, session_id)
    assert identity_data["state"] == "OTP_REQUESTED"
    assert identity_data["masked_mobile"] == "******0903"

    otp_response = await client.post(
        f"/api/v1/abha/registration-sessions/{session_id}/otp",
        json={"otp": OTP_VALID, "mobile_number": MOBILE_SAME_AS_AADHAAR},
        headers=auth_headers,
    )
    assert otp_response.status_code == 200
    assert otp_response.json()["data"]["state"] == "ENROLLED"

    profile_response = await client.get(
        f"/api/v1/abha/registration-sessions/{session_id}/profile", headers=auth_headers
    )
    assert profile_response.status_code == 200
    identity = profile_response.json()["data"]
    assert identity["abha_number"] == "91756140880001"  # dashes stripped (Phase A field diff)
    assert identity["abha_address"] == "sunita.devi0001@sbx"
    assert identity["date_of_birth"] == "1991-04-12"
    assert identity["gender"] == "F"
    assert identity["mobile_number"] == "******0903"  # masked, never fabricated full (D4)
    assert identity["photo_url"] is None  # D5: never populated
    assert identity["kyc_verified"] is True
    assert identity["verification_source"] == "ABDM_AADHAAR_OTP"

    poll = await client.get(
        f"/api/v1/abha/registration-sessions/{session_id}", headers=auth_headers
    )
    assert poll.json()["data"]["state"] == "COMPLETED"

    txn = await session.get(AbhaTransaction, session_id)
    assert txn is not None
    assert txn.state == "COMPLETED"
    assert txn.abha_number == "91756140880001"
    # D5 + Phase 1's own documented intent: the token is cleared once the session is terminal.
    assert txn.external_token_encrypted is None


# ---------------------------------------------------------------------------
# Full walk, mobile verification required
# ---------------------------------------------------------------------------


async def test_full_session_with_mobile_verification_required(
    client: AsyncClient, auth_headers: dict[str, str]
) -> None:
    session_id = await _start(client, auth_headers)
    await _submit_identity(client, auth_headers, session_id)

    otp_response = await client.post(
        f"/api/v1/abha/registration-sessions/{session_id}/otp",
        json={"otp": OTP_VALID, "mobile_number": MOBILE_DIFFERENT},
        headers=auth_headers,
    )
    assert otp_response.json()["data"]["state"] == "MOBILE_VERIFICATION_REQUIRED"

    mobile_response = await client.post(
        f"/api/v1/abha/registration-sessions/{session_id}/mobile-otp",
        json={"otp": OTP_VALID},
        headers=auth_headers,
    )
    assert mobile_response.status_code == 200
    assert mobile_response.json()["data"]["state"] == "MOBILE_VERIFIED"

    profile_response = await client.get(
        f"/api/v1/abha/registration-sessions/{session_id}/profile", headers=auth_headers
    )
    assert profile_response.status_code == 200
    assert profile_response.json()["data"]["abha_number"] == "91756140880001"


# ---------------------------------------------------------------------------
# D2: a 200 with authResult "failed" must not advance the session
# ---------------------------------------------------------------------------


async def test_d2_mobile_otp_expired_200_does_not_advance_state(
    client: AsyncClient, auth_headers: dict[str, str], session: AsyncSession
) -> None:
    session_id = await _start(client, auth_headers)
    await _submit_identity(client, auth_headers, session_id)
    await client.post(
        f"/api/v1/abha/registration-sessions/{session_id}/otp",
        json={"otp": OTP_VALID, "mobile_number": MOBILE_DIFFERENT},
        headers=auth_headers,
    )

    response = await client.post(
        f"/api/v1/abha/registration-sessions/{session_id}/mobile-otp",
        json={"otp": OTP_EXPIRED},
        headers=auth_headers,
    )
    assert response.status_code == 410
    assert response.json()["code"] == ErrorCode.ABHA_OTP_EXPIRED.value

    txn = await session.get(AbhaTransaction, session_id)
    assert txn is not None
    assert txn.state == "FAILED"  # not MOBILE_VERIFIED: the 200 did not mean success


async def test_invalid_otp_on_enrol_by_aadhaar_fails_the_session(
    client: AsyncClient, auth_headers: dict[str, str], session: AsyncSession
) -> None:
    session_id = await _start(client, auth_headers)
    await _submit_identity(client, auth_headers, session_id)

    response = await client.post(
        f"/api/v1/abha/registration-sessions/{session_id}/otp",
        json={"otp": OTP_INCORRECT, "mobile_number": MOBILE_SAME_AS_AADHAAR},
        headers=auth_headers,
    )
    assert response.status_code == 401
    assert response.json()["code"] == ErrorCode.ABHA_OTP_INCORRECT.value

    txn = await session.get(AbhaTransaction, session_id)
    assert txn is not None
    assert txn.state == "FAILED"
    assert txn.last_error_code == ErrorCode.ABHA_OTP_INCORRECT.value


async def test_live_mode_gateway_timeout_fails_session_with_persisted_row(
    client: AsyncClient,
    auth_headers: dict[str, str],
    app: FastAPI,
    test_settings: Settings,
    session: AsyncSession,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """A live ABDM call that raises before it ever produces an `AbdmResult` (httpx timeout here;
    equally a connect error, a non-2xx status, or a malformed body) must still land the
    transaction on FAILED with a persisted row, not leave it stuck at its prior state. Regression
    test for the service.py boundary around client.fetch_gateway_session_token /
    fetch_public_key_pem / the OTP calls: without it, this scenario raised
    httpx.ConnectTimeout straight out of the request handler and the row was never written."""
    session_id = await _start(client, auth_headers)

    live_settings = test_settings.model_copy(update={"abdm_mode": "live"})
    monkeypatch.setitem(app.dependency_overrides, settings_dep, lambda: live_settings)

    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectTimeout("simulated ABDM gateway timeout", request=request)

    real_init = httpx.AsyncClient.__init__

    def patched_init(self: httpx.AsyncClient, *args: object, **kwargs: object) -> None:
        kwargs["transport"] = httpx.MockTransport(handler)
        real_init(self, *args, **kwargs)

    monkeypatch.setattr(httpx.AsyncClient, "__init__", patched_init)

    response = await client.post(
        f"/api/v1/abha/registration-sessions/{session_id}/identity",
        json={"aadhaar_number": AADHAAR},
        headers=auth_headers,
    )
    assert response.status_code == 502
    assert response.json()["code"] == ErrorCode.ABHA_UPSTREAM_ERROR.value

    txn = await session.get(AbhaTransaction, session_id)
    assert txn is not None
    assert txn.state == "FAILED"
    assert txn.last_error_code == ErrorCode.ABHA_UPSTREAM_ERROR.value


async def test_live_mode_profile_fetch_failure_leaves_transaction_failed_with_persisted_row(
    client: AsyncClient,
    auth_headers: dict[str, str],
    app: FastAPI,
    test_settings: Settings,
    session: AsyncSession,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Same regression as test_live_mode_gateway_timeout_fails_session_with_persisted_row, for the
    `fetch_profile` boundary specifically: before this session, `client.get_profile`'s live branch
    raised a bare `NotImplementedError` and `service.fetch_profile` wrapped nothing in try/except,
    so any failure here escaped as an unhandled 500 with the transaction stuck at whatever state
    it was in (ENROLLED here), never FAILED, and no ABHA_SESSION_FAILED audit row.

    Reaches ENROLLED the normal way (stub mode, full walk, no mobile step needed), matching
    test_full_session_started_to_completed_no_mobile_step, then flips to live mode only for the
    `/profile` call, whose mock transport raises before producing any body at all.

    Asserts the PERSISTED row and a PERSISTED audit row, not the HTTP response alone, per this
    repo's rule that a request-scoped rollback can make a handler's return value lie about what
    actually landed in the database (the same trap D5/the Phase 5 ABDM adapter's `_fail` exists to
    avoid, and the exact reason `_fail` writes out of band).
    """
    session_id = await _start(client, auth_headers)
    await _submit_identity(client, auth_headers, session_id)
    otp_response = await client.post(
        f"/api/v1/abha/registration-sessions/{session_id}/otp",
        json={"otp": OTP_VALID, "mobile_number": MOBILE_SAME_AS_AADHAAR},
        headers=auth_headers,
    )
    assert otp_response.json()["data"]["state"] == "ENROLLED"

    live_settings = test_settings.model_copy(update={"abdm_mode": "live"})
    monkeypatch.setitem(app.dependency_overrides, settings_dep, lambda: live_settings)

    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectTimeout("simulated ABDM profile-fetch timeout", request=request)

    real_init = httpx.AsyncClient.__init__

    def patched_init(self: httpx.AsyncClient, *args: object, **kwargs: object) -> None:
        kwargs["transport"] = httpx.MockTransport(handler)
        real_init(self, *args, **kwargs)

    monkeypatch.setattr(httpx.AsyncClient, "__init__", patched_init)

    response = await client.get(
        f"/api/v1/abha/registration-sessions/{session_id}/profile", headers=auth_headers
    )
    assert response.status_code == 502
    assert response.json()["code"] == ErrorCode.ABHA_UPSTREAM_ERROR.value

    txn = await session.get(AbhaTransaction, session_id)
    assert txn is not None
    assert txn.state == "FAILED"
    assert txn.last_error_code == ErrorCode.ABHA_UPSTREAM_ERROR.value

    # Scoped to this session's own row, not "does an ABHA_SESSION_FAILED action exist anywhere in
    # the table": a shared test database running other tests' failure rows in sequence would make
    # an unscoped existence check pass even if THIS call never wrote one. _audit/_fail always put
    # {"session_id": ...} in the payload (see _audit and _fail's _write above), so filtering on
    # that value ties the assertion to this test's own persisted row.
    failed_audit_rows = (
        await session.execute(
            select(AuditEvent.payload).where(
                AuditEvent.action == AuditAction.ABHA_SESSION_FAILED.value
            )
        )
    ).scalars()
    assert any(session_id in payload for payload in failed_audit_rows)


async def test_live_mode_malformed_profile_body_fails_session_with_persisted_row(
    client: AsyncClient,
    auth_headers: dict[str, str],
    app: FastAPI,
    test_settings: Settings,
    session: AsyncSession,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """A live ABDM response that classifies as ok=True at the HTTP layer (status 200, ABHANumber
    present) can still fail once `mapping.profile_to_abha_identity` tries to use one of its other
    fields: `yearOfBirth` as a JSON number instead of a string, say, is a plausible contract drift
    a mocked stub can never produce but a real ABDM response could, and it raises AttributeError
    out of `dob.from_split_fields`'s `.strip()` call. Before this test, `fetch_profile` mapped the
    body only after already flushing `PROFILE_RETRIEVED` on the request session, so a mapping
    failure here left the row silently rolled back to its prior state on the request's rollback,
    never FAILED, never audited, the exact `_fail`-out-of-band trap this module's own docstring
    names. The mapping call now runs and is guarded before that flush."""
    session_id = await _start(client, auth_headers)
    await _submit_identity(client, auth_headers, session_id)
    otp_response = await client.post(
        f"/api/v1/abha/registration-sessions/{session_id}/otp",
        json={"otp": OTP_VALID, "mobile_number": MOBILE_SAME_AS_AADHAAR},
        headers=auth_headers,
    )
    assert otp_response.json()["data"]["state"] == "ENROLLED"

    live_settings = test_settings.model_copy(update={"abdm_mode": "live"})
    monkeypatch.setitem(app.dependency_overrides, settings_dep, lambda: live_settings)

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "ABHANumber": "91-7561-4088-0001",
                "name": "Sunita Devi",
                "yearOfBirth": 1991,  # malformed: a number, not the documented string
                "gender": "F",
                "mobile": "******0903",
                "kycVerified": True,
            },
        )

    real_init = httpx.AsyncClient.__init__

    def patched_init(self: httpx.AsyncClient, *args: object, **kwargs: object) -> None:
        kwargs["transport"] = httpx.MockTransport(handler)
        real_init(self, *args, **kwargs)

    monkeypatch.setattr(httpx.AsyncClient, "__init__", patched_init)

    response = await client.get(
        f"/api/v1/abha/registration-sessions/{session_id}/profile", headers=auth_headers
    )
    assert response.status_code == 502
    assert response.json()["code"] == ErrorCode.ABHA_UPSTREAM_ERROR.value

    txn = await session.get(AbhaTransaction, session_id)
    assert txn is not None
    assert txn.state == "FAILED"
    assert txn.last_error_code == ErrorCode.ABHA_UPSTREAM_ERROR.value

    failed_audit_rows = (
        await session.execute(
            select(AuditEvent.payload).where(
                AuditEvent.action == AuditAction.ABHA_SESSION_FAILED.value
            )
        )
    ).scalars()
    assert any(session_id in payload for payload in failed_audit_rows)


async def test_d5_no_phi_in_persisted_row_or_logs_live_mode(
    client: AsyncClient,
    auth_headers: dict[str, str],
    app: FastAPI,
    test_settings: Settings,
    session: AsyncSession,
    monkeypatch: pytest.MonkeyPatch,
    capsys: Any,
) -> None:
    """D5's live-mode sibling. test_d5_no_phi_in_persisted_row_or_logs below never overrides
    abdm_mode, so it only ever exercises client.get_profile's stub reply, the dict literal in
    client.py, never a real ABDM response parsed off the wire. This test is the one that actually
    covers the PHI risk D5 exists for: a real photo, under both recorded key names (profilePhoto
    and kycPhoto), arriving through a mocked HTTP response and the same response.json() parsing
    client.py's live branch uses, the same path a live account's face photo takes. Deliberately a
    separate test, not a parametrized
    merge of the two: keeping stub and live apart means a change that breaks live-mode redaction
    cannot hide behind the stub-mode test still passing.

    Kept as a same-property sibling, not folded into the one below, on the operator's explicit
    call: two tests over one parametrized test, so neither path can go quietly unexercised."""
    session_id = await _start(client, auth_headers)
    await _submit_identity(client, auth_headers, session_id)
    await client.post(
        f"/api/v1/abha/registration-sessions/{session_id}/otp",
        json={"otp": OTP_VALID, "mobile_number": MOBILE_SAME_AS_AADHAAR},
        headers=auth_headers,
    )

    live_settings = test_settings.model_copy(update={"abdm_mode": "live"})
    monkeypatch.setitem(app.dependency_overrides, settings_dep, lambda: live_settings)

    live_photo_marker = "/9j/live-fixture-jpeg-bytes-not-a-real-photo"

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "ABHANumber": "91-7561-4088-0001",
                "preferredAbhaAddress": "sunita.devi0001@sbx",
                "name": "Sunita Devi",
                "yearOfBirth": "1991",
                "monthOfBirth": "04",
                "dayOfBirth": "12",
                "gender": "F",
                "profilePhoto": live_photo_marker,
                "kycPhoto": live_photo_marker,
                "mobile": "******0903",
                "kycVerified": True,
            },
        )

    real_init = httpx.AsyncClient.__init__

    def patched_init(self: httpx.AsyncClient, *args: object, **kwargs: object) -> None:
        kwargs["transport"] = httpx.MockTransport(handler)
        real_init(self, *args, **kwargs)

    monkeypatch.setattr(httpx.AsyncClient, "__init__", patched_init)

    response = await client.get(
        f"/api/v1/abha/registration-sessions/{session_id}/profile", headers=auth_headers
    )
    assert response.status_code == 200
    assert response.json()["data"]["photo_url"] is None
    assert live_photo_marker not in response.text

    row = (
        (
            await session.execute(
                text(
                    "SELECT local_transaction_id, external_txn_id, state, last_error_code, "
                    "last_error_detail, abha_number, abha_address, abha_status, abha_type, "
                    "external_token_encrypted FROM abha_transactions "
                    "WHERE local_transaction_id = :id"
                ),
                {"id": session_id},
            )
        )
        .mappings()
        .one()
    )
    row_text = " ".join(str(v) for k, v in row.items() if k != "external_token_encrypted")
    assert "/9j/" not in row_text

    audit_rows = list((await session.execute(select(AuditEvent.payload))).scalars())
    for payload in audit_rows:
        assert "/9j/" not in payload

    captured = capsys.readouterr()
    log_output = captured.out + captured.err
    assert "/9j/" not in log_output


# ---------------------------------------------------------------------------
# Illegal transitions: 409 SAMD-ABHA-2002, never a silent no-op
# ---------------------------------------------------------------------------


async def test_skipping_identity_is_409(client: AsyncClient, auth_headers: dict[str, str]) -> None:
    session_id = await _start(client, auth_headers)

    response = await client.post(
        f"/api/v1/abha/registration-sessions/{session_id}/otp",
        json={"otp": OTP_VALID, "mobile_number": MOBILE_SAME_AS_AADHAAR},
        headers=auth_headers,
    )
    assert response.status_code == 409
    assert response.json()["code"] == ErrorCode.ABHA_INVALID_STATE.value


async def test_repeating_identity_submission_is_409(
    client: AsyncClient, auth_headers: dict[str, str]
) -> None:
    session_id = await _start(client, auth_headers)
    await _submit_identity(client, auth_headers, session_id)

    response = await client.post(
        f"/api/v1/abha/registration-sessions/{session_id}/identity",
        json={"aadhaar_number": AADHAAR},
        headers=auth_headers,
    )
    assert response.status_code == 409
    assert response.json()["code"] == ErrorCode.ABHA_INVALID_STATE.value


async def test_profile_before_enrollment_is_409(
    client: AsyncClient, auth_headers: dict[str, str]
) -> None:
    session_id = await _start(client, auth_headers)

    response = await client.get(
        f"/api/v1/abha/registration-sessions/{session_id}/profile", headers=auth_headers
    )
    assert response.status_code == 409
    assert response.json()["code"] == ErrorCode.ABHA_INVALID_STATE.value


async def test_unknown_session_id_is_404(client: AsyncClient, auth_headers: dict[str, str]) -> None:
    response = await client.get(
        "/api/v1/abha/registration-sessions/does-not-exist", headers=auth_headers
    )
    assert response.status_code == 404
    assert response.json()["code"] == ErrorCode.ABHA_SESSION_NOT_FOUND.value


async def test_session_from_another_facility_is_404(
    client: AsyncClient, auth_headers: dict[str, str], other_facility_headers: dict[str, str]
) -> None:
    session_id = await _start(client, auth_headers)
    response = await client.get(
        f"/api/v1/abha/registration-sessions/{session_id}", headers=other_facility_headers
    )
    assert response.status_code == 404


# ---------------------------------------------------------------------------
# D5: no Aadhaar, OTP, token, or photo bytes in any persisted row or log line
# ---------------------------------------------------------------------------


async def test_d5_no_phi_in_persisted_row_or_logs(
    client: AsyncClient,
    auth_headers: dict[str, str],
    session: AsyncSession,
    capsys: Any,
) -> None:
    session_id = await _start(client, auth_headers)
    await _submit_identity(client, auth_headers, session_id)
    await client.post(
        f"/api/v1/abha/registration-sessions/{session_id}/otp",
        json={"otp": OTP_VALID, "mobile_number": MOBILE_SAME_AS_AADHAAR},
        headers=auth_headers,
    )
    await client.get(
        f"/api/v1/abha/registration-sessions/{session_id}/profile", headers=auth_headers
    )

    forbidden_substrings = [
        AADHAAR,
        OTP_VALID,
        "stub-x-token",  # the plaintext X-token value client.py mints
        "/9j/",  # base64 JPEG magic-byte prefix: proves no photo bytes leaked, not just no Aadhaar
    ]

    # Raw SQL, not the ORM: the ORM's EncryptedText column_expression would decrypt
    # external_token_encrypted back to plaintext on SELECT, which would make this check pass even
    # if the column held the plaintext token, the exact failure mode this test exists to catch.
    row = (
        (
            await session.execute(
                text(
                    "SELECT local_transaction_id, external_txn_id, state, last_error_code, "
                    "last_error_detail, abha_number, abha_address, abha_status, abha_type, "
                    "external_token_encrypted FROM abha_transactions "
                    "WHERE local_transaction_id = :id"
                ),
                {"id": session_id},
            )
        )
        .mappings()
        .one()
    )

    # external_token_encrypted is bytea ciphertext or NULL; never compare it as text, only confirm
    # it does not literally contain the plaintext token bytes (it should be NULL here regardless,
    # since the flow completed and service.py clears it, but this is the direct proof either way).
    raw_token_bytes = row["external_token_encrypted"]
    if raw_token_bytes is not None:
        assert b"stub-x-token" not in bytes(raw_token_bytes)

    row_text = " ".join(str(v) for k, v in row.items() if k != "external_token_encrypted")
    for forbidden in forbidden_substrings:
        assert forbidden not in row_text, f"{forbidden!r} leaked into abha_transactions row"

    audit_rows = list((await session.execute(select(AuditEvent.payload))).scalars())
    sync_log_rows = list((await session.execute(select(SyncLogEntry.message))).scalars())
    for forbidden in forbidden_substrings:
        for payload in audit_rows:
            assert forbidden not in payload, f"{forbidden!r} leaked into an audit_events payload"
        for message in sync_log_rows:
            if message:
                assert forbidden not in message, f"{forbidden!r} leaked into a sync_log message"

    captured = capsys.readouterr()
    log_output = captured.out + captured.err
    for forbidden in forbidden_substrings:
        assert forbidden not in log_output, f"{forbidden!r} leaked into a log line"


async def test_d5_token_is_encrypted_at_rest_while_present(
    client: AsyncClient, auth_headers: dict[str, str], session: AsyncSession
) -> None:
    """The test above only proves the column is empty once a session reaches COMPLETED
    (service.py clears it there). That never exercises the actual encryption boundary: a column
    that is always NULL when checked is encrypted at rest by definition, vacuously. This proves
    the real claim, while the token is genuinely present (between enrollment and the /profile
    call, which is the one step that clears it): the raw column holds ciphertext, not the
    plaintext `stub-x-token-...` string client.py mints, and the ORM's EncryptedText decrypt path
    recovers exactly that plaintext back, closing the loop from "these bytes look different" to
    "this is genuinely the pgcrypto-encrypted form of that exact string."
    """
    session_id = await _start(client, auth_headers)
    await _submit_identity(client, auth_headers, session_id)
    await client.post(
        f"/api/v1/abha/registration-sessions/{session_id}/otp",
        json={"otp": OTP_VALID, "mobile_number": MOBILE_SAME_AS_AADHAAR},
        headers=auth_headers,
    )
    # Deliberately not calling /profile here: that call clears external_token_encrypted. The
    # token must still be present at this point or this test proves nothing.

    row = (
        (
            await session.execute(
                text(
                    "SELECT external_token_encrypted FROM abha_transactions "
                    "WHERE local_transaction_id = :id"
                ),
                {"id": session_id},
            )
        )
        .mappings()
        .one()
    )

    raw_token_bytes = row["external_token_encrypted"]
    assert raw_token_bytes is not None, "token must be present at this point in the flow"
    assert b"stub-x-token" not in bytes(raw_token_bytes)

    # The ORM read, through EncryptedText, must recover exactly the plaintext the raw bytes above
    # are not: proves the raw bytes are round-trippable ciphertext, not merely different-looking.
    txn = await session.get(AbhaTransaction, session_id)
    assert txn is not None
    assert txn.external_token_encrypted is not None
    assert txn.external_token_encrypted.startswith("stub-x-token")
