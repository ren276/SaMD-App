"""GET /admin/dashboard. Read-only visibility for DOCTOR, no write path (decision D-3).

Not under /api/v1: this returns HTML, not the envelope every JSON endpoint returns
(app/schemas/common.py), so it does not belong in that contract.
"""

from __future__ import annotations

from html import escape
from typing import Any

from fastapi import APIRouter, Depends
from fastapi.responses import HTMLResponse
from sqlalchemy import select, text
from sqlalchemy.ext.asyncio import AsyncSession

from app.deps import CurrentWorker, SessionDep, require_roles
from app.models.audit import AuditEvent
from app.models.enums import UserRole
from app.models.facility import Facility
from app.models.user import UserAccount
from app.services.audit import verify_chain

router = APIRouter(prefix="/admin", tags=["admin"])

# Every table carrying SyncMixin (app/models/mixins.py). Kept as a literal list, not introspected
# from the metadata at request time, so a new syncable table must be added here deliberately.
_SYNC_TABLES = (
    "kernel_reports",
    "evaluate_reports",
    "diagnosis_feedback",
    "attachments",
    "observations",
    "ailments",
    "case_records",
    "referrals",
    "abha_profiles",
    "prescriptions",
    "medication_lines",
    "medical_history_items",
    "allergies",
    "family_history_entries",
    "social_histories",
    "medication_entries",
    "patients",
    "encounters",
    "consultations",
)

_AUDIT_ROW_LIMIT = 200


async def _users_panel(session: AsyncSession) -> list[dict[str, Any]]:
    stmt = select(
        UserAccount.worker_id,
        UserAccount.display_name,
        UserAccount.role,
        UserAccount.facility_id,
        UserAccount.is_active,
        UserAccount.must_change_pin,
        UserAccount.failed_attempts,
        UserAccount.locked_until,
        UserAccount.last_login_at,
    ).order_by(UserAccount.facility_id, UserAccount.display_name)
    rows = (await session.execute(stmt)).all()
    return [dict(row._mapping) for row in rows]


async def _sync_panel(session: AsyncSession) -> list[dict[str, Any]]:
    # One UNION ALL round trip rather than one query per table (ponytail: 19 separate awaits
    # would be 19 network round trips for what is one page load).
    parts = [
        f"SELECT '{table}' AS table_name, sync_state, count(*) AS row_count "
        f"FROM {table} GROUP BY sync_state"
        for table in _SYNC_TABLES
    ]
    stmt = text(" UNION ALL ".join(parts) + " ORDER BY table_name, sync_state")
    rows = (await session.execute(stmt)).all()
    return [dict(row._mapping) for row in rows]


async def _audit_panel(session: AsyncSession) -> list[dict[str, Any]]:
    stmt = (
        select(
            AuditEvent.id,
            AuditEvent.sequence,
            AuditEvent.occurred_at,
            AuditEvent.facility_id,
            AuditEvent.actor_id,
            AuditEvent.actor_role,
            AuditEvent.action,
            AuditEvent.patient_id,
            AuditEvent.case_record_id,
            AuditEvent.entry_hash,
        )
        .order_by(AuditEvent.sequence.desc())
        .limit(_AUDIT_ROW_LIMIT)
    )
    rows = (await session.execute(stmt)).all()
    return [dict(row._mapping) for row in rows]


async def _chain_status(session: AsyncSession) -> list[dict[str, Any]]:
    facility_ids = (await session.execute(select(Facility.id))).scalars().all()
    results = []
    for facility_id in facility_ids:
        result = await verify_chain(session, facility_id=facility_id)
        results.append(
            {
                "facility_id": facility_id,
                "verified": result.verified,
                "entries_checked": result.entries_checked,
                "first_broken_sequence": result.first_broken_sequence,
            }
        )
    return results


def _table(headers: list[str], rows: list[dict[str, Any]]) -> str:
    if not rows:
        return "<p>No rows.</p>"
    head = "".join(f"<th>{escape(h)}</th>" for h in headers)
    body = "".join(
        "<tr>" + "".join(f"<td>{escape(str(row.get(h, '')))}</td>" for h in headers) + "</tr>"
        for row in rows
    )
    return f"<table><thead><tr>{head}</tr></thead><tbody>{body}</tbody></table>"


def _render(
    users: list[dict[str, Any]],
    sync_counts: list[dict[str, Any]],
    audit_rows: list[dict[str, Any]],
    chain_status: list[dict[str, Any]],
) -> str:
    users_html = _table(
        [
            "worker_id",
            "display_name",
            "role",
            "facility_id",
            "is_active",
            "must_change_pin",
            "failed_attempts",
            "locked_until",
            "last_login_at",
        ],
        users,
    )
    sync_html = _table(["table_name", "sync_state", "row_count"], sync_counts)
    audit_html = _table(
        [
            "sequence",
            "occurred_at",
            "facility_id",
            "actor_id",
            "actor_role",
            "action",
            "patient_id",
            "case_record_id",
            "entry_hash",
        ],
        audit_rows,
    )
    chain_html = _table(
        ["facility_id", "verified", "entries_checked", "first_broken_sequence"], chain_status
    )
    return f"""<!doctype html>
<html>
<head>
<meta charset="utf-8">
<title>Admin dashboard</title>
<style>
body {{ font-family: sans-serif; margin: 2rem; }}
table {{ border-collapse: collapse; margin-bottom: 2rem; width: 100%; }}
th, td {{ border: 1px solid #ccc; padding: 4px 8px; text-align: left; font-size: 0.85rem; }}
th {{ background: #eee; }}
h2 {{ margin-top: 2rem; }}
.note {{ background: #fff3cd; padding: 0.5rem 1rem; border: 1px solid #e0c36a; }}
</style>
</head>
<body>
<h1>Admin dashboard</h1>
<p>Read-only. No write path exists on this page or its route.</p>

<h2>Users</h2>
{users_html}

<h2>Sync state</h2>
<p class="note">Sync-failure-reason tracking is not implemented in the schema yet. Only
RECEIVED and CONFLICT states exist today; an empty table here does not mean sync is healthy,
it means no failure states currently exist to track.</p>
{sync_html}

<h2>Audit log (most recent {_AUDIT_ROW_LIMIT})</h2>
{audit_html}

<h2>Audit chain verification, per facility</h2>
{chain_html}
</body>
</html>"""


@router.get("/dashboard", response_class=HTMLResponse)
async def admin_dashboard(
    session: SessionDep,
    worker: CurrentWorker = Depends(require_roles(UserRole.DOCTOR)),
) -> HTMLResponse:
    users = await _users_panel(session)
    sync_counts = await _sync_panel(session)
    audit_rows = await _audit_panel(session)
    chain_status = await _chain_status(session)
    return HTMLResponse(_render(users, sync_counts, audit_rows, chain_status))
