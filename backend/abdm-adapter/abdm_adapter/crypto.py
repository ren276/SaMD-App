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

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding

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


async def fetch_public_key_pem(*, mode: str, cert_url: str, gateway_token: str | None) -> str:
    """The V3 cert, as a PEM string ready for `encrypt_oaep_sha1`.

    Stub mode returns the fixed test key above, no network call. Live mode would GET `cert_url`
    with the gateway session token and parse the certificate out of the response; not implemented
    here (ABDM_MODE=live is not exercised this session, per the brief) beyond raising loudly if it
    is ever reached, so a misconfigured live attempt fails immediately instead of silently
    encrypting against a stub key it should not be using.
    """
    if mode == "stub":
        return STUB_PUBLIC_KEY_PEM
    raise NotImplementedError(
        "ABDM_MODE=live cert fetch is not implemented this session. Building it is Phase 5's "
        "live-activation work, gated on real ABDM sandbox credentials arriving; see PROGRESS.md."
    )
