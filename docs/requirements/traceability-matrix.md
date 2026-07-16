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
| REQ-SEC-03 | `presentation/common/BiometricAuth.kt`, `LoginViewModel`/`LoginScreen` (biometric gate before `AuthSession.signIn`), `MainActivity: FragmentActivity` | H-06 | Manual on-device (emulator, no lock configured: confirmed clean block, no crash, "Can't sign in — no fingerprint/face/screen lock set up" shown, form stays usable) | ✓ LoginViewModelTest — PARTIAL: device-owner gate only, no per-person account binding/RBAC |
| REQ-SEC-04 | `domain/auth/AuthSession`, `data/local/auth/MockAuthSession` (Preferences DataStore), `presentation/login/*`, `presentation/auth/AuthViewModel` sign-in gate in AppNavHost | H-06 | Manual ✓ (on-device: cold start shows Login, sign-in persists across restart, sign-out returns to Login) | ✓ RoomAuditLoggerTest (session userId vs placeholder fallback) |
| REQ-AUD-01 | `AuditLogger`/`RoomAuditLogger` (userId now sourced from `AuthSession`, not a hardcoded placeholder — REQ-SEC-04), wired in 8 screens | H-07 | Manual ✓ (logcat capture of the real session userId, not "phc_field_worker") | ✓ RoomAuditLoggerTest, AuditPayloadTest |
| REQ-AUD-02 | `AuditLogDao` (insert + query only) | H-07 | Manual ✓ (interface review) | TODO — compile/lint guard |
| REQ-NET-01 | `NetworkMonitor`/`AndroidNetworkMonitor`, `ConnectivityViewModel` | — | Manual ✓ | TODO |
| REQ-SYN-01 | `SyncStatus`/`MockSyncStatus`, Home sync row | H-05 | Manual ✓ (online/offline) | ✓ MockSyncStatusTest, HomeViewModelTest |
| REQ-SYN-02 | *(planned)* `docs/sync-design.md` | H-05 | — | PLANNED |
| REQ-PED-01 | `domain/model/Patient.guardianRelation`, `PatientEntity`, migration 2→3 | — | Schema compiles (v3) | TODO |
| REQ-ABH-01 | `presentation/abha/AbhaSignUpScreen`+`AbhaSignUpViewModel`, `domain/usecase/CreateAbhaProfileUseCase`, `domain/model/AbhaProfile`/`formatAbhaId` | — | Manual (nav flow reviewed) | ✓ CreateAbhaProfileUseCaseTest |
| REQ-ABH-02 | `presentation/abha/AbhaLoginScreen`+`AbhaOtpScreen`, `domain/usecase/VerifyAbhaLoginUseCase`, `RegisterViewModel.loadAbhaProfile`, `abha-field-mapping.md` | H-06 | Manual (nav flow reviewed) | ✓ VerifyAbhaLoginUseCaseTest, RegisterViewModelTest (autofill) |
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
| REQ-HAN-07 | `domain/usecase/GenerateKernelReportUseCase`, `presentation/kernelassessment/KernelAssessmentScreen`+VM, `SendingViewModel`, `KernelReportRepository` | H-02,H-09 | Manual (nav flow reviewed) | ✓ GenerateKernelReportUseCaseTest |
| REQ-RX-01 | `domain/doctor/DoctorPrescriptionInbox`+`MockDoctorPrescriptionInbox`, `ReceiveDoctorPrescriptionUseCase`, `PatientSummaryScreen` follow-up UI | — | Manual (nav flow reviewed) | ✓ ReceiveDoctorPrescriptionUseCaseTest, MockDoctorPrescriptionInboxTest |
| REQ-RX-02 | `MedicationLine` KDoc + `ReportFormatter.formatMedicationLine` (throws on banned token) | — | Documented + enforced at report boundary | ✓ ReportFormatterTest (banned-abbrev) |
| REQ-RX-03 | `domain/model/KernelDecision`, `Prescription.kernelDecision`, `MIGRATION_4_5`, rendered in `ReportCanvasRenderer.rxBlock` | — | Manual (nav flow reviewed) | ✓ MockDoctorPrescriptionInboxTest (decision distribution) |
| REQ-REF-01 | `presentation/report/ReportScreen` (referral sheet), `CreateReferralUseCase`, `ReferralRepository`, `ReportFormatter.suggestsReferral` | — | Manual (nav flow reviewed) | ✓ CreateReferralUseCaseTest, ReportFormatterTest (eligibility) |

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
  day-scoped roster query. **36 tests, 0 failures.** GitHub Actions
  (`.github/workflows/android-ci.yml`) runs the unit suite + assembleDebug on every push/PR
  (unit-only tests; instrumented tests run locally for now). This first pass also caught a real
  regression — the cache-scoping interface change had silently broken a pre-existing use-case
  test that no CI ran.
- **Still TODO:** Compose UI tests (Register form, review dialogs, Login), instrumented SEC-01
  (SQLCipher) and AUD-02 (insert-only guard) coverage, and the PLANNED forward requirements
  (REQ-SEC-03 real auth/RBAC remains open — REQ-SEC-04's mock login does not satisfy it).
