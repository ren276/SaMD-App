package com.example.samdapp.domain.usecase

import com.example.samdapp.config.FeatureFlags
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

/** Called from the Transcription screen; returns the result already captured for [audioUri].
 *
 *  This use case is the only thing that writes a transcript into `Consultation.transcription`, and
 *  that value is concatenated into `symptom_string` on the evaluate wire, so it reaches
 *  `/api/v1/evaluate`. (The class that builds that string is named in
 *  `FeatureFlags.VOICE_AUDIO_ATTACHMENT_ENABLED`'s KDoc rather than linked here: this file is
 *  scanned by `NoPlatformRecognizerSourceScanTest`, which treats any mention of the remote-data
 *  package as a network client reintroduced onto the transcription path, and it is right to.) It
 *  passes through no confirmation gate, carries no
 *  [com.example.samdapp.domain.model.FieldProvenance], and is explicitly outside the
 *  `VOICE_UNCONFIRMED` write refusal in
 *  [com.example.samdapp.data.repository.ConsultationRepositoryImpl] (that refusal reads a
 *  provenance column, and this field has none). Registered as risk row H-15.C2.
 *
 *  The guard below therefore lives HERE rather than only in the screen or the navigation branch:
 *  the persist has to be unreachable from any caller, including the second caller that does not
 *  exist yet. Building the confirmation gate and the provenance column is the parked follow-up
 *  (`scratchpad/chief-complaint-voice-flip-design-memo.md` sections 4 and 9); until then the flag
 *  is the control.
 *
 *  Refused, not silently dropped: a dropped write would return success with nothing persisted,
 *  and the Transcription screen would show a transcript that is not in the database. */
class TranscribeAudioUseCase @Inject constructor(
    private val transcriptionService: TranscriptionService,
    private val consultationRepository: ConsultationRepository,
) {
    companion object {
        const val VOICE_ATTACHMENT_DISABLED = "VOICE_ATTACHMENT_DISABLED"
    }

    suspend operator fun invoke(consultationId: String, audioUri: String): Result<String> {
        if (!FeatureFlags.VOICE_AUDIO_ATTACHMENT_ENABLED) {
            return Result.failure(IllegalStateException(VOICE_ATTACHMENT_DISABLED))
        }
        val transcription = transcriptionService.transcribe(audioUri).getOrElse { return Result.failure(it) }
        consultationRepository.updateTranscription(consultationId, transcription).getOrElse {
            return Result.failure(it)
        }
        return Result.success(transcription)
    }
}
