# Requirements Traceability Matrix — initial

> Links each requirement (`software-requirements.md`) to its design/implementation and its
> verification (IEC 62304 §5.2/§5.7). **The verification column is the point of this document:**
> nearly everything is currently "Manual (emulator walkthrough)" with automated coverage
> **TODO** — that gap is blocker #4. Design column cites the owning component/package.

| REQ | Design / implementation | Risk link | Verification (current) | Automated test |
|-----|-------------------------|-----------|------------------------|----------------|
| REQ-REG-01 | `presentation/register/RegisterViewModel` (canSubmit) | — | Manual ✓ | ✓ RegisterUiStateTest, RegisterViewModelTest |
| REQ-REG-02 | `RegisterViewModel` DIGIT_LENGTH_RULES + `NumericInputFilters` | H-01 | Manual ✓ | ✓ RegisterUiStateTest |
| REQ-REG-03 | `RegisterPatientUseCase` / `PatientRepository` | H-03 | Manual ✓ | ✓ RegisterPatientUseCaseTest |
| REQ-MBG-01 | `presentation/medicalbackground/*`, `MedicalBackgroundUseCases` | — | Manual ✓ | TODO |
| REQ-MBG-02 | `MedicalBackgroundScreen` review dialog | H-08 | Manual ✓ | TODO |
| REQ-VIT-01 | `StartCaseUseCase`, `EncounterRepository` | — | Manual ✓ | TODO |
| REQ-VIT-02 | `CompounderViewModel`, `GetVitalsPrefillUseCase`, `VitalsSource` | — | Manual ✓ | TODO |
| REQ-VIT-03 | `CompounderViewModel`, `AddAilmentUseCase` (was `AddSymptomUseCase` — see rename note) | — | Manual ✓ | ✓ AilmentUseCasesTest |
| REQ-VIT-04 | *(planned)* | H-01 | — | PLANNED |
| REQ-CON-01 | `presentation/consultation/*`, `SaveConsultationUseCase`, `AddAttachmentUseCase` | — | Manual ✓ | TODO |
| REQ-CON-02 | `ConsultationScreen` review dialog | H-08 | Manual ✓ | TODO |
| REQ-HAN-01 | `SendToKernelUseCase` (mocked) | H-02,H-09 | Manual ✓ | TODO |
| REQ-HAN-02 | `TranscribeAudioUseCase`, `TranscriptionService` | — | Manual ✓ | TODO |
| REQ-HAN-03 | `AcknowledgeCaseUseCase`, `CaseRecordRepository` | — | Manual ✓ | TODO |
| REQ-HAN-04 | `AssignDoctorUseCase`, `DoctorListViewModel` | — | Manual ✓ | TODO |
| REQ-HAN-05 | *(planned)* | H-02,H-09 | — | PLANNED |
| REQ-HAN-06 | `domain/model/KernelPayload`, `SendToKernelUseCase` (no Patient param), `SendingViewModel` fetches by encounterId | H-10 | Manual ✓ (on-device: constructed payload for an identity-laden test patient contained none of it) | ✓ SendToKernelUseCaseTest |
| REQ-ROS-01 | `HomeViewModel`, `GetTodaysPatientsUseCase` | — | Manual ✓ | ✓ HomeViewModelTest, TodaysPatientsDaoTest |
| REQ-ROS-02 | `PatientDao.observePatientsWithEncounterBetween` (no all-query) | H-04 | Manual ✓ | ✓ TodaysPatientsDaoTest (instrumented, permanent) |
| REQ-PID-01 | `presentation/common/PatientContextBar`, `currentPatientId` | H-03 | Manual ✓ | TODO |
| REQ-SEC-01 | `DatabaseModule` + `DatabasePassphraseProvider` (SQLCipher/Keystore) | H-04 | Manual ✓ (DB header = ciphertext) | TODO (instrumented) |
| REQ-SEC-02 | day-scoped query (see REQ-ROS-02) | H-04 | Manual ✓ | ✓ TodaysPatientsDaoTest |
| REQ-SEC-03 | **CLOSED, Phase 6a.** `data/local/auth/BackendAuthSession` + `RetrofitAuthService`/`AuthApiService` (real PIN verified server side, backend/core §2), `presentation/common/BiometricAuth.kt`, `LoginViewModel`/`LoginScreen` (biometric gate + PIN field before `AuthSession.signIn`), `MainActivity: FragmentActivity` | H-06 | Manual on-device (emulator, no lock configured: confirmed clean block, no crash, "Can't sign in — no fingerprint/face/screen lock set up" shown, form stays usable); PIN verification itself not yet exercised against a live backend in this environment, see PROGRESS.md | ✓ LoginViewModelTest, WorkerIdDerivationTest, TokenAuthenticatorTest, BearerInterceptorTest: per-account PIN + biometric device gate, both required |
| REQ-SEC-04 | `domain/auth/AuthSession`, `data/local/auth/BackendAuthSession` (Phase 6a, replaces `MockAuthSession`) + `AuthTokenStore`/`DataStoreAuthTokenStore` (Preferences DataStore), `presentation/login/*`, `presentation/auth/AuthViewModel` sign-in gate in AppNavHost | H-06 | Manual ✓ (on-device: cold start shows Login, sign-in persists across restart, sign-out returns to Login) | ✓ RoomAuditLoggerTest (session userId vs placeholder fallback), WorkerIdDerivationTest (mock-to-real `worker_id` continuity) |
| REQ-SEC-05 | `src/dev/AndroidManifest.xml` (`usesCleartextTraffic="true"`, dev-flavor only), main manifest no longer sets it | — | Manual ✓ (`assembleDevDebug`/`assembleStagingDebug`/`assembleProdDebug` all succeed; manifest merge confirms flag present only in dev) | TODO (instrumented — verify staging/prod cleartext HTTP is actually refused) |
| REQ-SEC-06 | `app/build.gradle.kts` flavor-scoped `BACKEND_BASE_URL` (staging/prod = `https://...`, dev = `http://` LAN IP); `KERNEL_BASE_URL`/`ABHA_BACKEND_BASE_URL` deleted in Phase 6a (backend-prd.md D-4; the kernel and ABHA adapter are both reached only through `BACKEND_BASE_URL` now) | — | Manual ✓ (build.gradle review — staging/prod URLs are `https://`; `assembleDevDebug`/`assembleStagingDebug`/`assembleProdDebug` all succeed with no dangling `BuildConfig` reference) | TODO |
| REQ-SEC-07 | `app/build.gradle.kts` flavor-scoped `BuildConfig.SCREEN_SECURITY_ENABLED` (dev = `false`, staging/prod = `true`), `FeatureFlags.SCREEN_SECURITY_ENABLED`, `requiresScreenSecurity`/`SECURED_ROUTE_TYPES` in `presentation/navigation/Routes.kt`, applied window-wide in `AppNavHost` | H-06 | Manual ✓ (build.gradle review — dev intentionally off for demo screen recording, staging/prod on) | ✓ RoutesSecurityTest, asserts against the flavor's own `BuildConfig.SCREEN_SECURITY_ENABLED` value (run via `testDevDebugUnitTest`/`testStagingDebugUnitTest`/`testProdDebugUnitTest`) |
| REQ-AUD-01 | `AuditLogger`/`RoomAuditLogger` (userId now sourced from `AuthSession`, not a hardcoded placeholder — REQ-SEC-04), wired in 8 screens | H-07 | Manual ✓ (logcat capture of the real session userId, not "phc_field_worker") | ✓ RoomAuditLoggerTest, AuditPayloadTest |
| REQ-AUD-02 | `AuditLogDao` (insert + query only) | H-07 | Manual ✓ (interface review) | TODO — compile/lint guard |
| REQ-NET-01 | `NetworkMonitor`/`AndroidNetworkMonitor`, `ConnectivityViewModel` | — | Manual ✓ | TODO |
| REQ-SYN-01 | `SyncStatus`/`SyncStatusImpl` (Phase 6b, was `MockSyncStatus`), `ConnectivityController`, `CaseStatus.PENDING_SYNC`, `DoctorAssignmentConfirmViewModel` (offline confirm queues instead of sending), Home sync row | H-05 | Manual ✓ (online/offline, manual toggle + real network) | ✓ SyncStatusImplTest (9, ports the 6 MockSyncStatusTest behaviors + failedCount/outbox-trigger), HomeViewModelTest |
| REQ-SYN-02 | Push side (Phase 6b + PR#6 review fixes + syncstate-reset): `SyncPushWorker`, `SyncOutboxDrainer` (now `@Singleton` with a drain-serializing `Mutex`), `SyncBatchPacker` (per-record oversize rejection), `RoomSyncOutboxRepository`, `RetrofitSyncPushService`, per-entity `getPendingForSync`/`applySyncResult` DAO methods (all 20 tables, ack now guarded on the sent `localModifiedAt` revision to close the edit-during-flight race); doctor-assignment leg unchanged (`CaseRecordRepository.assignDoctor(isOnline)`/`sendAllPendingCases`/`observePendingSyncCount`); rest per `docs/sync-design.md` | H-05 | Manual ✓ | ✓ SyncBatchPackerTest (7, incl. large-backlog/byte-budget boundaries and per-record-oversize rejection), SyncAckMappingTest (6, table-driven), SyncRecordMappersTest (5, forbidden-field/wire-shape), SyncOutboxDrainerTest (6: large-backlog drain, crash-resume batch_id reuse, malformed-row→FAILED, unreadable-in-flight-batch blocks drain, oversized-record FAILED-locally, concurrent-drain no double-send) — all JVM. Producer side (syncstate-reset, 2026-08-18) now closes the loop: 12 syncable clinical mutation paths across 20 tables reset `syncState = 'PENDING'` on re-edit (7 plain-UPDATE, 5 REPLACE-upsert with `serverVersion` preserved) — proven by `SyncStateResetTest.kt` (12 round-trip tests + 1 applySyncResult guard test + 1 composition test proving the reset and PR#6's ack-revision guard together never lose a clinical edit made during an in-flight sync, androidTest, written and compiling clean but not runtime-executed, no device/emulator in this sandbox). REQ-SYN-02's push side is genuinely end-to-end: a synced row that is clinically re-edited re-drains. Still open: conflict field-level merge, `RemoteMediator`/pull, purge-on-sync minimisation |
| REQ-PED-01 | `domain/model/Patient.guardianRelation`, `PatientEntity`, migration 2→3 | — | Schema compiles (v3) | TODO |
| REQ-ABH-01 | `presentation/abha/AbhaSignUpScreen`+`AbhaSignUpViewModel`, `domain/usecase/CreateAbhaProfileUseCase`, `domain/model/AbhaProfile`/`formatAbhaId` | — | Manual (nav flow reviewed) | ✓ CreateAbhaProfileUseCaseTest |
| REQ-ABH-02 | `presentation/abha/AbhaLoginScreen`+`AbhaOtpScreen`, `domain/usecase/VerifyAbhaLoginUseCase`, `RegisterViewModel.loadAbhaProfile`, `abha-field-mapping.md` | H-06 | Manual (nav flow reviewed) | ✓ VerifyAbhaLoginUseCaseTest, RegisterViewModelTest (autofill) |
| REQ-ABH-03 | `backend/abdm-adapter/abdm_adapter/{router,service,transaction,client}.py`, mounted into `backend/core/app/main.py` | H-03 (linkage stays out of scope here) | Real PostgreSQL + real `uvicorn`, `ABDM_MODE=stub` (manual walk, this session) | ✓ `backend/core/tests/test_abha.py` (10), `backend/abdm-adapter/tests/` (49) |
| REQ-ABH-04 | `abdm_adapter/errors.py` (`classify_otp_verify`, D2) | H-03 | Real PostgreSQL, HTTP-layer | ✓ `test_error_mapping.py::test_d2_*`, `test_abha.py::test_d2_mobile_otp_expired_200_does_not_advance_state` |
| REQ-ABH-05 | `abdm_adapter/mapping.py` (photo dropped at the source function), `app/models/abha.py` (`external_token_encrypted` now `EncryptedText`) | H-04, H-07 | Real PostgreSQL, raw-SQL row inspection + `capsys` log capture | ✓ `test_abha.py::test_d5_no_phi_in_persisted_row_or_logs` |
| REQ-AIL-01 | `presentation/compounder/NewAilmentCard`, `AddAilmentUseCase`, `AilmentEntry.measurementType` | — | Manual (nav flow reviewed) | ✓ AilmentUseCasesTest |
| REQ-AIL-02 | `PrivateHandoffInterstitial`, `CompounderViewModel.toListItem`/`AilmentListItem`, `AilmentEntry.visibility` | H-04 | Manual (nav flow reviewed) | ✓ AilmentListItemMappingTest |
| REQ-AIL-03 | `domain/media/AilmentAudioRecorder`, `data/media/AndroidAilmentAudioRecorder` (real MediaRecorder), `DeleteAilmentUseCase` | H-04 | Manual (nav flow reviewed) | ✓ AilmentUseCasesTest |
| REQ-AIL-04 | `domain/repository/AilmentRepository` KDoc (unfiltered `observeForEncounter`), `Visibility`/`AilmentEntry` KDoc | H-10 | Documented; repository boundary verified | ✓ AilmentUseCasesTest (observe-stream check) |
| REQ-TRS-01 | `presentation/consent/ConsentScreen`+`ConsentViewModel`, `AuditAction.CONSENT_RECORDED` | H-07 | Manual (nav flow reviewed) | ✓ ConsentViewModelTest |
| REQ-TRS-02 | `CheckEmergencyThresholdsUseCase`, `CompounderViewModel.onContinue`, `EmergencyOverrideScreen`, `AuditAction.EMERGENCY_OVERRIDE` | H-01 | Manual (nav flow reviewed) | ✓ CheckEmergencyThresholdsUseCaseTest |
| REQ-TRS-03 | `domain/config/SyncWindowProvider`+`AndroidSyncWindowProvider`, `res/values/integers.xml`, `AcknowledgementScreen` | — | Manual (nav flow reviewed) | TODO — passthrough only, no dedicated ViewModel test yet (see PROGRESS.md) |
| REQ-TRS-04 | `NewAilmentCard` flat fields; `AilmentEntry.severity/duration/qualifiers/onset` | — | Manual (nav flow reviewed) | PARTIAL — per-ailment-type dynamic expansion is Phase 2.5, not built |
| REQ-TRS-05 | `VitalsCaptureMethod` enum, `Observation.captureMethod`/`ObservationEntity.captureMethod`, `CompounderScreen` dropdown | — | Manual (nav flow reviewed) | TODO |
| REQ-TRS-06 | `AilmentEntry`/`Observation` `capturedAtOffline`(`recordedAt`)+`syncedToCloudAt` | H-05 | Manual (nav flow reviewed) | TODO |
| REQ-RPT-01 | `domain/report/ReportFormatter`, `domain/usecase/AssembleReportUseCase`, `domain/report/ClinicalReport` | — | Manual (nav flow reviewed) | ✓ ReportFormatterTest |
| REQ-RPT-02 | `presentation/report/ReportCanvasRenderer` (Canvas), `ReportPdfExporter` (PdfDocument), `ReportScreen` (drawIntoCanvas), `Code128`, `ReportImageLoading.kt` (logo + attachment decode) | — | Manual (renderer is on-device only — see note) | ✓ Code128Test (barcode), ReportFormatterTest (attachment mapping); renderer TODO (needs Robolectric/instrumented) |
| REQ-RPT-03 | `docs/requirements/report-field-mapping.md` | — | Doc reviewed against renderer | n/a |
| REQ-HAN-07 | `domain/usecase/GenerateKernelReportUseCase`, `domain/kernel/RemoteKernelSource`, `data/remote/RetrofitKernelSource`+`api/KernelApiService`+`dto/*`, `di/NetworkModule`, `presentation/kernelassessment/KernelAssessmentScreen`+VM, `SendingViewModel`, `KernelReportRepository` | H-02,H-09 | Manual (nav flow reviewed) | ✓ GenerateKernelReportUseCaseTest |
| REQ-HAN-08 | `domain/model/InferenceSource`, `domain/model/KernelReportOutput`, `domain/usecase/GenerateKernelReportUseCase`, `data/local/entity/KernelReportEntity`, `data/local/Converters`, `data/local/Migrations` (MIGRATION_7_8), `data/repository/KernelReportRepositoryImpl`, `presentation/kernelassessment/KernelAssessmentScreen`, `presentation/report/ReportCanvasRenderer`, `presentation/sending/SendingViewModel`, `domain/audit/AuditLogger` | H-02,H-09 | Manual (nav flow reviewed) | ✓ GenerateKernelReportUseCaseTest, MigrationTest (instrumented, migration7To8) |
| REQ-RX-01 | `domain/usecase/SubmitDoctorDecisionUseCase`, `presentation/patientsummary/PatientSummaryViewModel`+`Screen` ("Review AI diagnosis (doctor)" card) | — | Manual (on-device, Vivo) | TODO — no ViewModel/use-case test yet for the new interactive flow |
| REQ-RX-02 | `MedicationLine` KDoc + `ReportFormatter.formatMedicationLine` (throws on banned token) | — | Documented + enforced at report boundary | ✓ ReportFormatterTest (banned-abbrev) |
| REQ-RX-03 | `domain/model/KernelDecision`, `Prescription.kernelDecision`, `MIGRATION_4_5`, rendered in `ReportCanvasRenderer.rxBlock`, set by `SubmitDoctorDecisionUseCase` per the reviewer's real pick | — | Manual (on-device, Vivo) | TODO |
| REQ-EVL-01 | `domain/usecase/GenerateEvaluateReportUseCase`, `domain/kernel/EvaluateKernelSource`, `data/remote/RetrofitEvaluateSource`+`api/ClinicalApiService`+`dto/Evaluate*Dto`, `domain/repository/EvaluateReportRepository`, `data/local/entity/EvaluateReportEntity` (MIGRATION_8_9), `presentation/sending/SendingViewModel` | H-02,H-09 | Manual (on-device, Vivo; curl-verified against live backend) | ✓ EvaluateReportDtoTest (deserialization) |
| REQ-EVL-02 | `domain/kernel/BrandLookupSource`, `data/remote/GeminiBrandLookupSource`, `data/remote/api/GeminiApiService`, `di/GeminiNetworkModule`, `domain/model/IndianBrandSuggestion` | — | Manual (curl-verified against live Gemini API: Paracetamol→Dolo 650, Nystatin→Nystin/Jagsonpal) | TODO |
| REQ-EVL-03 | `presentation/report/ReportCanvasRenderer.evaluateBlock`, `docs/requirements/report-field-mapping.md` | — | Manual (on-device, Vivo) | TODO |
| REQ-RFN-01 | `domain/usecase/SubmitDoctorDecisionUseCase`, `domain/model/DiagnosisFeedback`, `domain/repository/DiagnosisFeedbackRepository`, `data/local/entity/DiagnosisFeedbackEntity` (MIGRATION_9_10, MIGRATION_10_11), `domain/audit/AuditAction.DIAGNOSIS_FEEDBACK_RECORDED` | H-02 | Manual (on-device, Vivo; cross-checked against `train_model.py`/`train_symptom_classifier.py` column usage) | TODO |
| REQ-RFN-02 | `domain/model/TRAINED_ICD_CANDIDATES`, `presentation/patientsummary/PatientSummaryScreen` (MODIFY-only dropdown + note field, `ManualPrescriptionFields` kept separate) | — | Manual (on-device, Vivo) | TODO |
| REQ-REF-01 | `presentation/report/ReportScreen` (referral sheet), `CreateReferralUseCase`, `ReferralRepository`, `ReportFormatter.suggestsReferral` | — | Manual (nav flow reviewed) | ✓ CreateReferralUseCaseTest, ReportFormatterTest (eligibility) |

> **Orphaned-but-passing note (2026-07):** `domain/doctor/DoctorPrescriptionInbox`+
> `MockDoctorPrescriptionInbox`, `ReceiveDoctorPrescriptionUseCase`, and their tests
> (`MockDoctorPrescriptionInboxTest`, `ReceiveDoctorPrescriptionUseCaseTest`) are left in place and
> still pass, but nothing in the UI calls them anymore — `PatientSummaryViewModel` now calls
> `SubmitDoctorDecisionUseCase` instead (REQ-RX-01). Kept rather than deleted since they still
> represent a plausible future real-channel intake shape; revisit if that channel is ever built for
> real, otherwise consider removing the dead code in a later pass.

> **Rename note (Phase 2, done):** "Complaints" → "Ailments" — `AddSymptomUseCase`/`SymptomEntity`/
> `SymptomDao`/`Symptom` domain model are gone; `MIGRATION_3_4` backfilled `symptoms` into
> `ailments` then dropped the table. REQ-VIT-03's design is now `CompounderViewModel`'s ailment
> section (`NewAilmentCard`, `AddAilmentUseCase`) — same REQ-ID, new implementation. REQ-CON-01's
> "chief complaint" is unaffected — that's `Consultation.chiefComplaint`, a distinct clinical field
> the brief did not ask to rename.

## Summary
- **Automated coverage (blocker #4, first pass):** a JVM unit suite now covers registration
  validation, audit payload/logger, sync mock, Home roster/sync, the kernel-payload boundary, and
  the mock-login audit wiring (see the ✓ rows), plus a permanent instrumented DAO test for the
  day-scoped roster query. **133 tests, 0 failures** (grew from the initial 36 across every
  subsequent phase — see `PROGRESS.md` for the per-pass counts). The 2026-07 evaluate/refinement
  work (REQ-EVL/RFN) added new production code faster than test coverage — see the TODO rows
  above; `SubmitDoctorDecisionUseCase`, `PatientSummaryViewModel`'s new interactive flow, and
  `GeminiBrandLookupSource` are all currently verified manually only. GitHub Actions
  (`.github/workflows/android-ci.yml`) runs the unit suite + assembleDebug on every push/PR
  (unit-only tests; instrumented tests run locally for now). This first pass also caught a real
  regression — the cache-scoping interface change had silently broken a pre-existing use-case
  test that no CI ran.
- **Still TODO:** Compose UI tests (Register form, review dialogs, Login, PIN-change), instrumented
  SEC-01 (SQLCipher) and AUD-02 (insert-only guard) coverage. REQ-SEC-03 closed in Phase 6a (real
  per-account PIN auth via `BackendAuthSession`, replacing `MockAuthSession`'s device-owner-only
  gate): see the REQ-SEC-03/REQ-SEC-04 rows above and `PROGRESS.md`'s Phase 6a section for what
  was and was not exercised against a live backend.
