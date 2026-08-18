"""Date-of-birth normalisation. D3: two real ABDM shapes for the same logical field in one flow.

`enrol/byAadhaar`'s embedded `ABHAProfile.dob` is one string, `"DD-MM-YYYY"`.
`profile/account` (the endpoint the final `GET .../profile` step actually calls) has no combined
field at all, only separate `yearOfBirth`/`monthOfBirth`/`dayOfBirth` strings.

The adapter always emits one canonical `date_of_birth`, ISO-ordered (`YYYY-MM-DD`), regardless of
which ABDM response it came from. Year-only identities are real (ABDM represents an unknown
month/day as `"00"` or an empty string): the canonical output for those is the bare year
(`"1991"`), never a fabricated `"1991-01-01"`. `AbhaIdentity.date_of_birth` is typed `str | None`,
not `date`, specifically so this partial-precision form is representable without lying about
precision the source data does not have.
"""

from __future__ import annotations


def _is_unknown(component: str | None) -> bool:
    return component is None or component.strip() in ("", "0", "00")


def from_split_fields(*, year: str | None, month: str | None, day: str | None) -> str | None:
    """`profile/account`'s yearOfBirth/monthOfBirth/dayOfBirth."""
    if _is_unknown(year):
        return None
    if _is_unknown(month) or _is_unknown(day):
        return year
    try:
        return f"{year}-{int(month):02d}-{int(day):02d}"  # type: ignore[arg-type]
    except ValueError:
        return year


def from_ddmmyyyy(value: str | None) -> str | None:
    """`enrol/byAadhaar`'s `ABHAProfile.dob`, `"DD-MM-YYYY"`."""
    if value is None or not value.strip():
        return None
    parts = value.split("-")
    if len(parts) != 3:
        return None
    day, month, year = parts
    if _is_unknown(day) or _is_unknown(month):
        return year if not _is_unknown(year) else None
    try:
        return f"{year}-{int(month):02d}-{int(day):02d}"
    except ValueError:
        # A malformed value (not digits at all) is not a date this adapter can honestly render;
        # None, not a crash, matching the "represent honestly, do not fabricate" rule this
        # module exists for.
        return None
