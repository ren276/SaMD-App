"""Guards against the exact failure mode migration 0006 was written to close: the Android
InferenceSource enum gains a value, the backend model enum is updated to match, but the actual
DB CHECK constraint (frozen as literal SQL in whichever alembic migration last touched it) is
not, so the value is accepted by application code and rejected by the database. Two tests,
sourcing three independent things (Android source, the backend model enum, the migration SQL
text actually shipped), modeled on tests/test_audit_actions_device.py's audit-vocab guard.
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

from app.models.enums import InferenceSource

# backend/core/tests/ -> backend/core -> backend -> repo root -> app/src/.../InferenceSource.kt
_ANDROID_INFERENCE_SOURCE = (
    Path(__file__).resolve().parents[3]
    / "app"
    / "src"
    / "main"
    / "java"
    / "com"
    / "example"
    / "samdapp"
    / "domain"
    / "model"
    / "InferenceSource.kt"
)

_ALEMBIC_VERSIONS_DIR = Path(__file__).resolve().parents[1] / "alembic" / "versions"

_CHECK_TEXT = re.compile(r"inference_source IN \(([^)]*)\)")
_QUOTED_VALUE = re.compile(r"'([A-Z_]+)'")


def _parse_android_enum_values(source: str) -> frozenset[str]:
    start = source.index("enum class InferenceSource {")
    end = source.index("}", start)
    body = source[start:end]
    return frozenset(name.strip() for name in body.split("{", 1)[1].split(","))


def _upgrade_body(source: str) -> str:
    start = source.index("def upgrade(")
    downgrade_marker = "\ndef downgrade("
    end = (
        source.index(downgrade_marker, start) if downgrade_marker in source[start:] else len(source)
    )
    return source[start:end]


def _current_check_constraint_values() -> frozenset[str] | None:
    """Scans every migration in revision order and keeps the last one that touches this
    constraint's upgrade() body, so this test stays correct the next time the CHECK is widened
    again without anyone having to update this file.
    """
    current: frozenset[str] | None = None
    for path in sorted(_ALEMBIC_VERSIONS_DIR.glob("[0-9][0-9][0-9][0-9]_*.py")):
        source = path.read_text()
        if "inference_source IN (" not in source:
            continue
        match = _CHECK_TEXT.search(_upgrade_body(source))
        if match:
            current = frozenset(_QUOTED_VALUE.findall(match.group(1)))
    return current


def test_model_enum_matches_the_check_constraint_actually_shipped() -> None:
    """The value application code accepts (app.models.enums.InferenceSource) versus the value
    the database will actually enforce (the CHECK constraint's literal SQL, sourced from the
    migration files themselves, not retyped). This is precisely the pair that disagreed before
    migration 0006: the Android/model side gained UNAVAILABLE, the CHECK constraint did not.
    """
    shipped = _current_check_constraint_values()
    assert shipped is not None, "no migration defines the kernel_reports inference_source CHECK"
    assert {member.value for member in InferenceSource} == shipped


def test_model_enum_matches_the_android_source_when_reachable() -> None:
    if not _ANDROID_INFERENCE_SOURCE.exists():
        pytest.skip(
            "Android source not present in this checkout (backend-only clone or CI job). "
            "Update app.models.enums.InferenceSource by hand whenever "
            "app/src/main/java/com/example/samdapp/domain/model/InferenceSource.kt changes, and "
            "widen the kernel_reports CHECK constraint with a new migration in the same change."
        )

    android_values = _parse_android_enum_values(_ANDROID_INFERENCE_SOURCE.read_text())

    assert android_values, "parsed zero InferenceSource entries; the regex or enum shape changed"
    assert {member.value for member in InferenceSource} == android_values
