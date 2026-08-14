# PHC SaMD — Agent Memory

Android mockup → SaMD for rural Indian PHCs. Investor demo live. Target: ASHA workers,
nurses, compounders at a PHC. The doctor's review/prescription UI runs on a **separate
channel** — this app is the PHC-worker side only.

---

## Read first, on demand ( and other docs as they are made )

| File | Contains |
|------|---------|
| `agent_docs/spec.md` | Data models, screen-by-screen spec, package structure |
| `agent_docs/hardening.md` | Security/audit triage, what's done vs. deferred |
| `PROGRESS.md` | Ground truth for current state — read this, not just CLAUDE.md |
| `docs/requirements/software-requirements.md` | REQ-IDs — reference these when touching any requirement |
| `docs/requirements/traceability-matrix.md` | Requirement → code → test mapping |
| `docs/sync-design.md` | Offline-first sync architecture |
| `docs/data-retention.md` | Per-table deletion posture (insert-only vs. soft-delete vs. mutable) |

---

## File placement — three categories, explicit rule

| Directory | Category | Git | Rule |
|-----------|----------|-----|------|
| `docs/` | Controlled documentation | **Tracked** | IEC 62304-controlled. Regulatory, requirements, QMS. Never write clinical/audit-clean content anywhere else. |
| `agent_docs/` | Agent scratch | **Gitignored** | Spec, hardening plan, this file. Local only. Never commit. |
| `second-brain/` | Personal vault | **Gitignored** | Founder's opinions, mistakes log, working style. Mirrored to a separate private repo by the founder. Agents append to `mistakes-log.md` when they catch a wrong turn. **Never `git add` anything here from this repo's working tree.** |
| `backend/` | Backend source | **Tracked** | FastAPI Core API + ABDM adapter. Same repo, separate CI pipeline. |

---

## Stack — do not deviate without asking

- **Language/UI:** Kotlin only. Jetpack Compose + Material 3. No XML layouts.
- **Architecture:** MVVM + Clean Architecture.  
  `presentation/` (Android-dependent) → `domain/` (zero Android deps) → `data/`
- **DI:** Hilt. Not Koin — see `second-brain/decisions-and-opinions.md` for why.
- **Async:** Coroutines + Flow throughout.
- **Persistence:** Room + SQLCipher (encrypted at rest, passphrase from Android Keystore).
- **Networking:** Retrofit + OkHttp + Gson. Two real endpoints exist:
  - `POST /v1/assess` — `/v1/assess` XGBoost confidence + differentials (`RemoteKernelSource` / `RetrofitKernelSource`). Has a mock fallback.
  - `POST /api/v1/evaluate` — NLEM drug/dosage/brand-mapping/vitals-triage (`EvaluateKernelSource` / `RetrofitEvaluateSource`). **No mock fallback** — failure omits that section, no crash.
  - Base URLs come from `BuildConfig` fields, flavor-scoped — never hardcoded in
    `NetworkModule`. `KERNEL_BASE_URL` is wired today; `BACKEND_BASE_URL`/
    `ABHA_BACKEND_BASE_URL` exist as flavor-scoped fields but have no consumer yet (no
    `backend/` Retrofit service exists). See `## Environments` below for per-flavor values.
- **External AI:** Gemini API (`gemini-2.5-flash`, thinking disabled for latency) for India brand-name lookup. Key in `local.properties` → `BuildConfig.GEMINI_API_KEY`. gitignored.
- **Versions:** pinned exactly in `libs.versions.toml`. No `+` ranges.
- **SDK:** `minSdk` 26/27 (field devices). `compileSdk`/`targetSdk` latest. Dev device: Pixel 9 Pro.

### Backend (in progress)

FastAPI, Python 3.12, PostgreSQL, SQLAlchemy 2.0, Alembic, Redis, JWT via python-jose, httpx,
Docker. Location: `backend/core/` and `backend/abdm-adapter/`.

### Build flavors

See `## Environments` below.

---

## Auth & session

- **`MockAuthSession`** (`data/local/auth/`) — no credential check. Preferences DataStore (not Room: one small key-value blob). Survives app restart.
- **`AuthViewModel`** — Activity-scoped. Gates the Nav display: cold-start with no session → Login screen; session exists → skip directly. Drives Home's signed-in display and the idle-lock re-auth gate.
- **`UserRole`:** `ASHA_WORKER`, `NURSE`, `COMPOUNDER` (PHC-field roles). Admin/CMO dashboard is out of scope.
- **userId:** derived deterministically from `name + role` (SHA-256, truncated) — same worker signing in on different days keeps the same audit-trail userId.
- **Biometric gate (REQ-SEC-03, PARTIAL):** `BiometricPrompt` fires on Sign-in tap, after name+role entry. Gates `AuthSession.signIn`. Worker login only, not the ABHA patient flow. A device with no biometric enrolled *and* no screen lock is refused outright.
- **Idle lock:** `IdleLockViewModel` (Activity-scoped) — 75s no-touch timeout → `IdleLockScreen` drawn over `NavDisplay` in a Box (never removes NavDisplay from composition — doing so would destroy in-flight ViewModels like `CompounderViewModel`, minting duplicate encounters).

---

## Connectivity

- **`ConnectivityController`** (`domain/connectivity`) — single source of truth for online state. Merges real `NetworkMonitor` AND the manual debug toggle. **Not just a UI flag** — the send path (`DoctorAssignmentConfirmViewModel.onConfirm()`) checks this before sending.
- **`ConnectivityViewModel`** — thin wrapper delegating to `ConnectivityController`. Activity-scoped shared instance, same pattern as `AuthViewModel`/`IdleLockViewModel`.
- **`CaseStatus.PENDING_SYNC`** — the queued state. Offline doctor confirm → `PENDING_SYNC`, not `SENT_TO_DOCTOR`. Auto-syncs on reconnect (real network back or worker flips toggle).

---

## Environments

Three Gradle product flavors under `flavorDimension "environment"` in `app/build.gradle.kts`,
so all three can be installed side by side on one device.

| Flavor | applicationId suffix | `KERNEL_BASE_URL`/`BACKEND_BASE_URL`/`ABHA_BACKEND_BASE_URL` | Cleartext |
|--------|----------------------|---------------------------------------------------------------|-----------|
| `dev` | `.dev` | current LAN IPs over `http://` | Allowed — `src/dev/AndroidManifest.xml` sets `usesCleartextTraffic="true"` |
| `staging` | `.staging` | `https://staging.samd.example.com/...` (placeholder — ABDM sandbox, infra not deployed yet) | Blocked (platform default) |
| `prod` | none | `https://api.samd.example.com/...` (placeholder — ABDM production, infra not deployed yet) | Blocked (platform default) |

Cleartext is **dev-only**. The main manifest no longer sets `usesCleartextTraffic` — only the
`dev` flavor's manifest override does. Never add it back to the main manifest.

---

## Kernel integration — current state

### `/v1/assess` path (GenerateKernelReportUseCase)
Real FastAPI + XGBoost call. `RemoteKernelSource` / `RetrofitKernelSource`. Has a mock
scenario-table fallback on any failure (network down, timeout, server offline). The per-record
`inferenceSource` (`REAL_INFERENCE` / `MOCK_FALLBACK`) is stamped on `KernelReportOutput`.
`GenerateKernelReportUseCase` fires in `SendingViewModel`.

### `/api/v1/evaluate` path (GenerateEvaluateReportUseCase)
Real FastAPI + XGBoost call. `EvaluateKernelSource` / `RetrofitEvaluateSource`. Returns NLEM
drug/dosage/brand-mapping/vitals-triage via `EvaluateReportOutput`. **No mock fallback** —
failure just means those fields are absent from the report, app never crashes. Fires alongside
`/v1/assess` in `SendingViewModel`.

### KernelAssessmentScreen display
`AssessmentDisplay` is sourced from `EvaluateReportOutput` **first** (per-candidate
confidence/reasoning, no mock path). Falls back to `KernelReportOutput` (`/v1/assess`,
has its own REAL/MOCK split) only when no evaluate output exists. The old canvas block
sourced purely from `KernelReportOutput` has been **deleted** — do not try to add it back.

---

## Doctor review flow — current shape

Old shape (Phase 5): async mock-inbox polling via `ReceiveDoctorPrescriptionUseCase`.
**Superseded.** `MockDoctorPrescriptionInbox` still exists as plumbing but is no longer the
primary path.

Current shape: `PatientSummaryScreen` → "Review AI diagnosis (doctor)" → shows
`EvaluateReportOutput` top candidate → physician AGREE / MODIFY / REJECT picker →
`SubmitDoctorDecisionUseCase` → persists `DiagnosisFeedback` + builds final `Prescription`.
- MODIFY/REJECT: manual drug name + dosage, optional Gemini brand-lookup button, ICD dropdown
  (`TRAINED_ICD_CANDIDATES`, 18 classes the model actually trained on — validated in use case).
- AGREE: no extra input needed.
- Logs `AuditAction.DIAGNOSIS_FEEDBACK_RECORDED`.
- `PhysicianDecision.outcomeExplanation()` — investor-demo copy explaining training-pipeline
  implication of each decision.

---

## Named mock/real boundaries — keep mocked, don't build real yet

| Interface | Location | Status |
|-----------|----------|--------|
| `VitalsSource` | `domain/vitalssource/` | Mock only — `MockVitalsSource` returns randomized fake data |
| `TranscriptionService` | `domain/transcription/` | Real — Android `SpeechRecognizer` |
| `DoctorPrescriptionInbox` | `domain/doctor/` | Mock — `MockDoctorPrescriptionInbox` (plumbing, not primary path anymore) |
| `RemoteKernelSource` | `domain/kernel/` | Real API + mock fallback |
| `EvaluateKernelSource` | `domain/kernel/` | Real API, no fallback |
| `BrandLookupSource` | `domain/kernel/` | Real Gemini call, best-effort |

---

## Screen landscape (25+ presentation modules)

Home, Login, AbhaEntry, AbhaSignUp, AbhaLogin, AbhaOtp, Register, MedicalBackground,
Compounder, Consent, EmergencyOverride, Consultation, Sending, KernelAssessment,
Transcription, Acknowledgement, DoctorAssignmentConfirm, DoctorList, PatientSummary,
ConsultationChain, PatientAudit, Report, Referrals, Patients, Profile.

Bottom nav tabs: **Home / Patients / Referrals / Profile.** Bar is absent from
in-flow screens (Register, ABHA, Consultation, Compounder, Sending, Transcription,
Acknowledgement, KernelAssessment, Consent, EmergencyOverride, Report, DoctorList,
DoctorAssignmentConfirm) by construction — they never receive a `bottomBar` param.

---

## Anti-patterns — do not reintroduce

- Don't call AWS SDKs directly from `data/` — abstracted behind repository interfaces.
- Don't add fields to `domain/model/` without checking `agent_docs/spec.md` first — flag gaps, don't silently extend.
- Don't put demo-only UI behind the same interfaces as real hardening — see `agent_docs/hardening.md` for which is which.
- Don't create a Room entity without a companion `MIGRATION_N_M` in `DatabaseModule`. DB is currently at **v12**. Next migration is `MIGRATION_12_13`.
- Don't add a clinical action point without `AuditLogger.log(AuditAction.X)` — every clinical touch point must be logged. See `AuditAction.kt` for existing constants; add a new constant if none fits.
- Don't write regulatory documentation to `agent_docs/` — it belongs in `docs/` (tracked).
- Don't build the "explicitly later" list from `agent_docs/hardening.md` — it's there so it isn't forgotten, not so it gets started early.
- Don't add an all-patients query to the DAO — the only list query is day/week-scoped (data minimisation, REQ-ROS-02 / H-04).
- Don't re-litigate Hilt vs. Koin, multi-module, or `Result<T,E>` — all three are settled. See `second-brain/decisions-and-opinions.md`.
- Don't hardcode base URLs in `NetworkModule`. All URLs come from `BuildConfig`, flavor-scoped.

---

## Agent skills

### Issue tracker

Issues and specs live as GitHub issues in `ren276/SaMD-App`, via the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Default five canonical labels (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`). See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: `CONTEXT.md` + `docs/adr/` at repo root. See `docs/agents/domain.md`.