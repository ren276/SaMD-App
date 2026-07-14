package com.example.samdapp.domain.usecase

import com.example.samdapp.domain.model.VitalsReading
import com.example.samdapp.domain.model.VitalsSnapshot
import com.example.samdapp.domain.repository.VitalsRepository
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
