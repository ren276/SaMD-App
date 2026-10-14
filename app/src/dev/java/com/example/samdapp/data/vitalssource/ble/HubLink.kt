package com.example.samdapp.data.vitalssource.ble

import java.util.logging.Logger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/** One indication from the hub. The adapter delivers Control Point and Envelope indications into one
 *  channel in arrival order (addendum 2.4); the protocol depends on that order. */
sealed interface HubEvent {
    class ControlPointIndication(val value: ByteArray) : HubEvent

    class EnvelopeIndication(val value: ByteArray) : HubEvent
}

/**
 * A link operation failed. The adapter translates every failure of the BLE library into this one
 * type before it reaches [BleAcquisition], so the protocol maps reasons from [failure] alone and
 * never needs a catch-all (addendum 5.4).
 */
class HubLinkException(val failure: Failure, message: String? = null, cause: Throwable? = null) :
    Exception(message ?: failure.name, cause) {

    enum class Failure {
        /** Bluetooth was turned off. */
        BLUETOOTH_DISABLED,

        /** The link dropped, or the request was failed because it dropped. */
        DISCONNECTED,

        /** The peripheral lacks the vendor service, its Control Point or Envelope, or the SIG service
         *  for the instrument: it is not the hub this acquisition is for. */
        NOT_SUPPORTED,

        /** The request's own timeout expired. */
        TIMED_OUT,

        /** Any other GATT failure status. */
        FAILED,

        /** The library refused the request as invalid. */
        INVALID_REQUEST,

        /** The Nearby devices permission was revoked mid-run (a `SecurityException`). */
        PERMISSION_REVOKED,
    }
}

/**
 * The GATT link [BleAcquisition] drives. [BleHubManager] is the only real implementation; tests use a
 * fake. Every suspending call is cancellable, and every failure is a [HubLinkException].
 *
 * [R] names a characteristic the link can read later; the protocol never looks inside it.
 */
interface HubLink<R> {
    /**
     * Connects (no auto-connect, no retry) and, on this connection, runs [plan] as the init that
     * completes before the link is ready, so before this returns. A failed init step does not fail
     * the connect (section 15, G5 item 5): the steps that failed are returned, and an empty list
     * means every step succeeded.
     */
    suspend fun connect(timeoutMs: Long, plan: List<InitStep>): List<InitStep>

    /** The ATT MTU negotiated on this connection. */
    fun mtu(): Int

    /** Every service the peer exposes, with its characteristics, for [DisSearch]. */
    fun disServices(): List<DisSearch.Service<R>>

    suspend fun read(ref: R, timeoutMs: Long): ByteArray

    /** Write with response to the Control Point. */
    suspend fun writeControlPoint(value: ByteArray, timeoutMs: Long)

    /** Indications in arrival order. Closed with a [HubLinkException] when the link drops. */
    val events: ReceiveChannel<HubEvent>

    suspend fun disconnect(timeoutMs: Long)

    /** Drops every queued request and closes the GATT client: the cap path. */
    fun abort()

    /** Releases the link. Called at the end of every acquisition; idempotent. */
    fun close()
}

/**
 * The one way an indication enters a link's [HubLink.events]. Unlimited, because the protocol bounds
 * the indications (one answer and one envelope per session), and fed only by `trySend`, so a library
 * callback never suspends or blocks. An indication after [close] is expected, since the hub can still
 * be indicating while the link is torn down, and is dropped; any other refusal closes the channel as
 * a lost link rather than losing a reading in silence.
 */
internal class IndicationSink(capacity: Int = Channel.UNLIMITED) {
    private val channel = Channel<HubEvent>(capacity)

    val events: ReceiveChannel<HubEvent> get() = channel

    fun offer(event: HubEvent) {
        val sent = channel.trySend(event)
        if (sent.isClosed) {
            logger.fine("an indication after close was dropped")
        } else if (sent.isFailure) {
            channel.close(HubLinkException(HubLinkException.Failure.DISCONNECTED, "the indication channel refused an event"))
        }
    }

    fun close(cause: HubLinkException) {
        channel.close(cause)
    }

    private companion object {
        val logger: Logger = Logger.getLogger("IndicationSink")
    }
}
