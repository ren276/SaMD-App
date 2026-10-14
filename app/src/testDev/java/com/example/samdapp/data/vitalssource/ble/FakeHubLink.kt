package com.example.samdapp.data.vitalssource.ble

import java.util.UUID
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** The hub's Device Information service, BlueZ's own, and the vendor service, as a central sees them. */
fun hubServices(): List<DisSearch.Service<String>> = listOf(
    DisSearch.Service(SigUuids.DEVICE_INFORMATION_SERVICE, listOf(DisSearch.Characteristic(sig(0x2A50), "bluez-pnp"))),
    DisSearch.Service(
        SigUuids.DEVICE_INFORMATION_SERVICE,
        listOf(
            DisSearch.Characteristic(sig(0x2A29), "hub-manufacturer"),
            DisSearch.Characteristic(SigUuids.SERIAL_NUMBER, "hub-serial"),
            DisSearch.Characteristic(SigUuids.SOFTWARE_REVISION, "hub-software"),
        ),
    ),
    DisSearch.Service(ControlPoint.VENDOR_SERVICE, emptyList()),
)

private fun sig(short: Int): UUID = UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(short))

fun InitStep.label(): String = when (this) {
    is InitStep.RequestMtu -> "mtu($mtu)"
    is InitStep.EnableIndications -> "enable($target)"
}

/**
 * A scripted hub behind the [HubLink] seam. One instance is one connection, as one `BleHubManager` is
 * one acquisition. [log] records every call in order; the init plan is logged as it runs inside
 * [connect], exactly where the real adapter runs it.
 *
 * By default it behaves like kernelhub2 on the happy path: on START it answers SUCCESS and indicates
 * one envelope echoing the START nonce.
 *
 * Like a real GATT client, it is dead once [abort] or [close] ran: every later operation is logged
 * and then throws [HubLinkException.Failure.DISCONNECTED]. Only [close] may be repeated. Like the
 * adapter's cancellable awaits, a suspending call made by a cancelled caller never reaches the hub:
 * it throws before it is logged.
 */
class FakeHubLink(
    hubId: String = "kernelhub2",
    var mtu: Int = 517,
    var services: List<DisSearch.Service<String>> = hubServices(),
    var initFailures: List<InitStep> = emptyList(),
) : HubLink<String> {

    val values = mutableMapOf(
        "hub-serial" to hubId.toByteArray(Charsets.US_ASCII),
        "hub-software" to "bba2c6b".toByteArray(Charsets.US_ASCII),
    )
    val log = mutableListOf<String>()
    val writes = mutableListOf<ByteArray>()
    var aborts = 0
    var closes = 0

    /** Thrown by [connect], before the plan runs. */
    var connectFailure: HubLinkException? = null

    /** Thrown by [read]. */
    var readFailure: HubLinkException? = null

    /** Thrown by [writeControlPoint] for a START. */
    var startWriteFailure: HubLinkException? = null

    /** Virtual time [connect] takes. */
    var connectDelayMs = 0L

    /** The call that never returns: "connect", "read", "write" (either), "write(STOP)" or "disconnect". */
    var hang: String? = null

    /** Thrown by the teardown call with this [log] label: "write(STOP)", "disconnect", "abort" or "close". */
    val teardownFailures = mutableMapOf<String, Exception>()

    private var dead = false

    private fun alive(call: String) {
        log += call
        if (dead) throw HubLinkException(HubLinkException.Failure.DISCONNECTED, "$call on a closed link")
    }

    /** What the hub does when a START arrives, given the nonce bytes in it. */
    var onStart: FakeHubLink.(nonce: ByteArray) -> Unit = { nonce ->
        answer(0x01)
        envelope(envelopeBytes(nonce = nonce))
    }

    private val sink = IndicationSink()

    override val events = sink.events

    override suspend fun connect(timeoutMs: Long, plan: List<InitStep>): List<InitStep> {
        currentCoroutineContext().ensureActive()
        alive("connect")
        if (hang == "connect") awaitCancellation()
        delay(connectDelayMs)
        connectFailure?.let { throw it }
        plan.forEach { log += it.label() }
        return initFailures
    }

    override fun mtu(): Int {
        if (dead) throw HubLinkException(HubLinkException.Failure.DISCONNECTED, "mtu on a closed link")
        return mtu
    }

    override fun disServices(): List<DisSearch.Service<String>> {
        if (dead) throw HubLinkException(HubLinkException.Failure.DISCONNECTED, "disServices on a closed link")
        return services
    }

    override suspend fun read(ref: String, timeoutMs: Long): ByteArray {
        currentCoroutineContext().ensureActive()
        alive("read($ref)")
        if (hang == "read") awaitCancellation()
        readFailure?.let { throw it }
        return values.getValue(ref)
    }

    override suspend fun writeControlPoint(value: ByteArray, timeoutMs: Long) {
        val start = value[0] == 0x01.toByte()
        val label = if (start) "write(START)" else "write(STOP)"
        currentCoroutineContext().ensureActive()
        writes += value
        alive(label)
        teardownFailures[label]?.let { throw it }
        if (hang == "write" || hang == label) awaitCancellation()
        if (start) {
            startWriteFailure?.let { throw it }
            onStart(value.copyOfRange(3, 19))
        }
    }

    override suspend fun disconnect(timeoutMs: Long) {
        currentCoroutineContext().ensureActive()
        alive("disconnect")
        teardownFailures["disconnect"]?.let { throw it }
        if (hang == "disconnect") awaitCancellation()
    }

    override fun abort() {
        alive("abort")
        aborts++
        die()
        teardownFailures["abort"]?.let { throw it }
    }

    override fun close() {
        log += "close"
        closes++
        die()
        teardownFailures["close"]?.let { throw it }
    }

    private fun die() {
        dead = true
        sink.close(HubLinkException(HubLinkException.Failure.DISCONNECTED, "the link was closed"))
    }

    fun answer(result: Int) {
        sink.offer(HubEvent.ControlPointIndication(byteArrayOf(0x80.toByte(), 0x01, result.toByte())))
    }

    /** A raw Control Point indication, well-formed or not. */
    fun controlPoint(bytes: ByteArray) {
        sink.offer(HubEvent.ControlPointIndication(bytes))
    }

    fun envelope(bytes: ByteArray) {
        sink.offer(HubEvent.EnvelopeIndication(bytes))
    }

    fun dropLink() {
        sink.close(HubLinkException(HubLinkException.Failure.DISCONNECTED))
    }

    val startWrites: List<ByteArray> get() = writes.filter { it[0] == 0x01.toByte() }
    val stopWrites: List<ByteArray> get() = writes.filter { it[0] == 0x02.toByte() }
}
