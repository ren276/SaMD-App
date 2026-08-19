"""PHI redaction in logs.

Redaction is a processor, not a convention at call sites, so a new call site cannot forget it.
That property is what these tests defend.
"""

from __future__ import annotations

from app.config import REDACTED_KEYS
from app.logging import REDACTED, _drop_body_keys, redaction_processor


def _process(event: dict) -> dict:  # type: ignore[type-arg]
    return redaction_processor(None, "info", _drop_body_keys(None, "info", event))  # type: ignore[arg-type,return-value]


def test_top_level_phi_keys_are_masked() -> None:
    result = _process(
        {
            "event": "something",
            "aadhaar_number": "123456789012",
            "mobile_number": "9876543210",
            "pin": "482915",
            "abha_number": "91160145481380",
            "full_name": "Sunita Devi",
            "status": 200,
        }
    )

    assert result["aadhaar_number"] == REDACTED
    assert result["mobile_number"] == REDACTED
    assert result["pin"] == REDACTED
    assert result["abha_number"] == REDACTED
    assert result["full_name"] == REDACTED
    # Non-PHI passes through untouched, otherwise the log is useless.
    assert result["status"] == 200


def test_nested_phi_keys_are_masked() -> None:
    result = _process(
        {"event": "e", "worker": {"display_name": "x", "mobile_number": "9876543210"}}
    )
    assert result["worker"]["mobile_number"] == REDACTED


def test_phi_inside_a_list_is_masked() -> None:
    result = _process({"event": "e", "items": [{"otp": "123456"}, {"safe": 1}]})
    assert result["items"][0]["otp"] == REDACTED
    assert result["items"][1]["safe"] == 1


def test_bodies_are_dropped_entirely() -> None:
    result = _process({"event": "e", "body": {"anything": 1}, "response_body": "x", "raw": "y"})
    assert "body" not in result
    assert "response_body" not in result
    assert "raw" not in result


def test_secrets_are_masked() -> None:
    result = _process(
        {
            "event": "e",
            "access_token": "eyJ...",
            "refresh_token": "eyJ...",
            "client_secret": "s",
            "authorization": "Bearer eyJ...",
        }
    )
    assert all(
        result[key] == REDACTED
        for key in ("access_token", "refresh_token", "client_secret", "authorization")
    )


def test_deeply_nested_structures_are_replaced_not_partially_walked() -> None:
    """A half-walked tree is exactly where an unredacted field would hide."""
    deep: dict = {"pin": "1"}  # type: ignore[type-arg]
    for _ in range(12):
        deep = {"nested": deep}

    result = _process({"event": "e", "tree": deep})
    rendered = repr(result)
    assert '"1"' not in rendered
    assert "'1'" not in rendered


def test_abdm_secrets_and_transaction_ids_are_masked() -> None:
    """M1 live wiring: a config-load log line or a request log line must never carry the ABDM
    client secret, an OTP value, or the ABDM transaction id."""
    result = _process(
        {
            "event": "abdm_config_loaded",
            "abdm_client_secret": "s3cr3t",
            "otp_value": "123456",
            "txnid": "37d8d312-35a0-41e7-a6e4-1074eb18a5fa",
            "txn_id": "37d8d312-35a0-41e7-a6e4-1074eb18a5fa",
        }
    )
    assert result["abdm_client_secret"] == REDACTED
    assert result["otp_value"] == REDACTED
    assert result["txnid"] == REDACTED
    assert result["txn_id"] == REDACTED


def test_abdm_pem_value_is_masked_regardless_of_key_name() -> None:
    """The V3 public-key PEM (crypto.py's fetch_public_key_pem) has no single field name at every
    call site, so it must be caught by value, not by key."""
    result = _process(
        {
            "event": "abdm_cert_fetched",
            "some_unexpected_field_name": "-----BEGIN PUBLIC KEY-----\nMIIB...\n-----END",
        }
    )
    assert result["some_unexpected_field_name"] == REDACTED


def test_redaction_key_list_covers_the_identity_columns_encrypted_at_rest() -> None:
    """The log redaction list and the PHI-at-rest column list must not drift apart."""
    for column in (
        "full_name",
        "aadhaar_number",
        "abha_number",
        "mobile_number",
        "guardian_or_spouse_name",
        "emergency_contact",
    ):
        assert column in REDACTED_KEYS
