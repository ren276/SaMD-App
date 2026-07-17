package com.example.samdapp.domain.usecase

import com.example.samdapp.domain.model.AbhaProfile
import com.example.samdapp.domain.repository.AbhaProfileRepository
import kotlinx.coroutines.delay
import javax.inject.Inject
import kotlin.random.Random

/**
 * Mock ABHA login, second step (ABHA id already entered): verifies the mock OTP and returns the
 * stored [AbhaProfile] to autofill registration. Any 6-digit code is accepted — there is no real
 * SMS/OTP gateway — the caller's OTP field pre-fills `123456` and labels itself as a mock, per the
 * demo brief; this use case doesn't special-case that value, it just checks the shape.
 *
 * Only ABHA ids previously created on this device (via [CreateAbhaProfileUseCase]) resolve — there
 * is no real ABDM directory to look up against in the mock, so "existing-patient" here means
 * "existing on this install," not "existing nationally." That's an honest limit of the mock, not a
 * bug.
 */
class VerifyAbhaLoginUseCase @Inject constructor(
    private val repository: AbhaProfileRepository,
) {
    suspend operator fun invoke(abhaId: String, otp: String): Result<AbhaProfile> {
        if (otp.length != 6 || !otp.all(Char::isDigit)) {
            return Result.failure(IllegalArgumentException("Enter the 6-digit mock OTP"))
        }

        delay(Random.nextLong(500L, 1200L))

        val profile = repository.getProfile(abhaId)
            ?: return Result.failure(
                NoSuchElementException("No ABHA profile found for this ID on this device (mock — no real ABDM lookup)"),
            )
        return Result.success(profile)
    }
}
