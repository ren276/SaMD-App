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
    suspend fun startSession(@Body request: SessionStartRequestDto): Response<SessionStartResponseDto>

    /** 200 with a measurement, or 404 `NO_MEASUREMENT_AVAILABLE`, which is a normal answer rather
     *  than an error, hence [Response] instead of a bare body. All three calls return [Response]:
     *  a non-2xx answer carries an `{error, hub_id}` body that [PiGatewayVitalsSource] reads, and a
     *  bare body would turn it into an exception that has already thrown that body away. */
    @GET("api/v1/measurement")
    suspend fun getMeasurement(): Response<NormalizedMeasurementDto>

    @POST("api/v1/session/stop")
    suspend fun stopSession(): Response<SessionStopResponseDto>
}

data class SessionStartRequestDto(
    val instrument: String,
    val scenario: String,
    val transport: String = "WIFI",
    @SerializedName("reading_count") val readingCount: Int = 1,
    @SerializedName("interval_seconds") val intervalSeconds: Double = 2.0,
)

data class SessionStartResponseDto(
    @SerializedName("hub_id") val hubId: String? = null,
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
    @SerializedName("hub_id") val hubId: String? = null,
    @SerializedName("device_id") val deviceId: String? = null,
    @SerializedName("profile_version") val profileVersion: Int? = null,
    @SerializedName("envelope_version") val envelopeVersion: Int? = null,
    @SerializedName("emulator_build") val emulatorBuild: EmulatorBuildDto? = null,
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

/** The emulator's build stamp (SaMDPi `hubctx`): the commit, whether the tree was dirty, and
 *  whether the commit was merged. Decoded and audited, never a gate. */
data class EmulatorBuildDto(
    val sha: String? = null,
    val dirty: Boolean? = null,
    val merged: Boolean? = null,
) {
    /**
     * The hub's own label for this build, the one DIS Software Revision and the hub journal show:
     * the sha, then `-dirty` if the tree was dirty, then `-unmerged` if the commit was not merged
     * (SaMDPi `hubctx.py`), for example `bba2c6b1b` or `94c997ddf-unmerged`. Null when no sha was
     * reported. A flag the hub did not send is not read as clean or merged: it adds its suffix.
     */
    fun label(): String? = sha?.takeIf { it.isNotBlank() }?.let {
        it + (if (dirty != false) "-dirty" else "") + (if (merged != true) "-unmerged" else "")
    }
}

/** The `{error, hub_id, ...}` body every non-2xx answer carries. */
data class GatewayErrorBodyDto(
    val error: String? = null,
    @SerializedName("hub_id") val hubId: String? = null,
)

data class SessionStopResponseDto(
    @SerializedName("hub_id") val hubId: String? = null,
    val status: String? = null,
    @SerializedName("readings_emitted") val readingsEmitted: Int? = null,
)
