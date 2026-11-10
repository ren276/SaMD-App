Status: RESEARCH, not a design ruling. Sources read: memos `05`, `09`, `10`, `12`; SaMD-App at `4b9ee3c`; SaMDPi at `bba2c6b`; Android 14 to 17 behaviour pages (`07` section 4). Timing proposals are INFERRED, to be measured.

Tag key: a claim with a file:line citation and no other tag is VERIFIED, read in code at the commit named above. A citation to another memo inherits that memo's tag. Anything else is marked INFERRED, DOCS-ONLY or EXTERNAL.

# 11: Connection and liveness

Terminology (A7): **heartbeat** means only OneIoT's device-to-cloud IP reporting. **Liveness** means knowing an app-to-hub or app-to-app link is up.

`D/` is SaMD-App `app/src/dev/java/com/example/samdapp/data/vitalssource/`. `E/` is SaMDPi `emulator/`.

## 1. OneIoT's heartbeat

| Item | Finding |
|---|---|
| What it is | `ipverify.py` writes the Pi's IP to SQL Server every 5 s (`ipverify.py:50-59`, as read in `05` section 10; not reopened, the file holds credentials) |
| Purpose | Lets a phone find a device through the cloud |
| Under R4 | **Not needed.** NSD, CDM or NFC finds the hub. Retired with the cloud |
| Device credentials | Removing it removes DB credentials from devices (`06` section 1) |

## 2. Is a persistent full-duplex link needed?

**No.** A reading is one request and one answer, started by a human, while a screen is open. Evidence:

| Fact | Evidence |
|---|---|
| Working pattern exists: per-acquisition BLE connection, no reconnect | `D/ble/BleAcquisition.kt:25-29` |
| Wi-Fi is plain request and response | `D/PiGatewayApi.kt:22-33` |
| Boundary argument depends on no ingestion unless asked | `D/PiGatewayVitalsSource.kt:20-24`, `docs/design/samdpi-two-hub-memo.md:1096-1097` |
| A held link blocks others on BLE: advertising stops while owned | `E/transports/ble/control_point.py:158-163` |

The operator's earlier idea, a full-duplex socket with a 5 to 10 s heartbeat between apps, has **no job to do** under D1: there is no socket between the apps.

### When full duplex would be justified

| Use | Justified? | Note |
|---|---|---|
| Remote start | No | Request and response |
| Status during a measurement | No for demo | Poll on IP; indications on BLE, already duplex |
| Live waveform, intermediate cuff pressure | **Yes**, if ever asked | BLE indications or a WebSocket. Not in scope |
| Continuous monitoring | **Yes**, if ever asked | New hazard class. Not in scope |

## 3. Per option: is liveness needed, and how

Required detection time, proposed: a worker should know a link failed **within about 15 s**. Reason: BP latency is up to 4.5 s (`12` section 4.5), and SaMD's BLE cap is 25 s (`D/ble/BleAcquisition.kt:34`).

| Option | Link | Heartbeat or keepalive needed? | Mechanism | Detection | Battery |
|---|---|---|---|---|---|
| **D1** app to app | Activity result, Binder inside the framework | **No** | Framework returns `RESULT_CANCELED` if the child fails or crashes (EXTERNAL: [Activity](https://developer.android.com/reference/android/app/Activity)) | On return | None |
| **D1** OneIoT to hub, BLE | Per capture | **No app heartbeat** | Link supervision; disconnect callback; 10 s envelope timeout, 25 s cap | Up to the timeouts | Connected for one capture |
| **D1** OneIoT to hub, Wi-Fi or USB | Per request | **No** | HTTP timeouts; status poll every 1 s only during a capture | About 3 s after a failed poll | Radio active for one capture |
| D2 | Bound service | **No heartbeat** | `linkToDeath` calls back when the hosting process goes away (EXTERNAL: [IBinder](https://developer.android.com/reference/android/os/IBinder)) | Prompt (INFERRED) | None |
| D3 | WebSocket | **Yes**, while a session is open | OkHttp `pingInterval` 10 s; missed pong fails the socket | 10 to 20 s | Wakes radio every 10 s while open; closes on leaving the screen |
| T1 | In-process | **No** | As D1, hub side | As D1 | None extra |
| T2 | Bound service | **No heartbeat** | `linkToDeath` | Prompt (INFERRED) | None |
| T3 | WebSocket or Nearby | Yes | As D3 | 10 to 20 s | As D3 |

**Interval reasoning for D3 and T3.** Detection is at most twice the interval. A 15 s target gives 5 to 7 s; the operator's 5 to 10 s range is reasonable, with 10 s as the battery-friendlier end and slightly over target. Measure before fixing. In Doze (device unplugged, stationary, screen off) the system suspends app network access outside maintenance windows (EXTERNAL: [Doze](https://developer.android.com/training/monitoring-device-state/doze-standby)), so a backgrounded session cannot promise this at all. Close it instead.

**Not verified:** OkHttp's `pingInterval` failure behaviour in the D3 row is platform knowledge. Two OkHttp doc URLs returned 404 in this study. Confirm from OkHttp's docs before any D3 or T3 work.

## 4. What "live" honestly means on Android

| State | True statement |
|---|---|
| Screen open, app foreground | Link state is known within the detection time |
| App backgrounded | **Do not claim anything.** Cancel the capture. Target 34 and later would require a typed foreground service (`07` section 4), and none is proposed |
| Process killed | Link dropped. Hub discards session on owner disconnect (`control_point.py:174-179`); Wi-Fi hub session already completed. No state survives (`docs/quality/risk-management-file.md:279-285`) |
| Hub rebooted or down | The Avahi record is static and stays advertised, so a phone sees the hub as unreachable rather than absent (`DEPLOY.md:130-131`) |

The UI must not show "Connected" as a standing fact. Show the current action: Connecting, Measuring, Done, Failed. A green dot when nothing checks it is exactly this project's characteristic bug.

## 5. Message rules

Defined in `12` section 4. Summary:

| Topic | Rule |
|---|---|
| Schema version | `schema_version` in manifest; `version` in envelope; `reading_version` on reading; `envelope_version` on BLE |
| Per-reading idempotency key | `measurement_id`; on BLE derived from `(nonce, sequence)` |
| Ack | HTTP response; BLE indication confirm; D1 needs none: one result per launch |
| Delivery | At-least-once hub to OneIoT; **exactly one result** OneIoT to SaMD per request id |
| Dedupe | SaMD ignores a result for a request id it has consumed |
| Retry | GET may retry; start never retries with the same nonce; BLE has no retry |
| Half-written payload | IP: HTTP `Content-Length` (`E/transports/wifi/server.py:206`). BLE: sha256 over pages, envelope parser rejects trailing bytes (`E/envelope.py:137-138`). D1: parcel is atomic |
| Stale reading | Session or nonce mismatch rejects. Clock is advisory only, as it is the hub's (`E/envelope.py:12`). `STALE_TIMESTAMP` passes today (`09` section 1), since `measured_at` is not gated. Whether to gate: OQ |

## 6. Failure table

| Case | D1 behaviour | Control |
|---|---|---|
| Disconnect mid-capture | OneIoT shows failure, no Send | Disconnect callback, no reconnect |
| Disconnect after reading, before Send | Reading kept in OneIoT memory; Send still works | Link release is required anyway (`10` section 1.6) |
| Duplicate send | One result per launch | Request id |
| Half-written payload | Not possible on Binder | Framework |
| Stale reading | Nonce mismatch | RC-1 |
| OneIoT killed | Cancel | Nothing written |
| SaMD killed | Result dropped | RC-4 |
| Manifest mismatch | Refuse before START | Allowlist, sha256 |
| Skew | Refuse | Version fields |

## 7. Docs vs code

| Docs say | Code does | Result |
|---|---|---|
| Paper: device pings the host at a configurable tick rate (`02` section 8) | `ipverify.py` writes its IP to the database every 5 s (`05` section 10) | Code wins. This is the only heartbeat, and it is cloud reporting, not liveness |

## 8. Amendments at STOP 3 (R4-3, R4-R; operator 2026-11-08; additions only)

- **Latency (R4-3).** Opt-in per session with `simulate_latency` in the start request; default `false`, so the reading is ready at once and SaMD's single GET after start still works (`12` section 11.5). With the flag set, the client polls `Status` (HTTP) or takes `Status` indications (BLE) until `done`, `error` or `stopped`, inside the existing 25 s cap (`12` section 4.4).
- **Heartbeat returns, if R4-R is adopted.** The paper's device-to-host heartbeat with a configurable tick rate (p.6, p.23; DOCS-ONLY) becomes a small unit on kernelhub1 sending to our own Registration host (`10` section 9.3). It stays heartbeat in the A7 sense: it tells the host the device's address. It is not app-to-hub liveness, which D1 still does not need (section 2). kernelhub2's BLE service cannot send one (`PrivateNetwork=yes`, `samdpi-hub-ble.service:33`).
- **Configuration phase.** Setting the tick rate is the paper's own Configuration example (p.9), so it gives kernelhub1 a faithful Configuration message (`12` section 11.6).
