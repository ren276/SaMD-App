"""Closed formats for the strings a device sends about a kernel report, and the vocabulary of the
derivation cross-check.

One definition, used by three layers that must agree: the sync ingest check (`services/sync.py`),
the database CHECK constraints (`models/kernel.py`, alembic 0010) and the tests. No device-supplied
free string is stored verbatim: each one has a closed format, and a value outside it is rejected
rather than normalised, because the backend does not rewrite device-owned data.

The patterns are written in the POSIX subset that both Python `re` and PostgreSQL `~` read the same
way, so the ingest check and the CHECK constraint cannot disagree about what is valid.
"""

from __future__ import annotations

import re
from typing import Final

# UUID4, lowercase. The proxy mints or adopts a UUID4 (middleware/request_id.py) and the device
# lowercases what it stores, so an uppercase value indicates a defect, not a variant.
REQUEST_ID_PATTERN: Final[str] = (
    "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
)
DERIVATION_RULE_VERSION_PATTERN: Final[str] = "^HAN-07/08-v[0-9]{1,3}$"
# Not enforced by a database CHECK: rows written before this change are not guaranteed to match,
# and a migration must not fail on them. Enforced at ingest only.
MODEL_VERSION_PATTERN: Final[str] = "^[A-Za-z0-9._:+-]{1,80}$"
MODEL_SHA256_PATTERN: Final[str] = "^[0-9a-f]{64}$"

REQUEST_ID_RE: Final = re.compile(REQUEST_ID_PATTERN)
DERIVATION_RULE_VERSION_RE: Final = re.compile(DERIVATION_RULE_VERSION_PATTERN)
MODEL_VERSION_RE: Final = re.compile(MODEL_VERSION_PATTERN)
MODEL_SHA256_RE: Final = re.compile(MODEL_SHA256_PATTERN)

# The only fields a derivation check may name as differing. Field NAMES only, never values: the
# values are patient-linked clinical classifications and must not be copied into an operations
# table.
MISMATCH_FIELD_NAMES: Final[tuple[str, ...]] = (
    "urgency_level",
    "risk_category",
    "required_human_verification",
)
