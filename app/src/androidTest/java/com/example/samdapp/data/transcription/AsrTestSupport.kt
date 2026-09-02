package com.example.samdapp.data.transcription

import androidx.test.platform.app.InstrumentationRegistry

/**
 * One recognizer per instrumentation process, shared by every ASR test class.
 *
 * Not a tidiness point, and it is why this holder exists rather than a per-class companion: the
 * service retains its recognizer for the process lifetime by design, so a second instance means a
 * second resident copy of 622 MiB of weights. PR 4a found this the hard way, with a roughly 50%
 * flaky run where the instrumentation process was SIGKILLed partway through and whichever test
 * happened to be reporting last was marked failed. Three loaded recognizers do not fit in a 4 GB
 * emulator. This mirrors the single `@Singleton` the app actually binds.
 */
internal val sharedAsrService: SherpaOnnxTranscriptionService by lazy {
    SherpaOnnxTranscriptionService(
        InstrumentationRegistry.getInstrumentation().targetContext,
        MODEL_ASSET_DIR,
    )
}

/** 16-bit PCM mono little-endian, which is what the test fixtures are and what the model wants.
 *  Skips to the `data` chunk rather than assuming a 44-byte header. */
internal fun readPcm16Wav(assetPath: String): FloatArray {
    val bytes = InstrumentationRegistry.getInstrumentation().context.assets
        .open(assetPath).use { it.readBytes() }
    var offset = 12 // past "RIFF" <size> "WAVE"
    while (offset + 8 <= bytes.size) {
        val id = String(bytes, offset, 4, Charsets.US_ASCII)
        val size = littleEndianInt(bytes, offset + 4)
        if (id == "data") {
            val samples = FloatArray(size / 2)
            for (i in samples.indices) {
                val lo = bytes[offset + 8 + i * 2].toInt() and 0xff
                val hi = bytes[offset + 9 + i * 2].toInt()
                samples[i] = ((hi shl 8) or lo) / 32768f
            }
            return samples
        }
        offset += 8 + size + (size and 1)
    }
    throw IllegalArgumentException("no data chunk in $assetPath")
}

private fun littleEndianInt(b: ByteArray, at: Int): Int =
    (b[at].toInt() and 0xff) or
        ((b[at + 1].toInt() and 0xff) shl 8) or
        ((b[at + 2].toInt() and 0xff) shl 16) or
        ((b[at + 3].toInt() and 0xff) shl 24)
