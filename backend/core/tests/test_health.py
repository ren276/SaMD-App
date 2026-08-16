"""GET /health. api-contract.md section 1."""

from __future__ import annotations

from httpx import AsyncClient


async def test_health_returns_bare_object_not_envelope(client: AsyncClient) -> None:
    response = await client.get("/health")
    assert response.status_code == 200

    body = response.json()
    # The one endpoint with no envelope, on purpose: healthcheck tooling should not have to
    # parse one.
    assert "success" not in body
    assert "meta" not in body

    assert body["status"] == "ok"
    assert body["database"] == "ok"
    assert body["kernel"] == "unknown"  # No kernel proxy until Phase 3.
    assert body["abdm_mode"] in {"stub", "live"}
    assert body["environment"] in {"dev", "staging", "prod"}
    assert isinstance(body["uptime_seconds"], int)


async def test_health_timestamp_is_utc_with_explicit_z(client: AsyncClient) -> None:
    body = (await client.get("/health")).json()
    timestamp = body["timestamp"]
    assert timestamp.endswith("Z")
    # Millisecond precision, per api-contract.md section 0.3.
    assert len(timestamp) == len("2026-08-16T10:00:00.000Z")


async def test_health_needs_no_authentication(client: AsyncClient) -> None:
    response = await client.get("/health")
    assert response.status_code == 200


async def test_health_echoes_request_id_header(client: AsyncClient) -> None:
    inbound = "3f2b7c48-9a1e-4c2d-8b55-0f1a2d3e4c5b"
    response = await client.get("/health", headers={"X-Request-ID": inbound})
    assert response.headers["X-Request-ID"] == inbound


async def test_health_mints_request_id_when_inbound_is_not_uuid4(client: AsyncClient) -> None:
    response = await client.get("/health", headers={"X-Request-ID": "not-a-uuid"})
    assert response.headers["X-Request-ID"] != "not-a-uuid"
    assert len(response.headers["X-Request-ID"]) == 36
