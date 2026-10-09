package com.example.samdapp.data.vitalssource

import com.example.samdapp.domain.model.VitalsReading
import com.example.samdapp.domain.vitalssource.AcquisitionRequest
import com.example.samdapp.domain.vitalssource.AcquisitionResult
import com.example.samdapp.domain.vitalssource.AcquisitionTransport
import com.example.samdapp.domain.vitalssource.RejectReason
import com.example.samdapp.domain.vitalssource.VitalsSource
import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.stream.MalformedJsonException
import retrofit2.Response
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException

/**
 * The dev flavour's [VitalsSource] for a Wi-Fi hub: a Raspberry Pi instrument gateway on the LAN.
 *
 * [readVitals] returns an empty [VitalsReading] and makes ZERO network calls. That is load
 * bearing, not laziness. Prefill runs on screen open, so a network read there would mean the app
 * ingesting instrument data with nobody having asked for it. Acquisition happens only when a
 * worker presses Start, which is half of the argument that the gateway is an accessory outside the
 * device boundary. There is a test that asserts the request count is zero.
 *
 * [startAcquisition] runs start, then measurement, then [PiMeasurementMapper], whose reject-first
 * order is where the correlation and quality controls actually live. This class adds only the
 * transport-level rejections the mapper cannot see.
 *
 * [expectedHubId] is the hub the assignment names for Wi-Fi. It is checked three times: on the
 * `X-SaMDPi-Hub-Id` header of every response by [HubIdInterceptor] (errors included), on the body
 * `hub_id` of every answer read here, and, through the same rule, on error bodies. A mismatch is
 * [RejectReason.HUB_MISMATCH]. Every result carries [AcquisitionTransport.WIFI] and that hub id.
 */
class PiGatewayVitalsSource(
    private val api: PiGatewayApi,
    private val expectedHubId: String,
) : VitalsSource {

    private val gson = Gson()

    /** Held so [stopAcquisition] has a session to close. Deliberately NOT the value RC-1 compares
     *  against: correlation uses the session id this acquisition itself started, kept on the
     *  stack, so nothing that mutates this field can loosen the check. */
    @Volatile
    var activeSessionId: String? = null
        private set

    override suspend fun readVitals(): VitalsReading = VitalsReading()

    override suspend fun startAcquisition(request: AcquisitionRequest): AcquisitionResult =
        acquire(request).stamped()

    private suspend fun acquire(request: AcquisitionRequest): AcquisitionResult {
        val startResponse = try {
            api.startSession(
                SessionStartRequestDto(
                    instrument = request.instrument.name,
                    scenario = request.scenario.name,
                ),
            )
        } catch (e: Exception) {
            return AcquisitionResult.Rejected(e.toRejectReason() ?: throw e)
        }
        if (!startResponse.isSuccessful) return AcquisitionResult.Rejected(startResponse.errorReason())
        val start = startResponse.body() ?: return AcquisitionResult.Rejected(RejectReason.MALFORMED)
        if (start.hubId != expectedHubId) return AcquisitionResult.Rejected(RejectReason.HUB_MISMATCH)

        val sessionId = start.sessionId
        // No session id means nothing to correlate against, and correlating against a blank would
        // admit any payload that also omits it. Reject rather than acquire uncorrelated.
        if (sessionId.isNullOrBlank()) {
            return AcquisitionResult.Rejected(RejectReason.MALFORMED)
        }
        activeSessionId = sessionId

        val measurementResponse = try {
            api.getMeasurement()
        } catch (e: Exception) {
            return AcquisitionResult.Rejected(e.toRejectReason() ?: throw e)
        }

        // 404 NO_MEASUREMENT_AVAILABLE is a normal answer from a gateway that has nothing to give,
        // not a transport failure, and it must not become an all-zero reading. errorReason() tells
        // it apart from every other 404 by the error code in the body.
        if (!measurementResponse.isSuccessful) {
            return AcquisitionResult.Rejected(measurementResponse.errorReason())
        }
        val measurement = measurementResponse.body()
            ?: return AcquisitionResult.Rejected(RejectReason.MALFORMED)
        // Before the mapper: a reading from the wrong hub is not judged on its quality or its
        // session id, it is refused.
        if (measurement.hubId != expectedHubId) return AcquisitionResult.Rejected(RejectReason.HUB_MISMATCH)

        val mapped = PiMeasurementMapper.map(measurement, request.instrument, sessionId)
        return if (mapped is AcquisitionResult.Accepted) {
            mapped.copy(emulatorBuild = measurement.emulatorBuild?.label())
        } else {
            mapped
        }
    }

    /** Best effort by design: with `reading_count: 1` the session is already finished on the far
     *  side once a reading has been emitted, so a failed stop is not something the worker can act
     *  on. Cancellation still propagates, since a cancelled screen exit is not a stop failure. */
    override suspend fun stopAcquisition() {
        try {
            api.stopSession()
        } catch (e: Exception) {
            if (e.toRejectReason() == null) throw e
        } finally {
            activeSessionId = null
        }
    }

    private fun AcquisitionResult.stamped(): AcquisitionResult = when (this) {
        is AcquisitionResult.Accepted -> copy(hubId = expectedHubId, transport = AcquisitionTransport.WIFI)
        is AcquisitionResult.Rejected -> copy(hubId = expectedHubId, transport = AcquisitionTransport.WIFI)
    }

    /**
     * Turns a non-2xx answer into a reason from its `{error, hub_id}` body. A body that cannot be
     * read is MALFORMED; a body that does not name the expected hub is HUB_MISMATCH, whatever else
     * it says. Only two (status, error) pairs are anything but MALFORMED:
     *
     * - 404 `NO_MEASUREMENT_AVAILABLE`: [RejectReason.NO_MEASUREMENT].
     * - 400 `INSTRUMENT_NOT_SERVED`: [RejectReason.HUB_MISMATCH], the hub does not serve this
     *   instrument, so this is not the hub the assignment meant.
     *
     * Every other pair is a client or contract fault, including `HTTP_ERROR` (the hub could not
     * parse what the phone sent), 404 `NOT_FOUND` (a wrong path) and an unknown error code.
     */
    private fun Response<*>.errorReason(): RejectReason {
        val text = try {
            errorBody()?.string()
        } catch (_: IOException) {
            null
        }
        val body = try {
            gson.fromJson(text, GatewayErrorBodyDto::class.java)
        } catch (_: JsonParseException) {
            null
        } ?: return RejectReason.MALFORMED
        if (body.hubId != expectedHubId) return RejectReason.HUB_MISMATCH
        return when {
            code() == HttpURLConnection.HTTP_NOT_FOUND && body.error == "NO_MEASUREMENT_AVAILABLE" ->
                RejectReason.NO_MEASUREMENT
            code() == HttpURLConnection.HTTP_BAD_REQUEST && body.error == "INSTRUMENT_NOT_SERVED" ->
                RejectReason.HUB_MISMATCH
            else -> RejectReason.MALFORMED
        }
    }

    /** null means "not a transport failure this seam knows how to describe", and the caller
     *  rethrows rather than inventing a reason. [HubIdMismatchException], [SocketTimeoutException]
     *  and Gson's [MalformedJsonException] (a body that is not JSON) are [IOException]s, so they
     *  have to be matched first or every mismatch, timeout and unreadable body would report as
     *  unreachable. [JsonParseException] covers Gson's syntax and I/O parse failures, which is
     *  also what a truncated body raises. Note that `CancellationException`
     *  matches none of these and so is always rethrown. */
    private fun Throwable.toRejectReason(): RejectReason? = when (this) {
        is HubIdMismatchException -> RejectReason.HUB_MISMATCH
        is JsonParseException, is MalformedJsonException -> RejectReason.MALFORMED
        is SocketTimeoutException -> RejectReason.TIMEOUT
        is IOException -> RejectReason.UNREACHABLE
        else -> null
    }
}
