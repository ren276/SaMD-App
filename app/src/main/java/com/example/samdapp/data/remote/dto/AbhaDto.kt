package com.example.samdapp.data.remote.dto

import com.google.gson.annotations.SerializedName
import java.time.Instant
import java.time.LocalDate

/** Wire shapes for `POST/GET /api/v1/abha/registration-sessions*` (api-contract.md §8). Request
 *  bodies matched directly against `backend/abdm-adapter/abdm_adapter/schemas.py`'s
 *  `IdentitySubmit`/`OtpVerify`/`MobileOtpVerify` (all `StrictModel`, `extra="forbid"` — an
 *  unexpected or missing field is a 422, not a silent pass-through), not guessed from the table's
 *  Purpose column. Not yet exercised by any real request (see
 *  [com.example.samdapp.domain.abha.AbdmAbhaSource]'s KDoc for why). */

data class AbhaIdentityRequestDto(
    @SerializedName("aadhaar_number") val aadhaarNumber: String,
)

/** `OtpVerify` requires both fields: `mobile_number` is the account's communication mobile, sent
 *  in the clear in this request per `abdm_adapter/schemas.py`'s own note (never RSA-encrypted
 *  like the Aadhaar/OTP values, still never logged — `REDACTED_KEYS` covers `mobile_number`).
 *  Distinct from [AbhaMobileOtpRequestDto], which needs only the OTP itself. */
data class AbhaOtpRequestDto(
    val otp: String,
    @SerializedName("mobile_number") val mobileNumber: String,
)

data class AbhaMobileOtpRequestDto(
    val otp: String,
)

data class AbhaRegistrationSessionDto(
    @SerializedName("session_id") val sessionId: String,
    val state: String,
    @SerializedName("expires_at") val expiresAt: Instant?,
)

data class AbhaIdentitySubmitResponseDto(
    @SerializedName("session_id") val sessionId: String,
    val state: String,
    @SerializedName("masked_mobile") val maskedMobile: String?,
)

data class AbhaOtpResponseDto(
    @SerializedName("session_id") val sessionId: String,
    val state: String,
)

data class AbhaSessionStateResponseDto(
    @SerializedName("session_id") val sessionId: String,
    val state: String,
    @SerializedName("last_error") val lastError: String?,
)

/** The final verified identity — field-for-field aligned with [com.example.samdapp.data.local.entity.AbhaProfileEntity]
 *  and `docs/requirements/abha-field-mapping.md`, per api-contract.md §8's own framing. */
data class AbhaIdentityDto(
    @SerializedName("abha_number") val abhaNumber: String,
    @SerializedName("abha_address") val abhaAddress: String?,
    val name: String,
    @SerializedName("date_of_birth") val dateOfBirth: LocalDate?,
    val gender: String,
    val address: String?,
    val district: String?,
    val state: String?,
    val pincode: String?,
    @SerializedName("mobile_number") val mobileNumber: String?,
    @SerializedName("email_address") val emailAddress: String?,
    @SerializedName("photo_url") val photoUrl: String?,
    @SerializedName("kyc_verified") val kycVerified: Boolean,
    @SerializedName("verification_source") val verificationSource: String,
    @SerializedName("verified_at") val verifiedAt: Instant,
)
