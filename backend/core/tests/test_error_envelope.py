"""The error envelope. api-contract.md sections 0.6 and 9.1.

These tests exist because the envelope is a contract the Android client branches on. A change
here is a change to shipped app builds that stay in the field for years.
"""

from __future__ import annotations

from httpx import AsyncClient

from app.errors import PROBLEM_CONTENT_TYPE, ErrorCode, http_status_for, title_for

REQUIRED_MEMBERS = {
    "type",
    "title",
    "status",
    "detail",
    "instance",
    "code",
    "request_id",
    "timestamp",
}


async def test_unknown_route_is_rfc9457_shaped(client: AsyncClient) -> None:
    response = await client.get("/api/v1/does-not-exist")

    assert response.status_code == 404
    assert response.headers["content-type"].startswith(PROBLEM_CONTENT_TYPE)

    body = response.json()
    assert REQUIRED_MEMBERS <= set(body)
    assert body["code"] == ErrorCode.SYS_NOT_FOUND.value
    assert body["status"] == 404
    assert body["instance"] == "/api/v1/does-not-exist"
    # errors[] is absent, not null, when there are no field errors.
    assert "errors" not in body


async def test_validation_failure_carries_field_errors(client: AsyncClient) -> None:
    response = await client.post("/api/v1/auth/login", json={"worker_id": "nope"})

    assert response.status_code == 422
    body = response.json()
    assert body["code"] == ErrorCode.PAT_VALIDATION_FAILED.value
    assert isinstance(body["errors"], list)
    assert body["errors"]
    assert {"field", "message"} <= set(body["errors"][0])


async def test_validation_detail_never_echoes_the_submitted_value(client: AsyncClient) -> None:
    """detail must describe the failure, never the data that caused it (PHI rule)."""
    secret = "9876543210"
    response = await client.post(
        "/api/v1/auth/login",
        json={"worker_id": secret, "pin": "1", "device_id": "d"},
    )

    body = response.json()
    assert secret not in body["detail"]
    for error in body.get("errors", []):
        assert secret not in error["message"]


async def test_extra_field_is_forbidden(client: AsyncClient) -> None:
    """extra="forbid" on every request model, without exception."""
    response = await client.post(
        "/api/v1/auth/login",
        json={
            "worker_id": "949ad656774570f6",
            "pin": "482915",
            "device_id": "device-abc123",
            "unexpected": "value",
        },
    )
    assert response.status_code == 422
    assert response.json()["code"] == ErrorCode.PAT_VALIDATION_FAILED.value


async def test_error_body_request_id_matches_the_response_header(client: AsyncClient) -> None:
    inbound = "11111111-2222-4333-8444-555555555555"
    response = await client.get("/api/v1/nope", headers={"X-Request-ID": inbound})

    assert response.headers["X-Request-ID"] == inbound
    assert response.json()["request_id"] == inbound


async def test_missing_bearer_token_is_auth_1003(client: AsyncClient) -> None:
    response = await client.get("/api/v1/auth/me")
    assert response.status_code == 401
    assert response.json()["code"] == ErrorCode.AUTH_TOKEN_INVALID.value


def test_every_code_has_a_status_and_a_title() -> None:
    """Guards against a code added to the enum but never registered."""
    for code in ErrorCode:
        assert http_status_for(code) >= 400
        assert title_for(code)


def test_codes_are_unique() -> None:
    values = [code.value for code in ErrorCode]
    assert len(values) == len(set(values))


async def test_success_timestamps_are_utc_with_millisecond_precision(
    client: AsyncClient, auth_headers: dict[str, str]
) -> None:
    """api-contract.md section 0.3, asserted on a value that lands exactly on the second.

    Python's isoformat() omits the fractional part when it is zero, which would ship a shape the
    Android formatter cannot parse. The envelope normalises instead.
    """
    from tests.test_patients import create

    response = await create(client, auth_headers)
    data = response.json()["data"]

    for field in ("created_at", "updated_at"):
        assert data[field].endswith("Z"), data[field]
        assert len(data[field]) == len("2026-08-16T09:40:00.000Z"), data[field]

    assert response.json()["meta"]["timestamp"].endswith("Z")
