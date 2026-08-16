"""Column-level encryption and blind indexes for patient identity fields.

Two mechanisms, doing different jobs:

- :class:`EncryptedText` keeps identity ciphertext at rest. Encryption and decryption happen in
  PostgreSQL via pgcrypto, expressed as SQLAlchemy bind and column expressions, so the ORM reads
  and writes plaintext and no call site has to remember anything.
- :func:`blind_index` produces a deterministic HMAC so an encrypted column can still be found by
  exact match. Equality lookup works, range and prefix search do not, and are not needed.

Stated honestly, and repeated in backend-prd.md section 5.4: with the key in the application
environment this protects a stolen database dump or a backup file, not an attacker who already
has the application host. The real answer is AWS KMS with envelope encryption, and that lands
with the AWS deployment session. Column-level encryption without KMS is still worth having.
"""

from __future__ import annotations

import hashlib
import hmac
import re
from typing import Any

from sqlalchemy import BindParameter, LargeBinary, Text, func, literal, type_coerce
from sqlalchemy.sql.elements import ColumnElement
from sqlalchemy.types import TypeDecorator

from app.config import get_settings

# 32 hex characters of an HMAC-SHA256. 128 bits is far past collision risk at PHC patient volumes,
# and a truncated digest leaks less than a full one if the index is ever exfiltrated alone.
BLIND_INDEX_LENGTH = 32

_NON_DIGITS = re.compile(r"\D")


class EncryptedText(TypeDecorator[str]):
    """TEXT at the Python boundary, bytea ciphertext in the table.

    The key is passed as a bound parameter rather than interpolated into the statement text, so it
    never appears in a query plan, a slow-query log, or SQLAlchemy echo output.

    An encrypted column cannot be indexed, sorted, or searched by pattern. That is the whole
    reason blind indexes exist alongside these columns, not a limitation to work around later.
    """

    impl = LargeBinary
    cache_ok = True

    def bind_expression(self, bindparam: BindParameter[Any]) -> ColumnElement[Any]:
        # type_coerce, not a bare bindparam. Without it the parameter inherits this decorator's
        # impl (bytea) and asyncpg sends "$1::BYTEA" into pgp_sym_encrypt(text, text), which
        # fails with UndefinedFunctionError at the first insert. type_coerce changes only how the
        # value is bound; it emits no SQL cast of its own.
        return func.pgp_sym_encrypt(type_coerce(bindparam, Text), _key_param(), type_=LargeBinary)

    def column_expression(self, column: ColumnElement[Any]) -> ColumnElement[Any]:
        return func.pgp_sym_decrypt(column, _key_param(), type_=Text)


def _key_param() -> ColumnElement[Any]:
    """The pgcrypto symmetric key, as an anonymous bind parameter."""
    return literal(get_settings().phi_encryption_key, type_=Text)


def normalise_name(value: str) -> str:
    """Casefold and collapse whitespace so "  Sunita  Devi " and "sunita devi" index alike."""
    return " ".join(value.split()).casefold()


def normalise_digits(value: str) -> str:
    """Strip everything that is not a digit, so "98765 43210" and "9876543210" index alike.

    Deliberately does NOT strip a country code. "+919876543210" and "9876543210" index
    differently, which is correct here: REQ-REG-02 fixes mobile numbers at exactly 10 digits at
    entry, so a prefixed value is bad data rather than an alternative spelling, and silently
    folding the two together would let one patient match another's number.
    """
    return _NON_DIGITS.sub("", value)


def blind_index(value: str | None, *, digits_only: bool = False) -> str | None:
    """Deterministic lookup token for an encrypted column.

    HMAC rather than a plain hash: a bare SHA-256 of a 10-digit mobile number is brute forceable
    in seconds, and of a common name faster than that. The key turns the index from a thin
    obfuscation into something an attacker holding only the database cannot invert.
    """
    if value is None:
        return None
    normalised = normalise_digits(value) if digits_only else normalise_name(value)
    if not normalised:
        return None
    digest = hmac.new(
        get_settings().blind_index_key.encode("utf-8"),
        normalised.encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()
    return digest[:BLIND_INDEX_LENGTH]
