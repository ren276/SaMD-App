package com.example.samdapp.domain.media

/**
 * Real (not mocked) local-only audio recording for private ailment entries (REQ-AIL-03). There is
 * deliberately no read/playback method on this interface — the worker role must never be able to
 * play a private recording back, and the surest way to guarantee that is to not expose the
 * capability anywhere in the app, not just hide a button for it. Recordings are written to
 * app-private internal storage and are never uploaded; [deleteRecording] is the only other
 * operation a caller can perform on a recording once captured.
 */
interface AilmentAudioRecorder {
    /** Starts recording to a new local file; returns its local URI immediately (recording
     *  continues asynchronously until [stopRecording]). */
    fun startRecording(): Result<String>
    fun stopRecording(): Result<Unit>
    fun deleteRecording(uri: String)
}
