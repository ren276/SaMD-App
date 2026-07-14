package com.example.samdapp.domain.usecase

import kotlinx.coroutines.delay
import javax.inject.Inject
import kotlin.random.Random

/** Represents the (future, real) handoff to the AI processing kernel. No network call yet —
 * the delay is what the Sending screen's progress indicator is shown against. */
class SendToKernelUseCase @Inject constructor() {
    suspend operator fun invoke(): Result<Unit> {
        delay(Random.nextLong(1000L, 2000L))
        return Result.success(Unit)
    }
}
