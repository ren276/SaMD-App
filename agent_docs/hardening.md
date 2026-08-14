# Hardening — triaged from SaMD research

Source: founder's Notion research page. Split out here so agents don't over-scope a session.

---

## Done (real, not demo theater)

These are complete — do not reopen them.

- **SQLCipher** ✓ — Room DB encrypted at rest. `net.zetetic:android-database-sqlcipher`,
  passphrase from Android Keystore, `SupportFactory` swapped in `DatabaseModule`.
  **Note (latest session):** safe non-destructive migration path implemented in
  `DatabasePassphraseProvider` — detects unencrypted DB, copies via `sqlcipher_export`,
  runs `PRAGMA integrity_check` before deleting the old plaintext file.
- **Audit logger** ✓ — `AuditLogEntity` + insert-only DAO. Wired at every clinical action
  point across all relevant screens. `AuditAction.kt` is the canonical constant list.
- **Local cache scope** ✓ — roster queries scoped to today (Home) / last 7 days (Patients
  tab). No all-patients query exists anywhere on the DAO, by design (data minimisation,
  REQ-ROS-02 / H-04). `docs/data-retention.md` is the canonical per-table posture record.

---

## Demo additions (cheap, high perceived value) — status

- **Kernel AI Assessment Panel** ✓ — `KernelAssessmentScreen` (`presentation/kernelassessment`).
  Now gating real inference, not just a mock. Current display priority:
  - Primary: `EvaluateReportOutput` (`/api/v1/evaluate`, per-candidate confidence/reasoning,
    **no mock path**). Canvas block for this is the only one in `ReportCanvasRenderer`.
  - Fallback: `KernelReportOutput` (`/v1/assess`, has own REAL_INFERENCE/MOCK_FALLBACK split).
  - The old `/v1/assess`-only canvas block has been **deleted**. Do not recreate it.
  Liability checkbox gating Continue. Logs `AuditAction.KERNEL_ASSESSMENT_ACKNOWLEDGED`.
- **Offline simulation toggle** ✓ — No longer cosmetic. Manual toggle + real `NetworkMonitor`
  merge into `ConnectivityController.isOnline` (single source of truth). The doctor-assignment
  send path checks this signal — flipping offline genuinely queues to `PENDING_SYNC` instead
  of sending. Auto-syncs on reconnect. See `docs/sync-design.md`.
- **Security shield icon** — Not built. Deprioritised once real SQLCipher was in — the
  shield would just be a label for something already real. Left as optional.

---

## Explicitly later (production-phase, not mockup-phase)

Don't start these yet — noted here so they're not forgotten, not so they get started early:

### Approaching (this quarter)

- **Role-Based Access Control** — real auth doesn't exist yet. Mock login (`MockAuthSession`,
  `UserRole`, biometric gate on sign-in tap) is the current posture. Real RBAC enforcement
  (REQ-SEC-03) needs real accounts, out of scope until there's a real backend.
- **WorkManager-based sync worker** — no real backend to sync to yet.
- **`ai_kernel_version` field** — per-record real-vs-mock marker is now closed
  (`inferenceSource: InferenceSource` on `KernelReportOutput`). Versioning field itself
  still deferred — add only when the kernel has a real versioned deployment.

### Still deferred

- **AWS infra** (Aurora/RDS Postgres, S3, Cognito, Lambda) — reasonable long-term target
  (AWS Mumbai for DPDP data localisation, managed Postgres for relational medical data,
  S3 for attachments, Cognito for auth). Don't provision anything from it yet.
- **QMS (ISO 13485) and Design History File** — organisational/process artefacts that need to
  exist *before* production code is written. Flag to founder as a pre-production blocker;
  don't attempt to generate QMS documentation as a coding task. See `docs/quality/`.

---

## Environment separation (done, 2026-08-14)

- **Gradle product flavors** — `dev`/`staging`/`prod` under `flavorDimension "environment"` in
  `app/build.gradle.kts`. Each gets its own `KERNEL_BASE_URL`/`BACKEND_BASE_URL`/
  `ABHA_BACKEND_BASE_URL`/`ENVIRONMENT` BuildConfig field. `dev`/`staging` carry an
  `applicationIdSuffix` (`.dev`/`.staging`) so all three can be installed side by side on one
  device — useful for demoing against a real staging backend without losing the dev build.
- **Cleartext scoped to dev only** — `android:usesCleartextTraffic="true"` moved out of the main
  manifest into `src/dev/AndroidManifest.xml`. Staging and prod inherit the platform default
  (cleartext HTTP blocked), matching their HTTPS-only base URLs. See REQ-SEC-05/06 in
  `docs/requirements/traceability-matrix.md`.
- Real TLS certs / staging & prod infra don't exist yet — the HTTPS URLs
  (`staging.samd.example.com`, `api.samd.example.com`) are placeholders until the backend is
  deployed. Don't point a real device at them expecting a live server yet.

---

## Backend security (planned)

- JWT auth
- HTTPS enforcement
- Request-ID middleware
- Structured logging
- Error envelope
- PostgreSQL TLS
- Redis AUTH
- Docker non-root
- Alembic tracking
- Server-side audit log with hash chain
- ABDM secrets in env vars only

---

## Evaluated and deferred: new Android skills (2026-07-16)

Philip Lackner's Android skills (Koin DI, `:core`/`:feature` multi-module, `Result<T,E>`/
`DataError` typed error wrapper, strict MVI Action/Event naming) were evaluated against the
current architecture.

**Decision: no architecture changes.** Three sub-decisions:

- **Multi-module split** — defer until real feature boundaries are stable.
- **Koin vs. Hilt** — keeping Hilt. Compile-time DI safety is the right call for a SaMD under
  IEC 62304. Dagger/Hilt wiring errors fail the build; Koin wiring errors crash in the field.
- **`Result<T,E>`/`DataError` migration** — worth reconsidering once Hindi/UX work is done.
  More contained than the other two, could improve field-worker error messages (IEC 62366).

No code changes were made as part of this evaluation.