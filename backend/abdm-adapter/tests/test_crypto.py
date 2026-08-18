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


async def test_fetch_public_key_pem_live_mode_is_not_implemented() -> None:
    """Live mode is not built this session (the brief: no live ABDM call, no credential wiring).
    This asserts that boundary is a loud failure, not a silent stub fallback."""
    with pytest.raises(NotImplementedError):
        await fetch_public_key_pem(
            mode="live", cert_url="https://example.invalid", gateway_token="x"
        )
