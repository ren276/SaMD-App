package com.example.samdapp.presentation.compounder

import android.os.Build
import androidx.annotation.StringRes
import com.example.samdapp.R
import com.example.samdapp.domain.connectivity.LocalNetworkFailure
import com.example.samdapp.domain.connectivity.classifyLocalNetworkFailure
import com.example.samdapp.domain.vitalssource.AcquisitionTransport
import com.example.samdapp.domain.vitalssource.RejectReason

/**
 * The worker-facing words for a refused instrument reading, as string resource ids, keyed by the
 * reason and the transport the acquisition went over.
 *
 * Resource ids, not strings, so the copy is translatable and so a test can assert WHICH message a
 * reason selects without asserting the English in it. Every [RejectReason] has an entry, and the
 * `when` has no `else`, so a new reason is a compile error here rather than a blank on screen.
 *
 * Only [RejectReason.UNREACHABLE], [RejectReason.TIMEOUT] and [RejectReason.NOT_SUPPORTED] read
 * differently over Bluetooth; every other reason has one text. A null [transport] (a source that
 * does not route, or a failure raised before any transport was chosen) reads as Wi-Fi, which is
 * what the copy was written for before there was a second transport.
 *
 * A Wi-Fi reason that could be the local network goes through [classifyLocalNetworkFailure] rather
 * than being written inline, so the pre-enforcement third state (a vendor-level local-network
 * toggle that `checkSelfPermission` cannot see) reaches the worker with both causes named instead
 * of a confident wrong one. [sdkInt] is a parameter rather than a direct `Build.VERSION.SDK_INT`
 * read so the mapping is testable on the host JVM at each enforcement level.
 *
 * A BLE hub that is busy with another phone is reported as [RejectReason.UNREACHABLE], so the BLE
 * unreachable text covers both "out of range" and "in use by another device".
 *
 * None of these texts carries a measured value.
 */
@StringRes
internal fun acquisitionRejectionRes(
    reason: RejectReason,
    transport: AcquisitionTransport? = null,
    sdkInt: Int = Build.VERSION.SDK_INT,
): Int {
    val ble = transport == AcquisitionTransport.BLE
    return when (reason) {
        RejectReason.UNREACHABLE ->
            if (ble) R.string.acq_reject_unreachable_ble else localNetworkFailureRes(reason, sdkInt)
        RejectReason.PERMISSION_DENIED -> localNetworkFailureRes(reason, sdkInt)
        RejectReason.TIMEOUT -> if (ble) R.string.acq_reject_timeout_ble else R.string.acq_reject_timeout_wifi
        RejectReason.NOT_SUPPORTED -> if (ble) R.string.acq_reject_not_paired_ble else R.string.acq_reject_not_supported
        RejectReason.NO_MEASUREMENT -> R.string.acq_reject_no_measurement
        RejectReason.QUALITY_STATUS_NOT_OK -> R.string.acq_reject_quality
        RejectReason.SESSION_ID_MISMATCH, RejectReason.DEVICE_TYPE_MISMATCH -> R.string.acq_reject_mismatch
        RejectReason.MALFORMED -> R.string.acq_reject_malformed
        RejectReason.BLUETOOTH_UNAVAILABLE -> R.string.acq_reject_bluetooth_unavailable
        RejectReason.HUB_MISMATCH -> R.string.acq_reject_hub_mismatch
    }
}

@StringRes
internal fun localNetworkFailureRes(reason: RejectReason, sdkInt: Int): Int =
    when (classifyLocalNetworkFailure(permissionGranted = reason != RejectReason.PERMISSION_DENIED, sdkInt = sdkInt)) {
        LocalNetworkFailure.PERMISSION_DENIED -> R.string.acq_reject_permission_denied_wifi
        LocalNetworkFailure.UNREACHABLE -> R.string.acq_reject_unreachable_wifi
        LocalNetworkFailure.UNREACHABLE_OR_BLOCKED -> R.string.acq_reject_unreachable_or_blocked_wifi
    }
