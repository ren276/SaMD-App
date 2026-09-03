package com.example.samdapp.testutil

import com.example.samdapp.data.assessment.AssessmentQueueScheduler
import com.example.samdapp.data.assessment.AssessmentWorkState
import com.example.samdapp.data.local.dao.AuditLogDao
import com.example.samdapp.data.local.entity.AuditLogEntity
import com.example.samdapp.domain.audit.AuditAction
import com.example.samdapp.domain.audit.AuditLogEntry
import com.example.samdapp.domain.audit.AuditLogger
import com.example.samdapp.domain.config.DeviceInfoProvider
import com.example.samdapp.domain.connectivity.NetworkMonitor
import com.example.samdapp.domain.auth.AuthSession
import com.example.samdapp.domain.auth.ChangePinResult
import com.example.samdapp.domain.auth.SignInResult
import com.example.samdapp.domain.auth.UserRole
import com.example.samdapp.domain.auth.UserSession
import com.example.samdapp.domain.abha.AbdmAbhaSource
import com.example.samdapp.domain.abha.AbhaApiResult
import com.example.samdapp.domain.abha.AbhaIdentity
import com.example.samdapp.domain.abha.AbhaSessionSnapshot
import com.example.samdapp.domain.abha.AbhaTransactionState
import com.example.samdapp.domain.model.AbhaProfile
import com.example.samdapp.domain.model.AilmentEntry
import com.example.samdapp.domain.doctor.DoctorPrescriptionInbox
import com.example.samdapp.domain.doctor.IncomingPrescription
import com.example.samdapp.domain.kernel.BrandLookupSource
import com.example.samdapp.domain.model.CaseRecord
import com.example.samdapp.domain.model.ConsultationHistoryEntry
import com.example.samdapp.domain.model.DoctorTrackerEntry
import com.example.samdapp.domain.model.CaseStatus
import com.example.samdapp.domain.model.Doctor
import com.example.samdapp.domain.model.Encounter
import com.example.samdapp.domain.model.DiagnosisFeedback
import com.example.samdapp.domain.model.EvaluateReportOutput
import com.example.samdapp.domain.model.IndianBrandSuggestion
import com.example.samdapp.domain.model.KernelReportOutput
import com.example.samdapp.domain.model.Patient
import com.example.samdapp.domain.model.PatientDirectoryEntry
import com.example.samdapp.domain.model.Prescription
import com.example.samdapp.domain.model.ReferralRequest
import com.example.samdapp.domain.repository.AbhaProfileRepository
import com.example.samdapp.domain.repository.AilmentRepository
import com.example.samdapp.domain.repository.AuditLogRepository
import com.example.samdapp.domain.repository.CaseRecordRepository
import com.example.samdapp.domain.repository.DoctorRepository
import com.example.samdapp.domain.repository.EncounterRepository
import com.example.samdapp.domain.repository.DiagnosisFeedbackRepository
import com.example.samdapp.domain.repository.EvaluateReportRepository
import com.example.samdapp.domain.repository.KernelReportRepository
import com.example.samdapp.domain.repository.PatientRepository
import com.example.samdapp.domain.repository.PrescriptionRepository
import com.example.samdapp.domain.repository.ReferralRepository
import com.example.samdapp.domain.sync.SyncState
import com.example.samdapp.domain.sync.SyncStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import java.time.Instant

class FakeBrandLookupSource(
    private val brand: IndianBrandSuggestion? = IndianBrandSuggestion("Fake Brand", "Fake Pharma Co"),
) : BrandLookupSource {
    override suspend fun lookupTopIndianBrand(genericDrugName: String): IndianBrandSuggestion? = brand
}

fun testPatient(id: String, fullName: String = "P-$id", age: Int? = 30): Patient = Patient(
    id = id, fullName = fullName, dateOfBirth = null, age = age, biologicalSex = "Female",
    guardianOrSpouseName = null, guardianRelation = null, mobileNumber = null, aadhaarNumber = null, abhaNumber = null,
    village = null, block = null, district = null, state = null, pincode = null, category = null,
    maritalStatus = null, bloodGroup = null, emergencyContact = null, primaryCareClinicName = null,
    referringPhysicianName = null, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
)

fun testKernelReportOutput(
    caseRecordId: String,
    inferenceSource: com.example.samdapp.domain.model.InferenceSource,
    predictedCondition: String = "Viral fever",
    confidenceScore: Double = 0.8,
    requiredHumanVerification: Boolean = false,
): KernelReportOutput = KernelReportOutput(
    id = "kr-$caseRecordId", caseRecordId = caseRecordId, predictedCondition = predictedCondition,
    confidenceScore = confidenceScore, differentials = emptyList(), reasoningSummary = "summary",
    evidenceFor = emptyList(), evidenceAgainst = emptyList(), modelVersion = "test-model", icdCode = null,
    deviceId = "device-1", softwareVersion = "1.0", dataQualityScore = 1.0, uncertaintyScore = 1.0 - confidenceScore,
    riskCategory = com.example.samdapp.domain.model.RiskCategory.MODERATE,
    urgencyLevel = com.example.samdapp.domain.model.UrgencyLevel.ROUTINE,
    inferenceStartedAt = Instant.EPOCH, inferenceEndedAt = Instant.EPOCH,
    requiredHumanVerification = requiredHumanVerification, inferenceSource = inferenceSource,
)

/** Deterministic [com.example.samdapp.domain.kernel.KernelFallbackSource] test double — null by
 *  default (mirrors staging/prod's `NoFallbackKernelSource`), or returns [result] when set
 *  (mirrors dev's `MockKernelFallbackSource`, without the real keyword-matching logic — that's
 *  tested separately against the real class in `src/testDev/`). */
class FakeKernelFallbackSource(
    var result: KernelReportOutput? = null,
) : com.example.samdapp.domain.kernel.KernelFallbackSource {
    var callCount = 0
        private set

    override suspend fun fallback(
        caseRecordId: String,
        payload: com.example.samdapp.domain.model.KernelPayload,
        inferenceStartedAt: Instant,
        dataQualityScore: Double,
    ): KernelReportOutput? {
        callCount++
        return result
    }
}

/** Tracks whether [captureAudioAttachment] was ever invoked, so a test can assert the
 *  fix/asr-offdevice-exposure voice-flag guard actually stops the call before it happens,
 *  not just that the returned state looks unchanged. */
class FakeTranscriptionService(
    private val result: Result<com.example.samdapp.domain.transcription.CapturedAudio> =
        Result.success(com.example.samdapp.domain.transcription.CapturedAudio(uri = "speech-session://fake", transcript = "fake transcript")),
) : com.example.samdapp.domain.transcription.TranscriptionService {
    var captureAudioAttachmentCallCount = 0
        private set

    override suspend fun captureAudioAttachment(): Result<com.example.samdapp.domain.transcription.CapturedAudio> {
        captureAudioAttachmentCallCount++
        return result
    }

    override suspend fun transcribe(audioUri: String): Result<String> = result.map { it.transcript }
}

class FakeAuditLogger : AuditLogger {
    data class Entry(val action: String, val patientId: String?, val caseRecordId: String?, val payload: String)

    val logged = mutableListOf<Entry>()

    override suspend fun log(action: AuditAction, patientId: String?, caseRecordId: String?, payload: String) {
        logged += Entry(action.value, patientId, caseRecordId, payload)
    }
}

class FakePatientRepository(
    private val today: List<Patient> = emptyList(),
) : PatientRepository {
    var registered: Patient? = null
    var registerResult: Result<Unit> = Result.success(Unit)

    override suspend fun register(patient: Patient): Result<Unit> {
        registered = patient
        return registerResult
    }

    override fun observePatient(patientId: String): Flow<Patient?> =
        flowOf(registered?.takeIf { it.id == patientId })

    override fun observeTodaysPatients(): Flow<List<Patient>> = flowOf(today)

    override fun observeRegisteredOrSeenRecently(days: Int): Flow<List<PatientDirectoryEntry>> =
        flowOf(today.map { PatientDirectoryEntry(patient = it, lastSeenAt = null) })
}

class FakeAbhaProfileRepository(
    initialProfiles: List<AbhaProfile> = emptyList(),
) : AbhaProfileRepository {
    val profiles = mutableMapOf<String, AbhaProfile>().apply {
        initialProfiles.forEach { put(it.abhaId, it) }
    }
    var saveResult: Result<Unit> = Result.success(Unit)

    override suspend fun saveProfile(profile: AbhaProfile): Result<Unit> {
        if (saveResult.isSuccess) profiles[profile.abhaId] = profile
        return saveResult
    }

    override suspend fun getProfile(abhaId: String): AbhaProfile? = profiles[abhaId]
}

/** Every method defaults to a plausible [AbhaApiResult.Success] for the state that method is
 *  meant to produce; a test overrides only the one field it cares about. Call tracking is by
 *  argument capture, not just a boolean, so a consent-gate or masked-mobile-pass-through test can
 *  assert on exactly what was sent, not just whether something was. */
class FakeAbdmAbhaSource(
    var startResult: AbhaApiResult<AbhaSessionSnapshot> =
        AbhaApiResult.Success(AbhaSessionSnapshot(sessionId = "session-1", state = AbhaTransactionState.STARTED)),
    var submitIdentityResult: AbhaApiResult<AbhaSessionSnapshot> =
        AbhaApiResult.Success(AbhaSessionSnapshot(sessionId = "session-1", state = AbhaTransactionState.OTP_REQUESTED)),
    var verifyOtpResult: AbhaApiResult<AbhaSessionSnapshot> =
        AbhaApiResult.Success(AbhaSessionSnapshot(sessionId = "session-1", state = AbhaTransactionState.ENROLLED)),
    var verifyMobileOtpResult: AbhaApiResult<AbhaSessionSnapshot> =
        AbhaApiResult.Success(AbhaSessionSnapshot(sessionId = "session-1", state = AbhaTransactionState.MOBILE_VERIFIED)),
    var getProfileResult: AbhaApiResult<AbhaIdentity> = AbhaApiResult.Success(testAbhaIdentity()),
) : AbdmAbhaSource {
    var startCalled = false
    var submitIdentityCalled = false
    var submitIdentityAadhaar: String? = null
    val verifyOtpCalls = mutableListOf<Triple<String, String, String>>()
    val verifyMobileOtpCalls = mutableListOf<Pair<String, String>>()
    var getProfileCalled = false

    override suspend fun startRegistrationSession(): AbhaApiResult<AbhaSessionSnapshot> {
        startCalled = true
        return startResult
    }

    override suspend fun submitIdentity(sessionId: String, aadhaarNumber: String): AbhaApiResult<AbhaSessionSnapshot> {
        submitIdentityCalled = true
        submitIdentityAadhaar = aadhaarNumber
        return submitIdentityResult
    }

    override suspend fun verifyOtp(sessionId: String, otp: String, mobileNumber: String): AbhaApiResult<AbhaSessionSnapshot> {
        verifyOtpCalls += Triple(sessionId, otp, mobileNumber)
        return verifyOtpResult
    }

    override suspend fun verifyMobileOtp(sessionId: String, otp: String): AbhaApiResult<AbhaSessionSnapshot> {
        verifyMobileOtpCalls += sessionId to otp
        return verifyMobileOtpResult
    }

    override suspend fun getSessionState(sessionId: String): AbhaApiResult<AbhaSessionSnapshot> = startResult

    override suspend fun getProfile(sessionId: String): AbhaApiResult<AbhaIdentity> {
        getProfileCalled = true
        return getProfileResult
    }
}

fun testAbhaIdentity(
    abhaNumber: String = "12345678901234",
    name: String = "Anita Kumari",
    mobileNumber: String? = "XXXXXX7776",
): AbhaIdentity = AbhaIdentity(
    abhaNumber = abhaNumber, abhaAddress = null, name = name, dateOfBirth = null, gender = "Female",
    address = "Village Rampur", district = "Sitapur", state = "Uttar Pradesh", pincode = "261001",
    mobileNumber = mobileNumber, emailAddress = null, photoUrl = null, kycVerified = true,
    verificationSource = "ABDM", verifiedAt = Instant.EPOCH,
)

fun testAbhaProfile(
    abhaId: String = "12345678901234",
    name: String = "Anita Kumari",
    mobileNumber: String? = "9998887776",
    gender: String = "Female",
): AbhaProfile = AbhaProfile(
    abhaId = abhaId, abhaAddress = null, name = name, dateOfBirth = null, gender = gender,
    address = "Village Rampur", district = "Sitapur", state = "Uttar Pradesh", pincode = "261001",
    mobileNumber = mobileNumber, emailAddress = null, photoUrlMock = null, kycVerified = true,
    createdAt = Instant.EPOCH,
)

fun testAilmentEntry(
    id: String = "ailment-1",
    visibility: com.example.samdapp.domain.model.Visibility = com.example.samdapp.domain.model.Visibility.PUBLIC,
    description: String = "Fever, 3 days",
): AilmentEntry = AilmentEntry(
    id = id, patientId = "p1", encounterId = "e1", description = description,
    measurementType = com.example.samdapp.domain.model.MeasurementType.NON_MEASURABLE,
    visibility = visibility, measuredValue = null, measuredUnit = null, severity = 5,
    onset = null, duration = "3 days", qualifiers = null, audioLocalUri = null,
    capturedAtOffline = Instant.EPOCH, syncedToCloudAt = null, deletedAt = null, createdAt = Instant.EPOCH,
)

class FakeCaseRecordRepository(
    initial: List<CaseRecord> = emptyList(),
) : CaseRecordRepository {
    val records = mutableMapOf<String, CaseRecord>().apply { initial.forEach { put(it.id, it) } }
    private val streams = mutableMapOf<String, MutableStateFlow<CaseRecord?>>()

    private fun streamFor(id: String) = streams.getOrPut(id) { MutableStateFlow(records[id]) }

    override suspend fun createDraft(patientId: String, encounterId: String): Result<CaseRecord> {
        val now = Instant.EPOCH
        val record = CaseRecord(
            id = "case-${records.size + 1}", patientId = patientId, encounterId = encounterId,
            status = CaseStatus.DRAFT, assignedDoctorId = null, createdAt = now, updatedAt = now,
        )
        records[record.id] = record
        streamFor(record.id).value = record
        return Result.success(record)
    }

    override suspend fun markSavedLocally(caseRecordId: String): Result<Unit> = updateStatus(caseRecordId, CaseStatus.SAVED_LOCALLY)

    override suspend fun assignDoctor(caseRecordId: String, doctorId: String, isOnline: Boolean): Result<Unit> {
        val status = if (isOnline) CaseStatus.SENT_TO_DOCTOR else CaseStatus.PENDING_SYNC
        val updated = records[caseRecordId]?.copy(status = status, assignedDoctorId = doctorId) ?: return Result.failure(NoSuchElementException())
        records[caseRecordId] = updated
        streamFor(caseRecordId).value = updated
        return Result.success(Unit)
    }

    override suspend fun sendAllPendingCases(): Result<Unit> {
        records.values.filter { it.status == CaseStatus.PENDING_SYNC }.forEach { record ->
            val updated = record.copy(status = CaseStatus.SENT_TO_DOCTOR)
            records[record.id] = updated
            streamFor(record.id).value = updated
        }
        return Result.success(Unit)
    }

    // A real per-collection recompute (unlike the flowOf snapshots elsewhere in this fake) — tests
    // rely on this reflecting sendAllPendingCases() mutating `records` after `state` was built.
    override fun observePendingSyncCount(): Flow<Int> = flow { emit(records.values.count { it.status == CaseStatus.PENDING_SYNC }) }

    override suspend fun markPrescriptionReceived(caseRecordId: String): Result<Unit> = updateStatus(caseRecordId, CaseStatus.PRESCRIPTION_RECEIVED)

    private fun updateStatus(caseRecordId: String, status: CaseStatus): Result<Unit> {
        val updated = records[caseRecordId]?.copy(status = status) ?: return Result.failure(NoSuchElementException())
        records[caseRecordId] = updated
        streamFor(caseRecordId).value = updated
        return Result.success(Unit)
    }

    override fun observeCaseRecord(caseRecordId: String): Flow<CaseRecord?> = streamFor(caseRecordId).asStateFlow()

    // Mirrors CaseRecordRepositoryImpl.getDayOrdinal's day-from-the-case, not day-from-now, rule.
    override suspend fun getDayOrdinal(caseRecordId: String): Int? {
        val record = records[caseRecordId] ?: return null
        val zone = java.time.ZoneId.systemDefault()
        val day = java.time.LocalDate.ofInstant(record.createdAt, zone)
        return records.values.count {
            val otherDay = java.time.LocalDate.ofInstant(it.createdAt, zone)
            otherDay == day && (it.createdAt < record.createdAt || (it.createdAt == record.createdAt && it.id <= caseRecordId))
        }
    }

    override fun observeLatestForPatient(patientId: String): Flow<CaseRecord?> =
        flowOf(records.values.filter { it.patientId == patientId }.maxByOrNull { it.updatedAt })

    override fun observeByEncounterId(encounterId: String): Flow<CaseRecord?> =
        flowOf(records.values.firstOrNull { it.encounterId == encounterId })

    // No audit-log join in this fake (userId isn't modeled on CaseRecord) — approximates the real
    // per-worker query with "the most recently updated DRAFT record", good enough for tests that
    // don't exercise multi-worker draft isolation.
    override fun observeResumableDraftForUser(userId: String): Flow<CaseRecord?> =
        flowOf(records.values.filter { it.status == CaseStatus.DRAFT }.maxByOrNull { it.updatedAt })

    override fun observeOpenCaseCount(doctorId: String): Flow<Int> =
        flowOf(records.values.count { it.assignedDoctorId == doctorId && it.status == CaseStatus.SENT_TO_DOCTOR })

    override fun observeDoctorTrackerRows(): Flow<List<DoctorTrackerEntry>> = flowOf(
        records.values
            .filter { it.status == CaseStatus.SENT_TO_DOCTOR || it.status == CaseStatus.PRESCRIPTION_RECEIVED }
            .map {
                DoctorTrackerEntry(
                    caseRecordId = it.id, patientId = it.patientId, patientFullName = "Patient ${it.patientId}",
                    chiefComplaint = null, status = it.status, updatedAt = it.updatedAt,
                    doctorName = it.assignedDoctorId?.let { d -> "Dr. $d" }, doctorSpecialty = "General Physician",
                )
            },
    )
}

class FakePrescriptionRepository : PrescriptionRepository {
    val saved = mutableMapOf<String, Prescription>()
    var saveResult: Result<Unit> = Result.success(Unit)

    override suspend fun save(prescription: Prescription): Result<Unit> {
        if (saveResult.isSuccess) saved[prescription.caseRecordId] = prescription
        return saveResult
    }

    override suspend fun getForCase(caseRecordId: String): Prescription? = saved[caseRecordId]
}

class FakeReferralRepository : ReferralRepository {
    val created = mutableListOf<ReferralRequest>()
    var createResult: Result<Unit> = Result.success(Unit)

    override suspend fun createReferral(referral: ReferralRequest): Result<Unit> {
        if (createResult.isSuccess) created += referral
        return createResult
    }

    override fun observeForCase(caseRecordId: String): Flow<List<ReferralRequest>> =
        flowOf(created.filter { it.caseRecordId == caseRecordId })

    override fun observeAll(): Flow<List<ReferralRequest>> = flowOf(created)
}

class FakeKernelReportRepository : KernelReportRepository {
    val saved = mutableMapOf<String, KernelReportOutput>()
    var saveResult: Result<Unit> = Result.success(Unit)
    private val streams = mutableMapOf<String, MutableStateFlow<KernelReportOutput?>>()

    private fun streamFor(caseRecordId: String) = streams.getOrPut(caseRecordId) { MutableStateFlow(saved[caseRecordId]) }

    override suspend fun save(report: KernelReportOutput): Result<Unit> {
        if (saveResult.isSuccess) {
            saved[report.caseRecordId] = report
            streamFor(report.caseRecordId).value = report
        }
        return saveResult
    }

    override suspend fun getForCase(caseRecordId: String): KernelReportOutput? = saved[caseRecordId]

    override fun observeForCase(caseRecordId: String): Flow<KernelReportOutput?> = streamFor(caseRecordId).asStateFlow()
}

/** H-14: [saved] and [failures] mirror EvaluateReportRepositoryImpl's real "one row per case"
 *  behavior (getIdForCase-resolved REPLACE upsert) — [save] and [saveFailure] each clear the
 *  other's entry for the same case, so a successful retry can never leave both set at once. */
class FakeEvaluateReportRepository : EvaluateReportRepository {
    val saved = mutableMapOf<String, EvaluateReportOutput>()
    val failures = mutableMapOf<String, String>()
    var saveResult: Result<Unit> = Result.success(Unit)
    var saveFailureResult: Result<Unit> = Result.success(Unit)
    private val streams = mutableMapOf<String, MutableStateFlow<EvaluateReportOutput?>>()

    private fun streamFor(caseRecordId: String) = streams.getOrPut(caseRecordId) { MutableStateFlow(saved[caseRecordId]) }

    override suspend fun save(report: EvaluateReportOutput): Result<Unit> {
        if (saveResult.isSuccess) {
            saved[report.caseRecordId] = report
            failures.remove(report.caseRecordId)
            streamFor(report.caseRecordId).value = report
        }
        return saveResult
    }

    override suspend fun saveFailure(caseRecordId: String, failureCode: String): Result<Unit> {
        if (saveFailureResult.isSuccess) {
            failures[caseRecordId] = failureCode
            saved.remove(caseRecordId)
            streamFor(caseRecordId).value = null
        }
        return saveFailureResult
    }

    override suspend fun getForCase(caseRecordId: String): EvaluateReportOutput? = saved[caseRecordId]

    override fun observeForCase(caseRecordId: String): Flow<EvaluateReportOutput?> = streamFor(caseRecordId).asStateFlow()

    override suspend fun getFailureCodeForCase(caseRecordId: String): String? = failures[caseRecordId]
}

/** [enqueued] records every call for assertions; [setWorkState] lets a test simulate the
 *  WorkManager-observed state (QUEUED/RUNNING/NONE) independently of whether a report row has
 *  been saved, the same way the real [com.example.samdapp.data.assessment
 *  .WorkManagerAssessmentScheduler] and the DB are two independently-observed sources. */
class FakeAssessmentQueueScheduler : AssessmentQueueScheduler {
    val enqueued = mutableListOf<String>()
    private val states = mutableMapOf<String, MutableStateFlow<AssessmentWorkState>>()

    private fun streamFor(caseRecordId: String) = states.getOrPut(caseRecordId) { MutableStateFlow(AssessmentWorkState.NONE) }

    override fun enqueueAssessment(caseRecordId: String) {
        enqueued += caseRecordId
    }

    override fun observeWorkState(caseRecordId: String): Flow<AssessmentWorkState> = streamFor(caseRecordId).asStateFlow()

    fun setWorkState(caseRecordId: String, state: AssessmentWorkState) {
        streamFor(caseRecordId).value = state
    }
}

class FakeDiagnosisFeedbackRepository : DiagnosisFeedbackRepository {
    val saved = mutableMapOf<String, DiagnosisFeedback>()
    var saveResult: Result<Unit> = Result.success(Unit)

    override suspend fun save(feedback: DiagnosisFeedback): Result<Unit> {
        if (saveResult.isSuccess) saved[feedback.caseRecordId] = feedback
        return saveResult
    }

    override suspend fun getForCase(caseRecordId: String): DiagnosisFeedback? = saved[caseRecordId]
}

class FakeAilmentRepository : AilmentRepository {
    val added = mutableListOf<AilmentEntry>()
    val deletedIds = mutableListOf<String>()
    private val stream = MutableStateFlow<List<AilmentEntry>>(emptyList())

    override suspend fun addAilment(ailment: AilmentEntry): Result<Unit> {
        added += ailment
        stream.value = added.filter { it.id !in deletedIds }
        return Result.success(Unit)
    }

    override fun observeForEncounter(encounterId: String): Flow<List<AilmentEntry>> =
        stream.asStateFlow()

    override suspend fun markDeleted(id: String): Result<Unit> {
        deletedIds += id
        stream.value = added.filter { it.id !in deletedIds }
        return Result.success(Unit)
    }
}

class FakeDeviceInfoProvider(
    private val deviceId: String = "test-device",
    private val softwareVersion: String = "test-version",
) : DeviceInfoProvider {
    override fun deviceId(): String = deviceId
    override fun softwareVersion(): String = softwareVersion
}

class FakeEncounterRepository(
    private val history: List<ConsultationHistoryEntry> = emptyList(),
    initialEncounters: List<Encounter> = emptyList(),
) : EncounterRepository {
    val started = mutableListOf<Pair<String, String?>>()
    private val encountersById = initialEncounters.associateBy { it.id }.toMutableMap()

    override suspend fun startEncounter(patientId: String, followUpOfEncounterId: String?): Result<Encounter> {
        started += patientId to followUpOfEncounterId
        val encounter = Encounter(
            id = "enc-${started.size}", patientId = patientId, startedAt = Instant.EPOCH,
            createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH, followUpOfEncounterId = followUpOfEncounterId,
        )
        encountersById[encounter.id] = encounter
        return Result.success(encounter)
    }

    override fun observeEncounter(encounterId: String): Flow<Encounter?> = flowOf(encountersById[encounterId])

    override fun observeHistoryForPatient(patientId: String): Flow<List<ConsultationHistoryEntry>> = flowOf(history)
}

class FakeVitalsRepository(
    private val latestByEncounter: Map<String, com.example.samdapp.domain.model.VitalsSnapshot?> = emptyMap(),
) : com.example.samdapp.domain.repository.VitalsRepository {
    override suspend fun saveVitals(snapshot: com.example.samdapp.domain.model.VitalsSnapshot): Result<Unit> = Result.success(Unit)
    override fun observeLatestForEncounter(encounterId: String): Flow<com.example.samdapp.domain.model.VitalsSnapshot?> =
        flowOf(latestByEncounter[encounterId])
}

class FakeConsultationRepository(
    private val byEncounter: Map<String, com.example.samdapp.domain.model.Consultation?> = emptyMap(),
) : com.example.samdapp.domain.repository.ConsultationRepository {
    /** Every consultation handed to [saveConsultation], so a test can assert what would actually
     *  have been persisted (provenance included) rather than only the resulting UI state. */
    val saved = mutableListOf<com.example.samdapp.domain.model.Consultation>()

    override suspend fun saveConsultation(consultation: com.example.samdapp.domain.model.Consultation): Result<Unit> {
        saved += consultation
        return Result.success(Unit)
    }
    override suspend fun addAttachment(attachment: com.example.samdapp.domain.model.Attachment): Result<Unit> = Result.success(Unit)
    override suspend fun updateTranscription(consultationId: String, transcription: String): Result<Unit> = Result.success(Unit)
    override fun observeForEncounter(encounterId: String): Flow<com.example.samdapp.domain.model.Consultation?> =
        flowOf(byEncounter[encounterId])
    override suspend fun getById(consultationId: String): com.example.samdapp.domain.model.Consultation? =
        (saved + byEncounter.values.filterNotNull()).firstOrNull { it.id == consultationId }
}

fun testConsultation(
    encounterId: String,
    patientId: String = "p1",
    chiefComplaint: String = "fever",
): com.example.samdapp.domain.model.Consultation = com.example.samdapp.domain.model.Consultation(
    id = "consult-$encounterId", patientId = patientId, encounterId = encounterId, chiefComplaint = chiefComplaint,
    onset = null, durationBucket = null, severityScore = null, aggravatingFactors = null, relievingFactors = null,
    impactOnDailyActivities = null, impactOnDailyActivitiesProvenance = null, relevantHistory = null, transcription = null, attachments = emptyList(),
    createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
)

class FakeDoctorRepository(private val doctors: List<Doctor> = emptyList()) : DoctorRepository {
    override suspend fun getDoctors(): Result<List<Doctor>> = Result.success(doctors)
}

class FakeDoctorPrescriptionInbox : DoctorPrescriptionInbox {
    var response: IncomingPrescription? = null
    override suspend fun fetchPrescription(caseRecordId: String): Result<IncomingPrescription?> = Result.success(response)
}

class FakeSyncStatus : SyncStatus {
    private val _state = MutableStateFlow(SyncState())
    override val state: Flow<SyncState> = _state.asStateFlow()
    var syncCalls = 0

    override suspend fun syncNow(): Result<Unit> {
        syncCalls++
        _state.value = SyncState(lastSyncedAt = Instant.EPOCH, pendingCount = 0, isSyncing = false)
        return Result.success(Unit)
    }
}

class FakeNetworkMonitor(initial: Boolean = true) : NetworkMonitor {
    private val _isNetworkAvailable = MutableStateFlow(initial)
    override val isNetworkAvailable: Flow<Boolean> = _isNetworkAvailable.asStateFlow()

    fun setAvailable(value: Boolean) {
        _isNetworkAvailable.value = value
    }
}

class FakeAuthTokenStore(
    deviceId: String = "fake-device-id",
    accessToken: String? = null,
    refreshToken: String? = null,
) : com.example.samdapp.data.local.auth.AuthTokenStore {
    private var deviceId = deviceId
    private var accessToken = accessToken
    private var refreshToken = refreshToken
    private val _session = MutableStateFlow<UserSession?>(null)
    private val _mustChangePin = MutableStateFlow(false)

    override val session: Flow<UserSession?> = _session.asStateFlow()
    override val mustChangePin: Flow<Boolean> = _mustChangePin.asStateFlow()

    override suspend fun deviceId(): String = deviceId

    override suspend fun snapshot() =
        com.example.samdapp.data.local.auth.TokenSnapshot(deviceId = deviceId, accessToken = accessToken, refreshToken = refreshToken)

    override suspend fun saveLogin(
        accessToken: String,
        refreshToken: String,
        expiresInSeconds: Long,
        mustChangePin: Boolean,
        workerId: String,
        displayName: String,
        role: String,
        facilityId: String,
        facilityName: String,
    ) {
        this.accessToken = accessToken
        this.refreshToken = refreshToken
        _mustChangePin.value = mustChangePin
        _session.value = UserSession(userId = workerId, name = displayName, role = UserRole.valueOf(role))
    }

    override suspend fun saveTokens(accessToken: String, refreshToken: String, expiresInSeconds: Long) {
        this.accessToken = accessToken
        this.refreshToken = refreshToken
    }

    override suspend fun setMustChangePin(value: Boolean) {
        _mustChangePin.value = value
    }

    override suspend fun clear() {
        accessToken = null
        refreshToken = null
        _mustChangePin.value = false
        _session.value = null
    }
}

class FakeAuthSession(initialSession: UserSession? = null, initialMustChangePin: Boolean = false) : AuthSession {
    private val _session = MutableStateFlow(initialSession)
    private val _mustChangePin = MutableStateFlow(initialMustChangePin)

    override fun currentUser(): Flow<UserSession?> = _session.asStateFlow()

    override fun mustChangePin(): Flow<Boolean> = _mustChangePin.asStateFlow()

    override suspend fun signIn(name: String, role: UserRole, pin: String): SignInResult {
        _session.value = UserSession(userId = "fake-user-id", name = name, role = role)
        return SignInResult.Success(mustChangePin = _mustChangePin.value)
    }

    override suspend fun changePin(currentPin: String, newPin: String): ChangePinResult {
        _mustChangePin.value = false
        return ChangePinResult.Success
    }

    override suspend fun signOut() {
        _session.value = null
    }
}

class FakeAuditLogDao : AuditLogDao {
    val inserted = mutableListOf<AuditLogEntity>()
    private val _failedSyncCount = MutableStateFlow(0)

    private fun refreshFailedSyncCount() {
        _failedSyncCount.value = inserted.count { it.syncState == com.example.samdapp.domain.model.SyncState.FAILED }
    }

    override suspend fun insert(entry: AuditLogEntity) {
        inserted += entry
        refreshFailedSyncCount()
    }

    override fun observeAll(): Flow<List<AuditLogEntity>> = flowOf(inserted.toList())

    override fun observeByPatientId(patientId: String): Flow<List<AuditLogEntity>> =
        flowOf(inserted.filter { it.patientId == patientId })

    override fun observeByUserId(userId: String, limit: Int): Flow<List<AuditLogEntity>> =
        flowOf(inserted.filter { it.userId == userId }.take(limit))

    override suspend fun getPendingForSync(): List<AuditLogEntity> =
        inserted.filter { it.syncState == com.example.samdapp.domain.model.SyncState.PENDING }

    override suspend fun applySyncResult(
        id: String,
        syncState: com.example.samdapp.domain.model.SyncState,
        serverVersion: Int?,
        syncErrorCode: String?,
        attemptAt: java.time.Instant,
        sentLocalModifiedAt: java.time.Instant,
    ) {
        val index = inserted.indexOfFirst { it.id == id && it.localModifiedAt == sentLocalModifiedAt }
        if (index >= 0) {
            val entry = inserted[index]
            inserted[index] = entry.copy(syncState = syncState, serverVersion = serverVersion ?: entry.serverVersion, syncErrorCode = syncErrorCode, lastSyncAttemptAt = attemptAt)
            refreshFailedSyncCount()
        }
    }

    override fun observeFailedSyncCount(): Flow<Int> = _failedSyncCount.asStateFlow()
}

class FakeConsultationDocumentRepository : com.example.samdapp.domain.repository.ConsultationDocumentRepository {
    val saved = mutableMapOf<String, com.example.samdapp.domain.model.ConsultationDocument>()
    var uploadResult: ((com.example.samdapp.domain.model.ConsultationDocument) -> Result<com.example.samdapp.domain.model.ConsultationDocument>)? = null
    var retractResult: Result<Unit> = Result.success(Unit)
    val deletedBytesFor = mutableListOf<String>()

    override suspend fun upload(
        consultationId: String,
        sourceUri: String,
        claimedMimeType: String?,
        label: String,
        departmentCode: com.example.samdapp.domain.model.DepartmentCode,
        recordTypeCode: com.example.samdapp.domain.model.RecordTypeCode,
        uploaderUserId: String,
        uploaderRole: String,
    ): Result<com.example.samdapp.domain.model.ConsultationDocument> {
        val document = com.example.samdapp.domain.model.ConsultationDocument(
            id = "doc-${saved.size + 1}", consultationId = consultationId, patientId = "p1", abhaNumber = null,
            label = label, canonicalName = "canonical", departmentCode = departmentCode, recordTypeCode = recordTypeCode,
            storageKey = "storage-key", mimeType = "application/pdf", sizeBytes = 100L, sha256 = "hash",
            source = com.example.samdapp.domain.model.DocumentSource.DIRECT_FILE, uploadedAt = java.time.Instant.EPOCH,
            uploaderUserId = uploaderUserId, uploaderRole = uploaderRole, retractedAt = null, retractionReason = null,
        )
        val result = uploadResult?.invoke(document) ?: Result.success(document)
        result.onSuccess { saved[it.id] = it }
        return result
    }

    override suspend fun getById(documentId: String): com.example.samdapp.domain.model.ConsultationDocument? = saved[documentId]

    override fun observeForConsultation(consultationId: String): Flow<List<com.example.samdapp.domain.model.ConsultationDocument>> =
        flowOf(saved.values.filter { it.consultationId == consultationId && it.retractedAt == null })

    override fun observeIncludingRetracted(consultationId: String): Flow<List<com.example.samdapp.domain.model.ConsultationDocument>> =
        flowOf(saved.values.filter { it.consultationId == consultationId })

    override suspend fun readDecrypted(documentId: String, output: java.io.OutputStream) {
        output.write("decrypted".toByteArray())
    }

    override suspend fun retract(documentId: String, reason: String?): Result<Unit> {
        if (retractResult.isSuccess) {
            saved[documentId]?.let { doc ->
                deletedBytesFor += documentId
                saved[documentId] = doc.copy(retractedAt = java.time.Instant.now(), retractionReason = reason)
            }
        }
        return retractResult
    }
}

class FakeAuditLogRepository(
    private val entries: List<AuditLogEntry> = emptyList(),
) : AuditLogRepository {
    override fun observeRecentForUser(userId: String, limit: Int): Flow<List<AuditLogEntry>> =
        flowOf(entries.take(limit))

    override fun observeForPatient(patientId: String): Flow<List<AuditLogEntry>> =
        flowOf(entries.filter { it.patientId == patientId })
}
