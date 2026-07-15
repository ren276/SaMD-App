# Hardening — triaged from SaMD research

Source: founder's Notion research page on hardening the mockup toward a real SaMD. That doc is good but mixes three different categories of work together. Split out here so Claude Code doesn't over-scope a single session.

## Doing now (real, not demo)

- **SQLCipher** — encrypt the Room DB at rest. `net.zetetic:android-database-sqlcipher`, passphrase from Android Keystore, swap the `SupportFactory` in `DatabaseModule.kt`. This is genuinely a small change given the existing Clean Architecture — don't let it balloon into a bigger refactor.
- **Audit logger** — `AuditLogEntity` per `agent_docs/spec.md`. Insert-only at the DAO level. This one matters even for a mockup because it's cheap to build now and expensive to retrofit once real clinical actions are flowing through the app.
- **Local cache scope** — cache only what's needed for the current session/day's patients, not a full local mirror of a growing patient database. This is a data-minimization decision, not just a performance one — it directly reduces what's exposed if a device is lost. Don't build a general-purpose cache layer; scope it to "today's scheduled patients" from the start.

## Worth adding to the demo (cheap, high perceived value, not real infra)

These make the mockup *look* like it understands production concerns without requiring you to actually build the concern yet:

- **"AI Assessment Panel" placeholder** — when `SendToKernelUseCase` returns, show a confidence score + an explainability expandable + a "I have reviewed and verified this" checkbox before allowing "Complete Consultation." The kernel itself is still mocked; this UI just proves you've thought about automation bias (ISO 14971 human-in-the-loop). Build this as part of the existing Sending/Acknowledgement screens, not as new architecture.
- **Offline simulation toggle** — a debug menu item that fakes a network drop, so you can demo "saved locally, 1 pending sync" live. This is UI-only; it doesn't need `NetworkMonitor` to be wired to anything real yet.
- **Security shield icon** — a bottom sheet showing "Encrypted via SQLCipher" / "TLS 1.3" as status text. Once SQLCipher is actually in (see above), this can show real status instead of a simulated one — sequence it after the real work, not before.

Do not build these before the "doing now" list above — they're presentation, not substance, and should never be the reason the real hardening slips.

## Explicitly later (production-phase, not mockup-phase)

Don't start these yet — noted here so they're not forgotten, not so they're started early:

- Role-Based Access Control (ASHA worker / nurse / medical officer views) — real auth doesn't exist yet; a login switcher without real auth behind it is demo theater, not RBAC, and building the real thing now is premature.
- WorkManager-based sync worker — no real backend to sync to yet.
- AWS infra (Aurora/RDS Postgres, S3, Cognito, Lambda) — none of this until there's a reason to stand up real cloud infra. The Notion doc's stack recommendation (AWS Mumbai region for DPDP data localization, managed Postgres over NoSQL for relational medical data, S3 for attachments, Cognito for auth) is a reasonable target architecture to keep on file, but don't provision anything from it yet.
- QMS (ISO 13485) and Design History File — these are organizational/process artifacts that need to exist *before* production code is written, not something Claude Code can build. Flag to the founder as a pre-production blocker, don't attempt to generate QMS documentation as a coding task.
- `ai_kernel_version` field on `CaseRecord`/`AuditLogEntity` — add this when the kernel stops being mocked, not before; adding it now with no real versioned kernel behind it is a field with no meaning yet.