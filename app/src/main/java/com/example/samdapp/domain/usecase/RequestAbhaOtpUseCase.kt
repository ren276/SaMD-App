package com.example.samdapp.domain.usecase

import com.example.samdapp.domain.abha.AbdmAbhaSource
import com.example.samdapp.domain.abha.AbhaEnrolResult
import com.example.samdapp.domain.abha.flatMap
import com.example.samdapp.domain.abha.map
import com.example.samdapp.domain.abha.toEnrolResult
import javax.inject.Inject

/** What the Aadhaar step hands to the OTP step. [sessionId] is the backend's local transaction id
 *  and the only cross-step handle in this flow: no ABDM `txnId` is ever exposed to this client
 *  (it lives inside `backend/abdm-adapter`'s own `AbhaTransaction` row), so there is nothing here
 *  to redact. [maskedMobile] is masked at source by ABDM (`XXXXXX3210`) and is display-only, for
 *  telling the worker which phone to expect the OTP on. */
data class AbhaOtpRequested(
    val sessionId: String,
    val maskedMobile: String?,
)

/**
 * Step 1 of the real ABHA create flow: start a registration session, then submit the Aadhaar
 * number so the backend can encrypt it and ask ABDM for an OTP.
 *
 * The Aadhaar number is a parameter and nothing more. It is not stored in any field of this class,
 * not written to any Room entity (`AbhaProfileEntity` has no column for it), and never reaches
 * [com.example.samdapp.domain.audit.AuditLogger]. All crypto is the backend's: this client sends
 * the plain 12 digits over TLS to `backend/core` and the adapter does the RSA-OAEP work, per the
 * "Android never does crypto, never talks to ABDM directly" rule.
 *
 * Distinct from the still-mock [CreateAbhaProfileUseCase], which is untouched: that one backs the
 * old no-Aadhaar demo screen, this one backs the real Aadhaar-OTP flow.
 */
class RequestAbhaOtpUseCase @Inject constructor(
    private val abdmAbhaSource: AbdmAbhaSource,
) {
    suspend operator fun invoke(
        aadhaarNumber: String,
        consentGiven: Boolean,
    ): AbhaEnrolResult<AbhaOtpRequested> {
        // The consent gate lives here, not only on the button's enabled state. A disabled button
        // is a UI affordance; this is the check that makes "no Aadhaar leaves the device without
        // recorded consent" true for every caller, including a future one that forgets.
        if (!consentGiven) {
            return AbhaEnrolResult.Error(
                message = "Record the patient's consent before sending their Aadhaar number.",
                retryable = false,
            )
        }
        if (aadhaarNumber.length != 12 || !aadhaarNumber.all(Char::isDigit)) {
            return AbhaEnrolResult.Error(message = "Aadhaar number must be 12 digits.", retryable = false)
        }

        return abdmAbhaSource.startRegistrationSession().toEnrolResult().flatMap { started ->
            abdmAbhaSource.submitIdentity(started.sessionId, aadhaarNumber).toEnrolResult().map { submitted ->
                AbhaOtpRequested(sessionId = submitted.sessionId, maskedMobile = submitted.maskedMobile)
            }
        }
    }
}
