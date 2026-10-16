package com.example.samdapp.slm

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.samdapp.data.local.AppDatabase
import com.example.samdapp.data.local.audit.RoomAuditLogger
import com.example.samdapp.data.repository.AbhaProfileRepositoryImpl
import com.example.samdapp.data.repository.AilmentRepositoryImpl
import com.example.samdapp.data.repository.CaseRecordRepositoryImpl
import com.example.samdapp.data.repository.ConsultationRepositoryImpl
import com.example.samdapp.data.repository.DoctorRepositoryImpl
import com.example.samdapp.data.repository.EncounterRepositoryImpl
import com.example.samdapp.data.repository.EvaluateReportRepositoryImpl
import com.example.samdapp.data.repository.KernelReportRepositoryImpl
import com.example.samdapp.data.repository.PatientRepositoryImpl
import com.example.samdapp.data.repository.PrescriptionRepositoryImpl
import com.example.samdapp.data.repository.VitalsRepositoryImpl
import com.example.samdapp.domain.auth.AuthSession
import com.example.samdapp.domain.auth.ChangePinResult
import com.example.samdapp.domain.auth.SignInResult
import com.example.samdapp.domain.auth.UserRole
import com.example.samdapp.domain.auth.UserSession
import com.example.samdapp.domain.model.KernelDecision
import com.example.samdapp.domain.model.MedicationLine
import com.example.samdapp.domain.model.Patient
import com.example.samdapp.domain.model.Prescription
import com.example.samdapp.domain.model.SyncState
import com.example.samdapp.domain.report.ReportFormatter
import com.example.samdapp.domain.slm.ApprovedRecordReader
import com.example.samdapp.domain.slm.SANITIZER_TARGET_MODEL_ID
import com.example.samdapp.domain.slm.SlmEngine
import com.example.samdapp.domain.slm.SlmEngineIdentity
import com.example.samdapp.domain.slm.SlmEngineKind
import com.example.samdapp.domain.slm.SlmFinishReason
import com.example.samdapp.domain.slm.SlmReadbackResult
import com.example.samdapp.domain.slm.SlmReadbackUseCase
import com.example.samdapp.domain.slm.SlmRefusal
import com.example.samdapp.domain.slm.SlmRunConditions
import com.example.samdapp.domain.usecase.AssembleReportUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * The readback audit rows as SQLite stores them (on-device SLM memo 8, slice S4; CLAUDE.md:
 * assert the persisted row, not the call list). The real seam over the real reader, assembler and
 * Room repositories, writing through the real [RoomAuditLogger] into a real (in-memory) Room
 * database; only the engine is a fake. Mock data only.
 *
 * Sentinels sit in the questions and in the engine output; no column of any stored row may carry
 * them, nor the drug or the diagnosis.
 */
@RunWith(AndroidJUnit4::class)
class SlmReadbackAuditPersistenceTest {

    private lateinit var db: AppDatabase
    private val session = MutableStateFlow<UserSession?>(UserSession(userId = "u-audit", name = "Mock Worker", role = UserRole.DOCTOR))

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun theFourRowKindsArePersistedWithMetadataOnly() = runBlocking {
        val caseId = seedApprovedCase()

        // 1. Answered, REMOTE kind, open tier: INVOKED, SANITIZER_ACTIVITY.
        val answered = seam(FakeEngine("$GROUNDED $OUTPUT_SENTINEL", REMOTE, SANITIZER_TARGET_MODEL_ID))(caseId, "Summarise the record $QUESTION_SENTINEL")
        assertTrue("$answered", answered is SlmReadbackResult.Answer)

        // 2. LOCAL kind: the identity gate refuses a local id until S6, so INVOKED, SANITIZER, OUTPUT_SUPPRESSED.
        val suppressed = seam(FakeEngine(OUTPUT_SENTINEL, LOCAL, LOCAL.modelId, SlmRunConditions(1, true)))(caseId, "Summarise the record $QUESTION_SENTINEL")
        assertEquals(SlmReadbackResult.Refused(SlmRefusal.SERVED_MODEL_MISMATCH), suppressed)

        // 3. A worker's out-of-scope question: INPUT_REFUSED only.
        session.value = UserSession(userId = "u-audit", name = "Mock Worker", role = UserRole.ASHA_WORKER)
        val engine = FakeEngine(OUTPUT_SENTINEL, REMOTE, SANITIZER_TARGET_MODEL_ID)
        val refused = seam(engine)(caseId, "is ibuprofen safe with lisinopril $QUESTION_SENTINEL")
        assertEquals(SlmReadbackResult.Refused(SlmRefusal.OUT_OF_SCOPE_PHRASING), refused)
        assertEquals(0, engine.calls)

        // Timestamps can tie at clock resolution, so rows are matched by content, not by order.
        val rows = db.auditLogDao().observeAll().first().filter { it.action.startsWith("slm_readback_") }
        assertEquals(
            listOf(
                "slm_readback_input_refused", "slm_readback_invoked", "slm_readback_invoked",
                "slm_readback_output_suppressed", "slm_readback_sanitizer_activity", "slm_readback_sanitizer_activity",
            ),
            rows.map { it.action }.sorted(),
        )
        rows.forEach { row ->
            assertEquals(caseId, row.caseRecordId)
            assertEquals(null, row.patientId)
            assertEquals("u-audit", row.userId)
            assertEquals("queued for sync", SyncState.PENDING, row.syncState)
            val stored = listOf(row.payload, row.action, row.userId, row.caseRecordId.orEmpty()).joinToString("|")
            FORBIDDEN.forEach { assertFalse("${row.action} stores '$it': $stored", stored.contains(it, ignoreCase = true)) }
        }
        fun rowsOf(action: String) = rows.filter { it.action == action }.map { fields(it.payload) }

        val invoked = rowsOf("slm_readback_invoked").associateBy { it["engine"] }
        val remote = invoked.getValue("REMOTE")
        assertEquals(SANITIZER_TARGET_MODEL_ID, remote["model_id"])
        assertEquals("PHYSICIAN", remote["tier"])
        assertEquals("STOP", remote["finish_reason"])
        assertEquals(null, remote["engine_error"])
        val local = invoked.getValue("LOCAL")
        assertEquals("GPU", local["accelerator"])
        assertEquals(LOCAL.artifactSha256, local["artifact_sha256"])
        assertEquals("litertlm-android 0.18.0", local["runtime_version"])
        assertEquals("1", local["thermal_status"])
        assertEquals("true", local["gpu_init_failed"])
        rowsOf("slm_readback_sanitizer_activity").forEach { assertEquals("0", it["spans"]) }
        assertEquals(
            mapOf("reason" to "SERVED_MODEL_MISMATCH", "engine" to "LOCAL", "model_id" to LOCAL.modelId),
            rowsOf("slm_readback_output_suppressed").single(),
        )
        val input = rowsOf("slm_readback_input_refused").single()
        assertEquals("OUT_OF_SCOPE_PHRASING", input["reason"])
        assertEquals("COMMUNITY", input["tier"])
        assertEquals("is ibuprofen safe with lisinopril $QUESTION_SENTINEL".length.toString(), input["question_chars"])
    }

    private fun fields(payload: String): Map<String, String?> =
        Json.parseToJsonElement(payload).jsonObject.mapValues { (_, v) -> if (v is JsonNull) null else v.jsonPrimitive.content }

    private suspend fun seedApprovedCase(): String {
        val patientId = "p-audit"
        PatientRepositoryImpl(db.patientDao()).register(mockPatient(patientId)).getOrThrow()
        val encounter = EncounterRepositoryImpl(db.encounterDao()).startEncounter(patientId).getOrThrow()
        val case = CaseRecordRepositoryImpl(db.caseRecordDao()).createDraft(patientId, encounter.id).getOrThrow()
        PrescriptionRepositoryImpl(db.prescriptionDao()).save(
            Prescription(
                id = "rx-audit", patientId = patientId, encounterId = encounter.id, caseRecordId = case.id,
                doctorId = "doc-1", diagnosis = DIAGNOSIS, medications = listOf(AMOXICILLIN),
                kernelDecision = KernelDecision.AGREE, createdAt = Instant.EPOCH,
            ),
        ).getOrThrow()
        return case.id
    }

    private fun seam(engine: SlmEngine): SlmReadbackUseCase {
        val auth = object : AuthSession {
            override fun currentUser(): Flow<UserSession?> = session
            override fun mustChangePin(): Flow<Boolean> = flowOf(false)
            override suspend fun signIn(name: String, role: UserRole, pin: String): SignInResult = error("not used")
            override suspend fun changePin(currentPin: String, newPin: String): ChangePinResult = error("not used")
            override suspend fun signOut() = error("not used")
        }
        val caseRecords = CaseRecordRepositoryImpl(db.caseRecordDao())
        val assemble = AssembleReportUseCase(
            caseRecordRepository = caseRecords,
            patientRepository = PatientRepositoryImpl(db.patientDao()),
            encounterRepository = EncounterRepositoryImpl(db.encounterDao()),
            abhaProfileRepository = AbhaProfileRepositoryImpl(db.abhaProfileDao()),
            consultationRepository = ConsultationRepositoryImpl(db.consultationDao(), db.attachmentDao()),
            ailmentRepository = AilmentRepositoryImpl(db.ailmentDao()),
            vitalsRepository = VitalsRepositoryImpl(db.observationDao()),
            prescriptionRepository = PrescriptionRepositoryImpl(db.prescriptionDao()),
            kernelReportRepository = KernelReportRepositoryImpl(db.kernelReportDao()),
            evaluateReportRepository = EvaluateReportRepositoryImpl(db.evaluateReportDao()),
            doctorRepository = DoctorRepositoryImpl(db.doctorDao()),
            reportFormatter = ReportFormatter(),
        )
        return SlmReadbackUseCase(
            authSession = auth,
            approvedRecordReader = ApprovedRecordReader(auth, caseRecords, assemble),
            engine = engine,
            auditLogger = RoomAuditLogger(db.auditLogDao(), auth),
        )
    }

    private class FakeEngine(
        private val output: String,
        private val identity: SlmEngineIdentity,
        private val served: String?,
        private val conditions: SlmRunConditions? = null,
    ) : SlmEngine {
        var calls = 0

        override fun generate(caseRecordId: String, prompt: String, maxOutputTokens: Int): Flow<String> = flow {
            calls++
            emit(output)
        }

        override fun servedModelId(): String? = served
        override fun finishReason(): SlmFinishReason? = SlmFinishReason.STOP
        override fun answeredBy(): SlmEngineIdentity? = identity
        override fun runConditions(): SlmRunConditions? = conditions
    }

    private fun mockPatient(id: String) = Patient(
        id = id, fullName = "Mock Patient", dateOfBirth = null, age = 30, biologicalSex = "Female",
        guardianOrSpouseName = null, guardianRelation = null, mobileNumber = null, aadhaarNumber = null,
        abhaNumber = null, village = null, block = null, district = null, state = null, pincode = null,
        category = null, maritalStatus = null, bloodGroup = null, emergencyContact = null,
        primaryCareClinicName = null, referringPhysicianName = null, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
    )

    private companion object {
        const val QUESTION_SENTINEL = "QSENTINEL7"
        const val OUTPUT_SENTINEL = "OSENTINEL7"
        const val DIAGNOSIS = "Acute bacterial pharyngitis"
        val FORBIDDEN = listOf(QUESTION_SENTINEL, OUTPUT_SENTINEL, "Amoxicillin", "ibuprofen", DIAGNOSIS, "Summarise", "Mock Patient")
        val AMOXICILLIN = MedicationLine(
            genericName = "Amoxicillin", brandName = "Mox", strength = "500 mg", dosage = "1 capsule",
            frequency = "three times a day", route = "oral", duration = "5 days", quantity = "15",
            foodRelation = "after food", instructions = null,
        )
        const val GROUNDED = "The doctor approved Amoxicillin 500 mg by mouth, three times a day, for 5 days, 15 in total."
        val REMOTE = SlmEngineIdentity(SlmEngineKind.REMOTE, null, SANITIZER_TARGET_MODEL_ID, "2db5482b20d746879bb3ef79b5203e9075a2e2b98f54ec7c2f281c1477ddc550", null)
        val LOCAL = SlmEngineIdentity(
            SlmEngineKind.LOCAL, "GPU",
            "litert-community/gemma-4-E2B-it-litert-lm@b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1#181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
            "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c", "litertlm-android 0.18.0",
        )
    }
}
