package com.example.samdapp.data.doctor

import android.content.Context
import com.example.samdapp.domain.model.Doctor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class DoctorDto(
    val id: String,
    val name: String,
    val specialty: String,
    val available: Boolean,
    val facilityName: String? = null,
    val registrationNumber: String? = null,
)

/** Static/mock reference data for this phase, per the brief — loaded once from the bundled asset,
 * not written into Room (no real backend yet, so a cached table would add complexity with no payoff). */
@Singleton
class DoctorAssetDataSource @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var cached: List<Doctor>? = null

    fun loadDoctors(): List<Doctor> {
        cached?.let { return it }
        val raw = context.assets.open("doctors.json").bufferedReader().use { it.readText() }
        val doctors = json.decodeFromString<List<DoctorDto>>(raw).map {
            Doctor(id = it.id, name = it.name, specialty = it.specialty, available = it.available, facilityName = it.facilityName, registrationNumber = it.registrationNumber)
        }
        cached = doctors
        return doctors
    }
}
