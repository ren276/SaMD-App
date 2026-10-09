package com.example.samdapp.presentation.compounder

import android.os.Build
import androidx.annotation.StringRes
import com.example.samdapp.R
import com.example.samdapp.domain.connectivity.LocalNetworkFailure
import com.example.samdapp.domain.connectivity.classifyLocalNetworkFailure
import com.example.samdapp.domain.vitalssource.RejectReason

/**
 * The worker-facing words for a refused instrument reading, as string resource ids.
 *
 * Resource ids, not strings, so the copy is translatable and so a test can assert WHICH message a
 * reason selects without asserting the English in it. Every [RejectReason] has an entry, and the
 * `when` has no `else`, so a new reason is a compile error here rather than a blank on screen.
 *
 * A reason that could be the local network goes through [classifyLocalNetworkFailure] rather than
 * being written inline, so the pre-enforcement third state (a vendor-level local-network toggle
 * that `checkSelfPermission` cannot see) reaches the worker with both causes named instead of a
 * confident wrong one. [sdkInt] is a parameter rather than a direct `Build.VERSION.SDK_INT` read so
 * the mapping is testable on the host JVM at each enforcement level.
 *
 * None of these texts carries a measured value.
 */
@StringRes
internal fun acquisitionRejectionRes(reason: RejectReason, sdkInt: Int = Build.VERSION.SDK_INT): Int = when (reason) {
    RejectReason.UNREACHABLE, RejectReason.PERMISSION_DENIED -> localNetworkFailureRes(reason, sdkInt)
    RejectReason.TIMEOUT -> R.string.acq_reject_timeout_wifi
    RejectReason.NO_MEASUREMENT -> R.string.acq_reject_no_measurement
    RejectReason.QUALITY_STATUS_NOT_OK -> R.string.acq_reject_quality
    RejectReason.SESSION_ID_MISMATCH, RejectReason.DEVICE_TYPE_MISMATCH -> R.string.acq_reject_mismatch
    RejectReason.MALFORMED -> R.string.acq_reject_malformed
    RejectReason.NOT_SUPPORTED -> R.string.acq_reject_not_supported
}

@StringRes
internal fun localNetworkFailureRes(reason: RejectReason, sdkInt: Int): Int =
    when (classifyLocalNetworkFailure(permissionGranted = reason != RejectReason.PERMISSION_DENIED, sdkInt = sdkInt)) {
        LocalNetworkFailure.PERMISSION_DENIED -> R.string.acq_reject_permission_denied_wifi
        LocalNetworkFailure.UNREACHABLE -> R.string.acq_reject_unreachable_wifi
        LocalNetworkFailure.UNREACHABLE_OR_BLOCKED -> R.string.acq_reject_unreachable_or_blocked_wifi
    }
