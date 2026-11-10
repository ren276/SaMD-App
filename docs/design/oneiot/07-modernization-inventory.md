Status: RESEARCH, not a design ruling. Sources read: OneIoT build files (`01-inventory.md`), all Kotlin under `K/`, `$STUDY_WT/gradle/libs.versions.toml`, `$STUDY_WT/app/build.gradle.kts`, `$STUDY_WT/gradle/wrapper/gradle-wrapper.properties` (origin/master `4b9ee3c`); Android behavior-change pages, Bluetooth permissions, androidx security releases, Chaquopy versions, PyBluez README, as linked below. Nothing was built: STOP-1B is pending.

# 07: Modernization inventory

OneIoT paths are relative to `/media/sandesh/extra-ssd/AndroidWork/OneIOTApp`. `K/` means `app/src/main/java/com/example/iot1/`. `$STUDY_WT` is `/media/sandesh/extra-ssd/wt-oneiot-study`. Lines marked V are in `$STUDY_WT/gradle/libs.versions.toml`, lines marked B in `$STUDY_WT/app/build.gradle.kts`.

## 1. Versions vs SaMD-App

| Item | OneIoT | Source | SaMD-App | Source |
|---|---|---|---|---|
| Gradle | 8.7 | `gradle/wrapper/gradle-wrapper.properties.txt:4` | 9.6.1 | `$STUDY_WT/gradle/wrapper/gradle-wrapper.properties:3` |
| AGP | 8.5.0 | `gradle/libs.versions.toml:2` | 9.2.1 | V2 |
| Kotlin | 1.9.0 | `gradle/libs.versions.toml:5` | 2.3.10 | V3 |
| compileSdk | 34 | `app/build.gradle.kts:14` | 37 | B86-88 |
| targetSdk | 34 | `app/build.gradle.kts:19` | 37 | B93 |
| minSdk | 24 | `app/build.gradle.kts:18` | 26 | B92 |
| Java target | 1.8 | `app/build.gradle.kts:44-48` | 11 | B244-245 |
| UI | XML Views, data binding | `app/build.gradle.kts:39-42` | Compose BOM 2026.06.01 | V13 |
| Persistence | Raw SQLiteOpenHelper | `K/DBHelper.kt:7` | Room 2.8.4 with SQLCipher 4.17.0 | V17, V20 |
| DI | None | whole app | Hilt 2.60.1 | V15 |
| JSON | Gson 2.10.1 plus org.json | `gradle/libs.versions.toml:3` | kotlinx serialization 1.11.0, Gson converter | V19, V73 |
| Coroutines | Transitive, undeclared | `K/ThingDetailsActivity.kt:26-31`; 0 hits in OneIoT build files | 1.11.0 | V18 |
| core-ktx | 1.13.1 | `gradle/libs.versions.toml:6` | 1.19.0 | V5 |
| Activity | 1.9.0 | `gradle/libs.versions.toml:12` | activity-compose 1.13.0 | V12 |
| Retrofit | 2.9.0 | `app/build.gradle.kts:83` | 2.11.0 | V26 |
| OkHttp | 4.9.0, logging 4.10.0 (mismatched) | `app/build.gradle.kts:85,87` | 4.12.0 | V27 |
| BLE | None | whole app | Nordic ble-ktx 2.11.0 | V28, V36 |
| androidx junit | 1.2.1 | `gradle/libs.versions.toml:8` | 1.3.0 | V7 |
| junit | 4.13.2 | `gradle/libs.versions.toml:7` | 4.13.2 | V6 |
| Modules | `:app` only | `settings.gradle.kts:23` | see Phase 2 | n/a |

### Dependencies that would have no place in SaMD

| Dependency | Why | Evidence |
|---|---|---|
| `mssql-jdbc`, `jtds`, `mysql-connector-java` | Ship unused, large, and add many SOUP items | `02` section 6 |
| `security-crypto` 1.1.0-alpha03 | All APIs deprecated from 1.1.0-alpha07 in favour of platform APIs and Android Keystore (EXTERNAL: [androidx security releases](https://developer.android.com/jetpack/androidx/releases/security)) | `app/build.gradle.kts:86` |
| Chaquopy 16.0.0 | Exists only for crypto, ruled out by R0-3 | `build.gradle.kts:6` |
| `google-services` plugin | Firebase auth is in the catalog but unused by `app` | `app/build.gradle.kts:7` |

## 2. Would it build today? (static judgement only)

Not as it stands (INFERRED, confirmed only if STOP-1B runs):

| # | Blocker | Evidence |
|---|---|---|
| 1 | `gradle.properties.txt` is ignored, so `android.useAndroidX` is unset while AndroidX is used | file name, `gradle.properties.txt:17` |
| 2 | Wrapper properties have a `.txt` suffix, so `./gradlew` cannot find its distribution | file name |
| 3 | `gradlew` has no execute bit | `01` section 4 |
| 4 | Imports of an internal Gradle class at the top of the build script may fail | `app/build.gradle.kts:1-2` |
| 5 | Chaquopy `buildPython("/usr/bin/python3")`. Host Python is 3.14.6; compatibility with Chaquopy 16 and app Python 3.10 and 3.11 is unverified | `app/build.gradle.kts:62` |
| 6 | Unpinned `cryptography` needs a prebuilt wheel for that Chaquopy version | `app/build.gradle.kts:64` |
| 7 | JDK: AGP 8.5 needs JDK 17; host JDK not checked | n/a |
| 8 | `google-services.json` must match the package; file not opened | `app/build.gradle.kts:7` |

With 1 to 3 fixed in a copy, it could plausibly compile, since the APK shows an older revision did (INFERRED).

## 3. Deprecated and fragile APIs in the source

| API | Where | Note | Tag |
|---|---|---|---|
| `BluetoothAdapter.getDefaultAdapter()` | `K/CheckActivity.kt:26`, `K/BluetoothMainScreen.kt:81` | Deprecated at API 31 | EXTERNAL knowledge, confirm at migration |
| `ConnectivityManager.activeNetworkInfo`, `isConnected` | `K/CheckActivity.kt:37-38`, `K/MainActivity.kt:55` | Deprecated at API 29 | same |
| `startActivityForResult`, `onActivityResult` | `K/CheckActivity.kt:79,85` | Superseded by Activity Result API | same |
| `getParcelableExtra(String)` | `K/BluetoothMainScreen.kt:57` | Deprecated at API 33 | same |
| `MasterKeys`, `EncryptedSharedPreferences` | `K/MainActivity.kt:131-138`, `K/RegisterActivity.kt:127-134` | Deprecated (see section 1) | EXTERNAL |
| `android.widget.Switch` | `app/src/main/res/layout/xml3.xml:15` | Material or Compose switch preferred | INFERRED |
| `GlobalScope`, unscoped `CoroutineScope(Dispatchers.Main)` | `K/BluetoothMainScreen.kt:151`, `K/ThingDetailsActivity.kt:72` | Leaks past the Activity | VERIFIED |
| `runBlocking` inside a thread | `K/ThingDetailsActivity.kt:202` | Works, poor form | VERIFIED |
| Hardcoded `/storage/emulated/0/...`, `/data/data/...` | `K/DisplayThingActivity.kt:83,811,833` | Breaks for secondary users; use `getExternalFilesDir`, `filesDir` | INFERRED |
| Non-stable imports | `org.slf4j.MDC.put` from a transitive jar, unused (`K/DBHelper.kt:5`); `ContentValues.TAG` used as a log tag | | VERIFIED |

## 4. Android 14 to 17 changes vs this code

Target in the first column is the target API that triggers it.

| Target | Change | Applies here? | Evidence | Tag and source |
|---|---|---|---|---|
| 34 | Foreground service types required | Not yet: no services declared. Any future `connectedDevice` service needs a type | `app/src/main/AndroidManifest.xml` | EXTERNAL [14 changes](https://developer.android.com/about/versions/14/behavior-changes-14) |
| 34 | Context-registered receivers must set an export flag; receivers for system broadcasts only are exempt | Exempt: ACL-connected is a system broadcast | `K/BluetoothMainScreen.kt:257-260` | EXTERNAL same page |
| 34 | Implicit intents and mutable PendingIntent limits | No impact: explicit intents, no PendingIntent | whole app | EXTERNAL same page |
| 34 | Dynamic code must be read-only | Chaquopy extracts code at run time; whether affected is unverified | `build.gradle.kts:6` | EXTERNAL, open question |
| 34 | `UUID.fromString` stricter | Both UUIDs are well formed | `K/DisplayThingActivity.kt:57` | EXTERNAL, INFERRED |
| 35 | Edge-to-edge enforced | Affects all screens. `enableEdgeToEdge` with no inset handling on the generator container, and top and bottom rows would sit under bars | `K/DisplayThingActivity.kt:78`, `app/src/main/res/layout/activity_display_thing.xml` | EXTERNAL [15 changes](https://developer.android.com/about/versions/15/behavior-changes-15) |
| 35 | `dataSync` foreground service limit | Not used; matters if a future service picks that type | n/a | EXTERNAL same page |
| 35 | TLS 1.0 and 1.1 disallowed | No TLS in use at all | `K/ApiService.kt:37` | EXTERNAL same page |
| 36 | Edge-to-edge opt-out removed | As above | same | EXTERNAL [16 changes](https://developer.android.com/about/versions/16/behavior-changes-16) |
| 36 | Predictive back; `onBackPressed` not called | Not overridden | whole app | EXTERNAL same page |
| 36 | Orientation and resizability ignored at sw600dp and up, more re-creation | Rotation restarts the activity and reconnects | `K/DisplayThingActivity.kt:76-102` | EXTERNAL same page |
| 36, 37 | Bluetooth bond-loss intents (36); RFCOMM `read()` returns `-1` when the socket closes (37) | `-1` handled in `receiveFile`. `sendViaBluetooth` treats it as "no response" | `K/BluetoothMainScreen.kt:211-212`, `K/BluetoothDisplayThingActivity.kt:463-507` | EXTERNAL same page, [17 changes](https://developer.android.com/about/versions/17/behavior-changes-17) |
| 37 | `ACCESS_LOCAL_NETWORK` required for LAN | **Breaking.** Every IP path dials RFC 1918 addresses, and the permission is not declared. Socket errors result | `K/DisplayThingActivity.kt:384`, `K/ApiService.kt:37`, `app/src/main/AndroidManifest.xml:5-20` | EXTERNAL [17 changes](https://developer.android.com/about/versions/17/behavior-changes-17) |
| 37 | Native libs loaded with `System.load` must be read-only | Chaquopy native loading unverified | `build.gradle.kts:6` | EXTERNAL same page, open question |
| 37 | Opt-out of large-screen rules ends | As for 36 | n/a | EXTERNAL same page |
| any 31+ | `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` runtime; legacy permissions should set `maxSdkVersion` 30 | Legacy lacks `maxSdkVersion`. `ACCESS_FINE_LOCATION` kept, no `neverForLocation`. `BLUETOOTH_ADVERTISE` declared, never used. `CheckActivity` requests CONNECT with no version check. Below API 31 the OS does not know that permission, so it would likely be denied and stop the Bluetooth path (INFERRED) | `app/src/main/AndroidManifest.xml:5-11`, `K/CheckActivity.kt:55-62` | EXTERNAL [BT permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions) |
| any | `uses-feature bluetooth required=true` | Blocks install on any device with no Classic Bluetooth | `app/src/main/AndroidManifest.xml:21-23` | VERIFIED |
| any 33+ | `WRITE_EXTERNAL_STORAGE` capped at 32, app-scoped dirs need none | Harmless leftover | `:14-20` | INFERRED |

## 5. Chaquopy status

Latest listed: Chaquopy 17.2, Python 3.10 to 3.15, AGP 7.3 to 9.5, minSdk 24 (EXTERNAL: [Chaquopy versions](https://chaquo.com/chaquopy/doc/current/versions.html)). The page does not say when 16 KB pages arrived, so that question stays open. OneIoT pins 16.0.0. SaMD-App at AGP 9.2.1 falls in 17.2's stated range. That is compatibility only and says nothing about whether to use it; see R0-3.

## 6. Pi side vs Pi 5 on Raspberry Pi OS Lite

| Item | Today | On SaMDPi hubs | Evidence |
|---|---|---|---|
| Python | Hardcoded venv `/home/veman/Desktop/py3.9testenv`, Python 3.9 by name | Debian 13 based Raspberry Pi OS ships 3.13 (EXTERNAL, [Debian 13 summary](https://www.cnx-software.com/2025/08/10/debian-13-trixie-released-with-linux-6-12-official-64-bit-risc-v-support/), secondary source). Exact hub image: Phase 2 | `send.py:90` |
| PyBluez | Required for RFCOMM | README says no longer developed and recommends alternatives (EXTERNAL: [pybluez](https://github.com/pybluez/pybluez)) | `thing_bluetooth.py:1` |
| SDP advertising | `advertise_service` | Needs `bluetoothd` compat mode (EXTERNAL, weak: [community guide](https://github.com/sraodev/bluetooth-service-rfcomm-python), not confirmed for current OS) | `thing_bluetooth.py:33-39` |
| Lite image | `air_conditioner.py` needs a display, Kivy | Unusable on Lite; simulation only anyway (INFERRED) | `air_conditioner.py:9,11` |
| `RPLCD` | Imported at module top, all uses commented | Must be installed or `send.py` will not start | `send.py:3` |
| `pyodbc`, ODBC Driver 17 and 18 | `ipverify.py` on the Pi | Availability on 64-bit Pi OS unverified. Has no place in SaMD: hubs must not hold DB credentials | `ipverify.py:2,25` |
| `sudo -E` chains, self-spawn | Used for Bluetooth | Replace with services; recursion grows the process tree | `thing_mobile.py:133,141` |
| Paths | `/home/veman/Desktop` | Absent on hubs | `send.py:41,72` |
| Service units | None | SaMDPi has its own deploy (Phase 2) | `01` section 8 |
| Runtime files | Missing from this folder | `02` section 5 | |
| PEP 668 | System pip blocked on Debian 12 and later | A venv is needed (INFERRED) | n/a |

Conclusion: the Pi programs would need replacing, not porting (INFERRED). Comparison against the SaMDPi emulated instruments is Phase 2.

## 7. Working hypothesis R0-7: evidence from modernization

No recommendation until Phase 3.

| Direction | Evidence |
|---|---|
| For rebuilding as modules | Upgrading in place crosses Gradle, AGP, Kotlin 1 to 2, target 34 to 37, Views to Compose. Most lines would change anyway |
| For rebuilding | About 3350 Kotlin lines. The 3 generator activities are 1989 of them with heavy overlap, and the security layer is demo-only. Little to retain |
| For rebuilding | Pi programs do not survive the move to Pi 5 Lite and SaMDPi's own instruments |
| For rebuilding | Removes 4 SOUP groups (JDBC drivers, Chaquopy, its Python, `cryptography`) from any IEC 62304 scope |
| Against "extracting" | Only the concept and the JSON shape would carry over, and they need new fields (`03` section 8) |
| Against dropping the app outright | A modernised standalone OneIoT demo could serve the lab or grant, if wanted. Not knowable from the repo |
| Cost note | A build attempt (STOP-1B) would prove runnability, but would not change this picture |

Superseded by S1 and S3c (2026-11-06): the demo now runs on the OneIoT app, so a build of a copy is decision-relevant. See `07b` section 8.

## 8. Split per S3a: minimum for the demo vs full modernization

Details of the left column are in `07b-runnability-for-demo.md`.

| Item | Minimum to run the demo | Full modernization (later) |
|---|---|---|
| `.txt` properties, `gradlew` mode | **Yes** | |
| `google-services` plugin, unused catalog entries | **Yes**, plugin must go because its file is excluded | Remove all |
| JDBC drivers | Can stay for now, unused | Remove |
| AGP, Gradle, Kotlin | No, keep 8.5.0, 8.7, 1.9.0 if they build | 9.x, 2.x |
| Target and compile SDK | No, stay 34: LAN stays implicit | 37, with `ACCESS_LOCAL_NETWORK` |
| Chaquopy | **Yes if the Pixel has 16 KB pages**: needs 17.0.0 or later | Remove with the crypto |
| Deprecated APIs (section 3) | No | Yes |
| Views to Compose | No | Yes, target T1 |
| Edge-to-edge insets | No at target 34 | Yes |
| Generator labels, sensor widget, action, per-device manifest | **Yes**, core of the demo | Rebuild as modules |
| Reading path, result to SaMD | **Yes** | Contract survives (`12`) |
| Cloud | No if R2 or R3 is chosen | Retire or redesign |
| Pi scripts | Replace with the hub adapter (S4), bearers per T-0 | Same |
| Permissions cleanup | No | Yes |
| Tests | Enough for demo paths, per operator | Full suite, as for SaMD |

## 9. Measured-in lines (additions only)

- **Section 5**, "The page does not say when 16 KB pages arrived": Measured in the [Chaquopy change log](https://chaquo.com/chaquopy/doc/current/changelog.html). 16.1.0 (2025-05-07) first links stubs in 16 KB mode, and 17.0.0 says devices with 16 KB pages are supported. OneIoT's 16.0.0 (2024-10-15) predates both. `buildPython` must be at least 3.8.
- **Section 2, row 5**: Measured in the same log. `/usr/bin/python3` meets the minimum, and `/usr/bin/python3.10` also exists on the host.
- **Section 2, row 7**: Measured: host JDK is 21.0.7.
- **Section 6, Python row**: S3a and T-3 say Bookworm, while the SaMDPi repo says Debian 13. Unresolved, see `07b` section 4.
- **Section 1**, Chaquopy "Exists only for crypto, ruled out by R0-3": R0-3 applies to SaMD. Under S1 the separate demo app may keep it, by operator decision.
- **Section 4**, Android 17: all-apps page checked for a target-34 app. Nothing there blocks LAN sockets or install. Cross-profile loopback is blocked; D1 on the same profile is unaffected (EXTERNAL: [Android 17 all apps](https://developer.android.com/about/versions/17/behavior-changes-all)). `usesCleartextTraffic` is slated for deprecation (same page).

## 10. Measured: build of a copy (R2-1, 2026-11-06)

Plan: `07b-runnability-for-demo.md` section 8. Copy at `/media/sandesh/extra-ssd/oneiot-study/build-copy`, log at `/media/sandesh/extra-ssd/oneiot-study/build-1.log`. Only the approved copy edits were made. No install, adb or phone.

| Item | Result | Tag |
|---|---|---|
| Command | `./gradlew :app:assemblePy310Debug --no-daemon --console=plain` | VERIFIED |
| Outcome | **BUILD SUCCESSFUL in 53s**, 48 actionable tasks, exit 0 | VERIFIED (`build-1.log:170`) |
| APK | `app/build/outputs/apk/py310/debug/app-py310-debug.apk`, 42,942,428 bytes (42.9 MB) | VERIFIED |
| Chaquopy | 16.0.0 ran, Python installed for arm64-v8a and x86_64 | VERIFIED (`build-1.log:28,40`) |
| Wheels | Resolved: `cryptography` 42.0.8 for cp310, `cffi` 1.17.1, `chaquopy-libffi` 3.3, `pycparser` from `chaquo.com/pypi-13.1` | VERIFIED (`build-1.log:30-39`) |
| Kotlin warnings | 53 `w:` lines; no errors | VERIFIED |
| `google-services` plugin removed | Build unaffected, as no code uses Firebase | VERIFIED |
| Host | JDK 21.0.7, SDK `android-34`, `ANDROID_HOME` by env, no `local.properties` | VERIFIED |

Measured against section 2: blockers 1 to 3 and 8 were real and are cleared by the copy edits. Risks 4 (internal Gradle import), 5 (`buildPython`), 6 (`cryptography` wheel) and 7 (JDK) did not materialise with this host.

A compile proves only the toolchain baseline. Android 17 runtime behaviour, 16 KB page behaviour and every INFERRED runtime effect in `06` remain unproven.

## 11. Measured: hub OS and Python (R2-5, 2026-11-06)

- **Section 6, Python row**, and section 9's Bookworm line: Measured by operator: `kernelhub1` and `kernelhub2` both report Debian GNU/Linux 13 (trixie), `DEBIAN_VERSION_FULL=13.7`. S3a and T-3's Bookworm is superseded. **Hub target is Debian 13 trixie.** The Bookworm case is dropped.
- **Hub Python:** 3.13.5 on the hubs per `docs/design/samdpi-two-hub-memo.md:1650` and SaMDPi `emulator/transports/wifi/server.py:227` ("seen on 3.13.5"). `kernelhub1` runs `/usr/bin/python3` (`deploy/systemd/samdpi-hub-wifi.service:17`). `kernelhub2` runs `/home/sandesh/samdpi/.venv/bin/python` (`deploy/systemd/samdpi-hub-ble.service:20`). `pyproject.toml:5` requires 3.11 or later. `deploy/setup_pi.sh:18` only prints the version and pins nothing.
- **BlueZ:** 5.82 on both hubs (`docs/design/samdpi-two-hub-memo.md:78,1585`).
