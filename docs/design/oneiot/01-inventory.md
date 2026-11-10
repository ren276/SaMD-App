Status: RESEARCH, not a design ruling. Sources read: directory listing of `OneIOTApp`; `build.gradle.kts`, `settings.gradle.kts`, `app/build.gradle.kts`, `gradle/libs.versions.toml`, `gradle.properties.txt`, `gradle/wrapper/gradle-wrapper.properties.txt`, `app/src/main/AndroidManifest.xml`, `app/py311/release/output-metadata.json`, `ac.json`, `.gitignore`; first lines only of `grammar.txt`, `testingGrammer.txt`, `android_grammer_rules`, `Untitled Document 1`; import lines of all Python files.

# 01: OneIoT inventory

Paths are relative to `/media/sandesh/extra-ssd/AndroidWork/OneIOTApp`. Tags: VERIFIED, INFERRED, DOCS-ONLY, EXTERNAL.
This memo describes what exists. How it behaves is Phase 1 work. Any statement here about the purpose of an unread file is INFERRED.

## 1. Version control and history

| Item | Finding | Tag |
|---|---|---|
| Git | Not a git repo: `git rev-parse` fails at the root | VERIFIED |
| Commits, branches, contributors, remotes, first and last commit | Unknowable from this folder (brief 0.3 cannot be answered) | VERIFIED |
| File dates, excluding `build`, `.gradle`, `.idea` | 74 files 2025-05-21, 32 files 2026-10-05, 3 files 2026-11-06 | VERIFIED |
| Dates as history | Look like copy dates, not edit dates, so weak evidence of project age | INFERRED |
| Earliest date inside a file | `gradle/wrapper/gradle-wrapper.properties.txt:1` is stamped Thu Jul 11 2024 | VERIFIED |

## 2. Tree (depth 4, excluding `build`, `.gradle`, `.git`, `.idea`)

```
.
|-- ac.json                        example manifest
|-- grammar.txt                    formal grammar (V, T, S, P)
|-- android_grammer_rules          Kotlin grammarMap source, not compiled (no .kt suffix, outside app/)
|-- testingGrammer.txt             arithmetic grammar, unrelated to manifests from its first lines
|-- grammar_parser.py              imports lark
|-- Untitled Document 1            earlier DisplayThingActivity source, see section 7
|-- adhocServer.py  air_conditioner.py  blue.py  cloud.py  decrypter.py  decryptMessage_test.py
|-- encrypter.py  encryptMessage_test.py  fetch.py  IPLocation.py  ipverify.py  kotlintopython.py
|-- output.py  send.py  thing_bluetooth.py  thing_mobile.py  thing_mobile_bluetooth.py
|-- privateKey.txt  (rule 1, unopened)   publicKey.txt
|-- A_novel_IoT-device_management_platform_for_on-the- (1) (1).pdf
|-- TIH_IoT Project Documentation.docx.pdf  Android_code_documentation.pdf  Android UI Screenshots.pdf
|-- build.gradle.kts  settings.gradle.kts  gradle.properties.txt  gradlew  gradlew.bat  .gitignore
|-- gradle/libs.versions.toml
|-- gradle/wrapper/gradle-wrapper.jar  gradle/wrapper/gradle-wrapper.properties.txt
`-- app/
    |-- build.gradle.kts  proguard-rules.pro  .gitignore
    |-- google-services.json  (rule 1, unopened)
    |-- libs/jtds-1.3.1.jar
    |-- py311/release/app-py311-release.apk  output-metadata.json  baselineProfiles/{0,1}/*.dm
    `-- src/
        |-- androidTest/java/com/example/iot1/ExampleInstrumentedTest.kt
        |-- test/java/com/example/iot1/ExampleUnitTest.kt
        `-- main/
            |-- AndroidManifest.xml
            |-- java/com/example/iot1/   12 Kotlin files
            |-- python/                  adhoc.py decrypt.py encrypt.py hash.py hashverify.py
            `-- res/                     layout (16), drawable (6), xml (4), values, values-night, mipmap-*
```

## 3. Counts and LOC

Total files excluding `build`, `.gradle`, `.git`, `.idea`: 109 (VERIFIED). `cloc` is not installed, so these are `wc -l` figures including blanks and comments.
Counts exclude `build`, `.gradle`, `.git`, `.idea`, `app/py311`, `app/libs`, `gradle/wrapper`, `privateKey.txt`, `app/google-services.json`.

| Language | Files | Lines |
|---|---|---|
| Kotlin | 14 (12 main, 2 template tests) | 3353 |
| Python | 23 (18 root, 5 in `app/src/main/python`) | 1590 |
| XML | 33 | 1068 |
| Kotlin script | 3 | 117 |
| TOML | 1 | 40 |
| JSON | 1 (`ac.json`) | 31 |
| TXT | 4 (`gradle.properties.txt`, `grammar.txt`, `publicKey.txt`, `testingGrammer.txt`) | 116 |
| Extensionless text | 2 (`android_grammer_rules` 429, `Untitled Document 1` 365) | 794 |

### Kotlin main sources (lines)

| File | Lines | File | Lines |
|---|---|---|---|
| `DisplayThingActivity.kt` | 848 | `BluetoothMainScreen.kt` | 273 |
| `BluetoothDisplayThingActivity.kt` | 636 | `RegisterActivity.kt` | 180 |
| `Adhoc.kt` | 505 | `MainActivity.kt` | 144 |
| `ThingDetailsActivity.kt` | 364 | `CheckActivity.kt` | 111 |
| `AvailableThingsActivity.kt` | 96 | `ApiService.kt` | 67 |
| `DBHelper.kt` | 51 | `Thing.kt` | 39 |

### Root Python (lines, third-party imports)

| File | Lines | Third-party imports |
|---|---|---|
| `cloud.py` | 218 | flask, pyodbc |
| `thing_bluetooth.py` | 157 | bluetooth |
| `thing_mobile.py` | 142 | none |
| `thing_mobile_bluetooth.py` | 131 | bluetooth |
| `blue.py` | 127 | bluetooth |
| `output.py` | 111 | none |
| `send.py` | 93 | RPLCD |
| `fetch.py` | 79 | none |
| `kotlintopython.py` | 74 | none |
| `air_conditioner.py` | 62 | kivy, kivymd, twisted |
| `ipverify.py` | 59 | pyodbc |
| `decrypter.py` | 58 | cryptography |
| `encrypter.py` | 50 | cryptography |
| `grammar_parser.py` | 47 | lark |
| `adhocServer.py` | 34 | none |
| `decryptMessage_test.py` | 20 | cryptography |
| `encryptMessage_test.py` | 20 | cryptography |
| `IPLocation.py` | 6 | requests |

### App-embedded Python (Chaquopy, lines)

`adhoc.py` 23, `decrypt.py` 29, `encrypt.py` 25, `hash.py` 7, `hashverify.py` 18. Third-party: cryptography (`decrypt.py`, `encrypt.py`).

## 4. Android build configuration

| Setting | Value | Source | Tag |
|---|---|---|---|
| Gradle wrapper | 8.7 | `gradle/wrapper/gradle-wrapper.properties.txt:4` | VERIFIED |
| AGP | 8.5.0 | `gradle/libs.versions.toml:2` | VERIFIED |
| Kotlin | 1.9.0 | `gradle/libs.versions.toml:5` | VERIFIED |
| compileSdk, targetSdk, minSdk | 34, 34, 24 | `app/build.gradle.kts:14,18,19` | VERIFIED |
| Java and JVM target | 1.8 | `app/build.gradle.kts:44-48` | VERIFIED |
| UI toolkit | XML Views with viewBinding and dataBinding; no Compose dependency | `app/build.gradle.kts:39-42,68-89` | VERIFIED |
| applicationId, namespace | `com.example.iot1` | `app/build.gradle.kts:13,17` | VERIFIED |
| versionCode, versionName | 1, 1.0 | `app/build.gradle.kts:20,21` | VERIFIED |
| Project name | IOT1 | `settings.gradle.kts:22` | VERIFIED |
| Modules | `:app` only | `settings.gradle.kts:23` | VERIFIED |
| Flavors | `py310`, `py311` (dimension `pyVersion`) | `app/build.gradle.kts:50-54` | VERIFIED |
| ABI filters | arm64-v8a, x86_64 | `app/build.gradle.kts:25` | VERIFIED |
| Minify | off in release | `app/build.gradle.kts:32` | VERIFIED |
| Chaquopy | 16.0.0, Python 3.10 and 3.11, buildPython `/usr/bin/python3`, pip `cryptography` (unpinned) | `build.gradle.kts:6`, `app/build.gradle.kts:56-67` | VERIFIED |
| Prebuilt APK | `app/py311/release/app-py311-release.apk`, 46,304,182 bytes, py311Release, 1.0 | `app/py311/release/output-metadata.json:7-16` | VERIFIED |

### Build-file oddities (static, nothing was built)

| Finding | Source | Tag |
|---|---|---|
| Wrapper properties and Gradle properties carry a `.txt` suffix, so Gradle looks past both by name | `gradle/wrapper/gradle-wrapper.properties.txt`, `gradle.properties.txt` | VERIFIED name, INFERRED effect |
| `gradlew` has no execute bit (`-rw-rw-r--`) | listing | VERIFIED |
| `app/build.gradle.kts` imports `org.gradle.internal.impldep...IvyAuthenticator` (internal Gradle API) and does not use it in the file | `app/build.gradle.kts:1-2` | VERIFIED |
| `mysql-connector-java-v8030` alias resolves to 8.0.33, not 8.0.30 | `gradle/libs.versions.toml:16,32` | VERIFIED |
| `google-services` plugin is applied, while `firebase-auth` is in the catalog but not in `app` dependencies | `app/build.gradle.kts:7,68-89`, `gradle/libs.versions.toml:34` | VERIFIED |

## 5. Android dependencies

Applied in `app/build.gradle.kts:68-88`. Versions come from `gradle/libs.versions.toml` unless written inline.

| Dependency | Version | Line in `app/build.gradle.kts` |
|---|---|---|
| androidx.core:core-ktx | 1.13.1 | 69 |
| androidx.appcompat:appcompat | 1.7.0 | 70 |
| com.google.android.material:material | 1.12.0 | 71 |
| androidx.activity:activity | 1.9.0 | 72 |
| androidx.constraintlayout:constraintlayout | 2.1.4 | 73 |
| com.microsoft.sqlserver:mssql-jdbc | 11.2.0.jre8 | 75 |
| jtds (local jar `app/libs/jtds-1.3.1.jar`) | 1.3.1 | 76 |
| mysql:mysql-connector-java | 8.0.33 | 81 |
| androidx.cardview:cardview | 1.0.0 | 82 |
| com.squareup.retrofit2:retrofit | 2.9.0 | 83 |
| com.squareup.retrofit2:converter-gson | 2.9.0 | 84 |
| com.squareup.okhttp3:okhttp | 4.9.0 | 85 |
| androidx.security:security-crypto | 1.1.0-alpha03 | 86 |
| com.squareup.okhttp3:logging-interceptor | 4.10.0 | 87 |
| com.google.code.gson:gson | 2.10.1 | 88 |
| junit:junit (test) | 4.13.2 | 78 |
| androidx.test.ext:junit (androidTest) | 1.2.1 | 79 |
| androidx.test.espresso:espresso-core (androidTest) | 3.6.1 | 80 |

Build plugins: `com.android.application` 8.5.0, `org.jetbrains.kotlin.android` 1.9.0, `com.google.gms.google-services` 4.4.2, `com.chaquo.python` 16.0.0 (`gradle/libs.versions.toml:36-39`, `build.gradle.kts:3-6`).

Catalog entries not used by `app`: `jtds` (the local jar is used), `mysql-connector-java` 5.1.49, `mysql-connector-java-v8032` 8.0.32, `firebase-auth` 23.0.0 (`gradle/libs.versions.toml:23,31,33,34`).

okhttp 4.9.0 and logging-interceptor 4.10.0 are on different versions as declared (VERIFIED).

## 6. AndroidManifest summary

All from `app/src/main/AndroidManifest.xml` (VERIFIED). Behaviour analysis belongs to memos 06 and 07.

| Item | Value | Lines |
|---|---|---|
| Launcher | `CheckActivity` | 49-56 |
| Activities | Adhoc (not exported); BluetoothDisplayThing, BluetoothMainScreen, Check, DisplayThing, ThingDetails, Register, AvailableThings, Main (all exported) | 36-81 |
| Permissions | BLUETOOTH, BLUETOOTH_ADMIN, BLUETOOTH_CONNECT, BLUETOOTH_SCAN, BLUETOOTH_ADVERTISE, ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION, INTERNET, ACCESS_NETWORK_STATE, WRITE_EXTERNAL_STORAGE and READ_EXTERNAL_STORAGE (both maxSdk 32) | 5-20 |
| Required feature | `android.hardware.bluetooth` | 21-23 |
| Cleartext | `usesCleartextTraffic="true"`, plus `network_security_config` | 26, 31 |
| Backup | `allowBackup="true"` | 25 |
| Services, receivers | None declared | whole file |
| Provider | FileProvider `com.example.IOT1.provider`, not exported | 83-91 |

## 7. Manifest and dictionary files

| Path | Size (bytes) | What it is | Tag |
|---|---|---|---|
| `ac.json` | 1180 | The only manifest file on disk: air conditioner, sections DEVICE, MODE, CONTROL | VERIFIED |
| `grammar.txt` | 2845 | Formal grammar: non-terminals V, terminals T, start S, productions P | VERIFIED from first lines |
| `android_grammer_rules` | 12216 | Kotlin `GrammarRule` and `grammarMap` with a `layout` field; outside the compiled source set | VERIFIED from first lines |
| `grammar_parser.py` | 1558 | Imports lark; role to be read in Phase 1 | VERIFIED import |
| `testingGrammer.txt` | 312 | Arithmetic expression grammar, apparently a lark exercise | INFERRED |
| `Untitled Document 1` | 14790 | Kotlin `DisplayThingActivity` hardcoded to AC keys; uses `R.layout.ac`, and no such layout exists in `res/layout` | VERIFIED |

More manifests may be embedded in Kotlin or Python: Phase 1 checks. Manifest-driven layouts appear to be `res/layout/xml1.xml` to `xml6.xml` (INFERRED from names, to confirm in Phase 1).

## 8. Pi side

| Item | Finding | Tag |
|---|---|---|
| Language | Python, no shebang lines in any Python file | VERIFIED |
| Runtime version | Not declared anywhere on the Pi side. Chaquopy's 3.10 and 3.11 apply to app-embedded Python only. | VERIFIED |
| Dependency manifests | No `requirements*.txt`, `pyproject.toml`, `setup.py`, `Pipfile` within depth 3 | VERIFIED |
| Service units | No `*.service` files within depth 3 | VERIFIED |
| Third-party modules imported | cryptography, bluetooth, twisted, pyodbc, kivy, kivymd, RPLCD, requests, lark, flask | VERIFIED |
| Which scripts run on Pi, cloud or laptop | Not separated by folder; all live in the root | VERIFIED layout, split is Phase 1 work |

## 9. Tests and CI

| Item | Finding | Tag |
|---|---|---|
| Android tests | `ExampleUnitTest.kt` (16 lines), `ExampleInstrumentedTest.kt` (23 lines), template file names | VERIFIED names and sizes |
| Python tests | `encryptMessage_test.py`, `decryptMessage_test.py`; other scripts import them as modules, so possibly helpers, not tests | VERIFIED imports, INFERRED role |
| CI | No `.github`, `.gitlab-ci.yml`, `Jenkinsfile`, `Dockerfile` within depth 3 | VERIFIED |

## 10. Documents

| Path | Pages | Treated as |
|---|---|---|
| `A_novel_IoT-device_management_platform_for_on-the- (1) (1).pdf` | 28 | research paper |
| `TIH_IoT Project Documentation.docx.pdf` | 26 | project doc |
| `Android_code_documentation.pdf` | 4 | project doc |
| `Android UI Screenshots.pdf` | 4 | project doc |

## 11. Skipped, and why

| Path | Why |
|---|---|
| `privateKey.txt` | Rule 1. Content not displayed or stored. See incident below. |
| `app/google-services.json` | Rule 1. Content not displayed or stored. See incident below. |
| `/media/sandesh/extra-ssd/veman/Android-oneIot--main` | Excluded by operator at STOP-0A. Same-name files were compared at STOP-0A only (key files skipped); that folder holds 6 files absent here and a differing `thing_mobile.py`, none of them studied. |
| `build/`, `.gradle/`, `.idea/` | Generated or IDE state |
| `app/py311/release/*.apk`, `*.dm`, `app/libs/*.jar`, `gradle/wrapper/gradle-wrapper.jar`, `res/mipmap-*` | Binary |

### Incident: rule 1 breach during Phase 0

A first line-count loop used `cat ... | wc -l` over `*.txt` and `*.json`, which passed `privateKey.txt` and `app/google-services.json` through `cat`.
Only aggregate line counts reached the output, and no file content was displayed or stored. The counts in section 3 were redone with both files excluded.

Accepted as logged by operator ruling R0-2 (2026-11-06). From then on: file list built and printed first (`oneiot-study/filelist.txt`), no `cat` or globs that can expand to a credential file.

### Amendments from the rulings at STOP 0 (additions only)

**Superseded: ruling R0-4 (veman comparison table).** The operator's own message with those rulings said not to use veman at all, because it is old and this folder is current. No comparison table was produced, and Phase 1 used nothing from veman. The same-name comparison in the skip table above remains as historical record from STOP-0A.

**Measured in `02-what-oneiot-is.md`:**
- Section 2 tree: `android_grammer_rules` is confirmed not compiled. Section 7 of `02` adds syntax defects.
- Section 7: `res/layout/xml1.xml` to `xml6.xml` are confirmed as the generator's layouts.
- Section 8, "Runtime version not declared anywhere on the Pi side": still true of manifests, but `send.py:90` names a venv `py3.9testenv`, which implies Python 3.9 was used.
- Section 8, "Which scripts run on Pi, cloud or laptop": answered in `02` section 5.
- Section 4, prebuilt APK: its method names show an older revision than this source, see `02` section 6.
- Section 9: `encryptMessage_test.py` and `decryptMessage_test.py` are confirmed as libraries.

**Skipped on the APK by ruling R0-5:** `resources.arsc`, string pools, `res`, `assets`. Only `classes*.dex` were extracted to scratch, for class and method names.

**Disk:** root free space at STOP 1A logged in the stop message.

**Amendment S2 (additions only):** `privateKey.txt` and `app/google-services.json`: present, unread, operator states rotated.

**A5 upstream check:** optional under S2, history only. Not done: no clone at `/media/sandesh/extra-ssd/oneiot-study/upstream-IoT` has been reported by the operator. The paper's GitHub and Zenodo links were not fetched.

**Figures (A1):** rendered to `/media/sandesh/extra-ssd/oneiot-study/figs/`; transcriptions in `05` section 11.
