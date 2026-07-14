package com.example.samdapp.domain.usecase

import com.example.samdapp.domain.repository.ConsultationRepository
import com.example.samdapp.domain.transcription.CapturedAudio
import com.example.samdapp.domain.transcription.TranscriptionService
import javax.inject.Inject

/** Called from the Consultation screen when the user records an audio attachment. */
class CaptureAudioAttachmentUseCase @Inject constructor(
    private val transcriptionService: TranscriptionService,
) {
    suspend operator fun invoke(): Result<CapturedAudio> = transcriptionService.captureAudioAttachment()
}

/** Called from the Transcription screen; returns the result already captured for [audioUri]. */
class TranscribeAudioUseCase @Inject constructor(
    private val transcriptionService: TranscriptionService,
    private val consultationRepository: ConsultationRepository,
) {
    suspend operator fun invoke(consultationId: String, audioUri: String): Result<String> {
        val transcription = transcriptionService.transcribe(audioUri).getOrElse { return Result.failure(it) }
        consultationRepository.updateTranscription(consultationId, transcription).getOrElse {
            return Result.failure(it)
        }
        return Result.success(transcription)
    }
}
