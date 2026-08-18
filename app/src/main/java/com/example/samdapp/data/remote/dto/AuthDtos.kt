package com.example.samdapp.data.remote.dto

import com.google.gson.annotations.SerializedName

/** api-contract.md §2.2. `role` is deliberately absent: it is a property of the account on the
 *  server, not a claim the client asserts. */
data class LoginRequestDto(
    @SerializedName("worker_id") val workerId: String,
    val pin: String,
    @SerializedName("device_id") val deviceId: String,
)

data class WorkerSummaryDto(
    @SerializedName("worker_id") val workerId: String,
    @SerializedName("display_name") val displayName: String,
    val role: String,
    @SerializedName("facility_id") val facilityId: String,
    @SerializedName("facility_name") val facilityName: String,
)

data class LoginResponseDto(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("refresh_token") val refreshToken: String,
    @SerializedName("token_type") val tokenType: String,
    @SerializedName("expires_in") val expiresIn: Long,
    @SerializedName("must_change_pin") val mustChangePin: Boolean,
    val worker: WorkerSummaryDto,
)

/** api-contract.md §2.3. */
data class RefreshRequestDto(
    @SerializedName("refresh_token") val refreshToken: String,
    @SerializedName("device_id") val deviceId: String,
)

data class TokenPairDto(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("refresh_token") val refreshToken: String,
    @SerializedName("token_type") val tokenType: String,
    @SerializedName("expires_in") val expiresIn: Long,
)

/** api-contract.md §2.4. */
data class LogoutRequestDto(
    @SerializedName("refresh_token") val refreshToken: String,
)

data class LogoutResponseDto(
    val revoked: Boolean,
)

/** api-contract.md §2.5. */
data class MeResponseDto(
    @SerializedName("worker_id") val workerId: String,
    @SerializedName("display_name") val displayName: String,
    val role: String,
    @SerializedName("facility_id") val facilityId: String,
    @SerializedName("facility_name") val facilityName: String,
    @SerializedName("must_change_pin") val mustChangePin: Boolean,
    val permissions: List<String>,
)

/** api-contract.md §2.6. */
data class ChangePinRequestDto(
    @SerializedName("current_pin") val currentPin: String,
    @SerializedName("new_pin") val newPin: String,
)

data class ChangePinResponseDto(
    @SerializedName("pin_changed") val pinChanged: Boolean,
    @SerializedName("reauthentication_required") val reauthenticationRequired: Boolean,
)
