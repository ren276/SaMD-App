# PR-B design addendum: SaMD-App BLE client and two-hub routing

Status: GATED (gate review 2026-10-09, section 15). Design only; no code.
Date: 2026-10-09. Author: Claude, running as Opus 5.5 (`claude-opus-5-5`), a stated and accepted
substitution for Opus 5.
Location: `docs/design/samdpi-pr-b-client-memo.md`. It is committed as the first (docs) commit of
PR-B1, so every code commit cites a tracked design.

Authority: `docs/design/samdpi-two-hub-memo.md` (the parent memo), above all its section 14. Its
14.5 items 1 to 16 are requirements and its 14.2 Q1 to Q14 rulings are settled. This addendum adds
the operator rulings B1 to B12 given at STOP 0 on 2026-10-09 (section 0), and the gate rulings G1
to G5 and Q1 to Q11 given at the gate review on 2026-10-09 (section 15). Where section 15 conflicts
with sections 0 to 14, section 15 holds; those sections have been updated to agree with it.

Bases:

- SaMD-App `origin/master` = `e327e7b` (merge of PR #78). The working tree equals it
  (`git diff HEAD origin/master` printed nothing).
- SaMDPi `master` = `bba2c6b`, https://github.com/ren276/SaMDPi.
- Phone iQOO I2302 (adb serial 10BE3A09C700046): Android 16, SDK 36; features
  `android.hardware.bluetooth`, `android.hardware.bluetooth_le`,
  `android.software.companion_device_setup` (VERIFIED `getprop`, `pm list features`).

Evidence convention, as in the parent memo: VERIFIED means a `file:line`, a command with its output,
or a URL was read; INFERRED means reasoned and not observed. Library and platform evidence was read
from local copies (local evidence, not in either repo):

- `no.nordicsemi.android:ble:2.11.0` and `ble-ktx:2.11.0` sources jars and AARs from
  repo1.maven.org, sha1 checked against Maven Central's `.sha1` files (ble sources
  `4d2ba13c...a175`, ble-ktx sources `86e53acb...bd3c`, ble AAR `c3b32c4d...971d`, ble-ktx AAR
  `45700c55...fb1b`). File references below are paths inside those jars.
- developer.android.com pages fetched 2026-10-09 (URLs in Sources).

No `.env`, `local.properties`, keystore, `google-services.json` or `secrets/` file was opened.

---

## 0. Decisions B1 to B12 (operator rulings at STOP 0)

| ID | Decision | Evidence that forced it |
|---|---|---|
| B1 | A dev-only transparent activity, `BlePrepActivity`, declared in `app/src/dev/AndroidManifest.xml`, hosts the `BLUETOOTH_CONNECT` request and the CDM association. The dev BLE source starts it from the application context and suspends on its result. `src/main` gains no `android.bluetooth`, `android.companion`, `CompanionDeviceManager` or Bluetooth-permission references. Guard G-B11b. Verified viable (section 3.6). | Start is wired in `CompounderScreen.kt:281-284` (`src/main`). |
| B2 | The default Wi-Fi base URL is derived from the Wi-Fi entry of `PI_HUB_ASSIGNMENT` as `http://<hub_id>.local:8090/`. `PI_GATEWAY_BASE_URL` stays only as an explicit override, for laptop desk work. All Wi-Fi entries must name one `hub_id`, otherwise the build fails closed. | `build.gradle.kts:118` defaults to `kernel-hub.local`. The live Avahi instance is `kernelhub1` (parent 14.3), and `NsdGatewayDns.kt:136` matches the instance name exactly. |
| B3 | Remove `tools/kernel-hub-avahi.service`. Repoint `NsdGatewayDns.kt:35,71` and `build.gradle.kts:116` at SaMDPi `deploy/avahi/kernelhub1.service`. | `tools/kernel-hub-avahi.service:30` still publishes `%h`. |
| B4 | Nullable `hubId` and `transport` on both `AcquisitionResult.Accepted` and `Rejected` (`src/main` domain). Every acquisition audit row carries both. | `VitalsSource.kt:78-87` has neither; the audit payloads are at `CompounderViewModel.kt:559-569,583-587`. |
| B5 | No third new `RejectReason`. Messages are keyed by (reason, transport) through a copy object. BLE BUSY maps to UNREACHABLE, and the BLE UNREACHABLE text covers both "out of range" and "in use by another device". | `rejectionMessage(reason)` (`CompounderViewModel.kt:200`) has no transport input. |
| B6 | The phone parser rejects everything SaMDPi `envelope.parse` rejects, in a documented order, plus `profile_version != 1` as MALFORMED. This is deliberate consumer-side strictness, and it is tested. | `envelope.py:103-149` does not check `profile_version`. |
| B7 | Q5 is formatting only. Nonces come from `UUID.randomUUID()`. The echoed nonce is compared by exact lowercase canonical string equality, never by UUID version. The fixture's version-6 nonce stays valid for decoder tests. | The fixture nonce `00112233-4455-6677-8899-aabbccddeeff` is version 6. |
| B8 | Wi-Fi `INSTRUMENT_NOT_SERVED` maps to HUB_MISMATCH. `INVALID_SESSION_CONFIG`, `MALFORMED_REQUEST` and `MALFORMED_JSON` map to MALFORMED. `HTTP_ERROR` and `NO_MEASUREMENT_AVAILABLE` are in section 6.3. | Today a 400 becomes `HttpException`, then MALFORMED (`PiGatewayVitalsSource.kt:49-51,98`). |
| B9 | An OkHttp interceptor on the gateway client validates `X-SaMDPi-Hub-Id` on every response, errors included, before any body is parsed. A missing or wrong header throws a typed exception that maps to HUB_MISMATCH. `startSession`, `getMeasurement` and `stopSession` all return `Response<T>`. A body `hub_id` that disagrees with the header also maps to HUB_MISMATCH. | `PiGatewayApi.kt:23,31` return bare bodies. |
| B10 | Convert the whole `rejectionMessage` to the `@StringRes` copy-object pattern, with every reason's text in `strings.xml`, and update `CompounderViewModelTest`. | Precedents: `SyncFailureCopy.kt`, `SlmRefusalCopy.kt:61-62`, `CaseStatusDisplay.kt:19`. |
| B11 | A byte-identical fixture copy, plus a test pinning sha256 `e82aea4dfd9f4388c77a11ed58fc25c6e08598af05597c5eccb822a6ac96bffc` that fails with a pointer to SaMDPi `bba2c6b`. | SaMD-App CI cannot read SaMDPi. |
| B12 | A `synthetic` flag in the compounder UI state, set only from `Accepted.synthetic`. The labels render from it. `captureMethod` is untouched. | The UI state has no such field (`CompounderViewModel.kt:131-137`). |

**One conflict found while applying B1.** The guard token cannot be the bare string `BLUETOOTH_`,
because the new `RejectReason.BLUETOOTH_UNAVAILABLE` (14.5 item 11) lives in `src/main` and contains
it. G-B11b therefore scans for permission strings (`permission.BLUETOOTH`, the `android.permission.`
prefix form and the `Manifest.permission.` form), not the bare prefix. Section 10 has the exact
token list.

---

## 1. Scope and boundary

### 1.1 Files

**`src/dev` (dev flavour only; absent from staging and prod by construction):**

| File | Change |
|---|---|
| `di/DevClinicalMockModule.kt:39` | Bind `RoutingVitalsSource` instead of `PiGatewayVitalsSource` |
| `di/PiGatewayNetworkModule.kt` | Install `HubIdInterceptor`; base URL from `BuildConfig.PI_GATEWAY_BASE_URL`, which the build now derives (B2) |
| `data/vitalssource/HubAssignment.kt` | New: `PI_HUB_ASSIGNMENT` parser (section 7.1) |
| `data/vitalssource/RoutingVitalsSource.kt` | New: the single binding (section 7.2) |
| `data/vitalssource/HubIdInterceptor.kt` | New (B9) |
| `data/vitalssource/PiGatewayApi.kt` | `Response<T>` on all three calls; DTO gains `hub_id`, `device_id`, `profile_version`, `envelope_version`, `emulator_build` (object) |
| `data/vitalssource/PiGatewayVitalsSource.kt` | Error-body mapping (B8), body/header agreement (B9), stamps `transport = WIFI` and `hubId` |
| `data/vitalssource/NsdGatewayDns.kt` | KDoc only (B3) |
| `data/vitalssource/ble/BleHubVitalsSource.kt` | New: the BLE `VitalsSource` (section 5) |
| `data/vitalssource/ble/BleAcquisition.kt` | New: the pure protocol over a `HubLink` interface, JVM-testable |
| `data/vitalssource/ble/HubLink.kt` | New: the interface `BleAcquisition` drives |
| `data/vitalssource/ble/BleHubManager.kt` | New: the only real `HubLink`, a `BleManager` subclass (the adapter); overrides `log` to drop everything below WARN (Q11) |
| `data/vitalssource/ble/HubInitPlan.kt` | New: the ordered list of per-connection init steps (14.5 items 1 and 6; G2) |
| `data/vitalssource/ble/DisSearch.kt` | New: the duplicate-0x180A search, pure (14.5 item 2) |
| `data/vitalssource/ble/HubEnvelope.kt` | New: envelope parser (B6) |
| `data/vitalssource/ble/SigDecoders.kt` | New: SFLOAT, FLOAT, 0x2A35, 0x2A5E, 0x2A1C |
| `data/vitalssource/ble/ControlPoint.kt` | New: START and STOP encoding, response decoding, result mapping |
| `data/vitalssource/ble/HubAssociations.kt` | New: the dev-only `hub_id` to association ID store, lookup of that ID in `getMyAssociations()`, and disassociate (G1, Q10) |
| `data/vitalssource/ble/BlePrepActivity.kt`, `BlePrepBridge.kt` | New (B1, section 3.6) |
| `app/src/dev/AndroidManifest.xml` | Permissions, features, activity (section 4) |
| `app/src/testDev/...` | Tests (section 10), `resources/ble_golden_vectors.json` |

**`src/main` (compiled into staging and prod).** This is the exact list:

| # | File | Change | Why it is unreachable in staging and prod |
|---|---|---|---|
| M1 | `domain/vitalssource/VitalsSource.kt` | `RejectReason.BLUETOOTH_UNAVAILABLE` and `HUB_MISMATCH` (14.5 item 11); `enum class AcquisitionTransport { WIFI, BLE }`; nullable `hubId` and `transport` on `Accepted` and `Rejected`, defaulted to null (B4); nullable `emulatorBuild: String?` on `Accepted`, defaulted to null (Q7) | Staging and prod bind `UnavailableVitalsSource` (`app/src/{staging,prod}/.../ProductionClinicalModule.kt:25`). It overrides only `readVitals` (`UnavailableVitalsSource.kt:14-16`), so `startAcquisition` is the interface default `Rejected(NOT_SUPPORTED)` (`VitalsSource.kt:21-22`), which never produces either new value or a non-null `hubId`, `transport` or `emulatorBuild`. |
| M2 | `presentation/compounder/AcquisitionRejectionCopy.kt` (new) | `@StringRes` mapping keyed by (reason, transport, sdkInt) (B5, B10) | It is called only from the rejection branch of `onStartAcquisition` and from `onLocalNetworkPermissionDenied`, both reached only from `AcquisitionControls`, which renders only when `PI_GATEWAY_ENABLED` (`CompounderScreen.kt:188`). That flag is `false` in staging and prod (`build.gradle.kts:139,146`). |
| M3 | `presentation/compounder/CompounderViewModel.kt` | `rejectionMessage` replaced by M2; `acquisitionError` becomes `@StringRes Int?`; `synthetic` UI flag (B12); audit payloads gain `hubId` and `transport` (B4), and the RECEIVED payload gains `emulatorBuild` (Q7) | As M2: every changed path starts at `onStartAcquisition`. |
| M4 | `presentation/compounder/CompounderScreen.kt` | Error rendered with `stringResource`; Emulated banner and per-field supporting text (14.5 item 12, B12) | Rendered only when `synthetic == true`, which is set only from `Accepted.synthetic` (B12). `UnavailableVitalsSource` never returns `Accepted`. |
| M5 | `res/values/strings.xml` | Every rejection text (B10), the two new reason texts, BLE variants (B5), the banner and the label | Resources ship in every flavour (bytes change), but no reachable code path references them in staging or prod. |
| M6 | `app/src/test/.../MainHasNoBluetoothSurfaceTest.kt` (new), `CompounderViewModelTest.kt` (updated) | Guards | Test code, not in any APK. |

`domain/connectivity/LocalNetworkFailure.kt:20` (`UNREACHABLE_OR_BLOCKED_MESSAGE`) becomes a string
resource and the constant is deleted. Its only users are `CompounderViewModel.kt:12,196` and a
comment in `KernelFailureCopy.kt:19` (VERIFIED `git grep`). The comment is updated in the same
commit.

**Root, outside `app/src`:** `app/build.gradle.kts` (`PI_HUB_ASSIGNMENT` field and its
configuration-time validation, B2 URL derivation, `devImplementation`), `gradle/libs.versions.toml`
(the `ble-ktx` entry), `tools/kernel-hub-avahi.service` deleted (B3).

### 1.2 What changes in the released software item

- **Changes.** The bytes of the staging and prod APKs: M1 domain types (two enum constants, one
  new enum, three nullable fields), M2 to M4 presentation code, M5 strings. That is a change to the
  released software item (IEC 62304 5.8 change control applies; PR-C records it). The
  architecture is unchanged.
- **Does not change.** Behaviour in staging and prod: no acquisition is reachable (the reasons
  above). No new permission, no new dependency, no SOUP change, no Bluetooth or companion class
  referenced. These are proved by section 1.3, not asserted.

### 1.3 Proof that staging and prod gain no Bluetooth permission, no BLE class and no Nordic dependency

1. **Source guard (G-B11, G-B11b, JVM, M).**
   - Scope: every file under `app/src/main`, `app/src/staging` and `app/src/prod`.
   - Forbidden tokens: `android.bluetooth`, `android.companion`, `CompanionDeviceManager`,
     `no.nordicsemi`, `permission.BLUETOOTH`.
   - All of `app/src` is already a Gradle test input (`build.gradle.kts:323`), so the guard cannot
     go UP-TO-DATE.
2. **Dependency graph (live check 9).**
   - `./gradlew :app:dependencies --configuration stagingReleaseRuntimeClasspath` and the
     `prodReleaseRuntimeClasspath` equivalent both print no `no.nordicsemi` line.
   - Only `devImplementation` is used (VERIFIED: no `devImplementation` exists today,
     `build.gradle.kts:225-286`).
3. **Merged manifests (live check 9b).** This step is needed because the Nordic AAR's own manifest
   declares `android.permission.BLUETOOTH` (`maxSdkVersion="30"`) and
   `android.permission.BLUETOOTH_CONNECT` (VERIFIED `ble-2.11.0.aar` `AndroidManifest.xml`), and the
   manifest merger copies a library's permissions into any variant that depends on it.
   - Run `./gradlew :app:processStagingReleaseMainManifest :app:processProdReleaseMainManifest`.
   - Grep the merged manifests under `app/build/intermediates/merged_manifests/` for `BLUETOOTH`
     and `companion_device_setup`: zero lines each.
   - For the dev variant, record the two permissions as expected.
4. **APK inspection (live check 9c).**
   - `apkanalyzer manifest permissions` on the staging and prod release APKs: no Bluetooth entry.
   - `apkanalyzer dex packages --defined-only`: no `no.nordicsemi` package.
5. **Behaviour.** `ProductionClinicalModuleTest` (`app/src/testProd/...`) already pins the prod
   binding to `UnavailableVitalsSource`. No change is needed there.

---

## 2. Library: `no.nordicsemi.android:ble-ktx:2.11.0` (Q11)

### 2.1 Artifact facts (VERIFIED)

- Latest and release are both `2.11.0` for `ble` and `ble-ktx`; `ble-ktx-2.11.0.aar`
  last-modified 2025-09-11 (`maven-metadata.xml`, HTTP headers). The GitHub release 2.11.0 notes
  list "Adding missing `timeout` methods" (#634) and a `BleManager.close()` clarification (#630).
- `ble-ktx` pom: `no.nordicsemi.android:ble:2.11.0`, `kotlinx-coroutines-android:1.10.2`,
  `kotlin-stdlib:2.2.20` (compile scope). The app pins coroutines `1.11.0` and Kotlin `2.3.10`
  (`libs.versions.toml:3,18`), and Gradle resolves to the higher versions. INFERRED harmless; the
  dev build proves it.
- `ble` pom: `androidx.annotation:1.9.1`, `androidx.core:1.12.0` (runtime).
- Both AARs declare `minSdkVersion="18"`, below the app's 26.
- `ble` AAR manifest: `BLUETOOTH` (max 30) and `BLUETOOTH_CONNECT`. The comment there says
  "Since API 31 (Android 12) apps need to request BLUETOOTH_CONNECT runtime permission. This is not
  in scope of this library." That is why section 1.3 step 3 exists.

### 2.2 API used, with signatures (VERIFIED, sources jar)

`BleManager` (`no/nordicsemi/android/ble/BleManager.java`):

| Member | Line | Use |
|---|---|---|
| `public BleManager(@NonNull Context context)` | 165 | `BleHubManager(appContext)` |
| `protected boolean isRequiredServiceSupported(@NonNull BluetoothGatt gatt)` | 237 | Vendor and SIG services present; collects every 2A25 for the DIS search |
| `protected void initialize()` | 225 | Enqueues the `HubInitPlan` steps, each with a `.fail {}` that records the failure (section 15, G5 item 5) |
| `protected void onServicesInvalidated()` | 279 | Nulls the characteristic references |
| `public final ConnectRequest connect(@NonNull BluetoothDevice device)` | 676 | With `ConnectRequest.useAutoConnect(false)` (`ConnectRequest.java:208`), `.retry(0)` (`:151`), `.timeout(10_000)` (`:87`) |
| `public final DisconnectRequest disconnect()` | 727 | `finally` path |
| `public void close()` | 308 | Cap path and end of every acquisition |
| `protected final void cancelQueue()` | 2194 | Cap path, before `close()` |
| `protected MtuRequest requestMtu(@IntRange(from = 23, to = 517) int mtu)` | 2000 | 517 on every connection. Note at 1997-1998: "Starting from version 2.7.3 the maximum packet size is 512 bytes, even if the MTU is set to 517." The largest envelope is 86 bytes (parent 5.3), so this is irrelevant here. |
| `protected int getMtu()` | 2017 | Checked against 89 after the request |
| `protected WriteRequest enableIndications(@Nullable BluetoothGattCharacteristic c)` | 1449 | CP, then ENV. Never SIG (G2). |
| `protected ReadRequest readCharacteristic(@Nullable BluetoothGattCharacteristic c)` | 1483 | DIS 2A25 and 2A28 |
| `protected WriteRequest writeCharacteristic(@Nullable BluetoothGattCharacteristic c, @Nullable byte[] data, @WriteType int writeType)` | 1531 | Control Point, `BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT` (with response) |
| `protected ValueChangedCallback setIndicationCallback(@Nullable BluetoothGattCharacteristic c)` | 882 | CP and ENV indications into one ordered channel |
| `protected WaitForValueChangedRequest waitForIndication(@Nullable BluetoothGattCharacteristic c)` | 993 | Not used (section 2.4) |

Class hierarchy (VERIFIED):

- `WriteRequest extends TimeoutableValueRequest` (`WriteRequest.java:51`);
- `ReadRequest extends TimeoutableValueRequest` (`ReadRequest.java:56`);
- `TimeoutableValueRequest extends TimeoutableRequest` (`TimeoutableValueRequest.java:46`);
- `ConnectRequest extends TimeoutableRequest` (`ConnectRequest.java:53`);
- `MtuRequest extends SimpleValueRequest` (`MtuRequest.java:39`), so it has no timeout.

`ble-ktx` (`no/nordicsemi/android/ble/ktx/`):

| Function | Line | Cancellable? |
|---|---|---|
| `suspend fun Request.suspend()` | `RequestSuspend.kt:29` | No: `suspendCoroutine` (`:374-391`) |
| `suspend fun TimeoutableRequest.suspend()` | `:41` | Yes: `suspendCancellableCoroutine` with `invokeOnCancellation { cancel() }` (`:393-414`) |
| `suspend fun WriteRequest.suspend(): Data` | `:54-60` | No |
| `suspend fun ReadRequest.suspend(): Data` | `:106-112` | No |
| `suspend fun MtuRequest.suspend(): Int` | `:197-203` | No |
| `suspend fun WaitForValueChangedRequest.suspend(): Data` | `:235-256` | Yes |
| `fun ValueChangedCallback.asFlow(): Flow<Data>` (`@ExperimentalCoroutinesApi`) | `ValueChangedCallbackExt.kt` | Flow |
| `fun BleManager.stateAsFlow(): Flow<ConnectionState>` | `BleManagerExt.kt` | Flow; `ConnectionState.Disconnected(reason)` with `LINK_LOSS`, `TIMEOUT` and so on (`state/ConnectionState.kt`) |

Exceptions thrown by the suspend extensions (VERIFIED, `RequestSuspend.kt:380-386,400-406`):

| Fail status | Exception |
|---|---|
| `REASON_BLUETOOTH_DISABLED` | `BluetoothDisabledException` |
| `REASON_DEVICE_DISCONNECTED` | `DeviceDisconnectedException` |
| `REASON_CANCELLED` (cancellable variants only) | `CancellationException` |
| Any other status (including `REASON_TIMEOUT` and `REASON_DEVICE_NOT_SUPPORTED`) | `RequestFailedException(request, status)` |
| Invalid request | `InvalidRequestException` |

### 2.3 Cancellability, the 25 s cap, and the decision it forces

The non-cancellable variants ignore coroutine cancellation, so `withTimeout` cannot pre-empt a
write, a read or an MTU request in flight. And `close()` does not fail the in-flight request: it
calls `emptyTasks(REASON_DEVICE_DISCONNECTED)`, which fails only the queued `initQueue` and
`taskQueue` entries (VERIFIED `BleManagerHandler.java:527-602`), then closes the GATT, after which no
callback arrives. A non-cancellable suspension parked on that request would never resume.

Decision:

- Connect, write and read use the cancellable `TimeoutableRequest.suspend()`, reached by
  upcasting, for example `(writeCharacteristic(...) as TimeoutableRequest).suspend()`. Each also
  gets an explicit `.timeout(ms)`. A read captures its value with `.with { _, data -> ... }`, as the
  ktx functions themselves do (`RequestSuspend.kt:107-111`). Both points are VERIFIED (section 15,
  G5 item 8): the upcast selects `TimeoutableRequest.suspend()`, and `.with {}` runs before `done`.
- `MtuRequest` gets a 12-line local `awaitCancellable()` helper of the same shape as
  `suspendCancellable` (`RequestSuspend.kt:393-414`): done resumes, fail maps to the exceptions
  above, and cancellation is allowed.
- The non-cancellable ktx variants are never called (G-B23b, a source scan).
- The cap path is `withTimeout(25_000)` around the exchange. On expiry it calls
  `cancelQueue()`, then `close()`, then rejects TIMEOUT.

### 2.4 How the queue serializes operations, and why indications go through a callback

- **One operation at a time.** `taskQueue` is a `LinkedBlockingDeque<Request>`
  (`BleManagerHandler.java:91`). `enqueue` appends and calls `nextRequest(false)` (`:1658-1665`),
  and `operationInProgress` (`:136`) admits one GATT operation at a time. Requests enqueued in
  `initialize()` go to `initQueue` (`:1660`), which runs before the device is reported ready.
  This is the library's request queue the parent memo relies on (D2, D7); no hand-rolled queue.
- **Init runs on every connection, and connect waits for it (VERIFIED, section 15, G5 item 5).**
  A failed init request does not fail the connect. Each init step therefore records its own failure,
  and `BleAcquisition` checks that record after `connect` returns (section 5.2 step 4).
- **Why not `waitForIndication`.** It is a "blocking request, so the next request will be executed
  after the indication was received" (`BleManager.java` KDoc before `:993`). Waiting for the CP
  response and then the envelope with two blocking waits would leave a window between them in which
  the envelope could arrive unobserved.
- **What is used instead.** `setIndicationCallback` on CP and ENV is installed in `initialize()`,
  before the CCCDs are enabled. Both callbacks write tagged events into one
  `Channel<HubEvent>(UNLIMITED)` per manager instance, so arrival order is kept (VERIFIED, section
  15, G5 item 6). Neither callback is given its own `setHandler`: both must post to the manager's
  one handler, on which that order rests.
- **One manager per acquisition.** A new `BleHubManager` is created for each acquisition and
  closed at its end, so no event outlives its session.

### 2.5 How a disconnect mid-operation surfaces (VERIFIED)

On `onConnectionStateChange` to disconnected, the handler calls
`emptyTasks(REASON_DEVICE_DISCONNECTED)` and fails the current request with
`REASON_DEVICE_DISCONNECTED`, or with the GATT status when it is not success
(`BleManagerHandler.java:2300-2339`). The awaiting request is failed the same way (`:2337-2340`), and
`ConnectionObserver.onDeviceDisconnected(device, reason)` fires. The suspended call therefore throws
`DeviceDisconnectedException` (or `RequestFailedException` with a GATT status).
`BleAcquisition` maps both to UNREACHABLE (section 5.4).

A device that fails `isRequiredServiceSupported` is disconnected, and the connect request fails
with `REASON_DEVICE_NOT_SUPPORTED` (`:2346-2347`). That is mapped to HUB_MISMATCH, because a
peripheral without the vendor service is the wrong peripheral.

---

## 3. CompanionDeviceManager (VERIFIED on developer.android.com unless marked)

### 3.1 Request construction (vendor service UUID key)

```kotlin
val filter = BluetoothLeDeviceFilter.Builder()
    .setScanFilter(
        ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(UUID.fromString("f4610001-95ad-4b7d-a8ed-b568b5b0e927")))
            .build()
    )
    .setNamePattern(Pattern.compile("^SaMD-${Pattern.quote(hubId)}$"))
    .build()
val request = AssociationRequest.Builder()
    .addDeviceFilter(filter)
    .setSingleDevice(true)
    .build()
```

- `BluetoothLeDeviceFilter.Builder.setScanFilter(ScanFilter)` and `setNamePattern(Pattern)` were
  both added in API 26 (`BluetoothLeDeviceFilter.Builder` reference).
- `addDeviceFilter` and `setSingleDevice(true)` come from the companion-device-pairing guide.
- The hub advertises the vendor UUID in its service list (VERIFIED SaMDPi `ble_hub.py:355`:
  `Advertisement(profile.ble.local_name, [service_uuid, VENDOR_SERVICE], ...)`, with
  `VENDOR_SERVICE` at `:71`), and its local name is `SaMD-kernelhub2`
  (`profiles/kernelhub2.toml`).
- The name pattern assumes the convention `local_name == "SaMD-" + hub_id`. That holds for
  kernelhub2 but SaMDPi's profile schema does not enforce it. Q9 (section 15): a SaMDPi guard
  (`local_name == "SaMD-" + hub_id`) is filed for a later SaMDPi PR. The pattern only filters the
  CDM dialog; identity is still proved by the DIS serial (section 5.2 step 6).

### 3.2 `associate()` overloads and callbacks by API level

| API | Overload | Callback that delivers the IntentSender |
|---|---|---|
| 33 and up | `associate(AssociationRequest, Executor, CompanionDeviceManager.Callback)`, added in API 33 | `onAssociationPending(IntentSender)`, API 33. Then `onAssociationCreated(AssociationInfo)`, API 33. |
| 26 to 32 | `associate(AssociationRequest, CompanionDeviceManager.Callback, Handler)`, added in API 26 | `onDeviceFound(IntentSender)`, deprecated in 33 ("renamed to onAssociationPending() ... functionally equivalent") |

- Failure arrives in `onFailure(CharSequence)` (abstract), and on API 36 also in
  `onFailure(int errorCode, CharSequence)` with `RESULT_USER_REJECTED`,
  `RESULT_DISCOVERY_TIMEOUT`, `RESULT_INTERNAL_ERROR` or `RESULT_CANCELED`.
- Both overloads say: "Calling this API requires a uses-feature
  PackageManager.FEATURE_COMPANION_DEVICE_SETUP declaration in the manifest".

**Decision (Q1, section 15):** BLE acquisition requires API 33 or higher. Below 33 the BLE source
rejects `BLUETOOTH_UNAVAILABLE` without touching Bluetooth. This is a dev-only decision, to be
revisited before any release use.

- The only hardware is the SDK 36 handset, so the 26 to 32 path could only be compiled, never run.
- Two of the calls it needs are 33-only anyway: `getMyAssociations()` and
  `AssociationInfo.getDeviceMacAddress()`.

The rest of this section assumes the 33+ path.

### 3.3 IntentSender launch and result

- `Callback` reference: applications launch the UI via
  `Activity.startIntentSenderForResult(...)`. On confirmation, the AssociationInfo is delivered
  "both via `onAssociationCreated(AssociationInfo)` and via `Activity.setResult(int, Intent)`",
  with `RESULT_OK` and the extra `CompanionDeviceManager.EXTRA_ASSOCIATION`
  (`"android.companion.extra.ASSOCIATION"`, API 33).
- `BlePrepActivity` uses
  `registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult())` with
  `IntentSenderRequest.Builder(intentSender).build()`. The background-starts page says
  `ActivityResultLauncher<IntentSenderRequest>` "uses Context.startIntentSender() internally and is
  therefore affected by BAL restrictions". `BlePrepActivity` is visible when it launches, so the
  visible-window exception applies (section 3.6).
- `onAssociationCreated` is the primary signal. The activity result is the secondary one, used only
  to detect `RESULT_CANCELED`.
- `EXTRA_DEVICE` is deprecated in API 33 ("use AssociationInfo.getAssociatedDevice() instead") and
  is not used.

### 3.4 Finding a persisted association, and getting the `BluetoothDevice`

- `getMyAssociations(): List<AssociationInfo>`, API 33.
- `AssociationInfo.getDeviceMacAddress(): MacAddress?`, API 33.
- `AssociationInfo.getAssociatedDevice()`, API 34: "Note that this field is not persisted across
  sessions". It is not usable on the next Start.

- `AssociationInfo.getId(): int`, API 33: "the unique ID of this association record".
- `disassociate(int associationId)`, API 33: "Remove an association."

The association is found by its ID, never by its name (G1). At `onAssociationCreated(info)`,
`HubAssociations` persists `hub_id` to `info.getId()` in dev-only storage (a private
`SharedPreferences` file created from `src/dev` code). On each Start:

1. Read the stored ID for the assigned `hub_id`, and find the `AssociationInfo` with that ID in
   `getMyAssociations()`. If no ID is stored, or no association has that ID, run the CDM flow again
   (section 3.6). `getDisplayName()` is used only for logging.
2. Turn its MAC into a device with
   `bluetoothManager.adapter.getRemoteDevice(mac.toString().uppercase())`. `BluetoothDevice`
   reference: "use `BluetoothAdapter.getRemoteDevice(String)` to create one representing a device of
   a known MAC" (`BluetoothDevice.txt:30`).
3. The hub's address is stable: it keeps its public address with Privacy off (A16; PR #3 live check
   vi, "address fixed across scans").

### 3.5 BLUETOOTH_CONNECT, and a dismissed dialog

- **BLUETOOTH_CONNECT is still required.** `BluetoothDevice.connectGatt`: "For apps targeting
  Build.VERSION_CODES.S or higher, this requires the Manifest.permission.BLUETOOTH_CONNECT
  permission". The CDM guide and the Bluetooth-permissions page document an exemption from
  location permissions only. So CDM association does not remove the `BLUETOOTH_CONNECT`
  requirement (INFERRED from the absence of any documented exemption; G-B live check 1 shows the
  prompt).
- **A dismissed dialog.** The `Callback` reference: if the user rejects the association after the UI
  launched, the outcome is `onFailure(...)` and also `Activity.setResult(RESULT_CANCELED)`. The
  phone maps a rejection or dismissal to `NOT_SUPPORTED` with the BLE text "This instrument has not
  been paired with the app yet." (parent 7.7, keyed by transport per B5). No field is written.
  Nothing is retried.
- **Location Services.** The guide says "companion device pairing requires Location Services to be
  enabled". A CDM failure with Location Services off is mapped to `BLUETOOTH_UNAVAILABLE`, and the
  text names Bluetooth, Location and the Nearby devices permission (Q4, section 9).

### 3.6 B1 verification: starting `BlePrepActivity` from the application context

- **Allowed while the app is visible.** The background-starts page lists, under "When background
  activity starts are allowed": "The app has a visible window, such as an activity in the
  foreground." Start is tapped on the visible `CompounderScreen`, so the app has a visible window at
  that moment and the start is not a background launch. No `SYSTEM_ALERT_WINDOW` or
  PendingIntent opt-in is needed.
- **NEW_TASK flag.** `Context.startActivity`: "if this method is being called from outside of an
  Activity Context, then the Intent must include the Intent.FLAG_ACTIVITY_NEW_TASK launch flag"
  (VERIFIED `Context` reference). The bridge sets it. INFERRED: with the default task affinity the
  activity joins the app's existing task.
- **targetSdk 37 task-hijack rules.** These are opt-in (`allowCrossUidActivitySwitchFromBelow`), and
  both activities share one UID, so they do not block this.
- **How the result returns.** `BlePrepBridge` is a process-scoped singleton holding one
  `CompletableDeferred<PrepResult>` per request id.
  - `BleHubVitalsSource` awaits it with a 120 s preparation ceiling, outside the 25 s cap (Q2).
  - `BlePrepActivity` completes it from its permission or association result and calls `finish()`.
- **If the worker leaves the screen.**
  - `onCleared` cancels `acquisitionJob` (`CompounderViewModel.kt:609-611`). The awaiting coroutine
    is cancelled, and `invokeOnCancellation` marks the request abandoned.
  - The activity observes that flag in `onResume` and on completion, and finishes without acting.
    An association the user completes after abandonment is kept (harmless; used next time), but no
    acquisition proceeds.
- **Process death.** The deferred is gone. A recreated `BlePrepActivity` finds no pending request id
  and finishes immediately. The acquisition was already lost with the process (parent 7.5).
- **Fallback not needed.** The generic "prepare acquisition" seam in `src/main` is not needed.

---

## 4. Permissions and manifest (`app/src/dev/AndroidManifest.xml` only)

### 4.1 Exact entries added (the existing receiver and cleartext setting stay)

```xml
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-feature android:name="android.hardware.bluetooth_le" android:required="false" />
<uses-feature android:name="android.software.companion_device_setup" android:required="false" />

<application>
    <activity
        android:name=".data.vitalssource.ble.BlePrepActivity"
        android:exported="false"
        android:excludeFromRecents="true"
        android:theme="@android:style/Theme.Translucent.NoTitleBar" />
</application>
```

| API | Declared | Runtime request | Note |
|---|---|---|---|
| 26 to 32 | nothing used | none | BLE source rejects `BLUETOOTH_UNAVAILABLE` below 33 (section 3.2). The Nordic AAR still merges `BLUETOOTH` with max 30 into dev; harmless. |
| 31 and up | `BLUETOOTH_CONNECT` (also merged from the Nordic AAR; declared explicitly so the app does not depend on the library for it) | yes, from `BlePrepActivity`, before the first connect | The "Nearby devices" prompt (Bluetooth-permissions page) |
| all | the two `uses-feature` entries, `required="false"` | none | `companion_device_setup` is required by `associate()` and `getMyAssociations()`. INFERRED: `required="false"` satisfies "a uses-feature declaration". |
| none | `BLUETOOTH_SCAN` | none | Q10: declared only if CDM proves insufficient on the iQOO |

No location permission. `ACCESS_LOCAL_NETWORK` stays in `src/main` (`AndroidManifest.xml:12`).

### 4.2 Runtime flow and the local-network wrapper

Start goes through the existing `rememberLocalNetworkPermissionAction` (`CompounderScreen.kt:281`),
which asks for `ACCESS_LOCAL_NETWORK` only on SDK 37 or later (`PermissionAction.kt:63`). On the
SDK 36 handset it calls `onStartAcquisition` directly. The BLE source then:

1. checks `BluetoothManager.adapter?.isEnabled`; if there is no adapter or it is off, it rejects
   `BLUETOOTH_UNAVAILABLE` (VERIFIED callable without `BLUETOOTH_CONNECT`, section 15, G5 item 9);
2. finds the association (section 3.4); if there is none, it runs the CDM flow through
   `BlePrepActivity`;
3. if `checkSelfPermission(BLUETOOTH_CONNECT)` is not granted, requests it through `BlePrepActivity`;
   if denied, it rejects `BLUETOOTH_UNAVAILABLE`;
4. starts the 25 s BLE exchange (section 5).

Interaction on SDK 37 and later (not the current handset): the wrapper prompts for local-network
access even before a BLE start, and a denial there shows the local-network text. It is harmless but
inaccurate. Q3 (section 15): filed as a follow-up, not fixed in PR-B.

---

## 5. BLE acquisition sequence

### 5.1 Structure

`BleHubVitalsSource.startAcquisition(request)` resolves the assignment entry (section 7), runs
preparation (sections 3 and 4), then runs `BleAcquisition.run(link, request, hubId)`.
`BleAcquisition` is pure Kotlin over `HubLink`:

- `connect(timeoutMs)`
- `initPlan(steps)`
- `disSerialCandidates(): List<CharRef>`
- `read(ref)`
- `writeControlPoint(bytes)`
- `events: ReceiveChannel<HubEvent>`
- `abort()` (cancelQueue plus close)
- `close()`

`BleHubManager` implements `HubLink` and contains no decision logic.

### 5.2 Steps (each with its requirement)

| # | Step | Timeout | Failure maps to |
|---|---|---|---|
| 1 | `nonce = UUID.randomUUID()`. `nonceBytes` is the 16 big-endian bytes; `nonceString = nonce.toString()`, which is lowercase canonical (B7, 14.5 item 15). | | |
| 2 | `connect(device)` with `useAutoConnect(false)`, `retry(0)` | 10 s | Disconnect or GATT error: UNREACHABLE. `REASON_DEVICE_NOT_SUPPORTED`: HUB_MISMATCH. `RequestFailedException(REASON_TIMEOUT)`: UNREACHABLE. |
| 3 | `isRequiredServiceSupported` requires the vendor service with CP `f4610002-...` and ENV `f4610003-...`, and the SIG service for the instrument (0x1810/0x2A35 BP, 0x1822/0x2A5E SPO2, 0x1809/0x2A1C THERMOMETER). It collects every 2A25 across every 0x180A service. | | Missing: HUB_MISMATCH (step 2 path) |
| 4 | Init plan, run in `initialize()` on **every** connection, in exactly this order: `RequestMtu(517)`; `EnableIndications(CP)`; `EnableIndications(ENV)`. The phone does not enable the SIG characteristic's CCCD (G2). This satisfies 14.5 items 1 and 6: CCCDs and MTU before any Control Point write, on every connect and reconnect, because each acquisition is a fresh connection and the plan runs before the device is ready. A failed init step does not fail the connect (section 15, G5 item 5), so each step's `.fail {}` records the failure and `BleAcquisition` checks the record after `connect` returns. | per step 3 s | Recorded enable failure: MALFORMED |
| 5 | `getMtu() >= 89` | | MALFORMED |
| 6 | DIS search: `DisSearch.pick(candidates)` must return exactly one 2A25 across all 0x180A instances (14.5 item 2; BlueZ's own 0x180A has none, parent 14.1 item 10). Read it; its ASCII value must equal the assigned `hub_id` exactly. Read 2A28 from the same service for audit. | 3 s each | 0 or more than one, or a mismatch: HUB_MISMATCH. On a serial mismatch only, `disassociate(id)` and delete the stored ID, so the next Start asks the human again (Q10). |
| 7 | Drain `events` (anything before START is stale) | | |
| 8 | Write START `01 <instrument code> <scenario code> <nonce 16>`, 19 bytes, with response | 3 s | Write failure: UNREACHABLE |
| 9 | Read `events` in order. An ENV event before the CP response is discarded (G-B3). The first CP event must be exactly `80 01 <result>`. | 3 s | Section 5.3 |
| 10 | Result `0x01`: read `events` until the first ENV event after the response | 10 s | TIMEOUT |
| 11 | `phoneReceivedAt = Instant.now()`. Parse the envelope (section 8.3). | | Section 8.3 |
| 12 | Envelope `hub_id` must equal the assigned `hub_id` (14.5 item 3) | | HUB_MISMATCH. The association is kept (Q10 applies only to a DIS-serial mismatch). |
| 13 | Decode the SIG bytes for the requested instrument (section 8.2). Any present field holding a special value, with quality OK: QUALITY_STATUS_NOT_OK (G-B4). | | MALFORMED for a structure error |
| 14 | Build the DTO (section 5.5) and call `PiMeasurementMapper.map(dto, request.instrument, nonceString)`. RC-2, then RC-1 session (the formatted echo against `nonceString`), then RC-1 device type. | | Mapper reasons |
| 15 | `finally`, under `withContext(NonCancellable)`: if START was answered SUCCESS, write STOP `02 <nonce 16>` with a 2 s bound, and ignore its result. Then `disconnect()` with a 2 s bound, then `close()`. | | Never surfaces |

- **The cap.** Steps 2 to 14 run inside `withTimeout(25_000)` (parent 7.4). On expiry: `abort()`,
  then TIMEOUT. A Stop or screen exit cancels the job, and step 15 still runs.
- **No reconnect, no backoff, no foreground service.** One connect per Start (parent 7.5). A
  disconnect anywhere in steps 3 to 14 throws `DeviceDisconnectedException` (section 2.5), which
  maps to UNREACHABLE, and no field is written (G-B9).

### 5.3 Control Point result codes (VERIFIED `control_point.py:44-60`)

| Code | Hub meaning | RejectReason | Source |
|---|---|---|---|
| 0x01 SUCCESS | | continue | |
| 0x02 OPCODE_NOT_SUPPORTED | | MALFORMED | contract mismatch |
| 0x03 INVALID_PARAMETER | Bad length, scenario or instrument code, all-zero or reused nonce, wrong STOP nonce (`:271-280,294-300`) | MALFORMED | A fresh `randomUUID` cannot be reused in practice |
| 0x04 INSTRUMENT_NOT_SERVED | | HUB_MISMATCH | parent 7.7 |
| 0x05 BUSY | Another device owns the hub, or an intruder is connected (`:216`) | UNREACHABLE (BLE text) | B5 |
| 0x06 NOT_RUNNING | STOP only | ignored (STOP is best effort) | |
| 0x07 MTU_TOO_SMALL | | MALFORMED | parent 7.7 |
| 0x08 CCCD_NOT_ENABLED | | UNREACHABLE (BLE text) | 14.5 item 7; Q6 |
| any other value, or a response not 3 bytes, not `0x80`, or for another opcode | | MALFORMED | |

The hub's START check order is: owner, length, codes, nonce, instrument, MTU, CCCD (`:216,271-286`).
A reused nonce on a link whose CCCD is off therefore answers 0x03 before 0x08 (parent 14.5 item 7).

### 5.4 Exception mapping

| Exception | Reason |
|---|---|
| `BluetoothDisabledException` | BLUETOOTH_UNAVAILABLE |
| `DeviceDisconnectedException` | UNREACHABLE |
| `RequestFailedException(status = REASON_DEVICE_NOT_SUPPORTED)` | HUB_MISMATCH |
| `RequestFailedException(status = REASON_TIMEOUT)` | UNREACHABLE on connect; TIMEOUT on a later step |
| `RequestFailedException`, any other status | UNREACHABLE |
| `InvalidRequestException` | MALFORMED |
| `SecurityException` (permission revoked mid-run) | BLUETOOTH_UNAVAILABLE |
| `TimeoutCancellationException` from the cap | TIMEOUT |
| `CancellationException` | rethrown, as today (`PiGatewayVitalsSource.kt:91-94`) |

### 5.5 DTO built from BLE

| `NormalizedMeasurementDto` field | Value |
|---|---|
| `deviceType` | `PiMeasurementMapper.expectedDeviceType(envelope.instrument)`, from the envelope's own instrument code, so RC-1's device-type gate really compares |
| `qualityStatus` | Envelope quality name (`OK`, `WARNING`, `ERROR`, `UNAVAILABLE`) |
| `provenance["session_id"]` | Echoed nonce bytes formatted as a lowercase canonical UUID string |
| BP | `primaryValue` = systolic, `secondaryValue` = diastolic, `tertiaryValue` = pulse (null when flag bit2 is clear). MAP is decoded and discarded, matching the mapper (`PiMeasurementMapper.kt:56-60`). |
| SPO2 | `primaryValue` = SpO2, `secondaryValue` = pulse |
| THERMOMETER | `primaryValue` = Celsius |
| `measuredAt` | Envelope `device_time` as ISO-8601 UTC (advisory) |
| `receivedAt` | `phoneReceivedAt` (authoritative, parent 3.2) |
| `synthetic` | `true` (the parser already refused emulated = 0) |
| new `hubId` | Envelope `hub_id` |
| `Accepted.emulatorBuild` | The DIS 2A28 string read in step 6 (Q7) |

---

## 6. Wi-Fi side

### 6.1 `hub_id` on every response (B9, 14.5 item 3)

`HubIdInterceptor(expectedHubId)` is installed on `piGatewayOkHttpClient` (`PiGatewayNetworkModule.kt:65-71`).
For every response, 2xx or not:

- read `response.header("X-SaMDPi-Hub-Id")`;
- if it is missing or not equal to `expectedHubId`, close the body and throw
  `HubIdMismatchException`.

`HubIdMismatchException` extends `IOException`. INFERRED: OkHttp 4.12 propagates only an
`IOException` from an interceptor cleanly on an asynchronous call (Retrofit `suspend` uses
`enqueue`). It is proved by G-B16 on MockWebServer.

`PiGatewayVitalsSource.toRejectReason` matches `HubIdMismatchException` before `IOException`, as
`SocketTimeoutException` is matched today (`PiGatewayVitalsSource.kt:95-97`). The expected `hub_id`
is the Wi-Fi entry of the assignment (section 7.1). The hub sets the header on every response,
errors included (VERIFIED `server.py:198-216`).

### 6.2 Body and header agreement

- Every response DTO gains `@SerializedName("hub_id") val hubId: String?`.
- After a 2xx is parsed, `hubId` must equal the expected value (equal to the header by then);
  otherwise HUB_MISMATCH.
- For a non-2xx, `errorBody` is parsed as `{error, hub_id, ...}` with the same Gson. A `hub_id` that
  disagrees, or a body that cannot be parsed, maps to HUB_MISMATCH and MALFORMED respectively.
- The header and body come from one `_send_json` call on the hub (`server.py:202,211`), so a
  disagreement means a proxy or a different server; refuse.

### 6.3 Error bodies (B8; VERIFIED `server.py`)

| Status, `error` | Where | Reason |
|---|---|---|
| 400 `INSTRUMENT_NOT_SERVED` | `:291` | HUB_MISMATCH |
| 400 `INVALID_SESSION_CONFIG` | `:294-299,354` | MALFORMED |
| 400 `MALFORMED_REQUEST` | `:315-321` | MALFORMED |
| 400 `MALFORMED_JSON` | `:331` | MALFORMED |
| any status `HTTP_ERROR` (stdlib `send_error`: bad request line, 501 method, 431 headers) | `:218-232` | MALFORMED: the hub could not parse what the phone sent, a client or contract fault, not a transport one |
| 404 `NO_MEASUREMENT_AVAILABLE` | `:273` | NO_MEASUREMENT (unchanged, `PiGatewayVitalsSource.kt:69-71`) |
| 404 `NOT_FOUND` | `:242,285,306` | MALFORMED (wrong path: contract mismatch) |
| any other status or `error` value | | MALFORMED |

### 6.4 `emulator_build`

`@SerializedName("emulator_build") val emulatorBuild: EmulatorBuildDto?`, where
`data class EmulatorBuildDto(val sha: String?, val dirty: Boolean?, val merged: Boolean?)`
(VERIFIED `hubctx.py:56-57`, `server.py:269`; 14.5 item 4). It is decoded and is not a gate. It is
audited (Q7): `Accepted.emulatorBuild` carries `sha` plus the `dirty` and `merged` flags as one
string, and the RECEIVED payload records it.

### 6.5 Retrofit headers

`Response<T>.headers()["X-SaMDPi-Hub-Id"]` is available to tests and to the source. The gate itself
is the interceptor, so no call path can skip it.

---

## 7. Routing

### 7.1 `PI_HUB_ASSIGNMENT` (Q3)

- **Grammar.** `entry ( "," entry )*`, where `entry = INSTRUMENT "=" TRANSPORT ":" HUB_ID`.
  - `INSTRUMENT` is one of the `Instrument` names (`VitalsSource.kt:30`).
  - `TRANSPORT` is `wifi` or `ble`.
  - `HUB_ID` fullmatches `^[a-z0-9-]{3,32}$` (SaMDPi `profile.py:22`).
  - No whitespace anywhere.
- **Default.** `"SPO2=wifi:kernelhub1,BP=ble:kernelhub2"`.
- **Refusals (fail closed):**
  - empty string;
  - unknown instrument or transport;
  - a malformed or regex-failing `hub_id`;
  - a duplicate instrument;
  - more than one distinct Wi-Fi `hub_id` (B2);
  - a `ble` entry for anything but BP, SPO2 or THERMOMETER (PR #3 STOP 2 ruling 3);
  - any whitespace.
- **Two layers, as `samd.dev.kernelFallback` already does** (`build.gradle.kts:121-132`,
  `DevKernelFallbackMode.kt`):
  1. `build.gradle.kts` validates the `local.properties` value at configuration time and throws
     `GradleException` with the exact rule broken;
  2. `HubAssignment.parse` applies the same rules at runtime. If the runtime parse ever fails,
     every acquisition is `Rejected(NOT_SUPPORTED)`. There is no partial assignment.
- **Drift between the layers is caught on the generated values (G3).** G-B26 tests
  `BuildConfig` itself, not a copied function: `BuildConfig.PI_HUB_ASSIGNMENT` must parse with the
  runtime `HubAssignment.parse`, so a value Gradle accepted that the runtime rejects fails the tests;
  and the default build's `BuildConfig.PI_GATEWAY_BASE_URL` must equal
  `http://kernelhub1.local:8090/`.
- **B2 base URL.** If `PI_GATEWAY_BASE_URL` is set in `local.properties`, use it (laptop desk work:
  `http://127.0.0.1:8090/` with `SPO2=wifi:laptop-docker`). Otherwise, if there is a Wi-Fi entry,
  use `http://<hub_id>.local:8090/`. Otherwise use a fixed placeholder that is never called.
  Port 8090 is the profile port (SaMDPi `profiles/kernelhub1.toml`) and the Avahi port.

### 7.2 `RoutingVitalsSource` (the single binding)

```text
startAcquisition(r) = when (val e = assignment[r.instrument]) {
    null      -> Rejected(NOT_SUPPORTED)                                 // no transport, no hubId
    is Wifi   -> wifi.startAcquisition(r).stamped(WIFI, e.hubId)
    is Ble    -> ble.startAcquisition(r, e.hubId).stamped(BLE, e.hubId)
}
```

- **No fallback** from one transport to the other (G-B1).
- **Stop routing.** `lastStarted` (transport) is set before delegating. `stopAcquisition()` calls
  only that transport's stop, then clears it. With nothing started it is a no-op.
- **`readVitals`** returns `VitalsReading()` and calls neither delegate (G-B8). Both delegates
  already make zero calls there (`PiGatewayVitalsSource.kt:39`).

---

## 8. Decoding

### 8.1 SFLOAT and FLOAT (parent 5.1, 5.4; VERIFIED fixture)

- **SFLOAT (16-bit, little endian):** exponent is bits 15 to 12 (signed 4), mantissa is bits 11 to 0
  (signed 12); value = mantissa x 10^exponent.
- **FLOAT (32-bit):** exponent is bits 31 to 24 (signed 8), mantissa is bits 23 to 0 (signed 24).
- **Special values.** Any raw word in this table is a special value, not a number:

| Special value | SFLOAT | FLOAT |
|---|---|---|
| NaN | 0x07FF | 0x007FFFFF |
| NRes | 0x0800 | 0x00800000 |
| +INF | 0x07FE | 0x007FFFFE |
| -INF | 0x0802 | 0x00800002 |
| RFU | 0x0801 | 0x00800001 |

- **Values are decoded to `Double`** with `BigDecimal(mantissa).scaleByPowerOfTen(exp).toDouble()`,
  so 93.3 does not become 93.30000000000001. The mapper rounds integer vitals half-up and passes
  temperature through (`PiMeasurementMapper.kt:67-70,101-104`).

### 8.2 SIG characteristics (strict; unexpected flags are MALFORMED)

| Characteristic | Allowed flags | Layout | Reject |
|---|---|---|---|
| 0x2A35 BP | bit0 = 0 (mmHg), bit1 timestamp, bit2 pulse | flags, sys, dia, MAP (SFLOAT each), [7-byte timestamp], [pulse SFLOAT] | kPa (bit0 = 1), user id (bit3), status (bit4), any length other than the computed one |
| 0x2A5E PLX spot-check | bit0 timestamp only | flags, SpO2, PR (SFLOAT), [timestamp] | bits 1 to 4, length mismatch |
| 0x2A1C HTS | bit0 = 0 (Celsius), bit1 timestamp, bit2 type | flags, FLOAT, [timestamp], [type] | Fahrenheit (bit0 = 1), length mismatch |

Golden vectors: every `sfloat`, `float`, `timestamp`, `bp`, `plx_spot_check` and `thermometer`
entry of the fixture, the fault vectors included (all-NaN present fields).

### 8.3 Envelope parser (B6): the exact rejection order

1. Length < 35: MALFORMED ("too short"; `envelope.py:104`).
2. `envelope_version != 1`: MALFORMED (`:107`).
3. Unknown instrument code (not 1 to 6): MALFORMED (`:109`).
4. Unknown quality code (not 0 to 3): MALFORMED (`:111`).
5. `sequence == 0` (u32 little endian at offset 21): MALFORMED (`:113`).
6. Reserved flag bits set (`flags & 0xFE != 0`): MALFORMED (`:115`).
7. Emulated bit clear: MALFORMED (`:117`).
8. `n` (offset 33) outside 3 to 32: MALFORMED (`:120`).
9. Truncated before the SIG length: MALFORMED (`:123`).
10. `hub_id` not ASCII: MALFORMED (`:126-128`).
11. `hub_id` does not fullmatch `^[a-z0-9-]{3,32}$`: MALFORMED (`:129`). Kotlin `Regex.matches`
    is a full match. The `^...$` anchors are kept and `\n` is excluded by the class, so a trailing
    newline cannot pass, unlike Python `re.match` (R3 S10).
12. `m == 0`: MALFORMED (`:132`).
13. Truncated SIG: MALFORMED (`:135`).
14. Trailing bytes: MALFORMED (`:137`).
15. **Phone only:** `profile_version != 1`: MALFORMED (B6), checked last so that rules 1 to 14 keep
    SaMDPi's order.

### 8.4 Golden vectors in JVM tests (B11)

- The copy goes to `app/src/testDev/resources/ble_golden_vectors.json` and is read with
  `javaClass.classLoader.getResource(...)`. It is under `app/src`, so it is already a Gradle test
  input (`build.gradle.kts:323`).
- `GoldenFixturePinTest` computes the sha256 of the resource bytes and compares it to
  `e82aea4dfd9f4388c77a11ed58fc25c6e08598af05597c5eccb822a6ac96bffc`. On failure it says: "copy
  tests/fixtures/ble_golden_vectors.json byte-identically from SaMDPi bba2c6b, do not edit it here".
- The envelope vector (61 bytes, version-6 nonce) parses, and its session id formats as
  `00112233-4455-6677-8899-aabbccddeeff` (B7).

---

## 9. UI and strings

- **Q14 wording.** Card banner "Emulated instrument. Synthetic values, not a real measurement."
  Field supporting text "Emulated" on every field whose provenance is `DEVICE` while
  `uiState.synthetic == true`. A field the worker edits becomes `DEVICE_EDITED` and loses the label
  (parent section 8 says "every field written with DEVICE provenance").
- **B12.** `synthetic` is set only in `applyAcceptedReading`, from `accepted.synthetic == true`, and
  is replaced by the next accepted reading. A rejection leaves it unchanged, because the fields it
  labels are unchanged.
- **B10 strings.** Every rejection text moves to `strings.xml`. The copy object returns `@StringRes`.

| Key (proposed) | Text | Status |
|---|---|---|
| `acq_reject_unreachable_wifi` | "Cannot reach the device gateway. Check it is powered on and on the same Wi-Fi." | existing, `CompounderViewModel.kt:195` |
| `acq_reject_permission_denied_wifi` | "Local network access is off for this app. Allow it in system settings to reach the device gateway." | existing, `:193` |
| `acq_reject_unreachable_or_blocked_wifi` | the `LocalNetworkFailure.kt:20-22` text | existing |
| `acq_reject_timeout_wifi` | "The device gateway did not answer in time. Try again." | existing, `:202` |
| `acq_reject_no_measurement` | existing `:203` | existing |
| `acq_reject_quality` | existing `:205` | existing |
| `acq_reject_mismatch` | existing `:207` | existing |
| `acq_reject_malformed` | existing `:208` | existing |
| `acq_reject_not_supported` | existing `:209` | existing |
| `acq_reject_bluetooth_unavailable` | "Bluetooth or Location is off, or this app is not allowed to use Nearby devices. Turn them on, allow Nearby devices, then try again." | new, parent 7.7; text ruled in Q4. BLE only: the Wi-Fi path never produces this reason. |
| `acq_reject_hub_mismatch` | "That reading came from a different instrument hub, so nothing was filled in." | new, parent 7.7 |
| `acq_reject_unreachable_ble` | "Cannot connect to the instrument. Move closer and check it is switched on. If another phone is using it, wait and try again." | new, B5; approved (Q4) |
| `acq_reject_timeout_ble` | "The instrument did not answer in time. Try again." | new; approved (Q4) |
| `acq_reject_not_paired_ble` | "This instrument has not been paired with the app yet." | new, parent 7.7 |
| `emulated_instrument_banner` | "Emulated instrument. Synthetic values, not a real measurement." | Q14 |
| `emulated_field_supporting_text` | "Emulated" | Q14 |

- **Selection rules.** For transport `null` or WIFI, UNREACHABLE and PERMISSION_DENIED go through
  `classifyLocalNetworkFailure` as today (`CompounderViewModel.kt:190-197`). For BLE,
  UNREACHABLE, TIMEOUT and NOT_SUPPORTED use the `_ble` keys; every other reason uses the shared key.
- **`captureMethod` is untouched.** No acquisition path writes it. Today only `:388` (the worker)
  and `:419` (the demo fill) do (G-B10).
- **G-B12.** An androidTest Compose test in `CompounderScreenTest` (exists): with
  `synthetic = true` and BP fields at `DEVICE`, the banner and both field labels render; with
  `synthetic = false`, neither does.

---

## 10. Tests

JVM means `./gradlew :app:testDevDebugUnitTest` (or `testDebugUnitTest` for `app/src/test`).
androidTest means install plus `am instrument` on the iQOO, never a Gradle connected task. Every row
is mutation-proven with the standing protocol (parent section 11): snapshot plus sha256, break, one
test RED, restore from the snapshot, sha256 identical, GREEN.

| ID | Asserts | Kind, collaborator | Mutation that must turn it RED |
|---|---|---|---|
| G-B1 | BP to BLE, SPO2 to Wi-Fi, unassigned is NOT_SUPPORTED; a BLE failure never invokes the Wi-Fi fake | JVM, two fake `VitalsSource` | Route a BLE reject on to `wifi` |
| G-B2 | Envelope nonce differs from the sent one: SESSION_ID_MISMATCH, no Accepted | JVM, `FakeHubLink` | Pass the envelope's own nonce as `expectedSessionId` |
| G-B3 | ENV before the CP response is discarded, then TIMEOUT | JVM, `FakeHubLink`, virtual time | Accept the first ENV regardless of order |
| G-B4 | Quality not OK gives QUALITY_STATUS_NOT_OK. Each special word in each present field with quality OK also gives QUALITY_STATUS_NOT_OK. | JVM | Delete the special-value check |
| G-B5 | DIS serial mismatch, or envelope `hub_id` mismatch: HUB_MISMATCH. Only the DIS mismatch disassociates and deletes the stored ID (Q10). | JVM | Skip the DIS comparison; separately, skip the envelope comparison; separately, disassociate on the envelope mismatch |
| G-B6 | Emulated bit 0: MALFORMED | JVM, `HubEnvelope` | Drop rule 7 |
| G-B7 | Golden vectors decode exactly | JVM, fixture | SFLOAT exponent unsigned |
| G-B8 | `readVitals` makes zero BLE and zero HTTP calls | JVM, counting fakes plus MockWebServer request count | Delegate `readVitals` to `wifi` |
| G-B9 | Disconnect mid-exchange: UNREACHABLE within the cap, no Accepted | JVM, `FakeHubLink` throws `DeviceDisconnectedException` | Map it to TIMEOUT |
| G-B10 | An Accepted BLE reading leaves `captureMethod` null | JVM, `CompounderViewModelTest` | Write `DIGITAL_MONITOR` in `applyAcceptedReading` |
| G-B11 | `src/main/AndroidManifest.xml` has no Bluetooth permission | JVM, text | Add `BLUETOOTH_CONNECT` to the main manifest |
| G-B11b | No forbidden token in `src/main`, `src/staging` or `src/prod` (section 1.3) | JVM, text | Add `import android.bluetooth.BluetoothAdapter` to a main file; one row per token |
| G-B12 | Banner and labels render only when `synthetic` | androidTest, `CompounderScreenTest` | Render the banner unconditionally |
| G-B13 | CCCDs before any CP write, on every connection: `HubInitPlan.steps()` is exactly `[RequestMtu(517), Enable(CP), Enable(ENV)]` (G2); over two acquisitions on `FakeHubLink`, each connection's call log has `enable(CP)` and `enable(ENV)` before its first `writeControlPoint`, and no `enable(SIG)`; a recorded init-step failure gives MALFORMED and no START is written | JVM | Move `Enable(CP)` after the START write; separately, run the plan only on the first connection; separately, append `Enable(SIG)`; separately, ignore the recorded init failure |
| G-B14 | MTU 517 requested every connection; a negotiated MTU of 88 gives MALFORMED and no START is written | JVM | Request 23; separately, drop the 89 check |
| G-B15 | DIS search: one 2A25 across two 0x180A passes; zero or two gives HUB_MISMATCH | JVM, `DisSearch` over plain lists | Search only the first 0x180A |
| G-B16 | Wi-Fi: wrong or missing `X-SaMDPi-Hub-Id` on a 200, a 400 and a 404 gives HUB_MISMATCH; body and header disagreeing gives HUB_MISMATCH; no exception escapes the dispatcher | JVM, MockWebServer | Check the header only on 2xx |
| G-B17 | Wi-Fi error table (section 6.3), one case per row; no Wi-Fi case yields BLUETOOTH_UNAVAILABLE (Q4) | JVM, MockWebServer | Map `INSTRUMENT_NOT_SERVED` to MALFORMED; separately, map `IOException` to BLUETOOTH_UNAVAILABLE |
| G-B18 | DTO session id is the lowercase canonical form; an uppercase or undashed echo fails RC-1; the fixture's version-6 nonce formats and passes | JVM | Format with `.uppercase()` |
| G-B19 | Assignment parse refusals (section 7.1), one case each | JVM, `HubAssignment` | Drop the duplicate check; drop the one-Wi-Fi-hub check; drop the BLE instrument limit |
| G-B20 | Control Point result table (section 5.3), all codes plus malformed responses | JVM | Map 0x04 to MALFORMED |
| G-B21 | Envelope rules 1 to 15, one case each, in order (a payload failing two rules reports the earlier one) | JVM | Drop each rule (one row each); swap rules 6 and 7 |
| G-B22 | Fixture sha256 pin (B11) | JVM | Flip one byte of the copy |
| G-B23 | Cap: a hung step ends in TIMEOUT at 25 s virtual; `abort()` called; STOP written only if START succeeded; `close()` always | JVM, `FakeHubLink`, `runTest` | Remove the `finally` STOP; separately, write STOP when START failed |
| G-B23b | No non-cancellable ktx `suspend()` is called on a Write, Read or Mtu request in `src/dev` | JVM, source scan | Use `WriteRequest.suspend()` |
| G-B24 | RECEIVED and FAILED audit payloads carry `hubId` and `transport` (B4); RECEIVED carries `emulatorBuild` (Q7) | JVM, `CompounderViewModelTest` | Drop `transport` from FAILED; separately, drop `emulatorBuild` from RECEIVED |
| G-B25 | Copy object: every (reason, transport) pair has a resource; BLE BUSY results show the BLE UNREACHABLE text; Wi-Fi keeps local-network classification at SDK 36 and 37 | JVM | Return the Wi-Fi UNREACHABLE key for BLE |
| G-B26 | The generated values, not a copied function (G3): in the default build `BuildConfig.PI_GATEWAY_BASE_URL == "http://kernelhub1.local:8090/"`, and `HubAssignment.parse(BuildConfig.PI_HUB_ASSIGNMENT)` succeeds | JVM, `BuildConfig` of `devDebug` | Drop `.local` from the Gradle derivation; separately, make the runtime parser refuse a value Gradle accepts (for example, cap `hub_id` at 8 characters at runtime only) |
| G-B27 | `synthetic` set only from `Accepted.synthetic` (B12) | JVM, `CompounderViewModelTest` | Set it on a Rejected |
| G-B28 | `BlePrepBridge`: cancellation marks abandoned; completion after abandonment does not resume the source; the 120 s ceiling gives BLUETOOTH_UNAVAILABLE or NOT_SUPPORTED | JVM, virtual time | Ignore the abandoned flag |
| G-B29 | Below API 33 the BLE source rejects BLUETOOTH_UNAVAILABLE without touching the link (Q1) | JVM, injected `sdkInt` | Drop the gate |
| G-B30 | Association lookup by ID (G1): `onAssociationCreated` stores `hub_id` to ID; a Start finds that ID in `getMyAssociations()`; a stored ID missing from the list, or no stored ID, runs the CDM flow; an association whose display name is `SaMD-<hub_id>` but whose ID differs is not used | JVM, fake association list and in-memory store behind `HubAssociations` | Match on `getDisplayName()`; separately, skip the CDM flow when the stored ID is missing from the list |

G-B26 asserts the default build, so the unit suite is run with neither `PI_GATEWAY_BASE_URL` nor
`PI_HUB_ASSIGNMENT` set in `local.properties` (CI has no such file). A dev build made with an
override for live check 7 is expected to fail the URL assertion; that build is installed for the
live check and is not used as a test gate.

`PiGatewayVitalsSourceTest` and `PiMeasurementMapperTest` change only where B9 changes the API
(`Response<T>`, headers in MockWebServer responses). The mapper tests stay unchanged, which proves
RC-1 and RC-2 did not move. Not unit-tested: `BleHubManager` (the adapter; it holds no decisions)
and the system dialogs. Both are covered by live checks 1, 4 and 5.

---

## 11. Live check plan (iQOO SDK 36, both hubs on `bba2c6b`)

Build and install (operator or agent, read-write adb):

```bash
./gradlew :app:assembleDevDebug :app:assembleDevDebugAndroidTest
adb -s 10BE3A09C700046 install -r app/build/outputs/apk/dev/debug/app-dev-debug.apk
adb -s 10BE3A09C700046 install -r app/build/outputs/apk/androidTest/dev/debug/app-dev-debug-androidTest.apk
adb -s 10BE3A09C700046 shell am instrument -w \
  -e class com.example.samdapp.presentation.compounder.CompounderScreenTest \
  com.example.samdapp.dev.test/androidx.test.runner.AndroidJUnitRunner
```

Precondition (operator): no `PI_GATEWAY_BASE_URL` override and no `PI_HUB_ASSIGNMENT` override in
`local.properties` for the hub runs, so the defaults apply. The agent never reads that file.

1. **First BP Start.** `BlePrepActivity` shows the CDM dialog listing `SaMD-kernelhub2`, then the
   Nearby devices prompt. Values fill with the banner and labels. The hub journal shows one connect
   and a 61-byte envelope. This covers 14.5 items 2 (BlueZ's own 0x180A present; the search passes),
   9, 10 and 12.
2. **SPO2 Start over Wi-Fi** from kernelhub1 at the derived `http://kernelhub1.local:8090/` (B2).
3. **Alternate BP and SPO2 five times each, all accepted.** Each BP Start is a new connection, so
   this exercises 14.5 items 1 and 6 on reconnect.
4. **Bluetooth off:** BLUETOOTH_UNAVAILABLE text.
5. **kernelhub2 stopped mid-acquisition:** UNREACHABLE within 25 s, no field written. The operator
   runs this during an acquisition:
   `ssh -t kernelhub2 'sudo systemctl stop samdpi-hub-ble.service'`, then
   `ssh -t kernelhub2 'sudo systemctl start samdpi-hub-ble.service'`.
6. **DEVICE_ERROR scenario on both hubs:** QUALITY_STATUS_NOT_OK.
7. **Wrong-hub cases, without touching a hub.** Each needs a dev build with a different
   `PI_HUB_ASSIGNMENT`; the operator sets it in `local.properties`.
   - `BP=ble:kernelhub9`: the DIS serial mismatches, HUB_MISMATCH.
   - `THERMOMETER=ble:kernelhub2,SPO2=wifi:kernelhub1`, then Start THERMOMETER: the hub answers
     0x04, HUB_MISMATCH.
   - `SPO2=wifi:laptop-docker` with no URL override, so the URL derives to `laptop-docker.local`.
     mDNS fails, UNREACHABLE.
   - `SPO2=wifi:kernelhub1` with `PI_GATEWAY_BASE_URL=http://127.0.0.1:8090/` while the laptop-docker
     container answers: the header names `laptop-docker`, HUB_MISMATCH. This is the Wi-Fi `hub_id`
     check (14.5 item 3).
8. **Audit rows carry `transport` and `hubId`** (and `emulatorBuild` on RECEIVED, Q7), read from
   the dev backend's audit table (Q8). Before this check runs, verify that device audit rows sync to
   that table: make one acquisition, then find its row there. If none arrives, stop and report
   before live check 8.
9. **Released configurations:**
   - (a) `./gradlew :app:dependencies --configuration stagingReleaseRuntimeClasspath | grep -c no.nordicsemi`
     prints 0, and the same for `prodReleaseRuntimeClasspath`;
   - (b) the merged-manifest grep in section 1.3 step 3;
   - (c) `apkanalyzer` in section 1.3 step 4.

**14.5 item 13, the laptop-docker swap: part of PR-B1's live plan, done when PR-B1 merges (G4).**
PR-B1's `HubIdInterceptor` rejects the legacy container, which sends no `X-SaMDPi-Hub-Id`, so from
PR-B1 on, the dev app cannot use the old container for laptop desk work. The swap is also a
precondition of live check 7's fourth row. These are operator commands. The laptop container is the
operator's Docker.

```bash
# in the SaMDPi checkout, at origin/master
deploy/build_laptop_image.sh samd-pi-emulator:hub
docker stop samd-pi-emulator && docker rm samd-pi-emulator
docker run -d --name samd-pi-emulator --restart unless-stopped \
  -p 127.0.0.1:8090:8090 samd-pi-emulator:hub
curl -s http://127.0.0.1:8090/health      # expect hub_id laptop-docker
adb -s 10BE3A09C700046 reverse tcp:8090 tcp:8090
```

The phone then uses `PI_GATEWAY_BASE_URL=http://127.0.0.1:8090/` and
`PI_HUB_ASSIGNMENT=SPO2=wifi:laptop-docker` (Q2, Q3).

- `build_laptop_image.sh <tag>` and loopback-only publishing come from SaMDPi `DEPLOY.md:186-201`.
- That file documents a test container on `127.0.0.1:18090`. Using `127.0.0.1:8090` for the
  replacement keeps `tools/dev-connect.sh`'s `adb reverse tcp:8090 tcp:8090` unchanged (INFERRED; a
  choice, not documented in SaMDPi).

---

## 12. PR split and model routing

| PR | Branch | Content | Hardware | Model |
|---|---|---|---|---|
| PR-B1 | `feat/two-hub-routing-wifi` | This memo (first commit), M1 to M6 (`src/main`), B2, B3, B4, B5, B8, B9, B10, B12, Q7, the assignment parser and `RoutingVitalsSource` (BLE entries return NOT_SUPPORTED until PR-B2), G-B1, G-B8, G-B10, G-B11, G-B11b, G-B16 to G-B19, G-B24 to G-B27 | The laptop-docker swap at merge (G4); Wi-Fi live checks 2, 7 (Wi-Fi rows), 9 | Sonnet, entirely |
| PR-B2 | `feat/two-hub-ble-client` | `ble-ktx` `devImplementation`, dev manifest, `BlePrepActivity` and bridge, CDM and the association store (G1), `BleHubManager`, `BleAcquisition`, envelope and SIG decoders, fixture and pin, the remaining G-B tests (G-B30 included) | Live checks 1 and 3 to 9 | Opus for `BleAcquisition`, `BleHubManager` and `BlePrepActivity`. Sonnet for the decoders and the manifest. |

Commits inside each PR: one per decision, each green on its own hash with
`testDevDebugUnitTest`, following the SaMDPi PR #4 discipline. The first commit of PR-B1 is a docs
commit adding this memo with section 15, so every code commit cites a tracked design. PR-B1 can
merge and be checked live on Wi-Fi before any BLE code exists.

---

## 13. Hazard and boundary notes for PR-C (no edit to `docs/quality/`)

| Candidate (parent 14.6) | Controls implemented in PR-B | Proof |
|---|---|---|
| H-37 foreign or wrong-instrument reading | RC-1 unchanged in the one mapper for both transports; BLE session id is the formatted nonce echo (B7); device type from the envelope's instrument code | G-B2, G-B18, `PiMeasurementMapperTest` unchanged |
| H-38 fault reading recorded | RC-2 unchanged; special-value rejection; NaN decoding against the fault vectors | G-B4, G-B7 |
| H-39 cannot correct a reading | Unchanged (RC-3, `CompounderViewModel`) | existing tests |
| H-40 record without review | Unchanged; the acquisition path still persists nothing | existing tests, G-B10 |
| H-41 wrong peripheral | CDM association (human choice), found again by its stored ID, never by name (G1); DIS serial, with disassociation on a serial mismatch (Q10); envelope `hub_id`; Wi-Fi header and body `hub_id` on every response; INSTRUMENT_NOT_SERVED becomes HUB_MISMATCH on both transports; the duplicate-DIS search; CCCD-before-write order, on which the intruder residual depends | G-B5, G-B13, G-B15 to G-B17, G-B20, G-B30 |
| H-42 stale or replayed indication | Fresh `randomUUID` per Start; ENV before the response is discarded; one envelope then STOP; a fresh manager per acquisition | G-B3, G-B18, G-B23 |
| H-43 emulated value in a real record | Dev-only source set; emulated flag mandatory (rule 7); banner and labels; `synthetic` audited | G-B6, G-B11b, G-B12, G-B27. Residual unchanged: no persisted synthetic marker |
| H-44 silent disconnect | 25 s cap; disconnect becomes UNREACHABLE; no reconnect; cancellable awaits only | G-B9, G-B23, G-B23b |
| H-45 encoding error | Byte-identical fixture with a sha256 pin; strict SIG flags | G-B7, G-B21, G-B22 |

Left for PR-C:

- transcription of H-37 to H-45, and the H-13 amendment row;
- the interface specification (nonce origin and format B7, the error table in 6.3, the result
  table in 5.3);
- the SOUP note (`ble-ktx` and `ble` 2.11.0 dev-only, with their transitive dependencies in 2.1);
- the change record for the `src/main` items M1 to M5 (section 1.2).

---

## 14. Open questions (all ruled at the gate review; section 15)

Each item keeps the question and recommendation as asked, then the ruling.

1. **BLE requires API 33 or higher** (section 3.2)? Recommended yes. **Ruled yes**, dev-only;
   revisit before any release use.
2. **Should preparation (permission and CDM) sit outside the 25 s cap?** Recommended yes, with its
   own 120 s ceiling. **Ruled yes.**
3. **On SDK 37 and later the local-network prompt precedes a BLE Start** (section 4.2). Recommended
   filing it, not fixing it in PR-B. **Ruled: file it.**
4. **BLE wording.** **Ruled:** `acq_reject_unreachable_ble` and `acq_reject_timeout_ble` approved
   as proposed. `acq_reject_bluetooth_unavailable` on BLE becomes "Bluetooth or Location is off, or
   this app is not allowed to use Nearby devices. Turn them on, allow Nearby devices, then try
   again." The Wi-Fi path never produces that reason.
5. **Enable the SIG characteristic's CCCD?** Recommended enabling it last. **Ruled no** (G2).
6. **CCCD_NOT_ENABLED (0x08) mapping.** **Ruled: UNREACHABLE with the BLE text.**
7. **Audit `emulatorBuild`?** Recommended adding it to the RECEIVED payload. **Ruled yes.**
8. **Where to read the audit rows for live check 8.** **Ruled: the dev backend audit table.** Verify
   that device audit rows sync there before live check 8.
9. **CDM name pattern `^SaMD-<hub_id>$`.** **Ruled:** file a SaMDPi guard
   (`local_name == "SaMD-" + hub_id`) for a later SaMDPi PR.
10. **Stale association.** **Ruled yes, only on a DIS-serial mismatch**, not on an envelope
    `hub_id` mismatch.
11. **`BleHubManager` logging below WARN dropped.** **Ruled yes.**

---

## 15. Gate review, 2026-10-09

Operator rulings at the gate. Where this section conflicts with sections 0 to 14, this section
holds. Sections 0 to 14 have been updated in place to agree with it (15.4 lists every change).

### 15.1 Rulings G1 to G5

| ID | Ruling |
|---|---|
| G1 | Association lookup. At `onAssociationCreated`, persist `hub_id` to association ID in dev-only storage. On each Start, find that ID in `getMyAssociations()`; a missing ID runs the CDM flow again. `getDisplayName()` is used only for logging. Assumption 4 (display name equals the advertised local name) is removed from the build path. Tests (M): G-B30. |
| G2 | Q5 overridden: the phone does NOT enable the SIG characteristic's CCCD. `HubInitPlan` is exactly `[RequestMtu(517), Enable(CP), Enable(ENV)]`. Section 5.2 step 4 and G-B13 updated. |
| G3 | G-B26 and assignment drift: test the generated `BuildConfig` values, not a copied function. The default build's `BuildConfig.PI_GATEWAY_BASE_URL` equals `http://kernelhub1.local:8090/`, and `BuildConfig.PI_HUB_ASSIGNMENT` parses with the runtime `HubAssignment` (a value Gradle accepted that the runtime rejects must fail tests). The Gradle configuration-time check stays, per the `kernelFallback` precedent. |
| G4 | The laptop-docker container swap (parent 14.5 item 13) happens when PR-B1 merges, not PR-B2, because PR-B1's `HubIdInterceptor` rejects the legacy container (no `X-SaMDPi-Hub-Id`). The swap commands move to PR-B1's live plan. |
| G5 | Verify assumptions 5, 6, 8 and 9 from the 2.11.0 sources jar and developer.android.com, and mark each VERIFIED with `file:line` or URL, or report it false. All four are VERIFIED (15.3). None is false, so no STOP was raised. |

### 15.2 Open questions Q1 to Q11, model routing, process

- Q1 to Q11: as ruled in section 14.
- Model routing: PR-B1 on Sonnet entirely. PR-B2 on Opus for `BleAcquisition`, `BleHubManager` and
  `BlePrepActivity`, and on Sonnet for the decoders and the manifest (section 12).
- Process: this memo, with section 15, is committed as the first commit of PR-B1 (a docs commit),
  so every code commit cites a tracked design.
- Filings recorded, not yet made: Q3 (SDK 37 local-network prompt before a BLE Start) and Q9 (the
  SaMDPi `local_name` guard). Neither is filed by this memo.

### 15.3 G5 verification results

Library paths are inside `no.nordicsemi.android:ble:2.11.0` and `ble-ktx:2.11.0` sources jars
(sha1 in the header). Platform pages were fetched 2026-10-09; local copies are local evidence, not
in either repo.

**Assumption 5: `initialize()` runs on every connection, and connect completes only after the init
queue drains. VERIFIED.**

- Every `STATE_CONNECTED` with `!serviceDiscoveryRequested` schedules `discoverServices()`
  (`BleManagerHandler.java:2200-2240`). A disconnect resets `servicesDiscovered`,
  `serviceDiscoveryRequested` and `ready` (`:1988-1991`), so each connection discovers again.
- `onServicesDiscovered`, when `isRequiredServiceSupported` is true, creates `initQueue`
  (`:2414-2426`), calls `manager.initialize()` (`:2463`), then `nextRequest(true)` (`:2465`).
- `nextRequest` polls `initQueue` first (`:3530`). Only when it is empty does it set `ready`, call
  `onDeviceReady()` and `connectRequest.notifySuccess(...)` (`:3539-3554`).
- The other two `notifySuccess` sites do not apply: `:646` is a connect to the device already
  connected (a fresh manager per acquisition never has one), and `:792` is `autoConnect = true`
  (the design uses `useAutoConnect(false)`).
- **Refinement found (not a falsification):** a failed init request does not fail the connect.
  `onDescriptorWrite` with an error status calls `wr.notifyFail(...)`, then `checkCondition()` and
  `nextRequest(true)` (`:2745-2756`), which polls the next init request, and the connect still
  succeeds when the queue drains. The design therefore records each init step's failure through its
  own `.fail {}` and checks the record after `connect` returns (section 5.2 step 4; G-B13 has the
  mutation). No other design change.

**Assumption 6: indication callbacks are delivered in arrival order. VERIFIED.**

- Platform to app: `oneway interface IBluetoothGattCallback` with `void onNotify(...)` (AOSP
  `packages/modules/Bluetooth`, `android/app/aidl/android/bluetooth/IBluetoothGattCallback.aidl:25,37`,
  https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/android/app/aidl/android/bluetooth/IBluetoothGattCallback.aidl).
  One callback stub per `BluetoothGatt` (`BluetoothGatt.java:226`, same repository,
  `framework/java/android/bluetooth/`).
- `IBinder.FLAG_ONEWAY` (https://developer.android.com/reference/android/os/IBinder): "multiple
  oneway calls being made to the same IBinder object ... will be dispatched in the other process one
  at a time, with the same order as the original calls ... the next one will not be dispatched
  until the previous one completes."
- `onNotify` passes the value into `runOrQueueCallback` (`BluetoothGatt.java:644-672`), which posts to
  the `Handler` given to `connectGatt` (`:1124-1134`). On SDK above 26 the library passes its own
  handler (`BleManagerHandler.java:769-770`); `BleManager(Context)` makes it
  `new Handler(Looper.getMainLooper())` (`BleManager.java:165-167`).
- The library's `onCharacteristicChanged` hands each value to the characteristic's
  `ValueChangedCallback` (`BleManagerHandler.java:2810-2814`), which posts to the same manager
  handler (`ValueChangedCallback` built with `this`, `:1500`; `post` is `handler.post`, `:1742-1744`;
  `ValueChangedCallback.java:180-188`).
- So CP and ENV indications travel one ordered binder stream, then two FIFO posts on one Looper.
  The order holds only while neither callback is given its own `setHandler` (section 2.4).
- Caveat: the AIDL and `BluetoothGatt.java` were read at AOSP `main`, not at the handset's vendor
  build. The `FLAG_ONEWAY` guarantee and the handler parameter of `connectGatt` are public API.

**Assumption 8: the upcast selects the cancellable `suspend()`, and `.with {}` fires before
`done`. VERIFIED.**

- Candidates: `suspend fun Request.suspend()` (non-cancellable, `RequestSuspend.kt:29`) and
  `suspend fun TimeoutableRequest.suspend()` (cancellable, `:41`; body `:393-414`). With the
  receiver's static type `TimeoutableRequest`, `WriteRequest.suspend()` and `ReadRequest.suspend()`
  (`:54-60`, `:106-112`) do not apply.
- Kotlin resolves extensions statically, "based on the declared type, not the actual instance"
  (https://kotlinlang.org/docs/extensions.html). Of the two that apply, the more specific receiver,
  `TimeoutableRequest`, wins.
- `suspendCancellable` calls `.setHandler(null)` (`RequestSuspend.kt:397`), so request callbacks run
  inline: `Request.setHandler` posts to `r.run()` when the handler is null (`Request.java:167-173`).
- Read: `onCharacteristicRead` calls `rr.notifyValueChanged(...)` (`BleManagerHandler.java:2530`),
  which posts the `.with` callback (`ReadRequest.java:286-295`), before `rr.notifySuccess(...)`
  (`BleManagerHandler.java:2534`), which posts `done` (`Request.java:1230-1243`). Write: the value
  callback is posted in `notifyPacketSent` (`WriteRequest.java:295-304`) before `wr.notifySuccess`
  (`onCharacteristicWrite`, `BleManagerHandler.java:2578,2585`). Inline or on one handler, the order is kept.
- The ktx `ReadRequest.suspend()` itself depends on this order: `result!!` after the await
  (`RequestSuspend.kt:107-111`).

**Assumption 9: `BluetoothAdapter.isEnabled` needs no `BLUETOOTH_CONNECT` on API 31 and up.
VERIFIED.**

- https://developer.android.com/reference/android/bluetooth/BluetoothAdapter, `isEnabled()`: "For
  apps targeting Build.VERSION_CODES.R or lower, this requires the Manifest.permission.BLUETOOTH
  permission". No S-or-higher clause.
- Contrast on the same page, `getName()`: "For apps targeting Build.VERSION_CODES.S or or higher,
  this requires the Manifest.permission.BLUETOOTH_CONNECT permission". The page states the
  S-or-higher requirement where one exists.
- The library itself calls `BluetoothAdapter.getDefaultAdapter().isEnabled()` before connecting
  (`BleManagerHandler.java:641`).

### 15.4 In-place changes made to sections 0 to 14

- Header: status, location and authority lines name section 15 and the PR-B1 docs commit.
- 1.1: `BleHubManager` row (Q11 log filter); `HubInitPlan` row (G2); `HubAssociations` row (G1, Q10);
  M1 (`emulatorBuild` on `Accepted`, Q7); M3 (RECEIVED payload gains `emulatorBuild`).
- 1.2: "two nullable fields" to "three nullable fields".
- 2.2: `initialize()` row (per-step `.fail {}`); `enableIndications` row (CP, then ENV; never SIG).
- 2.3: upcast and `.with {}` marked VERIFIED.
- 2.4: init-per-connection bullet added; arrival order INFERRED to VERIFIED, with the one-handler
  condition.
- 3.1: name-pattern bullet (Q9 filed; DIS serial still proves identity).
- 3.2: "Proposed simplification (open question 1)" to "Decision (Q1)", dev-only.
- 3.4: lookup by display name (INFERRED) replaced by lookup by stored association ID (G1);
  `getId()` and `disassociate(int)` cited.
- 3.5: Location Services text points to the Q4 wording.
- 3.6: 120 s ceiling marked ruled (Q2).
- 4.2: step 1 INFERRED to VERIFIED (G5 item 9); SDK 37 note marked filed (Q3).
- 5.2: step 4 init plan without SIG (G2) and recorded init failures; step 6 disassociation on a
  serial mismatch (Q10); step 12 keeps the association.
- 5.3: 0x08 row cites Q6.
- 5.5: `Accepted.emulatorBuild` from DIS 2A28 (Q7).
- 6.4: `emulatorBuild` audited (Q7).
- 7.1: G3 drift bullet added.
- 9: `acq_reject_bluetooth_unavailable` text replaced (Q4); the two BLE texts approved.
- 10: G-B5 (Q10), G-B13 (G2, init failure), G-B17 (no Wi-Fi BLUETOOTH_UNAVAILABLE), G-B24 (Q7),
  G-B26 (G3), G-B29 (Q1) updated; G-B30 added (G1); note on running G-B26 without overrides.
- 11: live check 8 (Q8 and its sync precondition); laptop-docker swap moved to PR-B1 (G4).
- 12: PR-B1 row (memo first, Q7, swap, Sonnet entirely); PR-B2 row (association store, G-B30,
  routing); docs-commit sentence.
- 13: H-41 row (G1, Q10, G-B30).
- 14: every question carries its ruling.

### 15.5 Assumptions still INFERRED

Numbered as in the STOP report before the gate. 4 is removed (G1); 5, 6, 8 and 9 are VERIFIED.

1. Starting `BlePrepActivity` from the application context with `FLAG_ACTIVITY_NEW_TASK` joins the
   app's existing task (section 3.6). The visible-window exception and the flag rule are VERIFIED.
2. CDM association grants no exemption from `BLUETOOTH_CONNECT` for `connectGatt` (section 3.5).
   Live check 1 shows the prompt.
3. `uses-feature companion_device_setup` with `required="false"` satisfies CDM's "uses-feature
   declaration" requirement (section 4.1).
7. OkHttp 4.12 propagates only an `IOException` cleanly from an interceptor on an async call
   (section 6.1). G-B16 proves the chosen shape on MockWebServer.
10. `@android:style/Theme.Translucent.NoTitleBar` works with `ComponentActivity` and
    `registerForActivityResult` (section 4.1).
11. The app's newer coroutines and Kotlin versions resolve cleanly over `ble-ktx`'s (section 2.1).
    The dev build proves it.
12. Publishing the replacement laptop container on `127.0.0.1:8090` is a choice, not a SaMDPi
    documented setting (section 11).

---

## Sources (fetched 2026-10-09)

- https://repo1.maven.org/maven2/no/nordicsemi/android/ble/2.11.0/ and `.../ble-ktx/2.11.0/` (pom,
  sources jar, AAR, sha1), and `.../maven-metadata.xml` for both
- https://github.com/NordicSemiconductor/Android-BLE-Library/releases/tag/2.11.0
- https://developer.android.com/develop/connectivity/bluetooth/companion-device-pairing
- https://developer.android.com/reference/android/companion/CompanionDeviceManager
- https://developer.android.com/reference/android/companion/CompanionDeviceManager.Callback
- https://developer.android.com/reference/android/companion/AssociationInfo
- https://developer.android.com/reference/android/companion/AssociatedDevice
- https://developer.android.com/reference/android/companion/BluetoothLeDeviceFilter.Builder
- https://developer.android.com/develop/connectivity/bluetooth/bt-permissions
- https://developer.android.com/guide/components/activities/background-starts
- https://developer.android.com/reference/android/bluetooth/BluetoothDevice
- https://developer.android.com/reference/android/content/Context
- https://developer.android.com/reference/android/bluetooth/BluetoothAdapter (gate review)
- https://developer.android.com/reference/android/os/IBinder (gate review)
- https://kotlinlang.org/docs/extensions.html (gate review)
- https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/ (gate
  review): `android/app/aidl/android/bluetooth/IBluetoothGattCallback.aidl`,
  `framework/java/android/bluetooth/BluetoothGatt.java`
- SaMDPi `bba2c6b`: `emulator/envelope.py`, `emulator/profile.py`,
  `emulator/transports/ble/control_point.py`, `emulator/transports/ble/reading.py`,
  `emulator/transports/ble/ble_hub.py`, `emulator/transports/wifi/server.py`, `emulator/hubctx.py`,
  `tests/fixtures/ble_golden_vectors.json`, `profiles/kernelhub2.toml`, `DEPLOY.md`
