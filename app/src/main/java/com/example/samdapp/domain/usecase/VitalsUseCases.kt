package com.example.samdapp.domain.usecase

import com.example.samdapp.domain.model.VitalsReading
import com.example.samdapp.domain.model.VitalsSnapshot
import com.example.samdapp.domain.repository.VitalsRepository
import com.example.samdapp.domain.vitalssource.AcquisitionRequest
import com.example.samdapp.domain.vitalssource.AcquisitionResult
import com.example.samdapp.domain.vitalssource.VitalsSource
import javax.inject.Inject

/** Pulls a pre-fill from [VitalsSource] — the only place UI code's need for device vitals crosses
 * the mock-boundary seam. The result is always user-editable before [RecordVitalsUseCase]. */
class GetVitalsPrefillUseCase @Inject constructor(
    private val vitalsSource: VitalsSource,
) {
    suspend operator fun invoke(): VitalsReading = vitalsSource.readVitals()
}

class RecordVitalsUseCase @Inject constructor(
    private val vitalsRepository: VitalsRepository,
) {
    suspend operator fun invoke(snapshot: VitalsSnapshot): Result<Unit> =
        vitalsRepository.saveVitals(snapshot)
}

/** Worker-triggered acquisition from an instrument gateway, through the same [VitalsSource] the
 *  prefill uses. Thin on purpose: the admissibility controls belong to the source, not here, and
 *  this use case never persists anything. An [AcquisitionResult.Accepted] reading is form state
 *  the worker still edits and still has to save; [RecordVitalsUseCase] remains the only path into
 *  the record. */
class AcquireDeviceVitalsUseCase @Inject constructor(
    private val vitalsSource: VitalsSource,
) {
    suspend operator fun invoke(request: AcquisitionRequest): AcquisitionResult =
        vitalsSource.startAcquisition(request)
}

/** Ends whatever acquisition session the source has open, on the Stop control, on screen exit and
 *  on the failure path. Best effort, never throws. */
class StopDeviceAcquisitionUseCase @Inject constructor(
    private val vitalsSource: VitalsSource,
) {
    suspend operator fun invoke() = vitalsSource.stopAcquisition()
}
