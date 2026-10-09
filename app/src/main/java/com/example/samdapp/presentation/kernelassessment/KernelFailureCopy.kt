package com.example.samdapp.presentation.kernelassessment

import androidx.annotation.StringRes
import com.example.samdapp.R
import com.example.samdapp.domain.kernel.KernelFailure

/**
 * The one place a [KernelFailure] becomes something a PHC worker reads.
 *
 * Kept out of the domain layer on purpose. [KernelFailure] is pure Kotlin with no `androidx`
 * import, matching every other domain type here except `LocalNetworkFailure` (which imports
 * `android.os.Build` because it must read an SDK level). A domain enum that carried
 * `@StringRes` ids would drag the resource system into the layer that is supposed to be testable
 * without one.
 *
 * Resource ids, not strings. The screen resolves them with `stringResource`, so the copy is
 * translatable and so a test can assert WHICH message a failure selects without asserting the
 * English in it. `res/values/strings.xml` is where every string this track adds or changes lives;
 * strings outside it (`AbhaEnrolResult.messageForCode`,
 * `GenerateKernelReportUseCase.UNAVAILABLE_REASONING_SUMMARY`) are still inline constants, filed
 * for the pre-pilot externalisation and Hindi locale item. The instrument-gateway refusal texts
 * (`acq_reject_*`) are in `strings.xml` too, selected by `AcquisitionRejectionCopy`.
 */
data class KernelFailureCopy(
    @StringRes val titleRes: Int,
    @StringRes val bodyRes: Int,
)

/**
 * [failure] is null for the two UNAVAILABLE states that are not failures: a kernel that answered
 * 200 with an empty differential, and a case whose payload could not be built. Both get the
 * reach-neutral wording, which is the only place the pre-existing copy survives.
 */
fun kernelFailureCopy(failure: KernelFailure?): KernelFailureCopy = when (failure) {
    null -> KernelFailureCopy(
        R.string.kernel_failure_none_title,
        R.string.kernel_failure_none_body,
    )

    KernelFailure.OFFLINE -> KernelFailureCopy(
        R.string.kernel_failure_offline_title,
        R.string.kernel_failure_offline_body,
    )

    KernelFailure.TIMEOUT -> KernelFailureCopy(
        R.string.kernel_failure_timeout_title,
        R.string.kernel_failure_timeout_body,
    )

    KernelFailure.SECURE_CONNECTION_FAILED -> KernelFailureCopy(
        R.string.kernel_failure_secure_connection_failed_title,
        R.string.kernel_failure_secure_connection_failed_body,
    )

    KernelFailure.NOT_AUTHORIZED -> KernelFailureCopy(
        R.string.kernel_failure_not_authorized_title,
        R.string.kernel_failure_not_authorized_body,
    )

    KernelFailure.CASE_NOT_ON_SERVER -> KernelFailureCopy(
        R.string.kernel_failure_case_not_on_server_title,
        R.string.kernel_failure_case_not_on_server_body,
    )

    KernelFailure.PAYLOAD_REJECTED -> KernelFailureCopy(
        R.string.kernel_failure_payload_rejected_title,
        R.string.kernel_failure_payload_rejected_body,
    )

    KernelFailure.KERNEL_UNAVAILABLE -> KernelFailureCopy(
        R.string.kernel_failure_kernel_unavailable_title,
        R.string.kernel_failure_kernel_unavailable_body,
    )

    KernelFailure.MALFORMED_RESPONSE -> KernelFailureCopy(
        R.string.kernel_failure_malformed_response_title,
        R.string.kernel_failure_malformed_response_body,
    )

    KernelFailure.DEVICE_ERROR -> KernelFailureCopy(
        R.string.kernel_failure_device_error_title,
        R.string.kernel_failure_device_error_body,
    )

    KernelFailure.UNKNOWN -> KernelFailureCopy(
        R.string.kernel_failure_unknown_title,
        R.string.kernel_failure_unknown_body,
    )

    KernelFailure.RECORD_INCOMPLETE -> KernelFailureCopy(
        R.string.kernel_failure_record_incomplete_title,
        R.string.kernel_failure_record_incomplete_body,
    )

    KernelFailure.PATIENT_DUPLICATE -> KernelFailureCopy(
        R.string.kernel_failure_patient_duplicate_title,
        R.string.kernel_failure_patient_duplicate_body,
    )

    KernelFailure.CASE_SYNC_BLOCKED -> KernelFailureCopy(
        R.string.kernel_failure_case_sync_blocked_title,
        R.string.kernel_failure_case_sync_blocked_body,
    )

    KernelFailure.CASE_NOT_SENT_YET -> KernelFailureCopy(
        R.string.kernel_failure_case_not_sent_yet_title,
        R.string.kernel_failure_case_not_sent_yet_body,
    )
}
