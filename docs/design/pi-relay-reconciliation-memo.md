# Pi relay reconciliation: Phase 3 integration plan

Status: PROPOSED. Design only, no code written, nothing committed. Every regulatory artefact
below is PROPOSED pending operator signature and is deliberately NOT written into `docs/`.

Scope of this memo: the smallest, cleanest, most auditable integration of the Raspberry Pi
instrument emulator (`/media/sandesh/oneTB_SSD/samdpiadapterstest`, "kernel-hub") into the SaMD
Android app, honouring handoff section 31.

Operator decisions carried in from Phase 2, treated as locked:

- targetSdk is 37, the demo handset (iQOO) runs Android 16, so G7 is latent and not live. Land
  the fix now while it is inert. PR2 regardless.
- D-9 authorised: one-line `random.uniform` jitter, single-instrument path only.
- Device boundary: OUTSIDE. The Pi is an accessory, not part of the SaMD. The four Android-seam
  controls below are named risk controls that carry that argument, not incidental engineering.

Constraints honoured: read only, no code, no commits, no `.env` or credential file opened, no
em dashes.

---

## 0. What was verified in the source before writing this

Everything asserted below was read out of the two trees, not recalled.

Android side:

- `app/src/main/java/com/example/samdapp/domain/vitalssource/VitalsSource.kt` L8: the seam is
  `suspend fun readVitals(): VitalsReading`, one method, no arguments.
- `app/src/main/java/com/example/samdapp/domain/usecase/VitalsUseCases.kt` L11: the ONLY caller
  of the seam is `GetVitalsPrefillUseCase`.
- `app/src/main/java/com/example/samdapp/presentation/compounder/CompounderViewModel.kt` L243:
  `getVitalsPrefillUseCase()` is called once, in `init`, and L259 sets
  `source = ObservationSource.DEVICE` unconditionally. This is G11.
- `app/src/main/java/com/example/samdapp/domain/model/VitalsSnapshot.kt`: `toSnapshot()` also
  hardcodes `source = ObservationSource.DEVICE`. Second half of G11.
- `app/src/main/java/com/example/samdapp/domain/usecase/CheckEmergencyThresholdsUseCase.kt`:
  `BP_SYSTOLIC_CEILING_MMHG = 180`, `BP_SYSTOLIC_FLOOR_MMHG = 90`, `SPO2_FLOOR_PERCENT = 90`,
  `BP_DIASTOLIC_CEILING_MMHG = 120`. Confirms F.3 below.
- `app/src/main/AndroidManifest.xml` L5 to L8: RECORD_AUDIO, CAMERA, ACCESS_NETWORK_STATE,
  INTERNET. No `ACCESS_LOCAL_NETWORK`. This is G7.
- `app/src/dev/AndroidManifest.xml` L4: `android:usesCleartextTraffic="true"` already set for
  the dev flavour. Plain HTTP to the Pi on the LAN needs no new manifest work.
- `app/src/main/java/com/example/samdapp/presentation/common/rememberPermissionAction.kt`:
  already does `ContextCompat.checkSelfPermission` first, then launches, with an `onDenied`
  callback. PR2 reuses this rather than writing a new permission path.
- `app/src/main/java/com/example/samdapp/di/NetworkModule.kt`: the `@AbhaHttpStack` qualifier is
  the in-repo precedent for a second, separately configured OkHttp/Retrofit pair.
- `app/src/main/java/com/example/samdapp/data/local/entity/ObservationEntity.kt` L22 stores
  `ObservationSource` directly, which is why per-field provenance must NOT be modelled as new
  enum values on `ObservationSource`.
- `backend/core/app/domain/audit_actions_device.py` is the single mirror;
  `backend/core/app/services/sync.py` L37 imports it, and
  `backend/core/tests/test_audit_actions_device.py` L71 parses `AuditLogger.kt` and asserts
  equality. So a Kotlin enum value without a mirror entry is a red test, not a silent drift.
- `app/build.gradle.kts` L192: `okhttp-mockwebserver` is already on `testImplementation`, so it
  is on the `testDev` classpath too. No new test dependency.

Pi side:

- `emulator/transports/wifi/server.py`: `/health` reports a hardcoded `"version": "1.1.0"`;
  `stop_session()` cancels the timer and sets `status = STOPPED` but leaves
  `latest_measurement` and `active_config` populated (the residue); every response carries
  `Access-Control-Allow-Origin: *`; `run_wifi_server` defaults to host `0.0.0.0`, port 8090.
- `emulator/models.py`: `NormalizedMeasurement` carries `device_type` and `quality_status` at
  the top level, and `session_id` only inside `provenance`. This matters for the correlation
  predicate.
- `emulator/generator/profiles.py`: `DEVICE_ERROR` returns `0.0, 0.0, 0.0` with
  `quality_status = "ERROR"`; `SENSOR_UNAVAILABLE` returns `0.0, 0.0, 0.0` with
  `quality_status = "UNAVAILABLE"`. This is D-13, and it is exactly what control RC-2 rejects.
- `emulator/generator/profiles.py` BP HIGH: `sys_val = 175.0 + variation * 3` with
  `variation = ((step % 5) - 2) * 0.5`. At `step = 1`, variation is `-0.5`, so systolic is
  `173.5`. Confirms F.3: BP HIGH does not cross the 180 ceiling.
- `emulator/main.py` L117: without `--daemon-wifi` the process auto-starts a session at boot,
  so a measurement exists before Android ever calls `/session/start`.
- `deploy/run_emulator.sh` L18 to L24: parameters are read positionally (`${1:-"BP"}`) while the
  usage block documents `--instrument` style flags. Passing the documented flags sets
  `INSTRUMENT="--instrument"`.
- `emulator/transports/ble/real_ble_server.py` L258 to L263: the real BLE control HTTP server
  also binds port 8090 on `0.0.0.0`. This is G8.
- `emulator/generator/multi_worker.py` L252 to L292: `_assemble_final_tuple` substitutes
  `120.0 / 80.0 / 72.0 / 98.0 / 36.8 / 95.0 / 70.0` for any missing reading and hardcodes
  `quality_status = OK`. This is G-A. See section 7.6.
- The Pi tree is not a git repository (`git rev-parse` fails, no `.git`). This is why PR0 is
  first.

---

## 1. PiGatewayVitalsSource

### 1.1 Seam shape, and why the seam widens by two defaulted methods

The existing seam cannot express "start acquiring on instrument X" or "stop". `readVitals()`
takes no arguments and is called exactly once, in `CompounderViewModel.init`, for prefill.

Three ways to bridge that, and the choice:

- Rejected: change `readVitals()` to take an acquisition request. It rewrites a prod-facing
  clinical signature for the sake of a dev accessory, and touches `UnavailableVitalsSource`,
  `MockVitalsSource`, `GetVitalsPrefillUseCase` and both production DI modules.
- Rejected: a separate dev-only acquisition interface alongside `VitalsSource`. That is a second
  clinical vitals pipeline by construction, which the operator has ruled out.
- Chosen: two DEFAULTED methods on the existing `VitalsSource` interface. Default bodies mean
  `UnavailableVitalsSource`, `MockVitalsSource`, `GetVitalsPrefillUseCase`,
  `ProductionClinicalModule` (both prod and staging copies) and every existing test compile
  unchanged. One seam, one binding, one pipeline.

Proposed shape, in `VitalsSource.kt`:

- `suspend fun readVitals(): VitalsReading` unchanged.
- `suspend fun startAcquisition(request: AcquisitionRequest): AcquisitionResult` with a default
  body that returns a rejection carrying `NOT_SUPPORTED`.
- `suspend fun stopAcquisition()` with an empty default body.

`AcquisitionRequest` is a small data class (`instrument`, `scenario`) and `AcquisitionResult` is
a sealed result of `Accepted(reading, provenance metadata)` or `Rejected(reason)`, both declared
in the same file. `Instrument` and `RejectReason` are enums in that file. No new package.

`PiGatewayVitalsSource.readVitals()` returns an empty `VitalsReading()` and performs no network
call. Autofill therefore never happens on screen open. Only an explicit worker Start touches the
Pi. This is deliberate and is half of the accessory-boundary argument: the device does not
autonomously ingest instrument data.

### 1.2 The wire calls

Single-instrument route only, exactly as Phase 1 section 2 froze it:

1. `POST http://<pi-host>:<port>/api/v1/session/start` with body
   `{"instrument": "<INSTRUMENT>", "transport": "WIFI", "scenario": "<SCENARIO>",
   "reading_count": 1, "interval_seconds": 2.0}`. `reading_count: 1` is what makes the session
   emit one reading and immediately go `COMPLETED`, so there is no timer left running on the Pi.
   Response carries `session_id`.
2. `GET http://<pi-host>:<port>/api/v1/measurement`. Returns the `NormalizedMeasurement` dict, or
   404 `NO_MEASUREMENT_AVAILABLE`.
3. `POST http://<pi-host>:<port>/api/v1/session/stop` on the Stop control, on screen exit, and on
   the failure path.

The composite route (`/api/v1/session/composite/*`) is not called anywhere in this work.

### 1.3 Files that change in SaMDApp

New, dev flavour only:

- `app/src/dev/java/com/example/samdapp/data/vitalssource/PiGatewayVitalsSource.kt`
- `app/src/dev/java/com/example/samdapp/data/vitalssource/PiGatewayApi.kt` (Retrofit interface
  plus the request/response DTOs: `SessionStartRequestDto`, `SessionStartResponseDto`,
  `NormalizedMeasurementDto`, `SessionStopResponseDto`)
- `app/src/dev/java/com/example/samdapp/data/vitalssource/PiMeasurementMapper.kt`
- `app/src/dev/java/com/example/samdapp/di/PiGatewayNetworkModule.kt`

Modified:

- `app/src/main/java/com/example/samdapp/domain/vitalssource/VitalsSource.kt` (two defaulted
  methods plus the request/result/enum declarations)
- `app/src/main/java/com/example/samdapp/domain/usecase/VitalsUseCases.kt`
  (`AcquireDeviceVitalsUseCase`, `StopDeviceAcquisitionUseCase`, both thin, both injecting the
  same `VitalsSource`)
- `app/src/dev/java/com/example/samdapp/di/DevClinicalMockModule.kt` (bind
  `PiGatewayVitalsSource` instead of `MockVitalsSource`)
- `app/build.gradle.kts` (dev flavour only: `buildConfigField` for `PI_GATEWAY_BASE_URL`, read
  from `local.properties` with a default, mirroring the existing `BACKEND_BASE_URL` line at L59)
- `app/src/main/java/com/example/samdapp/presentation/compounder/CompounderViewModel.kt`
- `app/src/main/java/com/example/samdapp/presentation/compounder/CompounderScreen.kt`

Deleted (recommended, see section 12 Q3):

- `app/src/dev/java/com/example/samdapp/data/mock/MockVitalsSource.kt` becomes unbound dead code
  once the dev binding moves. `fillDemoData()` already covers the demo prefill need explicitly,
  and it is worker-triggered rather than silent.

New tests: see section 10.

### 1.4 The HTTP client

`PiGatewayNetworkModule` provides a `@PiGatewayHttpStack` qualified `OkHttpClient` and
`Retrofit`, following the `@AbhaHttpStack` precedent in `NetworkModule.kt`. It reuses the
existing `provideGson()` binding.

The Pi client MUST NOT reuse the general-purpose `OkHttpClient`. That client installs
`BearerInterceptor` and `TokenAuthenticator`, which would attach the backend access token to
every request sent to an accessory on the LAN. Structural absence, not a flag, same posture as
the ABHA client's absent logging interceptor.

Timeouts: connect 2s, read 5s, write 5s. The worker is standing at the instrument. A 10s/30s
hang is a worse failure than a fast honest "instrument not reachable".

### 1.5 Confirmations the operator asked for

- No second clinical vitals pipeline. `GetVitalsPrefillUseCase` remains the only prefill caller,
  `RecordVitalsUseCase` remains the only save path, `VitalsRepository` still fans a
  `VitalsSnapshot` into `Observation` rows. The Pi enters through the one existing seam and
  leaves through the one existing save gate.
- No Room migration. Nothing in this work adds, removes or retypes a column. `VitalsReading` is
  explicitly documented as ephemeral and never persisted as is. Per-field provenance lives in
  `CompounderUiState` and the audit payload only (section 5), which is precisely why it must not
  be expressed as new `ObservationSource` values: `ObservationEntity.source` persists that enum.
- Room database version is untouched, so no `MIGRATION_n_n+1`, no `AppDatabase` bump.

---

## 2. The four Android-seam risk controls

These are named risk controls. They are the technical substance of the accessory-boundary
determination: the Pi supplies numbers, the SaMD decides whether those numbers are admissible,
and a human decides whether they are recorded. Each is stated as what it rejects plus the test
that proves the rejection.

### RC-1: session_id and device_type correlation predicate

Hazard controlled: H-PI-01, a measurement from a foreign, previous or concurrent session, or
from an instrument other than the one the worker selected, is autofilled into this patient's
encounter. Cross-patient and cross-instrument contamination.

Rule: a measurement is admissible only if BOTH hold:

- `measurement.provenance.session_id` equals the `session_id` returned by the immediately
  preceding `/api/v1/session/start` for this acquisition, and
- `measurement.device_type` equals the device type expected for the requested instrument, per
  the fixed table in section 3.1.

Note the asymmetry, and it is a real one: `device_type` is a top-level field on
`NormalizedMeasurement`, `session_id` is only inside the `provenance` map. A missing or absent
`provenance.session_id` is a mismatch, never a pass.

Rejects: the boot-time phantom session created by `emulator/main.py` L117 without
`--daemon-wifi`; any reading left over from a previous acquisition; a reading produced by a
second phone talking to the same Pi; a GLUCOMETER reading answered to a BP request.

Test: `PiGatewayVitalsSourceTest`, MockWebServer, start returns `session_id: "A"`, measurement
returns `provenance.session_id: "B"`. Assert the result is `Rejected(SESSION_ID_MISMATCH)`, that
the returned `VitalsReading` is entirely null, and that no UI field is written. Second case:
matching `session_id`, `device_type: "GLUCOMETER"` against a BP request, assert
`Rejected(DEVICE_TYPE_MISMATCH)`.

### RC-2: quality_status == "OK" gate

Hazard controlled: H-PI-02, a fault or lead-off reading is recorded as a clinical measurement.
Concretely, D-13: `DEVICE_ERROR` and `SENSOR_UNAVAILABLE` both return `0.0` primary, secondary
and tertiary values. A `0.0` systolic, a `0.0` SpO2 or a `0.0` glucose autofilled into the form
is not merely wrong, it is wrong in the direction that trips
`CheckEmergencyThresholdsUseCase` (`spo2 0 < 90`, `systolic 0 < 90`) and manufactures a false
emergency override.

Rule: admissible only if `quality_status == "OK"`, compared exactly, case sensitive. `WARNING`,
`ERROR`, `UNAVAILABLE` and any unrecognised value are all rejected. There is no partial accept
and no "accept the fields that look plausible".

Rejects: every `DEVICE_ERROR` and `SENSOR_UNAVAILABLE` reading, at the boundary, before the
mapper is ever reached.

Test: `PiMeasurementMapperTest`, feed a `NormalizedMeasurementDto` with
`quality_status = "ERROR"` and `primary_value = 0.0`; assert `Rejected(QUALITY_STATUS_NOT_OK)`.
Repeat for `"UNAVAILABLE"`. A third case asserts `"WARNING"` is also rejected, so the gate is an
allowlist and not a two-value denylist.

### RC-3: every autofilled field remains editable

Hazard controlled: H-PI-03, the worker cannot correct an instrument reading they can see is
wrong, for example a cuff that slipped, so a known-bad number reaches the clinical record.

Rule: autofill writes the same `CompounderUiState` string fields the keyboard writes. No field
is set `readOnly`, `enabled = false`, or otherwise locked after a device write. The device write
and the human write are the same mutation, differing only in the provenance mark (section 5).

Rejects: any implementation that locks a field on device write. This is a control on our own
code, and its proof is a UI test rather than a rejection path.

Test: `CompounderScreenTest` (Compose, `createComposeRule`), seed the state as if a BP reading
was accepted, then `performTextReplacement` on the BP systolic field, assert the node's text
reflects the edit and that `onBpSystolicChange` fired. Assert `hasSetTextAction()` on all seven
device-writable fields after autofill.

### RC-4: Save and Continue remains the sole persistence gate

Hazard controlled: H-PI-04, instrument data reaches the patient record without a human having
looked at it. This is the single strongest element of the accessory-boundary argument: the Pi
cannot write to the record, only a person can.

Rule: the acquisition path never calls `RecordVitalsUseCase`, never calls `VitalsRepository`,
and never emits `VITALS_RECORDED`. It only mutates `CompounderUiState`. The sole persistence
gate stays `CompounderViewModel.onContinue()`, reached only through the primary action button on
`CompounderScreen`.

Naming note for the risk file: the risk-control wording is "Save and Continue"; in the current
source that control is the `Button` at `CompounderScreen.kt` L205 to L211, labelled "Continue"
(or "Saving..." while in flight), test tag `continue_button`. The risk file entry should name the
test tag, which is stable, rather than the visible label, which is not.

Rejects: any acquisition-path write to the database. Proved by absence.

Test: `CompounderViewModelTest`, fake `VitalsSource` returning an accepted BP reading and a fake
`RecordVitalsUseCase`. Drive Start, assert the state carries the values AND that the fake record
use case was never invoked and no `VITALS_RECORDED` audit row was logged. Then drive
`onContinue()` and assert exactly one invocation.

### Control to hazard summary

| Control | Rejects | Hazard |
|---|---|---|
| RC-1 correlation | foreign, stale or wrong-instrument reading | H-PI-01 cross-encounter and cross-instrument contamination |
| RC-2 quality gate | D-13 fault and lead-off `0.0` readings | H-PI-02 fault reading recorded as clinical, false emergency override |
| RC-3 editability | locked fields after autofill | H-PI-03 worker cannot correct a known-bad reading |
| RC-4 sole save gate | any acquisition-path persistence | H-PI-04 instrument data enters the record unreviewed |

---

## 3. Mapping rule

Lives in `PiMeasurementMapper.kt`, dev flavour, pure function, no Android dependency, so it is
unit testable with no robolectric and no coroutines.

Signature intent: `NormalizedMeasurementDto` plus the expected `Instrument` and the expected
`session_id` in, `AcquisitionResult` out.

Order of operations is fixed and is itself part of the control: reject first, map second. No
value is converted before both gates pass.

1. If `quality_status != "OK"`, return `Rejected(QUALITY_STATUS_NOT_OK)`. (RC-2)
2. If `provenance["session_id"] != expectedSessionId`, return `Rejected(SESSION_ID_MISMATCH)`.
   (RC-1)
3. If `device_type != expectedDeviceType`, return `Rejected(DEVICE_TYPE_MISMATCH)`. (RC-1)
4. Only now, map.

### 3.1 Instrument to device_type to field table

| Instrument | device_type | primary | secondary | tertiary |
|---|---|---|---|---|
| BP | `BLOOD_PRESSURE` | `bpSystolic` Int | `bpDiastolic` Int | `pulseBpm` Int |
| SPO2 | `PULSE_OXIMETER` | `spo2Percent` Int | `pulseBpm` Int | not used |
| THERMOMETER | `THERMOMETER` | `temperatureCelsius` Double | not used | not used |
| GLUCOMETER | `GLUCOMETER` | `bloodGlucoseMgDl` Int | not used | not used |
| WEIGHT_SCALE | `WEIGHT_SCALE` | `weightKg` Double | not used | not used |
| HEART_RATE | `HEART_RATE_MONITOR` | `pulseBpm` Int | not used | not used |

`respiratoryRate` and `heightCm` have no instrument on this Pi and are never device written.
They stay MANUAL always.

### 3.2 Numeric conversion, explicit

- Five Int fields: `pulseBpm`, `bpSystolic`, `bpDiastolic`, `spo2Percent`, `bloodGlucoseMgDl`.
  Conversion is `Math.round(value).toInt()`, which is round-half-up. Not `toInt()`, which
  truncates, and not `roundToInt()`, whose half-way behaviour is worth not having to argue about
  in a review. `173.5` becomes `174`. `98.5` becomes `99`.
- Two Double fields: `temperatureCelsius` and `weightKg`. Straight passthrough of the Double.
  No rounding, no formatting, no unit conversion. The Pi already emits Cel and kg (see
  `INSTRUMENT_METADATA`, `"unit": "Cel"` and `"unit": "kg"`).
- A `null` secondary or tertiary value maps to a null field, never to `0.0` and never to a
  substituted default. There is no default substitution anywhere in this mapper. That is the
  whole disease G-A represents on the Pi side and it does not get imported here.

The half-up rule interacts with F.3 and the demo: BP HIGH primary is `173.5`, which rounds to
`174`, which is still below the 180 ceiling. Rounding does not create an emergency and does not
suppress one. See section 11.

### 3.3 Section expansion on a glucometer reading (D.2)

`bloodGlucoseMgDl` lives inside the `showPointOfCareTests` collapsed section
(`CompounderScreen.kt` L193 to L201). An accepted GLUCOMETER reading that writes a field the
worker cannot see is a silent write.

Rule: when an accepted reading populates `bloodGlucoseMgDl`, the same state update sets
`showPointOfCareTests = true`. One-way. It never collapses the section, because collapsing would
hide a value the worker may have already edited. `onTogglePointOfCareTests()` stays the worker's
control and is not touched.

---

## 4. Start and Stop UI on CompounderScreen

### 4.1 State model (G-C)

Added to `CompounderUiState`:

- `acquiringInstrument: Instrument?` (null when idle). This is the in-flight guard and it mirrors
  the existing `isSaving: Boolean` pattern exactly: set before the suspend call, cleared in both
  the success and the failure branch. It is an `Instrument?` rather than a `Boolean` because it
  also names which field group shows the indicator.
- `selectedInstrument: Instrument` defaulting to BP.
- `selectedScenario: Scenario` defaulting to NORMAL (dev-only, see 4.4).
- `acquisitionError: String?`, distinct from the existing `errorMessage`, so an instrument
  failure never overwrites a save failure and vice versa.
- `activeSessionId: String?`, held so Stop and screen exit can post `/session/stop`, and so RC-1
  has something to compare against.

State transitions:

- Idle (`acquiringInstrument == null`) plus Start pressed goes to Acquiring
  (`acquiringInstrument = selectedInstrument`).
- Acquiring plus `Accepted` goes to Idle, fields written, provenance marked DEVICE,
  `VITALS_DEVICE_READING_RECEIVED` emitted.
- Acquiring plus `Rejected` or transport failure goes to Idle, NO field written,
  `acquisitionError` set, `VITALS_DEVICE_READING_FAILED` emitted.
- Any state plus Stop or screen exit posts `/session/stop`, clears `activeSessionId`, cancels the
  in-flight job.

Guard: Start is disabled while `acquiringInstrument != null` and while `isSaving`. Continue stays
enabled or disabled by the existing `canContinue`, which is untouched.

### 4.2 Indicator granularity

The screen-level `isLoadingPrefill` path (`CompounderScreen.kt` L100 to L103) replaces the whole
form with a spinner. That is wrong for acquisition: it would hide the fields the worker is
watching and hide any values they have already typed.

Rule: a field-level indicator only. While `acquiringInstrument != null`, the affected field
group shows a small trailing progress indicator and the Start button reads "Acquiring...". The
rest of the form stays visible, populated and editable. `isLoadingPrefill` is not reused and its
existing behaviour is not changed.

### 4.3 Instrument selection is a new control

The existing "How were these vitals captured?" `DropdownField` at `CompounderScreen.kt` L142 to
L150 is the worker's own attestation of how they took the reading (REQ-TRS-05). It stays
worker-owned and untouched, per D.8. Autofill never writes `captureMethod`.

The instrument selector is a separate, new control placed with the Start and Stop buttons, above
the vitals fields, visually and structurally distinct from the capture-method dropdown. It
selects which instrument the Pi is asked for. Reuses the existing `DropdownField` composable.

### 4.4 Scenario selector, dev only

A second new dropdown, `NORMAL` (default), `LOW`, `HIGH`, `DEVICE_ERROR`, `SENSOR_UNAVAILABLE`,
`STALE_TIMESTAMP`, `DUPLICATE`, `MALFORMED`. It exists so the demo can show hypo, normal and
hyper truthfully and so the fault paths can be demonstrated live.

The entire Start/Stop/instrument/scenario block is wrapped in a
`if (BuildConfig.PI_GATEWAY_ENABLED)` guard, a dev-flavour-only `buildConfigField` that is
`false` in staging and prod. Combined with `PiGatewayVitalsSource` physically living in
`src/dev/`, that is the same two-layer posture `DevClinicalMockModule` already documents: the
class does not exist outside dev, and the control that would call it does not render.

### 4.5 Stop

Stop posts `POST /api/v1/session/stop` and clears `activeSessionId`. It never clears any field
the worker can see. Stopping the instrument is not undoing the reading.

Screen exit (`DisposableEffect` in `CompounderScreen`, or `onCleared()` in the ViewModel, whichever
the existing lifecycle conventions favour, and `onCleared()` is the safer of the two because it
survives configuration change) also posts stop and cancels the in-flight acquisition job.

---

## 5. Per-field provenance (G-E)

### 5.1 Model

`enum class FieldProvenance { MANUAL, DEVICE, DEVICE_EDITED }`, declared next to the Compounder
UI state, not in `domain/model`, because it is deliberately presentation and audit only.

`CompounderUiState` gains
`val fieldProvenance: Map<VitalsField, FieldProvenance> = emptyMap()`, where `VitalsField` is a
small enum over the nine device-writable and manual vitals fields. An absent key means MANUAL.

Nothing about this touches Room. `ObservationEntity.source` continues to store the existing
two-value `ObservationSource`, unchanged, so there is no migration and no new persisted value
for the backend sync accept-set to learn.

### 5.2 Transitions

- Accepted device write sets each populated field to DEVICE.
- `DEVICE_EDITED` flips inside the EXISTING `onXChange` callback, not in a new interception
  layer: `onBpSystolicChange` and its eight siblings each add one line, "if this field is
  currently DEVICE, mark it DEVICE_EDITED". A field that is MANUAL stays MANUAL. A field that is
  already DEVICE_EDITED stays DEVICE_EDITED.
- The lazy correctness detail worth stating: the flip happens on any callback invocation from the
  text field, including one where the worker types the same character back. That is acceptable
  and is the safer direction. A field marked DEVICE_EDITED that was not really edited
  understates device provenance; the reverse would overstate it.
- A second accepted acquisition on the same field resets it to DEVICE, because the new device
  value has replaced whatever was there.

### 5.3 Roll-up rule for `VitalsSnapshot.source`

Stated explicitly, because this is the thing the audit record and the kernel see:

> `source = ObservationSource.DEVICE` if and only if at least one field in `fieldProvenance` is
> `DEVICE` at the moment `onContinue()` runs. Otherwise `MANUAL`.

`DEVICE_EDITED` does NOT count toward DEVICE. A number a human typed over is a human's number,
whatever put it there first. An all-null reading (`UnavailableVitalsSource`, or a Pi acquisition
that was never run) produces no DEVICE marks and therefore rolls up to MANUAL, which is exactly
the G11 fix arriving from the other direction.

### 5.4 Audit payload

`VITALS_DEVICE_READING_RECEIVED` carries the field-to-provenance map as names only. Also, at
`VITALS_RECORDED` time, the existing payload gains a `fieldProvenance` entry, so the record shows
which of the saved numbers came from an instrument. Field names and provenance labels only, never
values, matching the `AILMENT_CAPTURED` posture already in the file.

### 5.5 Visual treatment

Design intent only, not specified here and not blocking: a small instrument glyph or a
supporting-text line on a DEVICE field, and the same treatment struck through or greyed on
DEVICE_EDITED. Colour alone must not be the carrier. Final treatment is a separate design pass.

---

## 6. Audit actions

Two new values, both PROPOSED pending operator signature.

```
VITALS_DEVICE_READING_RECEIVED("vitals_device_reading_received")
VITALS_DEVICE_READING_FAILED("vitals_device_reading_failed")
```

`_FAILED` is not optional and is not decoration. Without it, a rejected reading is invisible:
the audit trail would show a gap where the worker pressed Start and nothing happened, and the
three most interesting rejections (D-13 fault reading, session mismatch, instrument unreachable)
would leave no trace at all. It is the same argument that put `EVALUATE_RESPONSE_FAILED` and
`KERNEL_EMPTY_DIFFERENTIAL` in the file.

Payloads, both of which NEVER carry a measured value:

- RECEIVED: `sessionId`, `deviceId`, `deviceType`, `measurementId`, `sequenceNumber`,
  `qualityStatus`, `transport`, `protocol`, `synthetic`, `fieldsPopulated` (field names, comma
  joined), `fieldProvenance` (field name to provenance label).
- FAILED: `sessionId` (the one requested, may be null if start itself failed),
  `instrumentRequested`, `rejectReason` (one of `QUALITY_STATUS_NOT_OK`, `SESSION_ID_MISMATCH`,
  `DEVICE_TYPE_MISMATCH`, `NO_MEASUREMENT`, `UNREACHABLE`, `PERMISSION_DENIED`, `MALFORMED`,
  `TIMEOUT`), `httpStatus` where one exists.

`quality_status` and `deviceType` are carried because they are the evidence that the control
fired, and they are not patient data.

### The sync-rejection trap

The Kotlin enum values in `app/src/main/java/com/example/samdapp/domain/audit/AuditLogger.kt` and
the mirror entries in `backend/core/app/domain/audit_actions_device.py` land in the SAME commit.
`backend/core/tests/test_audit_actions_device.py` L71 parses the Kotlin source and asserts set
equality, so splitting them across commits is a red test rather than a silent field failure. The
failure mode being avoided is the one already documented in that file's own comments: an action
the accept-set does not know is a permanent, silent sync rejection of every row the device keeps
re-sending.

`backend/core/app/services/sync.py` imports the frozenset and needs no edit of its own.

Payload-carrying values are also worth a line in `PatientFacingAudit.kt` if these rows should be
patient-visible. Recommendation: they should not be. A patient-facing string for "the BP cuff
did not correlate" is noise. Left out unless the operator says otherwise (section 12 Q4).

---

## 7. Pi-side changes

Scoped minimal. Each item is its own concern and its own commit inside PR3.

### 7.1 D-9 jitter, authorised, single-instrument path only

`emulator/generator/profiles.py`. The single-instrument path is fully deterministic: with
`reading_count: 1` the step is always 1, so `variation` is always `-0.5` and every acquisition of
a given instrument and scenario returns a byte-identical value. A demo that shows the same
`173.5` three times in a row reads as a hardcoded string, which is the opposite of the point.

Change: one line adding `random.uniform` jitter to the returned primary value, bounded so it
cannot cross a clinical threshold boundary that the scenario name promises (a NORMAL reading must
stay normal, a LOW reading must stay low). `random` is already imported at L6 of that file and is
currently unused, which is a hint that this was the original intent.

Not applied to the composite path (section 7.6).

### 7.2 stop_session clears the residue

`emulator/transports/wifi/server.py`, `WifiServerState.stop_session()`. Today it cancels the
timer and sets `status = STOPPED` but leaves `self.latest_measurement` and `self.active_config`
populated, so `GET /api/v1/measurement` keeps answering 200 with a stale reading after the
session is over. Add `self.latest_measurement = None` and `self.active_config = None` inside the
existing lock. `readings_emitted` is deliberately left alone because the stop response reports it.

After this fix, a post-stop `GET /api/v1/measurement` correctly returns 404
`NO_MEASUREMENT_AVAILABLE`. Note that RC-1 already rejects the stale reading independently, so
this is defence in depth and not the only thing standing between the worker and a stale number.

### 7.3 deploy/run_emulator.sh: getopts parsing, pass --daemon-wifi

The script's usage block documents `--instrument BP --transport WIFI ...` while the body reads
`${1:-"BP"}` positionally. Running the documented invocation sets `INSTRUMENT="--instrument"`,
which then fails `InstrumentType("--instrument")` inside `main.py`. Replace the positional reads
with a `getopts`-style long-option loop (a `while [[ $# -gt 0 ]]; case $1 in` loop, since bash
`getopts` does not do long options) that honours the documented flags and keeps the same
defaults.

Separately, add `--daemon-wifi` to the `python3 -m emulator.main` invocation. Without it,
`main.py` L117 auto-starts a session at boot, so a phantom measurement exists on the Pi before
Android ever calls `/session/start`. The daemon form is what the single-instrument route needs:
a server that sits idle and waits to be asked.

### 7.4 Move the real BLE control server off 8090 (G8)

`emulator/transports/ble/real_ble_server.py` L258 to L263 and L274: the BLE control HTTP server
defaults to port 8090, the same port `run_wifi_server` defaults to. Running both on one Pi means
whichever binds second fails, and the log line at L266 swallows it into a warning. Change the BLE
control default to 8091 and update the docstring at L13 and L134 which both name 8090.

### 7.5 Bind to the LAN interface, drop the CORS wildcard

Two changes in `emulator/transports/wifi/server.py`:

- `run_wifi_server` default host `0.0.0.0` becomes the LAN interface address, supplied by the
  caller. Binding all interfaces on a device that may also carry a management network is a wider
  exposure than the accessory needs.
- Remove `Access-Control-Allow-Origin: *` and the two matching allow-methods/allow-headers
  headers from `_send_json` and from `do_OPTIONS`. There is no browser client. An Android OkHttp
  client does not read or need CORS headers. The wildcard exists only to let any web page on the
  same network read the instrument stream, which is not a capability the accessory should offer.

These two are grouped as one concern (network exposure) but are separate hunks.

### 7.6 Explicitly out of scope: the composite path and G-A

The composite path is NOT touched by this work. Specifically:

`emulator/generator/multi_worker.py` `_assemble_final_tuple` (L252 to L292) substitutes fixed
clinical defaults for any instrument that did not report: systolic 120.0, diastolic 80.0, pulse
72.0, SpO2 98.0, temperature 36.8, glucose 95.0, weight 70.0, and then stamps the assembled
tuple `quality_status = OK` unconditionally. A composite session in which the thermometer worker
never reported produces a tuple that says, with an OK quality status, that the patient's
temperature is 36.8. That is fabricated clinical data presented as measured, which is the exact
class of defect the kernel-mock safety work removed from the Android side.

Decision, recorded: the composite path is not called by anything in this integration. The
single-instrument route is the only route used. G-A and its two pinning tests are therefore OUT
of scope for this work, and G-A remains a DOCUMENTED DEFECT, carried forward, to be fixed on the
day the composite path is first used by anything. It must not be used before that fix.

The two pinning tests that G-A needs (one asserting no substitution occurs for a missing
instrument, one asserting `quality_status` degrades rather than staying OK) are named here so
they are not lost, and are not written in this work.

---

## 8. PR plan

One branch each, in this order. Each PR is independently revertable.

### PR0: git init plus version scheme wired to /health

Branch: `chore/pi-git-init-and-version`
Scope: Pi tree only. `git init`, a `.gitignore` review (one exists already), an initial commit of
the current tree as the baseline. Then a single `VERSION` constant (or `emulator/__init__.py`
`__version__`) that `/health` reads, replacing the hardcoded `"1.1.0"` string in
`emulator/transports/wifi/server.py`.
Why first: the `"1.1.0"` in `/health` today is a literal that has never corresponded to anything.
Every subsequent PR changes behaviour behind that endpoint. Shipping any of them while `/health`
reports a fixed fictional version means the demo cannot answer "which build is on the Pi", which
is a question a regulator asks and an operator needs during a demo failure.
Gate: `curl /health` reports the same version as the git describe output of the checked-out tree.
Blocking dependency: none. This is the root of the chain.

### PR1: G11, ObservationSource.DEVICE made conditional

Branch: `fix/observation-source-conditional`
Scope: `VitalsSnapshot.kt` `toSnapshot()` and `CompounderViewModel.kt` L259. Today both set
`source = DEVICE` unconditionally, so in staging and prod, where `UnavailableVitalsSource`
returns an all-null reading and the worker types every number by hand, every observation row is
labelled DEVICE. The audit trail currently asserts a device measured numbers that no device ever
touched.
Fix: `source` is DEVICE only when the reading actually carried at least one non-null value.
Ahead of any Pi work, deliberately: it is a live data-integrity defect in shipped behaviour, it
is independently testable, and landing it first means PR4's provenance roll-up (section 5.3) is
refining a correct rule rather than replacing a wrong one.
Gate: a test asserting an all-null `VitalsReading` rolls up to MANUAL, and a test asserting a
partially populated one rolls up to DEVICE.
Blocking dependency: none. Can land in parallel with PR0.

### PR2: G7, ACCESS_LOCAL_NETWORK

Branch: `feat/local-network-permission`
Scope: three things.
1. `<uses-permission android:name="android.permission.ACCESS_LOCAL_NETWORK" />` in
   `app/src/main/AndroidManifest.xml`.
2. A runtime request at the point of use, reusing the existing
   `rememberPermissionAction` helper, which already does `checkSelfPermission` first and already
   takes an `onDenied`. No new permission machinery.
3. The error-handler split. A local-network failure must be reported as one of two distinct
   states: PERMISSION_DENIED or UNREACHABLE. The determination is made by
   `checkSelfPermission(ACCESS_LOCAL_NETWORK)` FIRST, before or independently of any exception
   inspection. Parsing an errno string, an `IOException` message, or `EPERM` text as the primary
   discriminator is explicitly forbidden: those strings are OEM and OS-version dependent, they
   are not part of any contract, and the iQOO's Funtouch layer is exactly the kind of skin that
   changes them. Exception text may be logged; it must never be the branch condition.
Latency note: the permission is enforced from the OS version that pairs with targetSdk 37. The
demo handset runs Android 16, so this code is inert on it. It is landed now precisely because it
is inert now: a permission fix written under demo pressure on the day it starts failing is a bad
fix.
Gate: dev build installs and runs unchanged on the iQOO; a unit test on the discriminator
function asserts it returns PERMISSION_DENIED from the `checkSelfPermission` result alone, with
an exception message that says nothing useful.
Blocking dependency: none, but it must land before PR4 so PR4 has a permission state to branch
on rather than inventing one.

### PR3: Pi-side bundle

Branch: `feat/pi-emulator-hardening`
Scope: section 7 items 7.1 through 7.5, one commit each. Explicitly excludes 7.6.
Gate: `--daemon-wifi` server starts idle with no measurement available; `POST /session/start` with
`reading_count: 1` followed by `GET /measurement` returns one reading; `POST /session/stop`
followed by `GET /measurement` returns 404; two successive single-instrument acquisitions of the
same instrument and scenario return different values (jitter); `deploy/run_emulator.sh
--instrument SPO2 --scenario LOW` actually runs SpO2 LOW; BLE control server and Wi-Fi server run
simultaneously without a bind failure.
Blocking dependency: PR0, because these commits need a repository to land in.

### PR4: PiGatewayVitalsSource plus the four risk controls plus Start/Stop UI plus provenance

Branch: `feat/pi-gateway-vitals-source`
Scope: sections 1, 2, 3, 4, 5 in full. This is the large one and it is deliberately not split,
because the four risk controls are only meaningful together with the code path they gate: a
`PiGatewayVitalsSource` merged without RC-1 and RC-2 is a source of unvalidated instrument data
sitting in the tree, and the accessory-boundary determination would be untrue for the duration.
Gate: the full section 10 test list green, plus a manual run against a live Pi on the LAN.
Blocking dependency: PR1 (roll-up rule builds on the conditional source), PR2 (permission state),
PR3 (the Pi must behave as the wire contract says before Android is written against it).

### PR5: audit actions plus backend mirror

Branch: `feat/vitals-device-audit-actions`
Scope: the two `AuditAction` values, the two mirror entries in
`backend/core/app/domain/audit_actions_device.py`, both in the same commit, plus the emit sites
in `CompounderViewModel`.
Gate: `backend/core/tests/test_audit_actions_device.py` green (it parses the Kotlin enum and
asserts set equality), plus a ViewModel test asserting each emit site fires exactly once with a
payload containing no measured value.
Blocking dependency: PR4, because the emit sites do not exist until the acquisition path does.
Note the tension: PR4's tests reference the audit emissions. Resolution: PR4 asserts the ABSENCE
of `VITALS_RECORDED` on the acquisition path (RC-4), which needs no new action, and PR5 adds the
positive assertions. If the operator prefers a single landing, PR4 and PR5 can be merged; they
are split here because the backend mirror touches a second service and reviewers differ.

---

## 9. Regulatory artefacts

All PROPOSED, all pending operator signature, none written into `docs/`. Listed here as the
content that would be transcribed on signature.

### 9.1 Accessory-boundary determination

Determination: the Raspberry Pi instrument gateway is OUTSIDE the SaMD device boundary. It is an
accessory data source.

Rationale, in the form the argument actually has to hold:

1. The Pi performs no clinical function. It acquires, normalises and serves measurements. It
   makes no assessment, applies no threshold, produces no recommendation, and reaches no
   conclusion about a patient.
2. The SaMD does not trust the Pi. Every reading crosses an admissibility boundary (RC-1, RC-2)
   before it can affect any state, and a rejected reading affects nothing.
3. The SaMD does not autonomously ingest from the Pi. Acquisition happens only on an explicit
   worker action, per acquisition, per instrument (`readVitals()` performs no network call).
4. The Pi cannot write to the patient record. It can only propose values into an editable form.
   A qualified human reviews every value and presses a single persistence control (RC-4).
5. Every proposed value remains editable up to the moment of persistence (RC-3), and the record
   retains which values were device-proposed, which were edited, and which were typed
   (section 5).

The four controls are the determination. If any one of them is removed, the determination does
not hold and the boundary has to be redrawn to include the Pi.

### 9.2 The four risk-control statements

Verbatim from section 2, one entry each in the risk file, each carrying: control identifier,
hazard identifier, what it rejects, where it is implemented (file and symbol), and the test that
proves it. RC-4's entry names the test tag `continue_button`, not the visible button label.

### 9.3 The two audit actions

`vitals_device_reading_received` and `vitals_device_reading_failed`, with the payload field lists
from section 6 and the explicit statement that neither ever carries a measured value. Added to
the audit-vocabulary section that already documents the existing action list.

### 9.4 SOUP note

The Pi emulator is an external, documented data source with a published interface
specification (9.6). It is not linked into the SaMD, shares no code with it, and is not
redistributed with it. Entry records: name, version (from PR0's version scheme, which is why PR0
is first), supplier (in-house), the interface specification reference, and the four controls that
mediate everything it supplies.

### 9.5 Version scheme

From PR0. Single source of truth in the Pi tree, reported by `/health`, tied to the git tree. The
existing hardcoded `"1.1.0"` is replaced, and the note records that the previous value was a
literal that corresponded to no build.

### 9.6 Interface specification, the boundary document

The single-instrument wire contract from Phase 1 section 2, frozen. Contents:

- Transport: HTTP/1.1, JSON, `MOCK_WIFI_JSON` protocol marker, plain HTTP on the LAN.
- `POST /api/v1/session/start`: request fields `instrument`, `transport`, `scenario`,
  `reading_count`, `interval_seconds`. Response: `status`, `session_id`, `instrument`,
  `reading_count`, `interval_seconds`. `reading_count: 1` is the contracted single-instrument
  form.
- `GET /api/v1/measurement`: 200 with a `NormalizedMeasurement`, or 404
  `NO_MEASUREMENT_AVAILABLE`.
- `POST /api/v1/session/stop`: `status`, `readings_emitted`.
- `NormalizedMeasurement` field list, verbatim from `emulator/models.py`, with the two facts the
  controls depend on called out explicitly: `device_type` and `quality_status` are TOP LEVEL,
  `session_id` is inside `provenance` and only there.
- `quality_status` domain: `OK`, `WARNING`, `ERROR`, `UNAVAILABLE`. Only `OK` is admissible.
- The instrument to `device_type` to unit table from section 3.1.
- `synthetic: true` on every payload, and the statement that this emulator emits synthetic data
  only and is not a clinical instrument.
- Explicitly excluded from the boundary document: the composite endpoints. They are not part of
  the contracted interface and are not called.

---

## 10. Test plan

Code-assertable, all in the existing source sets and with the existing dependencies.

`app/src/testDev/java/com/example/samdapp/data/vitalssource/PiMeasurementMapperTest.kt`:

1. Rounding, half case: BP HIGH primary `173.5` maps to `bpSystolic == 174`. This is the pinning
   test for round-half-up and it uses the exact number the demo produces.
2. Rounding, the other half: a value of `98.4` maps to `98`, and `98.5` maps to `99`.
3. Double passthrough: temperature `36.65` maps to `36.65` exactly, weight `68.4` to `68.4`. No
   rounding, no formatting.
4. `quality_status` rejection: `"ERROR"` with `0.0` values gives `Rejected(QUALITY_STATUS_NOT_OK)`
   and a null-everywhere reading. Repeat for `"UNAVAILABLE"` and for `"WARNING"`.
5. `session_id` rejection: `provenance.session_id` differing from the expected id gives
   `Rejected(SESSION_ID_MISMATCH)`. A missing `provenance.session_id` key gives the same.
6. `device_type` rejection: `"GLUCOMETER"` against a BP request gives
   `Rejected(DEVICE_TYPE_MISMATCH)`.
7. Ordering: a payload that is BOTH `quality_status = "ERROR"` AND session-mismatched reports
   `QUALITY_STATUS_NOT_OK`, proving the fixed reject order in section 3.
8. Null secondary and tertiary map to null fields, never `0.0`.

`app/src/testDev/java/com/example/samdapp/data/vitalssource/PiGatewayVitalsSourceTest.kt`
(MockWebServer, already on the classpath):

9. Happy path: start returns a session id, measurement correlates, result is `Accepted` and the
   reading carries the mapped values.
10. Stale-residue rejection after Stop: enqueue a stop response, then a 200 measurement whose
    `provenance.session_id` is the OLD id. Assert `Rejected(SESSION_ID_MISMATCH)`. This test is
    written so it passes with or without the PR3 residue fix, which is the point: the Android
    control does not depend on the Pi being fixed.
11. 404 `NO_MEASUREMENT_AVAILABLE` gives `Rejected(NO_MEASUREMENT)`, not a crash and not an
    all-zero reading.
12. `readVitals()` performs zero HTTP requests. Assert `MockWebServer.requestCount == 0`.
13. Connection failure (server shut down) gives `Rejected(UNREACHABLE)`.
14. No `Authorization` header is present on any request to the Pi. This pins section 1.4.

`app/src/test/java/com/example/samdapp/presentation/compounder/CompounderViewModelTest.kt` (new,
alongside the existing `AilmentListItemMappingTest.kt`):

15. Cancel on screen exit: with an acquisition in flight, `onCleared()` cancels the job and posts
    stop; no field is written afterwards.
16. Glucometer section expansion: an accepted GLUCOMETER reading sets `showPointOfCareTests` to
    true. A second test asserts an accepted BP reading does NOT collapse an already-open section.
17. Provenance transitions: accepted BP reading marks `bpSystolic` DEVICE; `onBpSystolicChange`
    flips it to DEVICE_EDITED; a further `onBpSystolicChange` leaves it DEVICE_EDITED; a fresh
    accepted reading resets it to DEVICE; a field never touched by a device stays MANUAL.
18. Roll-up: all-DEVICE_EDITED rolls up to `ObservationSource.MANUAL`; at least one DEVICE rolls
    up to DEVICE; no acquisition at all rolls up to MANUAL (this is also the PR1 regression).
19. RC-4: driving Start never invokes `RecordVitalsUseCase` and never logs `VITALS_RECORDED`;
    `onContinue()` invokes it exactly once.
20. Audit emit sites: an accepted reading emits exactly one
    `VITALS_DEVICE_READING_RECEIVED`; each rejection reason emits exactly one
    `VITALS_DEVICE_READING_FAILED` with the matching `rejectReason`. A payload assertion checks
    that no measured value string appears anywhere in the payload.
21. The `acquiringInstrument` guard: a second Start while one is in flight is a no-op.

`app/src/test/java/com/example/samdapp/presentation/compounder/CompounderScreenTest.kt`
(Compose):

22. RC-3 editability: after an autofilled reading, all seven device-writable fields report
    `hasSetTextAction()`, and a `performTextReplacement` on BP systolic takes effect.
23. The form stays visible during acquisition: with `acquiringInstrument` non-null, the vitals
    fields are still on screen (the `isLoadingPrefill` full-screen path is not entered).

Backend:

24. `backend/core/tests/test_audit_actions_device.py` passes with the two new values, which is
    the mirror-drift assertion, not a new test.

### 10.1 The two manual preconditions, not code-assertable

These cannot be asserted by any test and belong on the demo checklist as human steps.

- M1: the Funtouch local-network toggle. vivo/iQOO Funtouch OS carries a per-app local network
  access switch that is independent of the Android permission model and can be off while the app
  believes it holds every permission it needs. It must be checked by hand on the iQOO before
  each demo. Symptom if missed: connection refused or timeout to the Pi, with the app reporting
  UNREACHABLE, correctly and unhelpfully.
- M2: the RTC battery state on kernel-hub. The Pi 5 has a real-time clock that only holds time
  across power loss if its RTC battery is present and charged. A Pi that booted with a dead RTC
  and no NTP has a wrong wall clock, which puts a wrong `measured_at` and `received_at` on every
  measurement, and makes `STALE_TIMESTAMP` demonstrations meaningless. Check with
  `timedatectl` before each demo and confirm "System clock synchronized: yes".

---

## 11. Demo checklist, one page

### Before the demo, in this order

1. On kernel-hub: `timedatectl`, confirm "System clock synchronized: yes" (M2).
2. On kernel-hub: start the emulator in daemon form,
   `./deploy/run_emulator.sh --transport WIFI --daemon-wifi` (PR3 makes both flags real). Confirm
   `curl http://<pi>:8090/health` reports the expected version (PR0).
3. On the iQOO: Settings, app permissions, confirm the local-network toggle is ON for the dev
   build (M1).
4. In the app: open a patient, reach the Compounder screen, confirm the instrument selector and
   Start/Stop controls are visible and the scenario selector reads NORMAL.

### Scenario per instrument, to show hypo, normal and hyper truthfully

| Instrument | LOW | NORMAL | HIGH |
|---|---|---|---|
| BP | 85/55, pulse 52 | 120/80, pulse 72 | 173.5/105, pulse 112 |
| SPO2 | 87 to 88% | 98 to 99% | 99%, pulse 115 |
| THERMOMETER | 35.1 Cel | 36.7 Cel | 39.2 Cel |
| GLUCOMETER | 58 mg/dL | 95 mg/dL | 245 mg/dL |
| WEIGHT_SCALE | 45.0 kg | 68.5 kg | 112.5 kg |
| HEART_RATE | 46 bpm | 72 bpm | 132 bpm |

Values are those at step 1 of the single-instrument path, before the D-9 jitter, which moves them
slightly and does not move them across a scenario boundary.

### The emergency path, stated honestly

BP HIGH lands at systolic 173.5, which the mapper rounds to 174. The emergency ceiling is 180
(`CheckEmergencyThresholdsUseCase.BP_SYSTOLIC_CEILING_MMHG`). BP HIGH therefore does NOT trigger
the emergency override. This is F.3 and it is correct behaviour, not a bug: 174 systolic is
hypertensive and wants a doctor, it is not an acute emergency that must bypass store-and-forward.

Demonstrate the emergency path with one of:

- BP LOW: systolic 85, below the 90 floor. Triggers.
- SPO2 LOW: 87 to 88%, below the 90% floor. Triggers.

Say it out loud during the demo: "BP HIGH is hypertensive but not an emergency by our threshold,
so I am showing you the emergency path with a low reading, which is what actually crosses it."
Claiming BP HIGH triggers the emergency path, and having it not trigger, is the worst available
demo outcome.

### Fault paths worth showing, if there is time

- Scenario DEVICE_ERROR: the Pi returns `0.0` values with `quality_status: ERROR`. The app
  rejects at the boundary, no field is written, the worker sees an instrument error. This is the
  D-13 control, RC-2, visible. It is a good thing to show a regulator.
- Press Stop, then Start on a different instrument: the previous session's reading is not
  accepted (RC-1).

---

## 12. Open questions for the operator

Q1. Pi host and port configuration. The plan puts `PI_GATEWAY_BASE_URL` in the dev flavour's
`buildConfigField`, read from `local.properties`, mirroring the existing `BACKEND_BASE_URL` line.
That means changing the Pi's IP requires a rebuild. The alternative is a dev-only settings field
in the app. Recommendation: `local.properties`, matching what the repo already does for the
backend. Confirm, or ask for the in-app field.

Q2. Does the Start control need a patient-visible or worker-visible statement that the readings
are synthetic? Every payload carries `synthetic: true`, and the Pi's own banner says so, but the
app currently surfaces nothing. Recommendation: a single dev-only supporting-text line under the
instrument selector reading "Simulated instrument, dev build only". Cheap, and it removes any
chance of a demo audience believing they saw a real cuff.

Q3. Delete `MockVitalsSource.kt`? Once the dev binding moves to `PiGatewayVitalsSource`, it is
unbound dead code. `fillDemoData()` already covers the demo prefill and is explicitly
worker-triggered rather than silent. Recommendation: delete it in PR4. Confirm, because it
changes the existing demo behaviour: the Compounder screen will no longer arrive pre-populated
with randomised vitals.

Q4. Should the two new audit actions appear in `PatientFacingAudit.kt`? Recommendation: no. There
is no useful patient-facing sentence for "the instrument reading did not correlate". Confirm.

Q5. PR4 and PR5 split, or one PR? Section 8 splits them so the backend mirror gets its own
review. They can be merged into one landing if the operator prefers a single commit containing
both the emit sites and the accepted-set entries. The sync-rejection trap is satisfied either
way, since the constraint is only that the Kotlin value and the Python mirror entry share a
commit.

Q6. Section 7.6 records G-A as a documented defect carried forward. Confirm that the composite
path stays unused until G-A is fixed, and that this memo is the place that record lives until the
risk file is signed.

---

## Final stop gate

Nothing was written outside `scratchpad/pi-relay-reconciliation-memo.md`. No code was changed. No
commit was made. `PROGRESS.md` was not updated. No `.env` or credential file was opened. No file
in `docs/` was touched.

Awaiting operator signature on section 9 and answers to section 12 before any PR0 work begins.
