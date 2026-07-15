# Progress

Read this first, every session. Continue from the first unchecked item unless told otherwise.

## Mockup (done)
- [x] Repo, package structure, version catalog
- [x] Core data models + Room entities
- [x] Home screen
- [x] Register screen
- [x] Vitals screen
- [x] Consultation screen
- [x] Sending screen
- [x] Transcription screen
- [x] Acknowledgement/save screen
- [x] Doctor list screen
- [x] Runs end to end on Pixel 9 Pro

## Hardening (in progress)
- [x] SQLCipher on Room DB (see agent_docs/hardening.md)
- [ ] `AuditLogEntity` + insert-only DAO
- [ ] Wire audit logging into the 8 existing screens (one commit per screen, don't do all 8 in one pass)
- [ ] Local cache scoped to current day's patients only

## Not started
- [ ] JUnit + Compose UI tests for Register and Vitals
- [ ] GitHub Actions CI (build + test on push)
- [ ] Demo-theater additions from agent_docs/hardening.md (AI assessment panel, offline toggle, security shield sheet) — do after hardening, not before