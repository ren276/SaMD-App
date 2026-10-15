package com.example.samdapp.data.vitalssource.ble

import kotlinx.coroutines.CancellationException
import no.nordicsemi.android.ble.callback.FailCallback
import no.nordicsemi.android.ble.exception.BluetoothDisabledException
import no.nordicsemi.android.ble.exception.DeviceDisconnectedException
import no.nordicsemi.android.ble.exception.InvalidRequestException
import no.nordicsemi.android.ble.exception.RequestFailedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** A7: [translateBleFailure], one row per ble-ktx exception (`RequestSuspend.kt:399-406`), an unknown
 *  exception, and a cancellation with the caller active and cancelled. The library's exceptions take
 *  the request only to report it, so a null one builds them on the JVM. */
class BleFailureTranslationTest {

    private fun failureOf(e: Exception, callerActive: Boolean = true) =
        (translateBleFailure(e, callerActive) as? HubLinkException)?.failure

    @Test
    fun `every ble-ktx exception becomes its HubLinkException failure`() {
        val table = mapOf(
            BluetoothDisabledException() to HubLinkException.Failure.BLUETOOTH_DISABLED,
            DeviceDisconnectedException() to HubLinkException.Failure.DISCONNECTED,
            RequestFailedException(null, FailCallback.REASON_DEVICE_NOT_SUPPORTED) to HubLinkException.Failure.NOT_SUPPORTED,
            RequestFailedException(null, FailCallback.REASON_TIMEOUT) to HubLinkException.Failure.TIMED_OUT,
            RequestFailedException(null, 133) to HubLinkException.Failure.FAILED,
            InvalidRequestException(null) to HubLinkException.Failure.INVALID_REQUEST,
            SecurityException("BLUETOOTH_CONNECT revoked") to HubLinkException.Failure.PERMISSION_REVOKED,
            IllegalStateException("unknown") to HubLinkException.Failure.FAILED,
        )
        for ((thrown, expected) in table) assertEquals(thrown.toString(), expected, failureOf(thrown))
    }

    @Test
    fun `a cancellation is a lost link while the caller is active, and the caller's own when it is not`() {
        val cancelled = CancellationException("REASON_CANCELLED")

        assertEquals(HubLinkException.Failure.DISCONNECTED, failureOf(cancelled, callerActive = true))
        assertSame(cancelled, translateBleFailure(cancelled, callerActive = false))
    }

    @Test
    fun `a HubLinkException passes through unchanged`() {
        val own = HubLinkException(HubLinkException.Failure.DISCONNECTED, "request on a closed link")
        assertSame(own, translateBleFailure(own, callerActive = true))
    }
}
