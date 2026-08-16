"""Provision facilities and worker accounts.

Decision D-3: the facility administrator provisions accounts with this script and hands the
initial PIN to the worker in person. must_change_pin is true on every account created here, so
the administrator-issued PIN is a one-time credential and cannot become a long-lived one. There
is no self-service registration endpoint and no self-service reset in v1.

worker_id is not invented here. It is derived exactly as MockAuthSession.stableUserId does on
the device:

    sha256(name.trim().lowercase() + "|" + ROLE) hex encoded, first 16 characters

so the audit trail's actor identifier is continuous across the cutover from mock auth to real
auth. Historical device rows and new server rows for the same person share one identifier.

Usage:
    python -m app.scripts.seed_accounts facility PHC-RJ-0142 "PHC Bagru" --district Jaipur \\
        --state Rajasthan
    python -m app.scripts.seed_accounts worker "A. Devi" ASHA_WORKER PHC-RJ-0142 --pin 482915

Omit --pin to have one generated and printed once. It is printed to stdout deliberately: this is
an interactive administrator tool, not a service, and the value is single use.
"""

from __future__ import annotations

import argparse
import asyncio
import hashlib
import secrets
import sys

from sqlalchemy import select

from app.db.session import dispose_engine, get_sessionmaker
from app.models.enums import UserRole
from app.models.facility import Facility
from app.models.user import UserAccount
from app.services.auth import hash_pin

GENERATED_PIN_DIGITS = 6


def derive_worker_id(name: str, role: UserRole) -> str:
    """Mirror of MockAuthSession.stableUserId. Do not change without changing both sides."""
    normalised = f"{name.strip().lower()}|{role.value}"
    digest = hashlib.sha256(normalised.encode("utf-8")).hexdigest()
    return digest[:16]


async def _create_facility(
    facility_id: str, name: str, district: str | None, state: str | None, hfr_id: str | None
) -> None:
    factory = get_sessionmaker()
    async with factory() as session:
        existing = await session.get(Facility, facility_id)
        if existing is not None:
            print(f"Facility {facility_id} already exists. Nothing changed.")
            return
        session.add(
            Facility(id=facility_id, name=name, district=district, state=state, hfr_id=hfr_id)
        )
        await session.commit()
    print(f"Created facility {facility_id} ({name}).")


async def _create_worker(name: str, role_value: str, facility_id: str, pin: str | None) -> None:
    role = UserRole(role_value)
    worker_id = derive_worker_id(name, role)
    issued_pin = pin or "".join(secrets.choice("0123456789") for _ in range(GENERATED_PIN_DIGITS))

    factory = get_sessionmaker()
    async with factory() as session:
        facility = await session.get(Facility, facility_id)
        if facility is None:
            print(f"Facility {facility_id} does not exist. Create it first.", file=sys.stderr)
            raise SystemExit(2)

        existing = (
            await session.execute(select(UserAccount).where(UserAccount.worker_id == worker_id))
        ).scalar_one_or_none()
        if existing is not None:
            # Re-issuing a PIN is a deliberate, separate act from creating an account.
            existing.pin_hash = hash_pin(issued_pin)
            existing.must_change_pin = True
            existing.failed_attempts = 0
            existing.locked_until = None
            await session.commit()
            print(f"Reset PIN for existing worker {worker_id} ({name}, {role.value}).")
        else:
            session.add(
                UserAccount(
                    worker_id=worker_id,
                    display_name=name.strip(),
                    role=role.value,
                    facility_id=facility_id,
                    pin_hash=hash_pin(issued_pin),
                    must_change_pin=True,
                    is_active=True,
                )
            )
            await session.commit()
            print(f"Created worker {worker_id} ({name}, {role.value}) at {facility_id}.")

    print(f"Initial PIN: {issued_pin}")
    print("Hand this to the worker in person. They must change it at first login.")


def main() -> None:
    parser = argparse.ArgumentParser(description="Provision facilities and worker accounts.")
    sub = parser.add_subparsers(dest="command", required=True)

    facility = sub.add_parser("facility", help="Create a facility")
    facility.add_argument("facility_id")
    facility.add_argument("name")
    facility.add_argument("--district", default=None)
    facility.add_argument("--state", default=None)
    facility.add_argument("--hfr-id", default=None, help="Health Facility Registry id, for ABDM")

    worker = sub.add_parser("worker", help="Create a worker account or reset its PIN")
    worker.add_argument("name")
    worker.add_argument("role", choices=[role.value for role in UserRole])
    worker.add_argument("facility_id")
    worker.add_argument("--pin", default=None, help="Omit to generate one")

    args = parser.parse_args()

    async def run() -> None:
        try:
            if args.command == "facility":
                await _create_facility(
                    args.facility_id, args.name, args.district, args.state, args.hfr_id
                )
            else:
                await _create_worker(args.name, args.role, args.facility_id, args.pin)
        finally:
            await dispose_engine()

    asyncio.run(run())


if __name__ == "__main__":
    main()
