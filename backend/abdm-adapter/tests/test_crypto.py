"""RSA-OAEP-SHA1 encrypt round-trip against a known test key. Confirms the cipher this module
uses (`RSA/ECB/OAEPWithSHA-1AndMGF1Padding`, from `abha api docs/get started/
encodingndrsaencryption.md`) is really what gets applied, by decrypting with the matching private
key and checking the plaintext survives. This test does NOT reach `abdm_cert_url`, live or stub:
`fetch_public_key_pem` in stub mode returns a fixed local key, no network call, so passing here
does not validate the cert URL itself (D1 is a live-activation checklist item, not something this
test can prove).

The private key below exists only here, to verify the round trip. Nothing in `abdm_adapter/`
holds it; `crypto.py` only ever has the public half.
"""

from __future__ import annotations

import base64

import pytest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding

from abdm_adapter.crypto import STUB_PUBLIC_KEY_PEM, encrypt_oaep_sha1, fetch_public_key_pem

_TEST_PRIVATE_KEY_PEM = """-----BEGIN PRIVATE KEY-----
MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQDhnw8O/m35Jw8o
tyAsRVMIAqTdht0LtJBCczzT/LhP+rnyBSmtvHwB82F+LHS5vN6DefKtrRv7g/az
Yt9qbSog6VXAQxpPSt0o5rvRAmwgmPBpVubGxng+Ke5NikHCD3bGkIA4K1AZgOqn
aJvWajIiB/qAzqb14X485LEYi+xni+1Ev4eh0kyVQSwfttLP/CIuyTzo22G0O+Ff
oJavvuRCITdft+doZx74gvLd/d/yi9rEra/XA6Cchz6284xl4nmnB4VYRbmrcgxS
d2ziD+yVTNi28VcuJwZorJRhBsT7PNmr771BLqepDwvC/vRG4aEKP+uByPzwjn56
DQy+e3TLAgMBAAECggEADBeiznpreX2S7es2sbi7dQPzQx49zalNTApyGaiDVmIJ
QkPtkm62NMKPGJyYWvYY9PH9xDoxdD8D42RqtgEIz3/CBU5zZAmOpekL6u1Wzz5z
MCokr0Wm3yoPKB+/W3/JrWXEQX5syhkjOnq8U3cYncNQtUOeJjKCOhBFv5uMPZZk
AaIeR/uuTflX4Dv/ealBYHivWS9ogetOc20yq3qjkgmrbMwZsKcJp4XFrqD9kc5A
7susMyx5/r2xmyjZ1HV5T0g0LywIlJ3+AvdCBqtFzUE5Lv8Wg8iDakWSlwfaFEMc
4584PCcj4km/+KzRkkpF67G5PTBHyEMBT4qvGtiABQKBgQD95TSyVHiGvDSySt6w
Y/lDw5yB1mQvXj/R6lL1uJPzMYJbtFThgN9Gkn1j0hYjxsVjStNPjfSHzFqXWM2k
Fuo7PMzzdtwHOPkUApZyVct/jlhz04pLfqcu9w5le2msSQl8abflf1VgJuFFRP9H
0FE6+j5p5cktJ/cd1I2uhw43NwKBgQDjfdo1Y/Jw2wF7SI32D7BKRLAwBYv0QKTB
rPAvsW7dVloF8Zt4tmQlnPsDJ9fVCe8ne9mAeWdYbH5nGJW1xz1/xZ9ebzn6Hb3D
AGF4o9wGFBuuqnYhq0UoGqIqVZDFdLXQJBLBiY5adpSqqBE4bRnEVqeu8t+1gZ6D
xb+ac6gRDQKBgGiofdNw3In1tOc105v0agDT9oTS4lNgT4BxTic6IcqiwvCYYlDe
das6oXNvW2799cnbQ0XM51q/EyzGD2avh3hJtNY7TbGwe4QTGP8ifJMlEzMTpTM/
jxzd/FI60DGTndRqI8L3fTL4c+3A5lYs+f8MvoOalBYIHmZugsTJqUN7AoGBAK/v
GN7JmSnmddLeXcu6fmAxlwDMiVww0jpmgKqh0lei/KQ0IkTM6c6dBRN/sM+1ixtC
EQfm3CHqZiddQAsBN3KlZ4clBfWFIsi4dqlSMEsTKgV/FzUau7U0Q83xtrC+fg2m
mErNEyarJnaE3CyDU7YgPjZy5sf8opbOTEwqvgPRAoGBAOXI8qXShaczevb3awDT
AP34D5XFvKAoNGKPecqxuP2wOXrQ3CIVXkH3kis7vpHtZ2xuTNPGDNuLGyvGPSG+
yCSf1yN29N9KAiP9Egw0QkABV0Ljb7wXUAdXX9WgzumjHUf6rqd8WWeofvscfOY3
kbp+Cka3Sj/QviiCloMx16bC
-----END PRIVATE KEY-----
"""


# The real ABDM wire shape (D6, corrected 2026-08-24): `publicKey` is base64-encoded DER
# SubjectPublicKeyInfo, not PEM text. This literal is hardcoded, not computed from
# STUB_PUBLIC_KEY_PEM via the `cryptography` library at test time: a computed fixture would only
# prove `cryptography` round-trips with itself, the same fabrication defect that let the old
# live-mode mock send raw PEM and pass. By RFC 7468 a PEM body IS the base64 of the DER, so this
# literal is simply STUB_PUBLIC_KEY_PEM's six body lines concatenated — auditable by eye, no
# library needed. Shape: len 392, first 8 MIIBIjAN, last 8 ywIDAQAB, 2048-bit (real ABDM cert
# observed 2026-08-24: len 736, first 8 MIICIjAN, last 8 AwEAAQ==, 4096-bit — differs only by key
# size, per the design memo's correction: do not assert the ABDM tail bytes against this stub).
STUB_PUBLIC_KEY_B64_DER = (
    "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA4Z8PDv5t+ScPKLcgLEVT"
    "CAKk3YbdC7SQQnM80/y4T/q58gUprbx8AfNhfix0ubzeg3nyra0b+4P2s2Lfam0q"
    "IOlVwEMaT0rdKOa70QJsIJjwaVbmxsZ4PinuTYpBwg92xpCAOCtQGYDqp2ib1moy"
    "Igf6gM6m9eF+POSxGIvsZ4vtRL+HodJMlUEsH7bSz/wiLsk86NthtDvhX6CWr77k"
    "QiE3X7fnaGce+ILy3f3f8ovaxK2v1wOgnIc+tvOMZeJ5pweFWEW5q3IMUnds4g/s"
    "lUzYtvFXLicGaKyUYQbE+zzZq++9QS6nqQ8Lwv70RuGhCj/rgcj88I5+eg0Mvnt0"
    "ywIDAQAB"
)


def _decrypt(ciphertext_b64: str) -> str:
    private_key = serialization.load_pem_private_key(_TEST_PRIVATE_KEY_PEM.encode(), password=None)
    plaintext = private_key.decrypt(  # type: ignore[union-attr]
        base64.b64decode(ciphertext_b64),
        padding.OAEP(
            mgf=padding.MGF1(algorithm=hashes.SHA1()),  # noqa: S303
            algorithm=hashes.SHA1(),  # noqa: S303
            label=None,
        ),
    )
    return plaintext.decode("utf-8")


def test_encrypt_round_trip_against_known_test_key() -> None:
    ciphertext = encrypt_oaep_sha1("123456789012", STUB_PUBLIC_KEY_PEM)
    assert _decrypt(ciphertext) == "123456789012"


def test_encrypt_is_non_deterministic() -> None:
    """OAEP includes random padding; two encryptions of the same plaintext must not match. A
    match would mean the padding scheme silently degraded to something deterministic."""
    a = encrypt_oaep_sha1("111111", STUB_PUBLIC_KEY_PEM)
    b = encrypt_oaep_sha1("111111", STUB_PUBLIC_KEY_PEM)
    assert a != b
    assert _decrypt(a) == _decrypt(b) == "111111"


def test_wrong_key_cannot_decrypt() -> None:
    """Confirms the round trip is actually exercising RSA, not silently succeeding some other way:
    a ciphertext produced with a different keypair must not decrypt with the test private key."""
    from cryptography.hazmat.primitives.asymmetric import rsa

    other_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    other_public_pem = (
        other_key.public_key()
        .public_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PublicFormat.SubjectPublicKeyInfo,
        )
        .decode()
    )
    ciphertext = encrypt_oaep_sha1("999999", other_public_pem)
    with pytest.raises(ValueError):
        _decrypt(ciphertext)


async def test_fetch_public_key_pem_stub_mode_returns_the_fixed_test_key() -> None:
    pem = await fetch_public_key_pem(
        mode="stub", cert_url="https://example.invalid", gateway_token=None
    )
    assert pem == STUB_PUBLIC_KEY_PEM


def _patch_transport(monkeypatch: pytest.MonkeyPatch, handler: object) -> dict:  # type: ignore[type-arg]
    import httpx

    captured: dict[str, httpx.Request] = {}

    def wrapped_handler(request: httpx.Request) -> httpx.Response:
        captured["request"] = request
        return handler(request)  # type: ignore[operator]

    real_client_init = httpx.AsyncClient.__init__

    def patched_init(self: httpx.AsyncClient, *args: object, **kwargs: object) -> None:
        kwargs["transport"] = httpx.MockTransport(wrapped_handler)
        real_client_init(self, *args, **kwargs)  # type: ignore[arg-type]

    monkeypatch.setattr(httpx.AsyncClient, "__init__", patched_init)
    return captured


async def test_fetch_public_key_pem_live_mode_parses_json_wrapped_response(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Live mode GETs `cert_url` with the gateway token as a Bearer header and parses the real
    ABDM response shape: JSON with `publicKey` (base64-encoded DER SubjectPublicKeyInfo, D6
    corrected 2026-08-24) and `encryptionAlgorithm` fields, not raw PEM text. Confirmed against a
    local httpx MockTransport rather than a real ABDM call."""
    import httpx

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "publicKey": STUB_PUBLIC_KEY_B64_DER,
                "encryptionAlgorithm": "RSA/ECB/OAEPWithSHA-1AndMGF1Padding",
            },
        )

    captured = _patch_transport(monkeypatch, handler)

    pem = await fetch_public_key_pem(
        mode="live", cert_url="https://example.invalid/cert", gateway_token="gw-token-123"
    )
    returned_key = serialization.load_pem_public_key(pem.encode("utf-8"))
    stub_key = serialization.load_pem_public_key(STUB_PUBLIC_KEY_PEM.encode("utf-8"))
    assert returned_key.public_numbers() == stub_key.public_numbers()  # type: ignore[union-attr]
    request = captured["request"]
    assert request.headers["Authorization"] == "Bearer gw-token-123"
    assert "REQUEST-ID" in request.headers
    assert "TIMESTAMP" in request.headers


async def test_fetch_public_key_pem_live_mode_rejects_malformed_base64(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """A publicKey that isn't valid base64 must raise SamdError(ABHA_UPSTREAM_ERROR), not a bare
    binascii.Error. Before the fix, this exception was caught by the try block shared with
    httpx.HTTPError and misrouted to _result_from_transport_error (RETRYABLE); a cert this module
    cannot parse is never retryable."""
    import httpx
    from app.errors import ErrorCode, SamdError

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "publicKey": "not!!!valid!!!base64@@@",
                "encryptionAlgorithm": "RSA/ECB/OAEPWithSHA-1AndMGF1Padding",
            },
        )

    _patch_transport(monkeypatch, handler)

    with pytest.raises(SamdError) as exc_info:
        await fetch_public_key_pem(
            mode="live", cert_url="https://example.invalid/cert", gateway_token="gw-token-123"
        )
    assert exc_info.value.code == ErrorCode.ABHA_UPSTREAM_ERROR


async def test_fetch_public_key_pem_live_mode_rejects_valid_base64_non_der(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Valid base64 that does not decode to a DER SubjectPublicKeyInfo must also raise
    SamdError(ABHA_UPSTREAM_ERROR)."""
    import httpx
    from app.errors import ErrorCode, SamdError

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "publicKey": base64.b64encode(b"not a DER SubjectPublicKeyInfo").decode(),
                "encryptionAlgorithm": "RSA/ECB/OAEPWithSHA-1AndMGF1Padding",
            },
        )

    _patch_transport(monkeypatch, handler)

    with pytest.raises(SamdError) as exc_info:
        await fetch_public_key_pem(
            mode="live", cert_url="https://example.invalid/cert", gateway_token="gw-token-123"
        )
    assert exc_info.value.code == ErrorCode.ABHA_UPSTREAM_ERROR


async def test_fetch_public_key_pem_live_mode_rejects_missing_publickey_field(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """A response missing publicKey entirely must raise SamdError(ABHA_UPSTREAM_ERROR), the same
    NON_RETRYABLE class as every other malformed-cert case (previously this raised KeyError and
    was misrouted to RETRYABLE)."""
    import httpx
    from app.errors import ErrorCode, SamdError

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={"encryptionAlgorithm": "RSA/ECB/OAEPWithSHA-1AndMGF1Padding"},
        )

    _patch_transport(monkeypatch, handler)

    with pytest.raises(SamdError) as exc_info:
        await fetch_public_key_pem(
            mode="live", cert_url="https://example.invalid/cert", gateway_token="gw-token-123"
        )
    assert exc_info.value.code == ErrorCode.ABHA_UPSTREAM_ERROR


def test_stub_public_key_b64_der_matches_stub_pem() -> None:
    """Anti-fabrication guard: proves STUB_PUBLIC_KEY_B64_DER is genuinely the base64-DER
    encoding of STUB_PUBLIC_KEY_PEM, not a made-up literal that happens to parse. A mock that
    fabricates the wire shape is not a test of the wire shape."""
    decoded = base64.b64decode(STUB_PUBLIC_KEY_B64_DER, validate=True)
    stub_key = serialization.load_pem_public_key(STUB_PUBLIC_KEY_PEM.encode("utf-8"))
    expected_der = stub_key.public_bytes(  # type: ignore[union-attr]
        encoding=serialization.Encoding.DER,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    assert decoded == expected_der


async def test_fetch_public_key_pem_live_mode_rejects_unexpected_encryption_algorithm(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """If ABDM ever changes `encryptionAlgorithm` away from the one cipher this module
    implements, fetch_public_key_pem must fail loudly at fetch time, before any Aadhaar/OTP value
    is encrypted against a scheme this module no longer matches."""
    import httpx
    from app.errors import ErrorCode, SamdError

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "publicKey": STUB_PUBLIC_KEY_B64_DER,
                "encryptionAlgorithm": "RSA/ECB/PKCS1Padding",
            },
        )

    _patch_transport(monkeypatch, handler)

    with pytest.raises(SamdError) as exc_info:
        await fetch_public_key_pem(
            mode="live", cert_url="https://example.invalid/cert", gateway_token="gw-token-123"
        )
    assert exc_info.value.code == ErrorCode.ABHA_UPSTREAM_ERROR
