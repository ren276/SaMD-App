# Hardening — triaged from SaMD research

Source: founder's Notion research page on hardening the mockup toward a real SaMD. That doc is good but mixes three different categories of work together. Split out here so Claude Code doesn't over-scope a single session.

## Doing now (real, not demo)

- **SQLCipher** — encrypt the Room DB at rest. `net.zetetic:android-database-sqlcipher`, passphrase from Android Keystore, swap the `SupportFactory` in `DatabaseModule.kt`. This is genuinely a small change given the existing Clean Architecture — don't let it balloon into a bigger refactor.
- **Audit logger** — `AuditLogEntity` per `agent_docs/spec.md`. Insert-only at the DAO level. This one matters even for a mockup because it's cheap to build now and expensive to retrofit once real clinical actions are flowing through the app.
- **Local cache scope** — cache only what's needed for the current session/day's patients, not a full local mirror of a growing patient database. This is a data-minimization decision, not just a performance one — it directly reduces what's exposed if a device is lost. Don't build a general-purpose cache layer; scope it to "today's scheduled patients" from the start.

## Worth adding to the demo (cheap, high perceived value, not real infra)

These make the mockup *look* like it understands production concerns without requiring you to actually build the concern yet:

- **"AI Assessment Panel" placeholder** — when `SendToKernelUseCase` returns, show a confidence score + an explainability expandable + a "I have reviewed and verified this" checkbox before allowing "Complete Consultation." **Update 2026-07-21: the kernel is no longer purely mocked.** `GenerateKernelReportUseCase` now calls a real local FastAPI + XGBoost kernel over Retrofit (`data/remote/RetrofitKernelSource`, `domain/kernel/RemoteKernelSource`) as its primary path, and only falls back to the original mock scenario table on any API failure (network down, timeout, server offline). The panel itself is unchanged either way — still proves the human-in-the-loop checkpoint (ISO 14971), now gating a result that may be real inference, not always a mock.
- **Offline simulation toggle** — a debug menu item that fakes a network drop, so you can demo "saved locally, 1 pending sync" live. **Update 2026-07-19: this is no longer UI-only.** The manual toggle and real `NetworkMonitor` now combine into one `ConnectivityController.isOnline` signal that the doctor-assignment send path actually checks — flipping offline (either way) genuinely queues instead of sending. See `docs/sync-design.md`.
- **Security shield icon** — a bottom sheet showing "Encrypted via SQLCipher" / "TLS 1.3" as status text. Once SQLCipher is actually in (see above), this can show real status instead of a simulated one — sequence it after the real work, not before.

Do not build these before the "doing now" list above — they're presentation, not substance, and should never be the reason the real hardening slips.

## Explicitly later (production-phase, not mockup-phase)

Don't start these yet — noted here so they're not forgotten, not so they're started early:

- Role-Based Access Control (ASHA worker / nurse / medical officer views) — real auth doesn't exist yet; a login switcher without real auth behind it is demo theater, not RBAC, and building the real thing now is premature.
- WorkManager-based sync worker — no real backend to sync to yet.
- AWS infra (Aurora/RDS Postgres, S3, Cognito, Lambda) — none of this until there's a reason to stand up real cloud infra. The Notion doc's stack recommendation (AWS Mumbai region for DPDP data localization, managed Postgres over NoSQL for relational medical data, S3 for attachments, Cognito for auth) is a reasonable target architecture to keep on file, but don't provision anything from it yet.
- QMS (ISO 13485) and Design History File — these are organizational/process artifacts that need to exist *before* production code is written, not something Claude Code can build. Flag to the founder as a pre-production blocker, don't attempt to generate QMS documentation as a coding task.
- `ai_kernel_version` field on `CaseRecord`/`AuditLogEntity` — add this when the kernel stops being mocked, not before; adding it now with no real versioned kernel behind it is a field with no meaning yet. **Note (2026-07-21, closed): this gap is now closed — every persisted `KernelReportOutput` carries a non-nullable `inferenceSource: InferenceSource` (`REAL_INFERENCE` | `MOCK_FALLBACK`), stamped in `GenerateKernelReportUseCase` and surfaced in the AI Assessment Panel and exported report (REQ-HAN-08).** `ai_kernel_version` itself remains deferred — this closes the per-record real-vs-mock marker gap, not the versioning field.

## Evaluated and deferred: new Android skills (2026-07-16)

Source: Philip Lackner's Android skills (Koin DI, `:core`/`:feature` multi-module layout,
`Result<T,E>`/`DataError` typed error wrapper, strict MVI Action/Event naming) were added to
`.claude/skills/` and evaluated against the current architecture (Hilt, single `:app` module,
existing stdlib `Result<T>` + sealed `DataError`, existing Actions/Effects pattern that's already
close to MVI).

**Decision: no architecture changes now.** Priority is UI/UX polish, not architecture or
code-quality changes — don't run two unrelated change-sets across the same screens at once. Three
sub-decisions, not one bundled "adopt or don't":

- **Multi-module split** — defer until real feature boundaries are stable. Module boundaries
  chosen before the boundaries are known risk being wrong, and splitting twice is worse than
  splitting once.
- **Koin vs. Hilt** — leaning toward keeping Hilt even long-term, not just current inertia.
  Dagger/Hilt resolves DI at compile time (wiring errors fail the build); Koin resolves at
  runtime (wiring errors can crash in the field). For a SaMD-track app under IEC 62304,
  compile-time-safe DI is likely the more appropriate choice regardless of project stage. Revisit
  only if a concrete reason to reconsider comes up.
- **`Result<T,E>`/`DataError` error-type migration** — the one piece worth reconsidering later.
  More contained than the other two, doesn't conflict with the tracked regulatory docs the way a
  DI/module change would, and could genuinely improve field-worker-facing error messages (ties
  into usability/IEC 62366 work). Worth a dedicated session once UI/Hindi work is done, not
  before.

No code changes were made as part of this evaluation.