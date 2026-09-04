package com.example.samdapp.domain.document

/**
 * The `inSampleSize` for [android.graphics.BitmapFactory.Options] that keeps a decoded bitmap
 * within [maxDimension] on its longer edge - a power of two, as `BitmapFactory` requires.
 *
 * Pure arithmetic on the source's declared dimensions, so it runs BEFORE any pixel is allocated:
 * this is what makes a downscale a downscale-at-decode rather than a decode-then-shrink. A 12 MP
 * camera page decoded at full resolution is a ~48 MB `ARGB_8888` allocation; a handful of those
 * at once is an OOM on a low-end PHC phone, and the camera-assembly loop would hold one per page.
 *
 * Extracted from the Build 3a document viewer, which had the identical private copy, so the
 * viewer and the Build 3b assembler downscale by the same rule and one unit test covers both.
 *
 * [maxDimension] must be positive: a zero or negative bound has no sample size that satisfies it,
 * so the loop below would never terminate. Every call site passes a compile-time constant, so this
 * is a programming error rather than a runtime condition, and it fails loudly at the boundary.
 */
fun computeInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
    require(maxDimension > 0) { "maxDimension must be positive, was $maxDimension" }
    var sampleSize = 1
    while (width / sampleSize > maxDimension || height / sampleSize > maxDimension) {
        sampleSize *= 2
    }
    return sampleSize
}
