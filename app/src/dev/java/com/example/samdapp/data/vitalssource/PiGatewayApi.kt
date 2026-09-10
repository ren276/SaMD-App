package com.example.samdapp.data.vitalssource

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * The single-instrument wire contract of the Raspberry Pi instrument gateway, dev flavour only.
 * Plain HTTP on the LAN (the dev manifest already sets `usesCleartextTraffic`), Gson bodies, no
 * authentication of any kind. See [com.example.samdapp.di.PiGatewayNetworkModule] for why this
 * stack must never share the backend's `OkHttpClient`.
 *
 * The composite session endpoints are deliberately not modelled here: they are outside the
 * contracted interface and are never called.
 */
interface PiGatewayApi {

    /** `reading_count: 1` is what makes the gateway emit one reading and complete the session, so
     *  no timer is left running on the far side. */
    @POST("api/v1/session/start")
    suspend fun startSession(@Body request: SessionStartRequestDto): SessionStartResponseDto

    /** 200 with a measurement, or 404 `NO_MEASUREMENT_AVAILABLE`, which is a normal answer rather
     *  than an error, hence [Response] instead of a bare body. */
    @GET("api/v1/measurement")
    suspend fun getMeasurement(): Response<NormalizedMeasurementDto>

    @POST("api/v1/session/stop")
    suspend fun stopSession(): SessionStopResponseDto
}

data class SessionStartRequestDto(
    val instrument: String,
    val scenario: String,
    val transport: String = "WIFI",
    @SerializedName("reading_count") val readingCount: Int = 1,
    @SerializedName("interval_seconds") val intervalSeconds: Double = 2.0,
)

data class SessionStartResponseDto(
    val status: String? = null,
    @SerializedName("session_id") val sessionId: String? = null,
    val instrument: String? = null,
    @SerializedName("reading_count") val readingCount: Int? = null,
    @SerializedName("interval_seconds") val intervalSeconds: Double? = null,
)

/**
 * Mirrors the gateway's `NormalizedMeasurement`. Note the nesting, because both correlation
 * checks depend on it: [deviceType] and [qualityStatus] are TOP LEVEL fields, while the session id
 * lives only inside [provenance] under the key `session_id`. There is no top-level session id and
 * a missing `provenance` entry is a mismatch, never a pass.
 *
 * [provenance] is typed loosely because it is a free-form map on the wire; the mapper reads the one
 * key it needs and treats any non-string value there as absent.
 */
data class NormalizedMeasurementDto(
    @SerializedName("device_type") val deviceType: String? = null,
    @SerializedName("quality_status") val qualityStatus: String? = null,
    @SerializedName("primary_value") val primaryValue: Double? = null,
    @SerializedName("secondary_value") val secondaryValue: Double? = null,
    @SerializedName("tertiary_value") val tertiaryValue: Double? = null,
    @SerializedName("measured_at") val measuredAt: String? = null,
    @SerializedName("received_at") val receivedAt: String? = null,
    val unit: String? = null,
    val synthetic: Boolean? = null,
    val provenance: Map<String, Any?>? = null,
)

data class SessionStopResponseDto(
    val status: String? = null,
    @SerializedName("readings_emitted") val readingsEmitted: Int? = null,
)
