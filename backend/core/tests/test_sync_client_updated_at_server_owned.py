"""client_updated_at is a server-owned column: stored from the sync envelope, never accepted from a
payload, present on every synced table. Every test asserts persisted rows, not just the ack."""

from __future__ import annotations

from httpx import AsyncClient
from sqlalchemy import func, inspect, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.errors import ErrorCode
from app.services.sync import TABLE_REGISTRY, _attr_map
from tests.test_sync import patient_record, push


async def test_a_payload_cannot_set_client_updated_at(
    client: AsyncClient, auth_headers: dict[str, str], session: AsyncSession
) -> None:
    record = patient_record()
    record["data"]["client_updated_at"] = "2030-01-01T00:00:00.000Z"
    response = await push(client, auth_headers, [record])
    result = response.json()["data"]["results"][0]

    assert result["status"] == "rejected"
    assert result["code"] == ErrorCode.SYNC_RECORD_INVALID.value
    assert result["retry_class"] == "TERMINAL"
    assert result["message"] == "client_updated_at: unexpected field."
    assert (
        await session.scalar(select(func.count()).select_from(TABLE_REGISTRY["patients"].model))
        == 0
    )


def test_the_registry_has_nineteen_synced_tables_and_each_has_the_server_owned_revision() -> None:
    assert len(TABLE_REGISTRY) == 19
    for name, spec in TABLE_REGISTRY.items():
        assert "client_updated_at" in {c.key for c in inspect(spec.model).column_attrs}, name
        assert "client_updated_at" not in _attr_map(spec), name  # never client-writable
