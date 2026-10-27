package com.example.samdapp.data.local.auth

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.samdapp.data.local.AppDatabase
import com.example.samdapp.data.local.ENTITY_SCOPES
import com.example.samdapp.data.local.RoomLocalClinicalData
import com.example.samdapp.data.local.RoomLocalTransaction
import com.example.samdapp.data.local.audit.RoomAuditLogger
import com.example.samdapp.data.remote.RetrofitAuthService
import com.example.samdapp.data.remote.api.AuthApiService
import com.example.samdapp.data.remote.dto.ApiEnvelopeDto
import com.example.samdapp.data.remote.dto.ChangePinRequestDto
import com.example.samdapp.data.remote.dto.ChangePinResponseDto
import com.example.samdapp.data.remote.dto.LoginRequestDto
import com.example.samdapp.data.remote.dto.LoginResponseDto
import com.example.samdapp.data.remote.dto.LogoutRequestDto
import com.example.samdapp.data.remote.dto.LogoutResponseDto
import com.example.samdapp.data.remote.dto.MeResponseDto
import com.example.samdapp.data.remote.dto.RefreshRequestDto
import com.example.samdapp.data.remote.dto.TokenPairDto
import com.example.samdapp.data.sync.MemoryOwnerMismatchHold
import com.example.samdapp.data.sync.RoomSyncOutboxRepository
import com.example.samdapp.domain.audit.AuditAction
import com.example.samdapp.domain.auth.AuthSession
import com.example.samdapp.domain.auth.ChangePinResult
import com.example.samdapp.domain.auth.SignInResult
import com.example.samdapp.domain.auth.UserRole
import com.example.samdapp.domain.auth.UserSession
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.time.Instant
import java.util.Collections

/**
 * The facility-scoped sign-in (facility-scope memo 3.2, ruling FS-R) on the device: the real
 * database (a test file of its own, never the app's), the real owner DAO, clinical-data check,
 * outbox counts and audit logger, and a fake auth API. Only the network is faked.
 */
class FacilitySignInDeviceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val testDbName = "facility-sign-in-test.db"
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        context.deleteDatabase(testDbName)
        db = Room.databaseBuilder(context, AppDatabase::class.java, testDbName).build()
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(testDbName)
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────

    private class FakeAuthApi(private val facilityId: String?, private val facilityName: String?, private val workerId: String) : AuthApiService {
        val logouts: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override suspend fun login(request: LoginRequestDto): Response<ApiEnvelopeDto<LoginResponseDto>> {
            val worker = buildMap<String, Any?> {
                put("worker_id", workerId); put("display_name", "Worker"); put("role", "NURSE")
                if (facilityId != null) put("facility_id", facilityId)
                if (facilityName != null) put("facility_name", facilityName)
            }
            val body = mapOf(
                "access_token" to "access-$workerId", "refresh_token" to "refresh-$workerId", "token_type" to "bearer",
                "expires_in" to 900, "must_change_pin" to false, "worker" to worker,
            )
            return Response.success(ApiEnvelopeDto(true, Gson().fromJson(Gson().toJson(body), LoginResponseDto::class.java), null))
        }

        override suspend fun logout(request: LogoutRequestDto): Response<ApiEnvelopeDto<LogoutResponseDto>> {
            logouts += request.refreshToken
            return Response.success(ApiEnvelopeDto(true, LogoutResponseDto(revoked = true), null))
        }

        override suspend fun refresh(request: RefreshRequestDto): Response<ApiEnvelopeDto<TokenPairDto>> = error("unused")
        override suspend fun me(): Response<ApiEnvelopeDto<MeResponseDto>> = error("unused")
        override suspend fun changePin(request: ChangePinRequestDto): Response<ApiEnvelopeDto<ChangePinResponseDto>> = error("unused")
    }

    /** In memory: the test never touches the app's own `auth_session` DataStore. */
    private class MemoryTokenStore : AuthTokenStore {
        @Volatile var refreshToken: String? = null
        private val _session = MutableStateFlow<UserSession?>(null)
        override val session: Flow<UserSession?> = _session
        override val mustChangePin: Flow<Boolean> = MutableStateFlow(false)
        override suspend fun deviceId() = "device-test"
        override suspend fun snapshot() = TokenSnapshot(deviceId = "device-test", accessToken = null, refreshToken = refreshToken)
        override suspend fun saveLogin(
            accessToken: String, refreshToken: String, expiresInSeconds: Long, mustChangePin: Boolean,
            workerId: String, displayName: String, role: String, facilityId: String, facilityName: String,
        ) {
            this.refreshToken = refreshToken
            _session.value = UserSession(workerId, displayName, UserRole.valueOf(role))
        }
        override suspend fun saveTokens(accessToken: String, refreshToken: String, expiresInSeconds: Long) = error("unused")
        override suspend fun setMustChangePin(value: Boolean) = error("unused")
        override suspend fun clear() { refreshToken = null; _session.value = null }
    }

    private inner class Device(facilityId: String?, facilityName: String?, workerId: String) {
        val api = FakeAuthApi(facilityId, facilityName, workerId)
        val tokens = MemoryTokenStore()
        val session: BackendAuthSession

        init {
            val audit = RoomAuditLogger(db.auditLogDao(), NoSession)
            val gate = FacilitySignInGate(
                db.deviceDataOwnerDao(), RoomLocalClinicalData(db), outboxRepository(), RoomLocalTransaction(db),
                RetrofitAuthService(api),
            ) { audit }
            session = BackendAuthSession(RetrofitAuthService(api), tokens, gate, MemoryOwnerMismatchHold())
        }

        suspend fun signIn() = session.signIn("Worker", UserRole.NURSE, "482915")
    }

    /** The audit logger's session lookup is only for [com.example.samdapp.domain.audit.AuditLogger.log];
     *  every row here goes through `logAs`, so no session is ever consulted. */
    private object NoSession : AuthSession {
        override fun currentUser(): Flow<UserSession?> = MutableStateFlow(null)
        override fun mustChangePin(): Flow<Boolean> = MutableStateFlow(false)
        override suspend fun signIn(name: String, role: UserRole, pin: String): SignInResult = error("unused")
        override suspend fun changePin(currentPin: String, newPin: String): ChangePinResult = error("unused")
        override suspend fun signOut() = error("unused")
    }

    private fun outboxRepository() = RoomSyncOutboxRepository(
        patientDao = db.patientDao(), encounterDao = db.encounterDao(),
        consultationDao = db.consultationDao(), attachmentDao = db.attachmentDao(),
        observationDao = db.observationDao(), ailmentDao = db.ailmentDao(),
        medicalHistoryItemDao = db.medicalHistoryItemDao(), allergyDao = db.allergyDao(),
        familyHistoryEntryDao = db.familyHistoryEntryDao(), socialHistoryDao = db.socialHistoryDao(),
        medicationEntryDao = db.medicationEntryDao(), caseRecordDao = db.caseRecordDao(),
        kernelReportDao = db.kernelReportDao(), evaluateReportDao = db.evaluateReportDao(),
        diagnosisFeedbackDao = db.diagnosisFeedbackDao(), prescriptionDao = db.prescriptionDao(),
        referralDao = db.referralDao(), abhaProfileDao = db.abhaProfileDao(),
        auditLogDao = db.auditLogDao(),
    )

    /** One row in [table], a value for every column by its declared type. */
    private fun seedRow(table: String) {
        val sql = db.openHelper.writableDatabase
        val columns = sql.query("PRAGMA table_info(`$table`)").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name")) to c.getString(c.getColumnIndexOrThrow("type"))) }
        }
        val values: List<Any> = columns.map { (name, type) ->
            when {
                // Read back as an enum by the outbox counts, so it must be a real state.
                name == "syncState" -> "SYNCED"
                type.uppercase() == "INTEGER" -> 1L
                type.uppercase() == "REAL" -> 0.5
                else -> "$table-$name"
            }
        }
        sql.execSQL(
            "INSERT INTO `$table` (${columns.joinToString { "`${it.first}`" }}) VALUES (${columns.joinToString { "?" }})",
            values.toTypedArray<Any?>(),
        )
    }

    private fun ownerRows() = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM device_data_owner").use { it.moveToFirst(); it.getInt(0) }

    private suspend fun auditRows() = db.auditLogDao().observeAll().first()

    private suspend fun claimAlpha() = assertTrue(db.deviceDataOwnerDao().claim("fac-a", "PHC Alpha", Instant.EPOCH, "w-a", legacy = false))

    // ── tests ────────────────────────────────────────────────────────────────────

    @Test
    fun aFreshDatabaseIsClaimed() = runBlocking {
        val device = Device("fac-b", "PHC Beta", "w-b1")

        assertEquals(SignInResult.Success(mustChangePin = false), device.signIn())

        val owner = db.deviceDataOwnerDao().read()
        assertEquals("fac-b", owner?.facilityId)
        assertEquals(false, owner?.legacy)
        assertEquals("w-b1", owner?.claimedByWorkerId)
        assertEquals("refresh-w-b1", device.tokens.refreshToken)
        val row = auditRows().single()
        assertEquals(AuditAction.LOCAL_DATA_OWNER_CLAIMED.value, row.action)
        assertEquals("w-b1", row.userId)
        assertNull(row.patientId)
    }

    @Test
    fun theSameFacilityContinues() = runBlocking {
        claimAlpha()
        seedRow("patients")

        val device = Device("fac-a", "PHC Alpha", "w-a2")
        assertEquals(SignInResult.Success(mustChangePin = false), device.signIn())

        assertEquals("refresh-w-a2", device.tokens.refreshToken)
        assertEquals("w-a", db.deviceDataOwnerDao().read()?.claimedByWorkerId)
        assertEquals("no audit row for a plain continue", 0, auditRows().size)
        assertEquals(emptyList<String>(), device.api.logouts)
    }

    @Test
    fun anotherFacilityIsBlockedWithNoTokenSavedAndLogoutCalled() = runBlocking {
        claimAlpha()
        seedRow("patients")

        val device = Device("fac-b", "PHC Beta", "w-b1")
        assertEquals(SignInResult.FacilityBlocked("PHC Alpha"), device.signIn())

        assertNull("no token is saved", device.tokens.refreshToken)
        assertNull("no session exists", device.tokens.session.first())
        assertEquals("logout is called with the new refresh token", listOf("refresh-w-b1"), device.api.logouts)
        assertEquals("fac-a", db.deviceDataOwnerDao().read()?.facilityId)
        val row = auditRows().single()
        assertEquals(AuditAction.CROSS_FACILITY_SIGN_IN_BLOCKED.value, row.action)
        assertEquals("w-b1", row.userId)
        assertNull(row.patientId)
    }

    @Test
    fun aDatabaseWithDataAndNoMarkerIsClaimedAsLegacy() = runBlocking {
        seedRow("patients")

        val device = Device("fac-b", "PHC Beta", "w-b1")
        assertEquals(SignInResult.Success(mustChangePin = false), device.signIn())

        assertEquals(true, db.deviceDataOwnerDao().read()?.legacy)
        assertTrue(auditRows().single().payload.contains("\"legacy\":\"true\""))
    }

    @Test
    fun aBlankFacilityIdIsRefused() = runBlocking {
        val device = Device("", null, "w-x")

        assertEquals(SignInResult.NoFacility, device.signIn())

        assertNull(device.tokens.refreshToken)
        assertNull("a fresh phone is not claimed for an account with no facility", db.deviceDataOwnerDao().read())
        assertEquals(listOf("refresh-w-x"), device.api.logouts)
    }

    /**
     * Both sign-ins read "no owner" and decide CLAIM before either can insert: a second connection
     * holds the write lock while they start. Exactly one claim wins; the other re-decides and is
     * blocked.
     */
    @Test
    fun twoConcurrentClaimsLeaveExactlyOneOwner() = runBlocking {
        db.deviceDataOwnerDao().read() // opens the database, so the blocker below finds it in WAL mode
        val alpha = Device("fac-a", "PHC Alpha", "w-a1")
        val beta = Device("fac-b", "PHC Beta", "w-b1")
        val blocker = SQLiteDatabase.openDatabase(
            context.getDatabasePath(testDbName).path, null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING,
        )
        blocker.beginTransactionNonExclusive()
        val results = listOf(alpha, beta).map { device -> async(Dispatchers.IO) { device.signIn() } }
        delay(LOCK_HELD_MS)
        blocker.endTransaction()
        blocker.close()
        val outcomes = results.awaitAll()

        assertEquals("one owner row", 1, ownerRows())
        val owner = db.deviceDataOwnerDao().read()!!.facilityId
        val winner = if (owner == "fac-a") alpha else beta
        val loser = if (owner == "fac-a") beta else alpha
        assertEquals("exactly one sign-in succeeds: $outcomes", 1, outcomes.count { it is SignInResult.Success })
        assertTrue("the winner's login is saved", winner.tokens.refreshToken != null)
        assertNull("the loser's is not", loser.tokens.refreshToken)
        assertEquals("the loser's login is revoked", 1, loser.api.logouts.size)
        assertEquals(1, auditRows().count { it.action == AuditAction.LOCAL_DATA_OWNER_CLAIMED.value })
        assertEquals(1, auditRows().count { it.action == AuditAction.CROSS_FACILITY_SIGN_IN_BLOCKED.value })
    }

    @Test
    fun everyClinicalTableCountsAsDataAndTheReferenceTablesDoNot() = runBlocking {
        val check = RoomLocalClinicalData(db)
        assertFalse("an empty database holds nothing", check.present())

        ENTITY_SCOPES.values.filterNot { it.clinical }.forEach { seedRow(it.table) }
        assertFalse("the doctors list and the owner marker are not clinical data", check.present())

        ENTITY_SCOPES.values.filter { it.clinical }.forEach { scope ->
            db.openHelper.writableDatabase.execSQL("DELETE FROM `${scope.table}`")
        }
        ENTITY_SCOPES.values.filter { it.clinical }.forEach { scope ->
            seedRow(scope.table)
            assertTrue("${scope.table} counts as clinical data", check.present())
            db.openHelper.writableDatabase.execSQL("DELETE FROM `${scope.table}`")
        }
    }

    /** Every audit insert fails, in SQLite itself, as a full or corrupt database would make it. */
    private fun failAuditWrites() =
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_audit BEFORE INSERT ON audit_log BEGIN SELECT RAISE(ABORT, 'forced audit failure'); END",
        )

    @Test
    fun anAuditFailureDuringAClaimRollsTheClaimBackAndSavesNothing() = runBlocking {
        failAuditWrites()
        val device = Device("fac-b", "PHC Beta", "w-b1")

        assertEquals(SignInResult.Unavailable, device.signIn())

        assertEquals("the owner row rolled back with its audit row", 0, ownerRows())
        assertNull("no token is saved", device.tokens.refreshToken)
        assertEquals("the new login is revoked", listOf("refresh-w-b1"), device.api.logouts)
        assertEquals(0, auditRows().size)
    }

    @Test
    fun anAuditFailureDuringABlockStillBlocks() = runBlocking {
        claimAlpha()
        failAuditWrites()
        val device = Device("fac-b", "PHC Beta", "w-b1")

        assertEquals(SignInResult.FacilityBlocked("PHC Alpha"), device.signIn())

        assertNull(device.tokens.refreshToken)
        assertEquals(listOf("refresh-w-b1"), device.api.logouts)
        assertEquals("fac-a", db.deviceDataOwnerDao().read()?.facilityId)
    }

    private companion object {
        const val LOCK_HELD_MS = 500L
    }
}
