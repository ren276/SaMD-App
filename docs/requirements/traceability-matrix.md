# Requirements Traceability Matrix — initial

> Links each requirement (`software-requirements.md`) to its design/implementation and its
> verification (IEC 62304 §5.2/§5.7). **The verification column is the point of this document:**
> nearly everything is currently "Manual (emulator walkthrough)" with automated coverage
> **TODO** — that gap is blocker #4. Design column cites the owning component/package.

| REQ | Design / implementation | Risk link | Verification (current) | Automated test |
|-----|-------------------------|-----------|------------------------|----------------|
| REQ-REG-01 | `presentation/register/RegisterViewModel` (canSubmit) | — | Manual ✓ | TODO |
| REQ-REG-02 | `RegisterViewModel` DIGIT_LENGTH_RULES + `NumericInputFilters` | H-01 | Manual ✓ | TODO |
| REQ-REG-03 | `RegisterPatientUseCase` / `PatientRepository` | H-03 | Manual ✓ | TODO |
| REQ-MBG-01 | `presentation/medicalbackground/*`, `MedicalBackgroundUseCases` | — | Manual ✓ | TODO |
| REQ-MBG-02 | `MedicalBackgroundScreen` review dialog | H-08 | Manual ✓ | TODO |
| REQ-VIT-01 | `StartCaseUseCase`, `EncounterRepository` | — | Manual ✓ | TODO |
| REQ-VIT-02 | `CompounderViewModel`, `GetVitalsPrefillUseCase`, `VitalsSource` | — | Manual ✓ | TODO |
| REQ-VIT-03 | `CompounderViewModel`, `AddSymptomUseCase` | — | Manual ✓ | TODO |
| REQ-VIT-04 | *(planned)* | H-01 | — | PLANNED |
| REQ-CON-01 | `presentation/consultation/*`, `SaveConsultationUseCase`, `AddAttachmentUseCase` | — | Manual ✓ | TODO |
| REQ-CON-02 | `ConsultationScreen` review dialog | H-08 | Manual ✓ | TODO |
| REQ-HAN-01 | `SendToKernelUseCase` (mocked) | H-02,H-09 | Manual ✓ | TODO |
| REQ-HAN-02 | `TranscribeAudioUseCase`, `TranscriptionService` | — | Manual ✓ | TODO |
| REQ-HAN-03 | `AcknowledgeCaseUseCase`, `CaseRecordRepository` | — | Manual ✓ | TODO |
| REQ-HAN-04 | `AssignDoctorUseCase`, `DoctorListViewModel` | — | Manual ✓ | TODO |
| REQ-HAN-05 | *(planned)* | H-02,H-09 | — | PLANNED |
| REQ-ROS-01 | `HomeViewModel`, `GetTodaysPatientsUseCase` | — | Manual ✓ | TODO |
| REQ-ROS-02 | `PatientDao.observePatientsWithEncounterBetween` (no all-query) | H-04 | Manual ✓ + throwaway DAO test (removed) | **TODO — restore as permanent test** |
| REQ-PID-01 | `presentation/common/PatientContextBar`, `currentPatientId` | H-03 | Manual ✓ | TODO |
| REQ-SEC-01 | `DatabaseModule` + `DatabasePassphraseProvider` (SQLCipher/Keystore) | H-04 | Manual ✓ (DB header = ciphertext) | TODO (instrumented) |
| REQ-SEC-02 | day-scoped query (see REQ-ROS-02) | H-04 | Manual ✓ | TODO |
| REQ-SEC-03 | *(planned)* | H-06 | — | PLANNED |
| REQ-AUD-01 | `AuditLogger`/`RoomAuditLogger`, wired in 8 screens | H-07 | Manual ✓ (logcat capture) | TODO |
| REQ-AUD-02 | `AuditLogDao` (insert + query only) | H-07 | Manual ✓ (interface review) | TODO — compile/lint guard |
| REQ-NET-01 | `NetworkMonitor`/`AndroidNetworkMonitor`, `ConnectivityViewModel` | — | Manual ✓ | TODO |
| REQ-SYN-01 | `SyncStatus`/`MockSyncStatus`, Home sync row | H-05 | Manual ✓ (online/offline) | TODO |
| REQ-SYN-02 | *(planned)* `docs/sync-design.md` | H-05 | — | PLANNED |

## Summary
- **Implemented + manually verified:** the DONE requirements above, exercised via emulator
  walkthroughs this development cycle.
- **Automated coverage:** effectively zero (one DAO test was written to verify REQ-ROS-02 then
  removed). This is the central V&V gap — **blocker #4** turns the "TODO" column into real,
  repeatable tests run in CI, which is what IEC 62304 verification records require.
