package com.example.samdapp.data.vitalssource

import com.example.samdapp.domain.model.VitalsReading
import com.example.samdapp.domain.vitalssource.VitalsSource
import javax.inject.Inject

/**
 * Staging/prod's [VitalsSource] binding, until a real device/BLE integration exists. Returns an
 * empty [VitalsReading] (every field already defaults to null) rather than fabricated numbers —
 * the worker fills every field in manually, same as [com.example.samdapp.domain.usecase.GetVitalsPrefillUseCase]'s
 * KDoc already documents ("the result is always user-editable before save"). Dev binds
 * `MockVitalsSource` (in `src/dev/`) instead, which fabricates plausible values for demo purposes.
 */
class UnavailableVitalsSource @Inject constructor() : VitalsSource {
    override suspend fun readVitals(): VitalsReading = VitalsReading()
}
