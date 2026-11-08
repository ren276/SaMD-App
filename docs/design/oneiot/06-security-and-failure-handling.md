Status: RESEARCH, not a design ruling. Sources read: all Kotlin under `K/`, `app/src/main/python/*.py`, Pi and cloud Python (connection values masked in `cloud.py:12-30` and `ipverify.py:22-33`), `app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/*.xml`, `publicKey.txt` (length and type only), TIH sections 8.1, 8.4, 8.5. Not opened: `privateKey.txt`, `app/google-services.json`. Nothing was run: failure effects are traced by reading.

# 06: Security and failure handling

Paths are relative to `/media/sandesh/extra-ssd/AndroidWork/OneIOTApp`. `K/` means `app/src/main/java/com/example/iot1/`.

## 1. Key material ruling (R0-3)

**Ruling recorded from the operator (2026-11-06):**
- `privateKey.txt` and `app/google-services.json` are treated as compromised material.
- The key pair and OneIoT's crypto scheme must never be reused in SaMD.
- Whether the source GitHub repo is public is an open question for the operator. It was not searched for (R0-3).

Superseded by S2 (operator, 2026-11-06): operator states privateKey.txt and app/google-services.json are probably rotated; rule 1 unchanged, never read.

Operator informed of hardcoded DB credentials at `cloud.py:16-17` and `ipverify.py:28-29` and the Thing ID at `ipverify.py:38`.

Supporting facts:
- The paper names a public code location, github.com/nayancyiiti/IoT and a Zenodo DOI (paper p.23, p.25, DOCS-ONLY, not fetched).
- `.gitignore` here ignores `local.properties` but not `privateKey.txt` or `google-services.json` (VERIFIED). So if this tree was ever pushed, both may have gone with it (INFERRED).
- The same phone public key is hardcoded in two classes (`K/ThingDetailsActivity.kt:206`, `K/BluetoothMainScreen.kt:131`) and equals `publicKey.txt` (VERIFIED by comparison). So every install shares one phone key pair.

**Also flagged for the operator, paths only:** hardcoded database login values exist at `cloud.py:16-17` and `ipverify.py:28-29`, and a hardcoded Thing ID at `ipverify.py:38`. Values are not reproduced. If those databases still exist, these need rotating.

## 2. F8 and R0-6e: crypto as built

| Item | Fact | Evidence | Tag |
|---|---|---|---|
| Cipher | RSA OAEP, SHA-256, MGF1 SHA-256, no label | `app/src/main/python/encrypt.py:15-22`, `thing_mobile.py` via `encryptMessage_test.py:11-18` | VERIFIED |
| Key size | RSA-2048: base64 body is 372 chars | `publicKey.txt`, TIH 8.1 | VERIFIED length, INFERRED size |
| What is encrypted | Commands and ACKs only | `K/DisplayThingActivity.kt:500`, `thing_mobile.py:101` | VERIFIED |
| What is not | Manifest, public keys, HTTP calls, Thing ID and Thing Key over Bluetooth, Thing Key over HTTP | `K/ThingDetailsActivity.kt:213-214`, `K/BluetoothMainScreen.kt:126-133`, `K/ApiService.kt:37` | VERIFIED |
| Hash | MD5 of the plaintext, inside the ciphertext. Acts as a corruption check, gives no authenticity | `app/src/main/python/hash.py:3-6` | VERIFIED |
| Passwords | Unsalted SHA-256 in EncryptedSharedPreferences | `K/RegisterActivity.kt:123-140` | VERIFIED |
| Phone private key location | Three different paths, none written by the app: `/data/data/.../files/privateKey.txt`, external `files/privateKey.txt`, external `files/Documents/privateKey.txt` | `K/DisplayThingActivity.kt:833`, `K/BluetoothDisplayThingActivity.kt:622`, `K/Adhoc.kt:490` | VERIFIED |
| Provisioning | By hand, as nothing writes the file (INFERRED). Without it, every ACK fails to decrypt | same | INFERRED |
| Pi private key | Plain file read for each message | `thing_mobile.py:52-53` | VERIFIED |
| Key exchange | Each side sends its public key in cleartext at manifest time. Thing key found by searching for the text `ssh-rsa`. No pinning or fingerprint | `K/ThingDetailsActivity.kt:213,239-246`, `send.py:39-42` | VERIFIED |
| Why Python, not Kotlin | Not stated in any doc. Plausibly to share code and the OpenSSH key format with the Pi | none | INFERRED, open question |
| Cost of Python | Chaquopy, native interpreter, `cryptography` wheel: APK is 46.3 MB | `01` section 4 | VERIFIED |

### Scheme weaknesses (INFERRED from the above)

| # | Weakness | Attack |
|---|---|---|
| 1 | No authentication of commands | Anyone holding the Thing's public key can send valid commands. `send.py` gives that key to whoever asks |
| 2 | Key swap | An on-path attacker replaces the public key in the cleartext manifest fetch, then reads and alters all commands |
| 3 | No replay protection | No nonce, timestamp or sequence number. A captured ciphertext is accepted again |
| 4 | Forgeable allowlist | `send.py:56-60` stores the IP claimed inside the unauthenticated message. The attacker declares its own IP |
| 5 | Phone key overwrite | `send.py:41-42` writes the received key to disk before checking anything |
| 6 | Shared phone key | Compromise of one phone exposes ACKs for all |
| 7 | Device has DB write | `ipverify.py` holds DB credentials on the device, so one stolen Pi yields DB access |
| 8 | MD5 | Not safe as an integrity control against an attacker, though it sits inside RSA here |

## 3. Device trust and input validation

| Area | Finding | Evidence |
|---|---|---|
| Manifest integrity | None: no signature, hash or pin | `K/ThingDetailsActivity.kt:222-250` |
| Manifest fetch auth | None: Thing ID and key are not sent. Only the fixed string and an MD5 | `send.py:62` |
| Thing Key check | Query ignores Thing ID: any valid key from any vendor row passes | `cloud.py:140-142` |
| OTP | Client-side, shown in a toast. `nextInt(0, 9999)` | `K/RegisterActivity.kt:152-154` |
| Offline login | Hash compare, then a plain persistent flag. No logout | `K/MainActivity.kt:100-103,109-118` |
| Cloud auth | None on any route. `get_user_ip` gives any device's address to anyone | `cloud.py:173-197` |
| Flask mode | `debug=True` on `0.0.0.0`, so the Werkzeug debugger is exposed (INFERRED risk) | `cloud.py:218` |
| SQL | Parameterised, this part is sound | `cloud.py:90-91,184-188` |
| Error leakage | `str(e)` returned to client | `cloud.py:112,165,207` |
| Cleartext | `usesCleartextTraffic="true"` plus config for the one host | `app/src/main/AndroidManifest.xml:26`, `app/src/main/res/xml/network_security_config.xml` |
| Exported components | 8 of 9 activities exported, most with no filter. `DisplayThingActivity` takes `ip_address` from any caller | `app/src/main/AndroidManifest.xml:39-81`, `K/DisplayThingActivity.kt:80` |
| Secrets in logs | Password and username logged on failed login; hashes logged; HTTP BODY logging includes `userpassword` and personal data; encrypted messages logged | `K/MainActivity.kt:97-98,105`, `K/ApiService.kt:40-42`, `K/BluetoothDisplayThingActivity.kt:503` |
| Thing Key at rest | Plain text in SQLite | `K/DBHelper.kt:16,46` |
| Backup | `allowBackup="true"`, and rules files exist | `app/src/main/AndroidManifest.xml:25,27-28` |
| Pi privilege | Scripts spawn each other through `sudo -E` | `send.py:90`, `thing_mobile.py:133,141` |
| Hardcoded addresses | Server IP, DB IPs, MAC, user home path | `K/ApiService.kt:37`, `cloud.py:14`, `ipverify.py:26`, `K/DisplayThingActivity.kt:59`, `send.py:41` |

Conclusion: the scheme gives some confidentiality against a passive sniffer of command traffic after setup. It gives no meaningful authentication or integrity against an active attacker (INFERRED).

## 4. F9: failure modes

Effect column is traced by reading unless marked otherwise.

| Scenario | Current behaviour | Evidence | Consequence |
|---|---|---|---|
| Phone leaves network mid-manifest fetch | Read loop ends or throws. Partial bytes stay in `manifest.json`. Error only logged | `K/ThingDetailsActivity.kt:223-232,254-256` | Thing shows as added; next open parses a partial file and crashes |
| `ssh-rsa` key never arrives | Whole content stays as manifest; no `thingPublicKey.txt` | `K/ThingDetailsActivity.kt:240` | All encryption fails later, command dropped with a log line |
| Pi offline when manifest fetched | Socket throws, logged. Registration already reported success | `K/ThingDetailsActivity.kt:136-143,205` | Thing listed with no usable manifest |
| Cloud down when getting IP | `default_ip` stored as the address | `K/ThingDetailsActivity.kt:343-351,176` | Silent, permanently broken Thing until re-added |
| Device IP changes after add | Old IP kept forever | `K/ThingDetailsActivity.kt:169-176` | Connect fails, then Bluetooth fallback |
| Open an old Thing after adding a new one | Loads the newest manifest | `K/DisplayThingActivity.kt:83` | **Wrong controls for the device** |
| Disconnect mid-command, IP | Up to 3 attempts, then Bluetooth, logs only | `K/DisplayThingActivity.kt:508-556` | User sees nothing |
| ACK lost, command received | Resends same command | `K/DisplayThingActivity.kt:508-512` | **Duplicate execution**, no dedupe on device |
| Phone's private key missing | Decrypt returns null, so ACK counts as failure | `K/DisplayThingActivity.kt:520-521,843-845` | Every command sent 3 times, then transport switch |
| Empty read on Pi (peer closed) | `decrypt_message(b"")` raises. Only `BlockingIOError` is caught | `thing_mobile.py:48-56,107` | Script dies, parent `subprocess.run(check=True)` raises, no restart |
| Malformed or wrong-key message | Same uncaught exception | `thing_mobile.py:55-56` | One bad packet kills the device service |
| Idle for 15 s | Device leaves IP mode for Bluetooth, for good | `thing_mobile.py:109,130-135` | Normal idling degrades the session |
| Fallback message format | Pi `IndexError`, uncaught | `05` section 6 | Fallback kills the Bluetooth script |
| Bluetooth off during fallback | Toast from worker thread | `K/DisplayThingActivity.kt:493,547,644` | Likely crash |
| Bluetooth connect on main thread | Up to about 12 s block (TIH 7.2) | `K/BluetoothDisplayThingActivity.kt:82,127,146` | ANR risk |
| Manifest split race | `SplitFiles` runs before receive completes | `K/BluetoothMainScreen.kt:134-135` | Empty or partial manifest; missing file ends the flow with no message |
| Malformed manifest | Uncaught parse exception | `K/DisplayThingActivity.kt:94` | Crash loop on that Thing |
| Duplicate manifest send | Not applicable: one shot | `send.py:24` | Second add fails until scripts restart by hand |
| App killed | Socket dropped; no state to restore | `K/DisplayThingActivity.kt:725-735` | Pi hits empty-read failure |
| Process death | Reconnects; display resets to `min` | `K/DisplayThingActivity.kt:348` | Screen disagrees with device |
| Device reboot | No unit restarts scripts | `01` section 8 | Silent outage until manual restart |
| Network reachable, host not | `isNetworkAvailable` checks link only | `K/CheckActivity.kt:35-39` | Wrong mode chosen: IP chosen with no route, Bluetooth path skipped |

Paths with **no** failure visible to the user: all command-send failures, tamper warnings, checksum mismatches (`K/DisplayThingActivity.kt:530,586,805`).

## 5. Finished, demo-only, stubbed, dead (F10)

| Status | Parts |
|---|---|
| Works in the sense of "demo path" | Register, add Thing, IP manifest fetch, draw AC screen, send range and mode over IP and Bluetooth with ACK. Needs manual key placement and Pi setup |
| Demo-only | OTP, vendor spinner values, hardcoded MAC, `cloud.py` on a dev IP, AC layout labels |
| Stubbed or missing | `/validate_user` route, sensors, GET, modes' values, Fan, Swing and Power sending, SMF, configuration phase, IP refresh |
| Broken | Adhoc path; Wi-Fi to Bluetooth fallback format |
| Dead | `android_grammer_rules`, `grammar_parser.py`, `testingGrammer.txt`, `fetch.py`, `output.py`, `kotlintopython.py`, `IPLocation.py`, `thing_mobile_bluetooth.py`, `Untitled Document 1`, JDBC dependencies, FileProvider (0 uses in Kotlin), `string-array` resources. `hashverify` runs only on the older ACK path (`K/DisplayThingActivity.kt:624,772-807`) |

## 6. Amendment S2: key file status (additions only)

Superseding the first bullet of section 1: `privateKey.txt` and `app/google-services.json` are **present, unread, operator states rotated**. Neither was opened. R0-3 still stands: the key pair and OneIoT's crypto scheme are never reused in SaMD. Checking public repo history (A5) is optional, for the record only, and has not been done because no clone was provided.

Under S1 the demo may keep this scheme inside the separate OneIoT app. Whether it does is an operator decision (`07b` section 7, row 11).

## 7. Addendum A2: three documented security stories vs the code

| Aspect | (a) Paper p.23-24 | (b) TIH D1 and D5 (8.1, 8.5) | (c) TIH D4 (8.4) | (d) Code |
|---|---|---|---|---|
| Cipher | "Encrypting raw data end to end" with accepted standards, unspecified | RSA-2048 OAEP, SHA-256, MGF1 | AES-128 | **RSA OAEP SHA-256 MGF1**: `app/src/main/python/encrypt.py:15-22`, `encryptMessage_test.py:11-18`. Key size only inferred (`publicKey.txt`, 372 base64 chars) |
| Salting | Random salt per encryption | OAEP randomisation | Per-message salt | OAEP's own randomness only. No separate salt |
| Coverage | "In all phases" (p.9) | "All communications" (8.1) | All messages | **Commands and ACKs only.** Not manifest, keys, registration or Thing credentials (section 2) |
| Integrity | Not specified beyond validity check | MD5 inside the packet | Not stated | **MD5 inside the plaintext**: `K/DisplayThingActivity.kt:498-500`, `thing_mobile.py:80-83` |
| Packet | Header with protocol, version, encryption type | Message plus IP plus checksum (Fig 9) | JSON envelope | **Matches (b)**: `Data:<v>+IP:<ip>,<md5>`, `K/DisplayThingActivity.kt:497-499`. No header |
| Auth | Device key or an OAuth2-like revocable key | ThingId and ThingKey | OAuth2 bearer | **ThingKey at registration only** and Bluetooth handshake: `cloud.py:140`, `blue.py:57-58`. Commands carry no credential. 0 hits for `OAuth`, `Bearer`, `token` |
| Transport QoS | Not stated | Not stated | MQTT QoS 1, CoAP confirmable | **None.** TCP, RFCOMM. 0 hits for MQTT or CoAP |
| Hybrid scheme | Not stated | No | AES implies yes | **None.** RSA alone, one block per message |
| Key loading | Not stated | Not stated | Not stated | See below |

Conclusion: (b) describes the code, apart from coverage. (a) is aspirational, and (c) describes a system that is not in this folder (VERIFIED by search; absence here does not prove it was never built elsewhere).

### How does a 375+ byte manifest fit 190-byte RSA blocks?

**It doesn't go through RSA at all. It is sent in plaintext.** No chunking, no hybrid scheme.

- RSA-2048 OAEP SHA-256 carries at most 190 bytes per block (`05` section 4, EXTERNAL RFC 8017).
- `ac.json` is 374 bytes compact, 1180 as stored (VERIFIED).
- `send.py:72-74` and `blue.py:68-74` read the file and call `sendall` on the raw bytes. The Thing public key follows, also raw (`send.py:77-79`, `blue.py:115-117`).
- The app reads it directly to a file (`K/ThingDetailsActivity.kt:223-231`, `K/BluetoothMainScreen.kt:205-213`).
- Over the limit, `encrypt_message` raises in Python, which Kotlin catches and returns null, and the command is dropped with a log line (`K/DisplayThingActivity.kt:502-505,823-826`, INFERRED for the exception).

So the most safety-relevant object, the file that decides what the screen shows, is the one thing with no confidentiality and no integrity.

### Key loading, from code only

| Key | Loaded from | How | Evidence |
|---|---|---|---|
| Phone private | App-private or external file, 3 paths | `File.readText`, then `load_pem_private_key(password=None)`, so an unencrypted PEM is expected | `K/DisplayThingActivity.kt:833-836`, `app/src/main/python/decrypt.py:9-13` |
| Thing public, on phone | External files dir, written at manifest time | `File.readText`, then `load_ssh_public_key` | `K/DisplayThingActivity.kt:811-815`, `app/src/main/python/encrypt.py:9-12` |
| Phone public | Kotlin string constant | Sent to Pi at manifest time | `K/ThingDetailsActivity.kt:206,213` |
| Thing private | `ThingPrivateKey.txt` in working dir, re-read per message | `open`, then `load_pem_private_key(password=None)` | `thing_mobile.py:52-56`, `decryptMessage_test.py:7-11` |
| Phone public, on Pi | `MobilePublicKey.txt`, overwritten by any requester | `open`, then `load_ssh_public_key` | `send.py:41-42`, `thing_mobile.py:99-101` |
| Android Keystore | Not used for these keys. Only behind EncryptedSharedPreferences for login hashes | | `K/RegisterActivity.kt:127-134` |
