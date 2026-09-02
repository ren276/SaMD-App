package com.example.samdapp.domain.transcription

data class CapturedAudio(val uri: String, val transcript: String)

/** Recognition happens during [captureAudioAttachment] (called when the Consultation screen
 * records the audio attachment); [transcribe] later just returns that already-captured result
 * for the stored URI. The split exists because the implementation retains no audio: the capture
 * is recognised in memory and dropped, so there is no file for a later call to re-read. Keeping
 * [transcribe] on the interface anyway leaves the Transcription screen's call site swappable —
 * an implementation that *can* transcribe from a stored file would change only the
 * implementation, not either call site.
 *
 * Two suspend functions returning [Result], and that is the whole contract. Partial hypotheses
 * are deliberately absent: the confirmation gate shows one suggestion after the capture ends,
 * and surfacing live unconfirmed text mid-capture is the automation-bias surface the gate exists
 * to avoid, not a missing feature. */
interface TranscriptionService {
    suspend fun captureAudioAttachment(): Result<CapturedAudio>
    suspend fun transcribe(audioUri: String): Result<String>
}
