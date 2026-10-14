package com.example.samdapp.data.vitalssource.ble

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.example.samdapp.domain.vitalssource.Instrument
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.Request
import no.nordicsemi.android.ble.TimeoutableRequest
import no.nordicsemi.android.ble.callback.FailCallback
import no.nordicsemi.android.ble.exception.BluetoothDisabledException
import no.nordicsemi.android.ble.exception.DeviceDisconnectedException
import no.nordicsemi.android.ble.exception.InvalidRequestException
import no.nordicsemi.android.ble.exception.RequestFailedException
import no.nordicsemi.android.ble.ktx.suspend
import no.nordicsemi.android.ble.observer.ConnectionObserver

/**
 * The only real [HubLink]: a Nordic `BleManager` for one acquisition on one hub (addendum 2, 5.1).
 * It translates between the library and [HubLink] and decides nothing; every rule is in
 * [BleAcquisition], which is why this class has no unit test (addendum section 10).
 *
 * The contract `FakeHubLink` enforces holds here:
 * - [connect] runs the plan as this connection's init, inside [initialize], and returns the steps
 *   whose own `.fail` fired; the library reports the connect done only once the init queue drained.
 * - Nothing runs after [abort] or [close]: every operation checks [closed] first and throws
 *   DISCONNECTED, because the library itself would accept a new connect on a closed manager. A
 *   request that passed the check as the link closed fails in the library as an invalid request,
 *   which [translateBleFailure] also turns into a [HubLinkException].
 * - Every await is ble-ktx's cancellable one, on a request upcast to `TimeoutableRequest` (G-B23b).
 *
 * One instance per acquisition: [events] and the init record belong to one session and die with it.
 */
class BleHubManager(
    context: Context,
    private val device: BluetoothDevice,
    instrument: Instrument,
) : BleManager(context), HubLink<BluetoothGattCharacteristic> {

    private val sigService = when (instrument) {
        Instrument.BP -> SigUuids.BLOOD_PRESSURE_SERVICE to SigUuids.BLOOD_PRESSURE_MEASUREMENT
        Instrument.SPO2 -> SigUuids.PULSE_OXIMETER_SERVICE to SigUuids.PLX_SPOT_CHECK_MEASUREMENT
        Instrument.THERMOMETER -> SigUuids.HEALTH_THERMOMETER_SERVICE to SigUuids.TEMPERATURE_MEASUREMENT
        else -> throw IllegalArgumentException("no SIG service for $instrument; the source refuses it before here")
    }

    private val closed = AtomicBoolean(false)
    @Volatile private var plan: List<InitStep> = emptyList()
    private val failedInit: MutableList<InitStep> = Collections.synchronizedList(mutableListOf())
    @Volatile private var controlPoint: BluetoothGattCharacteristic? = null
    @Volatile private var envelope: BluetoothGattCharacteristic? = null
    @Volatile private var services: List<DisSearch.Service<BluetoothGattCharacteristic>> = emptyList()

    private val sink = IndicationSink()

    override val events: ReceiveChannel<HubEvent> = sink.events

    init {
        // Posted on the manager's one handler, after every indication that arrived before the drop.
        setConnectionObserver(object : ConnectionObserver {
            override fun onDeviceConnecting(device: BluetoothDevice) = Unit
            override fun onDeviceConnected(device: BluetoothDevice) = Unit
            override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) = Unit
            override fun onDeviceReady(device: BluetoothDevice) = Unit
            override fun onDeviceDisconnecting(device: BluetoothDevice) = Unit
            override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) {
                sink.close(HubLinkException(HubLinkException.Failure.DISCONNECTED, "link lost, reason $reason"))
            }
        })
    }

    // ---- the library's side --------------------------------------------------------------------

    /** Step 3: the vendor Control Point and Envelope and the instrument's SIG measurement. False is
     *  REASON_DEVICE_NOT_SUPPORTED on the connect, NOT_SUPPORTED here, UNREACHABLE in 5.4 as amended
     *  2026-10-14: the device is the associated hub, which is not serving right now. */
    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        val vendor = gatt.getService(ControlPoint.VENDOR_SERVICE)
        controlPoint = vendor?.getCharacteristic(ControlPoint.CONTROL_POINT)
        envelope = vendor?.getCharacteristic(ControlPoint.READING_ENVELOPE)
        services = gatt.services.map { service ->
            DisSearch.Service(service.uuid, service.characteristics.map { DisSearch.Characteristic(it.uuid, it) })
        }
        val sig = gatt.getService(sigService.first)?.getCharacteristic(sigService.second)
        return controlPoint != null && envelope != null && sig != null
    }

    /**
     * Runs on every connection, before the connect completes (G5 item 5). The indication callbacks
     * come first and keep the manager's one handler, no `setHandler` of their own, so Control Point and
     * Envelope indications reach [events] in arrival order (G5 item 6). Each plan step records its own
     * failure, inline (`setHandler(null)`), so the record is complete before the connect is reported.
     */
    override fun initialize() {
        setIndicationCallback(controlPoint).with { _, data ->
            sink.offer(HubEvent.ControlPointIndication(data.value ?: ByteArray(0)))
        }
        setIndicationCallback(envelope).with { _, data ->
            sink.offer(HubEvent.EnvelopeIndication(data.value ?: ByteArray(0)))
        }
        for (step in plan) {
            val request: Request = when (step) {
                is InitStep.RequestMtu -> requestMtu(step.mtu).setHandler(null).fail { _, _ -> failedInit += step }
                is InitStep.EnableIndications -> enableIndications(characteristic(step.target))
                    .timeout(BleAcquisition.STEP_TIMEOUT_MS).setHandler(null).fail { _, _ -> failedInit += step }
            }
            request.enqueue()
        }
    }

    override fun onServicesInvalidated() {
        controlPoint = null
        envelope = null
        services = emptyList()
    }

    /** Q11: nothing below WARN is built or logged. */
    override fun getMinLogPriority(): Int = Log.WARN

    override fun log(priority: Int, message: String) {
        if (priority >= Log.WARN) Log.println(priority, TAG, message)
    }

    // ---- HubLink -------------------------------------------------------------------------------

    override suspend fun connect(timeoutMs: Long, plan: List<InitStep>): List<InitStep> = translated {
        this.plan = plan
        (connect(device).useAutoConnect(false).retry(0).timeout(timeoutMs) as TimeoutableRequest).suspend()
        failedInit.toList()
    }

    override fun mtu(): Int {
        checkOpen("mtu")
        return getMtu()
    }

    override fun disServices(): List<DisSearch.Service<BluetoothGattCharacteristic>> {
        checkOpen("disServices")
        return services
    }

    override suspend fun read(ref: BluetoothGattCharacteristic, timeoutMs: Long): ByteArray = translated {
        var value: ByteArray? = null
        (readCharacteristic(ref).with { _, data -> value = data.value }.timeout(timeoutMs) as TimeoutableRequest).suspend()
        value ?: ByteArray(0)
    }

    override suspend fun writeControlPoint(value: ByteArray, timeoutMs: Long) = translated {
        val write = writeCharacteristic(controlPoint, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        (write.timeout(timeoutMs) as TimeoutableRequest).suspend()
    }

    override suspend fun disconnect(timeoutMs: Long) = translated {
        (disconnect().timeout(timeoutMs) as TimeoutableRequest).suspend()
    }

    /** The cap path: drop every queued request, then close. Only [close] may follow, and it finds the
     *  manager closed, so release runs here even if the queue cancel throws. */
    override fun abort() {
        if (!closed.compareAndSet(false, true)) throw HubLinkException(HubLinkException.Failure.DISCONNECTED, "abort on a closed link")
        try {
            cancelQueue()
        } finally {
            release()
        }
    }

    /** Idempotent: only the first of [abort] and [close] releases the manager. The library's own close
     *  tolerates a second call as well (`BleManager.close`, `BleManagerHandler.close`). */
    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }

    private fun release() {
        try {
            super.close()
        } finally {
            sink.close(HubLinkException(HubLinkException.Failure.DISCONNECTED, "the link was closed"))
        }
    }

    private fun checkOpen(call: String) {
        if (closed.get()) throw HubLinkException(HubLinkException.Failure.DISCONNECTED, "$call on a closed link")
    }

    private fun characteristic(target: IndicationTarget) = when (target) {
        IndicationTarget.CONTROL_POINT -> controlPoint
        IndicationTarget.ENVELOPE -> envelope
    }

    private suspend fun <T> translated(block: suspend () -> T): T {
        checkOpen("request")
        return try {
            block()
        } catch (e: Exception) {
            throw translateBleFailure(e, callerActive = currentCoroutineContext().isActive)
        }
    }

    private companion object {
        const val TAG = "BleHubManager"
    }
}

/**
 * Addendum 5.4's input side: every library failure becomes one [HubLinkException], as a pure function
 * so the table is a JVM test. A CancellationException is the caller's own only while the caller is
 * cancelled, and is returned as it is; one the library raises for a request it dropped
 * (REASON_CANCELLED) while the caller is active is a lost link, not a Stop. Anything else unknown is
 * FAILED, so nothing but a [HubLinkException] or the caller's own cancellation leaves the adapter.
 */
internal fun translateBleFailure(e: Exception, callerActive: Boolean): Exception = when (e) {
    is HubLinkException -> e
    is BluetoothDisabledException -> HubLinkException(HubLinkException.Failure.BLUETOOTH_DISABLED, cause = e)
    is DeviceDisconnectedException -> HubLinkException(HubLinkException.Failure.DISCONNECTED, cause = e)
    is RequestFailedException -> HubLinkException(
        when (e.status) {
            FailCallback.REASON_DEVICE_NOT_SUPPORTED -> HubLinkException.Failure.NOT_SUPPORTED
            FailCallback.REASON_TIMEOUT -> HubLinkException.Failure.TIMED_OUT
            else -> HubLinkException.Failure.FAILED
        },
        "status ${e.status}",
        e,
    )
    is InvalidRequestException -> HubLinkException(HubLinkException.Failure.INVALID_REQUEST, cause = e)
    is SecurityException -> HubLinkException(HubLinkException.Failure.PERMISSION_REVOKED, cause = e)
    is CancellationException ->
        if (callerActive) HubLinkException(HubLinkException.Failure.DISCONNECTED, "the library cancelled the request", e) else e
    else -> HubLinkException(HubLinkException.Failure.FAILED, cause = e)
}

/** The Android answers [BleHubVitalsSource] needs (addendum 4.2), over [HubAssociations]. */
class AndroidBleHubPlatform(
    private val context: Context,
    private val associations: HubAssociations,
) : BleHubPlatform {

    private val bluetooth: BluetoothManager? = context.getSystemService(BluetoothManager::class.java)

    /** Callable without BLUETOOTH_CONNECT (G5 item 9). */
    override fun isBluetoothEnabled(): Boolean = bluetooth?.adapter?.isEnabled == true

    /** The permission exists from API 31; the source refuses below 33 before asking (Q1). */
    override fun hasConnectPermission(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    override fun canAssociate(): Boolean = associations.canAssociate()

    override fun associatedAddress(hubId: String): String? = associations.addressFor(hubId)

    override fun forgetAssociation(hubId: String) = associations.forget(hubId)

    /** 3.4 step 2: the stored association's MAC, made a device without a scan. */
    override fun openLink(address: String, instrument: Instrument): HubLink<*> {
        val adapter = bluetooth?.adapter
            ?: throw HubLinkException(HubLinkException.Failure.BLUETOOTH_DISABLED, "no Bluetooth adapter")
        return BleHubManager(context, adapter.getRemoteDevice(address.uppercase()), instrument)
    }
}
