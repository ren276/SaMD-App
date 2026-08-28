package com.example.samdapp.presentation.patients

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.samdapp.domain.model.Patient
import com.example.samdapp.domain.model.PatientDirectoryEntry
import com.example.samdapp.domain.repository.PatientRepository
import com.example.samdapp.domain.usecase.GetRecentPatientsUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

private fun testPatient(id: String, fullName: String) = Patient(
    id = id, fullName = fullName, dateOfBirth = LocalDate.of(1990, 1, 1), age = 34, biologicalSex = "Female",
    guardianOrSpouseName = null, guardianRelation = null, mobileNumber = "9876543210", aadhaarNumber = null,
    abhaNumber = null, village = "Sample village", block = null, district = null, state = null, pincode = null,
    category = null, maritalStatus = null, bloodGroup = null, emergencyContact = null,
    primaryCareClinicName = null, referringPhysicianName = null,
    createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
)

private class FakeDirectoryPatientRepository(
    private val entries: List<PatientDirectoryEntry>,
) : PatientRepository {
    override suspend fun register(patient: Patient): Result<Unit> = Result.success(Unit)
    override fun observePatient(patientId: String): Flow<Patient?> = flowOf(null)
    override fun observeTodaysPatients(): Flow<List<Patient>> = flowOf(emptyList())
    override fun observeRegisteredOrSeenRecently(days: Int): Flow<List<PatientDirectoryEntry>> = flowOf(entries)
}

/**
 * Confirms a registered-not-yet-seen row (null lastSeenAt) stays tappable and still resolves to
 * the same PatientSummary target every other Patients-tab row uses - this row does not need a
 * different destination, only a visible "not yet seen" marker (see PatientsScreen's KDoc).
 */
class PatientsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun tappingARegisteredNotYetSeenRow_resolvesToThatPatientId() {
        val entry = PatientDirectoryEntry(patient = testPatient("p1", "Asha Devi"), lastSeenAt = null)
        val repo = FakeDirectoryPatientRepository(listOf(entry))
        var openedPatientId: String? = null

        composeRule.setContent {
            PatientsScreen(
                onOpenPatient = { patientId -> openedPatientId = patientId },
                viewModel = PatientsViewModel(GetRecentPatientsUseCase(repo)),
            )
        }

        composeRule.onNodeWithText("Registered, not yet seen").assertExists("expected the null-lastSeenAt row to show its subtitle")
        composeRule.onNodeWithText("Asha Devi").performClick()

        assert(openedPatientId == "p1") { "Expected tap on a null-lastSeenAt row to resolve to its patient id" }
    }
}
