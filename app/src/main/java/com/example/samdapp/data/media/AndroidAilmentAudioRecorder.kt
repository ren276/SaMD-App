package com.example.samdapp.data.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import com.example.samdapp.domain.media.AilmentAudioRecorder
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real (not mocked) local-only recording via [MediaRecorder] — see [AilmentAudioRecorder] for why
 * this interface has no playback method at all. Files land in `filesDir/ailment_audio/`, which is
 * app-private internal storage: not shared via `FileProvider`, not on external/shared storage, and
 * never touched by any upload path in this codebase.
 */
@Singleton
class AndroidAilmentAudioRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
) : AilmentAudioRecorder {

    private var recorder: MediaRecorder? = null

    override fun startRecording(): Result<String> = try {
        val dir = File(context.filesDir, "ailment_audio").apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.m4a")
        @Suppress("DEPRECATION")
        val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
        mediaRecorder.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
        recorder = mediaRecorder
        Result.success(file.toUri().toString())
    } catch (e: Exception) {
        Result.failure(e)
    }

    override fun stopRecording(): Result<Unit> = try {
        recorder?.apply {
            stop()
            release()
        }
        recorder = null
        Result.success(Unit)
    } catch (e: Exception) {
        recorder?.release()
        recorder = null
        Result.failure(e)
    }

    override fun deleteRecording(uri: String) {
        runCatching { File(android.net.Uri.parse(uri).path.orEmpty()).delete() }
    }

    private fun File.toUri() = android.net.Uri.fromFile(this)
}
