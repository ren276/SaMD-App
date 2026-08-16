"""Startup validation.

A production service that starts with a default signing key is worse than one that does not
start. These tests are the reason that sentence is true rather than aspirational.
"""

from __future__ import annotations

import pytest

from app.config import Settings

BASE = {
    "database_url": "postgresql+asyncpg://samd:pw@db:5432/samd",
    "jwt_secret_key": "a-real-jwt-secret-key-of-at-least-32-chars",
    "phi_encryption_key": "a-real-phi-encryption-key-at-least-32-ch",
    "blind_index_key": "a-real-blind-index-key-of-at-least-32-ch",
    "case_token_key": "a-real-case-token-key-of-at-least-32-chr",
    "require_https": True,
}


def test_prod_refuses_placeholder_secrets() -> None:
    with pytest.raises(ValueError, match="placeholder"):
        Settings(  # type: ignore[call-arg]
            **{**BASE, "environment": "prod", "jwt_secret_key": "CHANGE_ME_dev_only_jwt_secret"}
        )


def test_prod_refuses_short_secrets() -> None:
    with pytest.raises(ValueError, match="shorter than"):
        Settings(**{**BASE, "environment": "prod", "blind_index_key": "too-short"})  # type: ignore[call-arg]


def test_prod_refuses_cleartext() -> None:
    with pytest.raises(ValueError, match="REQUIRE_HTTPS"):
        Settings(**{**BASE, "environment": "prod", "require_https": False})  # type: ignore[call-arg]


def test_live_abdm_requires_credentials() -> None:
    with pytest.raises(ValueError, match="ABDM_CLIENT_ID"):
        Settings(**{**BASE, "environment": "staging", "abdm_mode": "live"})  # type: ignore[call-arg]


def test_prod_accepts_real_secrets() -> None:
    settings = Settings(**{**BASE, "environment": "prod"})  # type: ignore[call-arg]
    assert settings.is_dev is False


def test_dev_boots_on_the_env_example_values() -> None:
    """A fresh clone must start. That is the only reason the defaults exist."""
    settings = Settings(environment="dev")  # type: ignore[call-arg]
    assert settings.is_dev is True


def test_sync_database_driver_is_refused() -> None:
    """A sync driver silently blocks the event loop under load."""
    with pytest.raises(ValueError, match="asyncpg"):
        Settings(**{**BASE, "database_url": "postgresql://samd:pw@db:5432/samd"})  # type: ignore[call-arg]
