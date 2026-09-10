package com.example.samdapp.data.vitalssource

import com.example.samdapp.domain.model.VitalsReading
import com.example.samdapp.domain.vitalssource.AcquisitionRequest
import com.example.samdapp.domain.vitalssource.AcquisitionResult
import com.example.samdapp.domain.vitalssource.RejectReason
import com.example.samdapp.domain.vitalssource.VitalsSource
import com.google.gson.JsonParseException
import retrofit2.HttpException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import javax.inject.Inject

/**
 * The dev flavour's [VitalsSource]: a Raspberry Pi instrument gateway on the LAN.
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
 */
class PiGatewayVitalsSource @Inject constructor(
    private val api: PiGatewayApi,
) : VitalsSource {

    /** Held so [stopAcquisition] has a session to close. Deliberately NOT the value RC-1 compares
     *  against: correlation uses the session id this acquisition itself started, kept on the
     *  stack, so nothing that mutates this field can loosen the check. */
    @Volatile
    var activeSessionId: String? = null
        private set

    override suspend fun readVitals(): VitalsReading = VitalsReading()

    override suspend fun startAcquisition(request: AcquisitionRequest): AcquisitionResult {
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

        val sessionId = startResponse.sessionId
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
        // not a transport failure, and it must not become an all-zero reading.
        if (measurementResponse.code() == HttpURLConnection.HTTP_NOT_FOUND) {
            return AcquisitionResult.Rejected(RejectReason.NO_MEASUREMENT)
        }
        val measurement = measurementResponse.body()
            ?: return AcquisitionResult.Rejected(RejectReason.MALFORMED)

        return PiMeasurementMapper.map(measurement, request.instrument, sessionId)
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

    /** null means "not a transport failure this seam knows how to describe", and the caller
     *  rethrows rather than inventing a reason. [SocketTimeoutException] is an [IOException], so it
     *  has to be matched first or every timeout would report as unreachable. Note that
     *  `CancellationException` matches none of these and so is always rethrown. */
    private fun Throwable.toRejectReason(): RejectReason? = when (this) {
        is SocketTimeoutException -> RejectReason.TIMEOUT
        is IOException -> RejectReason.UNREACHABLE
        is HttpException -> RejectReason.MALFORMED
        is JsonParseException -> RejectReason.MALFORMED
        else -> null
    }
}
