package com.example.samdapp.data.mock

import com.example.samdapp.domain.model.VitalsReading
import com.example.samdapp.domain.vitalssource.VitalsSource
import kotlinx.coroutines.delay
import javax.inject.Inject
import kotlin.random.Random

/** Randomizes within plausible ranges on every call so the investor demo doesn't look static.
 *
 * UNBOUND as of the Pi gateway integration: [com.example.samdapp.di.DevClinicalMockModule] now
 * binds `PiGatewayVitalsSource` instead. Kept rather than deleted because this is the hardware-free
 * demo path, and restoring it is a one-line binding change in that module. Nothing references this
 * class in the meantime, which is intended. */
class MockVitalsSource @Inject constructor() : VitalsSource {

    override suspend fun readVitals(): VitalsReading {
        delay(400)
        return VitalsReading(
            pulseBpm = Random.nextInt(60, 100),
            bpSystolic = Random.nextInt(100, 140),
            bpDiastolic = Random.nextInt(60, 90),
            spo2Percent = Random.nextInt(95, 100),
            temperatureCelsius = Random.nextInt(361, 375) / 10.0,
            respiratoryRate = Random.nextInt(12, 20),
            weightKg = Random.nextInt(400, 900) / 10.0,
            heightCm = Random.nextInt(140, 190).toDouble(),
            bloodGlucoseMgDl = Random.nextInt(80, 140),
            deviceId = "MOCK-VITALS-MONITOR-01",
        )
    }
}
