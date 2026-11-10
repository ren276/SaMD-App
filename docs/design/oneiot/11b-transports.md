Status: RESEARCH, not a design ruling. Sources read: memos `05`, `09`, `10`, `12`; SaMDPi at `bba2c6b` including `DEPLOY.md`, `docs/CONNECTIVITY_TROUBLESHOOTING_AND_FIXES.md`; SaMD-App at `4b9ee3c`; `docs/design/samdpi-two-hub-memo.md` sections 5, 14; public docs linked below. **Nothing here was tested on hardware.** Superseded input: T-3's "Bookworm" is Debian 13 trixie, Python 3.13.5, BlueZ 5.82 (R2-5, `07` section 11). T-3's "custom OneIoT service" is superseded by R3-1b: existing vendor service.

Tag key: a claim with a file:line citation and no other tag is VERIFIED, read in code at the commit named above. A citation to another memo inherits that memo's tag. Anything else is marked INFERRED, DOCS-ONLY or EXTERNAL.

# 11b: Transports (T-0, T-3)

Goal (T-0): one OneIoT message model and one manifest, over every bearer.

`E/` is SaMDPi `emulator/`. `D/` is SaMD-App `app/src/dev/java/com/example/samdapp/data/vitalssource/`.

| Bearer | Role | Recommendation |
|---|---|---|
| BLE | Data | Reuse the SaMDPi stack |
| Wi-Fi | Data | Existing HTTP, plus NSD |
| USB | Data | IP over Pixel tethering, **if the Pixel test passes**. AOA as fallback |
| NFC | Tap to pair only, **never data** | Passive sticker first |

## 1. BLE

### 1.1 Stack options

| Option | Status | For | Against | Verdict |
|---|---|---|---|---|
| (a) Reuse SaMDPi stack: `dbus-fast` 5.2.0 on BlueZ D-Bus | On kernelhub2 today, hashed venv | Measured on hardware; pure state machine with tests; indications in order; hardened unit | Hand-written GATT classes | **Recommended** |
| (b) `bluez-peripheral` | Dev server only, not shipped (`DEPLOY.md:31-33`) | Less code | Two bugs on record: adapter discovery and premature unexport (`docs/CONNECTIVITY_TROUBLESHOOTING_AND_FIXES.md` issues 2, 6). Latest stable seen is 0.1.7 from 2023; 0.2.0 alphas are marked unstable (EXTERNAL, third-party mirror: [package page](https://data.safetycli.com/packages/pypi/bluez-peripheral/)) | Reject |
| (c) `bless` | Not used | OS-independent (EXTERNAL: [bless](https://github.com/kevincar/bless)) | Portability has no value on fixed Pis; new SOUP-like dependency; no hardware evidence here | Reject |

### 1.2 GATT layout

Standard services stay; one vendor service stays. New rows in bold.

| Service | Characteristic | Properties | Purpose | Status |
|---|---|---|---|---|
| `0x180A` Device Information | `2A29`, `2A24`, `2A25`, `2A28` | read | Manufacturer, model, serial = hub_id, build | Exists (`E/transports/ble/ble_hub.py:332-337`) |
| `0x1810` Blood Pressure | `0x2A35` Measurement | indicate | SIG bytes | Exists |
| | `0x2A49` Feature | read | | Exists |
| `0x1822` Pulse Oximeter | `0x2A5E` Spot-check | indicate | SIG bytes | Exists |
| | `0x2A60` Features | read | | Exists |
| `0x1809` Health Thermometer | `0x2A1C` Temperature | indicate | SIG bytes | Exists |
| `f4610001-...` Vendor | `f4610002-...` Control Point | write, indicate | START, STOP, **PROBE, START v2** | Exists, extended |
| | `f4610003-...` Reading Envelope | indicate | Admissible reading | Exists, unchanged |
| | **`f4610004-...` Manifest** | **read** | Current page | **New** |
| | **`f4610005-...` Status** | **indicate** | Lifecycle state | **New** |

Superseded by (2026-11-10, CodeRabbit on PR #85): `f4610005` Status must be `read` and `indicate`, because section 8.3 maps GET `Status` to a read. ren276/OneIoT `spec/oneiot-protocol-v1.md` section 7.1 lists both.

UUIDs verified against the SIG lists (`09` Appendix B). `f4610004` and `f4610005` are DRAFT.

### 1.3 Measurement formats (EXTERNAL via the memo, not re-derived)

Source: Bluetooth SIG GATT Specification Supplement 2026-09-09, and the BLS, PLXS, HTS ICS, per `docs/design/samdpi-two-hub-memo.md` sections 5.1, 5.4. Encoders cross-checked by reading `E/transports/ble/encoders.py:82-173`. Golden vectors: `tests/fixtures/ble_golden_vectors.json`.

| Characteristic | Layout, little endian | SaMDPi sends |
|---|---|---|
| `0x2A35` | flags (bit0 units, bit1 timestamp, bit2 pulse, bit3 user, bit4 status); systolic, diastolic, MAP as SFLOAT; 7-byte time; pulse SFLOAT | mmHg, timestamp, pulse |
| `0x2A5E` | flags (bit0 timestamp, bit1 measurement status, bit2 device and sensor status, bit3 PAI, bit4 clock not set); SpO2, PR as SFLOAT; time | Timestamp only |
| `0x2A1C` | flags (bit0 unit, bit1 timestamp, bit2 type); FLOAT value; time; type | Celsius, timestamp, type 2 |
| Faults | SFLOAT NaN `ff 07`, FLOAT NaN | `encoders.py:102-105,140,168` |

**Generic Health Sensor `0x1840`** exists, with `0x2BF3` Health Sensor Features and `0x2BF4` GHS Control Point (`09` Appendix B). Not adopted: none of today's code or golden vectors use it, and the phone's decoders are written for the three services. Its formats were not verified here. Note it for real-device work.

### 1.4 Probe over BLE

Manifest is 1,336 to 1,664 bytes (`12` section 10). Maximum value is 512 bytes. Sequence:

1. Connect; MTU 517; enable CP and Envelope CCCDs before any write (`docs/design/samdpi-two-hub-memo.md:1764-1765`).
2. Read DIS, check `hub_id`.
3. Write CP `0x03` with page 0. Hub answers on CP.
4. Read `f4610004`: header (page, total pages, total length, sha256), then data. Long read uses `offset`, handled at `ble_hub.py:175-179`.
5. Repeat per page. Check sha256 over the whole.
6. Compare to allowlist or NFC pin. **Only then START.**

Hub minimum MTU stays 89 for v1. PROBE under 89 still works, as long reads handle it, only slower. At default MTU 23 one page takes many round trips (INFERRED). Long reads are untested on these hubs (`:1724`): first item in the test plan.

### 1.5 Android client

| Item | Plan | Evidence |
|---|---|---|
| Library | Nordic `ble-ktx` 2.11.0 | `gradle/libs.versions.toml:28` in SaMD |
| Discovery, bonding | CDM with vendor UUID and name `^SaMD-<hub>$`. No bonding | `D/ble/BlePrepActivity.kt:113-117` |
| Identity | DIS Serial and envelope `hub_id` | `D/ble/BleAcquisition.kt:144-149,183` |
| Queue | Pattern from `HubInitPlan`, `BleAcquisition` | `D/ble/HubInitPlan.kt:26-29` |
| Min API | SaMD's source requires 33 for CDM calls | `D/ble/BleHubVitalsSource.kt:107-108` |
| Code sharing | Follow the pattern; do not import from SaMD's `dev` source set | `08` section 8 |

### 1.6 Coexistence on one hub adapter

| Concern | Finding | Evidence |
|---|---|---|
| New characteristics vs SaMD client | Additive: it checks named services, not an exact list (INFERRED) | `D/ble/BleAcquisition.kt:133-144`; test |
| New opcodes vs v1 | Unknown opcode already answers `OPCODE_NOT_SUPPORTED` | `control_point.py:224` |
| Advertising | One advertisement; 31-byte budget unchanged, no new service UUID in it | `ble_hub.py:355` |
| Adapter share | One process under A1 | `10` section 4 |
| Single central | `10` section 1.6 | |

### 1.7 Failure modes

| Failure | Behaviour |
|---|---|
| Long read not honoured | Sha256 fails, refuse. Fall back to indicating pages |
| Link drop mid-probe | Restart probe at page 0 after a new connect |
| Manifest changes between pages | Sha256 fails |
| Hub held by another central | Disconnected or BUSY. Tell the worker |
| Stale GATT handles after hub restart | Known F3 (`PROGRESS.md:5570`) |
| Second subscriber on same phone | Indications reach both apps; rule 1 in `10` section 1.6 |

## 2. Wi-Fi

### 2.1 Options

| Option | For | Against | Verdict |
|---|---|---|---|
| **Existing HTTP** | Framing solved by `Content-Length`, already strict (`E/transports/wifi/server.py:313-322`); hub, client and tests exist | Polling for status | **Recommended** |
| Length-prefixed JSON over TCP | Small | Reinvents HTTP; new parser; a new unit port | Reject. It was T-3's alternative to OneIoT's ad hoc framing, which goes away |
| WebSocket | Push | No need (`11` section 2); hub is stdlib, and a new hub dependency | Reject |

### 2.2 Discovery

| Option | Evidence | Verdict |
|---|---|---|
| NSD on `_samd-gw._tcp` | `deploy/avahi/kernelhub1.service:21-26`, `D/NsdGatewayDns.kt:83` | **Use**; cloud not needed |
| TXT records | `hub_id`, `instrument`, `profile_version`, `envelope_version` already there. Add `manifest_id` and `sha256` | Add |
| System picker `FLAG_SHOW_PICKER` | System dialog picks one device; connecting to that address needs no `ACCESS_LOCAL_NETWORK` (EXTERNAL: [local network permission](https://developer.android.com/privacy-and-security/local-network-permission)). SaMD's memo set it aside for its own case (`docs/design/local-network-permission-memo.md:428-432`). OneIoT has no login or sync, so its case differs (INFERRED) | Compare at Stage 1 |
| Manual address | For an AP that blocks mDNS (`app/build.gradle.kts:154-155`) | Keep in dev |

### 2.3 Failure modes

| Failure | Behaviour | Evidence |
|---|---|---|
| Permission denied | Gate before socket | `A/domain/connectivity/LocalNetworkGate.kt:20` pattern |
| mDNS blocked | Manual address | above |
| Avahi answers, hub down | UNREACHABLE | `DEPLOY.md:130-131` |
| `wlan0` recreated | Listener on dead index; restart unit | `DEPLOY.md:147-149` |
| Wrong box answers | Hub id header check | `D/HubIdInterceptor.kt:25-33` |
| Second client | Sessions overwrite each other (`server.py:65-77`), a single-central gap on Wi-Fi | Treat as OQ |

## 3. Wired (USB)

### 3.1 Primary hypothesis: IP over USB tethering

Pixel in USB tethering mode, Pi 5 as USB host, the phone not powering the Pi, reusing the Wi-Fi code path.

| Question | Finding | Tag |
|---|---|---|
| Has it been done with these parts? | SaMDPi doc claims a cable test with tethering on, `USB_WIRED`, on an iQOO, in the POC server era (`docs/CONNECTIVITY_TROUBLESHOOTING_AND_FIXES.md:263`). Sample app uses the same HTTP client | DOCS-ONLY for the result |
| **Can phone apps reach the tethered subnet?** | **Unknown. To be tested on the Pixel.** Search found no authoritative answer | Open |
| Does `ACCESS_LOCAL_NETWORK` cover it? | The permission page gates traffic to local network addresses, and names neither tethering nor an address list. Probably needed. Test (EXTERNAL: [local network permission](https://developer.android.com/privacy-and-security/local-network-permission)) | INFERRED |
| How does the phone learn the Pi's address? | **Top risk.** Phone runs DHCP; addresses reported to change on reconnect (EXTERNAL, weak: [forum report](https://community.e.foundation/t/usb-tethering-assigning-static-ip-addresses/45446)). An app has no API for tether leases (INFERRED). NSD over tether unverified | Open |
| User steps | Tethering is a Settings toggle, not app-controlled (INFERRED) | Demo friction |

### 3.2 Hub changes the hypothesis forces

| Issue | Evidence | Needs |
|---|---|---|
| Listener dies on replug: `SO_BINDTODEVICE` binds an interface index. `usb0` is destroyed and recreated with each cable pull | `E/hub.py:47-50`, `DEPLOY.md:147-149` | Restart on replug, or a re-bind. Silent failure otherwise |
| Interface absent at boot | `samdpi-hub-wifi.service:7-8`: start only fails if the interface does not exist | Unit must wait for `usb0`, or use a udev trigger |
| `IPAddressAllow=10.203.0.0/20` refuses the tether subnet | `samdpi-hub-wifi.service:34-35` | Own unit; allow the ranges actually seen |
| Transport truth: hub would say `WIFI` | `E/transports/wifi/server.py:207`, `E/models.py:22-24` | New value |
| Profile allows only `ble`, `wifi` | `E/profile.py:87-90` | `profile_version` 2, or a new section |
| Avahi limited to `wlan0` on kernelhub1 | `DEPLOY.md:124` | `allow-interfaces=usb0` on kernelhub3 |
| ARP and `rp_filter` settings tuned for dual-homed Wi-Fi | `deploy/sysctl/90-samdpi-wifi-hub.conf` | Recheck |

### 3.3 Fallback: Android Open Accessory

| Item | Finding | Tag |
|---|---|---|
| Model | Accessory drives detection, control requests 51, 52, 53, then bulk endpoints. PIDs `0x2D00`, `0x2D01` under VID `0x18D1` | EXTERNAL: [AOA](https://source.android.com/docs/core/interaction/accessories/aoa) |
| Pi side | `pyusb` as host; new hub dependency | INFERRED |
| Android side | `UsbManager`, `UsbAccessory`, accessory filter, user permission | INFERRED; verify |
| Frames | Length-prefixed JSON: same envelope, its own framing | |
| Discovery problem | **None**: no IP, so no address and no local-network permission | INFERRED |
| Power | The AOA page does not say. Host supplying VBUS is assumed; verify with the Pi 5 | Open |
| Cost | 5 or more days; new hub code path; hub unit needs USB device access while `PrivateDevices=yes` today | `samdpi-hub-wifi.service:37` |

### 3.4 Recommendation

Test the tether hypothesis first, as 2 to 3 hours on the Pixel with any Pi. If apps can reach the subnet and the address can be learned, use it. If not, decide between AOA and dropping wired from the demo. Do not build AOA speculatively.

### 3.5 Failure modes

| Failure | Behaviour |
|---|---|
| Cable pulled | Socket error; UNREACHABLE. Hub must recover on replug |
| Tethering off | No route. Prompt the user with the exact step |
| Subnet changes on reconnect | Rediscover; never cache an address across cable events |
| Phone charging draws Pi power | Test |

## 4. NFC: tap to pair only

Not data transport. Payload: hub id, BLE address, NSD name, manifest id, sha256.

### 4.1 NDEF record (DRAFT)

External type, for example `oneiot:pair`, with compact JSON:

```text
{"v":1,"hub":"kernelhub2","ble":"<address>","nsd":null,"mid":"samdpi-bp","ver":1,"sha256":"<64 hex>"}
```

About 130 bytes (INFERRED): small tags fit.

### 4.2 Hub-side options

| Option | How | For | Against | Verdict |
|---|---|---|---|---|
| Passive NTAG sticker | Write once at setup | No Pi change; no power; cheapest | Stale after a manifest change; rewritten under change control; can be swapped by hand | **First** |
| Dynamic I2C tag written by the Pi | Tag IC on the Pi's I2C bus | Always current; phone reads it like a sticker | Hardware, driver, hub I2C access under `PrivateDevices=yes` | Later |
| PN532 on Pi, with phone HCE | Pi polls, phone emulates | Pi learns phone tap | Inverted roles, for no need here; new hub dependency | Reject for this purpose |

nfcpy note: its docs recommend a PN532 board over a serial port on a Pi, and say the **ACR122U is not a good choice** (EXTERNAL: [nfcpy overview](https://nfcpy.readthedocs.io/en/latest/overview.html)). Serial link speed is limited, and PN532 tag emulation coverage is partial. Tag hardware choice is an OQ.

HCE facts, if ever used: ISO-DEP over Nfc-A, `HostApduService`, random UID per tap, about 1 KB within 300 ms as a sensible bound (EXTERNAL: [HCE](https://developer.android.com/develop/connectivity/nfc/hce)).

### 4.3 Android side

`NfcAdapter.enableReaderMode` with `FLAG_READER_NFC_A`, while a screen is foreground (EXTERNAL: [NfcAdapter](https://developer.android.com/reference/android/nfc/NfcAdapter)). `android.permission.NFC` is expected; the page section read did not state it for this call, so confirm. `uses-feature` optional.

### 4.4 How the pin works

1. Tap gives hub id, address, manifest id and sha256.
2. Phone connects by that address, or NSD name.
3. PROBE, and hash the received bytes.
4. Hash must equal the tap. Mismatch: refuse, no screen.
5. Identity checks still run: DIS Serial, `hub_id`.

Without a tag: bundled allowlist (`12` section 7). A tap proves physical proximity to a tag, not that the tag is honest.

### 4.5 Failure modes

| Failure | Behaviour |
|---|---|
| No NFC on device | Fall back to CDM picker or NSD |
| Stale sticker | Hash mismatch; tell the operator, refuse |
| Swapped sticker | Pointing to another approved hub is caught by `hub_id` only if the worker intended a hub; residual, `13` |
| Reader mode blocks payment apps while on | Only while the screen is open |

## 5. Per bearer: Android 17 permissions, hub packages, units

Debian 13 trixie, Python 3.13.5, BlueZ 5.82.

| Bearer | Android 17 permissions at target 37 | Hub packages | systemd |
|---|---|---|---|
| BLE | `BLUETOOTH_CONNECT` runtime (Nearby devices); CDM needs no scan permission | `bluez`; `dbus-fast==5.2.0` hashed venv | `samdpi-hub-ble.service`, unchanged |
| Wi-Fi | `ACCESS_LOCAL_NETWORK` runtime, in the `NEARBY_DEVICES` group, so a user who granted Bluetooth is not prompted again; or a picker (EXTERNAL: [local network permission](https://developer.android.com/privacy-and-security/local-network-permission)) | System Python only; `avahi-daemon` | `samdpi-hub-wifi.service`, unchanged |
| USB tether | Probably `ACCESS_LOCAL_NETWORK`; verify | As Wi-Fi | New `samdpi-hub-usb.service` with `usb0`, `After`, re-bind |
| USB AOA | User accessory permission; no LAN | `pyusb`, `libusb` | New unit with device access |
| NFC sticker | `NFC` (normal) | None | None |
| NFC dynamic tag | `NFC` | I2C tools or library | Unit with I2C device access |

## 6. Test plan

No hardware test has been done for anything new. Each row is a future gated test, Pixel `67041FDKX004ZY`, instrumented tests by `adb install -r` and `am instrument`, per project rules. Ask "phone free?" first.

| # | Bearer | Test | Pass means |
|---|---|---|---|
| 1 | Pixel | `getconf PAGE_SIZE` | Known; OQ closed |
| 2 | BLE | Long read of a 512-byte value at MTU 517 and at 23 | Paging design holds |
| 3 | BLE | Probe all pages, corrupt one byte in a mock | Sha256 refuses |
| 4 | BLE | SaMD's existing client against hub v2 | No regression, both opcodes |
| 5 | BLE | Golden vectors byte-identical, pytest and JVM | Encoders unchanged |
| 6 | BLE | OneIoT holds hub, SaMD starts; then the reverse; same phone and two phones | Section 1.6 rules hold |
| 7 | BLE | Disconnect mid-capture; hub reboot; bluetoothd restart | No stale value |
| 8 | Wi-Fi | v1 client against hub v2, with no `protocol_version` | Reading at once |
| 9 | Wi-Fi | v2 start: 404 until `DONE`, status progresses, stop cancels | Lifecycle true |
| 10 | Wi-Fi | Deny, then grant `ACCESS_LOCAL_NETWORK` | Gate works |
| 11 | Wi-Fi | NSD with AP blocking mDNS | Manual path works |
| 12 | USB | **App opens socket to tethered Pi** | Hypothesis lives |
| 13 | USB | App learns Pi address without manual entry | Discovery lives |
| 14 | USB | Pull and replug 5 times | Listener recovers |
| 15 | USB | `/health` says USB, not WIFI | Provenance true |
| 16 | NFC | Tap; stale tag; swapped tag; no tag | Pin rules hold |
| 17 | All | Mutation proof per project rule: break each check, see red, restore, see green | Checks are real |

## 7. Docs vs code

| Docs or brief say | Code or measurement | Result |
|---|---|---|
| Live cable test with `USB_WIRED` passed (`docs/CONNECTIVITY_TROUBLESHOOTING_AND_FIXES.md:263`) | Hub transport enum has BLE and WIFI only (`E/models.py:22-24`), and profiles allow only `ble` and `wifi` (`E/profile.py:87-90`). `USB_WIRED` exists in the sample app (`app/src/main/java/com/example/samdpi/model/NormalizedMeasurement.kt:17`) | Today's hub has no USB transport. The claim was not re-run |
| T-3: hubs on Bookworm | Measured Debian 13 trixie (`07` section 11) | Measurement wins (R2-5) |
| T-3: custom OneIoT service | R3-1b: existing vendor service | Later ruling wins |

## 8. Amendments at STOP 3 (R4-2, R4-K, R4-F; operator 2026-11-08; additions only)

### 8.1 Size limit, now from the primary source

"The maximum length of an attribute value shall be 512 octets." (EXTERNAL: Bluetooth Core 5.4, Vol 3 Part F, section 3.2.9 "Long attribute values", [ATT](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Core-54/out/en/host/attribute-protocol--att-.html)). This replaces the secondary source in `12` section 3.5.

### 8.2 Paging, mandatory (R4-2)

| Step | Bytes | Notes |
|---|---|---|
| CP write `0x03` PAGE_REQUEST | `03`, u16 page index (little endian) | New opcode; `0x01` START and `0x02` STOP unchanged (`E/transports/ble/control_point.py:44-45`) |
| CP response | `80 03 <result>`, then u16 total pages, u32 total length, u8 encryption type (`00` = none), 32-byte sha256 of the whole file | Existing response form `80 <opcode> <result>` (`:73`) extended for `0x03` only. Client can hit its sha256-keyed cache and skip the reads |
| Read `f4610004` | u16 page index, u16 total pages, u32 total length, then at most 504 data bytes | 8 + 504 = 512, the ATT maximum |
| Client | Reassemble, check length, compute sha256 over the bytes, compare with the announced value and with the bundled allowlist, cache by sha256 | Identity is the client's own hash (R4-K) |
| MTU | Client requires ATT_MTU of at least 513 so a 512-byte value fits one Read Response; below that the hub answers `MTU_TOO_SMALL` (`:54`, existing code, today used for START below 89 at `:56,283-284`) | No long read, so the long-read test (OQ-35) stops being a gate. INFERRED: Android 14 and later request 517 (`12` section 3.5) |

Superseded by 8.2: the 40-byte page header and 472 data bytes in `12` sections 3.5 and 10. Measured page counts with the R4-K drafts: BP 791 bytes, SpO2 672 bytes, 2 pages each (`12` section 11.3).

### 8.3 Invoke mapping on BLE

| Logical message (`12` section 11.6) | BLE form |
|---|---|
| Header: protocol and version | Vendor service `f4610001-...` (`E/transports/ble/ble_hub.py:71`), envelope version byte |
| Header: encryption type | Byte in the PAGE_REQUEST response, once per connection (envelope layout unchanged so SaMD's v1 parser is untouched) |
| SET `Measure` start | New `0x04`: same 19 bytes as START (opcode, instrument code, scenario code, 16-byte nonce; `:57`) plus one flags byte, bit 0 `simulate_latency`. The nonce is SaMD's request id |
| SET `Measure` stop | Existing `0x02` STOP |
| GET `Status` | Read `f4610005`, u8: 0 idle, 1 measuring, 2 done, 3 error, 4 stopped; indicated on change |
| GET reading | SIG Blood Pressure Measurement `0x2A35` indication plus envelope `f4610003` (nonce, sequence, device time). The phone writes Outputs with the MMF names. The SIG unit flag must match the MMF `UNIT_VAL` `mm[Hg]` or the reading is refused |
| Errors | Existing result codes: `BUSY` 0x05, `INVALID_PARAMETER` 0x03 and others (`:48-55`) |

Scenario code: the emulator needs one at start. OneIoT sends the default scenario unless a dev argument says otherwise (OQ-61). These are protocol-spec mappings, not manifest content (R4-K: `SOCKET` is the only binding keyword).
