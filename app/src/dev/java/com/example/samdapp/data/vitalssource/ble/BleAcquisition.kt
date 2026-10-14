package com.example.samdapp.data.vitalssource.ble

import com.example.samdapp.data.vitalssource.NormalizedMeasurementDto
import com.example.samdapp.data.vitalssource.PiMeasurementMapper
import com.example.samdapp.domain.vitalssource.AcquisitionRequest
import com.example.samdapp.domain.vitalssource.AcquisitionResult
import com.example.samdapp.domain.vitalssource.Instrument
import com.example.samdapp.domain.vitalssource.RejectReason
import java.time.Instant
import java.util.UUID
import java.util.logging.Logger
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One BLE acquisition over a [HubLink]: addendum 5.2 as amended by section 15. Pure Kotlin, no
 * Android, so every step and every failure path is a JVM test.
 *
 * Steps 2 to 14 run inside one 25 s cap (parent memo 7.4). Step 15, the teardown, always runs, under
 * [NonCancellable], whether the exchange returned, failed, hit the cap or was cancelled by Stop or
 * screen exit. On the cap the link is aborted, and an aborted link takes nothing but [HubLink.close]:
 * no STOP and no disconnect, since neither could reach the hub, which discards the session when its
 * owner drops anyway (A13). There is no reconnect and no retry: one connect per Start (parent memo 7.5).
 *
 * The result is unstamped; the router adds the transport and the hub.
 */
object BleAcquisition {
    const val CAP_MS = 25_000L
    const val CONNECT_TIMEOUT_MS = 10_000L
    const val STEP_TIMEOUT_MS = 3_000L
    const val ENVELOPE_TIMEOUT_MS = 10_000L
    const val TEARDOWN_TIMEOUT_MS = 2_000L

    /** The largest envelope is 86 bytes and an indication carries MTU - 3 (parent memo 5.3). */
    const val MIN_MTU = 89

    /** [disSerialMismatch] is true only when the DIS serial named another hub: the one case in which
     *  the caller drops the stored association, so the next Start asks the human again (Q10). */
    data class Outcome(val result: AcquisitionResult, val disSerialMismatch: Boolean = false)

    suspend fun <R> run(
        link: HubLink<R>,
        request: AcquisitionRequest,
        hubId: String,
        nonce: UUID = UUID.randomUUID(),
        now: () -> Instant = Instant::now,
    ): Outcome {
        // Step 1. The expected session id is the JDK's own canonical form of the nonce, lowercase
        // (B7). The echo is formatted separately, by ControlPoint.formatNonce, so a fault in either
        // formatter is a mismatch rather than two equal wrong strings.
        val nonceBytes = ControlPoint.nonceBytes(nonce)
        val expectedSessionId = nonce.toString()
        val progress = Progress()
        try {
            return withTimeout(CAP_MS) { exchange(link, request, hubId, nonceBytes, expectedSessionId, now, progress) }
        } catch (_: TimeoutCancellationException) {
            progress.aborted = true
            return Outcome(rejected(RejectReason.TIMEOUT))
        } catch (e: HubLinkException) {
            return Outcome(rejected(reasonFor(e.failure, progress.connected)))
        } finally {
            // Step 15. STOP for a START the hub may have opened, that is unless none was submitted or
            // the hub refused it, then disconnect, then close, each bounded. After the cap, abort, then
            // only close. Under NonCancellable, so a cancelled caller still reaches the hub. A link
            // already down takes neither STOP nor disconnect: the hub discards a session when its owner
            // drops (A13), and the library has closed its side.
            withContext(NonCancellable) {
                if (progress.aborted) {
                    teardownStep("abort") { link.abort() }
                } else {
                    if (progress.mayHoldSession() && linkReportsUp(link)) {
                        teardownStep("STOP") {
                            withTimeoutOrNull(TEARDOWN_TIMEOUT_MS) {
                                link.writeControlPoint(ControlPoint.encodeStop(nonceBytes), TEARDOWN_TIMEOUT_MS)
                            } ?: logger.warning("BLE teardown STOP timed out")
                        }
                    }
                    if (linkReportsUp(link)) {
                        teardownStep("disconnect") {
                            withTimeoutOrNull(TEARDOWN_TIMEOUT_MS) { link.disconnect(TEARDOWN_TIMEOUT_MS) }
                                ?: logger.warning("BLE teardown disconnect timed out")
                        }
                    }
                }
                teardownStep("close") { link.close() }
            }
            // Teardown never changes a decided result. Only the caller's own cancellation replaces it.
            currentCoroutineContext().ensureActive()
        }
    }

    /** Where START stands. IN_FLIGHT from the moment the write is handed to the link until a decoded
     *  answer: the hub may have opened a session the phone never heard about, so teardown stops it. A
     *  STOP with no session is answered NOT_RUNNING and changes nothing (SaMDPi control_point.py). */
    private enum class StartState { NOT_SUBMITTED, IN_FLIGHT, SUCCEEDED, REFUSED }

    private class Progress {
        var connected = false
        var start = StartState.NOT_SUBMITTED
        var aborted = false

        fun mayHoldSession() = start == StartState.SUCCEEDED || start == StartState.IN_FLIGHT
    }

    private suspend fun <R> exchange(
        link: HubLink<R>,
        request: AcquisitionRequest,
        hubId: String,
        nonceBytes: ByteArray,
        expectedSessionId: String,
        now: () -> Instant,
        progress: Progress,
    ): Outcome {
        // Steps 2 to 4. The plan runs as this connection's init, so the CCCDs and the MTU request
        // precede any Control Point write, on every connection (14.5 items 1 and 6). Step 3, the
        // required services, is checked by the adapter during connect: NOT_SUPPORTED, UNREACHABLE.
        val failedInit = link.connect(CONNECT_TIMEOUT_MS, HubInitPlan.steps())
        progress.connected = true
        // A failed init step is a link that could not be set up, not bytes the hub sent: UNREACHABLE
        // (2026-10-14 ruling after review; was MALFORMED, whose copy was untrue here).
        if (failedInit.isNotEmpty()) return Outcome(rejected(RejectReason.UNREACHABLE))

        // Step 5.
        if (link.mtu() < MIN_MTU) return Outcome(rejected(RejectReason.MALFORMED))

        // Step 6. Exactly one serial across every Device Information service (14.5 item 2), equal to
        // the assigned hub_id byte for byte.
        val dis = DisSearch.pick(link.disServices()) as? DisSearch.Result.Found
            ?: return Outcome(rejected(RejectReason.HUB_MISMATCH))
        val serial = link.read(dis.serial, STEP_TIMEOUT_MS)
        if (!serial.contentEquals(hubId.toByteArray(Charsets.US_ASCII))) {
            return Outcome(rejected(RejectReason.HUB_MISMATCH), disSerialMismatch = true)
        }
        val emulatorBuild = dis.softwareRevision?.let { String(link.read(it, STEP_TIMEOUT_MS), Charsets.UTF_8) }

        // Step 7. Anything that arrived before START belongs to no session of ours.
        while (link.events.tryReceive().isSuccess) Unit

        // Step 8.
        progress.start = StartState.IN_FLIGHT
        link.writeControlPoint(ControlPoint.encodeStart(request.instrument, request.scenario, nonceBytes), STEP_TIMEOUT_MS)

        // Step 9. The first Control Point indication is the answer. An envelope before it is stale
        // or replayed and is discarded (G-B3).
        val answer = withTimeoutOrNull(STEP_TIMEOUT_MS) { link.events.nextControlPoint() }
            ?: return Outcome(rejected(RejectReason.TIMEOUT))
        when (val response = ControlPoint.decodeStartResponse(answer)) {
            is StartResponse.Refused -> {
                if (ControlPoint.isStartAnswer(answer)) progress.start = StartState.REFUSED
                return Outcome(rejected(response.reason))
            }
            StartResponse.Success -> progress.start = StartState.SUCCEEDED
        }

        // Step 10. The first envelope after the answer.
        val raw = withTimeoutOrNull(ENVELOPE_TIMEOUT_MS) { link.events.nextEnvelope() }
            ?: return Outcome(rejected(RejectReason.TIMEOUT))

        // Step 11. The phone's receive time is the authoritative one (parent memo 3.2).
        val receivedAt = now()
        val envelope = when (val parse = HubEnvelope.parse(raw)) {
            is EnvelopeParse.Parsed -> parse.envelope
            is EnvelopeParse.Rejected -> return Outcome(rejected(RejectReason.MALFORMED))
        }

        // Step 12. The association is kept: only a DIS serial mismatch drops it (Q10).
        if (envelope.hubId != hubId) return Outcome(rejected(RejectReason.HUB_MISMATCH))

        // Step 13. Decoded as the characteristic of the instrument the envelope names, so a reading of
        // another instrument reaches the mapper's RC-1 device-type gate, the one both transports share.
        // A special value in any present field is a fault reading, whatever the quality byte claims (G-B4).
        val sig = try {
            decode(envelope.instrument, envelope.sig)
        } catch (_: SigFormatException) {
            null
        } ?: return Outcome(rejected(RejectReason.MALFORMED))
        if (sig.hasSpecialValue()) return Outcome(rejected(RejectReason.QUALITY_STATUS_NOT_OK))

        // Step 14. The one mapper both transports share: RC-2, then RC-1 session, then RC-1 device type.
        val mapped = PiMeasurementMapper.map(toDto(envelope, sig, receivedAt), request.instrument, expectedSessionId)
        return Outcome(if (mapped is AcquisitionResult.Accepted) mapped.copy(emulatorBuild = emulatorBuild) else mapped)
    }

    /** Addendum 5.4. A request timeout during connect is an unreachable hub; later it is TIMEOUT. The BLE
     *  source maps a link failure raised before any connect through this too, with [connected] false. */
    internal fun reasonFor(failure: HubLinkException.Failure, connected: Boolean): RejectReason = when (failure) {
        HubLinkException.Failure.BLUETOOTH_DISABLED -> RejectReason.BLUETOOTH_UNAVAILABLE
        HubLinkException.Failure.DISCONNECTED -> RejectReason.UNREACHABLE
        // The link was opened only through this hub's stored association, so a missing service is a hub
        // not serving right now, as when its GATT application stops mid-discovery (live check L5).
        // Identity failures stay HUB_MISMATCH: the DIS serial, the envelope hub_id, INSTRUMENT_NOT_SERVED.
        HubLinkException.Failure.NOT_SUPPORTED -> RejectReason.UNREACHABLE
        HubLinkException.Failure.TIMED_OUT -> if (connected) RejectReason.TIMEOUT else RejectReason.UNREACHABLE
        HubLinkException.Failure.FAILED -> RejectReason.UNREACHABLE
        HubLinkException.Failure.INVALID_REQUEST -> RejectReason.MALFORMED
        HubLinkException.Failure.PERMISSION_REVOKED -> RejectReason.BLUETOOTH_UNAVAILABLE
    }

    /** Null for an instrument with no SIG decoder: MALFORMED, since no BLE hub sends one. */
    private fun decode(instrument: Instrument, bytes: ByteArray): SigMeasurement? = when (instrument) {
        Instrument.BP -> SigDecoders.bloodPressure(bytes)
        Instrument.SPO2 -> SigDecoders.pulseOximeterSpotCheck(bytes)
        Instrument.THERMOMETER -> SigDecoders.temperature(bytes)
        Instrument.GLUCOMETER, Instrument.WEIGHT_SCALE, Instrument.HEART_RATE -> null
    }

    /** Addendum 5.5. Only finite values reach here: step 13 refused every special value. */
    private fun toDto(envelope: HubEnvelope, sig: SigMeasurement, receivedAt: Instant): NormalizedMeasurementDto {
        val (primary, secondary, tertiary) = when (sig) {
            is BloodPressureMeasurement -> Triple(sig.systolic.number(), sig.diastolic.number(), sig.pulse.number())
            is PulseOximeterSpotCheck -> Triple(sig.spo2.number(), sig.pulse.number(), null)
            is TemperatureMeasurement -> Triple(sig.celsius.number(), null, null)
        }
        return NormalizedMeasurementDto(
            hubId = envelope.hubId,
            profileVersion = envelope.profileVersion,
            envelopeVersion = 1,
            deviceType = PiMeasurementMapper.expectedDeviceType(envelope.instrument),
            qualityStatus = envelope.quality.name,
            primaryValue = primary,
            secondaryValue = secondary,
            tertiaryValue = tertiary,
            measuredAt = Instant.ofEpochMilli(envelope.deviceTimeMs).toString(),
            receivedAt = receivedAt.toString(),
            synthetic = true,
            provenance = mapOf("session_id" to ControlPoint.formatNonce(envelope.nonce)),
        )
    }

    private fun SigValue?.number(): Double? = (this as? SigValue.Finite)?.value

    private fun rejected(reason: RejectReason) = AcquisitionResult.Rejected(reason)

    private suspend fun ReceiveChannel<HubEvent>.nextControlPoint(): ByteArray {
        while (true) {
            when (val event = next()) {
                is HubEvent.ControlPointIndication -> return event.value
                is HubEvent.EnvelopeIndication -> Unit
            }
        }
    }

    private suspend fun ReceiveChannel<HubEvent>.nextEnvelope(): ByteArray {
        while (true) {
            when (val event = next()) {
                is HubEvent.EnvelopeIndication -> return event.value
                is HubEvent.ControlPointIndication -> Unit
            }
        }
    }

    /** A channel closed by a dropped link carries a [HubLinkException]; one closed without a cause is
     *  treated the same way, as a disconnect, rather than escaping as a channel exception. */
    private suspend fun ReceiveChannel<HubEvent>.next(): HubEvent {
        val received = receiveCatching()
        received.getOrNull()?.let { return it }
        val cause = received.exceptionOrNull()
        throw cause as? HubLinkException
            ?: HubLinkException(HubLinkException.Failure.DISCONNECTED, "the indication channel closed", cause)
    }

    private val logger = Logger.getLogger("BleAcquisition")

    /** Teardown asks the link whether it is up, and a link that cannot say is treated as down: no STOP
     *  and no disconnect, and close still runs. */
    private fun linkReportsUp(link: HubLink<*>): Boolean = runCatching { link.isConnected() }.getOrDefault(false)

    /** Teardown is best effort: the result is already decided, so no failure here may replace it, a
     *  cancellation a step raises included. An Error still propagates. The WARN names the link failure
     *  or the exception class, never a library message. */
    private inline fun teardownStep(step: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            logger.warning("BLE teardown $step failed: ${(e as? HubLinkException)?.failure?.name ?: e.javaClass.name}")
        }
    }
}
