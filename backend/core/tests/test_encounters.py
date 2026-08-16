"""Encounters and the case-record state machine. api-contract.md section 4."""

from __future__ import annotations

from datetime import datetime
from typing import Any

from httpx import AsyncClient
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession

from app.errors import ErrorCode
from app.models.enums import CaseStatus
from tests.conftest import TEST_FACILITY_ID
from tests.test_patients import PATIENT_ID, create

ENCOUNTER_ID = "8c1d4e6f-a2b3-4c5d-9e0f-112233445566"
CASE_ID = "cr-88f1"
NOW_ISO = "2026-08-16T09:44:00.000Z"


def encounter_body(**overrides: Any) -> dict[str, Any]:
    body: dict[str, Any] = {
        "id": ENCOUNTER_ID,
        "patient_id": PATIENT_ID,
        "started_at": NOW_ISO,
        "follow_up_of_encounter_id": None,
        "created_at": NOW_ISO,
        "updated_at": NOW_ISO,
    }
    body.update(overrides)
    return body


async def seed_patient_and_encounter(client: AsyncClient, headers: dict[str, str]) -> None:
    await create(client, headers)
    response = await client.post("/api/v1/encounters", json=encounter_body(), headers=headers)
    assert response.status_code == 201


async def seed_case_record(session: AsyncSession, status: CaseStatus) -> None:
    """Case records arrive through sync push (Phase 4), so there is no create endpoint to use."""
    await session.execute(
        text(
            "INSERT INTO case_records (id, patient_id, encounter_id, status, created_at, "
            "updated_at, facility_id, server_version, sync_state) VALUES "
            "(:id, :patient, :encounter, :status, now(), now(), :facility, 1, 'RECEIVED')"
        ),
        {
            "id": CASE_ID,
            "patient": PATIENT_ID,
            "encounter": ENCOUNTER_ID,
            "status": status.value,
            "facility": TEST_FACILITY_ID,
        },
    )
    await session.commit()


# ---------------------------------------------------------------------------
# Create
# ---------------------------------------------------------------------------


async def test_create_encounter(client: AsyncClient, auth_headers: dict[str, str]) -> None:
    await create(client, auth_headers)
    response = await client.post("/api/v1/encounters", json=encounter_body(), headers=auth_headers)

    assert response.status_code == 201
    assert response.json()["data"] == {"id": ENCOUNTER_ID, "server_version": 1}


async def test_create_encounter_for_an_unknown_patient_is_refused(
    client: AsyncClient, auth_headers: dict[str, str]
) -> None:
    """The foreign key is real here even though Room has none.

    An encounter with no patient is not a record, it is an orphan, and referential integrity is
    the last line of defence against a wrong-patient linkage (hazard H-03).
    """
    response = await client.post("/api/v1/encounters", json=encounter_body(), headers=auth_headers)
    assert response.status_code == 404
    assert response.json()["code"] == ErrorCode.PAT_NOT_FOUND.value


async def test_repeated_encounter_create_is_idempotent(
    client: AsyncClient, auth_headers: dict[str, str]
) -> None:
    await seed_patient_and_encounter(client, auth_headers)
    again = await client.post("/api/v1/encounters", json=encounter_body(), headers=auth_headers)
    assert again.status_code == 200


async def test_follow_up_must_belong_to_the_same_patient(
    client: AsyncClient, auth_headers: dict[str, str]
) -> None:
    """Otherwise a mistyped id silently links one patient's visit into another's history."""
    await seed_patient_and_encounter(client, auth_headers)
    await create(client, auth_headers, id="Other0000001", full_name="Other Person")

    response = await client.post(
        "/api/v1/encounters",
        json=encounter_body(
            id="enc-second", patient_id="Other0000001", follow_up_of_encounter_id=ENCOUNTER_ID
        ),
        headers=auth_headers,
    )
    assert response.status_code == 422
    assert response.json()["code"] == ErrorCode.ENC_PATIENT_MISMATCH.value


async def test_encounter_create_is_facility_scoped(
    client: AsyncClient, auth_headers: dict[str, str], other_facility_headers: dict[str, str]
) -> None:
    await create(client, auth_headers)
    response = await client.post(
        "/api/v1/encounters", json=encounter_body(), headers=other_facility_headers
    )
    assert response.status_code == 403


# ---------------------------------------------------------------------------
# Case status state machine
# ---------------------------------------------------------------------------


async def _patch_status(
    client: AsyncClient, headers: dict[str, str], status: str, **extra: Any
) -> Any:
    body: dict[str, Any] = {"status": status, "updated_at": "2026-08-16T09:58:00.000Z"}
    body.update(extra)
    return await client.patch(f"/api/v1/case-records/{CASE_ID}/status", json=body, headers=headers)


async def test_legal_transition_is_applied(
    client: AsyncClient, auth_headers: dict[str, str], session: AsyncSession
) -> None:
    await seed_patient_and_encounter(client, auth_headers)
    await seed_case_record(session, CaseStatus.SAVED_LOCALLY)

    response = await _patch_status(
        client, auth_headers, CaseStatus.SENT_TO_DOCTOR.value, assigned_doctor_id="doc-007"
    )
    assert response.status_code == 200
    assert response.json()["data"] == {
        "id": CASE_ID,
        "status": "SENT_TO_DOCTOR",
        "server_version": 2,
    }


async def test_illegal_transition_is_refused(
    client: AsyncClient, auth_headers: dict[str, str], session: AsyncSession
) -> None:
    """DRAFT cannot jump straight to SENT_TO_DOCTOR. The server decides, not the client."""
    await seed_patient_and_encounter(client, auth_headers)
    await seed_case_record(session, CaseStatus.DRAFT)

    response = await _patch_status(client, auth_headers, CaseStatus.SENT_TO_DOCTOR.value)
    assert response.status_code == 409
    assert response.json()["code"] == ErrorCode.ENC_ILLEGAL_TRANSITION.value


async def test_pending_sync_is_accepted_from_the_client(
    client: AsyncClient, auth_headers: dict[str, str], session: AsyncSession
) -> None:
    """The device legitimately writes PENDING_SYNC while offline and the row arrives later
    carrying it (CaseRecordRepository.assignDoctor(isOnline))."""
    await seed_patient_and_encounter(client, auth_headers)
    await seed_case_record(session, CaseStatus.SAVED_LOCALLY)

    response = await _patch_status(client, auth_headers, CaseStatus.PENDING_SYNC.value)
    assert response.status_code == 200

    onward = await _patch_status(client, auth_headers, CaseStatus.SENT_TO_DOCTOR.value)
    assert onward.status_code == 200


async def test_terminal_states_are_terminal(
    client: AsyncClient, auth_headers: dict[str, str], session: AsyncSession
) -> None:
    """A correction after PRESCRIPTION_RECEIVED is a new encounter, never an edit to a closed
    one (docs/data-retention.md)."""
    await seed_patient_and_encounter(client, auth_headers)
    await seed_case_record(session, CaseStatus.PRESCRIPTION_RECEIVED)

    response = await _patch_status(client, auth_headers, CaseStatus.SENT_TO_DOCTOR.value)
    assert response.status_code == 409


async def test_reasserting_the_current_status_is_a_no_op_not_a_violation(
    client: AsyncClient, auth_headers: dict[str, str], session: AsyncSession
) -> None:
    """A retried request over a bad link must not look like a state-machine violation."""
    await seed_patient_and_encounter(client, auth_headers)
    await seed_case_record(session, CaseStatus.SAVED_LOCALLY)

    response = await _patch_status(client, auth_headers, CaseStatus.SAVED_LOCALLY.value)
    assert response.status_code == 200


async def test_status_patch_honours_base_version(
    client: AsyncClient, auth_headers: dict[str, str], session: AsyncSession
) -> None:
    await seed_patient_and_encounter(client, auth_headers)
    await seed_case_record(session, CaseStatus.SAVED_LOCALLY)

    response = await _patch_status(
        client, auth_headers, CaseStatus.SENT_TO_DOCTOR.value, base_version=99
    )
    assert response.status_code == 409
    assert response.json()["code"] == ErrorCode.SYNC_VERSION_CONFLICT.value


async def test_unknown_case_record_is_404(
    client: AsyncClient, auth_headers: dict[str, str]
) -> None:
    response = await _patch_status(client, auth_headers, CaseStatus.SENT_TO_DOCTOR.value)
    assert response.status_code == 404
    assert response.json()["code"] == ErrorCode.ENC_CASE_NOT_FOUND.value


async def test_unknown_status_value_is_rejected(
    client: AsyncClient, auth_headers: dict[str, str], session: AsyncSession
) -> None:
    await seed_patient_and_encounter(client, auth_headers)
    await seed_case_record(session, CaseStatus.SAVED_LOCALLY)

    response = await _patch_status(client, auth_headers, "NOT_A_STATUS")
    assert response.status_code == 422


# ---------------------------------------------------------------------------
# Encounter bundle
# ---------------------------------------------------------------------------


async def test_encounter_fetch_is_doctor_only(
    client: AsyncClient, auth_headers: dict[str, str]
) -> None:
    """api-contract.md section 9.3. A field role has no reason to pull a whole encounter back
    down; the device already holds it."""
    await seed_patient_and_encounter(client, auth_headers)

    response = await client.get(f"/api/v1/encounters/{ENCOUNTER_ID}", headers=auth_headers)
    assert response.status_code == 403
    assert response.json()["code"] == ErrorCode.AUTH_ROLE_FORBIDDEN.value


async def test_encounter_bundle_reports_absent_children_as_null_or_empty(
    client: AsyncClient, auth_headers: dict[str, str], doctor_headers: dict[str, str]
) -> None:
    """A client must never have to tell "no prescription" apart from "key was omitted"."""
    await seed_patient_and_encounter(client, auth_headers)

    response = await client.get(f"/api/v1/encounters/{ENCOUNTER_ID}", headers=doctor_headers)
    assert response.status_code == 200

    data = response.json()["data"]
    assert data["encounter"]["id"] == ENCOUNTER_ID
    assert data["consultation"] is None
    assert data["case_record"] is None
    assert data["kernel_report"] is None
    assert data["evaluate_report"] is None
    assert data["diagnosis_feedback"] is None
    assert data["prescription"] is None
    assert data["attachments"] == []
    assert data["observations"] == []
    assert data["ailments"] == []


async def test_encounter_bundle_includes_private_ailments(
    client: AsyncClient,
    auth_headers: dict[str, str],
    doctor_headers: dict[str, str],
    session: AsyncSession,
) -> None:
    """PRIVATE hides an ailment from the worker-facing projection on the device, not from the
    clinical record (REQ-AIL-02, REQ-AIL-04). Getting this backwards in either direction is a
    defect, so it is asserted in both directions: the private row is present, and no
    audio_local_uri column exists to carry its audio.
    """
    await seed_patient_and_encounter(client, auth_headers)
    await session.execute(
        text(
            "INSERT INTO ailments (id, patient_id, encounter_id, description, measurement_type, "
            "visibility, captured_at_offline, created_at, facility_id, server_version, "
            "sync_state) VALUES ('ail-1', :p, :e, 'private complaint', 'NON_MEASURABLE', "
            "'PRIVATE', now(), now(), :f, 1, 'RECEIVED')"
        ),
        {"p": PATIENT_ID, "e": ENCOUNTER_ID, "f": TEST_FACILITY_ID},
    )
    await session.commit()

    data = (await client.get(f"/api/v1/encounters/{ENCOUNTER_ID}", headers=doctor_headers)).json()[
        "data"
    ]

    assert len(data["ailments"]) == 1
    assert data["ailments"][0]["visibility"] == "PRIVATE"
    assert data["ailments"][0]["description"] == "private complaint"
    assert "audio_local_uri" not in data["ailments"][0]


async def test_ailments_table_has_no_audio_column(session: AsyncSession) -> None:
    """REQ-AIL-03. Private-ailment audio never leaves the device, and there is no upload path
    anywhere in the app. The column must not exist for one to be added by accident."""
    columns = list(
        (
            await session.execute(
                text(
                    "SELECT column_name FROM information_schema.columns "
                    "WHERE table_name = 'ailments'"
                )
            )
        ).scalars()
    )
    assert "audio_local_uri" not in columns
    assert "description" in columns


async def test_soft_deleted_ailments_are_excluded_from_the_bundle(
    client: AsyncClient,
    auth_headers: dict[str, str],
    doctor_headers: dict[str, str],
    session: AsyncSession,
) -> None:
    """Excluded from the view, still in the table. Nothing here hard-deletes clinical data."""
    await seed_patient_and_encounter(client, auth_headers)
    await session.execute(
        text(
            "INSERT INTO ailments (id, patient_id, encounter_id, description, measurement_type, "
            "visibility, captured_at_offline, created_at, deleted_at, facility_id, "
            "server_version, sync_state) VALUES ('ail-del', :p, :e, 'removed', "
            "'NON_MEASURABLE', 'PUBLIC', now(), now(), now(), :f, 1, 'RECEIVED')"
        ),
        {"p": PATIENT_ID, "e": ENCOUNTER_ID, "f": TEST_FACILITY_ID},
    )
    await session.commit()

    data = (await client.get(f"/api/v1/encounters/{ENCOUNTER_ID}", headers=doctor_headers)).json()[
        "data"
    ]
    assert data["ailments"] == []

    still_stored = (
        await session.execute(text("SELECT count(*) FROM ailments WHERE id = 'ail-del'"))
    ).scalar_one()
    assert still_stored == 1


async def test_encounter_timestamps_are_utc_with_an_explicit_z(
    client: AsyncClient, auth_headers: dict[str, str], doctor_headers: dict[str, str]
) -> None:
    await seed_patient_and_encounter(client, auth_headers)

    data = (await client.get(f"/api/v1/encounters/{ENCOUNTER_ID}", headers=doctor_headers)).json()[
        "data"
    ]
    started = data["encounter"]["started_at"]
    assert datetime.fromisoformat(started.replace("Z", "+00:00")).tzinfo is not None
    assert started.endswith("Z") or started.endswith("+00:00")


async def test_encounter_endpoints_require_authentication(client: AsyncClient) -> None:
    assert (await client.get(f"/api/v1/encounters/{ENCOUNTER_ID}")).status_code == 401
    assert (await client.post("/api/v1/encounters", json=encounter_body())).status_code == 401
