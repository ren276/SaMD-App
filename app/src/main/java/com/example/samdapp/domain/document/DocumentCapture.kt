package com.example.samdapp.domain.document

/**
 * H-18, Build 3b. How a [com.example.samdapp.domain.model.ConsultationDocument]'s bytes reach
 * [com.example.samdapp.domain.repository.ConsultationDocumentRepository.upload].
 *
 * One entry point, two byte provenances, deliberately: PATH B (camera multi-capture) must land in
 * the SAME metadata row shape, the SAME storage-key scheme, the SAME audit action and the SAME
 * retract as PATH A. Modelling the difference as a parameter rather than a second `upload`-shaped
 * method is what keeps a second storage path from existing at all.
 */
sealed interface DocumentBytes {
    /** PATH A (Build 3a): an existing PDF/JPEG/PNG the worker already has, behind a content URI.
     *  [claimedMimeType] is the picker's untrusted claim, cross-checked against magic bytes. */
    data class DirectFile(val sourceUri: String, val claimedMimeType: String?) : DocumentBytes

    /**
     * PATH B (Build 3b): a multi-page PDF this app assembled from camera captures and already
     * encrypted, sitting in its capture-session directory awaiting a `consultationId`.
     *
     * [sizeBytes] and [sha256] are of the PLAINTEXT assembled PDF, measured by
     * [com.example.samdapp.data.local.security.DocumentEncryptionProvider] in the same pass that
     * encrypted it - this app's own measurement of its own output, never a caller's claim about
     * a foreign file, which is why they are carried here rather than re-derived.
     */
    data class AssembledCapture(
        val captureSessionId: String,
        val pageCount: Int,
        val sizeBytes: Long,
        val sha256: String,
    ) : DocumentBytes
}

/** One captured page, as the capture UI knows it. [thumbnailJpeg] is a small re-encoded JPEG held
 *  in memory only for the thumbnail strip - never written to disk, so the only on-disk copy of a
 *  captured page is the encrypted one. A `ByteArray` rather than a `Bitmap` so this type stays in
 *  the domain layer and the ViewModel never handles an Android graphics object. */
class CapturedPage(val pageId: String, val thumbnailJpeg: ByteArray)

/**
 * Thrown when a captured page cannot be turned into a drawable image - the ciphertext fails GCM
 * authentication, the file is gone, or the bytes will not decode.
 *
 * This is the mechanism behind H-18's camera-assembly hazard. A multi-page lab report that
 * silently arrives with page 3 missing, and no indication that page 3 ever existed, is a clinical
 * hazard: the reader has no way to know they are looking at an incomplete record. So an
 * unreadable page aborts the ENTIRE assembly and produces no document at all. It is never skipped
 * and the remaining pages are never assembled into a shorter PDF.
 */
class DocumentPageUnreadableException(
    val pageIndex: Int,
    val pageId: String,
    cause: Throwable? = null,
) : Exception("Captured page ${pageIndex + 1} could not be read", cause)

/**
 * H-18, Build 3b. Owns the on-disk life of an in-progress camera capture: the per-session
 * directory of individually-encrypted pages, the short-lived plaintext staging file each camera
 * hand-off needs, and the assembly of the final PDF.
 *
 * An interface in the domain layer for the same reason every repository here is: the ViewModel
 * that drives the capture loop is plain-JVM unit tested, and the real implementation needs the
 * Android Keystore, `BitmapFactory` and `PdfDocument`.
 *
 * **Encrypt-as-captured, not assemble-then-encrypt.** Each page is encrypted the moment the
 * camera returns it and decrypted one at a time during assembly. The alternative - keep N
 * plaintext JPEGs until the worker taps done, then assemble and encrypt - would leave every page
 * of a clinical document readable on disk for the whole capture loop and across any process death
 * in it, which is the exact posture this feature exists to avoid.
 */
interface DocumentCaptureStore {

    /** Cap on pages in one assembled document. Bounds both the assembly's wall-clock cost and the
     *  worst case a low-end PHC phone has to survive. */
    val maxPages: Int

    /** A new session id. Opaque and non-identifying: it becomes a raw filesystem path component,
     *  so it follows the same rule as `storageKey` and carries no UHID and no worker-typed text.
     *  Touches no disk - directories are created by the suspending calls that need them. */
    fun newSession(): String

    /** Absolute path of the plaintext staging file the camera app writes page [pageId] into,
     *  its directory created. A path, not a `Uri`: the Screen owns the `FileProvider` grant and
     *  the ViewModel never sees an Android `Uri` (the layering the direct-file path follows). */
    suspend fun stagingPathFor(sessionId: String, pageId: String): String

    /**
     * Encrypts the staging file for [pageId] into the session directory and deletes the plaintext
     * staging file before returning, on every path including failure. After this returns there is
     * no plaintext copy of the page anywhere on disk.
     */
    suspend fun ingestPage(sessionId: String, pageId: String): Result<CapturedPage>

    /** Deletes the plaintext staging file for a capture the camera never completed (cancelled,
     *  or returned `saved = false`). */
    suspend fun discardStaging(sessionId: String, pageId: String)

    /** Per-page delete before finalising (page order and page membership are clinical meaning). */
    suspend fun deletePage(sessionId: String, pageId: String)

    /** Deletes the whole session directory, every encrypted page in it, any assembled output and
     *  any staging leftovers. Abandoning a capture keeps nothing: encrypted PHI on disk with no
     *  metadata row, no audit and no owner is a worse posture than losing the photos. */
    suspend fun discardSession(sessionId: String)

    /**
     * Consolidates [orderedPageIds] into ONE PDF, in exactly that order, encrypts it inside the
     * session directory and returns its measurements. [onProgress] is called with
     * `(pagesDone, pageCount)` as each page is finished.
     *
     * Fails, writing nothing, if any page is unreadable ([DocumentPageUnreadableException]) or if
     * [orderedPageIds] is empty or longer than [maxPages]. Cancellation of the calling coroutine
     * deletes the partial output the same way a failure does.
     */
    suspend fun assemble(
        sessionId: String,
        orderedPageIds: List<String>,
        onProgress: (Int, Int) -> Unit,
    ): Result<DocumentBytes.AssembledCapture>
}
