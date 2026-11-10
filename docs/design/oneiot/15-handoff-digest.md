Status: RESEARCH, not a design ruling. Sources read: memos `00` to `14` of this study; paper Gupta, Banda et al., PeerJ Comput. Sci. 2024, DOI 10.7717/peerj-cs.2480 (text pp.6 to 15); `grammar.txt` (OneIoT repo); TIH project documentation sections 8.4, 8.6; SaMDPi at `bba2c6b`; SaMD-App at `4b9ee3c`; Bluetooth Core 5.4; Android `PackageManager` reference; operator rulings R0 to R4.

# 15: OneIoT handoff digest

Self-contained. Paste into a chat as is. Tags: **V** VERIFIED (read in code or measured), **I** INFERRED, **D** DOCS-ONLY (paper or project doc, not in code), **E** EXTERNAL (public standard or library doc). Rulings and proposals are not facts and are marked RULING or PROPOSAL. Every number of days is I, about plus or minus 50 percent. Paths: `E/` is SaMDPi `emulator/`, `D/` is SaMD-App `app/src/dev/java/com/example/samdapp/data/vitalssource/`.

## 1. OneIoT in 10 lines

1. One phone app drives any IoT device from a manifest the device ships (paper p.1 to 2) **D**.
2. Architecture: IoT node, remote host (OEM cloud, ownership and device address), user agent (paper p.6 to 7) **D**.
3. Protocol phases: Discovery, Registration, Probe/Advertisement, Invocation, Configuration (p.7 to 8) **D**.
4. Message: header (protocol name and version, encryption type), request payload (protocol name, operation name, arguments), response payload (outputs) (Figs 3 to 5, p.8) **D**.
5. Invoke: GET on a sensor or actuator, SET on an actuator (Fig 8, p.12) **D**.
6. Manifest: MMF mandatory and read-only; SMF optional, user modes and location (p.11 to 12) **D**.
7. Grammar: a CFG (pp.13 to 15) printed to JSON by Algorithm 1, with real defects (`03` Appendix B) **V**.
8. Legacy code implements little of it: raw TCP, RFCOMM, RSA, no sensor GET, no validator (`02` section 8) **V**.
9. Ruled: a **new app from scratch**, faithful to the paper's protocol, for vitals, with no legacy code (R4-0) RULING.
10. Target demo: SaMD-App launches OneIoT on the same phone, OneIoT reads SpO2 (kernelhub1, Wi-Fi) or BP (kernelhub2, BLE) and returns one reading (R4-1) RULING.

## 2. Keyword register (R4-K)

Spelling used: the paper's underscore form. `grammar.txt` writes `SW VERSION`, `UNIT VAL`, `IMAGE ATTRIB`, `PLAYER ATTRIB`, `STRUCT X`, `STRUCT Y` with spaces; otherwise its productions match the paper (`03` Appendix B.2) **V**. Recommended: underscore (OQ-48). Pages are the paper; lines are `grammar.txt`; TIH means the keyword appears in TIH 8.6's air-conditioner example **D**. Meanings are the paper's (p.11 to 12) **D**.

| Keyword | Page, line, TIH | Meaning | Vitals use |
|---|---|---|---|
| `DEVICE` | p.13, l.15, 8.6 | Device identity section | Used |
| `NAME` | p.13, l.17 and 19, 8.6 | Device or OEM name | Used |
| `DOMAIN` | p.13, l.17, 8.6 | e.g. smart home, medical | Used: `Medical` |
| `INSTALLATION` | p.13, l.17, 8.6 | Fixed or portable | Used: `Portable` |
| `WARRANTY` | p.13, l.17 | Warranty text | Unused |
| `SW_VERSION`, `HW_VERSION` | p.13, l.17 | Device versions | Used |
| `OEM` | p.13, l.18 | Maker; locates OEM server | Used (`NAME`) |
| `SOCKET` | p.13, l.20 | OEM server host and port (p.11) | Optional: our Registration host as `"host:port"` (OQ-50) |
| `MODE` | p.14, l.21, 8.6 | Preset actuator values | Unused |
| `LOCATION` | p.14, l.29 | Where the device is (SMF) | Unused |
| `SENSOR` | p.14, l.31 | Read-only values | Used: readings and `Status` |
| `DESC` | p.14, l.33 | Description | Used |
| `STRUCT_X`, `STRUCT_Y` | p.14 l.35, p.15 l.78 | Group of 2 or more members | Unused; key spelling unconfirmed (OQ-47) |
| `BATTERY` | p.14, l.36 | Battery level | Unused (no source on hubs) |
| `GRAPH`, `PIE`, `BAR` | p.14, l.37 to 39 | Chart | Unused |
| `BOOLEAN` | p.14, l.43, 8.6 | Two named states | Unused |
| `NUMERIC` | p.14, l.44, 8.6 | Number | Used: every reading |
| `RANGE` | p.14, l.45, 8.6 | Positional `[MIN, MAX, STEP, OPERATION, UNIT_VAL, EXCEPT]` | Used, length 5 |
| `MIN`, `MAX`, `STEP` | p.14, l.47 to 50 | Bounds; step is positive | Used: device range, resolution |
| `OPERATION` | p.14, l.52 | How step applies: `+`, `*`, `-1` | Used: `+` |
| `UNIT_VAL` | p.14, l.28 and 51 | Unit string | Used: UCUM `mm[Hg]`, `/min`, `%` |
| `EXCEPT` | p.14, l.53 | Excluded values | Unused |
| `TUPLE`, `UNIT` | p.14, l.54 and 56 | Discrete values with a unit | Unused |
| `STRING` | p.14, l.57, 8.6 | Text choice | Used: `Status`, `Measure` |
| `OPTION` | p.14, l.58, 8.6 | List of choices | Used |
| `ACTUATOR` | p.15, l.75, 8.6 | User-settable controls | Used: `Measure` |
| `IMAGE`, `AUDIO`, `VIDEO`, `SIZE`, `IMAGE_ATTRIB`, `PLAYER_ATTRIB` and members | p.15, l.60 to 70 | Media | Unused |
| `MAP`, `COLOR`, `DATE`, `TIME` | p.15, l.71 to 74 | Widget kinds | Unused |
| `CONTROL` | not in paper; legacy `ac.json:15` **V** | Legacy alias | Withdrawn (R4-K) |

LOINC and MDC codes never appear in a manifest; SaMD maps sensor names to codes (R4-K) RULING. Manifest identity is the client's sha256 of the file bytes (R4-K) RULING.

## 3. Conformance (R4-F)

### 3.1 Table

The Paper column is **D** throughout (pp.6 to 15); the Our design column is PROPOSAL; reasons citing a memo inherit its tag.

| Paper | Our design | Class | Reason |
|---|---|---|---|
| Five phases (p.7 to 8) | All five kept; Registration per section 7 | SAME | Faithful |
| Message header, Fig 3 | `header`: protocol, version, `encryption_type` | SAME | `none` on dev links (R4-F) |
| Request payload, Fig 4 | `payload`: phase, operation, arguments | SAME, with spelling OQ-49 | |
| Response payload, Fig 5 | `payload.outputs` | SAME | |
| Invoke GET and SET, Fig 8 | GET sensors, SET `Measure` | SAME | |
| GET of several sensors | One GET, one measurement | FILLED-GAP | Vitals need an atomic pair (OQ-55) |
| Reading metadata | In Outputs: section 5.3 | FILLED-GAP | Paper leaves Outputs unspecified (R4-K) |
| Errors | `outputs.error` codes | FILLED-GAP | Paper silent |
| MMF, SMF (p.11 to 12) | MMF only; no SMF for vitals | SAME (SMF optional) | |
| Grammar keywords | Exact, underscores | SAME | R4-K |
| `SOCKET -> :str:num` (p.13) | `"host:port"` string | FIXED-DEFECT | Grammar form is invalid JSON (`03` B.3) **V** |
| `SENSOR_TYPE -> {}` (p.14) | Rejected: a member needs one type key | FIXED-DEFECT | Emits `{{}}`, invalid JSON **V** |
| `EXCEPT -> [TUPLE]` (p.14) | Plain array if ever used | FIXED-DEFECT | Emits a key inside an array **V** |
| Key order (`START` production) | Not enforced | FIXED-DEFECT | JSON objects carry no order **V** |
| Algorithm 1 tool (p.15) | Hand-written, schema-validated | FILLED-GAP | Tool absent (`02` s.7) **V** |
| Media independence (p.6) **D** | Same messages on HTTP and GATT | FILLED-GAP for BLE | Paper's nodes are IP-enabled; BLE mapping is ours |
| All phases encrypted (p.9) **D** | `encryption_type` declared, `none` on dev links | FILLED-GAP | Paper names no cipher; R4-F |
| Heartbeat, tick rate (p.6, p.23) | kernelhub1 unit; tick rate by Configuration | SAME | kernelhub2 cannot (s.7) |
| Discovery by local scan (p.9) **D** | NSD on Wi-Fi, BLE scan with CDM | SAME | |
| Registration (Fig 7) | Our host; key matched to its Thing | SAME | Legacy ignored Thing ID (`02` s.8) **V** |

### 3.2 Message vocabulary: paper vs TIH 8.4 (OQ-49)

| Item | Paper (authority) | TIH 8.4 **D** | Proposed |
|---|---|---|---|
| Protocol name | "Protocol Name and Ver", one header field | `protocol` "OpenOneIoT" plus `version` | `protocol` "OneIoT", `version` "2": TIH split, since the paper gives no JSON form |
| Payload's protocol name | Request payload holds a protocol name whose value is the phase, e.g. PROBE (p.8) | `phase` | `phase`: the paper's name would collide with the header field |
| Phase tokens | Discovery, Registration, Probe/Advertisement, Invocation, Configuration; text shows `PROBE`, Fig 7 `Invoke` | DISCOVERY, REGISTER, PROBE, INVOKE, CONFIGURE | TIH tokens: paper gives no wire tokens beyond PROBE |
| Operation | GET, SET (Fig 8) | none | `operation`: GET, SET |
| Encryption type | Header field | none | `encryption_type` |
| Token | `userid, tid, tkey` in Registration (Fig 7) | `token`, bearer | Bearer token for the Registration host only |
| Timestamp | none | `timestamp` | Not in header; `measured_at` in Outputs |
| Outputs | Outputs (Fig 5) | `payload` | `outputs` |

## 4. Manifests in exact keywords, and validation

DRAFT. Ranges are emulator scenario bands, placeholders, not clinical bounds (OQ-19). Files in `/media/sandesh/extra-ssd/oneiot-study/drafts3/`.

BP, kernelhub2 (791 bytes):

```json
{
  "DEVICE": {
    "NAME": "Blood pressure monitor",
    "DOMAIN": "Medical",
    "INSTALLATION": "Portable",
    "SW_VERSION": "DRAFT",
    "HW_VERSION": "samdpi-hub"
  },
  "OEM": {
    "NAME": "SaMD dev emulator (synthetic)"
  },
  "SENSOR": {
    "Systolic": {"DESC": "Systolic pressure", "NUMERIC": {"RANGE": [82, 179, 0.1, "+", "mm[Hg]"]}},
    "Diastolic": {"DESC": "Diastolic pressure", "NUMERIC": {"RANGE": [53, 108, 0.1, "+", "mm[Hg]"]}},
    "Pulse": {"DESC": "Pulse rate", "NUMERIC": {"RANGE": [50, 115, 0.1, "+", "/min"]}},
    "Status": {"DESC": "Measurement state, read-only", "STRING": {"OPTION": ["idle", "measuring", "done", "error", "stopped"]}}
  },
  "ACTUATOR": {
    "Measure": {"DESC": "Start or stop one measurement", "STRING": {"OPTION": ["start", "stop"]}}
  }
}
```

SpO2, kernelhub1 (672 bytes):

```json
{
  "DEVICE": {
    "NAME": "Pulse oximeter",
    "DOMAIN": "Medical",
    "INSTALLATION": "Portable",
    "SW_VERSION": "DRAFT",
    "HW_VERSION": "samdpi-hub"
  },
  "OEM": {
    "NAME": "SaMD dev emulator (synthetic)"
  },
  "SENSOR": {
    "SpO2": {"DESC": "Oxygen saturation", "NUMERIC": {"RANGE": [86, 100, 0.1, "+", "%"]}},
    "Pulse": {"DESC": "Pulse rate", "NUMERIC": {"RANGE": [56, 118, 0.1, "+", "/min"]}},
    "Status": {"DESC": "Measurement state, read-only", "STRING": {"OPTION": ["idle", "measuring", "done", "error", "stopped"]}}
  },
  "ACTUATOR": {
    "Measure": {"DESC": "Start or stop one measurement", "STRING": {"OPTION": ["start", "stop"]}}
  }
}
```

Schemas: `mmf.schema.json` (paper grammar, exact keywords, defects fixed per 3.1); `vitals.schema.json` (constraints only: `Medical`, `RANGE` length 5 with UCUM `UNIT_VAL`, fixed `Status` and `Measure` options).

**Result, 2026-11-08 (`jsonschema` from `check-jsonschema==0.33.0`) V:** both schemas pass the metaschema; BP and SpO2 **PASS** both schemas; SpO2 with `OEM.SOCKET` `"kernelhub1.local:8090"` passes; legacy `ac.json` fails (`CONTROL`); `ac.json` with `ACTUATOR` passes the MMF schema and fails the vitals profile, as it should. **16 of 16 mutations rejected** (invented keys, `CONTROL`, named `RANGE`, spaced spelling, bad `SOCKET`, missing unit, non-UCUM unit, and others; list in `12` section 11.3).

## 5. Message formats per bearer

### 5.1 Logical form (PROPOSAL; spellings pend OQ-49)

```text
request  = {"header": {"protocol": "OneIoT", "version": "2", "encryption_type": "none"},
            "payload": {"phase": "INVOKE", "operation": "GET", "arguments": {"names": ["Systolic", "Diastolic", "Pulse"]}}}
response = {"header": {...}, "payload": {"outputs": {...}}}
```

| Message | Arguments | Outputs |
|---|---|---|
| PROBE | empty (p.8) **D** | HTTP: base64 of the MMF file bytes; BLE: pages |
| SET `Measure` start | `name`, `value` `start`, `session_id` (SaMD request id), `simulate_latency` (default `false`, R4-3) | `Status` |
| SET `Measure` stop | `name`, `value` `stop`, `session_id` | `Status` `stopped` |
| GET `Status` | `names: ["Status"]` | `Status` value |
| GET reading | sensor names | Section 5.3 |
| CONFIGURE (kernelhub1) | `tick_rate_s` | echo |
| Error | | `error`: `BUSY`, `UNKNOWN_NAME`, `INVALID_ARGUMENT`, `NOT_READY`, `MTU_TOO_SMALL` |

### 5.2 Per bearer

| Part | Wi-Fi, kernelhub1, port 8090 | BLE, kernelhub2 |
|---|---|---|
| Transport | New `POST /oneiot/v2`, JSON above; v1 routes unchanged | Vendor service `f4610001-...` (`E/transports/ble/ble_hub.py:71`) **V** |
| Header | JSON | Service UUID plus envelope version; encryption type byte in the PAGE_REQUEST response |
| PROBE | `outputs.manifest` base64 | CP `0x03` + u16 page; CP response `80 03 <result>`, u16 pages, u32 length, u8 encryption, 32-byte sha256; read `f4610004`: u16 page, u16 pages, u32 length, up to 504 data bytes |
| Start | SET `Measure` | New CP `0x04`: v1 START's 19 bytes plus a flags byte (bit 0 latency); `0x01` and `0x02` unchanged (`E/transports/ble/control_point.py:44-45,57`) **V** |
| Status | GET `Status` | Read and indicate `f4610005`, u8 0 to 4 |
| Reading | GET sensors | SIG `0x2A35` plus envelope `f4610003`; phone builds Outputs; SIG unit must equal `mm[Hg]` |
| MTU | n/a | ATT_MTU at least 513, else `MTU_TOO_SMALL` (code exists, `:54`) **V** |

ATT caps one attribute value at 512 octets (Bluetooth Core 5.4, Vol 3 Part F, 3.2.9) **E**. Both manifests need 2 pages **V**. No long read is used, so the long-read test is not a gate (R4-2).

### 5.3 Reading Outputs (payload fields, not keywords)

`values` (MMF names to numbers), `measurement_id`, `quality_status` (`OK`, `WARNING`, `ERROR`, `UNAVAILABLE`; values empty unless `OK`), `measured_at` (device, nullable), `hub_id`, `provenance.session_id`, `synthetic`. Names follow SaMD's `NormalizedMeasurementDto`, where session id sits only inside `provenance` (`D/PiGatewayApi.kt:54-77`) **V**. Units come only from the MMF. `received_at` is set by the receiving app.

## 6. Bridge and app identity

| Item | Design | Tag |
|---|---|---|
| Pattern | D1: SaMD dev flavour starts OneIoT for a result, gets one reading, maps it; no new inbound surface | RULING R4-1; I |
| RC-1 end to end | SaMD request id becomes the hub nonce / `session_id` | I (`12` section 4.6) |
| `applicationId` | `com.example.oneiot` | RULING R4-6 |
| SaMD dev package | `com.example.samdapp.dev` (`app/build.gradle.kts:91,115`) | V |
| Caller check | `checkSignatures(SaMD, OneIoT)` must return `SIGNATURE_MATCH`; API 1, not deprecated | E ([PackageManager](https://developer.android.com/reference/android/content/pm/PackageManager)) |
| `<queries>` | Needed in SaMD's dev manifest; absent today (V), else check fails closed with `SIGNATURE_UNKNOWN_PACKAGE` | I |
| Signer | Same debug key, both built on one machine; production key and rename deferred (OQ-59) | RULING R4-6 |
| Result to SaMD | Reading Outputs plus per-value unit from MMF, manifest sha256, OneIoT package and version | PROPOSAL |
| SaMD audit | New action with provenance (package, version, sha256, request id, measurement id); no new capture-method value | RULING R4-1 |
| Prerequisite | Unit check and canonicalisation in `PiMeasurementMapper` merge first; it ignores unit today (`D/PiMeasurementMapper.kt:39-53`) | V, RULING R4-4 |
| BLE sequencing | Only one app holds a hub; OneIoT disconnects before Send; hub cannot tell two apps on one phone apart | I (`10` section 1.6) |
| Manifest trust | Bundled sha256 allowlist until NFC; allowlist entry also names the instrument (OQ-57) | RULING R4-1, PROPOSAL |

## 7. Registration host (R4-R; final ruling at STOP 4)

| Question | Finding | Tag |
|---|---|---|
| What | Ownership registry, device lookup, heartbeat with tick rate, authenticated; not `cloud.py` | PROPOSAL |
| Where | **Dev laptop, own small service**, separate from SaMD's backend (no clinical data) | I |
| kernelhub1 reach | Hub unit allows only `10.203.0.0/20` (`deploy/systemd/samdpi-hub-wifi.service:34-35`); laptop seen at `10.203.2.52`, inside it | V |
| Heartbeat | Separate kernelhub1 unit | I |
| kernelhub2 | BLE unit `PrivateNetwork=yes`, `AF_UNIX` only (`samdpi-hub-ble.service:30,33`), so ownership only; Pi uplink unknown (OQ-52) | V, open |
| Auth | User bearer token; per-device secret for heartbeat; Thing key matched to its Thing | PROPOSAL |
| Configuration | Tick rate is the paper's own example (p.9) **D**, so kernelhub1 shows Configuration | PROPOSAL |
| Effort | 6 to 9 days | I |

Cost: a second login in OneIoT (OQ-53) I. With this host, all five phases are demonstrable: Discovery (NSD, BLE scan), Registration, Probe, Invocation, Configuration (I). Operator to confirm the demo needs all five (OQ-51).

## 8. Tiers (R4-5), days I

| Tier | Contents | Days |
|---|---|---|
| 1 | New OneIoT app, target 37, manifest-driven UI; five phases; Probe and Status on kernelhub1 (Wi-Fi SpO2) and kernelhub2 (BLE BP); hub additions (A1); D1 bridge; SaMD unit fix and D4 | 24 to 38 app and hubs, 6 to 9 Registration, 3 to 5 bridge, about 2 SaMD: **35 to 54** |
| 2 | Third hub, thermometer over USB; gated on OQ-3 and USB tests | **8 to 15** |
| 3 | NFC tap-to-pair, hash pinning; after hardware | **4 to 7** |

## 9. Hub additions (A1, in-process; v1 byte-compatible)

All rows are PROPOSAL, sized as I; current hub facts cited are V.

| Hub | Addition |
|---|---|
| kernelhub1 | MMF file on disk; `POST /oneiot/v2` (PROBE, INVOKE, CONFIGURE); `session_id` from client; `simulate_latency` timer, cancelled by stop; `Status` states; refuse a second client with `BUSY` (OQ-39); heartbeat unit (section 7) |
| kernelhub2 | CP `0x03` paging and `0x04` start with flags; `f4610004` manifest page read; `f4610005` status read and indicate; `Delay` action in the pure state machine (R2 to R3 risk, `12` section 4.5) |
| Both | Latency constants only from `E/generator/multi_worker.py:35-42`, composite module never reused (`12` section 4.5) V |

## 10. Top hazards (PROPOSED, provisional labels; never in `docs/quality/`)

Causes are V where cited, otherwise I; controls are PROPOSAL.

| Label | Hazard | Control |
|---|---|---|
| SAMD-1 | No plausibility bounds; 999 mmHg saves (`CompounderViewModel.kt:668-671`) V | Two layers: hard reject limits, soft confirm ranges with clinician sign-off; values not chosen (R4-4) |
| SAMD-2 | Mapper ignores unit (V) | Unit check merges before the bridge (R4-4) |
| H-OI-23 | Renamed or swapped sensor maps to wrong vital | Exact-name map per allowlisted sha256; unknown name refuses the reading |
| H-OI-24 | SIG unit flag and MMF `UNIT_VAL` disagree | Refuse on mismatch |
| H-OI-25 | Registration host down or stale address | Address only for kernelhub1; NSD fallback; host holds no clinical data |
| Stale or spoofed | Old reading; fake app | RC-1 nonce; `checkSignatures`; `measured_at` gate pending (OQ-16) |
| SAMD-5 | Failure audit names a stale session (`CompounderViewModel.kt:563`) V | Fix before the bridge |

Full list: `13` sections 3 and 9 (H-OI-01 to H-OI-25).

## 11. Rulings (one line each)

| Id | Ruling |
|---|---|
| STOP-0A | OneIoTApp folder only, no veman; public keys readable; brief's stop script; paper as named |
| R0-1 | No upstream on study branch |
| R0-2 | No cat or broad globs near credential files; list first |
| R0-3 | Key pair and crypto scheme compromised; never reused in SaMD |
| R0-4 | Veman comparison dropped |
| R0-5 | APK class and method names only |
| R0-6 | Questions a to h answered in `02`, `03` |
| R0-7 | Hypothesis evidence only until Phase 3 |
| R0-8 | Log root free space at each STOP |
| A1 | Render and transcribe figures |
| A2 | Security stories, manifest vs RSA limit |
| A3 | Further docs-vs-code checks |
| A4 | Grammar audit |
| A5 | Upstream check optional |
| A6 | Related papers to request |
| A7 | Heartbeat (device to cloud) is not liveness (app to hub) |
| S1 | Demo uses OneIoT as a separate app |
| S2 | Key files present, unread, operator states rotated |
| S3 | Runnability, reading and Intent hooks |
| S4 | Thing adapter design |
| S5 | Demo bridge and target options |
| S6 | Nothing changes in any repo during the study; no code |
| T-0 | Wi-Fi, BLE, USB, NFC later; RFCOMM retired; one envelope, one manifest on every bearer |
| T-1 | Inventory RFCOMM and socket use |
| T-2 | Identify SaMDPi BLE stack and SaMD BLE, CDM |
| T-3 | Write `11b` transports memo |
| T-4 | Nothing installed on hubs; no SSH; no code |
| R1-1 | STOP-1B no (later superseded by R2-1) |
| R1-2 | Masking rule for `cloud.py`, `ipverify.py` |
| R1-3 | Demo viability memo |
| R1-4 | New repo assumption (replaced by R2-3) |
| R1-5 | Instrument detail, vitals form |
| R1-6 | Memo 12 scope |
| R2-1 | Build a copy: success |
| R2-2 | POC memo, baseline rule |
| R2-3 | Staged replacement in same app (superseded by R4-0) |
| R2-4 | a cloud R4 end state; b drop RSA, MD5, Chaquopy; c target 37; d BLE GATT only |
| R2-5 | Hubs measured: Debian 13 |
| R3-0 | Commit and push legacy snapshot: `25952a2` |
| R3-1a to j | Protocol v2 extends SaMDPi; generated manifest served on both bearers; A1; real states; thermometer and wired; demo link security; SaMD items separate; bridge defaults; BLE single central; P1 to P3 (as changed by R4) |
| R3-2 | Open items to `14` |
| R4-0 | New app from scratch, paper-faithful, new repo; legacy archived; P3 |
| R4-K | Paper keywords exactly; withdrawals; metadata in Outputs; sha256 identity |
| R4-F | Faithful to the paper's protocol; deviations recorded |
| R4-R | Evaluate own Registration host; rule at STOP 4 |
| R4-1 | D1 same phone; logical envelope; A1; no new capture method; allowlist until NFC |
| R4-2 | BLE paging mandatory; long read not a gate |
| R4-3 | Latency opt-in, default instant |
| R4-4 | SaMD items separate PRs; unit check first; SAMD-1 two layers |
| R4-5 | Tiers 1 to 3 |
| R4-6 | `com.example.oneiot`; debug signer; `checkSignatures` |
| R4-7 | Tag key for memos; full tags in digest |
| R4-8 | This digest |

## 12. Open questions

Answered by R4: OQ-1, 2, 6, 7, 8, 9, 10, 11, 13, 41 (`14`). Still open:

| OQ | Question |
|---|---|
| 3 | Does a third Pi exist? |
| 4 | Superseded in part by R4-R; demo phases are OQ-51 |
| 5 | targetSdk 37 (R2-4c agrees); confirm for the new app |
| 12 | New audit action vs new keys on existing action |
| 14 | Entry point (b) data model and consent |
| 15 | Plausibility bounds owner and values |
| 16 | `measured_at` age gate and window |
| 17 | Emergency override on every entry point? |
| 18 | Should simulated latency splits mimic a real cuff? |
| 19 | Who defines device `RANGE`? |
| 20 | Reword POC disclaimer conflict |
| 21, 22 | Severity and final IDs for `H-OI-*` |
| 23 | BLE ever in a release build? |
| 24 | Is OneIoT identity needed for the TiH grant or lab? |
| 25 | OpenIoT-HealthSaRK relation |
| 26 | Pixel `PAGE_SIZE` (operator runs; study ran no adb) |
| 27 | `docs/adr/` absent at `origin/master` |
| 28 | 19 vs 23 synced tables |
| 29 | Unverified codes and UCUM entries |
| 30 | Related papers (Banda 2015, 2017; demo paper) |
| 31 | Public repo history of key files |
| 32 | Do legacy DB credentials still work (rotation) |
| 33, 34 | USB tether reachability and address discovery |
| 35 | Long read: no longer a gate (R4-2) |
| 36, 37 | Two apps on one hub; CDM per app |
| 38 | NFC hardware and date |
| 39 | Wi-Fi second client: refuse with BUSY? |
| 40 | Does SaMD's client tolerate added fields? |
| 42 | Commit these memos? |
| 43 | Delete `.txt` Gradle copies in legacy repo |
| 44 | Android Studio sync result |
| 45 | Confirm R3-0 credential-word judgement |
| 46 | OkHttp `pingInterval`, `getCallingActivity` semantics |
| 47 | `STRUCT_X`/`STRUCT_Y` key spelling |
| 48 | Underscore vs spaced spelling (recommend underscore) |
| 49 | Message vocabulary conflicts, section 3.2 |
| 50 | `SOCKET` as Registration host address |
| 51 | Must the demo show all five phases? |
| 52 | kernelhub2 IP uplink |
| 53 | Registry login inside OneIoT |
| 54 | Configuration content for kernelhub2 |
| 55 | Multi-sensor GET as one measurement |
| 56 | Encryption types beyond `none` |
| 57 | Instrument identity from allowlist entry |
| 58 | OneIoT checks its caller too |
| 59 | Production key and package rename |
| 60 | MTU under 513 fails closed |
| 61 | Emulator scenario from OneIoT |
| 62 | Registration host location (STOP 4) |

## 13. Memo index (`docs/design/oneiot/`)

`00` index, rulings; `01` inventory; `02` overview, docs vs code; `03` dictionary, grammar audit; `04` UI generator; `05` protocol, figures; `06` security; `07`, `07b-*` modernisation, demo; `08`, `08a` SaMD pipeline, SaMDPi POC; `09` mapping; `10` options (R4: section 9); `11` liveness (s.8); `11b` transports (s.8); `12` manifest, protocol, reading (s.11); `13` risk (s.9); `14` OQs; `15` digest.
