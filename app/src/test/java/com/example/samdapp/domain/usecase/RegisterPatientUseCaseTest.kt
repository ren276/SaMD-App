package com.example.samdapp.domain.usecase

import com.example.samdapp.domain.model.Patient
import com.example.samdapp.domain.model.PatientDirectoryEntry
import com.example.samdapp.domain.repository.PatientRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FakePatientRepository : PatientRepository {
    val registered = mutableListOf<Patient>()

    override suspend fun register(patient: Patient): Result<Unit> {
        registered.add(patient)
        return Result.success(Unit)
    }

    override fun observePatient(patientId: String): Flow<Patient?> =
        flowOf(registered.firstOrNull { it.id == patientId })

    override fun observeTodaysPatients(): Flow<List<Patient>> = flowOf(registered.toList())

    override fun observeRegisteredOrSeenRecently(days: Int): Flow<List<PatientDirectoryEntry>> =
        flowOf(registered.map { PatientDirectoryEntry(patient = it, lastSeenAt = null) })
}

class RegisterPatientUseCaseTest {

    private lateinit var repository: FakePatientRepository
    private lateinit var useCase: RegisterPatientUseCase

    @Before
    fun setUp() {
        repository = FakePatientRepository()
        useCase = RegisterPatientUseCase(repository)
    }

    @Test
    fun `blank full name is rejected`() = runTest {
        val result = useCase(
            fullName = "",
            dateOfBirth = null,
            age = null,
            biologicalSex = "Female",
            mobileNumber = "9999999999",
            village = null,
        )
        assertTrue(result.isFailure)
        assertTrue(repository.registered.isEmpty())
    }

    @Test
    fun `no contact method is rejected`() = runTest {
        val result = useCase(
            fullName = "Anita Kumari",
            dateOfBirth = null,
            age = 30,
            biologicalSex = "Female",
            mobileNumber = null,
            village = null,
        )
        assertTrue(result.isFailure)
        assertTrue(repository.registered.isEmpty())
    }

    @Test
    fun `mobile number alone satisfies the contact requirement`() = runTest {
        val result = useCase(
            fullName = "Anita Kumari",
            dateOfBirth = null,
            age = 30,
            biologicalSex = "Female",
            mobileNumber = "9999999999",
            village = null,
        )
        assertTrue(result.isSuccess)
        assertEquals(1, repository.registered.size)
        assertEquals("Anita Kumari", repository.registered.first().fullName)
    }

    @Test
    fun `village address alone satisfies the contact requirement`() = runTest {
        val result = useCase(
            fullName = "Ramesh Yadav",
            dateOfBirth = null,
            age = 45,
            biologicalSex = "Male",
            mobileNumber = null,
            village = "Rampur",
        )
        assertTrue(result.isSuccess)
        assertEquals(1, repository.registered.size)
    }

    @Test
    fun `generated patient gets a non-blank id`() = runTest {
        val result = useCase(
            fullName = "Ramesh Yadav",
            dateOfBirth = null,
            age = 45,
            biologicalSex = "Male",
            mobileNumber = "9998887777",
            village = null,
        )
        assertTrue(result.getOrThrow().id.isNotBlank())
    }
}
