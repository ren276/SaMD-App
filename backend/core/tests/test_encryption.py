"""Encryption at rest and blind-index lookup.

An encryption control nobody has read the raw bytes of is a claim, not a control. These tests
read the ciphertext straight out of the column.
"""

from __future__ import annotations

import pytest
from httpx import AsyncClient
from sqlalchemy import select, text
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.types import blind_index, normalise_digits, normalise_name
from app.models.patient import Patient
from tests.conftest import TEST_FACILITY_ID
from tests.test_patients import PATIENT_ID, create

PLAINTEXT_NAME = "Sunita Devi"
PLAINTEXT_MOBILE = "9876543210"
PLAINTEXT_AADHAAR = "123456789012"


async def _seed(client: AsyncClient, headers: dict[str, str]) -> None:
    response = await create(client, headers, aadhaar_number=PLAINTEXT_AADHAAR)
    assert response.status_code == 201


async def test_identity_columns_hold_ciphertext_not_plaintext(
    client: AsyncClient, auth_headers: dict[str, str], session: AsyncSession
) -> None:
    await _seed(client, auth_headers)

    row = (
        await session.execute(
            text("SELECT full_name, mobile_number, aadhaar_number FROM patients WHERE id = :id"),
            {"id": PATIENT_ID},
        )
    ).one()

    for column in row:
        assert isinstance(column, bytes | memoryview)
        raw = bytes(column)
        assert PLAINTEXT_NAME.encode() not in raw
        assert PLAINTEXT_MOBILE.encode() not in raw
        assert PLAINTEXT_AADHAAR.encode() not in raw
        # pgcrypto PGP message framing. Proves it is genuinely pgp_sym_encrypt output and not,
        # say, a UTF-8 string that merely failed a substring check.
        assert raw[:1] == b"\xc3"


async def test_non_identity_columns_stay_plaintext_and_queryable(
    client: AsyncClient, auth_headers: dict[str, str], session: AsyncSession
) -> None:
    """district and state are deliberately not encrypted, so facility-level aggregates do not
    require decrypting every row. At PHC granularity they are not identifying on their own."""
    await _seed(client, auth_headers)

    count = (
        await session.execute(text("SELECT count(*) FROM patients WHERE district = 'Jaipur'"))
    ).scalar_one()
    assert count == 1


async def test_the_orm_round_trips_plaintext(
    client: AsyncClient, auth_headers: dict[str, str], session: AsyncSession
) -> None:
    """Every call site outside app/db/types.py deals in plaintext and never knows otherwise."""
    await _seed(client, auth_headers)

    patient = (await session.execute(select(Patient).where(Patient.id == PATIENT_ID))).scalar_one()
    assert patient.full_name == PLAINTEXT_NAME
    assert patient.mobile_number == PLAINTEXT_MOBILE
    assert patient.aadhaar_number == PLAINTEXT_AADHAAR


async def test_blind_index_finds_an_encrypted_row_without_decrypting_it(
    client: AsyncClient, auth_headers: dict[str, str], session: AsyncSession
) -> None:
    """The whole point of the blind index: exact-match lookup against ciphertext.

    The query below never mentions the encrypted column, so PostgreSQL never runs
    pgp_sym_decrypt to answer it.
    """
    await _seed(client, auth_headers)

    found = (
        await session.execute(
            select(Patient.id).where(
                Patient.mobile_blind_idx == blind_index(PLAINTEXT_MOBILE, digits_only=True)
            )
        )
    ).scalar_one_or_none()
    assert found == PATIENT_ID

    by_name = (
        await session.execute(
            select(Patient.id).where(Patient.name_blind_idx == blind_index("  sunita   DEVI "))
        )
    ).scalar_one_or_none()
    assert by_name == PATIENT_ID


async def test_blind_index_is_refreshed_on_update(
    client: AsyncClient, auth_headers: dict[str, str], session: AsyncSession
) -> None:
    """A stale index is worse than no index: the patient silently stops being findable by the
    value they were just given."""
    await _seed(client, auth_headers)

    await client.patch(
        f"/api/v1/patients/{PATIENT_ID}",
        json={"mobile_number": "9998887776", "updated_at": "2026-08-16T11:02:00.000Z"},
        headers=auth_headers,
    )

    stale = (
        await session.execute(
            select(Patient.id).where(
                Patient.mobile_blind_idx == blind_index(PLAINTEXT_MOBILE, digits_only=True)
            )
        )
    ).scalar_one_or_none()
    assert stale is None

    fresh = (
        await session.execute(
            select(Patient.id).where(
                Patient.mobile_blind_idx == blind_index("9998887776", digits_only=True)
            )
        )
    ).scalar_one_or_none()
    assert fresh == PATIENT_ID


async def test_blind_index_stores_no_recoverable_plaintext(
    client: AsyncClient, auth_headers: dict[str, str], session: AsyncSession
) -> None:
    await _seed(client, auth_headers)

    row = (
        await session.execute(
            text("SELECT name_blind_idx, mobile_blind_idx FROM patients WHERE id = :id"),
            {"id": PATIENT_ID},
        )
    ).one()
    for value in row:
        assert len(value) == 32
        assert PLAINTEXT_NAME.lower() not in value
        assert PLAINTEXT_MOBILE not in value


async def test_abha_number_is_plaintext_and_uniquely_constrained(
    client: AsyncClient, auth_headers: dict[str, str], session: AsyncSession
) -> None:
    """abha_number is deliberately not encrypted: it needs exact match plus a UNIQUE constraint,
    and that constraint IS the SAMD-PAT-3004 wrong-patient guard."""
    await create(client, auth_headers, abha_number="12345678901234")

    stored = (
        await session.execute(
            text("SELECT abha_number FROM patients WHERE id = :id"), {"id": PATIENT_ID}
        )
    ).scalar_one()
    assert stored == "12345678901234"

    with pytest.raises(Exception):  # noqa: B017 - any integrity failure is the point
        await session.execute(
            text(
                "INSERT INTO patients (id, full_name, biological_sex, abha_number, district, "
                "created_at, updated_at, facility_id) VALUES "
                "('Dup000000001', pgp_sym_encrypt('X', 'k'), 'MALE', '12345678901234', 'Jaipur', "
                "now(), now(), :facility)"
            ),
            {"facility": TEST_FACILITY_ID},
        )
    await session.rollback()


def test_normalisation_rules() -> None:
    assert normalise_name("  SUNITA   Devi ") == "sunita devi"
    assert normalise_digits("98765 43210") == "9876543210"
    # Deliberately not equal: REQ-REG-02 fixes mobile numbers at 10 digits, so a country-code
    # prefix is bad data rather than an alternative spelling of the same number.
    assert normalise_digits("+919876543210") != normalise_digits("9876543210")


def test_blind_index_of_none_is_none() -> None:
    assert blind_index(None) is None
    assert blind_index("   ") is None


def test_blind_index_is_deterministic_and_key_bound() -> None:
    assert blind_index("Sunita Devi") == blind_index("sunita devi")
    assert blind_index("Sunita Devi") != blind_index("Sunita Devu")


async def test_same_plaintext_encrypts_differently_in_two_rows(
    client: AsyncClient, auth_headers: dict[str, str], session: AsyncSession
) -> None:
    """pgp_sym_encrypt is randomised, so two patients sharing a name do not share ciphertext.

    That property is exactly why equality search needs the blind index and cannot simply compare
    the encrypted column, and it is also what stops the table leaking "these two are the same
    person" to anyone holding a dump.
    """
    await create(client, auth_headers)
    await create(client, auth_headers, id="Twin00000001")

    rows = (
        await session.execute(
            text("SELECT full_name FROM patients WHERE id IN (:a, :b)"),
            {"a": PATIENT_ID, "b": "Twin00000001"},
        )
    ).all()
    assert len(rows) == 2
    assert bytes(rows[0][0]) != bytes(rows[1][0])

    # The blind index, by contrast, is identical: that is what makes it searchable.
    indexes = (
        await session.execute(
            text("SELECT name_blind_idx FROM patients WHERE id IN (:a, :b)"),
            {"a": PATIENT_ID, "b": "Twin00000001"},
        )
    ).all()
    assert indexes[0][0] == indexes[1][0]
