package com.example.samdapp.domain.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H-18, Build 3b (R3). The downscale-at-decode arithmetic, covered here in a plain JVM test
 * because it is the half of the memory discipline that can be proven deterministically.
 *
 * The other half - that only one decoded page is alive at a time - is structural (decode, draw,
 * `finishPage`, `recycle`, then the next page) and is proven by
 * `DocumentCaptureAssemblyTest.assemblingTheMaximumPageCountOfLargePagesDoesNotExhaustMemory`,
 * which assembles the full 20-page cap from oversized sources on a real device. An assertion on
 * a specific peak-heap number would be a flake, not a control, so the on-device test asserts the
 * outcome that actually matters (it completes) and this one pins the rule that makes it possible.
 */
class ImageDownscaleTest {

    @Test
    fun `a source already within budget is not downscaled`() {
        assertEquals(1, computeInSampleSize(1200, 1600, 1600))
    }

    @Test
    fun `sample size grows by powers of two until both edges fit`() {
        assertEquals(2, computeInSampleSize(3200, 2400, 1600))
        assertEquals(4, computeInSampleSize(6400, 4800, 1600))
    }

    /** The field case this exists for: a 12 MP camera page. Full resolution would be a ~48 MB
     *  ARGB_8888 allocation; the sampled decode must bring it under a tenth of that. */
    @Test
    fun `a twelve megapixel camera frame is sampled down before any pixel is allocated`() {
        val width = 4000
        val height = 3000
        val sampleSize = computeInSampleSize(width, height, 1600)

        assertEquals(4, sampleSize)
        val sampledBytes = (width / sampleSize).toLong() * (height / sampleSize) * 4
        assertTrue("sampled allocation was $sampledBytes bytes", sampledBytes < 4L * 1024 * 1024)
    }

    /** Both edges are tested, so a page held in landscape is downscaled by its long edge too. */
    @Test
    fun `the longer edge drives the sample size regardless of orientation`() {
        assertEquals(
            computeInSampleSize(4000, 3000, 1600),
            computeInSampleSize(3000, 4000, 1600),
        )
    }

    @Test
    fun `a degenerate zero dimension terminates instead of looping`() {
        assertEquals(1, computeInSampleSize(0, 0, 1600))
    }

    @Test
    fun `a non-positive bound is rejected instead of looping forever`() {
        assertThrows(IllegalArgumentException::class.java) {
            computeInSampleSize(3200, 2400, 0)
        }
    }
}
