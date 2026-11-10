Status: RESEARCH, not a design ruling. Sources read: memos `02` to `08a`; SaMDPi at `bba2c6b` (`emulator/**`, `profiles/*`, `deploy/**`, `DEPLOY.md`); study worktree at `4b9ee3c`; public standards linked in Appendix B. Manifests here are DRAFT, in this memo only. Nothing was run, and no hub or phone was touched.

Tag key: a claim with a file:line citation and no other tag is VERIFIED, read in code at the commit named above. A citation to another memo inherits that memo's tag. Anything else is marked INFERRED, DOCS-ONLY or EXTERNAL.

# 09: Mapping and gap analysis

`E/` is SaMDPi `emulator/`. `A/` is SaMD-App `app/src/main/java/com/example/samdapp/`. `D/` is `app/src/dev/java/com/example/samdapp/data/vitalssource/`. `K/` is OneIoT `app/src/main/java/com/example/iot1/`.

Headline findings:

1. **No hub serves a thermometer.** kernelhub1 serves SPO2 over Wi-Fi, kernelhub2 serves BP over BLE, one each by rule (`profiles/kernelhub1.toml:4`, `profiles/kernelhub2.toml:4`, `DEPLOY.md:21`).
2. **The emulator has no measurement lifecycle.** No inflating or measuring state exists on any hub path.
3. **S4's localhost idea cannot work as stated.** Both hub units block it (section 3).
4. **Hub links have no security to align with** (section 4). Relevant to R2-4b.
5. **Plausibility bounds have no owner today** (`08` section 5).

## 1. The instruments (R1-5a)

### Values and ranges

Formula: `variation = ((step % 5) - 2) * 0.5`, so one of -1, -0.5, 0, 0.5, 1 (`E/generator/profiles.py:40`). Hub paths also add jitter (`E/generator/instruments.py:108`, `E/transports/ble/reading.py:41`). Ranges below include both (INFERRED arithmetic from the cited lines).

| Instrument | Value | Wire position | Unit on Wi-Fi | NORMAL | LOW | HIGH | Rounding | Evidence |
|---|---|---|---|---|---|---|---|---|
| BP | Systolic | primary | `mmHg` | 117 to 123 | 82 to 88 | 171 to 179 | 1 dp | `profiles.py:46-63`, `instruments.py:24` |
| BP | Diastolic | secondary | `mmHg` | 78 to 82 | 53 to 57 | 102 to 108 | 1 dp | same, `:25` |
| BP | Pulse | tertiary | `bpm` | 70 to 74 | 50 to 54 | 109 to 115 | 1 dp | same, `:26` |
| BP | MAP | BLE only, computed | none | derived | derived | derived | n/a | `E/transports/ble/encoders.py:108` |
| SpO2 | Saturation | primary | `%` | 97.7 to 99.3 | 86.7 to 88.3 | 98.7 to 99.3, capped at 100 | 1 dp | `profiles.py:66-79`, `instruments.py:34` |
| SpO2 | Pulse | secondary | `bpm` | 70 to 74 | 56 to 60 | 112 to 118 | 1 dp | same, `:35` |
| Thermometer | Body temperature | primary | `Cel` | 36.5 to 36.9 | 34.9 to 35.3 | 38.9 to 39.5 | 2 dp | `profiles.py:82-91`, `instruments.py:44` |
| Thermometer | Temperature type | BLE only, fixed 2 (Body) | n/a | 2 | 2 | 2 | n/a | `encoders.py:152,171` |

### Timing

| Path | Behaviour | Evidence |
|---|---|---|
| Wi-Fi hub | **No simulated latency.** First reading exists before `start` returns | `E/transports/wifi/server.py:76-77` |
| Wi-Fi interval | Hub path forces `reading_count == 1`, so no timer runs | `server.py:296-300,142-147` |
| BLE hub | **No simulated latency.** Reading built when the Control Point SUCCESS is confirmed, then envelope, then SIG, each awaiting a confirm | `E/transports/ble/control_point.py:23-26,236-243,305` |
| Phone timeouts on BLE | Connect 10 s, each step 3 s, envelope 10 s, whole exchange 25 s | `D/ble/BleAcquisition.kt:34-38` |
| Realistic latencies | BP 2.5 to 4.5 s, SpO2 1.2 to 2.5 s, Thermometer 0.8 to 1.8 s. **Dev composite only**, not on hubs | `E/generator/multi_worker.py:35-42`, `DEPLOY.md:31-33` |

So a demo BP "measurement" completes instantly today. Any status UI would have nothing to show unless the adapter adds a delay.

### Lifecycle states

| Path | States | Evidence |
|---|---|---|
| Wi-Fi session | `IDLE`, `RUNNING`, `COMPLETED`, `STOPPED`. With count 1: `RUNNING` then `COMPLETED` inside one call | `E/models.py:38-42`, `server.py:142-147` |
| BLE session | `IDLE`, `RUNNING`, `DONE` | `control_point.py:151-154` |
| Dev composite | Per instrument: `PENDING`, `MEASURING`, `COMPLETED` | `multi_worker.py:169,189,231` |
| Cuff inflating, deflating, finger detected, stabilising | **None exist** | search of `E/` |
| Intermediate cuff pressure `0x2A36` | **None**: documented only | `08a` section 3 |

Manifest v2's STATUS and EVENT therefore describe what hubs must add, not what they do. Android's phase mapping is in `D/ble/BleAcquisition.kt:121-198`.

### Error cases

| Case | Wi-Fi exposure | BLE exposure | Phone result | Evidence |
|---|---|---|---|---|
| `DEVICE_ERROR` | HTTP 200, values 0.0, quality `ERROR` | Quality byte `ERROR`, SIG values NaN | `QUALITY_STATUS_NOT_OK` | `profiles.py:33-34`, `reading.py:47`, `encoders.py:102-105` |
| `SENSOR_UNAVAILABLE` | HTTP 200, values 0.0, quality `UNAVAILABLE` | Quality byte, NaN | `QUALITY_STATUS_NOT_OK` | `profiles.py:36-37` |
| `STALE_TIMESTAMP` | `measured_at` 2 h back | Not offered on BLE | Not gated: accepted | `instruments.py:112-115`, `reading.py:21-27` |
| `DUPLICATE` | Same measurement re-emitted | Not offered on BLE | Not relevant at count 1 | `instruments.py:97-102` |
| `MALFORMED` | Baseline values, quality OK | Not offered on BLE | Accepted | `profiles.py:58-59` |
| Wrong instrument | 400 `INSTRUMENT_NOT_SERVED` | CP `0x04` | `HUB_MISMATCH` | `server.py:290-291`, `control_point.py:281-282` |
| Wrong transport, count not 1 | 400 `INVALID_SESSION_CONFIG` | n/a | `MALFORMED` | `server.py:293-300` |
| Bad JSON, bad headers | 400 `MALFORMED_JSON`, `MALFORMED_REQUEST` | CP `0x03` | `MALFORMED` | `server.py:315-331` |
| No reading | 404 `NO_MEASUREMENT_AVAILABLE` | n/a | `NO_MEASUREMENT` | `server.py:273` |
| Second central | n/a | CP `0x05` BUSY, then disconnect | `HUB_MISMATCH` path or TIMEOUT | `control_point.py:171-172,216-217` |
| MTU under 89, CCCD off, reused nonce | n/a | CP `0x07`, `0x08`, `0x03` | Rejected | `control_point.py:279-286` |

**Stale reading note:** `GET /api/v1/measurement` keeps returning the last reading after `COMPLETED`, until stop (`server.py:157,174-176`). The phone relies on `session_id` (RC-1). A fresh envelope must keep this.

### How each path exposes a reading

| | Wi-Fi (kernelhub1 today) | BLE (kernelhub2 today) |
|---|---|---|
| Start | `POST /api/v1/session/start` JSON (`server.py:334-354`) | Write CP `0x01`, instrument, scenario, 16-byte nonce (`control_point.py:58,271-292`) |
| Reading | `GET /api/v1/measurement`, `NormalizedMeasurement` plus hub fields (`server.py:262-271`) | Indication on Envelope `f4610003-...`, wrapping SIG bytes (`E/envelope.py:4-16`) |
| Plain SIG | n/a | Also indicated on `0x2A35`, `0x2A5E` or `0x2A1C` if subscribed, not used for admission (`control_point.py:239-241`) |
| Stop | `POST /api/v1/session/stop` (`server.py:370-372`) | Write CP `0x02` with nonce (`:294-302`) |
| Session id | Hub-issued UUID in `provenance` (`instruments.py:141`) | Phone nonce echoed (`D/ble/BleAcquisition.kt:51,58`) |
| Health | `GET /health` (`server.py:244-252`) | DIS: Manufacturer, Model, Serial = hub_id, Software Revision = build (`E/transports/ble/ble_hub.py:334-337`) |

### Thermometer gap

BLE code supports it (`E/profile.py:28`, `ble_hub.py:79`). Wi-Fi code supports it too. No profile assigns it, and `HubAssignment` leaves it `NOT_SUPPORTED` by default (`D/RoutingVitalsSource.kt:48`). Options for Phase 3 (operator's call): a third hub, re-profiling a hub, or a new rule allowing more instruments per hub. `DEPLOY.md:21` refuses multi-instrument profiles today.

## 2. Mapping table

| OneIoT concept | OneIoT evidence | SaMD-App or SaMDPi equivalent | Evidence | Gap | Integration needs |
|---|---|---|---|---|---|
| Main manifest | `ac.json` | Instrument table | `E/generator/instruments.py:19-80` | Fixed in code; no self-description | Manifest v2, one per instrument, ideally generated from this table |
| Device identity in manifest | `DEVICE` keys, `grammar.txt:15-17` | DIS fields, `hub_id` | `ble_hub.py:334-337`, `hubctx.py:179-181` | No serial or id in manifest | Add `device_serial`, `manifest_id` |
| Dictionary: `SENSOR` | `grammar.txt:31` | Primary, secondary, tertiary | `E/models.py:63-68` | Positional | Named values |
| Dictionary: `NUMERIC`, `RANGE` | `grammar.txt:44-47` | Scenario bands (not a device range) | section 1 | Device range undefined | Define in manifest; never clinical |
| Dictionary: units | Free text, `grammar.txt:28` | `mmHg`, `bpm`, `%`, `Cel` on wire; `mmHg`, `bpm`, `%`, `°C` in Room | `instruments.py:24-44`, `A/domain/model/Observation.kt:6-10` | No UCUM; three spellings; mapper ignores unit | UCUM on the wire; canonicalise in SaMD |
| Plausibility bounds | `RANGE`, unenforced | None. Emergency thresholds only | `A/domain/usecase/CheckEmergencyThresholdsUseCase.kt:20-23` | No plausibility table | SaMD-owned table: OQ |
| Dictionary: `MODE` | `grammar.txt:21` | `Scenario` (dev only) | `A/domain/vitalssource/VitalsSource.kt:34` | Different meaning | Keep out of clinical UI |
| Start measure | None; misuse of `BOOLEAN` | Worker's Start button | `CompounderViewModel.kt:520` | Grammar lacks an action | `ACTION` |
| Result event | None | Accepted or Rejected | `VitalsSource.kt:86-113` | Grammar lacks an event | `EVENT` |
| Status | None | `acquiringInstrument` on phone; thin on hub | `CompounderViewModel.kt:130` | No wire status | `STATUS`; hub must emit it |
| UI generator | `K/DisplayThingActivity.kt:199-309` | Hand-written Compose form | `A/presentation/compounder/CompounderScreen.kt:218-263` | Different stacks. Old generator is unsafe (`03` section 8) | Fixed template, per R1-6 |
| Field editability | Sensors editable by accident | RC-3 editable by design, in the form | `CompounderViewModel.kt:728` | Opposite reasons: OneIoT app must keep sensors read-only, SaMD form stays editable | Read-only in OneIoT; edit in SaMD |
| Empty state | Shows `min` | Empty until accepted; null never written | `CompounderViewModel.kt:620-656` | Old OneIoT fabricates | No value until a reading arrives |
| Transport: TCP | `K/DisplayThingActivity.kt:379-386` | HTTP, port 8090 | `E/transports/wifi/server.py:401-413` | Framing, identity | Envelope over HTTP or framed socket |
| Transport: RFCOMM | `05` section 12 | BLE GATT | `ble_hub.py:328-357` | Retired by T-0 | BLE per T-3 |
| Discovery | Cloud lookup | Avahi `_samd-gw._tcp`, NSD | `deploy/avahi/kernelhub1.service:21-26`, `D/NsdGatewayDns.kt:83` | Cloud not needed | NSD (R4) |
| F1 gate | None. target 34 | `LocalNetworkGate` | `A/domain/connectivity/LocalNetworkGate.kt:20` | OneIoT at target 37 needs its own (R2-3) | Same permission pattern |
| Data output | None | `AcquisitionResult.Accepted` | `VitalsSource.kt:94-104` | No file or reading | Payload extending `NormalizedMeasurement` |
| Device serial | Thing ID typed by user | `hub_id`, `device_id` | `K/DBHelper.kt:15`, `hubctx.py:179-181` | Thing ID is a user input | Device states identity; phone verifies |
| Thing Key | `cloud.py:140` | None | n/a | Not needed with CDM and NSD | Drop (R2-4) |
| Hub id check | None | `HubIdInterceptor`, DIS, envelope | `D/HubIdInterceptor.kt:25-33`, `D/ble/BleAcquisition.kt:147,183` | OneIoT has no peer check | OneIoT v2 checks, or SaMD re-checks |
| Session id | None | `sessionId`, `activeSessionId`; D4 | `CompounderViewModel.kt:140,563` | D4 is live | Bridge must not lean on `activeSessionId` |
| Patient binding | None | ViewModel `patientId`, encounter | `CompounderViewModel.kt:666-667` | OneIoT has no patient concept, correctly | Bind in SaMD on receipt, per brief 3.3 |
| Audit | None | 2 device actions, plus insert-only chain | `08` section 3 | New provenance fields | New action, or extend existing (S5) |
| Sync | None | `observations` | `A/data/sync/SyncRecordMappers.kt:95-104` | `device_id` null on save (`08` section 6) | Consider setting it. New capture method needs backend enum |
| Tests | Template only | Mapper, routing, BLE tests | `app/src/testDev/...` | None to reuse | New |

Superseded by (2026-11-10, CodeRabbit on PR #85): the rows proposing `device_serial`, `manifest_id`, `ACTION`, `EVENT` and `STATUS` are not requirements to implement. R4-K withdrew those keys (memo `12` section 11), and ren276/OneIoT `spec/oneiot-protocol-v1.md` adds no manifest keyword (section 2): `Status` and `Measure` are ordinary members (section 3.3), and manifest identity is the SHA-256 of the received bytes (section 3.1). The Thing Key row is corrected too: CDM and NSD provide association and discovery, not authentication. Dropping the Thing Key would leave the hub links unauthenticated and would not establish device trust; the specification keeps the Thing Key for REGISTER (section 5.2) and accepts unauthenticated dev links for synthetic data only (section 10).

## 3. Thing adapter on the hub (S4, design level, no code)

**Goal:** a hub serves one manifest per instrument and answers reading requests from the existing emulator, so simulation is not forked.

### S4's "over localhost" cannot work as written

| Hub | Why | Evidence |
|---|---|---|
| kernelhub1 | Listener pinned to `wlan0` with `SO_BINDTODEVICE`, so loopback does not match it. The unit says so directly | `E/hub.py:45-56`, `deploy/systemd/samdpi-hub-wifi.service:28-31` |
| kernelhub1 | `IPAddressAllow=10.203.0.0/20`, `IPAddressDeny=any` exclude `127.0.0.1` | `samdpi-hub-wifi.service:34-35` |
| kernelhub2 | No IP at all: `RestrictAddressFamilies=AF_UNIX`, `PrivateNetwork=yes` | `samdpi-hub-ble.service:30,33` |

### Options that keep "no forked simulation"

| Option | How | Keeps hardening? | Note |
|---|---|---|---|
| A1 In-process route | Add OneIoT routes to `WifiRequestHandler`, and a custom GATT service to `BleHub` | Yes, unchanged | Same process, one reading source. `SocketBindAllow=tcp:8090` still fits. Touches controlled hub code |
| A2 Separate process, shared library | New unit imports `ProfileEngine`, `make_reading` directly; own port, own instance | Yes, if its own unit is hardened alike | Same code, not a fork. Two processes then own sessions separately, and a BLE adapter must share `hci0` |
| A3 Separate process over `wlan0` address | Calls the hub's real address (the unit allows that: `samdpi-hub-wifi.service:31`) | kernelhub1 yes; kernelhub2 impossible | Only closest to S4's wording. Fails on the BLE hub |

Preference is Phase 3's job. A1 looks simplest (INFERRED).

### Port and resource clashes

| Resource | Today | Clash |
|---|---|---|
| TCP 8090 | Gateway, kernelhub1 | Old OneIoT 12345, 12346 do not clash. Adapter on a new port needs `SocketBindAllow` change or own unit |
| TCP on kernelhub2 | sshd only (`DEPLOY.md:170`) | Any Wi-Fi adapter there needs a new unit |
| TCP 8091 | Dev BLE server only, not shipped (`real_ble_server.py:274`) | None on hubs |
| `hci0`, one advertisement | `ActiveInstances 0x01` (`DEPLOY.md:170`) | A second advertiser may conflict; second GATT app unproven |
| Single central | First connection owns the hub; others are refused (`control_point.py:158-172`) | **OneIoT and SaMD on two phones cannot share a BLE hub at once.** Same phone: unverified |
| Avahi `_samd-gw._tcp` | One static record | Add a record, or a type |

### What in SaMDPi would change

| Item | Change | Evidence |
|---|---|---|
| New module | Adapter, plus manifest files | `E/` |
| Deploy allowlist | Must ship manifests; `git archive` paths and the BUILD manifest pick up new files | `DEPLOY.md:29-33`, `E/hubctx.py:93-124` |
| Profile schema | Exact key set refuses unknowns; any new key needs `profile_version` 2 | `E/profile.py:64-71,97-101` |
| Units | New or changed per option | `deploy/systemd/*` |
| Tests | `tests/test_deploy_files.py` and others guard this tree | `tests/` |
| BLE layout | New service, plus envelope compatibility | `ble_hub.py:339-357` |
| Hub states | STATUS needs real states and likely a delay | section 1 |
| Accessory boundary | Must stay outside the device while RC-1 to RC-4 hold | `docs/design/samdpi-two-hub-memo.md:1106-1107` |

## 4. SaMDPi BLE stack and link security (T-2, R2-4b)

| Item | Value | Evidence |
|---|---|---|
| Library | `dbus-fast==5.2.0`, hashed venv. Not `bluez-peripheral` on hubs | `pyproject.toml:10`, `requirements-ble.txt:3` |
| BlueZ | 5.82 | `docs/design/samdpi-two-hub-memo.md:78` |
| Python | 3.13.5, Debian 13 | `07` section 11 |
| GATT API | `org.bluez.GattManager1.RegisterApplication` | `E/transports/ble/ble_hub.py:277-278` |
| Advertising | `LEAdvertisingManager1`, type `peripheral`, `LocalName` not `Includes`, 100 ms interval, stops while owned | `ble_hub.py:95,208-239,283-286`, `control_point.py:162` |
| Local name | `SaMD-kernelhub2` | `profiles/kernelhub2.toml:9` |
| Service UUIDs in advert | Instrument service plus vendor service | `ble_hub.py:355` |
| DIS `0x180A` | `2A29`, `2A24`, `2A25` (hub_id), `2A28` (build) | `ble_hub.py:332-337` |
| BP | `0x1810`: `0x2A35` indicate, `0x2A49` read | `ble_hub.py:77` |
| SpO2 | `0x1822`: `0x2A5E` indicate, `0x2A60` read | `ble_hub.py:78` |
| Thermometer | `0x1809`: `0x2A1C` indicate | `ble_hub.py:79` |
| Vendor service | `f4610001-95ad-4b7d-a8ed-b568b5b0e927` | `ble_hub.py:71` |
| Control Point | `f4610002-...`, write and indicate | `ble_hub.py:72,350` |
| Envelope | `f4610003-...`, indicate | `ble_hub.py:73,351` |
| Minimum MTU | 89 | `control_point.py:57` |
| Android side | See `08` section 8 | |

Dev-only alternative: `real_ble_server.py` uses `bluez_peripheral`, with bugs on record (`docs/CONNECTIVITY_TROUBLESHOOTING_AND_FIXES.md` issues 2, 6) and is excluded from hubs (`DEPLOY.md:31-33`).

### Existing link security (for R2-4b)

There is **no encryption or authentication** on either link, by stated design.

| Link | State | Other controls | Evidence |
|---|---|---|---|
| Wi-Fi | Cleartext HTTP, no auth | `hub_id` on every response; kernel limits on bind and address; dev flavour only | `D/PiGatewayApi.kt:11-12`, `samdpi-hub-wifi.service:32-35`, `POC:383` |
| BLE | Open GATT, no bonding; no encrypt flags | CDM human choice, DIS Serial, single central, 16-byte nonce with hub-side reuse check, `emulated` flag | `ble_hub.py:337-351`, `control_point.py:279-280`, `POC:384` |

Roadmap items (mTLS, LE Secure Connections) are DOCS-ONLY (`POC:387-395`). So "align with hub links" means correlation and identity controls, and no crypto. Phase 3 gives the recommendation.

## 5. Wired and NFC search (T-2)

| Item | Finding | Evidence |
|---|---|---|
| SaMDPi sample app wired | `USB_WIRED` transport, same HTTP client on a tethered IP, `MOCK_USB_JSON` label | `app/src/main/java/com/example/samdpi/model/NormalizedMeasurement.kt:17`, `presentation/SampleAppViewModel.kt:128,157-159` |
| SaMDPi USB claim | Direct cable, phone tethering on; streamed readings | `docs/CONNECTIVITY_TROUBLESHOOTING_AND_FIXES.md:263`: a doc claim, not re-run |
| Would that work on today's hubs? | **No** (INFERRED). `wlan0` pin and `IPAddressAllow=10.203.0.0/20` refuse a USB interface and tether subnet | `samdpi-hub-wifi.service:34-35`, `E/hub.py:45-56` |
| SaMD-App wired | None | `08` section 8 |
| NFC in SaMDPi | Named in strategy only | `POC:283,368` |
| NFC in SaMD-App | One memo line | `08` section 8 |
| Wired design notes | POC section 13: wired is not automatically secure | `POC:368-376` |

---

## Appendix A: DRAFT manifests in OneIoT's own format

**DRAFT. Only in this memo, never in any repo.** Written in the paper grammar (`03` Appendix B), matching the emulator field by field. `RANGE` min and max are the emulator's scenario bands from section 1, widened to whole values, **not** clinical bounds and not device limits. `BOOLEAN` for Measure misuses the type, as `03` section 8 shows. Version fields are placeholders.

### BP (kernelhub2 today)

```json
{
  "DEVICE": {"NAME": "Blood Pressure Monitor", "DOMAIN": "Medical", "INSTALLATION": "Portable", "SW_VERSION": "DRAFT", "HW_VERSION": "samdpi-hub"},
  "OEM": {"NAME": "SaMD dev emulator (synthetic)"},
  "SENSOR": {
    "Systolic":  {"DESC": "Systolic pressure",  "NUMERIC": {"RANGE": [82, 179, 0.1, "+", "mmHg"]}},
    "Diastolic": {"DESC": "Diastolic pressure", "NUMERIC": {"RANGE": [53, 108, 0.1, "+", "mmHg"]}},
    "Pulse":     {"DESC": "Pulse rate",         "NUMERIC": {"RANGE": [50, 115, 0.1, "+", "bpm"]}}
  },
  "ACTUATOR": {"Measure": {"DESC": "One acquisition", "BOOLEAN": ["start", "stop"]}}
}
```

### SpO2 (kernelhub1 today)

```json
{
  "DEVICE": {"NAME": "Pulse Oximeter", "DOMAIN": "Medical", "INSTALLATION": "Portable", "SW_VERSION": "DRAFT", "HW_VERSION": "samdpi-hub"},
  "OEM": {"NAME": "SaMD dev emulator (synthetic)"},
  "SENSOR": {
    "SpO2":  {"DESC": "Oxygen saturation", "NUMERIC": {"RANGE": [86, 100, 0.1, "+", "%"]}},
    "Pulse": {"DESC": "Pulse rate",        "NUMERIC": {"RANGE": [56, 118, 0.1, "+", "bpm"]}}
  },
  "ACTUATOR": {"Measure": {"DESC": "One acquisition", "BOOLEAN": ["start", "stop"]}}
}
```

### Thermometer (no hub today)

```json
{
  "DEVICE": {"NAME": "Digital Thermometer", "DOMAIN": "Medical", "INSTALLATION": "Portable", "SW_VERSION": "DRAFT", "HW_VERSION": "samdpi-hub"},
  "OEM": {"NAME": "SaMD dev emulator (synthetic)"},
  "SENSOR": {
    "Body temperature": {"DESC": "Body site", "NUMERIC": {"RANGE": [34, 40, 0.01, "+", "Cel"]}}
  },
  "ACTUATOR": {"Measure": {"DESC": "One acquisition", "BOOLEAN": ["start", "stop"]}}
}
```

### Field-by-field match

| Emulator field | Manifest field | Fit |
|---|---|---|
| `device_type`, display name | `DEVICE.NAME` | Fits |
| DIS Manufacturer, Model | `OEM.NAME`, `DEVICE.HW_VERSION` | Fits, stretches `HW_VERSION` |
| `hub_id`, `device_id` | None | **Not expressible** |
| `primary_value`, `secondary_value`, `tertiary_value` | `SENSOR` members | Fits by name, position lost |
| `unit`, `secondary_unit`, `tertiary_unit` | RVAL index 4 | Fits as free text |
| Rounding | RVAL `STEP` | Stretch: 0.1 and 0.01 are resolutions, not steps |
| `quality_status`, `measured_at`, `measurement_id`, `sequence_number`, `synthetic` | None | **Not expressible**: reading fields, not manifest fields (`12`) |
| Start, stop | `BOOLEAN` | Misuse |
| Lifecycle, errors | None | **Not expressible** |
| Temperature type | None | **Not expressible** |
| MAP | None | **Not expressible** |

## Appendix B: Vocabulary to standards

Tag EXTERNAL unless noted. Numeric MDC codes are what is unique; names are informational ([PHD terminology](https://build.fhir.org/ig/HL7/phd/terminology.html)). `NUMERIC`, `RANGE`, `BOOLEAN` and similar dictionary terms are structural and map to no code.

| Vital | App or dictionary term | LOINC | MDC code, name | UCUM | Bluetooth SIG | SaMD unit today | Sources |
|---|---|---|---|---|---|---|---|
| BP panel | `BLOOD_PRESSURE` | 85354-9 | 150020 `MDC_PRESS_BLD_NONINV` | n/a | Service 0x1810 | n/a | [FHIR R4 vitals](https://hl7.org/fhir/R4/observation-vitalsigns.html), [POCD NBP](https://build.fhir.org/ig/HL7/uv-pocd/Observation-NBPCompoundNumericObservation.html) |
| Systolic | `BP_SYSTOLIC` | 8480-6 | 150021 `MDC_PRESS_BLD_NONINV_SYS` | `mm[Hg]` | 0x2A35 | `mmHg` | same, [PHD blood pressure](https://hl7.org/fhir/uv/phd/2019May/bloodPress-1.0.1.html) |
| Diastolic | `BP_DIASTOLIC` | 8462-4 | 150022 `MDC_PRESS_BLD_NONINV_DIA` | `mm[Hg]` | 0x2A35 | `mmHg` | same |
| Mean | MAP, BLE only | 8478-0 | 150023 `MDC_PRESS_BLD_NONINV_MEAN` | `mm[Hg]` | 0x2A35 | not stored | POCD NBP |
| Pulse, from BP cuff | `PULSE` | 8867-4 (heart rate) | `MDC_PULS_RATE_NON_INV` named in `E/models.py:118`; **number not verified** | `/min` | 0x2A35 | `bpm` | FHIR R4 vitals |
| Oxygen saturation | `SPO2` | 2708-6; 59408-5 for pulse oximetry | 150456 `MDC_PULS_OXIM_SAT_O2` | `%` | Service 0x1822; 0x2A5E spot-check, 0x2A5F continuous | `%` | FHIR R4 vitals, [LOINC 59408-5](https://loinc.org/59408-5), [FHIR example](https://hl7.org/fhir/observation-example-satO2.html) |
| Pulse, from oximeter | `PULSE` | 8867-4; 8889-8 **not verified** | 149530 `MDC_PULS_OXIM_PULS_RATE` | `/min` | 0x2A5E | `bpm` | [DK Core IEEE](https://hl7.dk/fhir/core/3.2.0/ValueSet-dk-core-IEEEBasicObservation.html) |
| Body temperature | `TEMPERATURE` | 8310-5 | 150364 `MDC_TEMP_BODY` | `Cel` | Service 0x1809; 0x2A1C; type 0x2A1D; intermediate 0x2A1E | `°C` | FHIR R4 vitals, DK Core IEEE |

### Bluetooth SIG numbers

From the SIG's own [service list](https://bitbucket.org/bluetooth-SIG/public/raw/main/assigned_numbers/uuids/service_uuids.yaml) and [characteristic list](https://bitbucket.org/bluetooth-SIG/public/raw/main/assigned_numbers/uuids/characteristic_uuids.yaml).

| Number | Name | Used by SaMDPi hubs? |
|---|---|---|
| 0x1810 | Blood Pressure | Yes |
| 0x1822 | Pulse Oximeter | Yes |
| 0x1809 | Health Thermometer | Code yes, no hub |
| 0x180A | Device Information | Yes |
| **0x1840** | **Generic Health Sensor** | No |
| 0x2A35, 0x2A36, 0x2A49 | BP Measurement, Intermediate Cuff Pressure, BP Feature | 0x2A36 no; others yes |
| 0x2A5E, 0x2A5F, 0x2A60 | PLX Spot-Check, PLX Continuous, PLX Features | 0x2A5F no; others yes |
| 0x2A1C, 0x2A1D, 0x2A1E | Temperature Measurement, Temperature Type, Intermediate Temperature | 0x2A1C only |
| 0x2BF3, 0x2BF4 | Health Sensor Features, GHS Control Point | No |

### Not verified

- `MDC_PULS_RATE_NON_INV`'s number; LOINC 8889-8.
- ABDM and NRCeS FHIR profile bindings: search found nothing. OQ for `14`.
- UCUM unit `/min` and `mm[Hg]` come from the FHIR R4 vitals profile, not UCUM's own table.
- GHS's detailed use: name and number only; Phase 3 (T-3) will verify formats.

Unit gap: `mmHg`, `bpm` and `°C` are not the UCUM forms. The wire and Room would need canonicalising in SaMD, not by a manifest (R1-6).
