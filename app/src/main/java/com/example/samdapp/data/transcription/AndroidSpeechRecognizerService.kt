package com.example.samdapp.data.transcription

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.example.samdapp.domain.transcription.CapturedAudio
import com.example.samdapp.domain.transcription.TranscriptionService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Real (not hardcoded) on-device speech-to-text via the platform [SpeechRecognizer].
 * Callers must hold `RECORD_AUDIO` before invoking [captureAudioAttachment] — requesting that
 * runtime permission is a presentation-layer concern, not this service's. */
@Singleton
class AndroidSpeechRecognizerService @Inject constructor(
    @ApplicationContext private val context: Context,
) : TranscriptionService {

    private val transcriptsByUri = ConcurrentHashMap<String, String>()

    override suspend fun captureAudioAttachment(): Result<CapturedAudio> {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            return Result.failure(IllegalStateException("Speech recognition is not available on this device"))
        }
        return try {
            val transcript = withContext(Dispatchers.Main) { recognizeOnce() }
            val uri = "speech-session://${UUID.randomUUID()}"
            transcriptsByUri[uri] = transcript
            Result.success(CapturedAudio(uri = uri, transcript = transcript))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun transcribe(audioUri: String): Result<String> {
        val transcript = transcriptsByUri[audioUri]
            ?: return Result.failure(IllegalStateException("No captured transcription for $audioUri"))
        return Result.success(transcript)
    }

    private suspend fun recognizeOnce(): String = suspendCancellableCoroutine { continuation ->
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString())
        }
        recognizer.setRecognitionListener(
            object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    if (continuation.isActive) continuation.resume(text)
                    recognizer.destroy()
                }

                override fun onError(error: Int) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(RuntimeException("Speech recognition error code $error"))
                    }
                    recognizer.destroy()
                }

                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            },
        )
        recognizer.startListening(intent)
        continuation.invokeOnCancellation { recognizer.destroy() }
    }
}
