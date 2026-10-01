"""Re-syncing an existing row: the last-write-wins comparator, the stored client_updated_at, and
the containment of an unexpected error to one record.

Master compared the incoming client_updated_at with a DATA column (updated_at, created_at) and
four tables (kernel_reports, evaluate_reports, medication_lines, referrals) had neither, so a
second write of an existing id raised AttributeError, escaped the per-record savepoint and
answered 500 for the whole batch. Every test asserts persisted rows, not just the ack.
"""

from __future__ import annotations

import re
from datetime import UTC, datetime, timedelta
from typing import Any

import httpx
import pytest
from fastapi import FastAPI
from httpx import AsyncClient
from sqlalchemy import (
    CheckConstraint,
    DateTime,
    Float,
    Integer,
    String,
    Text,
    func,
    select,
    text,
)
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.exc import OperationalError
from sqlalchemy.ext.asyncio import AsyncSession

from app.errors import ErrorCode
from app.models.sync import SyncBatch
from app.services import sync as sync_service
from app.services.sync import TABLE_REGISTRY, _attr_map, _resolve_write
from tests.conftest import TEST_FACILITY_ID
from tests.test_patients import PATIENT_ID
from tests.test_sync import (
    CASE_ID,
    ENCOUNTER_ID,
    ailment_record,
    batch_body,
    case_record_record,
    consultation_record,
    encounter_record,
    kernel_report_record,
    observation_record,
    patient_record,
    push,
)

T0 = datetime(2026, 8, 16, 9, 0, tzinfo=UTC)
_MUTATED_TIME = datetime(2027, 1, 1, tzinfo=UTC)
CONSULTATION_ID = "con-resync-1"
PRESCRIPTION_ID = "rx-resync-1"


def iso(moment: datetime) -> str:
    return moment.strftime("%Y-%m-%dT%H:%M:%S.000Z")


# --- the pure decision, every rule branch ---

_STORED = T0
_BEFORE = T0 - timedelta(hours=1)
_AFTER = T0 + timedelta(hours=1)


@pytest.mark.parametrize(
    ("stored_ts", "stored_version", "incoming", "base_version", "expected"),
    [
        # rule 1: an exact replay is stale, and it outranks the conflict check
        (_STORED, 3, _STORED, None, "stale"),
        (_STORED, 3, _STORED, 3, "stale"),
        (_STORED, 3, _STORED, 1, "stale"),
        # rule 2: base_version present and different
        (_STORED, 3, _AFTER, 1, "conflict"),
        (_STORED, 3, _BEFORE, 99, "conflict"),
        # rule 3: base_version matches, applied whatever the clocks say (clock skew)
        (_STORED, 3, _AFTER, 3, "apply"),
        (_STORED, 3, _BEFORE, 3, "apply"),
        (None, 3, _BEFORE, 3, "apply"),
        # rule 4: no base_version, last-write-wins; a stored NULL is older than anything
        (_STORED, 3, _AFTER, None, "apply"),
        (_STORED, 3, _BEFORE, None, "conflict"),
        (None, 3, _BEFORE, None, "apply"),
        (None, 3, _AFTER, None, "apply"),
    ],
)
def test_the_decision_for_every_rule_branch(
    stored_ts: datetime | None,
    stored_version: int,
    incoming: datetime,
    base_version: int | None,
    expected: str,
) -> None:
    assert _resolve_write(stored_ts, stored_version, incoming, base_version) == expected


# --- building a valid record for any of the 19 tables ---

# What each table needs to exist first. Seeded parents are written once, then the table under test.
_NEEDS: dict[str, tuple[str, ...]] = {
    "patients": (),
    "encounters": ("patients",),
    "consultations": ("patients", "encounters"),
    "attachments": ("patients", "encounters", "consultations"),
    "observations": ("patients", "encounters"),
    "ailments": ("patients", "encounters"),
    "medical_history_items": ("patients",),
    "allergies": ("patients",),
    "family_history_entries": ("patients",),
    "social_histories": ("patients",),
    "medication_entries": ("patients",),
    "case_records": ("patients", "encounters"),
    "kernel_reports": ("patients", "encounters", "case_records"),
    "evaluate_reports": ("patients", "encounters", "case_records"),
    "diagnosis_feedback": ("patients", "encounters", "case_records"),
    "prescriptions": ("patients", "encounters", "case_records"),
    "medication_lines": ("patients", "encounters", "case_records", "prescriptions"),
    "referrals": ("patients", "encounters", "case_records"),
    "abha_profiles": (),
}
_FK_TARGET_IDS = {
    "patients.id": PATIENT_ID,
    "encounters.id": ENCOUNTER_ID,
    "case_records.id": CASE_ID,
    "consultations.id": CONSULTATION_ID,
    "prescriptions.id": PRESCRIPTION_ID,
}
_OWN_ID = {
    "patients": PATIENT_ID,
    "encounters": ENCOUNTER_ID,
    "case_records": CASE_ID,
    "consultations": CONSULTATION_ID,
    "prescriptions": PRESCRIPTION_ID,
    "social_histories": PATIENT_ID,  # keyed on the patient
    "abha_profiles": "91123456789012",
}
_HAND_BUILT = {
    "patients": lambda rid, ts, bv: patient_record(rid, client_updated_at=ts, base_version=bv),
    "encounters": lambda rid, ts, bv: encounter_record(rid, client_updated_at=ts, base_version=bv),
    "consultations": lambda rid, ts, bv: consultation_record(
        rid, client_updated_at=ts, base_version=bv
    ),
}


def _enum_first_values(table: Any) -> dict[str, str]:
    first: dict[str, str] = {}
    for constraint in table.constraints:
        if isinstance(constraint, CheckConstraint):
            match = re.match(r"(\w+) IN \((.*)\)", str(constraint.sqltext))
            if match and match.group(1) != "sync_state":
                first[match.group(1)] = re.findall(r"'([^']*)'", match.group(2))[0]
    return first


def _is_bool(column: Any) -> bool:
    try:
        return bool(column.type.python_type is bool)
    except NotImplementedError:  # a custom type such as EncryptedText: text, not a boolean
        return False


def _generic_data(table_name: str, rid: str, ts: str) -> dict[str, Any]:
    """Required client-writable columns filled from the model itself, so a table needs no
    hand-written builder. Content is irrelevant to the comparator; validity is not."""
    spec = TABLE_REGISTRY[table_name]
    table = spec.model.__table__
    enums = _enum_first_values(table)
    data: dict[str, Any] = {}
    for key, attr in _attr_map(spec).items():
        column = table.c[attr]
        if column.nullable or column.default is not None or column.server_default is not None:
            continue
        if attr == spec.pk_attr:
            continue
        targets = [fk.target_fullname for fk in column.foreign_keys]
        if targets:
            data[key] = _FK_TARGET_IDS[targets[0]]
        elif attr in enums:
            data[key] = enums[attr]
        elif key == "sending_phc_id":
            data[key] = TEST_FACILITY_ID
        elif isinstance(column.type, DateTime):
            data[key] = ts
        elif isinstance(column.type, JSONB):
            data[key] = {"v": 1}
        elif isinstance(column.type, Integer):
            data[key] = 1
        elif isinstance(column.type, Float):
            data[key] = 0.5
        elif _is_bool(column):
            data[key] = True
        else:
            data[key] = "v1"
    return data


def _record(table_name: str, rid: str, ts: str, base_version: int | None) -> dict[str, Any]:
    if table_name in _HAND_BUILT:
        return _HAND_BUILT[table_name](rid, ts, base_version)
    if table_name == "case_records":
        record = case_record_record(rid, client_updated_at=ts)
    elif table_name == "observations":
        record = observation_record(rid, client_updated_at=ts)
    elif table_name == "ailments":
        record = ailment_record(rid, client_updated_at=ts)
    elif table_name == "kernel_reports":
        record = kernel_report_record(rid, client_updated_at=ts)
    else:
        record = {
            "table": table_name,
            "op": "upsert",
            "id": rid,
            "client_updated_at": ts,
            "base_version": None,
            "data": _generic_data(table_name, rid, ts),
        }
    record["base_version"] = base_version
    return record


def _mutate(table_name: str, record: dict[str, Any], n: int) -> str:
    """Change one data column to a recognisable value and return its wire key. Columns already in
    the record are preferred; an optional text column is added when the record has none (a table
    whose required columns are all timestamps or keys). Expected value: _expected_value."""
    spec = TABLE_REGISTRY[table_name]
    table = spec.model.__table__
    enums = _enum_first_values(table)
    data = record["data"]
    candidates = []
    for key, attr in _attr_map(spec).items():
        column = table.c[attr]
        if (
            column.foreign_keys
            or attr in enums
            or attr == spec.pk_attr
            or key in ("created_at", "sending_phc_id")
        ):
            continue
        candidates.append((key in data, key, column))
    candidates.sort(key=lambda item: not item[0])  # present first, stable otherwise

    def is_text(column: Any) -> bool:
        return isinstance(column.type, String | Text) or "Encrypted" in type(column.type).__name__

    for _, key, column in candidates:
        if isinstance(column.type, JSONB):
            data[key] = {"v": n}
            return key
    for _, key, column in candidates:
        if is_text(column) and not key.endswith("_at"):
            data[key] = f"v{n}"
            return key
    for _, key, column in candidates:
        if isinstance(column.type, Integer | Float):
            data[key] = n
            return key
    for _, key, column in candidates:
        if isinstance(column.type, DateTime):
            data[key] = iso(_MUTATED_TIME + timedelta(days=n))
            return key
    raise AssertionError(f"no mutable column for {table_name}")


async def _seed_parents(client: AsyncClient, headers: dict[str, str], table_name: str) -> None:
    records: list[dict[str, Any]] = []
    for parent in _NEEDS[table_name]:
        records.append(_record(parent, _OWN_ID[parent], iso(T0), None))
    if records:
        response = await push(client, headers, records)
        assert response.json()["data"]["rejected"] == 0, response.json()


async def _send(
    client: AsyncClient, headers: dict[str, str], record: dict[str, Any]
) -> dict[str, Any]:
    response = await push(client, headers, [record])
    assert response.status_code == 200, response.text
    return response.json()["data"]["results"][0]


async def _row(session: AsyncSession, table_name: str, rid: str) -> Any:
    spec = TABLE_REGISTRY[table_name]
    pk = getattr(spec.model, spec.pk_attr)
    stmt = select(spec.model).where(pk == rid).execution_options(populate_existing=True)
    return (await session.execute(stmt)).scalar_one()


def _as_stored(table_name: str, key: str, value: Any) -> Any:
    """A wire value as the database returns it (an ISO string becomes an aware datetime)."""
    spec = TABLE_REGISTRY[table_name]
    column = spec.model.__table__.c[_attr_map(spec)[key]]
    if isinstance(column.type, DateTime) and isinstance(value, str):
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    return value


def _persisted(row: Any, table_name: str, key: str) -> Any:
    return getattr(row, _attr_map(TABLE_REGISTRY[table_name])[key])


# --- every rule, over all 19 tables ---


@pytest.mark.parametrize("table_name", list(TABLE_REGISTRY))
async def test_every_comparator_rule_holds_on_every_synced_table(
    client: AsyncClient, auth_headers: dict[str, str], session: AsyncSession, table_name: str
) -> None:
    await _seed_parents(client, auth_headers, table_name)
    rid = _OWN_ID.get(table_name, f"rec-{table_name}")

    # 1. No existing row: inserted, and the envelope timestamp is stored as the sync revision.
    first = _record(table_name, rid, iso(T0), None)
    original = {k: (dict(v) if isinstance(v, dict) else v) for k, v in first["data"].items()}
    result = await _send(client, auth_headers, first)
    assert result["status"] == "applied", (table_name, result)
    row = await _row(session, table_name, rid)
    assert (row.server_version, row.client_updated_at) == (1, T0)

    mutated_key = _mutate(table_name, _record(table_name, rid, iso(T0), None), 99)

    def changed(base: int | None, ts: datetime, n: int) -> dict[str, Any]:
        record = _record(table_name, rid, iso(ts), base)
        assert _mutate(table_name, record, n) == mutated_key
        return record

    async def assert_untouched(step: str, version: int, ts: datetime) -> None:
        row = await _row(session, table_name, rid)
        assert (row.server_version, row.client_updated_at) == (version, ts), step
        assert _persisted(row, table_name, mutated_key) == _as_stored(
            table_name, mutated_key, original.get(mutated_key)
        ), step

    # 2. An exact replay (same timestamp) is stale, with any base_version, and changes nothing.
    for base in (None, 1, 99):
        replay = _record(table_name, rid, iso(T0), base)
        result = await _send(client, auth_headers, replay)
        assert result["status"] == "stale", (table_name, base, result)
        assert result["server_version"] == 1
    # A replay carrying different content still reads as stale: the revision is what matched.
    await assert_untouched("after replays", 1, T0)

    # 3. No base_version and an OLDER timestamp: conflict, the stored row is unchanged.
    result = await _send(client, auth_headers, changed(None, T0 - timedelta(hours=1), 2))
    assert result["status"] == "conflict", (table_name, result)
    assert "server_state" in result
    await assert_untouched("after older write without base_version", 1, T0)

    # 4. base_version present and wrong: conflict, unchanged (even with a newer timestamp).
    result = await _send(client, auth_headers, changed(99, T0 + timedelta(hours=1), 2))
    assert result["status"] == "conflict", (table_name, result)
    await assert_untouched("after wrong base_version", 1, T0)

    # 5. No base_version and a NEWER timestamp: last-write-wins applies it.
    t1 = T0 + timedelta(hours=1)
    result = await _send(client, auth_headers, changed(None, t1, 2))
    assert result["status"] == "applied", (table_name, result)
    row = await _row(session, table_name, rid)
    assert (row.server_version, row.client_updated_at) == (2, t1)
    assert _persisted(row, table_name, mutated_key) == _expected_value(table_name, mutated_key, 2)

    # 6. Clock skew: base_version matches and the timestamp is OLDER than stored. Applied.
    t_skew = T0 - timedelta(hours=2)
    result = await _send(client, auth_headers, changed(2, t_skew, 3))
    assert result["status"] == "applied", (table_name, result)
    row = await _row(session, table_name, rid)
    assert (row.server_version, row.client_updated_at) == (3, t_skew)
    assert _persisted(row, table_name, mutated_key) == _expected_value(table_name, mutated_key, 3)

    # 7. Lost-ack update replay: the same content and timestamp as the write just applied, resent
    #    under a NEW batch_id with the base_version the device still holds (2, now behind 3).
    #    Stale, NOT conflict.
    result = await _send(client, auth_headers, changed(2, t_skew, 3))
    assert result["status"] == "stale", (table_name, result)
    assert result["server_version"] == 3
    row = await _row(session, table_name, rid)
    assert (row.server_version, row.client_updated_at) == (3, t_skew)

    # 8. A legacy row (stored NULL) counts as older than anything: an older-looking write applies.
    await session.execute(
        text(f"UPDATE {table_name} SET client_updated_at = NULL"),  # noqa: S608 - table from registry
    )
    await session.commit()
    t_legacy = T0 - timedelta(hours=5)
    result = await _send(client, auth_headers, changed(None, t_legacy, 4))
    assert result["status"] == "applied", (table_name, result)
    row = await _row(session, table_name, rid)
    assert (row.server_version, row.client_updated_at) == (4, t_legacy)


def _expected_value(table_name: str, key: str, n: int) -> Any:
    spec = TABLE_REGISTRY[table_name]
    column = spec.model.__table__.c[_attr_map(spec)[key]]
    if isinstance(column.type, JSONB):
        return {"v": n}
    if isinstance(column.type, Integer | Float):
        return n
    if isinstance(column.type, DateTime):
        return _MUTATED_TIME + timedelta(days=n)
    return f"v{n}"


# --- the two natural-key tables, a second writer ---


@pytest.mark.parametrize("table_name", ["social_histories", "abha_profiles"])
async def test_a_second_writer_without_base_version_and_an_older_timestamp_gets_conflict(
    client: AsyncClient, auth_headers: dict[str, str], session: AsyncSession, table_name: str
) -> None:
    """These tables are keyed on a natural key (patient_id, abha_id), so two devices can write the
    same row. The device that never received a server_version and is older must be told conflict,
    not synced, and the newer data must stay."""
    await _seed_parents(client, auth_headers, table_name)
    rid = _OWN_ID[table_name]
    newer = _record(table_name, rid, iso(T0 + timedelta(hours=1)), None)
    key = _mutate(table_name, newer, 2)
    assert (await _send(client, auth_headers, newer))["status"] == "applied"

    older = _record(table_name, rid, iso(T0), None)
    _mutate(table_name, older, 7)
    result = await _send(client, auth_headers, older)

    assert result["status"] == "conflict"
    row = await _row(session, table_name, rid)
    assert (row.server_version, row.client_updated_at) == (1, T0 + timedelta(hours=1))
    assert _persisted(row, table_name, key) == _expected_value(table_name, key, 2)


# --- re-assessment, the realistic trigger ---


@pytest.mark.parametrize("table_name", ["kernel_reports", "evaluate_reports"])
async def test_a_reassessment_of_a_case_updates_the_existing_report(
    client: AsyncClient, auth_headers: dict[str, str], session: AsyncSession, table_name: str
) -> None:
    """The device reuses the report's id for a case (REPLACE keyed on caseRecordId), so a second
    assessment re-pushes an existing id with the server_version it was acked with."""
    await _seed_parents(client, auth_headers, table_name)
    rid = f"rec-{table_name}"
    assert (await _send(client, auth_headers, _record(table_name, rid, iso(T0), None)))[
        "status"
    ] == "applied"

    second = _record(table_name, rid, iso(T0 + timedelta(minutes=30)), 1)
    key = _mutate(table_name, second, 2)
    result = await _send(client, auth_headers, second)

    assert result["status"] == "applied"
    row = await _row(session, table_name, rid)
    assert (row.server_version, row.client_updated_at) == (2, T0 + timedelta(minutes=30))
    assert _persisted(row, table_name, key) == _expected_value(table_name, key, 2)


# --- a resend of the SAME batch is answered from the store ---


async def test_the_same_batch_id_is_replayed_and_a_new_one_is_stale(
    client: AsyncClient, auth_headers: dict[str, str], session: AsyncSession
) -> None:
    await _seed_parents(client, auth_headers, "kernel_reports")
    record = _record("kernel_reports", "kr-replay", iso(T0), None)
    body = batch_body([record])
    first = await client.post("/api/v1/sync/push", json=body, headers=auth_headers)
    again = await client.post("/api/v1/sync/push", json=body, headers=auth_headers)
    assert first.json()["data"]["results"][0]["status"] == "applied"
    assert (
        again.json()["data"]["results"][0]["status"] == "applied"
    )  # the stored response, verbatim

    fresh = await _send(client, auth_headers, record)  # new batch_id, same content
    assert fresh["status"] == "stale"
    row = await _row(session, "kernel_reports", "kr-replay")
    assert row.server_version == 1


# --- the revision is stored from the envelope ---


async def test_the_stored_revision_comes_from_the_envelope_not_the_payload(
    client: AsyncClient, auth_headers: dict[str, str], session: AsyncSession
) -> None:
    record = patient_record(client_updated_at=iso(T0), updated_at="2031-01-01T00:00:00.000Z")
    assert (await _send(client, auth_headers, record))["status"] == "applied"

    row = await _row(session, "patients", PATIENT_ID)
    assert row.client_updated_at == T0
    assert row.updated_at.year == 2031  # the device's own data column is untouched by the revision


# --- containment: one bad record cannot 500 a batch ---


class _LogSpy:
    def __init__(self) -> None:
        self.errors: list[tuple[str, dict[str, Any]]] = []

    def error(self, event: str, **fields: Any) -> None:
        self.errors.append((event, fields))


def _device_client(app: FastAPI) -> httpx.AsyncClient:
    """A client that turns a server exception into the HTTP status a real device would see."""
    transport = httpx.ASGITransport(app=app, raise_app_exceptions=False)
    return httpx.AsyncClient(transport=transport, base_url="http://testserver")


async def test_one_record_that_raises_is_rejected_retryable_and_the_batch_still_applies(
    app: FastAPI,
    client: AsyncClient,
    auth_headers: dict[str, str],
    session: AsyncSession,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    await _seed_parents(client, auth_headers, "kernel_reports")
    spy = _LogSpy()
    monkeypatch.setattr(sync_service, "logger", spy)
    real_apply = sync_service._apply_generic

    async def apply_or_blow_up(*args: Any, **kwargs: Any) -> Any:
        if kwargs["table"] == "kernel_reports":
            raise RuntimeError("patient detail that must not be logged")
        return await real_apply(*args, **kwargs)

    monkeypatch.setattr(sync_service, "_apply_generic", apply_or_blow_up)
    batch = batch_body(
        [
            _record("kernel_reports", "kr-bad", iso(T0), None),
            observation_record("obs-good", client_updated_at=iso(T0)),
        ]
    )

    async with _device_client(app) as device:
        response = await device.post("/api/v1/sync/push", json=batch, headers=auth_headers)

    assert response.status_code == 200
    results = {(r["table"], r["id"]): r for r in response.json()["data"]["results"]}
    bad = results[("kernel_reports", "kr-bad")]
    assert bad["status"] == "rejected"
    assert bad["code"] == ErrorCode.SYS_INTERNAL.value
    assert bad["retry_class"] == "RETRYABLE"
    assert results[("observations", "obs-good")]["status"] == "applied"

    # Persisted state: the valid record and the idempotency row exist, the bad record does not.
    obs = TABLE_REGISTRY["observations"].model
    assert (
        await session.scalar(select(func.count()).select_from(obs).where(obs.id == "obs-good")) == 1
    )
    kernel = TABLE_REGISTRY["kernel_reports"].model
    assert await session.scalar(select(func.count()).select_from(kernel)) == 0
    assert (
        await session.scalar(
            select(func.count())
            .select_from(SyncBatch)
            .where(SyncBatch.batch_id == batch["batch_id"])
        )
        == 1
    )
    # The class name only, never the message.
    assert spy.errors == [
        (
            "sync_record_unexpected_error",
            {"table": "kernel_reports", "record_id": "kr-bad", "error_class": "RuntimeError"},
        )
    ]


async def test_a_database_level_failure_still_fails_the_whole_batch(
    app: FastAPI,
    client: AsyncClient,
    auth_headers: dict[str, str],
    session: AsyncSession,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """A dead connection or deadlock may have left the session unusable, so it is NOT contained to
    one record: the device must retry the whole batch."""
    await _seed_parents(client, auth_headers, "kernel_reports")

    async def database_down(*args: Any, **kwargs: Any) -> Any:
        raise OperationalError("SELECT 1", {}, Exception("server closed the connection"))

    monkeypatch.setattr(sync_service, "_apply_generic", database_down)
    batch = batch_body([observation_record("obs-lost", client_updated_at=iso(T0))])

    async with _device_client(app) as device:
        response = await device.post("/api/v1/sync/push", json=batch, headers=auth_headers)

    assert response.status_code == 503  # SYS_DATABASE_UNAVAILABLE: the whole batch, not one record
    obs = TABLE_REGISTRY["observations"].model
    assert await session.scalar(select(func.count()).select_from(obs)) == 0
    assert (
        await session.scalar(
            select(func.count())
            .select_from(SyncBatch)
            .where(SyncBatch.batch_id == batch["batch_id"])
        )
        == 0
    )
