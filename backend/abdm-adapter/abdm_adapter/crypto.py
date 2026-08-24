"""RSA-OAEP-SHA1 encryption for the V3 cert boundary, and the cert fetch/cache.

Cipher, confirmed from `abha api docs/get started/encodingndrsaencryption.md` and unchanged by
any of the Phase A/B findings: `RSA/ECB/OAEPWithSHA-1AndMGF1Padding`. In the `cryptography`
library's own vocabulary that is OAEP with both the hash and the MGF1 mask hash set to SHA-1, no
label. Every Aadhaar number, OTP value, and mobile number this adapter sends to ABDM goes through
`encrypt_oaep_sha1` before it leaves this process; nothing else in this package does its own RSA.

Cert URL (D1, Phase A/B correction): `abhasbx.abdm.gov.in/abha/api/v3/profile/public/certificate`,
from the real Postman "Cert API" request, not the `healthidsbx.abdm.gov.in/api/v1/auth/cert` value
`get started/encodingndrsaencryption.md` and this same setting's own Phase 1 default both carried
before this session. See `app.config.Settings.abdm_cert_url`.

Stub mode uses a fixed, locally generated 2048-bit test RSA keypair, never ABDM's real cert. The
private half exists only in `tests/test_crypto.py`, to decrypt and verify the round trip; nothing
in this module or anywhere under `abdm_adapter/` holds it. This is what lets the crypto round-trip
test exercise the real encryption code (padding scheme, key loading, base64 framing) without a
live ABDM call, and it is also why the round trip passing does NOT prove `abdm_cert_url` is
correct: the stub path never fetches that URL at all. Live-activation checklist item, not a test.
"""

from __future__ import annotations

import base64
import binascii

import httpx
from app.errors import ErrorCode, SamdError
from cryptography.exceptions import UnsupportedAlgorithm
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa

from abdm_adapter.request_context import abdm_headers

# The one cipher this module implements. D6 (Phase 5 live wiring, 2026-08-19): the real ABDM cert
# response is JSON-wrapped and names its own algorithm (`encryptionAlgorithm`), confirmed against
# a real sandbox call, not assumed. If ABDM ever returns a different value here, encrypting
# against `publicKey` anyway would silently produce ciphertext ABDM cannot decrypt; failing loudly
# at fetch time, before any Aadhaar/OTP encryption is attempted, is cheaper than debugging that.
#
# D6 correction (watched live M1 run, 2026-08-24): the JSON wrapper above was confirmed correct,
# but `publicKey`'s own value was not: it is base64-encoded DER SubjectPublicKeyInfo, not PEM
# text. Verbatim finding from the watched run: "ABDM sandbox GET
# /abha/api/v3/profile/public/certificate returns HTTP 200, content-type: application/json, body
# shape {"publicKey": "<base64-DER>", "encryptionAlgorithm": "RSA/ECB/OAEPWithSHA-1AndMGF1Padding"}.
# The publicKey value is a base64-encoded DER SubjectPublicKeyInfo, not a PEM string. Length
# observed: 736 chars, decoding to a 4096-bit RSA public key. First 8 chars MIICIjAN, last 8 chars
# AwEAAQ==. Confirmed 2026-08-24 via probe against abhasbx.abdm.gov.in from a live-mode backend
# container." `fetch_public_key_pem` below decodes and re-serializes to PEM so every caller keeps
# taking PEM; nothing downstream of this module ever learns the wire format changed.
EXPECTED_ENCRYPTION_ALGORITHM = "RSA/ECB/OAEPWithSHA-1AndMGF1Padding"

# Fixed 2048-bit RSA public key, stub-mode only. Generated once for this session; the matching
# private key lives only in tests/test_crypto.py. Never treat this as a real ABDM cert: it exists
# solely so ABDM_MODE=stub can exercise real encryption without a network call.
STUB_PUBLIC_KEY_PEM = """-----BEGIN PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA4Z8PDv5t+ScPKLcgLEVT
CAKk3YbdC7SQQnM80/y4T/q58gUprbx8AfNhfix0ubzeg3nyra0b+4P2s2Lfam0q
IOlVwEMaT0rdKOa70QJsIJjwaVbmxsZ4PinuTYpBwg92xpCAOCtQGYDqp2ib1moy
Igf6gM6m9eF+POSxGIvsZ4vtRL+HodJMlUEsH7bSz/wiLsk86NthtDvhX6CWr77k
QiE3X7fnaGce+ILy3f3f8ovaxK2v1wOgnIc+tvOMZeJ5pweFWEW5q3IMUnds4g/s
lUzYtvFXLicGaKyUYQbE+zzZq++9QS6nqQ8Lwv70RuGhCj/rgcj88I5+eg0Mvnt0
ywIDAQAB
-----END PUBLIC KEY-----
"""


def encrypt_oaep_sha1(plaintext: str, public_key_pem: str) -> str:
    """Encrypt one plaintext value (Aadhaar number, OTP, or mobile number) for the V3 wire.

    Returns base64, matching what every recorded Postman example puts in `loginId`/`otpValue`.
    Takes the PEM directly rather than a cached object, so a stub-mode call and a live-mode call
    (once creds exist) go through the exact same function with no branch inside it; only the PEM
    source differs, in `fetch_public_key_pem` below.
    """
    public_key = serialization.load_pem_public_key(public_key_pem.encode("utf-8"))
    ciphertext = public_key.encrypt(  # type: ignore[union-attr]
        plaintext.encode("utf-8"),
        padding.OAEP(
            mgf=padding.MGF1(algorithm=hashes.SHA1()),  # noqa: S303 (ABDM's own cipher, not ours to choose)
            algorithm=hashes.SHA1(),  # noqa: S303
            label=None,
        ),
    )
    return base64.b64encode(ciphertext).decode("ascii")


async def fetch_public_key_pem(
    *,
    mode: str,
    cert_url: str,
    gateway_token: str | None,
    timeout_seconds: float = 30.0,
) -> str:
    """The V3 cert, as a PEM string ready for `encrypt_oaep_sha1`.

    Stub mode returns the fixed test key above, no network call. Live mode GETs `cert_url` with
    the gateway session token (the Postman "Cert API" request's own auth) and parses the response
    as JSON: `{"publicKey": "<base64-DER>", "encryptionAlgorithm":
    "RSA/ECB/OAEPWithSHA-1AndMGF1Padding"}` (D6, corrected 2026-08-24 — see the comment on
    `EXPECTED_ENCRYPTION_ALGORITHM` above for the watched-run finding that caught this).
    `encryptionAlgorithm` is asserted against `EXPECTED_ENCRYPTION_ALGORITHM` before the key is
    parsed: this is the one place that can catch ABDM changing the cipher before this module
    silently encrypts against a scheme it no longer matches. `publicKey` is then base64-decoded
    (`validate=True`: the default silently drops invalid characters and can turn corrupt input
    into plausible-looking DER) and parsed as a DER SubjectPublicKeyInfo, then re-serialized to
    PEM so every caller of this function keeps taking PEM regardless of the wire format.

    Every failure mode below — bad algorithm, missing `publicKey`, malformed base64, base64 that
    isn't a valid DER SubjectPublicKeyInfo — raises `SamdError(ABHA_UPSTREAM_ERROR)`, the same
    classified error the rest of this package's upstream failures use. This is deliberately
    unified: a cert this module cannot use is never retryable (retrying can't fix a shape ABDM
    sent wrong), so every one of these must route through `_result_from_samd_error` in
    `service.py`, not `_result_from_transport_error`. Before the base64-DER fix, a malformed
    `publicKey` raised `binascii.Error`/`ValueError` from inside the try block that also covers
    `httpx.HTTPError`, misrouting it to `_result_from_transport_error` (RETRYABLE) and burning a
    real gateway session token retrying a call that could never succeed.
    """
    if mode == "stub":
        return STUB_PUBLIC_KEY_PEM
    async with httpx.AsyncClient(timeout=timeout_seconds) as http_client:
        response = await http_client.get(
            cert_url, headers=abdm_headers(gateway_token=gateway_token)
        )
        response.raise_for_status()
        body = response.json()

    algorithm = body.get("encryptionAlgorithm")
    if algorithm != EXPECTED_ENCRYPTION_ALGORITHM:
        raise SamdError(
            ErrorCode.ABHA_UPSTREAM_ERROR,
            detail=(
                "ABDM certificate endpoint returned an unexpected encryptionAlgorithm "
                f"({algorithm!r}), expected {EXPECTED_ENCRYPTION_ALGORITHM!r}."
            ),
        )

    public_key_b64 = body.get("publicKey")
    if not isinstance(public_key_b64, str):
        raise SamdError(
            ErrorCode.ABHA_UPSTREAM_ERROR,
            detail="ABDM certificate endpoint response was missing a publicKey field.",
        )
    try:
        der_bytes = base64.b64decode(public_key_b64, validate=True)
        public_key = serialization.load_der_public_key(der_bytes)
    except (binascii.Error, ValueError, UnsupportedAlgorithm) as exc:
        raise SamdError(
            ErrorCode.ABHA_UPSTREAM_ERROR,
            detail="ABDM certificate endpoint returned a publicKey that could not be parsed.",
        ) from exc

    # `load_der_public_key` accepts any valid SubjectPublicKeyInfo, not just RSA — an EC key
    # parses fine here but `encrypt_oaep_sha1` cannot use it (no `.encrypt()` on an EC public
    # key), and `submit_identity`/`verify_otp`/`verify_mobile_otp` in service.py catch only
    # `ValueError` around that call, so the AttributeError would escape uncaught instead of
    # landing as the classified NON_RETRYABLE error every other malformed-cert case gets here.
    if not isinstance(public_key, rsa.RSAPublicKey):
        raise SamdError(
            ErrorCode.ABHA_UPSTREAM_ERROR,
            detail="ABDM certificate endpoint returned a publicKey that is not RSA.",
        )

    return public_key.public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    ).decode("ascii")
