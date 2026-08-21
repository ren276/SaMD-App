package com.example.samdapp.domain.usecase

import com.example.samdapp.domain.abha.AbdmAbhaSource
import com.example.samdapp.domain.abha.AbhaEnrolResult
import com.example.samdapp.domain.abha.AbhaIdentity
import com.example.samdapp.domain.abha.AbhaTransactionState
import com.example.samdapp.domain.abha.flatMap
import com.example.samdapp.domain.abha.toEnrolResult
import com.example.samdapp.domain.model.AbhaProfile
import com.example.samdapp.domain.repository.AbhaProfileRepository
import javax.inject.Inject

/**
 * Where a verify round leaves the flow. [MobileVerificationRequired] is not an error: ABDM can
 * demand a second OTP on the communication mobile before the account is usable
 * (api-contract.md section 8's `MOBILE_VERIFICATION_REQUIRED`), and the caller answers it with
 * [EnrolAbhaUseCase.verifyCommunicationMobile] rather than restarting.
 */
sealed interface AbhaEnrolOutcome {
    data object MobileVerificationRequired : AbhaEnrolOutcome
    data class Enrolled(val profile: AbhaProfile) : AbhaEnrolOutcome
}

/**
 * Step 2 of the real ABHA create flow: verify the Aadhaar OTP (which is what actually enrols the
 * account with ABDM), handle the conditional second mobile-OTP round, then fetch and store the
 * verified profile.
 *
 * **What is persisted and what is not.** The OTP and the worker-entered communication mobile are
 * parameters only: they are passed to the backend and dropped. Neither is held in a field, written
 * to Room, or put in an audit payload. What IS persisted is the returned [AbhaIdentity], via the
 * existing [AbhaProfileRepository] and the existing `abha_profiles` table, because
 * [com.example.samdapp.presentation.register.RegisterViewModel] reads the profile back by ABHA
 * number to autofill registration. That table has no Aadhaar column and no OTP column, and this
 * use case adds none. Note especially that [AbhaProfile.mobileNumber] is filled from
 * [AbhaIdentity.mobileNumber] (ABDM's masked value, passed through unmodified) and NOT from the
 * `mobileNumber` argument below, so the plaintext number the worker typed never lands in the
 * database. [com.example.samdapp.domain.model.isMaskedAbhaMobile] is what downstream code uses to
 * notice the stored value is masked.
 */
class EnrolAbhaUseCase @Inject constructor(
    private val abdmAbhaSource: AbdmAbhaSource,
    private val abhaProfileRepository: AbhaProfileRepository,
) {
    /** Aadhaar-OTP round. [mobileNumber] is the ABHA communication mobile, required by the
     *  backend's `OtpVerify` schema alongside the OTP and sent in the clear per that schema's own
     *  note; it is a different number from the Aadhaar-linked one that received this OTP. */
    suspend operator fun invoke(
        sessionId: String,
        otp: String,
        mobileNumber: String,
    ): AbhaEnrolResult<AbhaEnrolOutcome> {
        otpShapeError(otp)?.let { return it }
        if (mobileNumber.length != 10 || !mobileNumber.all(Char::isDigit)) {
            return AbhaEnrolResult.Error(message = "Mobile number must be 10 digits.", retryable = false)
        }

        return abdmAbhaSource.verifyOtp(sessionId, otp, mobileNumber).toEnrolResult().flatMap { snapshot ->
            when (snapshot.state) {
                AbhaTransactionState.MOBILE_VERIFICATION_REQUIRED ->
                    AbhaEnrolResult.Success(AbhaEnrolOutcome.MobileVerificationRequired)
                AbhaTransactionState.ENROLLED -> completeEnrolment(sessionId)
                else -> unexpectedState()
            }
        }
    }

    /** Conditional second round, reachable only from [AbhaEnrolOutcome.MobileVerificationRequired].
     *  Takes no mobile number: the backend already holds the one submitted in the first round. */
    suspend fun verifyCommunicationMobile(sessionId: String, otp: String): AbhaEnrolResult<AbhaEnrolOutcome> {
        otpShapeError(otp)?.let { return it }

        return abdmAbhaSource.verifyMobileOtp(sessionId, otp).toEnrolResult().flatMap { snapshot ->
            when (snapshot.state) {
                AbhaTransactionState.MOBILE_VERIFIED -> completeEnrolment(sessionId)
                else -> unexpectedState()
            }
        }
    }

    private suspend fun completeEnrolment(sessionId: String): AbhaEnrolResult<AbhaEnrolOutcome> =
        abdmAbhaSource.getProfile(sessionId).toEnrolResult().flatMap { identity ->
            val profile = identity.toProfile()
            abhaProfileRepository.saveProfile(profile).fold(
                onSuccess = { AbhaEnrolResult.Success(AbhaEnrolOutcome.Enrolled(profile)) },
                // Retryable: the ABHA account exists at ABDM either way, only the local copy
                // failed, and pressing again re-fetches and re-saves rather than re-enrolling.
                onFailure = {
                    AbhaEnrolResult.Error(
                        message = "The ABHA account was created but could not be saved on this device. Try again.",
                        retryable = true,
                    )
                },
            )
        }

    private fun otpShapeError(otp: String): AbhaEnrolResult.Error? =
        if (otp.length != 6 || !otp.all(Char::isDigit)) {
            AbhaEnrolResult.Error(message = "Enter the 6-digit OTP.", retryable = false)
        } else {
            null
        }

    /** A state outside the ones this flow can act on. The backend enforces the state machine
     *  server side (api-contract.md section 8), so this is contract drift rather than anything the
     *  worker did; the state name is deliberately not interpolated into what they are shown. */
    private fun unexpectedState(): AbhaEnrolResult.Error =
        AbhaEnrolResult.Error(
            message = "The ABHA registration is in an unexpected state. Start again from the Aadhaar step.",
            retryable = false,
        )
}

/** [AbhaProfile.photoUrlMock] is the mock-era name of the field; the real photo URL goes in it
 *  unchanged, renaming the column is a migration and out of scope here. */
private fun AbhaIdentity.toProfile() = AbhaProfile(
    abhaId = abhaNumber,
    abhaAddress = abhaAddress,
    name = name,
    dateOfBirth = dateOfBirth,
    gender = gender,
    address = address,
    district = district,
    state = state,
    pincode = pincode,
    mobileNumber = mobileNumber,
    emailAddress = emailAddress,
    photoUrlMock = photoUrl,
    kycVerified = kycVerified,
    createdAt = verifiedAt,
)
