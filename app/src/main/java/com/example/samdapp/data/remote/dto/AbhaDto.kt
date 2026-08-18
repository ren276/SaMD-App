package com.example.samdapp.data.remote.dto

import com.google.gson.annotations.SerializedName
import java.time.Instant
import java.time.LocalDate

/** Wire shapes for `POST/GET /api/v1/abha/registration-sessions*` (api-contract.md §8). Request
 *  bodies are not pinned exactly in that doc (only the success `data` shapes are) — inferred from
 *  each endpoint's stated Purpose column, the same "Submit Aadhaar"/"Verify OTP" language the
 *  table itself uses. Not yet exercised by any real request (see
 *  [com.example.samdapp.domain.abha.AbdmAbhaSource]'s KDoc for why), so treat these request shapes
 *  as a documented best guess to verify against a real backend call before first use, not as
 *  something already round-tripped. */

data class AbhaIdentityRequestDto(
    @SerializedName("aadhaar_number") val aadhaarNumber: String,
)

data class AbhaOtpRequestDto(
    val otp: String,
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
