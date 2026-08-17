"""Guards against the exact failure mode Phase 4 found: an accepted-action set that quietly
stops matching its source of truth. Two tests, sourcing the two sides from the two actual
components, never one list typed twice into both places (that is what let the original 7-value
guess in app/services/sync.py rot for four phases without anything noticing).
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

from app.domain.audit_actions_device import DEVICE_AUDIT_ACTIONS
from app.services import sync as sync_service

# backend/core/tests/ -> backend/core -> backend -> repo root -> app/src/.../AuditLogger.kt
_ANDROID_AUDIT_LOGGER = (
    Path(__file__).resolve().parents[3]
    / "app"
    / "src"
    / "main"
    / "java"
    / "com"
    / "example"
    / "samdapp"
    / "domain"
    / "audit"
    / "AuditLogger.kt"
)

# One entry per line, e.g. `    PATIENT_REGISTERED("patient_registered"),`. Deliberately does not
# try to be a Kotlin parser: it only needs to pull quoted string literals out of enum constructor
# calls, and the file has exactly one such call per constant.
_ENUM_ENTRY = re.compile(r'^\s*[A-Z_]+\("([a-z_]+)"\),?\s*$', re.MULTILINE)


def _parse_android_enum_values(source: str) -> frozenset[str]:
    start = source.index("enum class AuditAction(val value: String) {")
    end = source.index("\n}\n", start)
    body = source[start:end]
    return frozenset(_ENUM_ENTRY.findall(body))


def test_backend_accepted_device_set_matches_the_checked_in_mirror() -> None:
    """The set app/services/sync.py actually enforces against. Sourced from the mirror module,
    not retyped: if someone edits _DEVICE_AUDIT_ACTION_VALUES back into a hand-typed literal,
    this fails the moment it disagrees with app/domain/audit_actions_device.py by even one value.
    """
    assert sync_service._DEVICE_AUDIT_ACTION_VALUES == DEVICE_AUDIT_ACTIONS


def test_mirror_matches_the_android_source_when_reachable() -> None:
    """The other half: does the checked-in mirror still match Android's real enum. Runs for real
    in this monorepo checkout (both app/ and backend/ are the same working tree), rather than
    skipping unconditionally: a skip that always fires is not a test, it is a comment. Only skips
    if the Android source genuinely is not present, which is the honest fallback for a backend-only
    checkout, not a way to dodge running the assertion here.
    """
    if not _ANDROID_AUDIT_LOGGER.exists():
        pytest.skip(
            "Android source not present in this checkout (backend-only clone or CI job). "
            "Regenerate app/domain/audit_actions_device.py by hand from "
            "app/src/main/java/com/example/samdapp/domain/audit/AuditLogger.kt's AuditAction "
            "enum whenever that enum changes, and note the regeneration in PROGRESS.md."
        )

    android_values = _parse_android_enum_values(_ANDROID_AUDIT_LOGGER.read_text())

    assert android_values, "parsed zero AuditAction entries; the regex or the enum shape changed"
    assert DEVICE_AUDIT_ACTIONS == android_values
