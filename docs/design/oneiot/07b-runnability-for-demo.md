Status: RESEARCH, not a design ruling. Sources read: all Kotlin under `K/`, Pi and cloud Python (connection values masked), `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/network_security_config.xml`, host tool versions, `/media/sandesh/oneTB_SSD/SaMDPi/deploy/setup_pi.sh:4` and `docs/OPENIOT_HEALTHSARK_CONNECTIVITY_POC_ARCHITECTURE.md:5,122` (OS lines only), Android 17 behavior pages, Chaquopy change log. Not opened: `privateKey.txt`, `app/google-services.json` (present, unread, operator states rotated). Nothing was built, installed or run: every "works" below is INFERRED until a build and a Pixel run happen.

# 07b: Runnability for the demo (scope change S1, S3)

Paths are relative to `/media/sandesh/extra-ssd/AndroidWork/OneIOTApp`. `K/` means `app/src/main/java/com/example/iot1/`.

## 1. Verdict

There are two different targets, and today's code reaches neither unmodified.

| Level | Goal | Today's code | What it takes |
|---|---|---|---|
| A | The lab's AC demo, on a Pixel, over Wi-Fi | Does not run from this folder | Build-file fixes, a cloud or a bypass, Pi file setup, and at least one Pi script edit (section 4) |
| B | S1 use case: hubs expose BP, SpO2, Thermometer through manifests, the app draws them, readings reach SaMD-App | **Not possible** | New capability on both sides: no reading path, sensor UI, per-device manifest or result channel exists (section 7) |

So "OneIoT must run end to end for our use case" is new development on OneIoT, even for the demo bridge. Modernization can be deferred; these capabilities cannot.

## 2. App: build and install on Android 17

| Item | Finding | Tag |
|---|---|---|
| Blockers to build | `.txt` suffixes, `gradlew` mode, `google-services.json` excluded by rule 1 while the plugin needs it (`app/build.gradle.kts:7`) | VERIFIED names, INFERRED effect |
| Firebase actually used | No: 0 Firebase references outside the catalog (`gradle/libs.versions.toml:18,34`). The plugin can be removed in a copy | VERIFIED |
| Host tools | JDK 21.0.7, SDK platform `android-34` and build-tools `34.0.0` present, `/usr/bin/python3.10` present, no 3.11, `/media/sandesh/extra-ssd/.gradle` exists | VERIFIED |
| Flavor to build | `py310` matches host Python. Chaquopy's `buildPython` must be at least 3.8 (EXTERNAL: [Chaquopy change log](https://chaquo.com/chaquopy/doc/current/changelog.html)) | INFERRED |
| Build risks | Kotlin 1.9.0 under JDK 21; internal Gradle import at `app/build.gradle.kts:1-2`; unpinned `cryptography` wheel | INFERRED |
| Install on Android 17 | targetSdk 34, arm64 ABI included (`app/build.gradle.kts:19,25`). The all-apps page lists no minimum target that blocks it (EXTERNAL: [Android 17 all apps](https://developer.android.com/about/versions/17/behavior-changes-all)) | INFERRED |
| **16 KB pages** | Chaquopy 16.0.0 predates support: 16.1.0 first mentions 16 KB stubs, and 17.0.0 first says such devices are supported (same change log). If the Pixel runs 16 KB pages, the pinned version may not load. Pixel page size unverified: no adb in this study | EXTERNAL, open question |
| Prebuilt APK | Older than source (`02` section 6), also Chaquopy-era unknown. Not a substitute for a build | VERIFIED |

### targetSdk choice (for OQ list)

| Target | LAN from the app | Other effects |
|---|---|---|
| 34 (as is) | Implicit through `INTERNET`, no new permission. That grant is temporary per Android docs (EXTERNAL: [local network permission](https://developer.android.com/privacy-and-security/local-network-permission)) | Least change. Fine for a dev demo |
| 37 | Needs `ACCESS_LOCAL_NETWORK`, or a system picker; all IP paths fail without it | Edge-to-edge fixes, RFCOMM read semantics, stricter native loading, see `07` section 4 |

### Cleartext

`usesCleartextTraffic="true"` sits next to a network security config that allows only `10.203.6.97` (`app/src/main/AndroidManifest.xml:26,31`, `app/src/main/res/xml/network_security_config.xml:4`). Which one wins was not confirmed from docs. A moved cloud should change both (INFERRED). Raw sockets to the hub are not subject to this config (INFERRED).

## 3. Registration: what it really needs (S3a)

| Step | Needs cloud? | Needs SQL? | Needs in-app JDBC? | Evidence |
|---|---|---|---|---|
| App start with network | No, only link state | No | No | `K/CheckActivity.kt:28-32` |
| Sign up | Network link required. Credentials save locally even if the server fails, but the app only moves on after success; user must press Back | No | No | `K/RegisterActivity.kt:77-79,100-103` |
| Log in | No. Failure falls to the offline check, and `/validate_user` does not exist anyway | No | No | `K/MainActivity.kt:74-83` |
| Add Thing | **Yes.** `POST /register_thing` must return `{"registered":true}`, else nothing is saved | Behind `cloud.py`, yes | No | `K/ThingDetailsActivity.kt:128-158` |
| Find device IP | **Yes.** `GET /get_user_ip` | Behind `cloud.py`, yes | No | `K/ThingDetailsActivity.kt:169-171,202` |

**JDBC drivers: not needed.** Zero calls from app code (`02` section 6). They can go.

**Real `cloud.py` needs:** Python with `flask`, `pyodbc`, ODBC Driver 17, a SQL Server, and tables `vendor` (`thingId`, `thingKey`, `thingIp`) and `user` (`username`, `useremail`, `usermobilenum`, `thingId`, `mobileIp`, `thingName`, `thingKey`, `thingIp`). Column names are read from queries (`cloud.py:48,55,90,100,134-151`). No DDL exists in this folder (VERIFIED). Host and login are hardcoded (`cloud.py:12-18`). MySQL is not used by any code despite TIH 8.1.

### Ways past the cloud (design level, no code, operator's choice)

| Option | Change | App edits | Keeps OEM registration story | Note |
|---|---|---|---|---|
| R1 Real stack | SQL Server, `cloud.py`, `ipverify.py`, a host reachable at `10.203.6.97` or edited URL | URL if moved | Yes | Heaviest. Device holds DB credentials |
| R2 Stub cloud | A small HTTP service answers the 2 routes from a static table | URL if moved | Looks the same on screen | No database |
| R3 Dev-mode static device list | Seed `DBHelper.insertThing` (`K/DBHelper.kt:36`) and run the manifest fetch (`K/ThingDetailsActivity.kt:197-266`) against a known address, skipping `fetchUserIp` (`:202`) | Yes, small | No | No cloud. Fits "one hub, known address" |
| R4 NSD discovery | Find the hub on LAN (T-3, Phase 3) | Yes | No | Replaces cloud lookup for good, not just a demo trick |

Whether the demo must show the registration step is an OQ for `14` (S5).

## 4. Pi side, Level A

### Scripts

| Must run | Role | Why | Port |
|---|---|---|---|
| `send.py` | Serves manifest, one shot | App pulls manifest here | TCP 12345 |
| `thing_mobile.py` | Receives commands | Spawned by `send.py:90` | TCP 12346 |
| `ipverify.py` | Device-to-cloud IP reporting | Only with option R1 | ODBC to DB |
| `cloud.py` | Cloud | Only with R1, on a host | TCP 5000 |

Not needed: `blue.py`, `thing_bluetooth.py`, `thing_mobile_bluetooth.py`, `adhocServer.py`, and all laptop scripts. **But see the blocker below.**

### Blocker: an IP-only demo still calls Bluetooth

`thing_mobile.py:133` and `:141` run `thing_bluetooth.py` with `check=True`, after 15 s idle or at any session end. With PyBluez missing, that raises `CalledProcessError` and the script dies (INFERRED). So at minimum those lines need editing, or PyBluez must be present. T-0 retires RFCOMM, so edit is the natural route.

### Other Pi prerequisites

| Item | Requirement | Evidence |
|---|---|---|
| Paths | `/home/veman/Desktop` and venv `/home/veman/Desktop/py3.9testenv/bin/python3` are hardcoded. Create them or edit scripts | `send.py:41,59,72,77,90` |
| Working directory | Must be `/home/veman/Desktop`: scripts read relative names that `send.py` writes by absolute path | `thing_mobile.py:22,52,88,99`, `send.py:41,59` |
| `ThingPublicKey.txt`, `ThingPrivateKey.txt` | A generated RSA pair: OpenSSH public, unencrypted PEM private. Code only; files not in this folder | `send.py:77`, `thing_mobile.py:52`, `decryptMessage_test.py:7-11` |
| `Thing_IP.txt` | Manual. Last space-separated token is the IP | `thing_mobile.py:88-92` |
| `ac.json` | At the Desktop path | `send.py:72` |
| Privilege | `sudo -E` must not prompt | `send.py:90` |
| Restart | Manual, every session. `send.py` is one shot; nothing restarts after a crash | `send.py:24,89` |

### Phone key pair

The phone's public key is compiled in (`K/ThingDetailsActivity.kt:206`, `K/BluetoothMainScreen.kt:131`), and the private key is read from `/data/data/com.example.iot1/files/privateKey.txt` (`K/DisplayThingActivity.kt:833`), which nothing in the app writes. For a working ACK check, a matching pair must be placed by hand: on a debug build through `run-as` (INFERRED). The repo's key files stay out of this: a new pair would need those two source lines changed. Without it, every command is sent 3 times, then the transport switches (`06` section 4).

### Python and packages

| Script | Pip | System |
|---|---|---|
| `send.py` | `RPLCD` (imported, unused: `send.py:3`) | I2C libs on import only if it pulls them, unverified |
| `thing_mobile.py`, `*_test.py` | `cryptography` | none |
| `ipverify.py` | `pyodbc` | unixODBC, ODBC Driver 18 (`ipverify.py:25`), arm64 availability unverified |
| `cloud.py` | `flask`, `pyodbc` | ODBC Driver 17 (`cloud.py:13`) |
| `blue.py`, `thing_bluetooth.py` | PyBluez | `bluez`, `libbluetooth-dev`, `bluetoothd` compat mode |

### Hub OS: brief and repo disagree

S3a and T-3 say Raspberry Pi OS Lite Bookworm. The SaMDPi repo says Debian 13: `deploy/setup_pi.sh:4` and `docs/OPENIOT_HEALTHSARK_CONNECTIVITY_POC_ARCHITECTURE.md:5,122` (Trixie). `docs/CONNECTIVITY_TROUBLESHOOTING_AND_FIXES.md:66` muddles the names, "Debian 13 Bookworm / Trixie". Bookworm is Debian 12. No SSH in this study, so the running hub is unverified. **Operator check:** `cat /etc/os-release` on `kernelhub1` and `kernelhub2`.

| OS | Python | Source | Consequence |
|---|---|---|---|
| Bookworm (Debian 12) | 3.11.2 | EXTERNAL, secondary: [Bookworm notes](https://picockpit.com/raspberry-pi/whats-up-with-raspberry-pi-bookworm-os) | venv needed for pip |
| Trixie (Debian 13) | 3.13 | EXTERNAL, secondary: [Debian 13](https://www.cnx-software.com/2025/08/10/debian-13-trixie-released-with-linux-6-12-official-64-bit-risc-v-support/) | venv needed for pip; matches SaMDPi's `samdpi-py313` venv name on this host |

Measured (R2-5, operator, 2026-11-06): both hubs report Debian GNU/Linux 13 (trixie), `DEBIAN_VERSION_FULL=13.7`. The SaMDPi repo was right. The Bookworm row is dropped, and the hub target is Debian 13 trixie with Python 3.13.5 (`07` section 11). PyBluez on 3.13 is unproven, and T-0 retires it anyway.

Measured (R2-1): the build of a copy succeeded; see `07` section 10. Pixel page size is still unmeasured and stays an open item for `14`.

### PyBluez feasibility (INFERRED, nothing built)

- PyPI 0.23 fails to build on current setuptools with a `use_2to3` error, reported on Python 3.11 (EXTERNAL: [pybluez issue 484](https://github.com/pybluez/pybluez/issues/484)).
- Reported workaround: install from the git development branch (EXTERNAL: [Stack Overflow mirror](https://kiwix.lema.org/stackoverflow.com_en_all_2023-11/questions/75818465/pybluez-error-in-pybluez-setup-command-use-2to3-is-invalid)); the Debian package may be another route, unverified.
- `advertise_service` reportedly needs `bluetoothd --compat` (EXTERNAL, weak: [community guide](https://github.com/sraodev/bluetooth-service-rfcomm-python)).
- The project says it is not under active development (EXTERNAL: [pybluez](https://github.com/pybluez/pybluez)).
- Python 3.13 and Pi 5 behaviour: unknown.

Feasible on Bookworm with effort, unknown on Trixie. T-0 makes this moot for the demo, since RFCOMM is to be replaced.

## 5. Ports

| Port | Proto | Who listens | Who dials | Needed in Level A | Evidence |
|---|---|---|---|---|---|
| 12345 | TCP | `send.py` (also `adhocServer.py`) | App | Yes | `send.py:16` |
| 12346 | TCP | `thing_mobile.py` | App | Yes | `thing_mobile.py:19` |
| 5000 | HTTP | `cloud.py` | App | R1, R2 | `cloud.py:218` |
| SQL default | TDS | SQL Server | `cloud.py`, `ipverify.py` | R1 | no port in `cloud.py:26`, INFERRED 1433 |
| 8004 | TCP | `air_conditioner.py` | Old app only | No | `air_conditioner.py:48` |
| 65432 | TCP | `decrypter.py` | `encrypter.py` | No | `decrypter.py:29` |

Clashes with the SaMDPi gateway: Phase 2, S4.

## 6. Where a reading is received, displayed, and where "Send to SaMD" attaches (S3b)

**A reading is not received or displayed anywhere.** The closest points:

| Function needed | Nearest existing code | Evidence | Note |
|---|---|---|---|
| Bytes in from device, IP | ACK read in `sendToRpi` | `K/DisplayThingActivity.kt:515-521` | Only inside a send, 1024-byte buffer, no listener |
| Bytes in, Bluetooth | ACK read in `sendViaBluetooth` | `K/BluetoothDisplayThingActivity.kt:462-470` | Same |
| Parse inbound message | `handleAcknowledgment` | `K/DisplayThingActivity.kt:561-592` | Message part is logged, then discarded |
| Show a value | `txtDisplay` | `K/DisplayThingActivity.kt:349,353,363,372` | Local value only, not from the device |
| Row inflation | `addUIElements` | `K/DisplayThingActivity.kt:199-309` | Natural place for a read-only row type |
| Add a "Send to SaMD" control | After the add loop, before the populate call | `K/DisplayThingActivity.kt:306-308` | A new row, or a fixed button in `activity_display_thing.xml`, once a reading exists |
| Return to caller | Nothing. 0 uses of `setResult`, `callingActivity`, `callingPackage` | search over file list | Must be added |

### Launch by another app and return a result

| Question | Answer | Evidence |
|---|---|---|
| Explicit Intent to an activity? | Yes, 8 of 9 are exported with no permission. `Adhoc` is not | `app/src/main/AndroidManifest.xml:36-81` |
| Best entry | `DisplayThingActivity`, with extra `ip_address` | `K/DisplayThingActivity.kt:80` |
| Missing extra | Throws `IllegalArgumentException` in `onCreate`: crash | `K/DisplayThingActivity.kt:80` |
| Manifest absent | Blank screen: requires a previous Add Thing | `K/DisplayThingActivity.kt:83-87` |
| Login gate | Bypassed when entering directly. No caller check | `K/DisplayThingActivity.kt:76-102` |
| Entering through `CheckActivity` | Would lose the result: it `finish()`es after starting the next activity, with no forward-result flag | `K/CheckActivity.kt:41-45` |
| Return a result today | No. `finish()` with no `setResult` returns `RESULT_CANCELED` by platform rule | EXTERNAL knowledge, confirm in build |
| Caller checks (spoofing either way) | None on OneIoT's side. SaMD-side checks are Phase 3 (S5) | INFERRED |
| Package visibility | A SaMD app targeting 30 or above likely needs a `<queries>` entry to read OneIoT's signature (Phase 3) | INFERRED |

Today's `applicationId` is `com.example.iot1` (`app/build.gradle.kts:17`). A debug-signed copy has no stable signing identity for a certificate check: Phase 3 topic.

## 7. Level B: capabilities that must be added

No design here (Phase 2 and 3). This is the gap list. Each row is absent today.

| # | Capability | Why | Today |
|---|---|---|---|
| 1 | Reading path device to app | The core of the demo | Absent (`02` section 7g) |
| 2 | Hub-side adapter: serves manifests, answers reading requests from the SaMDPi emulator | S4; do not fork simulation | Absent |
| 3 | Instrument manifests with action and result concepts | `03` section 8 | AC only |
| 4 | Manifest storage per device | Newest overwrites | `K/ThingDetailsActivity.kt:222` |
| 5 | Control-name on the wire | Device cannot tell which control | `K/DisplayThingActivity.kt:497` |
| 6 | Labels from manifest; no fake value; read-only widget; action button | Wrong label and `0.0 mmHg` are the top safety finding of this study | `03` section 8 |
| 7 | Device tabs (BP, SpO2, Thermometer) | Operator's design | Absent |
| 8 | Send action and result channel to SaMD-App | S1 | Absent |
| 9 | Reading payload contract | S5, `12` | Absent |
| 10 | Transport replacing RFCOMM, framing fix | T-0 | Ad hoc |
| 11 | Decision on command crypto for the demo | R0-3 bars it from SaMD; OneIoT is a separate app under S1, so its fate there is the operator's call | Open |

## 8. Answer to STOP-1B (S3c)

**Recommendation: yes, build a copy, app only.** A compile result is now decision-relevant: it settles the toolchain baseline before any demo work is sized, and it surfaces real blockers instead of my INFERRED list. Still awaiting the operator's yes.

Proposed exact plan, all under `/media/sandesh/extra-ssd/oneiot-study/build-copy`:

1. `df -h /` check. Copy from the printed file list plus required binaries (`gradle-wrapper.jar`, `app/libs`, `mipmap-*`), never `privateKey.txt`, `google-services.json`, `.idea`, `build`, `.gradle`, `app/py311`.
2. In the copy only: drop `.txt` from the 2 properties files, set `gradlew` executable, and remove the `google-services` plugin lines (`build.gradle.kts:5`, `app/build.gradle.kts:7`). That edit is required because rule 1 excludes the file, and needs explicit approval.
3. `ANDROID_HOME` by environment, no `local.properties`. `GRADLE_USER_HOME=/media/sandesh/extra-ssd/.gradle`, `TMPDIR` on the SSD, `--no-daemon`.
4. `./gradlew :app:assemblePy310Debug --no-daemon --console=plain`, needing network for dependencies.
5. Record outcome in `07`. No install, no adb, no phone.

| Would prove | Would not prove |
|---|---|
| Toolchain baseline compiles or what breaks | Runtime on Android 17 |
| Chaquopy 16.0.0 still resolves its wheels | 16 KB page behaviour on the Pixel |
| Size of the debug APK | Anything about the Pi side |

**Pi-side local simulation: not recommended now.** It needs path edits and new keys, and it exercises scripts that T-0 and the S4 adapter replace. It would prove little that reading has not.
