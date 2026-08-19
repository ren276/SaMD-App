"""Live-mode (`ABDM_MODE=live`) network wiring: request shape against the real Postman ground
truth, and the gateway session token cache's single-flight refresh. Every call here goes through
a local `httpx.MockTransport`, never a real ABDM host.
"""

from __future__ import annotations

import asyncio

import httpx
import pytest

import abdm_adapter.client as client_module
from abdm_adapter.client import enrol_by_aadhaar, fetch_gateway_session_token, send_otp


@pytest.fixture(autouse=True)
def _reset_session_cache() -> None:
    """The token cache is module-level, in-memory state (by design: never DB, never disk). Each
    test must start from a clean cache so one test's cached token cannot leak into the next."""
    client_module._cached_token = None
    client_module._cached_token_expires_at = None
    yield
    client_module._cached_token = None
    client_module._cached_token_expires_at = None


def _patch_transport(monkeypatch: pytest.MonkeyPatch, handler: object) -> None:
    real_init = httpx.AsyncClient.__init__

    def patched_init(self: httpx.AsyncClient, *args: object, **kwargs: object) -> None:
        kwargs["transport"] = httpx.MockTransport(handler)  # type: ignore[arg-type]
        real_init(self, *args, **kwargs)  # type: ignore[arg-type]

    monkeypatch.setattr(httpx.AsyncClient, "__init__", patched_init)


async def test_send_otp_live_mode_matches_postman_request_shape(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: dict[str, httpx.Request] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["request"] = request
        return httpx.Response(200, json={"txnId": "real-txn-id", "message": "OTP sent"})

    _patch_transport(monkeypatch, handler)

    result = await send_otp(
        mode="live",
        txn_id="",
        scope=["abha-enrol"],
        login_hint="aadhaar",
        encrypted_login_id="ZW5jcnlwdGVkLWFhZGhhYXI=",
        otp_system="aadhaar",
        base_url="https://abhasbx.abdm.gov.in",
    )

    assert result.ok is True
    request = captured["request"]
    assert str(request.url) == "https://abhasbx.abdm.gov.in/abha/api/v3/enrollment/request/otp"
    assert "REQUEST-ID" in request.headers
    assert "TIMESTAMP" in request.headers
    # Phase A finding: no Authorization/X-CM-ID header on this endpoint in any recorded example.
    assert "Authorization" not in request.headers
    import json as _json

    body = _json.loads(request.content)
    assert body == {
        "txnId": "",
        "scope": ["abha-enrol"],
        "loginHint": "aadhaar",
        "loginId": "ZW5jcnlwdGVkLWFhZGhhYXI=",
        "otpSystem": "aadhaar",
    }


async def test_enrol_by_aadhaar_live_mode_never_sends_plaintext_otp(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: dict[str, httpx.Request] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["request"] = request
        return httpx.Response(
            200,
            json={
                "message": "Account created successfully",
                "txnId": "real-txn-id",
                "tokens": {"token": "x-token", "expiresIn": 1800},
                "ABHAProfile": {"ABHANumber": "91-0000-0000-0001", "mobile": "******1234"},
                "isNew": True,
            },
        )

    _patch_transport(monkeypatch, handler)

    result = await enrol_by_aadhaar(
        mode="live",
        txn_id="real-txn-id",
        otp_plain="123456",
        encrypted_otp="ZW5jcnlwdGVkLW90cA==",
        communication_mobile="9876543210",
        consent_code="abha-enrollment",
        consent_version="1.4",
        base_url="https://abhasbx.abdm.gov.in",
    )

    assert result.ok is True
    request = captured["request"]
    assert str(request.url) == (
        "https://abhasbx.abdm.gov.in/abha/api/v3/enrollment/enrol/byAadhaar"
    )
    import json as _json

    body = _json.loads(request.content)
    assert body["authData"]["otp"]["otpValue"] == "ZW5jcnlwdGVkLW90cA=="
    assert "123456" not in request.content.decode()
    assert body["authData"]["otp"]["mobile"] == "9876543210"
    assert body["consent"] == {"code": "abha-enrollment", "version": "1.4"}


async def test_fetch_gateway_session_token_caches_across_calls(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    call_count = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal call_count
        call_count += 1
        return httpx.Response(200, json={"accessToken": "token-A", "expiresIn": 1800})

    _patch_transport(monkeypatch, handler)

    first = await fetch_gateway_session_token(
        mode="live",
        session_url="https://dev.abdm.gov.in/api/hiecm/gateway/v3/sessions",
        client_id="cid",
        client_secret="csecret",
    )
    second = await fetch_gateway_session_token(
        mode="live",
        session_url="https://dev.abdm.gov.in/api/hiecm/gateway/v3/sessions",
        client_id="cid",
        client_secret="csecret",
    )

    assert first == "token-A"
    assert second == "token-A"
    assert call_count == 1


async def test_fetch_gateway_session_token_single_flights_concurrent_refresh(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Ten callers racing an empty cache must trigger exactly one POST to the sessions endpoint,
    not ten: the single-flight lock, not luck, is what proves this. The handler blocks on the
    first call until the other nine callers have had a chance to pile up behind the lock, so the
    overlap is forced rather than hoping the event loop happens to interleave that way."""
    call_count = 0
    entered = asyncio.Event()
    release = asyncio.Event()

    async def handler(request: httpx.Request) -> httpx.Response:
        nonlocal call_count
        call_count += 1
        entered.set()
        await release.wait()
        return httpx.Response(200, json={"accessToken": "token-B", "expiresIn": 1800})

    _patch_transport(monkeypatch, handler)

    gathered = asyncio.gather(
        *[
            fetch_gateway_session_token(
                mode="live",
                session_url="https://dev.abdm.gov.in/api/hiecm/gateway/v3/sessions",
                client_id="cid",
                client_secret="csecret",
            )
            for _ in range(10)
        ]
    )
    task = asyncio.ensure_future(gathered)
    await entered.wait()
    for _ in range(10):
        await asyncio.sleep(0)
    release.set()
    results = await task

    assert all(token == "token-B" for token in results)
    assert call_count == 1


async def test_fetch_gateway_session_token_refreshes_after_expiry(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    call_count = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal call_count
        call_count += 1
        # expiresIn=61 minus the 60s refresh skew leaves ~1s of validity, so the second call
        # (after we force time forward) must refresh rather than reuse the cache.
        return httpx.Response(200, json={"accessToken": f"token-{call_count}", "expiresIn": 61})

    _patch_transport(monkeypatch, handler)

    first = await fetch_gateway_session_token(
        mode="live",
        session_url="https://dev.abdm.gov.in/api/hiecm/gateway/v3/sessions",
        client_id="cid",
        client_secret="csecret",
    )
    assert first == "token-1"
    assert call_count == 1

    # Force the cached expiry into the past to simulate real elapsed time without a real sleep.
    client_module._cached_token_expires_at = client_module.datetime.now(client_module.UTC).replace(
        year=2000
    )

    second = await fetch_gateway_session_token(
        mode="live",
        session_url="https://dev.abdm.gov.in/api/hiecm/gateway/v3/sessions",
        client_id="cid",
        client_secret="csecret",
    )
    assert second == "token-2"
    assert call_count == 2
