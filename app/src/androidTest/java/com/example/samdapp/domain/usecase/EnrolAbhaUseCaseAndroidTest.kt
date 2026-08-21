package com.example.samdapp.domain.usecase

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.samdapp.data.local.AppDatabase
import com.example.samdapp.data.repository.AbhaProfileRepositoryImpl
import com.example.samdapp.domain.abha.AbdmAbhaSource
import com.example.samdapp.domain.abha.AbhaApiResult
import com.example.samdapp.domain.abha.AbhaEnrolResult
import com.example.samdapp.domain.abha.AbhaIdentity
import com.example.samdapp.domain.abha.AbhaSessionSnapshot
import com.example.samdapp.domain.abha.AbhaTransactionState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * The Room-level half of the "no raw Aadhaar/OTP on device" guarantee. [AbhaProfileEntity] has no
 * Aadhaar or OTP column at all (checked at STEP 1: `data/local/entity/AbhaProfileEntity.kt` has
 * none), so this test drives the real create flow end to end — [RequestAbhaOtpUseCase] then
 * [EnrolAbhaUseCase], through both OTP rounds, against a real [AbhaProfileRepositoryImpl] backed
 * by an in-memory Room database — and reads the persisted row back through the DAO, per this
 * project's rule that a write-survived-a-path test must assert the ORM-read row, not just the
 * use case's return value. Every sensitive value used along the way (the Aadhaar number, both
 * OTPs, the worker-typed communication mobile) is a distinct, greppable sentinel string, so this
 * test fails loudly if any of them ever end up in a persisted column.
 */
class EnrolAbhaUseCaseAndroidTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    private val aadhaarSentinel = "999911112222"
    private val aadhaarOtpSentinel = "135790"
    private val mobileOtpSentinel = "246801"
    private val workerTypedMobileSentinel = "9123456780"

    @Test
    fun persistedProfileRowContainsNeitherAadhaarNorEitherOtpNorTheTypedMobileNumber() = runBlocking {
        val identity = AbhaIdentity(
            abhaNumber = "43422151056749",
            abhaAddress = "anita@sbx",
            name = "Anita Kumari",
            dateOfBirth = null,
            gender = "Female",
            address = "Village Rampur",
            district = "Sitapur",
            state = "Uttar Pradesh",
            pincode = "261001",
            // ABDM's masked value — distinct on purpose from workerTypedMobileSentinel, so the
            // assertion below proves the stored number came from the identity, not the argument.
            mobileNumber = "XXXXXX0000",
            emailAddress = null,
            photoUrl = null,
            kycVerified = true,
            verificationSource = "ABDM",
            verifiedAt = Instant.EPOCH,
        )
        val source = RecordingAbdmAbhaSource(identity = identity)
        val repository = AbhaProfileRepositoryImpl(db.abhaProfileDao())

        val requested = RequestAbhaOtpUseCase(source)(aadhaarNumber = aadhaarSentinel, consentGiven = true)
        val sessionId = (requested as AbhaEnrolResult.Success).data.sessionId

        val enrolUseCase = EnrolAbhaUseCase(source, repository)
        val firstRound = enrolUseCase(sessionId = sessionId, otp = aadhaarOtpSentinel, mobileNumber = workerTypedMobileSentinel)
        assertEquals(AbhaEnrolOutcome.MobileVerificationRequired, (firstRound as AbhaEnrolResult.Success).data)

        val secondRound = enrolUseCase.verifyCommunicationMobile(sessionId = sessionId, otp = mobileOtpSentinel)
        assertEquals(AbhaEnrolOutcome.Enrolled::class, (secondRound as AbhaEnrolResult.Success).data::class)

        // The ORM read-back, not the use case's own return value.
        val row = db.abhaProfileDao().getByAbhaId(identity.abhaNumber)
        assertNotNull("profile must be persisted by the real ABHA number", row)
        checkNotNull(row)

        val persistedStrings = listOfNotNull(
            row.abhaId, row.abhaAddress, row.name, row.gender, row.address, row.district,
            row.state, row.pincode, row.mobileNumber, row.emailAddress, row.photoUrlMock,
        )
        assertFalse("Aadhaar number must never be persisted", persistedStrings.any { it.contains(aadhaarSentinel) })
        assertFalse("Aadhaar OTP must never be persisted", persistedStrings.any { it.contains(aadhaarOtpSentinel) })
        assertFalse("mobile OTP must never be persisted", persistedStrings.any { it.contains(mobileOtpSentinel) })
        assertFalse(
            "the worker-typed communication mobile must never be persisted, only ABDM's masked value",
            persistedStrings.any { it.contains(workerTypedMobileSentinel) },
        )
        assertEquals("ABDM's masked mobile is what gets stored", identity.mobileNumber, row.mobileNumber)
    }
}

/** Minimal hand-rolled fake, self-contained rather than reused from `src/test`'s
 *  `FakeAbdmAbhaSource`: `androidTest` and `test` are separate Gradle source sets with no shared
 *  sourceSet wired in `app/build.gradle.kts`, so nothing under `src/test` is on this test's
 *  classpath. */
private class RecordingAbdmAbhaSource(private val identity: AbhaIdentity) : AbdmAbhaSource {
    private var sessionId: String = "unset"

    override suspend fun startRegistrationSession(): AbhaApiResult<AbhaSessionSnapshot> {
        sessionId = "session-android-test"
        return AbhaApiResult.Success(AbhaSessionSnapshot(sessionId = sessionId, state = AbhaTransactionState.STARTED))
    }

    override suspend fun submitIdentity(sessionId: String, aadhaarNumber: String): AbhaApiResult<AbhaSessionSnapshot> =
        AbhaApiResult.Success(
            AbhaSessionSnapshot(sessionId = sessionId, state = AbhaTransactionState.OTP_REQUESTED, maskedMobile = "XXXXXX0000"),
        )

    override suspend fun verifyOtp(sessionId: String, otp: String, mobileNumber: String): AbhaApiResult<AbhaSessionSnapshot> =
        AbhaApiResult.Success(AbhaSessionSnapshot(sessionId = sessionId, state = AbhaTransactionState.MOBILE_VERIFICATION_REQUIRED))

    override suspend fun verifyMobileOtp(sessionId: String, otp: String): AbhaApiResult<AbhaSessionSnapshot> =
        AbhaApiResult.Success(AbhaSessionSnapshot(sessionId = sessionId, state = AbhaTransactionState.MOBILE_VERIFIED))

    override suspend fun getSessionState(sessionId: String): AbhaApiResult<AbhaSessionSnapshot> =
        AbhaApiResult.Success(AbhaSessionSnapshot(sessionId = sessionId, state = AbhaTransactionState.MOBILE_VERIFIED))

    override suspend fun getProfile(sessionId: String): AbhaApiResult<AbhaIdentity> = AbhaApiResult.Success(identity)
}
