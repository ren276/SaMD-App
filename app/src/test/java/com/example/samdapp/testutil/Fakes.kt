package com.example.samdapp.testutil

import com.example.samdapp.data.local.dao.AuditLogDao
import com.example.samdapp.data.local.entity.AuditLogEntity
import com.example.samdapp.domain.audit.AuditLogger
import com.example.samdapp.domain.auth.AuthSession
import com.example.samdapp.domain.auth.UserRole
import com.example.samdapp.domain.auth.UserSession
import com.example.samdapp.domain.model.Patient
import com.example.samdapp.domain.repository.PatientRepository
import com.example.samdapp.domain.sync.SyncState
import com.example.samdapp.domain.sync.SyncStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import java.time.Instant

fun testPatient(id: String, fullName: String = "P-$id", age: Int? = 30): Patient = Patient(
    id = id, fullName = fullName, dateOfBirth = null, age = age, biologicalSex = "Female",
    guardianOrSpouseName = null, mobileNumber = null, aadhaarNumber = null, abhaNumber = null,
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
}
