"""Application configuration. Every value comes from the environment.

No secret is ever hardcoded, defaulted to a real value, or read from a committed file. The
placeholder guard in :meth:`Settings.validate_secrets` refuses to boot a non-dev environment that
is still carrying a .env.example value.
"""

from __future__ import annotations

import functools
from typing import Literal

from pydantic import field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

Environment = Literal["dev", "staging", "prod"]
AbdmMode = Literal["stub", "live"]

# Any secret still equal to a value starting with this marker is a .env.example leftover.
PLACEHOLDER_PREFIX = "CHANGE_ME"
MIN_SECRET_LENGTH = 32


class Settings(BaseSettings):
    """Typed view of the process environment.

    Instantiated once per process through :func:`get_settings`. Tests override by constructing a
    Settings directly and overriding the FastAPI dependency, never by mutating a global.
    """

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
        case_sensitive=False,
    )

    # Environment
    environment: Environment = "dev"
    require_https: bool = False
    git_sha: str = "unknown"
    app_version: str = "0.1.0"

    # Database
    database_url: str = "postgresql+asyncpg://samd:samd_dev_only@localhost:5432/samd"
    db_pool_size: int = 5
    db_max_overflow: int = 5
    db_echo: bool = False

    # Secrets
    jwt_secret_key: str = "CHANGE_ME_dev_only_jwt_secret_key_min_32_chars"
    phi_encryption_key: str = "CHANGE_ME_dev_only_phi_encryption_key_min_32"
    blind_index_key: str = "CHANGE_ME_dev_only_blind_index_key_min_32_chars"
    case_token_key: str = "CHANGE_ME_dev_only_case_token_key_min_32_chars"

    # Tokens. D-8: HS256. One process signs and verifies; RS256 earns its keep when a second
    # verifier exists, and switching later is a config change plus a key rollover.
    jwt_algorithm: str = "HS256"
    jwt_issuer: str = "samd-backend"
    jwt_audience: str = "samd-android"
    access_token_ttl_seconds: int = 3600
    refresh_token_ttl_seconds: int = 604800

    # Login throttling. Counters live on user_accounts, not Redis.
    login_max_failed_attempts: int = 5
    login_lockout_seconds: int = 900

    # Kernel (Phase 3). Connect and read timeouts are separate settings deliberately: a hung TCP
    # connect and a slow model are different failures and must be distinguishable in logs, not
    # both folded into one "kernel_timeout_seconds".
    kernel_base_url: str = "http://10.16.4.182:8000"
    kernel_connect_timeout_seconds: float = 5.0
    # 30s matches the Android app's existing OkHttp read timeout (di/NetworkModule.kt), so
    # behaviour under a slow kernel does not change now that the call is proxied.
    kernel_read_timeout_seconds: float = 30.0
    # 3, not 5 (Phase 3 fix pass, B3): no retries by design, so this threshold is how many real
    # clinical submissions must fail before fast-fail engages. 5 meant 5 real submissions eating
    # the full read timeout before a worker got a fast, honest failure.
    kernel_circuit_threshold: int = 3
    kernel_circuit_cooldown_seconds: float = 30.0

    # ABDM / ABHA (Phase 5)
    abdm_mode: AbdmMode = "stub"
    abdm_base_url: str = "https://abhasbx.abdm.gov.in"
    abdm_cert_url: str = "https://healthidsbx.abdm.gov.in/api/v1/auth/cert"
    abdm_client_id: str = ""
    abdm_client_secret: str = ""
    abdm_hip_id: str = ""
    abdm_cm_id: str = "sbx"
    abdm_consent_code: str = "abha-enrollment"
    abdm_consent_version: str = "1.4"
    abdm_timeout_seconds: int = 30

    # Logging
    log_level: str = "INFO"

    @property
    def is_dev(self) -> bool:
        return self.environment == "dev"

    @field_validator("database_url")
    @classmethod
    def require_async_driver(cls, value: str) -> str:
        if not value.startswith("postgresql+asyncpg://"):
            raise ValueError(
                "DATABASE_URL must use the postgresql+asyncpg:// scheme. "
                "A sync driver silently blocks the event loop under load."
            )
        return value

    @field_validator("log_level")
    @classmethod
    def normalise_log_level(cls, value: str) -> str:
        allowed = {"DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL"}
        upper = value.upper()
        if upper not in allowed:
            raise ValueError(f"LOG_LEVEL must be one of {sorted(allowed)}")
        return upper

    @model_validator(mode="after")
    def validate_secrets(self) -> Settings:
        """Refuse to boot a non-dev environment holding placeholder or short secrets.

        Dev is allowed to run on the .env.example values so a fresh clone starts, which is the
        only reason the defaults above exist at all.
        """
        if self.is_dev:
            return self

        secrets = {
            "JWT_SECRET_KEY": self.jwt_secret_key,
            "PHI_ENCRYPTION_KEY": self.phi_encryption_key,
            "BLIND_INDEX_KEY": self.blind_index_key,
            "CASE_TOKEN_KEY": self.case_token_key,
        }
        problems: list[str] = []
        for name, value in secrets.items():
            if not value or value.startswith(PLACEHOLDER_PREFIX):
                problems.append(f"{name} is unset or still a .env.example placeholder")
            elif len(value) < MIN_SECRET_LENGTH:
                problems.append(f"{name} is shorter than {MIN_SECRET_LENGTH} characters")

        if not self.require_https:
            problems.append("REQUIRE_HTTPS must be true outside dev")

        if self.abdm_mode == "live" and not (self.abdm_client_id and self.abdm_client_secret):
            problems.append("ABDM_MODE=live requires ABDM_CLIENT_ID and ABDM_CLIENT_SECRET")

        if problems:
            raise ValueError(
                f"Refusing to start in ENVIRONMENT={self.environment}: " + "; ".join(problems)
            )
        return self


@functools.lru_cache(maxsize=1)
def get_settings() -> Settings:
    """Process-wide settings singleton. Cached so the environment is read once."""
    return Settings()


# Consent block required on every ABDM V3 enrollment call. Sourced from
# "abha api docs/ABDM_ABHA_V3_AP_Is_V1_31_07_2025_869ab8cda9.md". Phase 5 reads it from here so
# the version is one edit, not a scatter of literals.
def abdm_consent_block(settings: Settings | None = None) -> dict[str, str]:
    resolved = settings or get_settings()
    return {"code": resolved.abdm_consent_code, "version": resolved.abdm_consent_version}


# Fields that must never appear in a log line, an audit payload, or an error detail. Enforced by
# the structlog redaction processor in app.logging, not by discipline at call sites.
REDACTED_KEYS: frozenset[str] = frozenset(
    {
        "pin",
        "new_pin",
        "current_pin",
        "password",
        "pin_hash",
        "access_token",
        "refresh_token",
        "token",
        "authorization",
        "aadhaar",
        "aadhaar_number",
        "abha_number",
        "abhanumber",
        "otp",
        "otp_value",
        "otpvalue",
        "client_secret",
        "full_name",
        "name",
        "mobile",
        "mobile_number",
        "email_address",
        "address",
        "emergency_contact",
        "guardian_or_spouse_name",
    }
)
