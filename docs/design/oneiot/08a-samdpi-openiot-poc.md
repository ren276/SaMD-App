Status: RESEARCH, not a design ruling. Sources read: SaMDPi at `bba2c6b`: `docs/OPENIOT_HEALTHSARK_CONNECTIVITY_POC_ARCHITECTURE.md` in full (no links to other docs inside it), `docs/CONNECTIVITY_TROUBLESHOOTING_AND_FIXES.md`, `DEPLOY.md`, `emulator/**` (`encoders.py` in full), `profiles/*.toml`, `deploy/systemd/*`, `deploy/avahi/*`, `deploy/setup_pi.sh`, sample-app `model/*.kt`. Helper scripts in the repo root were scanned masked only, not read. Tests were listed, not run.

# 08a: The "OpenIoT" POC in SaMDPi (R2-2)

`POC` means `docs/OPENIOT_HEALTHSARK_CONNECTIVITY_POC_ARCHITECTURE.md`. Paths are relative to `/media/sandesh/oneTB_SSD/SaMDPi`.

## 1. What "OpenIoT" means there

**OpenIoT-HealthSaRK is the name of SaMDPi's own emulator and sample-app POC.** It is a medical instrument emulator on a Pi, with BLE and Wi-Fi transports and an Android test client (`POC:1,25`).

| Question | Answer | Tag |
|---|---|---|
| Related to OneIoT in code? | **No.** 0 hits for `thingId`, `grammar`, `manifest.json`, `MMF`, `OneIoT` in `emulator`, `app/src`, `deploy`, `docs`, `profiles`, `tests` | VERIFIED |
| Uses manifests? | No. Instruments are fixed in code (`emulator/generator/instruments.py:19-80`) | VERIFIED |
| Shares OneIoT's protocol or crypto? | No. Plain HTTP and unauthenticated GATT (`POC:383-384`) | VERIFIED |
| Same organisation or lineage? | Name similarity only in the repo. The brief says OneIoT was "OpenIoT in older notes", so the two names can be confused | INFERRED; OQ for `14` |

So "OpenIoT" in SaMDPi does not mean the IIT Indore OneIoT platform. Memos should say **OneIoT** for the manifest platform and **SaMDPi POC** for this.

The POC doc is partly superseded: it describes one `kernel-hub` with one multi-service server. The hub path that runs today is two hubs, one instrument and one transport each (`DEPLOY.md:15-19`).

## 2. Normalized measurement model, field by field

**An implemented model exists.** Under R2-2's rule it is the baseline: new designs extend it, and no second competing model is created.

Sources: Python `emulator/models.py:53-75`, sample app `app/src/main/java/com/example/samdpi/model/NormalizedMeasurement.kt:44-63`, SaMD-App DTO `app/src/dev/java/com/example/samdapp/data/vitalssource/PiGatewayApi.kt:62-78` (study worktree).

| Field (wire name) | Type | Meaning | Python | SaMD DTO reads it? | Notes |
|---|---|---|---|---|---|
| `device_id` | str | Device id | `:58` | `deviceId`, not used by the mapper | Dev path `HealthSaRK-BP-001`; hub path overwritten with `<hub_id>-<instrument>` (`emulator/hubctx.py:179-181`, `server.py:267`) |
| `device_type` | str | `BLOOD_PRESSURE`, `PULSE_OXIMETER`, `THERMOMETER`... | `:59` | Yes, RC-1 gate | `instruments.py:22,32,42` |
| `measurement_id` | UUID str | Reading id | `:60` | **No** | Natural idempotency key, unused by SaMD today |
| `sequence_number` | int | Monotonic count | `:61` | **No** | |
| `measurement_type` | str | Observation name | `:62` | **No** | |
| `primary_value` | float | BP systolic, SpO2, temperature | `:63` | Yes | Positional meaning, by instrument |
| `unit` | str | Primary unit | `:64` | **Declared, never checked** | `PiMeasurementMapper` does not read it |
| `secondary_value`, `secondary_unit` | float, str | BP diastolic, SpO2 pulse | `:65-66` | Value yes, unit no | |
| `tertiary_value`, `tertiary_unit` | float, str | BP pulse | `:67-68` | Value yes, unit no | |
| `measured_at` | ISO str, nullable | Device time | `:69` | Yes, audited | Advisory (`envelope.py:12`) |
| `received_at` | ISO str | Receive time | `:70` | Declared | Hub fills it at generation; on BLE the phone fills it (`BleAcquisition.kt:176`) |
| `quality_status` | str | `OK`, `WARNING`, `ERROR`, `UNAVAILABLE` (`models.py:45-49`) | `:71` | Yes, RC-2 gate, exact "OK" | |
| `transport` | str | `WIFI`, `BLE` | `:72` | No | |
| `protocol` | str | `MOCK_WIFI_JSON`, `GATT_SIG` | `:73` | No | |
| `synthetic` | bool | Emulated flag | `:74` | Yes, audited | |
| `provenance` | map | Holds `session_id`, `instrument`, `scenario`, extras | `:75` | `session_id` only, RC-1 | Session id sits only in here |
| Hub additions: `hub_id` | str | Hub identity | `server.py:202` | Yes, 3 checks | Also `X-SaMDPi-Hub-Id` header (`:211`) |
| `profile_version`, `emulator_build`, `envelope_version` | int, obj, int | Build and format | `server.py:268-270` | Yes, `emulatorBuild` audited | |

### BLE form of the same reading

BLE carries no JSON. The Reading Envelope (`emulator/envelope.py:4-16`) holds envelope_version, profile_version, instrument code, flags (emulated), quality, 16-byte nonce, sequence, device_time, hub_id and the SIG bytes. The phone maps it into the same DTO (`BleAcquisition.kt:224-244`).

### Timestamp rule

`measured_at` is device time, null when no clock exists, never fabricated (`POC:229`). The phone's receive time is authoritative (`envelope.py:18`). Keep it.

### Weakness to fix when extending, not replacing (INFERRED)

Positional `primary`, `secondary`, `tertiary` are the same pattern as OneIoT's positional RVAL. Units are free strings, and only some are UCUM (`Cel`, `%` yes; `mmHg`, `bpm` no: `instruments.py:24-26,34-35,44`). No field holds a LOINC or MDC code. `CompositeVitalsTuple` holds MDC names in comments (`models.py:115-122`) but it is dev-only.

## 3. Doc claims vs code

Status at `POC:4` reads "Verified & Implemented". Checked claim by claim. Code wins.

| Claim | POC | Code | Verdict |
|---|---|---|---|
| Six instruments generate values | `:30,127-136` | `generator/profiles.py:45-127` | IMPLEMENTED |
| GATT services for six instruments | `:178-184` | Hub path: BP, SPO2, THERMOMETER only (`transports/ble/ble_hub.py:76-80`, `profile.py:28`). Dev server: 4 services, no glucose or weight (`real_ble_server.py:291-297`) | PARTIAL |
| Intermediate Cuff Pressure `0x2A36` | `:179` | 0 hits under `emulator/` | **DOCS-ONLY** |
| SpO2 and others via Notifications | `:180,182,184` | Hub path uses `indicate` for all (`ble_hub.py:342`) | Docs stale |
| BlueZ D-Bus GATT and advertising | `:177` | `ble_hub.py:277-289`, on `dbus_fast` | IMPLEMENTED |
| SFLOAT, FLOAT, 7-byte timestamp | `:186-188` | `encoders.py:16-79` | IMPLEMENTED |
| Wi-Fi over FastAPI, Uvicorn | `:32,408` | Stdlib `http.server` (`transports/wifi/server.py:4,11`); 0 hits for `fastapi` in `emulator`. `CONNECTIVITY:10` says `aiohttp`; also wrong | **DOCS-ONLY** |
| HTTP on port 8090, 5 endpoints | `:191-197` | `server.py:244-285,334-378`, `profiles/kernelhub1.toml:9` | IMPLEMENTED |
| LOW, HIGH values | `:146-147` | Close, not equal: HIGH BP is 175/105 (`profiles.py:55-56`), doc says 165/105 | Docs differ |
| `SENSOR_ERROR`, `SENSOR_DETACHED` | `:148-149` | `ERROR`, `UNAVAILABLE` (`profiles.py:34,37`) | Docs stale |
| Stale timestamp 24 h | `:150` | 2 h (`instruments.py:113-114`) | Docs stale |
| Duplicate injection | `:151` | `instruments.py:97-102` | IMPLEMENTED |
| Malformed payload injection | `:152` | `MALFORMED` falls to baseline values with quality OK (`profiles.py:58-59`). No corrupting code in the generator | **DOCS-ONLY** in the generator |
| Bounded sessions, remote stop | `:158-170` | `server.py:122-162`; hub forces `reading_count == 1` (`:296-300`) | IMPLEMENTED |
| `measured_at`, `received_at` | `:228-230` | `models.py:69-70` | IMPLEMENTED |
| Android state machine | `:241` | `AcquisitionStatus`, `NormalizedMeasurement.kt:31-42` | IMPLEMENTED in sample app |
| `ACCESS_LOCAL_NETWORK` | `:244-246` | Sample app; not re-checked here | Not checked |
| Security: cleartext, open GATT | `:383-384` | Matches: flags `read`, `write`, `indicate`, no encrypt flags (`ble_hub.py:337,342,350-351`) | IMPLEMENTED as stated |
| T1 to T16 pass | `:419-436` | All 16 test names exist in `tests/test_scenarios.py`. Not run here | Exists, not re-run |
| 36 pytest, 15 Android tests | `:451` | Now 265 `def test_` and 15 `@Test` | Docs stale, count grew |
| Vendor device survey | `:258-268` | No code, sources not checked | DOCS-ONLY |
| USB wired | `:368`; `CONNECTIVITY:263` | Sample app only: `USB_WIRED` (`NormalizedMeasurement.kt:17`), same HTTP client. Doc claims a live tether test; not reproducible on hub units (`09` section 5) | Sample app only |

## 4. Overlap with planned artifacts

| Planned (R1-6) | Already in SaMDPi | Rule for Phase 3 |
|---|---|---|
| Reading payload | `NormalizedMeasurement` plus hub fields, and the BLE envelope | **Extend, do not replace.** Add named values, UCUM, codes. Keep `measurement_id`, `quality_status`, `measured_at`, `received_at`, `synthetic`, `session_id`, `hub_id` |
| Protocol envelope | `POST start`, `GET measurement`, `POST stop`; BLE Control Point with START, STOP, nonce | One envelope can wrap these. BLE already has opcode and result codes (`control_point.py:44-55`) |
| Idempotency | `measurement_id`, BLE nonce plus sequence | Reuse |
| Session binding | `session_id` from hub, or phone nonce on BLE | Keep the RC-1 predicate. Patient and session binding stays in SaMD |
| Quality flags | `QualityStatus` | Reuse the same 4 values |
| Manifest v2 | **Nothing.** Instrument metadata only: `instruments.py:19-80` | New, and could be generated from this table so the two cannot drift (INFERRED) |
| Status and lifecycle | Wi-Fi `SessionStatus`, BLE hub state | Exists, thin. See `09` section 1 |
| UI rule | Sample app shows raw and normalized; SaMD has RC-3, RC-4 | SaMD rules stand |
| Discovery | Avahi `_samd-gw._tcp` with TXT records (`deploy/avahi/kernelhub1.service:21-26`) | Reuse the pattern (R4) |

**Conclusion:** Phase 3's reading payload and protocol extend this baseline. Only the manifest is new.
