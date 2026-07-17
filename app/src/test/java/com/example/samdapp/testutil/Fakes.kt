package com.example.samdapp.testutil

import com.example.samdapp.data.local.dao.AuditLogDao
import com.example.samdapp.data.local.entity.AuditLogEntity
import com.example.samdapp.domain.audit.AuditLogEntry
import com.example.samdapp.domain.audit.AuditLogger
import com.example.samdapp.domain.config.DeviceInfoProvider
import com.example.samdapp.domain.auth.AuthSession
import com.example.samdapp.domain.auth.UserRole
import com.example.samdapp.domain.auth.UserSession
import com.example.samdapp.domain.model.AbhaProfile
import com.example.samdapp.domain.model.AilmentEntry
import com.example.samdapp.domain.doctor.DoctorPrescriptionInbox
import com.example.samdapp.domain.doctor.IncomingPrescription
import com.example.samdapp.domain.model.CaseRecord
import com.example.samdapp.domain.model.ConsultationHistoryEntry
import com.example.samdapp.domain.model.DoctorTrackerEntry
import com.example.samdapp.domain.model.CaseStatus
import com.example.samdapp.domain.model.Doctor
import com.example.samdapp.domain.model.Encounter
import com.example.samdapp.domain.model.KernelReportOutput
import com.example.samdapp.domain.model.Patient
import com.example.samdapp.domain.model.Prescription
import com.example.samdapp.domain.model.ReferralRequest
import com.example.samdapp.domain.repository.AbhaProfileRepository
import com.example.samdapp.domain.repository.AilmentRepository
import com.example.samdapp.domain.repository.AuditLogRepository
import com.example.samdapp.domain.repository.CaseRecordRepository
import com.example.samdapp.domain.repository.DoctorRepository
import com.example.samdapp.domain.repository.EncounterRepository
import com.example.samdapp.domain.repository.KernelReportRepository
import com.example.samdapp.domain.repository.PatientRepository
import com.example.samdapp.domain.repository.PrescriptionRepository
import com.example.samdapp.domain.repository.ReferralRepository
import com.example.samdapp.domain.sync.SyncState
import com.example.samdapp.domain.sync.SyncStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import java.time.Instant

fun testPatient(id: String, fullName: String = "P-$id", age: Int? = 30): Patient = Patient(
    id = id, fullName = fullName, dateOfBirth = null, age = age, biologicalSex = "Female",
    guardianOrSpouseName = null, guardianRelation = null, mobileNumber = null, aadhaarNumber = null, abhaNumber = null,
    village = null, block = null, district = null, state = null, pincode = null, category = null,
    maritalStatus = null, bloodGroup = null, emergencyContact = null, primaryCareClinicName = null,
    referringPhysicianName = null, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
)

class FakeAuditLogger : AuditLogger {
    data class Entry(val action: String, val patientId: String?, val caseRecordId: String?, val payload: String)

    val logged = mutableListOf<Entry>()

    override suspend fun log(action: String, patientId: String?, caseRecordId: String?, payload: String) {
        logged += Entry(action, patientId, caseRecordId, payload)
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

    override fun observeRecentPatients(days: Int): Flow<List<Patient>> = flowOf(today)
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

fun testAbhaProfile(
    abhaId: String = "12345678901234",
    name: String = "Anita Kumari",
    mobileNumber: String? = "9998887776",
): AbhaProfile = AbhaProfile(
    abhaId = abhaId, abhaAddress = null, name = name, dateOfBirth = null, gender = "Female",
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

    override suspend fun assignDoctor(caseRecordId: String, doctorId: String): Result<Unit> {
        val updated = records[caseRecordId]?.copy(status = CaseStatus.SENT_TO_DOCTOR, assignedDoctorId = doctorId) ?: return Result.failure(NoSuchElementException())
        records[caseRecordId] = updated
        streamFor(caseRecordId).value = updated
        return Result.success(Unit)
    }

    override suspend fun markPrescriptionReceived(caseRecordId: String): Result<Unit> = updateStatus(caseRecordId, CaseStatus.PRESCRIPTION_RECEIVED)

    private fun updateStatus(caseRecordId: String, status: CaseStatus): Result<Unit> {
        val updated = records[caseRecordId]?.copy(status = status) ?: return Result.failure(NoSuchElementException())
        records[caseRecordId] = updated
        streamFor(caseRecordId).value = updated
        return Result.success(Unit)
    }

    override fun observeCaseRecord(caseRecordId: String): Flow<CaseRecord?> = streamFor(caseRecordId).asStateFlow()

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

    override suspend fun save(report: KernelReportOutput): Result<Unit> {
        if (saveResult.isSuccess) saved[report.caseRecordId] = report
        return saveResult
    }

    override suspend fun getForCase(caseRecordId: String): KernelReportOutput? = saved[caseRecordId]
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

class FakeAuthSession(initialSession: UserSession? = null) : AuthSession {
    private val _session = MutableStateFlow(initialSession)

    override fun currentUser(): Flow<UserSession?> = _session.asStateFlow()

    override suspend fun signIn(name: String, role: UserRole) {
        _session.value = UserSession(userId = "fake-user-id", name = name, role = role)
    }

    override suspend fun signOut() {
        _session.value = null
    }
}

class FakeAuditLogDao : AuditLogDao {
    val inserted = mutableListOf<AuditLogEntity>()

    override suspend fun insert(entry: AuditLogEntity) {
        inserted += entry
    }

    override fun observeAll(): Flow<List<AuditLogEntity>> = flowOf(inserted.toList())

    override fun observeByPatientId(patientId: String): Flow<List<AuditLogEntity>> =
        flowOf(inserted.filter { it.patientId == patientId })

    override fun observeByUserId(userId: String, limit: Int): Flow<List<AuditLogEntity>> =
        flowOf(inserted.filter { it.userId == userId }.take(limit))
}

class FakeAuditLogRepository(
    private val entries: List<AuditLogEntry> = emptyList(),
) : AuditLogRepository {
    override fun observeRecentForUser(userId: String, limit: Int): Flow<List<AuditLogEntry>> =
        flowOf(entries.take(limit))

    override fun observeForPatient(patientId: String): Flow<List<AuditLogEntry>> =
        flowOf(entries.filter { it.patientId == patientId })
}
