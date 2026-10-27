package com.example.samdapp.data.local.auth

import com.example.samdapp.data.local.LocalClinicalData
import com.example.samdapp.data.local.LocalTransaction
import com.example.samdapp.data.local.audit.RoomAuditLogger
import com.example.samdapp.data.local.dao.AuditLogDao
import com.example.samdapp.data.local.dao.DeviceDataOwnerDao
import com.example.samdapp.data.local.entity.AuditLogEntity
import com.example.samdapp.data.local.entity.DeviceDataOwnerEntity
import com.example.samdapp.data.remote.RetrofitAuthService
import com.example.samdapp.data.sync.MemoryOwnerMismatchHold
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
import com.example.samdapp.domain.audit.AuditAction
import com.example.samdapp.domain.auth.SignInOutbox
import com.example.samdapp.domain.auth.SignInResult
import com.example.samdapp.domain.auth.UserRole
import com.example.samdapp.testutil.FakeAuditLogDao
import com.example.samdapp.testutil.FakeAuthSession
import com.example.samdapp.testutil.FakeAuthTokenStore
import com.example.samdapp.testutil.FakeSyncOutboxRepository
import com.google.gson.Gson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.time.Instant

/**
 * The sign-in path end to end over fakes (facility-scope memo 3.2, ruling FS-R): every branch of
 * the decision as [BackendAuthSession] executes it, with what is saved, what is revoked and what
 * is audited.
 */
class FacilitySignInGateTest {

    // ── fakes ────────────────────────────────────────────────────────────────────

    private class FakeOwnerDao : DeviceDataOwnerDao {
        var owner: DeviceDataOwnerEntity? = null

        /** Runs just before an insert, to let a concurrent sign-in claim first. */
        var beforeInsert: () -> Unit = {}

        /** Every insert loses and no owner appears: a state the real table cannot be in. */
        var loseEveryClaim = false

        override suspend fun insertIfAbsent(owner: DeviceDataOwnerEntity): Long {
            beforeInsert()
            if (this.owner != null || loseEveryClaim) return -1L
            this.owner = owner
            return 1L
        }

        override suspend fun read(): DeviceDataOwnerEntity? = owner
    }

    private data class Row(val actor: String, val action: AuditAction, val patientId: String?, val caseRecordId: String?, val payload: String)

    /** The real audit logger writes through this; [fail] makes every insert throw, as a full or
     *  corrupt database would. */
    private class SwitchableAuditLogDao(private val inner: FakeAuditLogDao = FakeAuditLogDao()) : AuditLogDao by inner {
        var fail = false
        override suspend fun insert(entry: AuditLogEntity) {
            if (fail) throw IllegalStateException("audit write failed")
            inner.insert(entry)
        }
        val rows: List<Row>
            get() = inner.inserted.map { e -> Row(e.userId, AuditAction.values().first { it.value == e.action }, e.patientId, e.caseRecordId, e.payload) }
    }

    /** As Room's transaction: a block that throws leaves the owner as it was before. */
    private class RollbackTransaction(private val owners: FakeOwnerDao) : LocalTransaction {
        override suspend fun <T> run(block: suspend () -> T): T {
            val before = owners.owner
            return try {
                block()
            } catch (e: Exception) {
                owners.owner = before
                throw e
            }
        }
    }

    private enum class Revoke { OK, REFUSED, IO_ERROR, CRASH }

    private class FakeAuthApi(private val facilityId: String?, private val facilityName: String?) : AuthApiService {
        var revoke = Revoke.OK
        val logouts = mutableListOf<String>()

        /** Parsed by Gson as the real response is, so a facility the server leaves out arrives as
         *  null whatever the DTO declares. */
        override suspend fun login(request: LoginRequestDto): Response<ApiEnvelopeDto<LoginResponseDto>> {
            val worker = buildMap<String, Any?> {
                put("worker_id", SERVER_WORKER_ID); put("display_name", "Worker"); put("role", "NURSE")
                if (facilityId != null) put("facility_id", facilityId)
                if (facilityName != null) put("facility_name", facilityName)
            }
            val body = mapOf(
                "access_token" to "access-new", "refresh_token" to "refresh-new", "token_type" to "bearer",
                "expires_in" to 900, "must_change_pin" to false, "worker" to worker,
            )
            return Response.success(ApiEnvelopeDto(true, Gson().fromJson(Gson().toJson(body), LoginResponseDto::class.java), null))
        }

        override suspend fun logout(request: LogoutRequestDto): Response<ApiEnvelopeDto<LogoutResponseDto>> {
            logouts += request.refreshToken
            return when (revoke) {
                Revoke.OK -> Response.success(ApiEnvelopeDto(true, LogoutResponseDto(revoked = true), null))
                Revoke.REFUSED -> Response.error(503, "{}".toResponseBody())
                Revoke.IO_ERROR -> throw IOException("offline")
                Revoke.CRASH -> throw IllegalStateException("unexpected")
            }
        }

        override suspend fun refresh(request: RefreshRequestDto): Response<ApiEnvelopeDto<TokenPairDto>> = error("unused")
        override suspend fun me(): Response<ApiEnvelopeDto<MeResponseDto>> = error("unused")
        override suspend fun changePin(request: ChangePinRequestDto): Response<ApiEnvelopeDto<ChangePinResponseDto>> = error("unused")
    }

    private val alpha = DeviceDataOwnerEntity(facilityId = "fac-a", facilityName = "PHC Alpha", claimedAt = Instant.EPOCH, claimedByWorkerId = "w-a", legacy = false)

    private inner class Harness(
        facilityId: String? = "fac-b",
        facilityName: String? = "PHC Beta",
        holdsData: Boolean = false,
        clinical: suspend () -> Boolean = { holdsData },
    ) {
        val api = FakeAuthApi(facilityId, facilityName)
        val tokens = FakeAuthTokenStore()
        val owners = FakeOwnerDao()
        val audit = SwitchableAuditLogDao()
        val outbox = FakeSyncOutboxRepository().apply { signInOutbox = SignInOutbox(pendingClinical = 2, failed = 1) }
        private val logger = RoomAuditLogger(audit, FakeAuthSession())
        val hold = MemoryOwnerMismatchHold()
        val session = BackendAuthSession(
            RetrofitAuthService(api), tokens,
            FacilitySignInGate(
                owners, object : LocalClinicalData { override suspend fun present() = clinical() }, outbox,
                RollbackTransaction(owners), RetrofitAuthService(api),
            ) { logger },
            hold,
        )

        suspend fun signIn() = session.signIn("Worker", UserRole.NURSE, "482915")

        /** The result, or the exception that escaped as text, so an escape fails at the assertion. */
        suspend fun signInWithoutThrowing(): Any = runCatching { signIn() }.getOrElse { "escaped: $it" }
        suspend fun saved() = tokens.snapshot().refreshToken != null || tokens.session.first() != null
    }

    private fun fields(payload: String) = Json.parseToJsonElement(payload).jsonObject.mapValues { it.value.jsonPrimitive.content }

    private val countFields = mapOf("pending_clinical" to "2", "held_clinical" to "0", "pending_audit" to "0", "failed" to "1", "conflict" to "0")

    // ── tests ────────────────────────────────────────────────────────────────────

    @Test
    fun `a fresh phone is claimed for the login's facility, then the login is saved`() = runTest {
        val h = Harness()

        assertEquals(SignInResult.Success(mustChangePin = false), h.signIn())

        assertEquals("fac-b", h.owners.owner?.facilityId)
        assertEquals("PHC Beta", h.owners.owner?.facilityName)
        assertEquals(false, h.owners.owner?.legacy)
        assertTrue("the login is saved", h.saved())
        assertEquals("no revoke", emptyList<String>(), h.api.logouts)
        val row = h.audit.rows.single()
        assertEquals(AuditAction.LOCAL_DATA_OWNER_CLAIMED, row.action)
        assertEquals("attributed to the signing-in worker", SERVER_WORKER_ID, row.actor)
        assertEquals(mapOf("decision" to "claim", "facility_id" to "fac-b", "legacy" to "false") + countFields, fields(row.payload))
    }

    @Test
    fun `a phone with data and no owner is claimed as legacy`() = runTest {
        val h = Harness(holdsData = true)

        assertEquals(SignInResult.Success(mustChangePin = false), h.signIn())

        assertEquals(true, h.owners.owner?.legacy)
        assertEquals(mapOf("decision" to "legacy_claim", "facility_id" to "fac-b", "legacy" to "true") + countFields, fields(h.audit.rows.single().payload))
    }

    @Test
    fun `the owning facility continues with no claim and no audit row`() = runTest {
        val h = Harness(facilityId = "fac-a", facilityName = "PHC Alpha", holdsData = true)
        h.owners.owner = alpha

        assertEquals(SignInResult.Success(mustChangePin = false), h.signIn())

        assertTrue(h.saved())
        assertEquals(alpha, h.owners.owner)
        assertEquals(emptyList<Row>(), h.audit.rows)
        assertEquals(emptyList<String>(), h.api.logouts)
    }

    @Test
    fun `another facility is blocked, nothing is saved, the new login is revoked and audited`() = runTest {
        val h = Harness(holdsData = true)
        h.owners.owner = alpha

        assertEquals(SignInResult.FacilityBlocked("PHC Alpha"), h.signIn())

        assertTrue("nothing is saved", !h.saved())
        assertEquals("the new refresh token is revoked", listOf("refresh-new"), h.api.logouts)
        assertEquals(alpha, h.owners.owner)
        val row = h.audit.rows.single()
        assertEquals(AuditAction.CROSS_FACILITY_SIGN_IN_BLOCKED, row.action)
        assertEquals("attributed to the signing-in worker", SERVER_WORKER_ID, row.actor)
        assertEquals(
            mapOf("decision" to "block", "facility_id" to "fac-b", "owner_facility_id" to "fac-a", "revoke" to "revoked") + countFields,
            fields(row.payload),
        )
    }

    @Test
    fun `a revoke that fails still saves nothing and is audited as failed`() = runTest {
        listOf(Revoke.REFUSED, Revoke.IO_ERROR, Revoke.CRASH).forEach { failure ->
            val h = Harness()
            h.owners.owner = alpha
            h.api.revoke = failure

            assertEquals("$failure", SignInResult.FacilityBlocked("PHC Alpha"), h.signIn())

            assertTrue("$failure: nothing is saved", !h.saved())
            assertEquals("$failure", "failed", fields(h.audit.rows.single().payload)["revoke"])
        }
    }

    @Test
    fun `a login with no facility id is refused like a block, on a fresh phone and an owned one`() = runTest {
        // Non-null blanks first: a regression that admits a blank id then fails at an assertion,
        // before the null case reaches a non-null parameter.
        listOf("", "  ", null).forEach { blank ->
            listOf(null, alpha).forEach { existing ->
                val h = Harness(facilityId = blank, facilityName = null)
                h.owners.owner = existing

                assertEquals("id '$blank', owner $existing", SignInResult.NoFacility, h.signIn())

                assertTrue("nothing is saved", !h.saved())
                assertEquals("nothing is claimed", existing, h.owners.owner)
                assertEquals(listOf("refresh-new"), h.api.logouts)
                val row = h.audit.rows.single()
                assertEquals(AuditAction.CROSS_FACILITY_SIGN_IN_BLOCKED, row.action)
                assertEquals(
                    mapOf("decision" to "no_facility", "facility_id" to "", "owner_facility_id" to (existing?.facilityId ?: "null"), "revoke" to "revoked") + countFields,
                    fields(row.payload),
                )
            }
        }
    }

    @Test
    fun `a claim lost to another facility's concurrent sign-in re-decides and blocks`() = runTest {
        val h = Harness()
        h.owners.beforeInsert = { h.owners.owner = alpha }

        assertEquals(SignInResult.FacilityBlocked("PHC Alpha"), h.signIn())

        assertTrue(!h.saved())
        assertEquals(alpha, h.owners.owner)
        assertEquals(listOf(AuditAction.CROSS_FACILITY_SIGN_IN_BLOCKED), h.audit.rows.map { it.action })
    }

    @Test
    fun `a claim lost to a concurrent sign-in of the same facility re-decides and continues`() = runTest {
        val h = Harness()
        val racer = alpha.copy(facilityId = "fac-b", facilityName = "PHC Beta", claimedByWorkerId = "w-other")
        h.owners.beforeInsert = { h.owners.owner = racer }

        assertEquals(SignInResult.Success(mustChangePin = false), h.signIn())

        assertTrue(h.saved())
        assertEquals("the racer's claim stands", racer, h.owners.owner)
        assertEquals("only the winner audits its claim", emptyList<Row>(), h.audit.rows)
    }

    @Test
    fun `no audit row carries a patient or case id`() = runTest {
        val rows = buildList {
            Harness().apply { signIn() }.audit.rows.let(::addAll)
            Harness().apply { owners.owner = alpha; signIn() }.audit.rows.let(::addAll)
            Harness(facilityId = "").apply { signIn() }.audit.rows.let(::addAll)
        }
        assertEquals(3, rows.size)
        rows.forEach { row ->
            assertNull(row.patientId)
            assertNull(row.caseRecordId)
            assertTrue("${row.action}: no patient field in the payload", fields(row.payload).keys.none { "patient" in it || "case" in it })
        }
    }

    // ── P7b: a new sign-in releases a drain held after SAMD-SYNC-6007; a refused one does not ──

    @Test
    fun `an admitted sign-in releases a drain held after an owner-facility refusal`() = runTest {
        val h = Harness(facilityId = "fac-a", facilityName = "PHC Alpha")
        h.owners.owner = alpha
        h.hold.hold()

        assertEquals(SignInResult.Success(mustChangePin = false), h.signIn())

        assertEquals(false, h.hold.isHeld())
    }

    @Test
    fun `a refused sign-in leaves the hold in place`() = runTest {
        val blocked = Harness().apply { owners.owner = alpha; hold.hold() }
        assertEquals(SignInResult.FacilityBlocked("PHC Alpha"), blocked.signIn())
        assertEquals(true, blocked.hold.isHeld())

        val noFacility = Harness(facilityId = "").apply { hold.hold() }
        assertEquals(SignInResult.NoFacility, noFacility.signIn())
        assertEquals(true, noFacility.hold.isHeld())
    }

    // ── FS-R5: nothing leaves the gate, and the claim and its audit row are atomic ──

    @Test
    fun `an audit write failure during a claim rolls the claim back, saves nothing and refuses`() = runTest {
        listOf(false, true).forEach { holdsData ->
            val h = Harness(holdsData = holdsData)
            h.audit.fail = true

            assertEquals("holds data $holdsData", SignInResult.Unavailable, h.signInWithoutThrowing())

            assertNull("holds data $holdsData: no owner survives the rolled-back claim", h.owners.owner)
            assertTrue("holds data $holdsData: no token is saved", !h.saved())
            assertEquals("holds data $holdsData: the new login is revoked", listOf("refresh-new"), h.api.logouts)
        }
    }

    @Test
    fun `an audit write failure during a block still blocks, with its message`() = runTest {
        val h = Harness()
        h.owners.owner = alpha
        h.audit.fail = true

        assertEquals(SignInResult.FacilityBlocked("PHC Alpha"), h.signIn())

        assertTrue(!h.saved())
        assertEquals(listOf("refresh-new"), h.api.logouts)
        assertEquals(alpha, h.owners.owner)
    }

    @Test
    fun `an audit write failure on a blank facility id still refuses it`() = runTest {
        val h = Harness(facilityId = "")
        h.audit.fail = true

        assertEquals(SignInResult.NoFacility, h.signIn())

        assertTrue(!h.saved())
        assertNull(h.owners.owner)
    }

    @Test
    fun `a lost claim followed by no owner is refused fail closed and audited`() = runTest {
        val h = Harness()
        h.owners.loseEveryClaim = true

        assertEquals(SignInResult.Unavailable, h.signIn())

        assertTrue(!h.saved())
        assertNull(h.owners.owner)
        assertEquals(listOf("refresh-new"), h.api.logouts)
        val row = h.audit.rows.single()
        assertEquals(AuditAction.CROSS_FACILITY_SIGN_IN_BLOCKED, row.action)
        assertEquals("unavailable", fields(row.payload)["decision"])
    }

    @Test
    fun `a failing database read is refused fail closed, never thrown`() = runTest {
        val h = Harness(clinical = { throw IllegalStateException("database unreadable") })

        assertEquals(SignInResult.Unavailable, h.signInWithoutThrowing())

        assertTrue(!h.saved())
        assertNull(h.owners.owner)
        assertEquals(listOf("refresh-new"), h.api.logouts)
        assertEquals("unavailable", fields(h.audit.rows.single().payload)["decision"])
    }

    private companion object {
        const val SERVER_WORKER_ID = "w-server-1"
    }
}
