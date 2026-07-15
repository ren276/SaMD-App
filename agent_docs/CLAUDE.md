# PHC SaMD — Claude Code memory

Android mockup → future SaMD for rural Indian PHCs. Investor demo is live (8 screens built). Current phase: hardening the mockup with real security/audit primitives without turning it into a full production build.

Full details, don't duplicate here — read on demand:
- `agent_docs/spec.md` — data models, screen-by-screen spec
- `agent_docs/hardening.md` — security/audit/cache work, triaged now-vs-later
- `PROGRESS.md` — what's done, what's next, read this first every session

## Stack (do not deviate without asking)

- Kotlin only, Jetpack Compose + Material 3, no XML layouts
- MVVM + Clean Architecture: `presentation/` (Android-dependent) → `domain/` (zero Android deps) → `data/`
- Hilt for DI, Coroutines + Flow for async, Room for persistence
- Retrofit + OkHttp when networking is needed — no AWS SDK yet, no real backend calls
- Versions pinned exactly in `libs.versions.toml`, no `+` ranges
- `minSdk` 26/27 (field devices), `compileSdk`/`targetSdk` latest (dev device is Pixel 9 Pro)

## Two mock boundaries — keep mocked, don't build real yet

- `VitalsSource` interface (`domain/`) — `MockVitalsSource` returns randomized fake data. UI/ViewModel never touches the mock directly.
- `TranscriptionService` interface (`domain/`) — implemented with Android's `SpeechRecognizer` (this one is real, not faked — reads better in a demo).

## Now hardening (real, not demo theater)

- **SQLCipher** on the Room database — encrypt at rest, passphrase from Android Keystore. Swap `SupportFactory` in `DatabaseModule.kt`, not a schema rewrite.
- **Audit logger** — `AuditLogEntity`, separate Room table, **insert-only**: no `UPDATE`/`DELETE` DAO methods should exist on it at all, not just "unused." Log every clinical action (encounter start, audio captured, kernel response, consultation locked) with timestamp, user ID, patient ID, payload.
- **Local cache** — see `agent_docs/hardening.md` for scope; don't let this turn into syncing the entire patient DB to the tablet.

## Anti-patterns already hit (don't reintroduce)

- Don't call AWS SDKs directly from `data/` — abstracted behind repository interfaces, no exceptions.
- Don't add fields to `domain/model` without checking `agent_docs/spec.md` first — flag gaps, don't silently extend.
- Don't put demo-only UI (role switcher, network-drop toggle) behind the same interfaces as real hardening — see `agent_docs/hardening.md` for which is which.