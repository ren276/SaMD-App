"""Patient CRUD. api-contract.md section 3."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from typing import Any

from httpx import AsyncClient

from app.errors import ErrorCode
from tests.conftest import TEST_FACILITY_ID

PATIENT_ID = "K7m2Qx9pR4tZ"
NOW = datetime(2026, 8, 16, 9, 40, tzinfo=UTC)


def patient_body(**overrides: Any) -> dict[str, Any]:
    body: dict[str, Any] = {
        "id": PATIENT_ID,
        "full_name": "Sunita Devi",
        "date_of_birth": "1991-04-12",
        "age": 35,
        "biological_sex": "FEMALE",
        "mobile_number": "9876543210",
        "district": "Jaipur",
        "state": "Rajasthan",
        "pincode": "303007",
        "created_at": "2026-08-16T09:40:00.000Z",
        "updated_at": "2026-08-16T09:41:30.000Z",
    }
    body.update(overrides)
    return body


async def create(client: AsyncClient, headers: dict[str, str], **overrides: Any) -> Any:
    return await client.post("/api/v1/patients", json=patient_body(**overrides), headers=headers)


# ---------------------------------------------------------------------------
# Create
# ---------------------------------------------------------------------------


async def test_create_returns_201_and_no_patient_body(
    client: AsyncClient, auth_headers: dict[str, str]
) -> None:
    response = await create(client, auth_headers)
    assert response.status_code == 201

    data = response.json()["data"]
    assert data["id"] == PATIENT_ID
    assert data["server_version"] == 1
    assert data["facility_id"] == TEST_FACILITY_ID

    # The response deliberately does not echo the patient body (api-contract.md section 3.1).
    # Sending identity back over the wire for no reason widens the exposure surface.
    raw = response.text
    assert "Sunita" not in raw
    assert "9876543210" not in raw


async def test_repeated_create_with_the_same_payload_is_idempotent(
    client: AsyncClient, auth_headers: dict[str, str]
) -> None:
    """A retried create over a bad link must not mint a duplicate patient."""
    first = await create(client, auth_headers)
    second = await create(client, auth_headers)

    assert first.status_code == 201
    assert second.status_code == 200
    assert second.json()["data"]["server_version"] == 1


async def test_repeated_create_with_different_data_is_a_conflict(
    client: AsyncClient, auth_headers: dict[str, str]
) -> None:
    await create(client, auth_headers)
    response = await create(client, auth_headers, full_name="Someone Else")

    assert response.status_code == 409
    assert response.json()["code"] == ErrorCode.PAT_ID_CONFLICT.value


async def test_create_rejects_a_malformed_patient_id(
    client: AsyncClient, auth_headers: dict[str, str]
) -> None:
    """REQ-REG-03: 10 to 12 alphanumeric characters, enforced server side too."""
    response = await create(client, auth_headers, id="short")
    assert response.status_code == 422
    assert response.json()["code"] == ErrorCode.PAT_VALIDATION_FAILED.value


async def test_create_requires_a_contact_method(
    client: AsyncClient, auth_headers: dict[str, str]
) -> None:
    """REQ-REG-01. The device enforces this; a client is not a trust boundary."""
    response = await create(client, auth_headers, mobile_number=None, village=None, district=None)
    assert response.status_code == 422
    assert response.json()["code"] == ErrorCode.PAT_VALIDATION_FAILED.value


async def test_create_requires_a_date_of_birth_or_an_age(
    client: AsyncClient, auth_headers: dict[str, str]
) -> None:
    response = await create(client, auth_headers, date_of_birth=None, age=None)
    assert response.status_code == 422


async def test_create_rejects_a_mobile_number_that_is_not_ten_digits(
    client: AsyncClient, auth_headers: dict[str, str]
) -> None:
    """REQ-REG-02 fixed-length digit rules."""
    response = await create(client, auth_headers, mobile_number="98765")
    assert response.status_code == 422


async def test_create_rejects_a_client_supplied_facility_id(
    client: AsyncClient, auth_headers: dict[str, str]
) -> None:
    """facility_id comes from the token. Accepting it from a body is a scoping escape."""
    response = await create(client, auth_headers, facility_id="PHC-SOMEWHERE-ELSE")
    assert response.status_code == 422


# ---------------------------------------------------------------------------
# ABHA collision guard, the wrong-patient control
# ---------------------------------------------------------------------------


async def test_duplicate_abha_number_is_refused_never_merged(
    client: AsyncClient, auth_headers: dict[str, str]
) -> None:
    """SAMD-PAT-3004, hazard H-03.

    Two patient records claiming one ABHA number is resolved by a human, never by whichever
    write landed second.
    """
    await create(client, auth_headers, abha_number="12345678901234")
    response = await create(
        client,
        auth_headers,
        id="P2m2Qx9pR4tZ",
        full_name="Different Person",
        abha_number="12345678901234",
    )

    assert response.status_code == 409
    assert response.json()["code"] == ErrorCode.PAT_DUPLICATE_ABHA.value


async def test_patching_an_abha_number_onto_a_second_patient_is_refused(
    client: AsyncClient, auth_headers: dict[str, str]
) -> None:
    """Late linking is the normal path: a patient registered offline gets an ABHA later. Linking
    one that already belongs to somebody else must still fail."""
    await create(client, auth_headers, abha_number="12345678901234")
    await create(client, auth_headers, id="P2m2Qx9pR4tZ", full_name="Second Person")

    response = await client.patch(
        "/api/v1/patients/P2m2Qx9pR4tZ",
        json={"abha_number": "12345678901234", "updated_at": "2026-08-16T11:00:00.000Z"},
        headers=auth_headers,
    )
    assert response.status_code == 409
    assert response.json()["code"] == ErrorCode.PAT_DUPLICATE_ABHA.value


async def test_late_abha_linking_keeps_the_original_patient_id(
    client: AsyncClient, auth_headers: dict[str, str]
) -> None:
    """The app UID stays primary. ABHA is a secondary identifier that may arrive later."""
    await create(client, auth_headers)

    patched = await client.patch(
        f"/api/v1/patients/{PATIENT_ID}",
        json={
            "abha_number": "91160145481380",
            "abha_address": "sunita.devi@sbx",
            "abha_status": "ACTIVE",
            "updated_at": "2026-08-16T11:00:00.000Z",
        },
        headers=auth_headers,
    )
    assert patched.status_code == 200
    assert patched.json()["data"]["id"] == PATIENT_ID
    assert patched.json()["data"]["server_version"] == 2

    detail = (await client.get(f"/api/v1/patients/{PATIENT_ID}", headers=auth_headers)).json()[
        "data"
    ]
    assert detail["abha_number"] == "91160145481380"
    assert detail["id"] == PATIENT_ID


# ---------------------------------------------------------------------------
# Read and update
# ---------------------------------------------------------------------------


async def test_get_returns_the_decrypted_record(
    client: AsyncClient, auth_headers: dict[str, str]
) -> None:
    await create(client, auth_headers)

    response = await client.get(f"/api/v1/patients/{PATIENT_ID}", headers=auth_headers)
    assert response.status_code == 200

    data = response.json()["data"]
    assert data["full_name"] == "Sunita Devi"
    assert data["mobile_number"] == "9876543210"
    assert data["district"] == "Jaipur"
    assert data["server_version"] == 1


async def test_get_unknown_patient_is_404(
    client: AsyncClient, auth_headers: dict[str, str]
) -> None:
    response = await client.get("/api/v1/patients/ZZZZZZZZZZZZ", headers=auth_headers)
    assert response.status_code == 404
    assert response.json()["code"] == ErrorCode.PAT_NOT_FOUND.value


async def test_patch_only_touches_the_fields_sent(
    client: AsyncClient, auth_headers: dict[str, str]
) -> None:
    """PATCH, not PUT. An absent field keeps its stored value instead of being nulled."""
    await create(client, auth_headers)

    await client.patch(
        f"/api/v1/patients/{PATIENT_ID}",
        json={"mobile_number": "9876543211", "updated_at": "2026-08-16T11:02:00.000Z"},
        headers=auth_headers,
    )

    detail = (await client.get(f"/api/v1/patients/{PATIENT_ID}", headers=auth_headers)).json()[
        "data"
    ]
    assert detail["mobile_number"] == "9876543211"
    assert detail["full_name"] == "Sunita Devi"
    assert detail["district"] == "Jaipur"


async def test_patch_with_a_stale_base_version_is_a_conflict(
    client: AsyncClient, auth_headers: dict[str, str]
) -> None:
    await create(client, auth_headers)
    await client.patch(
        f"/api/v1/patients/{PATIENT_ID}",
        json={"village": "Bagru Khurd", "updated_at": "2026-08-16T11:02:00.000Z"},
        headers=auth_headers,
    )

    response = await client.patch(
        f"/api/v1/patients/{PATIENT_ID}",
        json={
            "village": "Somewhere",
            "updated_at": "2026-08-16T11:03:00.000Z",
            "base_version": 1,
        },
        headers=auth_headers,
    )
    assert response.status_code == 409
    assert response.json()["code"] == ErrorCode.SYNC_VERSION_CONFLICT.value


async def test_patch_rejects_an_immutable_field(
    client: AsyncClient, auth_headers: dict[str, str]
) -> None:
    await create(client, auth_headers)
    response = await client.patch(
        f"/api/v1/patients/{PATIENT_ID}",
        json={"id": "OTHERID12345", "updated_at": "2026-08-16T11:02:00.000Z"},
        headers=auth_headers,
    )
    assert response.status_code == 422


# ---------------------------------------------------------------------------
# Roster: REQ-ROS-02 and hazard H-04 across the network boundary
# ---------------------------------------------------------------------------


async def _seed_encounter(
    client: AsyncClient,
    headers: dict[str, str],
    *,
    patient_id: str,
    started_at: datetime,
    encounter_id: str = "enc-0001",
) -> None:
    """Create an encounter, and fail loudly if it does not.

    The assert is the point. An earlier version of this helper built an id longer than the
    column allows and ignored the resulting 422, so every roster assertion silently tested an
    empty database and the "excludes rows outside the window" case passed for the wrong reason.
    """
    timestamp = started_at.isoformat().replace("+00:00", "Z")
    response = await client.post(
        "/api/v1/encounters",
        json={
            "id": encounter_id,
            "patient_id": patient_id,
            "started_at": timestamp,
            "created_at": timestamp,
            "updated_at": timestamp,
        },
        headers=headers,
    )
    assert response.status_code == 201, response.text


async def test_roster_requires_both_bounds(
    client: AsyncClient, auth_headers: dict[str, str]
) -> None:
    """There is deliberately no way to ask for every patient."""
    response = await client.get("/api/v1/patients", headers=auth_headers)
    assert response.status_code == 422


async def test_roster_refuses_a_window_wider_than_31_days(
    client: AsyncClient, auth_headers: dict[str, str]
) -> None:
    response = await client.get(
        "/api/v1/patients",
        params={
            "encounter_from": "2026-01-01T00:00:00Z",
            "encounter_to": "2026-06-01T00:00:00Z",
        },
        headers=auth_headers,
    )
    assert response.status_code == 422
    assert response.json()["code"] == ErrorCode.PAT_VALIDATION_FAILED.value


async def test_roster_returns_a_projection_without_identifiers(
    client: AsyncClient, auth_headers: dict[str, str]
) -> None:
    """A roster is a list of who is here today. It does not need Aadhaar, ABHA, or a mobile."""
    await create(client, auth_headers, abha_number="12345678901234")
    await _seed_encounter(client, auth_headers, patient_id=PATIENT_ID, started_at=NOW)

    response = await client.get(
        "/api/v1/patients",
        params={
            "encounter_from": "2026-08-16T00:00:00Z",
            "encounter_to": "2026-08-17T00:00:00Z",
        },
        headers=auth_headers,
    )
    assert response.status_code == 200

    entries = response.json()["data"]["patients"]
    assert len(entries) == 1
    assert entries[0]["id"] == PATIENT_ID
    assert set(entries[0]) == {
        "id",
        "full_name",
        "age",
        "biological_sex",
        "last_encounter_at",
        "server_version",
    }
    raw = response.text
    assert "12345678901234" not in raw
    assert "9876543210" not in raw


async def test_roster_excludes_encounters_outside_the_window(
    client: AsyncClient, auth_headers: dict[str, str]
) -> None:
    await create(client, auth_headers)
    await _seed_encounter(
        client, auth_headers, patient_id=PATIENT_ID, started_at=NOW - timedelta(days=10)
    )

    response = await client.get(
        "/api/v1/patients",
        params={
            "encounter_from": "2026-08-16T00:00:00Z",
            "encounter_to": "2026-08-17T00:00:00Z",
        },
        headers=auth_headers,
    )
    assert response.json()["data"]["patients"] == []


async def test_roster_pages_with_a_cursor(
    client: AsyncClient, auth_headers: dict[str, str]
) -> None:
    ids = ["Pat000000001", "Pat000000002", "Pat000000003"]
    for index, patient_id in enumerate(ids):
        await create(client, auth_headers, id=patient_id, full_name=f"Person {index}")
        await _seed_encounter(
            client,
            auth_headers,
            patient_id=patient_id,
            started_at=NOW + timedelta(hours=index),
            encounter_id=f"enc-{index:04d}",
        )

    params = {
        "encounter_from": "2026-08-16T00:00:00Z",
        "encounter_to": "2026-08-17T00:00:00Z",
        "limit": 2,
    }
    first = (await client.get("/api/v1/patients", params=params, headers=auth_headers)).json()[
        "data"
    ]
    assert len(first["patients"]) == 2
    assert first["next_cursor"] is not None

    second = (
        await client.get(
            "/api/v1/patients",
            params={**params, "cursor": first["next_cursor"]},
            headers=auth_headers,
        )
    ).json()["data"]
    assert len(second["patients"]) == 1
    assert second["next_cursor"] is None

    seen = [entry["id"] for entry in first["patients"] + second["patients"]]
    assert sorted(seen) == sorted(ids)


# ---------------------------------------------------------------------------
# Facility isolation
# ---------------------------------------------------------------------------


async def test_a_patient_is_invisible_to_another_facility(
    client: AsyncClient, auth_headers: dict[str, str], other_facility_headers: dict[str, str]
) -> None:
    await create(client, auth_headers)

    response = await client.get(f"/api/v1/patients/{PATIENT_ID}", headers=other_facility_headers)
    assert response.status_code == 403
    assert response.json()["code"] == ErrorCode.AUTH_ROLE_FORBIDDEN.value


async def test_another_facility_roster_does_not_leak_rows(
    client: AsyncClient, auth_headers: dict[str, str], other_facility_headers: dict[str, str]
) -> None:
    await create(client, auth_headers)
    await _seed_encounter(client, auth_headers, patient_id=PATIENT_ID, started_at=NOW)

    response = await client.get(
        "/api/v1/patients",
        params={
            "encounter_from": "2026-08-16T00:00:00Z",
            "encounter_to": "2026-08-17T00:00:00Z",
        },
        headers=other_facility_headers,
    )
    assert response.status_code == 200
    assert response.json()["data"]["patients"] == []


async def test_patient_endpoints_require_authentication(client: AsyncClient) -> None:
    assert (await client.get(f"/api/v1/patients/{PATIENT_ID}")).status_code == 401
    assert (await client.post("/api/v1/patients", json=patient_body())).status_code == 401
