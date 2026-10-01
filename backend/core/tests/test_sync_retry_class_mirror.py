"""The backend half of the sync retry-class coupling.

Two jobs, and they are different:

1. **No reject path can omit a classification.** Asserted against `app/services/sync.py`'s own
   AST, not against a hand-maintained list of call sites. A list would be a second artifact that
   can rot, and S-4's lesson applies directly: a test comparing two sets can pass because both
   are wrong.
2. **The device declares every value this backend can send, and no others.** Asserted by reading
   the Kotlin enum, the mirror of `app/src/test/.../SyncRetryClassMirrorTest.kt`'s own read of
   this side. Each half must fail on whichever side the edit was made, because contributors run
   one suite or the other, not both.

The AST is used rather than a regex for job 1 specifically because `sync.py`'s reject sites are
heavily commented, several with prose that names a `SyncRetryClass` value; a regex over the text
would read those comments as call arguments. `ast.parse` discards comments by construction, which
is a stronger version of the "strip comments first" fix the audit-action mirror test needed.
"""

from __future__ import annotations

import ast
import re
from pathlib import Path

import pytest

from app.models.enums import SyncRetryClass
from app.services import sync as sync_service

_REPO_ROOT = Path(__file__).resolve().parents[3]
_SYNC_SOURCE = Path(sync_service.__file__)
_DEVICE_ENUM = _REPO_ROOT / "app/src/main/java/com/example/samdapp/domain/model/SyncRetryClass.kt"

# The number of `_reject(...)` call sites in app/services/sync.py. Pinned so that ADDING a reject
# path is a deliberate act that updates this number, rather than something that slips in
# classified by copy-paste. The diagnosis counted seventeen; re-counted here from the AST. The
# eighteenth is the per-record catch-all in _apply_one (RETRYABLE: a server defect, not the
# record), the nineteenth is the closed-format check on the kernel_reports identity fields
# (TERMINAL).
_EXPECTED_REJECT_SITES = 19


def _reject_calls() -> list[ast.Call]:
    tree = ast.parse(_SYNC_SOURCE.read_text())
    return [
        node
        for node in ast.walk(tree)
        if isinstance(node, ast.Call)
        and isinstance(node.func, ast.Name)
        and node.func.id == "_reject"
    ]


def test_every_reject_site_passes_a_retry_class() -> None:
    calls = _reject_calls()
    assert len(calls) == _EXPECTED_REJECT_SITES, (
        f"app/services/sync.py has {len(calls)} _reject call sites, expected "
        f"{_EXPECTED_REJECT_SITES}. If a reject path was added, classify it and update this "
        "number in the same commit; if one was removed, likewise."
    )

    valid_names = {member.name for member in SyncRetryClass}
    # The two helpers that compute a class at the one non-uniform site.
    valid_calls = {"_constraint_retry_class"}

    for call in calls:
        # _reject's signature has no default for retry_class, so a missing argument is already a
        # TypeError at call time and a mypy error before that. This asserts the stronger property
        # the signature cannot: that the value is one this module recognises, rather than some
        # other expression that happens to type-check.
        args = call.args
        keyword = {kw.arg: kw.value for kw in call.keywords}.get("retry_class")
        supplied = args[4] if len(args) >= 5 else keyword
        assert supplied is not None, (
            f"_reject at {_SYNC_SOURCE.name}:{call.lineno} passes no retry_class"
        )

        if isinstance(supplied, ast.Attribute):
            assert isinstance(supplied.value, ast.Name) and supplied.value.id == "SyncRetryClass", (
                f"_reject at {_SYNC_SOURCE.name}:{call.lineno} passes "
                f"{ast.dump(supplied)}, not a SyncRetryClass member"
            )
            assert supplied.attr in valid_names, (
                f"_reject at {_SYNC_SOURCE.name}:{call.lineno} passes SyncRetryClass."
                f"{supplied.attr}, which is not a member"
            )
        elif isinstance(supplied, ast.Call) and isinstance(supplied.func, ast.Name):
            assert supplied.func.id in valid_calls, (
                f"_reject at {_SYNC_SOURCE.name}:{call.lineno} computes its retry_class with "
                f"{supplied.func.id}(), which is not one of {sorted(valid_calls)}. A new "
                "classifier must be reviewed, not inferred."
            )
        else:
            pytest.fail(
                f"_reject at {_SYNC_SOURCE.name}:{call.lineno} passes a retry_class expression "
                f"this guard does not recognise: {ast.dump(supplied)}"
            )


def test_every_sqlstate_the_module_messages_also_has_a_retry_class() -> None:
    """The two sqlstate maps must cover the same keys.

    A sqlstate with a message and no class silently falls to the TERMINAL default, which is the
    same silent permanent rejection this change set exists to remove, just moved one level down.
    """
    assert set(sync_service._SQLSTATE_MESSAGES) == set(sync_service._SQLSTATE_RETRY_CLASSES)


def test_the_foreign_key_sqlstate_is_retryable() -> None:
    """The single row that is the difference between permanent loss and a record that syncs on
    the next drain. Pinned on its own so a future edit to the map has to argue with a named
    test rather than quietly flip it."""
    assert sync_service._SQLSTATE_RETRY_CLASSES["23503"] == SyncRetryClass.RETRYABLE


def test_the_unique_sqlstate_is_conflict_not_retryable() -> None:
    """The duplicate-ABHA case. Resolved to a definite value, not "sometimes"."""
    assert sync_service._SQLSTATE_RETRY_CLASSES["23505"] == SyncRetryClass.CONFLICT


def test_an_unknown_sqlstate_defaults_to_terminal_which_is_todays_behaviour() -> None:
    class _Orig:
        sqlstate = "42P01"

    class _Exc:
        orig = _Orig()

    assert sync_service._constraint_retry_class(_Exc()) == SyncRetryClass.TERMINAL  # type: ignore[arg-type]

    class _NoState:
        orig = object()

    assert sync_service._constraint_retry_class(_NoState()) == SyncRetryClass.TERMINAL  # type: ignore[arg-type]


# ---------------------------------------------------------------------------
# The mirror
# ---------------------------------------------------------------------------


def _device_values() -> set[str]:
    """Members of the Kotlin `enum class SyncRetryClass`, comments stripped and bounded to the
    enum body.

    Stripping is why this is not a one-line regex over the file: that enum's KDoc names every one
    of its own values in prose, and the sibling audit-action mirror test shipped with exactly that
    bug before it stripped comments. Block comments and line comments both go.
    """
    source = _DEVICE_ENUM.read_text()
    source = re.sub(r"/\*.*?\*/", "", source, flags=re.S)
    source = "\n".join(line.split("//")[0] for line in source.splitlines())

    start = source.index("enum class SyncRetryClass")
    body = source[start:]
    body = body[: body.index("}") + 1]
    return set(re.findall(r"^\s{4}([A-Z_]+)\s*,", body, flags=re.M))


def test_mirror_matches_the_android_source_when_reachable() -> None:
    if not _DEVICE_ENUM.is_file():
        pytest.skip(f"device source not present at {_DEVICE_ENUM}")

    backend = {member.value for member in SyncRetryClass}
    device = _device_values()

    assert backend, "parsed zero values from the backend enum"
    assert device, f"parsed zero values from {_DEVICE_ENUM}; the file shape changed"

    assert backend - device == set(), (
        f"This backend can send retry_class values the device does not declare: "
        f"{sorted(backend - device)}. The device falls back to TERMINAL for them, abandoning "
        "exactly the rows this change set exists to save. Add them to SyncRetryClass.kt."
    )
    assert device - backend == set(), (
        f"The device declares retry_class values this backend cannot send: "
        f"{sorted(device - backend)}. Remove them, or add them here."
    )


def test_every_backend_member_name_equals_its_value() -> None:
    """Both mirror tests compare NAMES on one side against VALUES on the other. That is only
    sound while the two coincide, so it is asserted rather than assumed."""
    for member in SyncRetryClass:
        assert member.name == member.value
