# SaMDPi two-hub split: kernelhub1 (Wi-Fi) and kernelhub2 (BLE)

Status: PROPOSED. Design only. No code, no branch, no commit, no tracked file edited.
Date: 2026-10-07. Author: Claude (Opus 5), for operator gate review.
Location: `docs/design/samdpi-two-hub-memo.md`, per the `docs/design/README.md` convention.
Sections 1 to 13 are the design as proposed on 2026-10-07. Section 14 (added 2026-10-08) records
every ruling, amendment and correction since, and holds where the two disagree.

Bases:

- SaMD-App (this repository) at `origin/master` = `59c0181`
  (merge of PR #77). The task prompt expected `d60e247`; that commit is 3 behind.
- SaMDPi (https://github.com/ren276/SaMDPi) at `origin/master` = `efaaa9b` (merge of PR #2),
  plus two local, uncommitted changes: `M emulator/models.py`, `?? Dockerfile`.

Evidence convention. Every factual claim carries one tag:

- VERIFIED: followed by `file:line`, or the exact command and its output.
- INFERRED: reasoned, not observed. Each one is either cheap to verify during build or appears in
  section 13.

Constraints honoured while writing this: no `.env`, `local.properties`, keystore, `secrets/` or
credential-suggestive file opened (the `PI_GATEWAY_BASE_URL` value below is operator-stated);
hubs reached only by `ssh -o BatchMode=yes`; hub commands limited to the approved read-only
list; phone read with `getprop` and `dumpsys` only; no em dashes.

Scratch used: a local scratch folder (local evidence, not in either repo) holds the downloaded
Bluetooth SIG ICS PDFs and the GATT Specification Supplement used in section 5; their public URLs
are under Sources. Two hash files written briefly in a local working folder (local evidence, not
in either repo) during Phase 0 were deleted.

---

## 1. Current state as found

### 1.1 Repos

| Item | Value | Evidence |
|---|---|---|
| SaMD-App HEAD | `59c0181` on `master`, equal to `origin/master`; only `?? scratchpad/` untracked | VERIFIED `git log -1 origin/master`, `git status --short` |
| SaMDPi path | separate repository, https://github.com/ren276/SaMDPi; its local checkout is not under the SaMD-App tree | VERIFIED `find ... -iname 'samdpi*'` over the local disk (local evidence, not in either repo) |
| SaMDPi HEAD | `efaaa9b`, 0 ahead and 0 behind `origin/master` | VERIFIED `git rev-list --left-right --count HEAD...origin/master` gave `0 0` |
| SaMDPi dirty tree | `emulator/models.py` adds `CompositeVitalsTuple.update_readings` (4 lines, no caller); `Dockerfile` untracked | VERIFIED `git diff`, `grep -rn update_readings` found only the definition at `models.py:156` |
| SaMDPi dependency manifest | none (no requirements, no pyproject) | VERIFIED `ls requirements*.txt pyproject.toml` failed |
| OpenIoT protocol repo | local folder `IOTApp` (local evidence, not in either repo), zipped 2024 copy, not git, not read | VERIFIED `ls`; age operator-stated |
| Previous Pi integration memo | `docs/design/pi-relay-reconciliation-memo.md` (PROPOSED). It still cites the retired path `oneTB_SSD/samdpiadapterstest` | VERIFIED memo lines 1 to 8 |

### 1.2 Where the emulator runs today

| Host | What runs | Evidence |
|---|---|---|
| Laptop (dev box) | Docker container `samd-pi-emulator`, image `samd-pi-emulator:latest`, amd64, built 2026-09-21, `0.0.0.0:8090->8090`, bridge network, restart `unless-stopped`, ENTRYPOINT `python3 -m emulator.main --host 0.0.0.0 --port 8090 --daemon-wifi` | VERIFIED `docker ps -a`, `docker inspect`, `docker image inspect` (`arch=amd64`) |
| Laptop container code | byte-identical to the SaMDPi working tree, including the uncommitted `models.py` | VERIFIED per-file sha256 diff, differences were sort order only |
| kernelhub1 | `samd-pi-emulator.service`, native systemd, `loaded active running`; code at `/home/sandesh/SaMDPi`, byte-identical to the SaMDPi working tree including `models.py` | VERIFIED `systemctl list-units --type=service`, sha256 diff printed `kernelhub1 emulator == local working tree` |
| kernelhub1 bind | `0.0.0.0:8090` | VERIFIED `ss -tuln` |
| kernelhub2 | nothing: no service, no copy, no data listener | VERIFIED `systemctl list-units`, `find`, `ss -tuln` |
| Docker on hubs | not installed on either | VERIFIED `docker: command not found` on both |

The code default since `d3115aa` binds to the LAN address, not `0.0.0.0`
(VERIFIED `emulator/transports/wifi/server.py:289-295`). So kernelhub1's unit passes
`--host 0.0.0.0` explicitly (INFERRED, `systemctl cat` was not run).

How the phone reaches an emulator today: the dev build's `PI_GATEWAY_BASE_URL` is overridden in
local.properties to `http://127.0.0.1:8090/` (operator-stated). `127.0.0.1` on the handset reaches
the laptop only through `adb reverse tcp:8090 tcp:8090` (VERIFIED
`tools/dev-connect.sh`, the `adb -s "$dev" reverse tcp:8090 tcp:8090` line). `adb reverse --list`
was empty at recon time (VERIFIED), so at that moment no emulator was reachable from the phone
(INFERRED). A non-`.local` host bypasses mDNS (VERIFIED `NsdGatewayDns.kt:216-218`).
**kernelhub1 is not in the phone's data path in the current configuration.**

### 1.3 Hubs

| | kernelhub1 | kernelhub2 | Evidence |
|---|---|---|---|
| Static hostname | `kernel-hub` | `kernelhub2` | VERIFIED `hostnamectl` |
| OS | Debian 13.7 trixie, arm64 | Debian 13.7 trixie, arm64 | VERIFIED `/etc/os-release` |
| Kernel | 6.18.39+rpt-rpi-2712 | 6.18.50+rpt-rpi-2712 | VERIFIED `uname -a` |
| BlueZ | 5.82, `bluetooth.service` active | 5.82, active | VERIFIED `bluetoothd -v` via libexec path, `systemctl is-active bluetooth` |
| Controller | 2C:CF:67:C9:D8:52 public, Name/Alias `kernel-hub` | D8:3A:DD:9F:DC:16 public, Name/Alias `kernel-hub2` (rename not propagated) | VERIFIED `bluetoothctl show` |
| HCI version | 0x09 | 0x09 | VERIFIED `bluetoothctl show`; 0x09 means Bluetooth 5.0 (INFERRED from Core assigned numbers) |
| Pairable / Discoverable | no / yes, timeout 0 | no / no | VERIFIED |
| Roles | central, peripheral | central, peripheral | VERIFIED |
| Advertising | 0 of 5 instances active; MaxAdvLen 31, MaxScnRspLen 31 | same | VERIFIED |
| eth0 | UP 10.203.12.57/20 | DOWN | VERIFIED `ip -br addr` |
| wlan0 | UP 10.203.2.76/20 | UP 10.203.13.194/20 | VERIFIED |
| TCP listeners | 22, `0.0.0.0:8090`, 127.0.0.1:6010 | 22, 127.0.0.1:6010 | VERIFIED `ss -tuln`. 6010 is an SSH X11 forward (INFERRED) |
| avahi-daemon | running; publishes `_samd-gw._tcp` instance `kernel-hub` at 10.203.2.76:8090 (wlan0) | running; no `_samd-gw` record | VERIFIED `systemctl list-units`, laptop `avahi-browse -rtp _samd-gw._tcp` |
| `iw`, `git` | not installed | not installed | VERIFIED `command not found` |
| SSH | key auth works under BatchMode | key auth works under BatchMode | VERIFIED. Password login disabled and sudo needs a password: operator-stated |

### 1.4 Phone

iQOO I2302, Android 16, SDK 36, installed `com.example.samdapp.dev` and `.dev.test`
(VERIFIED `getprop ro.build.version.release`, `ro.build.version.sdk`, `pm list packages`).
`dumpsys bluetooth_manager` returned nothing readable, so the handset's Bluetooth state is unknown.

### 1.5 SaMD-App acquisition path

- Seam: `VitalsSource` with `readVitals`, and `startAcquisition`/`stopAcquisition` with default
  bodies. The KDoc says "exactly one vitals seam and one binding" and the code has
  "One instrument per acquisition; there is no composite route" (VERIFIED `VitalsSource.kt:12-14,28-30`).
- `RejectReason` has 9 values (VERIFIED `VitalsSource.kt:38-67`). Every value is mapped to
  worker text by an exhaustive `when` (VERIFIED `CompounderViewModel.kt:200-210`), so a new value
  is a compile error until it gets a message.
- Dev binding: `DevClinicalMockModule.bindVitalsSource(impl: PiGatewayVitalsSource)`
  (VERIFIED `app/src/dev/java/.../di/DevClinicalMockModule.kt:39`).
- Start: `CompounderScreen.kt:281` (local-network permission wrapper), then
  `CompounderViewModel.onStartAcquisition` (`:544-592`), then `AcquireDeviceVitalsUseCase`
  (`VitalsUseCases.kt:31-36`), then `PiGatewayVitalsSource.startAcquisition`
  (`PiGatewayVitalsSource.kt:41-76`). That sends `POST api/v1/session/start` with
  `reading_count: 1` (`PiGatewayApi.kt:21-23,47-53`), one `GET api/v1/measurement`, and then
  `PiMeasurementMapper.map` (VERIFIED).
- Gate order: RC-2 quality, RC-1 session id, RC-1 device type, then map; no substitution
  (VERIFIED `PiMeasurementMapper.kt:41-90`).
- RC-4 by absence: the acquisition path only mutates UI state (VERIFIED `CompounderViewModel.kt:539-543`).
- Stop: `onStopAcquisition` (`:600-605`) and `onCleared` teardown (`:609-625`), then
  `POST api/v1/session/stop` (VERIFIED).
- Gating: the controls render only when `PI_GATEWAY_ENABLED` (`CompounderScreen.kt:186-190`).
  That flag is true in dev only (`app/build.gradle.kts:108,139,146`). The gateway classes live in
  `src/dev/` (VERIFIED).
- Provenance: per-field `VitalsFieldProvenance { MANUAL, DEVICE, DEVICE_EDITED }`
  (`CompounderProvenance.kt:24`). The snapshot rolls up to `ObservationSource.DEVICE` when any
  field is still DEVICE (`CompounderViewModel.kt:165-170`). **Nothing persisted records that a
  value was synthetic**; `synthetic` reaches only the audit payload (`CompounderViewModel.kt:563`)
  (VERIFIED).
- Capture-method dropdown: worker-owned, structurally separate from the acquisition card
  (`CompounderScreen.kt:192-203,270-278`). The only code writing it is the human demo button
  `fillDemoData` (`CompounderViewModel.kt:419`) (VERIFIED).
- Labelling: the card title is "Instrument reading (dev)" (`CompounderScreen.kt:287`); no
  "Emulated" label exists (VERIFIED).
- mDNS: `NsdGatewayDns` resolves `.local` hosts through DNS-SD type `_samd-gw._tcp.`
  (`NsdGatewayDns.kt:246`). It matches the service instance name against the URL host
  (`:296-301`). Avahi publishes `%h` (`tools/kernel-hub-avahi.service:30`). Build default
  `http://kernel-hub.local:8090/` (`app/build.gradle.kts:118`) (VERIFIED).
- Manifest: `ACCESS_LOCAL_NETWORK` only (`AndroidManifest.xml:12`), enforced from SDK 37
  (`LocalNetworkFailure.kt:13`). No Bluetooth permission. No BLE library in
  `gradle/libs.versions.toml`. No `devImplementation` used yet in `app/build.gradle.kts` (VERIFIED).
- Risk file: `docs/quality/risk-management-file.md` has H-01 to H-15 and no H-PI row
  (VERIFIED). H-13 residual still reads "No real BLE/device vitals-monitor integration exists yet"
  (VERIFIED line 37).

### 1.6 SaMDPi emulator

- Entry: `emulator/main.py`, default `--mode composite` (VERIFIED `main.py:27-33`).
  `--transport BLE` drives `MockGattServer`, which is in-memory and never touches a radio
  (VERIFIED `main.py:139-149`, `session_manager.py:357-361`, `gatt_server.py:45-60`).
- Wi-Fi server: stdlib `ThreadingMixIn` HTTPServer plus `threading.Timer`
  (VERIFIED `server.py:32-33,122-125`). With the contracted `reading_count: 1`, no timer is
  armed (VERIFIED `server.py:122`). The first reading is produced synchronously inside
  `start_session` (VERIFIED `server.py:60-61`).
- Wire: `MOCK_WIFI_JSON` over HTTP/1.1 on 8090, no auth. Response is `NormalizedMeasurement`
  (VERIFIED `models.py:52-77`). `/health` hardcodes `"target_hardware": "Raspberry Pi 5 Model B
  (kernel-hub)"` (VERIFIED `server.py:190`). The composite endpoints are reachable
  (VERIFIED `server.py:202-209,244-264`), and the composite path still carries the G-A
  default-fabrication defect (VERIFIED pi-relay memo section 7.6).
- Real BLE server `emulator/transports/ble/real_ble_server.py`:
  - built on `bluez_peripheral`; four services in one peripheral "HealthSaRK-Medical";
  - measurement characteristics declared `NOTIFY | INDICATE | READ` (`:80,94,108`);
  - an HTTP control server on `0.0.0.0:8091` (`:258-260`);
  - free-running emission with no Start/Stop over BLE (`:319-339`).
  (VERIFIED)
- Encoders: BP and PLX use SFLOAT; the thermometer uses 32-bit FLOAT (VERIFIED
  `encoders.py:135-189,197-278`). A `DEVICE_ERROR` BP today encodes as a valid zero:
  `encode_bp_measurement(0.0, 0.0, 0.0)` gives `00 00 00 00 00 00 00` (VERIFIED by running the
  repo encoder). This is the D-13 trap on the BLE wire.
- Identity: `device_id` is per instrument (`HealthSaRK-BP-001`), not per hub
  (VERIFIED `instruments.py:21`). `__version__ = "1.0.0"` is a literal (VERIFIED `emulator/__init__.py`).
- Tests: 5 pytest files, none covering `real_ble_server.py` (VERIFIED `git ls-files tests/`).
- SaMDPi also holds a sample Android app with a raw `android.bluetooth` client
  (VERIFIED `app/src/main/java/com/example/samdpi/transport/ble/BleGattClient.kt`, 324 lines).
  It is not part of SaMD-App and is not reused here.

---

## 2. Decisions D1 to D7

Each decision lists the decision, the alternatives considered, the failure modes, and a
recommendation. A leaning is accepted only where the repo agrees with it.

### D1. One codebase, per-hub profile, refuse invalid profiles

**Decision.** Accept the profile mechanism. Reject "one arm64 image on both hubs".

- One codebase (SaMDPi `master`) runs on kernelhub1, kernelhub2 and the laptop container.
- A tracked, non-secret TOML profile per hub selects instrument and transport:
  `profiles/kernelhub1.toml`, `profiles/kernelhub2.toml`, `profiles/laptop-docker.toml`.
- Parsing uses stdlib `tomllib`, which needs Python 3.11 or later (INFERRED: Debian trixie
  ships 3.13; the laptop image is `python:3.11-slim`, VERIFIED `Dockerfile:1`). No new dependency.
- A new entrypoint `python -m emulator.hub --profile <path>` loads and validates the profile,
  then starts exactly one transport.
- The profile path comes from the systemd unit or the Dockerfile, never from `hostname`.
- The process exits with status 78 (EX_CONFIG) on any invalid profile, and the unit sets
  `RestartPreventExitStatus=78` so systemd does not crash-loop on a bad profile.

**Why not one arm64 image.** Neither hub has Docker (VERIFIED). Installing it is a
sudo, operator-run change on two machines for no functional gain:

- the Wi-Fi hub needs only the stdlib;
- the BLE hub needs one pure D-Bus dependency (D5);
- kernelhub1 already runs natively under systemd (VERIFIED).

The Dockerfile stays, for the amd64 laptop emulator only, and gets committed in PR-A
(it is untracked today).

**"No deleted or forked emulator files."** Honoured, with a cost stated plainly:

- `main.py`, `MockGattServer` and `real_ble_server.py` stay byte-identical and unreferenced by
  `emulator.hub`.
- `real_ble_server.py` cannot be the BLE hub. It is multi-instrument by design, it opens HTTP on
  8091, and it has no Start/Stop.
- Its deletion is filed (section 12), not done here.

**Alternatives.**

| Option | Verdict |
|---|---|
| (a) Extend `main.py` with `--profile` | Rejected. Its composite default (`main.py:30`) and mock-BLE path stay one flag away from a profile-started hub. |
| (b) Hostname-keyed config | Rejected. Violates the explicit `hub_id` rule. |
| (c) Env vars | Rejected. Untyped, nothing to validate as a unit, not reviewable as a tracked artefact. |

**Failure modes and guards.**

| Failure mode | Guard |
|---|---|
| Profile names two instruments | `instrument` must be a scalar string from the enum. A list or table is refused. Mutation-tested. |
| Typo key silently ignored | Unknown keys are refused at every level. |
| Profile copied to the wrong hub | `hub_id` is self-declared and published in `/health`, the mDNS TXT record, DIS Serial Number and every BLE envelope. The phone pins the expected `hub_id` and rejects a mismatch (section 7). |
| Stale profile format | `profile_version` must equal the version the code supports. |

**Recommendation.** Profile plus `emulator.hub` plus native systemd on both hubs; Docker on
the laptop only.

### D2. One process per hub, one event loop; phone-side concurrency

**Decision, hub side.** One process per hub, accepted.

- BLE hub: one asyncio loop and no threads, accepted. `dbus-fast` is asyncio-native (D5).
- Wi-Fi hub: keep the existing stdlib server unchanged. Accept per-request handler threads; do
  not port it to asyncio.

Reasons for leaving the Wi-Fi hub alone:

- D3 asks to keep the Wi-Fi wire format.
- The contracted path (`reading_count: 1`) never arms the timer thread (VERIFIED `server.py:122`).
- Porting it means either a new dependency (aiohttp) or a hand-rolled HTTP parser. Both add risk
  and remove nothing.

"No threads" is therefore a BLE-hub property, proven by a test that asserts
`threading.active_count() == 1` after the BLE hub starts against a fake bus.

**Decision, phone side.** Reject "one cold Flow per transport, merged in the repository layer".
Adopt a per-instrument router behind the existing suspend seam.

Why the merge is rejected:

- It contradicts "exactly one vitals seam and one binding" and "no composite route"
  (VERIFIED `VitalsSource.kt:12-14,28-30`).
- It weakens boundary rationale 3, "The SaMD does not autonomously ingest from the Pi"
  (VERIFIED pi-relay memo section 9.1 item 3). A merged always-on stream is ingestion nobody
  asked for.

The leaning's mechanism survives inside the BLE transport. Indications are a cold Flow that
exists only inside one `startAcquisition` call, is consumed with `first { ... }` under a
timeout, and is cancelled on return.

All GATT operations are serialized by the BLE library's request queue (D7). There is no
hand-rolled queue.

**Failure modes.**

| Failure mode | Guard |
|---|---|
| Two concurrent acquisitions race field writes | Existing in-flight guard `canStartAcquisition` (VERIFIED `CompounderViewModel.kt:173,546-548`) stays the only concurrency rule. |
| Router falls back from a failed BLE BP to Wi-Fi | A different hub would answer the worker's request. Forbidden; test G-B1. |

**Recommendation.** As stated. One acquisition at a time from the phone; both hubs powered and
reachable at the same time.

### D3. kernelhub1 keeps its wire format; mDNS so the literal can retire

**Decision.** Keep `MOCK_WIFI_JSON` request and response shapes. Changes are additive only
(section 4). The literal to retire is `127.0.0.1`, the adb-reverse route to the laptop, not a Pi IP.

mDNS already exists:

- `NsdGatewayDns` plus `_samd-gw._tcp`; the type is pinned by a unit test (VERIFIED
  `tools/kernel-hub-avahi.service:20-23`);
- kernelhub1 already advertises it (VERIFIED `avahi-browse`).

Do **not** introduce `_samdpi._tcp`: a second type means a second pinned constant and a second
place to drift.

Change the Avahi record from `%h` to a static instance name equal to `hub_id`, `kernelhub1`,
and add TXT records. Then:

- the app URL becomes `http://kernelhub1.local:8090/`;
- `NsdGatewayDns` matches the instance name `kernelhub1` (VERIFIED `NsdGatewayDns.kt:299`);
- the address comes from DNS-SD resolve, not an A record. So the Pi's hostname `kernel-hub`
  becomes irrelevant and needs no rename.

This is the change that takes hub identity off the hostname.

**Alternatives.**

| Option | Verdict |
|---|---|
| (a) Rename kernelhub1's host to `kernelhub1` | Rejected. Hostname-derived identity again, plus `/etc/hosts` churn. |
| (b) Keep `%h` | Rejected. Breaks the explicit-`hub_id` rule. |
| (c) Literal IP | Rejected. DHCP-fragile, which is what NSD replaced. |

**Failure modes.**

| Failure mode | Guard |
|---|---|
| Two hubs publish the same instance name | Avahi renames the clash to `kernelhub1 #2`, and the exact-match rule refuses it (VERIFIED `NsdGatewayDns.kt:296-298`). |
| Phone not on the hub's LAN | Fails as `UNREACHABLE` (existing behaviour). |

**Recommendation.** Static instance name plus TXT. Move the Avahi file into SaMDPi `deploy/`,
where it is installed (today it lives in SaMD-App `tools/`). One local.properties line, edited by
the operator.

### D4. kernelhub2 as a GATT peripheral with Bluetooth SIG profiles

**Decision.** Accept SIG profiles, with four corrections the specs force (section 5 has the
evidence):

1. **0x2A35 is Indicate only.** BLS ICS: Indication M; Single Notification is mandatory only
   with Intermediate Cuff Pressure, which we do not expose; Read Characteristic Value is not
   listed. The repo's `NOTIFY|INDICATE|READ` (`real_ble_server.py:80`) is non-conformant.
2. **Blood Pressure Feature 0x2A49 (Read) is mandatory** and missing today.
3. **The thermometer uses FLOAT (medfloat32), not SFLOAT.** The repo encoder is already right
   (`encoders.py:166-189`); the leaning is wrong.
4. **PLX:** use Spot-check 0x2A5E (Indicate) plus the mandatory PLX Features 0x2A60 (Read).
   Do not expose Continuous 0x2A5F. A one-reading acquisition is a spot check, and a Notify
   stream would be the ingestion D2 rejects.

**The SIG profile alone cannot carry RC-1.** 0x2A35 has no session field (VERIFIED GSS
section 3.34). RC-1 requires `provenance.session_id` to equal this acquisition's session
(VERIFIED `PiMeasurementMapper.kt:145-149`, pi-relay memo section 2). Section 9.1 says if any RC
is removed "the determination does not hold".

Therefore the **only admissible data source is a vendor Reading Envelope characteristic**. It
carries the session nonce, sequence, hub_id, instrument code, quality, the emulated flag and the
SIG measurement bytes verbatim.

The SIG service is still exposed, so any generic collector (nRF Connect) sees a standard BP
monitor. The SaMD app never admits a bare SIG indication. Side benefit: a real third-party BP
cuff nearby can never be admitted, because it has no envelope.

**Alternatives.**

| Option | Verdict |
|---|---|
| (a) Pair a separate session characteristic with the SIG indication | Rejected. Two indications must be correlated, which is an ordering race. |
| (b) Put a session token in the BP User ID field (uint8) | Rejected. 256 values, collides with multi-user semantics. |
| (c) Reword RC-1 to "this GATT connection after Start" | Rejected for now. Needs an operator-signed change to a PROPOSED control, and is weaker against queued indications. |
| (d) Vendor-only payload, no SIG service | Rejected. Loses standards fidelity and the free conformance check. |

**Recommendation.** SIG BLS (or PLXS or HTS by profile), DIS, and a vendor service with Control
Point and Reading Envelope. The envelope embeds the SIG bytes, so the phone runs the SIG decoder
on every admitted reading.

### D5. Start/Stop over a vendor control characteristic; BlueZ integration; library

**Decision.**

- **Control.** A vendor Control Point (Write with response, plus Indicate) toggles only the
  emulation session. The GATT server and advertising stay up for the life of the process. This
  mirrors Wi-Fi exactly: the daemon is persistent and Start opens a one-reading session
  (VERIFIED `server.py:49-68`). State machine in section 5.6.
- **Host integration.** Native systemd, not a container.
  - Neither hub has Docker (VERIFIED).
  - For BLE, `network_mode: host` is irrelevant: BLE does not ride IP. It would only widen IP
    exposure on the hub that must have none.
  - Mounting `/run/dbus/system_bus_socket` couples the container's uid and gid to BlueZ's D-Bus
    policy, which is fragile.
  - The unit sets `RestrictAddressFamilies=AF_UNIX`, so the kernel itself refuses any IP socket
    from the BLE hub process (section 6).
- **Library.** Use `dbus-fast` directly to talk to BlueZ (`GattManager1.RegisterApplication`,
  `LEAdvertisingManager1.RegisterAdvertisement`).

| Library | Latest | Released | Depends on | Evidence |
|---|---|---|---|---|
| `bluez-peripheral` (in repo today) | 0.1.7 stable; 0.2.0a5 alpha | 2022-12-19; 2026-01-08 | `dbus-next` | VERIFIED PyPI JSON and RSS |
| `bless` | 0.3.0 | 2025-12-23 | `bleak>=1.1.1`, `dbus_next` on Linux | VERIFIED PyPI JSON |
| `dbus-next` (transitive for both above) | 0.2.3 | 2021-07-25, nothing since | none | VERIFIED PyPI JSON |
| `dbus-fast` | 5.2.0 | 2026-10-02 | none | VERIFIED PyPI RSS; repo pushed 2026-10-05, not archived (GitHub API) |

Both higher-level libraries rest on `dbus-next`, which has had no release since 2021. `bless`
also drags in `bleak`, a BLE client library, onto a peripheral. `dbus-fast` is actively released
by the Bluetooth-Devices org and is asyncio-native, which is exactly D2's BLE-hub model.

The cost is writing the BlueZ object tree ourselves: ObjectManager plus GattService1,
GattCharacteristic1 and LEAdvertisement1, for one SIG service, DIS and two vendor
characteristics. Estimated 250 to 350 lines (INFERRED).

The BlueZ version is not a differentiator: both hubs run 5.82 (VERIFIED).

**Failure modes.**

| Failure mode | Guard |
|---|---|
| bluetoothd restarts and our objects vanish | Watch `NameOwnerChanged` for `org.bluez`, exit non-zero, and let `Restart=on-failure` re-register. No in-process recovery code (INFERRED adequate). |
| Indication sent while CCCD is off, so the reading vanishes silently | START is refused with `CCCD_NOT_ENABLED` unless the envelope CCCD is on (section 5.6). |
| CCCD state bookkeeping | Owned by BlueZ through `StartNotify`/`StopNotify` (INFERRED from BlueZ gatt-api docs; verify in PR-A). |

**Recommendation.** `dbus-fast==5.2.0`, pinned with hashes in `requirements-ble.txt`, installed
into a venv on kernelhub2. Native systemd unit.

### D6. Instrument assignment

**Decision.** Accept: kernelhub2 is the BP monitor over BLE, kernelhub1 is the pulse oximeter
over Wi-Fi.

Swappable by editing the hub profile **and** one phone-side assignment line (section 7.1).

The phone decodes BP (0x2A35), PLX spot-check (0x2A5E) and Thermometer (0x2A1C), the three
profiles D4 names, so a swap among those needs no app change. Glucose, Weight and HR over BLE
reject with `NOT_SUPPORTED` until a decoder exists (YAGNI).

**Failure mode.** The hub and phone assignments disagree. Caught at Start: the hub refuses an
instrument it does not serve, with `INSTRUMENT_NOT_SERVED` on BLE or HTTP 400 on Wi-Fi.

### D7. Android BLE central

**Decision.** Accept a direct central to kernelhub2 with no relay, CompanionDeviceManager
association, no bonding in dev, and production bonding filed.

Changes to the leaning:

- **Permissions go in `app/src/dev/AndroidManifest.xml` only.** Staging and prod must not gain a
  Bluetooth permission, the same structural-absence posture as `src/dev/` code
  (VERIFIED `PiGatewayNetworkModule.kt` KDoc, `DevClinicalMockModule.kt:19-25`).
- **CDM first; app-side scanning only as a fallback.** Google documents that CDM "Doesn't require
  location permissions" (VERIFIED developer.android.com Bluetooth permissions page).
- **Library: `no.nordicsemi.android:ble-ktx:2.11.0`**, pinned, as `devImplementation` only.

| Artifact | Latest on Maven Central | Status | Evidence |
|---|---|---|---|
| `no.nordicsemi.android:ble` and `:ble-ktx` | 2.11.0, 2025-09-11 | stable | VERIFIED `maven-metadata.xml` |
| `no.nordicsemi.kotlin.ble:client-android` (Kotlin BLE Library v2) | 2.0.0-beta06, 2026-10-02 | no stable 2.x exists | VERIFIED version list: alpha01 to alpha19, beta01 to beta06 |
| `no.nordicsemi.android.kotlin.ble:client-android` (v1) | 1.3.1, 2025-05-27 | superseded by v2 | VERIFIED |

A beta does not go into a medical-device repo, even dev-only. `ble-ktx` has a built-in serial
request queue, which satisfies D2 without our own.

**Alternative:** raw `android.bluetooth`. Rejected. The SaMDPi sample shows it costs over 300
lines (VERIFIED `BleGattClient.kt`), and it is where status-133 and queueing bugs live (INFERRED).

**Recommendation.** As stated. Section 7 has the details.

---

## 3. Hub profile schema and reading envelope

### 3.1 Hub profile (TOML, `profile_version = 1`)

```toml
# profiles/kernelhub2.toml  (tracked, non-secret)
profile_version = 1            # integer; must equal the version this build supports
hub_id = "kernelhub2"          # ^[a-z0-9-]{3,32}$ ; explicit, NEVER derived from hostname
instrument = "BP"              # exactly one of BP SPO2 THERMOMETER GLUCOMETER WEIGHT_SCALE HEART_RATE
transport = "ble"              # "wifi" | "ble"

[ble]                          # required iff transport = "ble"; forbidden otherwise
adapter = "hci0"
local_name = "SaMD-kernelhub2" # scan-response name; <= 20 bytes (section 5.7)
```

```toml
# profiles/kernelhub1.toml
profile_version = 1
hub_id = "kernelhub1"
instrument = "SPO2"
transport = "wifi"

[wifi]                         # required iff transport = "wifi"; forbidden otherwise
bind_interface = "wlan0"       # resolved to that interface's IPv4 at start; "*" only for laptop-docker
port = 8090
```

`profiles/laptop-docker.toml` is the same as kernelhub1 except `hub_id = "laptop-docker"` and
`bind_interface = "*"`. Inside a bridge-networked container `0.0.0.0` is required; the container
boundary does the scoping.

Validation, all refusals exit 78:

- every key above is required and no other key is allowed, at any level;
- type checks on every value;
- `instrument` must be a scalar enum string;
- the section matching `transport` must be present and the other must be absent;
- `hub_id` must match the regex;
- `profile_version` must equal the supported value;
- `[ble]` and `[wifi]` cannot both be present.

Scenario is not in the profile. It stays per acquisition, chosen on the phone
(VERIFIED `VitalsSource.kt:32-34`).

`emulator_build` is not in the profile. It is runtime data: `__version__` plus the deployed git
short sha, read from an untracked `BUILD` file that the deploy step writes. If absent it is
`"unknown"`, which `/health` and DIS will show honestly.

### 3.2 Reading envelope (logical model, `envelope_version = 1`)

| Field | Type | Source | Authority |
|---|---|---|---|
| `envelope_version` | int | hub | contract version |
| `hub_id` | string | hub profile | identity, pinned by the phone |
| `transport` | `wifi` / `ble` | the phone, from the channel it used; the hub also states it on Wi-Fi | channel fact |
| `instrument` | enum | hub profile | must equal the requested instrument (RC-1 second half) |
| `profile_version` | int | hub profile | contract version |
| `emulator_build` | string | hub runtime | provenance only |
| `session_id` | Wi-Fi: hub-issued UUID string. BLE: 16-byte phone-generated nonce, hex | see column | must equal this acquisition's (RC-1) |
| `sequence` | uint32, starts at 1 per session | hub | must be 1 for the contracted one-reading session |
| `quality` | OK / WARNING / ERROR / UNAVAILABLE | hub | only OK admissible (RC-2) |
| `device_time` | UTC instant | Pi clock | **advisory only**: Pi clock sync is not guaranteed (INFERRED, the Pi 5 RTC needs a battery and NTP needs a route) |
| `phone_received_time` | UTC instant | phone, `Instant.now()` at receipt | **authoritative**, never sent by a hub |
| `emulated` | bool | hub | must be `true` in this system; `false` is refused as MALFORMED, because no real instrument is supported |
| measurement values | per instrument | SIG bytes on BLE, JSON numbers on Wi-Fi | admitted only after every gate |

OpenIoT seam: `envelope_version`, `profile_version`, the instrument code table, the mDNS TXT keys
and the GATT UUIDs are all versioned data. A future manifest can describe them without code
assumptions changing. Nothing else is built for OpenIoT now.

---

## 4. Wi-Fi hub contract and discovery (kernelhub1)

All changes are additive. The existing Gson DTO ignores unknown fields (INFERRED: Gson default;
proven by an unchanged `PiGatewayVitalsSourceTest` passing against a response with extra fields).

| Endpoint | Change |
|---|---|
| `POST /api/v1/session/start` | Unchanged request and response. New: `instrument` not equal to the profile's instrument returns 400 `{"error":"INSTRUMENT_NOT_SERVED"}` before any session state changes. |
| `GET /api/v1/measurement` | Unchanged fields. Adds top level `hub_id`, `profile_version`, `emulator_build`, `envelope_version: 1`. `synthetic` remains the emulated flag. `measured_at` is `device_time`. |
| `POST /api/v1/session/stop` | Unchanged. |
| `GET /health` | `target_hardware` literal replaced by a `hub` object `{hub_id, instrument, transport:"wifi", profile_version, emulator_build}`. |
| Composite endpoints | Return 404 when started through `emulator.hub`. G-A becomes unreachable on every hub. |

Bind: `bind_interface = "wlan0"`, so the listener is `10.203.2.76:8090` and not `0.0.0.0`. This
makes "sends only over Wi-Fi" true: eth0 can no longer reach 8090.

Unit hardening: `RestrictAddressFamilies=AF_INET AF_INET6`. No `AF_UNIX`, so this process cannot
talk to BlueZ over D-Bus, which makes "Wi-Fi only" kernel-enforced.

Discovery, `deploy/avahi/kernelhub1.service`, moved from SaMD-App `tools/kernel-hub-avahi.service`:

```xml
<service-group>
  <name replace-wildcards="no">kernelhub1</name>
  <service>
    <type>_samd-gw._tcp</type>
    <port>8090</port>
    <txt-record>hub_id=kernelhub1</txt-record>
    <txt-record>instrument=SPO2</txt-record>
    <txt-record>profile_version=1</txt-record>
    <txt-record>envelope_version=1</txt-record>
  </service>
</service-group>
```

App side:

- `PI_GATEWAY_BASE_URL` default becomes `http://kernelhub1.local:8090/` (`app/build.gradle.kts:118`).
- The operator deletes the `127.0.0.1` override from local.properties when switching to the hub,
  or keeps it for laptop-only desk work.
- `NsdGatewayDns` is unchanged. Its KDoc path reference to `tools/kernel-hub-avahi.service` is
  updated.
- The phone must be on kernelhub1's Wi-Fi LAN, 10.203.0.0/20 (INFERRED from the /20 on wlan0).

---

## 5. BLE GATT table (kernelhub2)

### 5.1 Sources checked

| Source | Revision | What it settles |
|---|---|---|
| Assigned Numbers `service_uuids.yaml`, `characteristic_uuids.yaml` (Bluetooth SIG Bitbucket) | fetched 2026-10-07 | every UUID below |
| BLS ICS | BLS.ICS.p11, 2026-06-23 | BLS requirements |
| PLXS ICS | PLXS.ICS.p5, 2026-06-23 | PLXS requirements |
| HTS ICS | HTS.ICS.p4, 2026-02-17 | HTS requirements |
| DIS ICS | DIS.ICS.p7, 2026-02-17 | DIS requirements |
| GATT Specification Supplement | 2026-09-09 | field formats, special values |

The ICS files were downloaded with the URLs `bls-ics-p8`, `plxs-ics-p3`, `hts-ics-p3`,
`dis-ics-p5`, but the PDF contents carry the newer revisions listed above.

UUIDs, all VERIFIED against the assigned-numbers YAML:

- Services: 0x1810 Blood Pressure, 0x1822 Pulse Oximeter, 0x1809 Health Thermometer,
  0x180A Device Information.
- Characteristics: 0x2A35 Blood Pressure Measurement, 0x2A36 Intermediate Cuff Pressure,
  0x2A49 Blood Pressure Feature, 0x2A5E PLX Spot-Check Measurement, 0x2A5F PLX Continuous
  Measurement, 0x2A60 PLX Features, 0x2A1C Temperature Measurement, 0x2A1D Temperature Type,
  0x2A1E Intermediate Temperature, 0x2A29 Manufacturer Name String, 0x2A24 Model Number String,
  0x2A25 Serial Number String, 0x2A28 Software Revision String, 0x2A08 Date Time.
- 0x2902 CCCD: Core-defined (INFERRED, not in the fetched YAML query).

Requirements, VERIFIED from the ICS tables:

- **BLS** (supported over LE: M; BLS v1.1 mandatory):
  - Blood Pressure Measurement M;
  - Blood Pressure Feature M;
  - Intermediate Cuff Pressure O;
  - GATT Indication M;
  - Single Notification only if ICP or Enhanced ICP or BP Record;
  - Read and Write Characteristic Descriptor M;
  - Bonding procedure O.
- **PLXS:**
  - at least one of Spot-check or Continuous;
  - PLX Features M;
  - Indication mandatory if Spot-check;
  - Read Characteristic Value M (for PLX Features).
- **HTS:**
  - Temperature Measurement M;
  - Temperature Type O;
  - Indication M;
  - Single Notification only if Intermediate Temperature.
- **DIS:** every string characteristic O; PnP ID O.

Formats, VERIFIED from the GSS:

- BP compound values and Pulse Rate are `medfloat16` (SFLOAT), section 3.34.
- Temperature Measurement Value is `medfloat32` (FLOAT), section 3.239.
- Time Stamp is 7 bytes (Date Time, section 3.80).
- Special values, section 2.1.1:

| Meaning | SFLOAT | FLOAT |
|---|---|---|
| NaN | 0x07FF | 0x007FFFFF |
| NRes | 0x0800 | 0x00800000 |
| +INF | 0x07FE | 0x007FFFFE |
| -INF | 0x0802 | 0x00800002 |
| RFU | 0x0801 | 0x00800001 |

### 5.2 Table (profile `instrument = "BP"`)

| Service | Characteristic | Properties | CCCD | Value |
|---|---|---|---|---|
| 0x1800 GAP, 0x1801 GATT | (BlueZ-provided) | - | - | Device Name 0x2A00 comes from the adapter alias, so the alias must be set (section 12) |
| 0x180A DIS | 0x2A29 Manufacturer Name | Read | - | `"SaMD dev emulator (synthetic)"` |
| | 0x2A24 Model Number | Read | - | `"samdpi-hub"` |
| | 0x2A25 Serial Number | Read | - | `hub_id`, e.g. `"kernelhub2"` |
| | 0x2A28 Software Revision | Read | - | `emulator_build` |
| 0x1810 BLS | 0x2A35 Blood Pressure Measurement | **Indicate only** | yes | SIG bytes, section 5.4 |
| | 0x2A49 Blood Pressure Feature | Read | - | `0x0000` (no optional detection features) |
| Vendor `f4610001-95ad-4b7d-a8ed-b568b5b0e927` | Control Point `f4610002-...-b568b5b0e927` | Write (with response), Indicate | yes | section 5.6 |
| | Reading Envelope `f4610003-...-b568b5b0e927` | Indicate | yes | section 5.5 |

The vendor UUID base is a fresh random v4 generated for this memo (VERIFIED `uuid.uuid4()` output
`f46135e4-95ad-4b7d-a8ed-b568b5b0e927`). Bytes 2 and 3 of the base are replaced by
0001, 0002, 0003. It is frozen on PR-A merge.

Profile swaps:

- SPO2 replaces 0x1810 with 0x1822: 0x2A5E (Indicate) plus 0x2A60 PLX Features (Read). Supported
  Features is 0x0000, which keeps the PLX Features conditional fields excluded.
- THERMOMETER replaces it with 0x1809: 0x2A1C (Indicate).

No characteristic in this system is Notify-only, and none of the measurements is Readable. The
latter means nothing can poll a stale value.

Dev security: no encryption flags on any characteristic; adapter Pairable stays `no`
(VERIFIED current). Production is in section 12.

### 5.3 MTU assumption

The envelope is 35 + len(hub_id) + len(SIG bytes) bytes (section 5.5). The worst case is
hub_id 32 plus SIG 19 = 86 bytes, which needs ATT MTU of at least 89, since an indication carries
MTU - 3 bytes (INFERRED from the Core ATT rules).

- The phone requests MTU 517 right after connect.
- The hub reads the negotiated MTU from BlueZ's `mtu` option on `WriteValue` (INFERRED; verify in
  PR-A).
- The hub refuses START with `MTU_TOO_SMALL` below 89.
- No long writes, no segmentation.
- The Control Point write is 19 bytes at most, so it fits even at the default MTU 23.

### 5.4 SIG measurement encodings (byte layouts, little endian)

Every example below is VERIFIED by running the repo's own encoders (`encoders.py`) in a
read-only import.

**SFLOAT** (16-bit): bits 15 to 12 are a signed 4-bit exponent, bits 11 to 0 a signed 12-bit
mantissa; value = mantissa x 10^exponent.

| Value | Bytes | Decode |
|---|---|---|
| 120 | `78 00` | 0x0078: mantissa 120, exponent 0 |
| 80 | `50 00` | |
| 72 | `48 00` | |
| 98 | `62 00` | |
| 93.333 (MAP) | `a5 f3` | 0xF3A5: exponent 0xF = -1, mantissa 0x3A5 = 933, so 93.3 |
| 36.7 | `6f f1` | 0xF16F: exponent -1, mantissa 367 |
| 0.0 | `00 00` | **a valid zero**, the D-13 trap |

**FLOAT** (32-bit): bits 31 to 24 are a signed 8-bit exponent, bits 23 to 0 a signed 24-bit
mantissa.

| Value | Bytes |
|---|---|
| 36.7 | `6f 01 00 ff` (exponent 0xFF = -1, mantissa 367) |
| 39.4 | `8a 01 00 ff` |

**Blood Pressure Measurement 0x2A35:**

```
off size field
0   1    flags  bit0 units (0=mmHg) | bit1 time stamp | bit2 pulse | bit3 user id | bit4 status
1   2    systolic  SFLOAT
3   2    diastolic SFLOAT
5   2    MAP       SFLOAT
7   7    time stamp (if bit1): year u16, month, day, hour, minute, second
+   2    pulse rate SFLOAT (if bit2)
+   1    user id (if bit3)            -> never set here
+   2    measurement status u16 (if bit4) -> never set here
```

Golden vectors:

- `04 78 00 50 00 a5 f3 48 00`: 120/80 mmHg, MAP 93.3, pulse 72, no time stamp.
- `06 78 00 50 00 a5 f3 ea 07 0a 07 0a 1e 0f 48 00`: the same at 2026-10-07 10:30:15.

**PLX Spot-check 0x2A5E** (the layout beyond the first three fields comes from the PLXS spec
body, not the GSS: INFERRED):

```
0 1 flags  bit0 timestamp | bit1 measurement status | bit2 device+sensor status | bit3 PAI | bit4 device clock not set
1 2 SpO2 SFLOAT
3 2 PR   SFLOAT
+ 7 timestamp (if bit0)
```

Golden vector: `00 62 00 48 00` = SpO2 98 %, PR 72.

**Temperature Measurement 0x2A1C:**

```
0 1 flags bit0 unit (0=Cel) | bit1 time stamp | bit2 temp type
1 4 FLOAT value
+ 7 time stamp (if bit1)
+ 1 temperature type (if bit2; 2 = body)
```

Golden vector: `04 6f 01 00 ff 02` = 36.7 Cel, type body.

**Fault encoding (required change).** For quality ERROR or UNAVAILABLE (scenarios
`DEVICE_ERROR`, `SENSOR_UNAVAILABLE`), every present value field is SFLOAT NaN `ff 07` (FLOAT NaN
`ff ff 7f 00`), never `00 00`. The phone rejects any special value in any present field even
when quality says OK. That is defence in depth for RC-2.

### 5.5 Reading Envelope characteristic (Indicate)

```
off      size  field
0        1     envelope_version = 1
1        1     profile_version  = 1
2        1     instrument code: 1 BP, 2 SPO2, 3 THERMOMETER, 4 GLUCOMETER, 5 WEIGHT_SCALE, 6 HEART_RATE
3        1     flags: bit0 emulated (MUST be 1); bits1-7 reserved, MUST be 0
4        1     quality: 0 OK, 1 WARNING, 2 ERROR, 3 UNAVAILABLE
5        16    session nonce (echo of the START that opened this session)
21       4     sequence u32 (1 for the contracted one-reading session)
25       8     device_time i64, ms since Unix epoch UTC, advisory
33       1     n = hub_id length (3..32)
34       n     hub_id, ASCII [a-z0-9-]
34+n     1     m = SIG payload length
35+n     m     SIG characteristic value bytes, exactly as indicated on the SIG characteristic
```

kernelhub2 BP with time stamp and pulse is 35 + 10 + 16 = 61 bytes.

The phone refuses, as MALFORMED:

- an unknown `envelope_version`;
- reserved bits set;
- `emulated` = 0;
- a length mismatch;
- trailing bytes.

The hub sends the envelope indication first and the SIG indication second. ATT permits one
outstanding indication per bearer (INFERRED, Core ATT), so the hub awaits each confirmation in
order. The phone ignores the SIG indication; it subscribes to it only so that a generic collector
and the phone see the same server.

### 5.6 Control Point state machine

Requests (Write with response):

| Opcode | Payload | Length |
|---|---|---|
| 0x01 START | instrument code u8, scenario code u8 (0 NORMAL, 1 LOW, 2 HIGH, 3 DEVICE_ERROR, 4 SENSOR_UNAVAILABLE), nonce 16 bytes | 19 bytes |
| 0x02 STOP | nonce 16 bytes | 17 bytes |

The response is an indication on the Control Point: `0x80, request opcode, result`.

| Result | Code |
|---|---|
| SUCCESS | 0x01 |
| OPCODE_NOT_SUPPORTED | 0x02 |
| INVALID_PARAMETER (bad length, scenario or nonce) | 0x03 |
| INSTRUMENT_NOT_SERVED | 0x04 |
| BUSY (another connection owns a session) | 0x05 |
| NOT_RUNNING (STOP with no session) | 0x06 |
| MTU_TOO_SMALL | 0x07 |
| CCCD_NOT_ENABLED (envelope CCCD off) | 0x08 |

This response pattern is modelled on SIG control points such as RACP (INFERRED analogy). The
alternative, ATT errors on the write itself, was rejected: BlueZ maps a small fixed set of D-Bus
errors to ATT codes (INFERRED), which would lose these reasons.

```
IDLE
  START ok (instrument served, CCCDs on, MTU >= 89)   -> RUNNING(owner=conn, nonce=N, seq=0)
                                                          respond SUCCESS; after its confirmation,
                                                          emit ONE reading (seq=1) then -> DONE
  START bad                                           -> IDLE, respond reason
  STOP                                                -> IDLE, respond NOT_RUNNING
RUNNING / DONE (owner = conn A)
  START from A, valid      -> RUNNING with new nonce N' (replaces; mirrors server.py:49-55)
  START from B             -> respond BUSY, state unchanged
  STOP from A, nonce == N  -> IDLE, respond SUCCESS
  STOP from A, nonce != N  -> respond INVALID_PARAMETER, state unchanged
  A disconnects            -> IDLE (session discarded, nothing emitted after)
```

Invariant: an envelope is only ever emitted with the current session's nonce, after that
session's SUCCESS response was confirmed. A nonce is never re-emitted after it is replaced.

### 5.7 Advertising (31-byte legacy PDU, VERIFIED MaxAdvLen 31)

| AD structure | Bytes |
|---|---|
| Flags | 3 |
| Incomplete 16-bit UUIDs [0x1810] | 4 |
| Complete 128-bit UUIDs [vendor service] | 18 |
| Total | 25 |

Scan response: Complete Local Name `SaMD-kernelhub2` = 17 bytes. Connectable, undirected.
Advertising stays on while the process runs.

---

## 6. kernelhub2 runtime layout and proving "BLE only"

Layout (native, no container):

```
/home/sandesh/samdpi/                       deployed SaMDPi tree (rsync from a clean checkout) + BUILD file
/home/sandesh/samdpi/.venv/                 python3 -m venv; pip install --require-hashes -r requirements-ble.txt (dbus-fast==5.2.0)
/etc/systemd/system/samdpi-hub.service      from tracked deploy/systemd/samdpi-hub-ble.service (operator installs, sudo)
```

```ini
[Unit]
Description=SaMDPi hub (BLE peripheral, profile-selected)
Requires=bluetooth.service
After=bluetooth.service

[Service]
User=sandesh
SupplementaryGroups=bluetooth
WorkingDirectory=/home/sandesh/samdpi
ExecStart=/home/sandesh/samdpi/.venv/bin/python -m emulator.hub --profile profiles/kernelhub2.toml
Restart=on-failure
RestartPreventExitStatus=78
NoNewPrivileges=yes
ProtectSystem=strict
PrivateTmp=yes
RestrictAddressFamilies=AF_UNIX

[Install]
WantedBy=multi-user.target
```

Notes:

- The D-Bus policy on Debian lets group `bluetooth` talk to `org.bluez` (INFERRED; verify with a
  live START in PR-A). If it does not, the fix is a policy drop-in, not root.
- The kernelhub1 unit is the same shape: profile `kernelhub1.toml`, no
  `Requires=bluetooth.service`, `RestrictAddressFamilies=AF_INET AF_INET6`. It replaces the
  existing `samd-pi-emulator.service`.

Proof of "BLE only", all checks:

1. **Kernel-enforced:** `systemctl show samdpi-hub -p RestrictAddressFamilies` prints `AF_UNIX`.
   Any `socket(AF_INET, ...)` from the process fails with EAFNOSUPPORT.
2. **Observed:** `ss -tulnp` on kernelhub2 shows only sshd on 22, avahi on 5353 and its ephemeral
   UDP port, and the 127.0.0.1:6010 X11 forward if an SSH session has one. No 8090, no 8091.
3. **Negative probe:** from the laptop, `curl -m 3 http://10.203.13.194:8090/` must fail with
   connection refused.
4. **Positive:** `bluetoothctl show` reports `ActiveInstances: 0x01`. nRF Connect on any phone
   lists 0x1800, 0x1801, 0x180A, 0x1810 and the vendor service.
5. **Code-level:** a pytest guard asserts that starting `emulator.hub` with a BLE profile against
   a fake bus leaves `emulator.transports.wifi.server` absent from `sys.modules`. Mutation-tested.
6. **Physical, recommended (section 9):** eth0 cabled for management and `rfkill block wifi`, so
   kernelhub2 has no Wi-Fi radio at all.

---

## 7. Android BLE client design (SaMD-App, dev flavour only)

### 7.1 Routing

`RoutingVitalsSource` (`src/dev/`) replaces `PiGatewayVitalsSource` as the single
`VitalsSource` binding (`DevClinicalMockModule.kt:39`). It holds:

- `wifi: PiGatewayVitalsSource` (unchanged);
- `ble: BleHubVitalsSource` (new);
- an assignment from a dev `buildConfigField` `PI_HUB_ASSIGNMENT`, default
  `"SPO2=wifi,BP=ble:kernelhub2"`, overridable from local.properties exactly like
  `PI_GATEWAY_BASE_URL`.

Behaviour:

```
startAcquisition(r) = when (assignment[r.instrument]) {
    WIFI -> wifi.startAcquisition(r)
    BLE  -> ble.startAcquisition(r)
    null -> Rejected(NOT_SUPPORTED)
}
```

- No fallback across transports.
- `stopAcquisition` routes to the transport of the last started acquisition.
- `readVitals` makes zero network and zero BLE calls (existing invariant, VERIFIED
  `PiGatewayVitalsSource.kt:18-22,39`, extended to BLE by test G-B8).

Alternative: runtime discovery by instrument (NSD TXT `instrument=` plus BLE scan filter by SIG
service). Deferred, because it adds a scan before every Start. The OpenIoT manifest is the
natural home for it. Section 13 Q3.

### 7.2 Permissions by API level (`app/src/dev/AndroidManifest.xml` only)

| API | Declared | Runtime request |
|---|---|---|
| 26 to 30 | `BLUETOOTH` and `BLUETOOTH_ADMIN`, both `maxSdkVersion="30"` | none for CDM; CDM needs no location (VERIFIED Google docs) |
| 31 and up | `BLUETOOTH_CONNECT` | yes ("Nearby devices") before the first connect |
| 31 and up, fallback only | `BLUETOOTH_SCAN` with `usesPermissionFlags="neverForLocation"` | only if CDM is unavailable (section 13 Q10) |
| all | `<uses-feature android:name="android.hardware.bluetooth_le" android:required="false"/>`, `<uses-feature android:name="android.software.companion_device_setup" android:required="false"/>` | - |

The iQOO is SDK 36, so `BLUETOOTH_CONNECT` is live. `ACCESS_LOCAL_NETWORK` stays for the Wi-Fi
hub, enforced only from 37 (VERIFIED `LocalNetworkFailure.kt:13`).

Guard G-B11 proves no Bluetooth permission exists in `src/main/AndroidManifest.xml`. It reads
the file as text, so it must be declared as a Gradle task input (an operator working note, local
evidence, not in either repo).

### 7.3 Association flow

1. The first BP Start with no association calls
   `CompanionDeviceManager.associate(AssociationRequest(BluetoothLeDeviceFilter(service UUID =
   vendor service, name pattern ^SaMD-kernelhub2$), singleDevice = true))`. API 33 and up use the
   executor callback overload; 26 to 32 use the `IntentSender` overload (INFERRED API shape).
2. The worker picks the hub in the system dialog. This is an explicit human choice, part of the
   wrong-peripheral control.
3. The association persists in CDM. Later Starts use the associated address directly with no scan.
4. On connect, the client reads DIS Serial Number. It must equal the assigned `hub_id`
   (`kernelhub2`), otherwise disconnect and reject `HUB_MISMATCH`. The envelope's `hub_id` is
   checked again on every reading.

### 7.4 Operation queue and one acquisition

Built on `ble-ktx`'s `BleManager`, which serialises every request in its own queue
(INFERRED library behaviour, verify in PR-B). One acquisition does:

1. connect (timeout 10 s);
2. `requestMtu(517)`; refuse below 89;
3. discover; require the vendor service, the SIG service for the requested instrument, and DIS;
4. read DIS Serial (hub_id check) and DIS Software Revision (emulator_build, audit);
5. enable indications on Control Point, Envelope and the SIG characteristic;
6. generate a 16-byte nonce with `SecureRandom`;
7. write START, await the Control Point response (3 s), require SUCCESS, otherwise map the
   result to a reason;
8. take the first Envelope indication **after** that response (10 s timeout), stamp
   `phone_received_time`;
9. parse the envelope and decode the SIG bytes;
10. build a `NormalizedMeasurementDto`:
    - `deviceType` from the instrument code;
    - `qualityStatus` from the quality byte;
    - `provenance.session_id` = the nonce hex;
    - values from the SIG decode;
    - `synthetic` = emulated;
11. **call the existing `PiMeasurementMapper.map`**. RC-1 and RC-2 have exactly one
    implementation for both transports;
12. write STOP and disconnect, in `finally`.

The overall cap is 25 s; Stop cancels at any point.

### 7.5 Reconnect, backoff, lifecycle

- **No automatic reconnect, no backoff loop.** One connect attempt per Start. A failure is shown
  and the worker presses Start again. This matches the repo rule that a failed call propagates
  and is never replayed somewhere else (VERIFIED `NsdGatewayDns.kt:185-191`). A hidden retry
  could hand the worker a reading from a later moment than the one they acted on.
- **No foreground service.** An acquisition is user-initiated, happens while
  `CompounderScreen` is visible, takes 25 s at most, and the connection exists only inside it.
  Background BLE work is the case that needs a `connectedDevice` FGS; there is none here.
  `onCleared` already runs Stop on screen exit (VERIFIED `CompounderViewModel.kt:609-625`).
  Process death drops the link and persists nothing (RC-4).
- **Disconnect mid-acquisition** ends the call immediately with `UNREACHABLE`. No field is
  written (G-B9).

### 7.6 Merge with the Wi-Fi path

There is no merge. The router selects a transport per Start. The ViewModel, mapper, provenance
marks and audit actions are shared unchanged. The audit payload gains `transport` and `hubId`
keys (the backend does not pin payload keys: VERIFIED `grep -rn RejectReason backend` found
nothing).

### 7.7 Errors shown to the worker

`RejectReason` gains two values. Everything else reuses an existing one.

| Situation | Reason | Worker text (proposed) |
|---|---|---|
| Bluetooth off, or `BLUETOOTH_CONNECT` denied | **`BLUETOOTH_UNAVAILABLE`** (new) | "Bluetooth is off or not allowed for this app. Turn it on and allow Nearby devices, then try again." |
| No CDM association, or dialog dismissed | `NOT_SUPPORTED`, or a new value if the operator prefers | "This instrument has not been paired with the app yet." |
| DIS Serial or envelope `hub_id` differs from assignment; hub answers `INSTRUMENT_NOT_SERVED` | **`HUB_MISMATCH`** (new) | "That reading came from a different instrument hub, so nothing was filled in." |
| Connect failed or link dropped | `UNREACHABLE` | needs a BLE-specific message, because the existing text is local-network wording (VERIFIED `CompounderViewModel.kt:201`) |
| No response or no envelope in time | `TIMEOUT` | existing |
| `BUSY` | `UNREACHABLE` | "The instrument is in use by another device." |
| Bad envelope or MTU too small | `MALFORMED` | existing |
| Session, instrument, quality gates | existing RC reasons | existing |

---

## 8. Provenance and truthfulness

- **Emulated label (new).** When `AcquisitionResult.Accepted.synthetic == true`:
  - the acquisition card shows "EMULATED instrument: synthetic values, not a measurement";
  - every field written with DEVICE provenance shows the supporting text "Emulated".
  - Proven by a Compose test (G-B12).
- **The emulated flag is mandatory on both wires.** BLE `flags.bit0 = 1`, Wi-Fi
  `synthetic: true`. Absent or false is MALFORMED in this build: no real instrument is supported,
  so a hub claiming to be real is lying or misconfigured.
- **Capture-method dropdown untouched.** No acquisition path writes `captureMethod`. Guard G-B10:
  after an Accepted BLE reading, `captureMethod` is still null. Mutation: write `DIGITAL_MONITOR`
  in `applyAcceptedReading`, see red.
- **No auto-assignment.** Instrument selection stays the worker's dropdown (VERIFIED
  `CompounderScreen.kt:288-295`). The router reads it and never sets it. A hub never pushes, and
  the phone never connects without a Start.
- **Known gap, recorded, not fixed here.** A saved snapshot records `ObservationSource.DEVICE`
  with no synthetic marker (VERIFIED `CompounderViewModel.kt:165-170`). The control today is
  structural: emulator paths exist only in dev builds, which talk to the dev backend. Persisting a
  synthetic marker means a Room migration, a sync DTO change and a backend change. It is filed
  (section 12) as a precondition before any emulator reading may reach a non-dev backend.

---

## 9. RF coexistence

- The Pi 5 radio is a single combo chip (Infineon CYW43455, INFERRED) with one antenna shared by
  2.4/5 GHz Wi-Fi and Bluetooth through time-division coexistence (INFERRED).
- **kernelhub2:** eth0 is DOWN (VERIFIED), so every SSH session to it rides wlan0 on the same
  chip that does BLE. Its band is unknown: `iw` is not installed (VERIFIED), and `nmcli` was not
  approved. If it is on 2.4 GHz, SSH traffic and indications time-share airtime (INFERRED small
  for a 61-byte indication, but real during an `rsync` deploy).
- **Recommendation for kernelhub2 (operator-run):** cable eth0 for SSH and deploy, then
  `sudo rfkill block wifi`. That frees the radio for BLE and turns "BLE only" from a software
  property into a physical one. NTP then also works over eth0, which keeps `device_time` sane
  (still advisory). If it cannot be cabled, put wlan0 on a 5 GHz SSID.
- **kernelhub1:** data on wlan0 (VERIFIED the Avahi address), management on eth0 (VERIFIED). Its
  Bluetooth is idle but powered and BR/EDR-discoverable forever (VERIFIED `Discoverable: yes`,
  timeout 0). Recommend `sudo rfkill block bluetooth` on kernelhub1. It is not needed for
  correctness, because the unit's `RestrictAddressFamilies` already stops the process using
  BlueZ, but it removes a second always-visible device from the phone's Bluetooth list.
- **Phone:** Wi-Fi and BLE share the handset radio too (INFERRED). Keep kernelhub2 within a few
  metres during demos.

---

## 10. Hazard and regulatory delta (all PROPOSED)

Numbering continues the PROPOSED H-PI-01 to H-PI-04 from the pi-relay memo, none of which is in
the risk file yet (VERIFIED). PR-C should transcribe both sets together after signature.

| ID | Hazard | Sequence | Controls | Proof |
|---|---|---|---|---|
| H-PI-05 | Wrong peripheral, wrong patient | Phone connects to another BLE device (a second hub, a nearby real cuff, or a hub re-profiled to another instrument); its value is autofilled into this encounter | (1) CDM association, a human-chosen device; (2) DIS Serial and envelope `hub_id` must equal the assignment, else `HUB_MISMATCH`; (3) only the vendor envelope is admissible, so a real cuff's bare 0x2A35 is never admitted; (4) instrument code must equal the request (RC-1 second half); (5) hub refuses `BUSY` to a second central | G-B5, G-B2, G-A9 |
| H-PI-06 | Stale or replayed indication | An indication queued from an earlier session, or replayed, is taken as this reading | (1) fresh 16-byte nonce per Start, echoed in the envelope, compared by the existing RC-1 code; (2) envelopes before the Control Point SUCCESS are discarded; (3) one envelope per session, then STOP and disconnect; (4) hub never emits a replaced nonce | G-B3, G-A10 |
| H-PI-07 | Emulated value in a real record | A synthetic number is saved into a patient record believed to be measured | (1) dev-only source set (H-13 pattern); (2) emulated flag mandatory on both wires; (3) visible EMULATED labels; (4) `synthetic` in audit. **Residual:** the persisted record has no synthetic marker (section 8) | G-B6, G-B12, G-B11 |
| H-PI-08 | Silent disconnect | Link drops; the UI keeps "Acquiring..." or the worker assumes the last value is fresh | (1) per-acquisition connection with a 25 s cap; (2) disconnect callback ends the call with `UNREACHABLE`; (3) no auto-reconnect or replay; (4) Stop is idempotent; (5) a rejection never writes a field (existing) | G-B9 |
| H-PI-09 | Encoding or decoding error | SFLOAT exponent or sign mishandled (36.7 read as 367), or a fault encoded as 0 | (1) shared golden vectors byte-identical in pytest and JUnit; (2) faults encode NaN; (3) any special value rejected; (4) RC-3 editability (existing) | G-A7, G-A8, G-B7, G-B4 |

**RC-1 to RC-4 recheck with a second transport:**

- **RC-1 holds unchanged in wording and code.** The BLE path builds the same DTO and runs the same
  `PiMeasurementMapper.map`. The session id is a phone nonce echoed by the hub rather than a
  hub-issued id; the predicate "equals this acquisition's session" is identical. PR-C should
  record the nonce origin in the interface specification.
- **RC-2 holds, and is strengthened on BLE** by the special-value rejection.
- **RC-3 and RC-4 are transport-agnostic.** They live in `CompounderViewModel`, which does not
  change.
- **Boundary rationale 3 (no autonomous ingestion) holds** because the BLE client connects only
  inside `startAcquisition`, and `readVitals` makes no BLE call (G-B8).

**Does the app BLE client change the device boundary?**

- The BLE client is SaMD-App code, so it is inside the software item tree.
- It compiles only into the dev flavour, as the existing Wi-Fi client does (VERIFIED `src/dev/`).
  `ble-ktx` is a `devImplementation` dependency, so the released configurations (staging, prod)
  change by zero bytes. There is no change to the released device's IEC 62304 software items or
  SOUP list (INFERRED; proof by inspecting the staging and prod dependency graphs in PR-B).
- The Pi remains an accessory outside the boundary for exactly as long as RC-1 to RC-4 hold, and
  the second transport keeps all four.
- Promoting any of this to a released flavour would make the BLE client and `ble-ktx` released
  software items (SOUP entry, class assessment) and would need the production security items in
  section 12 first.

SOUP:

- `dbus-fast` runs on the accessory, outside the SaMD. It is recorded in the interface
  specification, not the SOUP list.
- `ble-ktx 2.11.0` is noted as dev-only SOUP-in-waiting.

---

## 11. PR split

Order: PR-A, then PR-B (it consumes PR-A's contract), then PR-C. PR-C is drafted in parallel but
merged only after operator signature.

Mutation protocol for every guard marked M (per an operator working note, local evidence, not in
either repo; the protocol is restated in full here):

1. snapshot the target file and record its sha256;
2. apply the break;
3. run the single test and see RED (an empty test-results directory also counts as RED);
4. restore from the snapshot, not `git checkout`;
5. confirm the sha256 is byte-identical;
6. run again and see GREEN;
7. log every step in the PR body.

### PR-A: SaMDPi, branch `feat/hub-profile-and-ble-peripheral`

Pre-step (operator decision, Q6): commit the Dockerfile; drop the unused
`models.py:update_readings` change.

Files:

| File | Change |
|---|---|
| `profiles/kernelhub1.toml`, `profiles/kernelhub2.toml`, `profiles/laptop-docker.toml` | new |
| `emulator/profile.py` | new: load and validate, `tomllib` |
| `emulator/hub.py` | new entrypoint, exit 78 on invalid profile |
| `emulator/envelope.py` | new: pack, instrument and quality codes |
| `emulator/transports/ble/ble_hub.py` | new: dbus-fast app, advertisement, Control Point state machine |
| `emulator/transports/ble/encoders.py` | NaN for fault values |
| `emulator/transports/wifi/server.py` | additive hub fields, `INSTRUMENT_NOT_SERVED`, composite 404 under hub, bind by interface, `/health` hub object |
| `requirements-ble.txt` | new, hashed |
| `deploy/systemd/samdpi-hub-ble.service`, `deploy/systemd/samdpi-hub-wifi.service`, `deploy/avahi/kernelhub1.service` | new |
| `Dockerfile` | committed; ENTRYPOINT `-m emulator.hub --profile profiles/laptop-docker.toml` |
| `real_ble_server.py`, `gatt_server.py`, `main.py` | untouched |

Tests (pytest; M = mutation-proven):

| ID | Asserts | M |
|---|---|---|
| G-A1 | list-valued `instrument` refused, exit 78 | M |
| G-A2 | unknown key refused at every level | M |
| G-A3 | `hub_id` comes only from the profile: `socket.gethostname` monkeypatched to `"evil"` changes nothing | M (mutation: default `hub_id` to `gethostname()`) |
| G-A4 | BLE profile start leaves `emulator.transports.wifi.server` out of `sys.modules`; `threading.active_count() == 1` | M |
| G-A5 | Wi-Fi start with a non-served instrument returns 400 and leaves state unchanged | M |
| G-A6 | composite endpoints 404 under `emulator.hub` | M |
| G-A7 | DEVICE_ERROR and SENSOR_UNAVAILABLE encode NaN, never `00 00` | M |
| G-A8 | golden vectors (section 5.4 and an envelope vector) byte-exact | M |
| G-A9 | state machine: second connection gets BUSY; wrong-nonce STOP gets INVALID_PARAMETER; owner disconnect returns to IDLE; CCCD off gets CCCD_NOT_ENABLED; MTU 88 gets MTU_TOO_SMALL | M (each) |
| G-A10 | after START N then START N', no envelope carries N | M |
| G-A11 | emulated bit is always 1 | M |

The state machine is a pure class tested without D-Bus. `ble_hub.py` only adapts it to dbus-fast.

Live checks (operator runs sudo steps via `ssh -t`):

- kernelhub2: install the unit, start it, run section 6 checks 1 to 4, read the full GATT table
  with nRF Connect.
- kernelhub1: replace `samd-pi-emulator.service` with the Wi-Fi unit, install the Avahi file,
  `ss -tuln` shows `10.203.2.76:8090` and no `0.0.0.0:8090`, `avahi-browse` shows instance
  `kernelhub1` with TXT.
- Invalid profile: the unit stops with status 78 and does not restart.
- Laptop: rebuilt container `/health` shows `hub_id: laptop-docker`.

### PR-B: SaMD-App, branch `feat/two-hub-ble-acquisition`

Files:

| File | Change |
|---|---|
| `gradle/libs.versions.toml`, `app/build.gradle.kts` | `devImplementation("no.nordicsemi.android:ble-ktx:2.11.0")`, `PI_HUB_ASSIGNMENT`, default URL `kernelhub1.local` |
| `app/src/dev/AndroidManifest.xml` | permissions and features |
| `app/src/dev/.../vitalssource/RoutingVitalsSource.kt`, `BleHubVitalsSource.kt`, `BleHubManager.kt`, `HubEnvelope.kt` (parse plus SFLOAT/FLOAT and SIG decode) | new |
| `app/src/dev/.../di/DevClinicalMockModule.kt` | rebind |
| `domain/vitalssource/VitalsSource.kt` | two `RejectReason` values |
| `CompounderViewModel.kt` | `rejectionMessage` |
| `CompounderScreen.kt` | Emulated labels |
| `NsdGatewayDns.kt` | KDoc path only |
| `tools/kernel-hub-avahi.service` | removed; moved to SaMDPi. Not an emulator file, so D1 does not apply |

Tests (`testDev` JVM unless noted; M = mutation-proven):

| ID | Asserts | M |
|---|---|---|
| G-B1 | BP goes to BLE, SPO2 to Wi-Fi, unassigned is NOT_SUPPORTED; a BLE failure never invokes the Wi-Fi fake | M |
| G-B2 | envelope nonce differs from the sent nonce: SESSION_ID_MISMATCH, no field written | M |
| G-B3 | envelope arriving before the SUCCESS response is discarded, then TIMEOUT | M |
| G-B4 | quality != OK: QUALITY_STATUS_NOT_OK; SFLOAT NaN or NRes or INF in a present field: rejected | M |
| G-B5 | DIS Serial or envelope `hub_id` mismatch: HUB_MISMATCH | M |
| G-B6 | emulated bit 0: MALFORMED | M |
| G-B7 | golden vectors decode exactly (same hex as G-A8) | M |
| G-B8 | `readVitals` performs zero BLE operations (fake manager counts) | M |
| G-B9 | disconnect mid-acquisition: UNREACHABLE within the cap, no field written | M |
| G-B10 | (`test/`, ViewModel) Accepted BLE reading leaves `captureMethod` null | M |
| G-B11 | `src/main/AndroidManifest.xml` has no `BLUETOOTH` permission; file declared as task input | M |
| G-B12 | (androidTest, Compose) Emulated label renders when `synthetic = true` | M |

The existing `PiGatewayVitalsSourceTest` and `PiMeasurementMapperTest` must pass unchanged, which
proves the Wi-Fi path did not regress.

Live checks on the iQOO (SDK 36):

1. First BP Start shows the CDM dialog, then the `BLUETOOTH_CONNECT` prompt, then values with
   EMULATED labels.
2. SPO2 Start over Wi-Fi from kernelhub1 (phone on the 10.203 LAN, override removed).
3. Both hubs up; alternate BP and SPO2 five times each, all accepted.
4. Bluetooth off: BLUETOOTH_UNAVAILABLE text.
5. kernelhub2 unit stopped mid-acquisition: UNREACHABLE within 25 s, no field written.
6. DEVICE_ERROR scenario on both hubs: QUALITY_STATUS_NOT_OK.
7. kernelhub2 re-profiled to SPO2 while the phone assignment says BP: HUB_MISMATCH.
8. Audit rows carry `transport` and `hubId`.
9. The staging and prod dependency graphs have no `no.nordicsemi` entry.

### PR-C: SaMD-App docs, branch `docs/two-hub-hazard-delta` (PROPOSED wording)

Files:

- `docs/quality/risk-management-file.md`: H-PI-01 to H-PI-09 as PROPOSED rows; H-13 residual
  updated;
- the interface specification (pi-relay memo section 9.6 transcribed) extended with sections 4
  and 5 here, and the nonce origin;
- the SOUP note;
- the traceability rows.

No tests. Operator signature gates the merge.

---

## 12. Filed items

| Item | State |
|---|---|
| Production BLE security: LE Secure Connections only, bonding, encrypted-authenticated characteristic permissions, CDM association plus bond | Filed. Not in dev. |
| Android 16 bond loss | Android 16 disconnects, keeps the local bond and shows a system re-pair dialog (VERIFIED Android 16 behaviour-changes page). Production client must map an auth-failure disconnect to an actionable "re-pair the hub" state rather than UNREACHABLE. Filed. |
| SSH | Key-only on both hubs: BatchMode key auth VERIFIED; password login disabled operator-stated; sudo requires a password, so privileged steps stay operator-run. Done. |
| kernelhub2 Bluetooth alias still `kernel-hub2` (VERIFIED) | Set the alias, or PRETTY_HOSTNAME, to `SaMD-kernelhub2` so GAP Device Name matches the advertised name. Operator, sudo. |
| kernelhub1 hostname `kernel-hub` | No rename needed once the Avahi instance name is `kernelhub1` (D3). |
| `real_ble_server.py`, `MockGattServer`, `main.py --transport BLE`, the SaMDPi sample Android app | Unreferenced after PR-A. Deletion filed as a separate operator decision (D1 forbids it now). |
| G-A composite default-fabrication defect | Still open. PR-A makes it unreachable on every hub (404), which is not a fix. |
| Persisted synthetic marker on saved vitals (section 8) | Filed; precondition before any emulator reading reaches a non-dev backend. |
| `measured_at` staleness is not checked by the app; the app `Scenario` enum lacks STALE_TIMESTAMP and DUPLICATE (VERIFIED `VitalsSource.kt:34`) | Covered by nonce plus phone time for now; filed. |
| SaMDPi Python dependencies unpinned (no manifest, VERIFIED) | PR-A pins `dbus-fast` only; the Wi-Fi hub stays stdlib. |
| OpenIoT seam | Reserved only: versioned profile, envelope, TXT keys, frozen UUIDs. Runtime discovery (section 7.1 alternative) belongs there. |
| `emulator.__version__` literal `1.0.0` versus the git-tied scheme from the pi-relay memo section 9.5 | `emulator_build` adds the sha; full scheme filed. |

---

## 13. Open questions for the operator

1. **Docker on the hubs?** Recommend no: native systemd on both, Docker for the laptop only (D1, D5).
2. **Laptop Docker emulator after the hubs are live?** Recommend keep it as `hub_id =
   laptop-docker` for desk work via `127.0.0.1` and adb reverse, never labelled "kernel-hub".
   Only one Wi-Fi target is active in a given phone build.
3. **Phone hub assignment: static (`PI_HUB_ASSIGNMENT` in local.properties) or runtime
   discovery?** Recommend static plus runtime verification (`HUB_MISMATCH`). Discovery waits for
   OpenIoT.
4. **Vendor envelope as the only admissible BLE data path, with SIG 0x2A35 exposed but never
   admitted?** Recommend yes. It keeps RC-1 exactly as signed and stops a real nearby cuff being
   admitted.
5. **Session id origin on BLE: phone nonce (recommended) or hub-issued id in the Control Point
   response?** Phone nonce: one less round trip, and the phone knows it before any data arrives.
6. **SaMDPi dirty tree before PR-A?** Recommend commit the Dockerfile (amended ENTRYPOINT) and
   discard the unused `update_readings` change. Note that both the laptop container and
   kernelhub1 currently run that uncommitted `models.py` (VERIFIED).
7. **kernelhub2 management: cable eth0 and `rfkill block wifi`?** Recommend yes (section 9).
   Fallback is a 5 GHz SSID. May I run `nmcli -t -f ACTIVE,SSID,FREQ dev wifi` read-only on both
   hubs to learn the current band?
8. **Is the phone on kernelhub1's 10.203.0.0/20 Wi-Fi?** Required for the SPO2 path from the hub.
   If not, SPO2 stays on the laptop container until it is.
9. **Two new `RejectReason` values (`BLUETOOTH_UNAVAILABLE`, `HUB_MISMATCH`) in `src/main/`
   domain code?** Recommend yes. The alternative overloads `UNREACHABLE` and `DEVICE_TYPE_MISMATCH`
   with worker text that would mislead.
10. **D7 asked for `BLUETOOTH_SCAN` with `neverForLocation`.** Recommend declaring it only if PR-B
    finds CDM insufficient on the iQOO. CDM alone needs only `BLUETOOTH_CONNECT` on 31 and up
    (INFERRED for SCAN, to verify on device).
11. **`ble-ktx 2.11.0` (stable) versus Kotlin BLE Library v2 (`2.0.0-beta06`, no stable)?**
    Recommend `ble-ktx 2.11.0`; revisit when v2 ships stable.
12. **Simultaneous acquisitions (BP over BLE while SPO2 over Wi-Fi from one Start)?** Recommend
    no. The existing single in-flight guard stays; hubs run concurrently, acquisitions do not.
13. **May I run `systemctl cat samd-pi-emulator.service` on kernelhub1?** It would confirm the
    `--host 0.0.0.0` inference before PR-A replaces the unit.
14. **Emulated label wording** (section 8): operator to approve, or supply the text.

---

## 14. Rulings, amendments and corrections since 2026-10-07

Added 2026-10-08, after SaMDPi PR-A and its review fixes were merged:

- PR #3 (PR-A), https://github.com/ren276/SaMDPi/pull/3, merge `a6d1777`;
- PR #4 (fixes from the post-merge review of PR #3), https://github.com/ren276/SaMDPi/pull/4,
  merge `bba2c6b` (SaMDPi `master` at the time of writing).

Sections 1 to 13 stay as the record of what was designed. Apart from the header and the path
replacements listed in 14.1, they are unchanged. Where this section and sections 1 to 13 disagree,
this section holds.

Sources, cited by short name:

- **PR #3**, **PR #4**: the bodies of those PRs.
- **R3**: the PR #3 post-merge review (2026-10-07). **R4**: the PR #4 merge-gate review. **R4d**:
  the PR #4 delta review. **R4f**: the PR #4 final check (all 2026-10-08). All four are local
  evidence, not in either repo. The PR #4 body summarizes their findings and records the ruling on
  each.
- Commit hashes are on SaMDPi `master` and are ancestors of `bba2c6b`.

### 14.1 Errata to sections 1 to 13

Each item reads: section; what it said; what is true; source.

1. **Section 1.5, risk file.** Said: `docs/quality/risk-management-file.md` has H-01 to H-15.
   True: the register holds H-01 to H-29 and H-32 to H-36. H-30 and H-31 are reserved by
   `docs/design/backend-truthfulness-memo.md` (section 8 there) and are not yet in the register.
   Source: PR #3 "Memo corrections" (operator-stated there), re-checked against the risk file at
   SaMD-App `59c0181` for this record.
2. **Section 6, unit file and notes.** Said: the D-Bus policy lets group `bluetooth` talk to
   `org.bluez`, so the unit sets `SupplementaryGroups=bluetooth`. True: not needed. The D-Bus
   policy grants `org.bluez` to `context="default"`, and the directive was dropped. Source: PR #3
   STOP 0 ruling 5 and "Memo corrections".
3. **Section 5.7, advertising.** Said: the scan response carries the Complete Local Name
   `SaMD-kernelhub2`. True: that holds only when the advertisement sets `LocalName`, which gives the
   full name. `Includes ["local-name"]` makes the kernel cut the name to 10 characters. The hub uses
   `LocalName` only (A14). Source: PR #3 A14 and "Memo corrections"; spike S7 (`ble_hub.py` module
   docstring, `bba2c6b`).
4. **Section 4, bind, and section 11, PR-A live check.** Said: binding the listener to the wlan0
   address makes "sends only over Wi-Fi" true, because eth0 can no longer reach 8090. The live
   check expected `10.203.2.76:8090` and no `0.0.0.0:8090`. True: false under PR #3.
   - On kernelhub1, eth0 (10.203.12.57) and wlan0 (10.203.2.76) share 10.203.0.0/20. Replies
     followed the lower-metric eth0 route, and eth0 answered ARP for the wlan0 address
     (`arp_ignore` and `arp_announce` were 0). So every SPO2 reading from kernelhub1 up to and
     including PR #3's live checks travelled over Ethernet.
   - PR #3's evidence, a refused `curl` to the eth0 address, tested the wrong address.
   - Fixed in PR #4 (`ddd721f`). The listener binds `0.0.0.0` with `SO_BINDTODEVICE` set to the
     profile interface before `bind`. Accepted sockets inherit it, and `ss` shows only
     `0.0.0.0%wlan0:8090`. `deploy/sysctl/90-samdpi-wifi-hub.conf` sets `arp_ignore=1` and
     `arp_announce=2`. `rp_filter` is deliberately left loose, because strict would drop wlan0
     ingress from the phone, whose best reverse route is eth0.
   - Proof: an operator tcpdump over one 40 s window covered 5 SPO2 start-plus-measurement runs
     from the phone, all `HTTP/1.0 200 OK`. It captured 0 packets on eth0 and 103 on wlan0.
   Source: R3 B2; PR #4 "Correction to PR #3" and B2.
5. **Sections 3.1 and 3.2, `emulator_build`.** Said: a string (`__version__` plus the short sha),
   read from an untracked `BUILD`, and `"unknown"` when that file is absent. True:
   - it is an object `{sha, dirty, merged}` on `/health` and `/api/v1/measurement`;
   - DIS Software Revision carries the sha, with `-unmerged` appended for an unmerged build;
   - a hub with a missing, dirty or malformed `BUILD` exits 78, and only `laptop-docker` may be
     dirty (14.2).
   Source: PR #3 A4; R3 N1; PR #4 N1, B1 and S5.
6. **Section 6, layout and proof 1.** Said: the tree is deployed by rsync from a clean checkout,
   and the unit is installed as `samdpi-hub.service`. True: `deploy/deploy_hub.sh` ships a
   `git archive` of an allowlist with a verified `BUILD` manifest and swaps it in atomically
   (14.2). The units are `samdpi-hub-wifi.service` on kernelhub1 and `samdpi-hub-ble.service` on
   kernelhub2. Source: PR #3 A4 and `326462a`; PR #4 S4, BL1 and the live tables.
7. **Section 6, proof 4.** Said: `bluetoothctl show` reports `ActiveInstances: 0x01`. True: 0x01
   only while no central owns the hub. Advertising stops while the hub is owned (0 instances) and
   restarts when the owner disconnects (A13). Source: PR #3 live check vii.
8. **Section 5.6, "START from B -> respond BUSY".** True: a second central is disconnected and
   never owns the hub (A13). Its Control Point write fails at the ATT level with
   `org.bluez.Error.NotPermitted`, and no response is indicated to it. BUSY is the answer the owner
   gets while a rejected device is still connected. Source: PR #3 A13 and STOP 2 rulings 1 and 2.
9. **Section 5.6, "START ok (... CCCDs on ...)".** True: START requires only the Control Point and
   Envelope CCCDs. The SIG characteristic's CCCD is optional. Source: PR #3 A7.
10. **Sections 5.2 and 7.4, one Device Information service.** Said, by implication: the server has
    one 0x180A. True: BlueZ also exposes its own 0x180A, with one characteristic, PnP ID 0x2A50,
    and no 2A25. A central therefore sees two. The BlueZ configuration is left untouched, and PR-B
    searches every instance (14.5). Source: PR #3 "Duplicate 0x180A" and STOP 3a ruling 4.
11. **Section 3.1, instrument enum for a BLE profile.** Said: any one of six instruments. True:
    a BLE profile may name only BP, SPO2 or THERMOMETER. Source: PR #3 STOP 2 ruling 3.
12. **Location of this memo.** The SaMDPi PR #3 body says the design memo is SaMD-App
    `design-records/samdpi-two-hub-memo.md`. That location was never tracked. The memo lives at
    `docs/design/samdpi-two-hub-memo.md`. The PR #4 body does not cite a location. The merged PR
    bodies are not edited. Source: PR #3 body; PR #77's `docs/design/README.md`.
13. **Header and sections 1.1, 7.2 and 11, local paths.** Local-only paths and references to
    operator working notes were replaced by a repository URL or by "local evidence, not in either
    repo". No stated fact changed. Hub paths under `/home/sandesh/` are kept, because SaMDPi
    `DEPLOY.md` documents them.
14. **Section 1.3, hub facts.** Said: the hub table as found at recon on 2026-10-07 (static
    hostnames `kernel-hub` and `kernelhub2`, kernelhub1 Bluetooth powered and discoverable,
    kernelhub2 eth0 DOWN). True: stale after a re-flash.
    - Both hubs were re-flashed on 2026-10-07 to Raspberry Pi OS Lite 64-bit (trixie).
    - The hostnames are now `kernel-hub1` (32 GB card) and `kernel-hub2` (16 GB card). The IPs,
      the SSH aliases `kernelhub1` and `kernelhub2`, and the profile `hub_id`s are unchanged.
      Identity never derives from the hostname.
    - Both hubs: key-only SSH, password login off, sudo requires a password, Wi-Fi power save off.
    - kernelhub1: Bluetooth is rfkill-blocked and `bluetooth.service` is disabled.
    - kernelhub2: eth0 is still not cabled.
    Source: operator-stated, 2026-10-07.

### 14.2 Operator rulings and amendments in force

**PR #3 amendments.** The PR #3 body numbers them A2 to A7, A9 and A11 to A16. There is no A1, A8
or A10.

- **A2.** Wi-Fi responses and `/health` carry `hub_id` in the body and in the `X-SaMDPi-Hub-Id`
  header, error responses included. PR-B verifies it as it does on BLE. Carried by PR #3
  (`bdc28b9`). Stdlib error paths were completed by PR #4 N2 (`edcebe5`, `2e3bd50`) and SF4
  (`f42e226`).
- **A3.** `device_id` is `<hub_id>-<instrument>` on both wires. One function produces it, and only
  on the `emulator.hub` path. PR #3.
- **A4.** `BUILD` is stamped from a `git archive` of an allowlist. `/health` and DIS Software
  Revision report it. A hub exits 78 on a dirty or missing `BUILD` unless it is `laptop-docker`.
  PR #3 (`12a7aea` build stamp, `326462a` deploy); extended by PR #4 B1 (below).
- **A5.** Wi-Fi bind address from `SIOCGIFADDR`. **Superseded** by PR #4 B2 (`ddd721f`): the hub
  path does no address lookup, and `emulator/netif.py` is removed.
- **A6.** The BlueZ assumptions were spiked on kernelhub2 (S1 to S7) before `ble_hub.py` was
  written. PR #3. Results in 14.3.
- **A7.** START requires only the Control Point and Envelope CCCDs; the SIG CCCD is optional. PR #3
  (`12a7aea`).
- **A9.** `pyproject.toml` sets `requires-python >= 3.11`, no runtime dependencies, an extra
  `ble = dbus-fast==5.2.0` and a pinned dev group. There is a `uv.lock`, and a hashed
  `requirements-ble.txt` is installed with `--require-hashes`. PR #3 (`742a535`).
- **A11.** kernelhub1's Bluetooth and the access point are operator tasks outside SaMDPi. PR #3.
- **A12.** Avahi on kernelhub1 publishes only the wlan0 IPv4 address (`DEPLOY.md`). PR #3
  (`326462a`).
- **A13, single-central rule.** The first device to connect owns the hub. Advertising stops while
  it is owned and restarts when the owner disconnects. Any other device is disconnected and never
  owns. START and STOP are accepted only from the owner. While a rejected device is still connected,
  the owner's writes are answered BUSY, because BlueZ's `StartNotify` carries no device. PR #3
  (`12a7aea` state machine, `7470039` adapter).
- **A14.** The advertisement uses `LocalName`, never `Includes ["local-name"]`. PR #3 (`7470039`).
- **A15.** No dbus-fast `ServiceInterface` subclass assigns `self.name`. PR #3 (`7470039`).
- **A16.** The hub keeps its public address with Privacy off. The phone never uses its own address
  as identity, because that address rotates. PR #3.

**PR #3 operator rulings.**

- STOP 0:
  - (1) Run on 8090; the 8092 override is dropped.
  - (2) Leave kernelhub1's Bluetooth and the access point alone.
  - (3) The branch upstream stays unset until the first authorized push. This was a process ruling.
  - (4) pytest runs only as `pytest tests/`, enforced by `testpaths` and guard G-A17.
  - (5) Drop `SupplementaryGroups=bluetooth` (14.1 item 2).
  - (6) `requires-python >= 3.11`, with `pytest==9.1.1` pinned.
- STOP 2:
  - (1) The BUSY-until-stranger-disconnect race is closed with a bounded `Device1.Disconnect` retry
    (3 attempts, 1 s apart, logged) that never relaxes BUSY.
  - (2) A response is indicated only to the owner. Any other device's write fails with
    `org.bluez.Error.NotPermitted`.
  - (3) BLE profiles are limited to BP, SPO2 and THERMOMETER.
  - (4) No commit may contain a `hub.py` that imports a missing `ble_hub`, and every commit passes
    the full suite.
  - (5) The deploy allowlist is `deploy/systemd`, `deploy/avahi`, `emulator`, `profiles` and
    `requirements-ble.txt`. Since then PR #4 added `deploy/sysctl` and left four legacy modules
    out (N13 below).
  - (6) `laptop-docker` serves SPO2.
  - (7) A plain `docker build` fails without `BUILD`, on purpose.
  - (8) `instruments.py` stays out of the PR.
- STOP 3a:
  - (1) The connect-signal check is the first live check, with no implicit-owner-on-write fallback.
  - (2) A pure state-machine contract test: a non-owner write yields exactly one `Respond` and
    nothing else.
  - (3) Both units set `RestartSec=2`, `StartLimitIntervalSec=120` and `StartLimitBurst=40`, which
    gives at least 78 s of retries.
  - (4) The duplicate 0x180A is handled in PR-B, as option (a), with the BlueZ config untouched.
  - (5) Exit codes 69 and 75 are accepted, and 78 is the only no-restart code.
- STOP 4: SaMDPi carries `DEPLOY.md`.

**Gate-review rulings on the section 13 questions** (operator, 2026-10-07; recorded here because no
PR body carries them).

- Q1: no Docker on the hubs; native systemd.
- Q2: keep `laptop-docker` as a desk-test fallback. It can never pose as a hub, because `hub_id` is
  checked on both transports (14.5).
- Q3: a static assignment that names the `hub_id` on both transports:
  `PI_HUB_ASSIGNMENT = "SPO2=wifi:kernelhub1,BP=ble:kernelhub2"`. Laptop desk work sets
  `"SPO2=wifi:laptop-docker"` in local.properties.
- Q4: the vendor envelope is the only admissible BLE data.
- Q5: a phone-generated 16-byte nonce, carried in the DTO as a lowercase canonical UUID4 string
  (one session-id format for RC-1 on both transports).
- Q6: the Dockerfile is committed; the uncommitted `models.py` change was saved as a patch and
  discarded.
- Q7: kernelhub2 is managed over eth0, with `rfkill block wifi` during demos. eth0 is not yet
  cabled; until it is, SSH rides wlan0 on 5 GHz.
- Q8: the phone reaches kernelhub1 over the IITI Wi-Fi, which has no client isolation (phone ping
  to 10.203.2.76 PASS, 2026-10-07). A hub-owned access point is deferred to demo hardening.
- Q9: both new `RejectReason` values; their text in strings.xml.
- Q10: `BLUETOOTH_SCAN` is declared only if CDM proves insufficient on the iQOO.
- Q11: `no.nordicsemi.android:ble-ktx` 2.11.0 as `devImplementation`; Kotlin BLE Library v2 is
  revisited when it ships stable.
- Q12: no simultaneous acquisitions.
- Q13: allowed (the reading itself became moot after the re-flash).
- Q14: approved wording. Card banner "Emulated instrument. Synthetic values, not a real
  measurement." Field supporting text "Emulated".

**Design rules in force.** One line each, with the PR or commit that carries the rule.

- **Vendor envelope as the only admissible BLE data.** The SIG indication is exposed but never
  admitted. Section 2 D4 and 13 Q4. PR #3 (`12a7aea`).
- **Phone-generated nonce.** The BLE session id is a 16-byte phone nonce, echoed in the envelope
  (section 13 Q5). START refuses an all-zero nonce, or one used since process start (the last 4096),
  with INVALID_PARAMETER before touching state. A refused START does not use up its nonce. PR #3;
  PR #4 S2 (`6eb8fb7`).
- **Per-hub profile with an explicit `hub_id`.** A tracked TOML profile, never derived from the
  hostname. An invalid profile exits 78. Section 2 D1. PR #3 (`12a7aea`, `bdc28b9`).
- **Native systemd** on both hubs; Docker only for the laptop emulator. Section 2 D1 and 13 Q1. PR #3
  (`326462a`).
- **systemd hardening.**
  - Both units get the full sandbox set and run Python with `-s` and `PYTHONNOUSERSITE=1`.
  - The BLE unit adds `PrivateNetwork=yes`.
  - The Wi-Fi unit adds `SocketBindAllow=tcp:8090`, `SocketBindDeny=any`,
    `IPAddressAllow=10.203.0.0/20` and `IPAddressDeny=any`.
  - PR #4 S9 and B1 units (`23eec69`).
- **Wi-Fi interface pinning.** `SO_BINDTODEVICE` plus the arp sysctl drop-in (14.1 item 4). PR #4 B2
  (`ddd721f`).
- **BUILD provenance with the file manifest and the merged flag.**
  - `BUILD` is `{"sha", "dirty": false, "merged", "files": {path: git blob id}}`.
  - At start the hub exits 78 on any of: a blob mismatch, a missing file, a symlink, an unlisted
    file, an unusable manifest path, or any `__pycache__` outside the root `.venv`.
  - `merged` is true only for an ancestor of `origin/master` after a fetch. `--allow-unmerged`
    stamps it false, and the hub then reports `-unmerged`.
  - This detects drift at start. It does not detect edits after start, or someone who can edit both
    `BUILD` and the files.
  - PR #4 B1 (`87a4106`), S5 (`87a4106`, `23eec69`), SF1 (`31b2537`), SF-B (`3f8a6ff`), NIT 1
    (`2be40ca`).
- **Atomic deploy.**
  - The archive and `BUILD` are built locally first.
  - The hub extracts into `<dir>.new` and counts the regular files against the archive (exit 4).
  - It then runs `hubctx.load_build` on the staged tree with `-s -I -B` (exit 5).
  - Only then does it swap with `mv`, keeping `<dir>.prev`. Any failure removes `<dir>.new` and
    leaves the live tree and `<dir>.prev` untouched.
  - The script never restarts a unit. It prints the restart command and the stamped sha.
  - PR #4 S4 and N12 (`23eec69`), BL1 (`5294cce`, `62d7870`), SF6 (`2bdbc26`).
- **Venv marker.**
  - `--venv` removes `.venv/.samdpi-requirements` and rebuilds the venv with
    `python3 -m venv --clear`. It writes the marker, the git blob id of the shipped
    `requirements-ble.txt`, only after pip succeeds.
  - The BLE hub exits 78 unless the marker matches.
  - A deploy without `--venv` refuses (exit 3) on a mismatch, and on a kernelhub2 with no `.venv`.
  - `--venv` is refused for kernelhub1.
  - Under `--venv`, a carried venv with no executable python is rebuilt rather than refused.
  - PR #4 (`23eec69`), SF3 (`b5fd299`), NITs 10 and 11 (`2be40ca`), SF-2 (`ca8ab03`).
- **`connect(owner)` is an idempotent no-op.** A connect for the current owner while that owner is
  connected returns no actions and changes no session, subscription or advertising state.
  - Reason: BlueZ can report one link twice (InterfacesAdded plus PropertiesChanged), and a late
    duplicate would wipe live subscriptions on the normal path. A missed disconnect is rarer and
    fails safe as TIMEOUT.
  - This reverses R3 S11's proposed reset. Live, one connect event per link was observed.
  - PR #4 "Rulings and reversals" (`6eb8fb7`).
- **The `start_notify` purge runs only with no intruder connected.** It drops a characteristic's
  pending confirms only then. PR #4 SF2 (`11b3349`), narrowed by SF-A (`fbbd87b`).
- **The `_expect` guard stays.** Ruling 1 of the second pass, which proposed deleting it, was
  reversed at review because the guard is state-reachable. PR #4 "Ruling 1 was reversed at review".
- **`reading_count` must be exactly integer 1** under a hub profile; a missing value is refused.
  PR #4 N10 (`edcebe5`).
- **Hub-path HTTP.**
  - Stdlib error paths answer in JSON with `hub_id`, and a `HTTP/1.0` status line is forced.
  - `Content-Length` must be 1 to 6 ASCII digits.
  - More than one `Content-Length`, or any `Transfer-Encoding`, gets 400.
  - The socket timeout is 10 s per read.
  - PR #4 N2 (`edcebe5`, `2e3bd50`), SF4 (`f42e226`), NITs 3 and 4 (`2be40ca`).
- **N13 reconciliation.**
  - The hub archive leaves out `emulator/main.py`, `real_ble_server.py`, `gatt_server.py` and
    `multi_worker.py`.
  - `generator/__init__.py` no longer imports `multi_worker`, and `server.py` imports
    `CompositeSessionOrchestrator` lazily.
  - The change was made before asking and was accepted at review.
  - PR #4 (`6f864f3`, `23eec69`).
- **Every documented `emulator.hub` command passes `-B`.** After any manual run on a hub,
  `find /home/sandesh/samdpi -name __pycache__ -not -path '*/.venv/*'` must print nothing before the
  next restart. PR #4 SF-1 (`3ee466e`).
- **Laptop image.** It runs as `USER 65534` on a digest-pinned `python:3.11-slim`, and
  `DEPLOY.md` publishes `127.0.0.1:18090:8090` only. PR #4 N6 (`23eec69`).

### 14.3 Facts verified on hardware

**BlueZ version.** 5.82 on both hubs. Section 1.3; PR #4 "Filed"
(`/usr/libexec/bluetooth/bluetoothd -v`, rc 0, read-only).

**Spike results** (A6). S1 to S4 and S6, and the S5 detail, are sourced from the PR-A spike report,
STOP 1, 2026-10-07 (operator-stated; local evidence, not in either repo).

- S1: `RegisterApplication` and `RegisterAdvertisement` succeeded as uid 1000, without sudo.
- S2: `WriteValue` options carry `device` (object path), `link` "LE" and `mtu`; `mtu` was 517
  after the phone requested it.
- S3: BlueZ calls the characteristic's `Confirm` about 97 ms after each indication (6 samples, 97.0
  to 97.4 ms). Back-to-back indications are queued, not dropped. With no subscriber there is no
  `Confirm` and no `StartNotify`.
- S4: untested (no second central). It is still not exercised live (14.4).
- S5: a disconnect arrives as `Device1` PropertiesChanged `Connected` false on that phone's own
  path, and BlueZ calls `StopNotify` on each subscribed characteristic in the same instant. On
  these hubs a disconnect therefore delivers `StopNotify` (also in the `control_point.py`
  `start_notify` comment, `bba2c6b`; PR #4 SF-A).
- S6: on a bluetoothd restart, the advertisement's `Release()` is called, then `NameOwnerChanged`
  for `org.bluez`; re-registration worked 2 s later.
- S7: an `Includes ["local-name"]` advertisement is cut to 10 characters. BlueZ's default
  advertising interval scans at about 1.28 s between packets (`ble_hub.py`, `bba2c6b`).
- Unnumbered: assigning `self.name` on a dbus-fast `ServiceInterface` made BlueZ publish empty
  services (PR #3 A15; `ble_hub.py` docstring).

**BlueZ source facts.** Read from `src/gatt-database.c` in master and in the tags 5.55, 5.66, 5.72,
5.79 and 5.82, not in every tag between them.

- `StartNotify` is called on each device's 0-to-1 CCCD enable (`ccc_write_cb`: "Always call
  StartNotify for an incoming enable"). `StartNotify` carries no device.
- There is no call when the same device rewrites the same value.
- `StopNotify` is called when the last device's enable goes away. That includes a non-bonded
  device's disconnect, via `att_disconnected` and then `clear_ccc_state`. For a bonded device
  `att_disconnected` returns early.

`tests/bluez_model.py` says "5.55 to 5.82", which overstates this; it is left unchanged. Source:
R4d SF-A; R4f; PR #4 SF-A and "Filed".

**PR #3 live checks (kernelhub2, iQOO I2302 with nRF Connect).**

- The GATT table is as designed:
  - DIS 2A29 `SaMD dev emulator (synthetic)` (29 bytes, read in full), 2A24 `samdpi-hub`, 2A25
    `kernelhub2`, 2A28 build sha;
  - 2A35 is INDICATE only and 2A49 is READ;
  - the Control Point is INDICATE plus WRITE, and the Envelope is INDICATE.
- START produced a 61-byte envelope, identical on the phone and in the hub log. Indication order
  held: response, confirm (95 to 98 ms), envelope, confirm.
- One thread, 3 Unix sockets, no IP listener.
- `dbus-fast 5.2.0` installed from the cp313 aarch64 wheel, hash-checked.
- The public address stayed fixed across scans.
- `ActiveInstances` was 0 while the hub was owned and 1 after the owner left. Advertising came back
  16 ms after the disconnect.
- The iQOO sent `mtu=517` with no explicit request.
- A 100 ms advertising interval was accepted and measured at 101 ms. The default measured 1281 to
  1284 ms.
- A first connect was seen via `InterfacesAdded`, a reconnect via `PropertiesChanged`.
- A restarted hub adopted the connected phone via `GetManagedObjects`.
- Phone Bluetooth off produced a graceful teardown: IDLE, then advertising again.

**PR #4 live checks.**

- One connect event per link.
- START worked on a reconnected link after its CCCDs were re-enabled.
- A repeated nonce and a 15-byte nonce were both answered `800103`.
- The BLE unit runs in its own network namespace (`PrivateNetwork=yes`).
- The hub cannot reach itself on `127.0.0.1:8090`.
- On the hubs' Python 3.13.5:
  - a malformed request line and `Content-Length: abc` were each answered `HTTP/1.0 400` with
    `X-SaMDPi-Hub-Id`;
  - a duplicate `Content-Length` got 400 with `hub_id`;
  - a stalled body was closed at 10.01 s, with no session started and no traceback.

**Security scores.** `systemd-analyze security` gave `samdpi-hub-wifi` 1.7 OK and `samdpi-hub-ble`
1.1 OK. PR #4.

**Exit-78 and tamper proofs.**

- PR #3: in the foreground, the hub exited 78 for an unreadable profile, for `BUILD.dirty: true`
  and for a missing `BUILD`.
- PR #4, tamper: one byte was appended to `emulator/hub.py` on kernelhub1, then the unit was
  restarted. It exited 78 with `emulator/hub.py does not match the stamped commit`;
  `Result=exit-code`, `NRestarts=0`, `ActiveState=failed`.
- PR #4, tamper: kernelhub2's `BUILD` was replaced by the PR #3 two-key format. It exited 78 with
  `BUILD must have exactly the keys ['dirty', 'files', 'merged', 'sha']`; `NRestarts=0`.
- PR #4: a failed deploy (`--commit` without `deploy/sysctl`) was refused before any ssh, and the
  hub's `BUILD`, tree and unit were unchanged.
- The first live deploy of the staged check (from `5294cce`) wrote `emulator/__pycache__`, because
  `-I` ignores `PYTHONDONTWRITEBYTECODE`. That was fixed with `-B` (`62d7870`).
- At `94c997d` the `find` for `__pycache__` printed nothing on both hubs.

**Post-merge, `bba2c6b` (2026-10-08; local evidence, not in either repo; operator-run checks
noted).**

- Both hubs were deployed without `--allow-unmerged`. `BUILD` says `merged: true` on both, the
  units are active with `NRestarts=0`, and the journal build line carries no `-unmerged`.
- `/health` shows `merged: true`.
- A malformed request line, `Content-Length: abc`, a duplicate `Content-Length` and
  `INSTRUMENT_NOT_SERVED` were each answered `HTTP/1.0 400` with `hub_id`.
- A stalled body was closed at 10.02 s with zero response bytes and no traceback, by design.
- Avahi publishes one IPv4 address with 4 TXT records.
- kernelhub2 is advertising, on one thread.
- The `DEPLOY.md` exit-78 check, run by the operator with `-B`, returned status 78 and was not
  restarted. The `__pycache__` find then printed nothing.
- Every commit hash cited in the PR #4 body is reachable from `bba2c6b`.

### 14.4 Residual hazards and filed items

**The intruder residual** (filed, not fixed). All three preconditions are required:

1. an intruder is connected and holds the Control Point CCCD;
2. the owner's own Control Point CCCD is off when it writes, either before it has enabled it or
   after it disabled it;
3. the intruder leaves without confirming the queued response.

Sequence:

- The owner's BUSY response is queued.
- The owner then enables its CCCD. Nothing is purged, because an intruder is connected.
- The intruder leaves. There is no `StopNotify`, because the owner still holds the CCCD.
- The owner's next START is queued behind the stale entry. The phone's confirm of the START
  response pops the stale entry instead, so the envelope is never indicated.

Outcome: the phone gets TIMEOUT, and nothing wrong is indicated (fail-safe). It depends on PR-B's
write order (14.5 item 1). The comment is in `control_point.py` `start_notify` (`6ec15cc`).
It is carried as a residual-risk note under candidate H-41 (wrong peripheral, intruder) in 14.6.
Source: PR #4 third pass and final-check fixes; R4f NIT 2; placement under H-41 ruled by the
operator, 2026-10-08.

**Not exercised live.**

- A second central connecting while the hub is owned.
- Supervision-timeout link loss.
- BlueZ-down restart pacing under real systemd.

Source: PR #3 and PR #4, "Not exercised live".

**Still INFERRED from PR #3.**

- `org.bluez.Error.NotPermitted` maps to ATT 0x03.
- `Device1.Disconnect` works on a peripheral-role link.
- Long reads versus a larger MTU.

The two-device `StartNotify` semantics are now settled by 14.3.

**Production bonding.** Before any BLE bonding, revisit reconnect handling. A bonded device keeps
its CCC state across reconnects. BlueZ sends no `StopNotify` for it, and the phone's same-value
rewrite on reconnect makes no `StartNotify`. So neither the `StopNotify` purge nor the
`start_notify` purge would run. The characteristics carry no `encrypt-*` flags, so bonding is not
expected (INFERRED). The production security items and Android 16 bond loss in section 12 stay
filed. Source: PR #4 "Filed"; section 12.

**Persisted synthetic marker gap.** A saved vitals snapshot records `ObservationSource.DEVICE` with
no synthetic marker. This must be fixed before any emulator reading may reach a non-dev backend.
Unchanged from sections 8 and 12.

**Deferred NITs, as listed in PR #4.**

- Second pass:
  - NIT 4: five prose-matching tests.
  - NIT 5: `AF_INET6` in the Wi-Fi unit.
  - NIT 6, remainder: "kernel-enforced" for `SocketBindAllow` and `IPAddressAllow` stays INFERRED,
    with no live check.
  - NIT 9: the nonce check precedes the CCCD check (a note for PR-B, 14.5).
  - NIT 13: encoder values 2046 and 2047 raise, intentionally.
  - Dockerfile: a future `-I` entrypoint needs `-B`.
- Fourth pass:
  - The model omits the intruder's own confirms, and assumes `out = 0` on the owner's disable.
  - The walk floors are loose: each would miss a coverage collapse of up to about 90%.
  - The NIT 4 timeout applies per read, not per request, so a trickled body is unbounded. The hub
    path is LAN-only behind `IPAddressAllow`.
  - Final-check NITs 1, 5 and 10 are unchanged.

**Rollback copy.** `samdpi.prev` on both hubs now holds the `94c997d` tree;
`samdpi.keep23eec69` is deleted after the post-merge deploy (2026-10-08) has run cleanly for a
day. Source: PR #4; post-merge deploy (14.3).

Section 12 items not named here are unchanged.

### 14.5 Requirements PR-B must meet

1. Enable Control Point and Envelope indications before any Control Point write, after every
   connect and reconnect. Add a test asserting that order. Source: PR #4 "For PR-B"; 14.4.
2. Search every 0x180A instance and require exactly one 2A25 across them; otherwise
   `HUB_MISMATCH`. Source: PR #3 "Notes for PR-B", STOP 3a ruling 4.
3. Check `hub_id` on both transports:
   - BLE: DIS Serial and the envelope's `hub_id`;
   - Wi-Fi: the response's `hub_id` (body field and `X-SaMDPi-Hub-Id` header) on every response,
     error responses included.
   Source: PR #3 A2; section 7.3.
4. `emulator_build` is an object `{sha, dirty, merged}` on `/health` and `/api/v1/measurement`, not
   a string. Source: PR #4 "For PR-B".
5. Copy `tests/fixtures/ble_golden_vectors.json` byte-identically from SaMDPi `master` (`bba2c6b`).
   Its note now reads "Hand-verified against GSS 2026-09-09." Source: PR #3 and PR #4 "For PR-B".
6. A reconnected link re-enables the CCCDs (Control Point and Envelope) and requests MTU 517 again,
   explicitly, before START. Source: PR #4 "For PR-B"; PR #3 "Notes for PR-B".
7. `CCCD_NOT_ENABLED` (0x08) is a reachable result and must be handled. A reused nonce on a link
   whose CCCD is off answers `INVALID_PARAMETER`, because the nonce check runs first. Source: PR #4
   "For PR-B"; PR #4 deferred NIT 9.
8. Per-instrument routing with no transport fallback. Source: sections 2 D2 and 7.1, test G-B1.
9. CompanionDeviceManager association. The filter keys on the vendor service UUID, and identity is
   DIS Serial plus the envelope's `hub_id`. Source: sections 2 D7 and 7.3; PR #3 "Notes for PR-B".
10. Bluetooth permissions go in `app/src/dev/AndroidManifest.xml` only. Guard G-B11 declares
    `src/main/AndroidManifest.xml` as a task input. Source: sections 2 D7 and 7.2.
11. The two new `RejectReason` values, `BLUETOOTH_UNAVAILABLE` and `HUB_MISMATCH`, with their
    worker text in `app/src/main/res/values/strings.xml`. Source: section 7.7; 14.2 gate-review
    Q9. Today every existing rejection
    text is a Kotlin literal in `rejectionMessage` (`CompounderViewModel.kt:200`, `59c0181`).
    The placement is consistent with the standing rule that new or changed user-facing strings go
    in strings.xml; externalizing the existing Kotlin literals is a separate filed item.
12. The visible Emulated labels: the card label and the per-field "Emulated" text, proven by
    G-B12. Wording: card banner "Emulated instrument. Synthetic values, not a real
    measurement."; field supporting text "Emulated". Source: section 8; 14.2 gate-review Q14.
13. The laptop-docker container swap when PR-B merges. Until then the legacy laptop container stays
    on 8090, because the new `laptop-docker` profile serves SPO2 only. Source: PR #3 "Notes for
    PR-B".
14. Route per `PI_HUB_ASSIGNMENT` exactly as in Q3, with a `hub_id` per instrument:
    `"SPO2=wifi:kernelhub1,BP=ble:kernelhub2"`; laptop desk work sets `"SPO2=wifi:laptop-docker"`
    in local.properties. Source: 14.2 gate-review Q3.
15. Map the BLE nonce to the DTO session id as a lowercase canonical UUID4 string, so RC-1 sees one
    session-id format on both transports. Source: 14.2 gate-review Q5.
16. Pin `no.nordicsemi.android:ble-ktx:2.11.0` as `devImplementation` only, and prove that the
    staging and prod dependency graphs contain no `no.nordicsemi` entry (section 11, PR-B live
    check 9). Source: 14.2 gate-review Q11; section 11.

### 14.6 Hazard rows for PR-C (PROPOSED)

These rows are for sign-off with Prof. Banda. Nothing here is written into `docs/quality/`. PR-C
transcribes them after signature.

Numbering follows the risk file's H-nn convention and starts at H-37, because the register holds
H-01 to H-29 and H-32 to H-36, and H-30 and H-31 are reserved. These IDs replace the provisional
H-PI-nn labels of `docs/design/pi-relay-reconciliation-memo.md` (H-PI-01 to H-PI-04) and of
section 10 (H-PI-05 to H-PI-09), keeping their order. Section 10 says PR-C transcribes both sets
together.

| Candidate | Was | Hazard | Primary controls |
|---|---|---|---|
| H-37 | H-PI-01 | A reading from a foreign, previous or concurrent session, or from an instrument other than the one selected, is autofilled into this encounter | RC-1 correlation |
| H-38 | H-PI-02 | A fault or lead-off reading is recorded as a clinical measurement | RC-2 quality gate; NaN fault encoding; special-value rejection |
| H-39 | H-PI-03 | The worker cannot correct an instrument reading they can see is wrong | RC-3 editability |
| H-40 | H-PI-04 | Instrument data reaches the record without human review | RC-4 sole save gate |
| H-41 | H-PI-05 | Wrong peripheral, wrong patient | CDM association; `hub_id` checks; envelope-only admission; instrument code check; single-central rule (A13). Residual: the intruder residual (14.4); fail-safe, the phone gets TIMEOUT |
| H-42 | H-PI-06 | A stale or replayed indication is taken as this reading | Fresh nonce per Start with hub-side freshness check; envelopes before SUCCESS discarded; one envelope per session |
| H-43 | H-PI-07 | An emulated value enters a real record | Dev-only source set; emulated flag mandatory on both wires; visible labels; `synthetic` in audit. Residual: no persisted synthetic marker (14.4) |
| H-44 | H-PI-08 | Silent disconnect | Per-acquisition connection with a 25 s cap; disconnect ends the call with UNREACHABLE; no auto-reconnect |
| H-45 | H-PI-09 | Encoding or decoding error | Shared golden vectors byte-identical in pytest and JUnit; NaN faults; special-value rejection |

PR-C adds an H-13 amendment as a new row; signed rows are never edited (the same pattern as the
H-05 and H-09 amendment rows). H-32 to H-36 are signed and are not touched. PR-C also updates the
interface specification, including the nonce origin, as section 11 lists.

---

## Sources

Web sources fetched during Phase 1, 2026-10-07:

- Bluetooth SIG assigned numbers:
  https://bitbucket.org/bluetooth-SIG/public/raw/main/assigned_numbers/uuids/service_uuids.yaml
  and `characteristic_uuids.yaml`
- Bluetooth SIG spec pages:
  https://www.bluetooth.com/specifications/specs/blood-pressure-service-1-1-1/,
  `pulse-oximeter-service-1-0-1`, `health-thermometer-service-1-0`,
  `device-information-service-1-1`, plus the linked ICS PDFs on files.bluetooth.com
- GATT Specification Supplement (2026-09-09):
  https://btprodspecificationrefs.blob.core.windows.net/gatt-specification-supplement/GATT_Specification_Supplement.pdf
- Maven Central metadata: `no/nordicsemi/android/ble-ktx`, `no/nordicsemi/android/ble`,
  `no/nordicsemi/kotlin/ble/client-android`, `no/nordicsemi/android/kotlin/ble/client-android`
- PyPI: `bless`, `bluez-peripheral`, `dbus-next` (JSON), `dbus-fast` (RSS); GitHub API repo
  metadata for `spacecheese/bluez_peripheral`, `kevincar/bless`, `Bluetooth-Devices/dbus-fast`
- Android Bluetooth permissions: https://developer.android.com/develop/connectivity/bluetooth/bt-permissions
- Android 16 behaviour changes (bond loss): https://developer.android.com/about/versions/16/behavior-changes-all
