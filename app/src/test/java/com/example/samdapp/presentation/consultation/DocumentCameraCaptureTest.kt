package com.example.samdapp.presentation.consultation

import android.graphics.Rect
import android.media.Image
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * A3 (H-18, Build 3b, Option A). [extractJpegBytes] is the one place in the capture path that
 * touches a real [ImageProxy], so it is tested against a hand-written fake rather than needing
 * Robolectric or a mocking library this module does not otherwise depend on ([FakeImageProxy]
 * implements only the interface CameraX declares - every unused member throws, since
 * [extractJpegBytes] never calls it).
 */
class DocumentCameraCaptureTest {

    /** [failOnRead] simulates the plane's buffer throwing mid-copy (a decode-level failure),
     *  the case [extractJpegBytes]'s `finally` exists for. */
    private class FakeImageProxy(bytes: ByteArray, private val failOnRead: Boolean = false) : ImageProxy {
        // Named `sourceBuffer`, not `buffer`: overriding the interface's `getBuffer()` below makes
        // Kotlin synthesize a `buffer` property on the PlaneProxy object mapped to that same
        // method, and a same-named outer val would be shadowed by it - a bare `buffer` reference
        // inside `getBuffer()`'s body would then resolve to itself and recurse forever.
        private val sourceBuffer = ByteBuffer.wrap(bytes)
        var closed = false
            private set

        override fun close() {
            closed = true
        }

        override fun getCropRect(): Rect = throw UnsupportedOperationException("not used by extractJpegBytes")
        override fun setCropRect(rect: Rect?) = throw UnsupportedOperationException("not used by extractJpegBytes")
        override fun getFormat(): Int = throw UnsupportedOperationException("not used by extractJpegBytes")
        override fun getHeight(): Int = throw UnsupportedOperationException("not used by extractJpegBytes")
        override fun getWidth(): Int = throw UnsupportedOperationException("not used by extractJpegBytes")
        override fun getImageInfo(): ImageInfo = throw UnsupportedOperationException("not used by extractJpegBytes")
        override fun getImage(): Image? = throw UnsupportedOperationException("not used by extractJpegBytes")

        override fun getPlanes(): Array<ImageProxy.PlaneProxy> = arrayOf(
            object : ImageProxy.PlaneProxy {
                override fun getRowStride(): Int = throw UnsupportedOperationException("not used by extractJpegBytes")
                override fun getPixelStride(): Int = throw UnsupportedOperationException("not used by extractJpegBytes")
                override fun getBuffer(): ByteBuffer =
                    if (failOnRead) throw IllegalStateException("plane buffer unreadable") else sourceBuffer
            },
        )
    }

    @Test
    fun `extractJpegBytes copies the plane and closes the proxy on success`() {
        val source = byteArrayOf(1, 2, 3, 4, 5)
        val image = FakeImageProxy(source)

        val copied = extractJpegBytes(image)

        assertArrayEquals(source, copied)
        assertTrue(image.closed)
    }

    @Test
    fun `extractJpegBytes closes the proxy even when the copy itself fails`() {
        val image = FakeImageProxy(ByteArray(0), failOnRead = true)

        try {
            extractJpegBytes(image)
        } catch (_: IllegalStateException) {
            // Expected - the point of this test is what happens to `image`, not this rethrow.
        }

        assertTrue(image.closed)
    }
}
