package com.example.samdapp.domain.usecase

import com.example.samdapp.config.FeatureFlags
import com.example.samdapp.testutil.FakeConsultationRepository
import com.example.samdapp.testutil.FakeTranscriptionService
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The audio-attachment transcript is the one voice value that reaches `/api/v1/evaluate` with no
 * confirmation gate and no provenance (risk row H-15.C2), so the guard that keeps it from being
 * persisted is worth a test of its own rather than trusting the hidden button above it.
 *
 * `FeatureFlags.VOICE_AUDIO_ATTACHMENT_ENABLED` is a compile-time `const val`, so only the
 * flag-off side is exercisable in this build. That is the side that carries the safety claim; the
 * assertion is guarded so it fails loudly rather than silently passing if the flag is ever flipped
 * without this test being revisited.
 */
class TranscribeAudioUseCaseTest {

    @Test
    fun `refuses to transcribe or persist while the audio-attachment flag is off`() = runTest {
        assertFalse(
            "Flag is on: this test asserts the flag-off behaviour and must be revisited before flipping.",
            FeatureFlags.VOICE_AUDIO_ATTACHMENT_ENABLED,
        )
        val transcriptionService = FakeTranscriptionService()
        val consultationRepository = FakeConsultationRepository()
        val useCase = TranscribeAudioUseCase(transcriptionService, consultationRepository)

        val result = useCase(consultationId = "consultation-1", audioUri = "file://audio.wav")

        assertTrue(result.isFailure)
        assertEquals(
            TranscribeAudioUseCase.VOICE_ATTACHMENT_DISABLED,
            result.exceptionOrNull()?.message,
        )
        // The persist is the thing that matters: a returned failure would be worthless if the row
        // had already been written on the way to it.
        assertEquals(0, consultationRepository.updateTranscriptionCallCount)
        assertEquals(0, transcriptionService.transcribeCallCount)
    }
}
