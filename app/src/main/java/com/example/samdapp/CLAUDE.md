# PHC SaMD mockup — build instructions for Claude Code

This file is the working brief for an investor-facing Android mockup of a PHC (Primary Health Centre) patient care app. Read this fully before writing code. This is not throwaway UI — every screen must be wired to real local persistence from the first commit. Two integration points (device vitals, audio transcription) are intentionally mocked behind interfaces; everything else should be real.

## Project summary

A SaMD (Software as a Medical Device) for rural Indian PHCs. Flow: register a patient → capture vitals → record a consultation (voice/text + attachments) → send to a processing "kernel" (mocked, future AI) → get a transcription of any audio → acknowledge and save locally → pick an available doctor and send the case → (future, not this phase) doctor sends back a prescription.

Target audience for this build: investor demo. It must look and feel finished (big buttons, accessible, no dead ends) even though several backend pieces are stubs.

## Non-negotiable architecture decisions

Do not deviate from these without asking first — they were chosen deliberately, not defaults.

- **Language:** Kotlin only.
- **UI:** Jetpack Compose, Material 3. No XML layouts.
- **Architecture:** MVVM with Clean-Architecture-style layering:
    - `presentation/` — Compose UI + ViewModels. Android-dependent.
    - `domain/` — use cases, plain Kotlin data classes and interfaces. **Zero Android dependencies.** This layer must compile without importing anything from `android.*`.
    - `data/` — Room database, repositories, mock/remote data sources.
- **DI:** Hilt.
- **Async:** Kotlin Coroutines + Flow throughout. No callbacks, no RxJava.
- **Local persistence:** Room (SQLite). Every screen reads/writes through a repository backed by Room — never hold form state only in a ViewModel and skip persistence.
- **Networking (when needed):** Retrofit + OkHttp. Do not add the AWS Amplify SDK or any AWS SDK in this phase — there is no real backend yet, and pulling in AWS tooling now creates coupling the architecture is explicitly trying to avoid.
- **Dependency versions:** Pin every version exactly in `gradle/libs.versions.toml` (a Gradle version catalog). No `+`, no dynamic ranges. Before scaffolding the project, check the current stable versions of Kotlin, AGP, and the Compose BOM directly from developer.android.com and kotlinlang.org — do not assume versions from training data, they will be stale. As of mid-2026 the Compose BOM was around `2026.06.00` and Kotlin around `2.3.x`, but confirm current numbers at build time.
- **`minSdk`:** 26 or 27 (Android 8/8.1). This targets realistic low-end PHC field devices, not the developer's test device. Do not raise this without discussion.
- **`compileSdk` / `targetSdk`:** latest stable at build time. This only affects the toolchain, not field compatibility, so no reason to hold it back.
- **Testing:** Add JUnit + Compose UI tests for at least the Register and Vitals screens before considering the mockup "done." Not exhaustive coverage — just enough that a future change can't silently break the core flow.
- **CI:** Set up a GitHub Actions workflow that runs a debug build + unit tests on push. Do this early, not as a last step.

## Two explicit mock boundaries — do not build the real thing yet

1. **Vitals source.** Define a `VitalsSource` interface in `domain/` (e.g. `suspend fun readVitals(): VitalsReading`). Implement one `MockVitalsSource` that returns plausible fake data (with slight randomization so the demo doesn't look static). The Vitals screen must depend only on the interface, injected via Hilt — never reference the mock implementation directly from UI or ViewModel code. This is the seam a real BLE implementation plugs into later.
2. **Transcription.** Use Android's built-in `SpeechRecognizer` API for a real (not hardcoded) transcription in the demo — it's a few lines of code and reads far better to investors than a fake string. Wrap it behind a `TranscriptionService` interface in `domain/` so it can be swapped for a custom model later without touching the consultation screen.

Everything else — registration, vitals entry UI, consultation capture, local save, doctor list, case status — should be fully functional against real local storage, not mocked.

## Data models (define these first, before any screen)

Create these as plain Kotlin data classes in `domain/model/`, then mirror them as Room `@Entity` classes in `data/local/`. Field lists below are a starting point, not exhaustive — flag anything you think is missing rather than silently adding fields.

### `Patient`
- `id: String` — locally generated UID, 10–12 alphanumeric characters, assigned at creation time (not server-assigned; this app is offline-first)
- `fullName: String` (required)
- `dateOfBirth: LocalDate?` (nullable — age-only entry is common in rural registration)
- `age: Int?` (nullable — fallback when DOB is unknown)
- `gender: String`
- `guardianOrSpouseName: String?`
- `mobileNumber: String?` (required: at least one contact method between this and address)
- `aadhaarNumber: String?` (optional)
- `abhaNumber: String?` (optional — Ayushman Bharat Health Account ID, 14-digit; optional field, do not build ABHA creation/linking flow, just capture the string)
- `village: String?`, `block: String?`, `district: String?`, `state: String?`, `pincode: String?`
- `category: String?` (General/OBC/SC/ST — used for scheme eligibility)
- `occupation: String?`
- `maritalStatus: String?`
- `bloodGroup: String?`
- `knownAllergies: String?`
- `emergencyContact: String?`
- `createdAt: Instant`

Only `id`, `fullName`, and one contact method should be required in the UI. Everything else optional — do not block registration on missing fields.

### `VitalsReading`
- `id: String`, `patientId: String` (FK), `recordedAt: Instant`
- `pulseBpm: Int?`
- `bpSystolic: Int?`, `bpDiastolic: Int?`
- `spo2Percent: Int?`
- `temperatureCelsius: Double?`
- `respiratoryRate: Int?`
- `weightKg: Double?`, `heightCm: Double?`, `bmi: Double?` (calculated from weight/height, don't ask the user for it directly)
- `bloodGlucoseMgDl: Int?`
- `source: String` — `"manual"` or `"device"` (set to `"manual"` for now; the field exists so `MockVitalsSource` output is distinguishable later from real device data)

### `Consultation`
- `id: String`, `patientId: String` (FK)
- `chiefComplaint: String` (from voice or text input)
- `durationBucket: String?` — one of `"today"`, `"few_days"`, `"week_plus"`, `"chronic"`
- `severity: String?` — one of `"mild"`, `"moderate"`, `"severe"`
- `relevantHistory: String?`
- `attachments: List<Attachment>`
- `transcription: String?` — filled in by `TranscriptionService` if an audio attachment exists
- `createdAt: Instant`

### `Attachment`
- `id: String`, `consultationId: String` (FK)
- `type: String` — `"image"`, `"video"`, `"audio"`, `"affected_area_photo"` (treat this as distinct from a generic image — it's the most common real attachment type in rural teleconsultation)
- `uri: String` (local file URI)

### `Doctor` (mock/static data for this phase — a JSON asset is fine, no backend call)
- `id: String`, `name: String`, `specialty: String`, `available: Boolean`, `facilityName: String?`

### `CaseRecord`
Bundles a patient's registration + latest vitals + latest consultation + sync status. This is the object whose lifecycle actually matters:
- `id: String`, `patientId: String`, `vitalsId: String?`, `consultationId: String?`
- `status: String` — `"draft"`, `"saved_locally"`, `"sent_to_doctor"` (no `"synced_to_cloud"` state yet — that's future work, but name the field so it's forward-compatible)
- `assignedDoctorId: String?`
- `updatedAt: Instant`

## Screen flow (build in this exact order)

1. **Home** — big-button entry point, single primary action: "Register new patient."
2. **Register** — patient form per the `Patient` model above. Simple text inputs, large touch targets, minimal required fields.
3. **Vitals** — numeric input fields for the core four (pulse, BP, SpO2, temperature) plus the secondary set (respiratory rate, weight, height → auto-calculated BMI, blood glucose). Pull an initial value from `VitalsSource` (the mock) as a pre-fill the user can edit, don't require typing everything from scratch — this is what makes the mock feel real in a demo.
4. **Consultation** — chief complaint via voice or text (toggle), duration bucket, severity, relevant history, attachment picker (image / video / audio / affected-area photo) via `ActivityResultContracts`.
5. **Sending** — a short async delay (1–2s) with a progress indicator representing the (mocked) handoff to the kernel. No real network call.
6. **Transcription** — if an audio attachment exists, run it through `TranscriptionService` (Android `SpeechRecognizer`) and show the result; skip this screen if there's no audio.
7. **Acknowledgement / save** — write the full `CaseRecord` to Room with `status = "saved_locally"`. This is a real write, not a UI-only confirmation.
8. **Doctor list** — static/mock list from a bundled JSON asset. Selecting a doctor and tapping "send" updates `CaseRecord.status` to `"sent_to_doctor"` and sets `assignedDoctorId`. No real backend call — this is intentionally the last mocked step in the demo.

Prescription return flow is **out of scope for this build** — do not implement it, just leave the `CaseRecord` model forward-compatible (it already is, via `status`).

## Package structure to scaffold first

```
app/
  presentation/
    home/
    register/
    vitals/
    consultation/
    sending/
    transcription/
    acknowledgement/
    doctorlist/
  domain/
    model/
    repository/        (interfaces only)
    usecase/
    vitalssource/       (VitalsSource interface + contract)
    transcription/       (TranscriptionService interface)
  data/
    local/               (Room entities, DAOs, database)
    repository/          (repository implementations)
    mock/                (MockVitalsSource, static doctor JSON loader)
  di/                    (Hilt modules)
```

Keep this package split even though it's a single Gradle module for now — a future multi-module split should be a mechanical move of packages into modules, not a redesign.

## Definition of done for this phase

- All 8 screens implemented, wired to real Room-backed repositories, navigable end to end with no dead ends.
- `VitalsSource` and `TranscriptionService` interfaces exist and are the only way UI/ViewModel code touches vitals or transcription — no direct references to the mock implementations outside the DI module and their own implementation files.
- Runs on the Pixel 9 Pro test device.
- Also runs on at least one low-end/older Android emulator profile (API 26/27, low RAM) — this is cheap to check now and expensive to discover later. Report anything that breaks rather than silently working around it.
- Basic JUnit + Compose UI tests exist for Register and Vitals.
- GitHub Actions workflow runs build + tests on push.
- No AWS SDKs, no real network calls, no hardcoded transcription strings.

## What to ask about before proceeding, rather than guessing

- Whether the two apps mentioned in the original scope (beyond this PHC-worker app) share this codebase as a module, or are separate repos.
- Exact color/typography direction if a design system isn't provided — default to Material 3 defaults with large touch targets rather than inventing a custom visual identity.
- Any specific doctor specialties or mock data content the founder wants visible in the investor demo.