package com.example.samdapp.domain.transcription

data class CapturedAudio(val uri: String, val transcript: String)

/** Android's `SpeechRecognizer` only supports live mic input, not transcribing an
 * already-saved file — there's no public file-based recognition API. So recognition happens
 * during [captureAudioAttachment] (called when the Consultation screen records the audio
 * attachment); [transcribe] later just returns that already-captured result for the stored
 * URI. This keeps the Transcription screen's call site swappable — a future cloud STT backend
 * that *can* transcribe from a file would only change the implementation, not either call site. */
interface TranscriptionService {
    suspend fun captureAudioAttachment(): Result<CapturedAudio>
    suspend fun transcribe(audioUri: String): Result<String>
}
