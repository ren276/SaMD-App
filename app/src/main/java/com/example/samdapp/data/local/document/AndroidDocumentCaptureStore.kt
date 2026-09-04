package com.example.samdapp.data.local.document

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.media.ExifInterface
import com.example.samdapp.data.local.security.DocumentEncryptionProvider
import com.example.samdapp.data.repository.ConsultationDocumentRepositoryImpl.Companion.MAX_DOCUMENT_SIZE_BYTES
import com.example.samdapp.domain.document.CapturedPage
import com.example.samdapp.domain.document.DocumentBytes
import com.example.samdapp.domain.document.DocumentCaptureStore
import com.example.samdapp.domain.document.DocumentPageUnreadableException
import com.example.samdapp.domain.document.computeInSampleSize
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/** Sibling of Build 3a's `documents/<consultationId>/` directories, under the same `filesDir`
 *  root. A `consultationId` is a UUID and can never be the literal `.capture`, so the two never
 *  collide and the sweep below can never reach a stored document. */
private const val CAPTURE_DIR = ".capture"

/** The plaintext staging directory. In `cacheDir`, not `filesDir`, and its own subdirectory so
 *  the sweep and the `FileProvider` grant are both scoped to exactly it - the camera app is
 *  granted this path and nothing else. */
private const val STAGING_DIR = "document_capture_staging"

private const val ASSEMBLED_FILE = "assembled.enc"

/** A4 at 72 dpi, the unit `PdfDocument.PageInfo` uses. */
private const val PAGE_WIDTH_PT = 595
private const val PAGE_HEIGHT_PT = 842

/**
 * Longer-edge pixel budget for a captured page. At 1600 px on an A4 page this is roughly 190 dpi
 * - comfortably legible for printed lab-report text - while capping one decoded page at about
 * 1600x1200x4 = 7.7 MB instead of the ~48 MB a full-resolution 12 MP frame would take.
 *
 * ponytail: fixed budget, not adaptive. If a 20-page assembly ever trips the 20 MB document cap
 * in the field, lower this before reaching for anything cleverer - it is the one knob that moves
 * output size, and the failure it prevents is loud (an explicit error, no document) rather than
 * silent.
 */
private const val PAGE_MAX_DIMENSION = 1600

private const val THUMBNAIL_MAX_DIMENSION = 256
private const val THUMBNAIL_JPEG_QUALITY = 70

/**
 * H-18, Build 3b. Deletes every capture-session directory and every plaintext staging file.
 *
 * **Policy: sweep everything, unconditionally, at app start.** A capture session's page list
 * lives only in `ConsultationViewModel` state, so it cannot survive process death; any session
 * directory that exists when the process starts is by definition orphaned, and there is no live
 * session for the sweep to damage. That makes an age heuristic or a liveness registry pointless
 * complexity here.
 *
 * Deliberately a SECOND, separate sweep rather than an extension of Build 3a's
 * [com.example.samdapp.presentation.documents.sweepOrphanedViewerTempFiles]. They cover disjoint
 * directories (`cacheDir/document_viewer_temp` there, `filesDir/documents/.capture` plus
 * `cacheDir/document_capture_staging` here) and answer different questions, so merging them would
 * only hide which one failed. Both are called from `SaMDApplication.onCreate`.
 */
fun sweepOrphanedCaptureSessions(context: Context) {
    File(File(context.filesDir, "documents"), CAPTURE_DIR).deleteRecursively()
    File(context.cacheDir, STAGING_DIR).deleteRecursively()
}

@Singleton
class AndroidDocumentCaptureStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val encryptionProvider: DocumentEncryptionProvider,
) : DocumentCaptureStore {

    override val maxPages: Int = MAX_PAGES

    private fun sessionDir(sessionId: String): File =
        File(File(File(context.filesDir, "documents"), CAPTURE_DIR), sanitizeId(sessionId)).apply { mkdirs() }

    private fun stagingDir(): File = File(context.cacheDir, STAGING_DIR).apply { mkdirs() }

    private fun stagingFile(sessionId: String, pageId: String): File =
        File(stagingDir(), "${sanitizeId(sessionId)}__${sanitizeId(pageId)}.jpg")

    private fun pageFile(sessionId: String, pageId: String): File =
        File(sessionDir(sessionId), "${sanitizeId(pageId)}.enc")

    internal fun assembledFile(sessionId: String): File = File(sessionDir(sessionId), ASSEMBLED_FILE)

    override fun newSession(): String = UUID.randomUUID().toString()

    override suspend fun stagingPathFor(sessionId: String, pageId: String): String =
        withContext(Dispatchers.IO) { stagingFile(sessionId, pageId).absolutePath }

    /**
     * R2, the encrypt-when rule. The plaintext staging file is deleted in a `finally`, so it is
     * gone before this function returns on EVERY path - success, encryption failure, decode
     * failure, or a thrown cancellation. The next page cannot be captured until the ViewModel
     * observes this result, so at most one page of plaintext exists on disk at any instant, and
     * only for the duration of this call.
     */
    override suspend fun ingestPage(sessionId: String, pageId: String): Result<CapturedPage> =
        withContext(Dispatchers.IO) {
            val staging = stagingFile(sessionId, pageId)
            try {
                if (!staging.exists() || staging.length() == 0L) {
                    return@withContext Result.failure(IllegalStateException("The camera returned no image"))
                }
                // DocumentPageUnreadableException is deliberately NOT used here: it is the
                // assembly-abort signal (R4), and a page that fails at capture time simply never
                // enters the page list, so there is nothing to abort.
                val thumbnail = renderThumbnail(staging)
                    ?: return@withContext Result.failure(IllegalStateException("The captured image could not be read"))
                staging.inputStream().use { plaintext ->
                    encryptionProvider.encryptToFile(plaintext, pageFile(sessionId, pageId), MAX_DOCUMENT_SIZE_BYTES)
                }
                Result.success(CapturedPage(pageId, thumbnail))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pageFile(sessionId, pageId).delete()
                Result.failure(e)
            } finally {
                staging.delete()
            }
        }

    override suspend fun discardStaging(sessionId: String, pageId: String) {
        withContext(Dispatchers.IO) { stagingFile(sessionId, pageId).delete() }
    }

    override suspend fun deletePage(sessionId: String, pageId: String) {
        withContext(Dispatchers.IO) {
            pageFile(sessionId, pageId).delete()
            stagingFile(sessionId, pageId).delete()
        }
    }

    override suspend fun discardSession(sessionId: String) {
        withContext(Dispatchers.IO) {
            sessionDir(sessionId).deleteRecursively()
            // Staging files are named with the session prefix, so a capture the camera abandoned
            // mid-flight (no ingest, therefore no `finally` ran) is caught here too.
            stagingDir().listFiles()?.forEach { file ->
                if (file.name.startsWith("${sanitizeId(sessionId)}__")) file.delete()
            }
        }
    }

    /**
     * R3/R4. The whole assembly is one streamed encryption: [DocumentEncryptionProvider]
     * measures, hashes and encrypts the PDF as `PdfDocument.writeTo` pushes it, so the assembled
     * document never exists as a plaintext file and never as a whole `ByteArray`.
     *
     * **No page can be silently skipped.** The loop body has exactly one outcome per page:
     * [drawPage] either finishes the page or throws [DocumentPageUnreadableException]. There is
     * no `catch`, no `continue`, and no null-tolerant call inside the loop, so a page that cannot
     * be read propagates out of `writePlaintext`, out of `encryptToFile` (which deletes its own
     * partial destination on the way past), and lands in the single `catch` below, which deletes
     * the output again and returns a failure. Nothing reaches the metadata row, so an incomplete
     * assembly can never become a stored document that merely looks short.
     */
    override suspend fun assemble(
        sessionId: String,
        orderedPageIds: List<String>,
        onProgress: (Int, Int) -> Unit,
    ): Result<DocumentBytes.AssembledCapture> = withContext(Dispatchers.Default) {
        if (orderedPageIds.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("No pages to assemble"))
        }
        if (orderedPageIds.size > MAX_PAGES) {
            return@withContext Result.failure(IllegalArgumentException("At most $MAX_PAGES pages per document"))
        }
        val dest = assembledFile(sessionId)
        // Captured here, in the suspend frame, so the non-suspend PDF-writing lambda below can
        // still honour cancellation: DocumentEncryptionProvider's push-mode sink takes an
        // ordinary (OutputStream) -> Unit, not a suspend function.
        val callerContext = coroutineContext
        try {
            val encrypted = encryptionProvider.encryptToFile(dest, MAX_DOCUMENT_SIZE_BYTES) { sink ->
                writePdf(sessionId, orderedPageIds, sink, callerContext, onProgress)
            }
            Result.success(
                DocumentBytes.AssembledCapture(
                    captureSessionId = sessionId,
                    pageCount = orderedPageIds.size,
                    sizeBytes = encrypted.sizeBytes,
                    sha256 = encrypted.sha256,
                ),
            )
        } catch (e: CancellationException) {
            // R8: cancelling mid-assembly cleans up exactly as an abort does. Rethrown, never
            // swallowed into a Result, so the caller's coroutine still ends as cancelled.
            dest.delete()
            throw e
        } catch (e: Exception) {
            // Redundant with encryptToFile's own delete, and kept anyway: R4's "produce NO
            // document" guarantee should be readable here, at the abort site, without having to
            // trust a second file to have cleaned up after itself.
            dest.delete()
            Result.failure(e)
        }
    }

    private fun writePdf(
        sessionId: String,
        orderedPageIds: List<String>,
        sink: OutputStream,
        callerContext: CoroutineContext,
        onProgress: (Int, Int) -> Unit,
    ) {
        val document = PdfDocument()
        try {
            // Index order IS the worker's final reordered order: this list is the ViewModel's
            // ordered page list, read once, after every move and delete has been applied.
            orderedPageIds.forEachIndexed { index, pageId ->
                callerContext.ensureActive()
                drawPage(document, sessionId, pageId, index)
                onProgress(index + 1, orderedPageIds.size)
            }
            document.writeTo(sink)
        } finally {
            document.close()
        }
    }

    /**
     * One page in memory at a time: decrypt page N, decode it downscaled, start the page, draw,
     * finish the page, recycle, and only then return so page N+1 can begin. Nothing holds a
     * reference to the previous page's pixels. `finishPage` has already serialised the page into
     * the native document by the time `recycle()` runs, so peak heap is one downscaled bitmap
     * plus one page's compressed bytes, never N bitmaps.
     *
     * (`PdfDocument` does accumulate the finished pages' compressed content natively until
     * `writeTo`; that is inherent to the platform API, which has no incremental write, and is
     * what the page cap bounds.)
     */
    private fun drawPage(document: PdfDocument, sessionId: String, pageId: String, index: Int) {
        val encrypted = pageFile(sessionId, pageId)
        val plaintext = try {
            ByteArrayOutputStream().also { encryptionProvider.decryptToStream(encrypted, it) }.toByteArray()
        } catch (e: Exception) {
            // A page whose ciphertext fails GCM authentication, or whose file is missing, is
            // unreadable. It aborts; it is never dropped from the document.
            throw DocumentPageUnreadableException(index, pageId, e)
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(plaintext, 0, plaintext.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw DocumentPageUnreadableException(index, pageId)

        val options = BitmapFactory.Options().apply {
            inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, PAGE_MAX_DIMENSION)
        }
        val bitmap = BitmapFactory.decodeByteArray(plaintext, 0, plaintext.size, options)
            ?: throw DocumentPageUnreadableException(index, pageId)

        try {
            val page = document.startPage(
                PdfDocument.PageInfo.Builder(PAGE_WIDTH_PT, PAGE_HEIGHT_PT, index + 1).create(),
            )
            try {
                drawFitted(page.canvas, bitmap, exifRotationDegrees { ExifInterface(ByteArrayInputStream(plaintext)) })
            } finally {
                document.finishPage(page)
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** Fits the page onto the sheet with its aspect ratio preserved and centred. The camera's EXIF
     *  rotation is applied to the CANVAS rather than by rewriting the bitmap, so an upright page
     *  costs no second allocation - a sideways lab report is a legibility defect worth this much
     *  code and not one byte more. */
    private fun drawFitted(canvas: Canvas, bitmap: Bitmap, rotationDegrees: Int) {
        val quarterTurned = rotationDegrees == 90 || rotationDegrees == 270
        val drawWidth = if (quarterTurned) bitmap.height else bitmap.width
        val drawHeight = if (quarterTurned) bitmap.width else bitmap.height
        val scale = minOf(PAGE_WIDTH_PT / drawWidth.toFloat(), PAGE_HEIGHT_PT / drawHeight.toFloat())
        val scaledWidth = bitmap.width * scale
        val scaledHeight = bitmap.height * scale
        val centerX = PAGE_WIDTH_PT / 2f
        val centerY = PAGE_HEIGHT_PT / 2f

        canvas.save()
        if (rotationDegrees != 0) canvas.rotate(rotationDegrees.toFloat(), centerX, centerY)
        canvas.drawBitmap(
            bitmap,
            null,
            RectF(
                centerX - scaledWidth / 2f,
                centerY - scaledHeight / 2f,
                centerX + scaledWidth / 2f,
                centerY + scaledHeight / 2f,
            ),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
        )
        canvas.restore()
    }

    /**
     * The reader is constructed INSIDE the `try`, not passed in: every `ExifInterface` constructor
     * declares `IOException`, so building one at the call site would put the failure outside this
     * fallback and let unreadable EXIF fail a page whose pixels decoded fine.
     */
    private fun exifRotationDegrees(openExif: () -> ExifInterface): Int = try {
        when (openExif().getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (e: Exception) {
        // Missing or malformed EXIF is not a reason to fail a readable page - only an undecodable
        // image is (R4). Unrotated is the honest fallback.
        0
    }

    /** Small in-memory JPEG for the thumbnail strip, decoded from the staging file before it is
     *  deleted so a thumbnail never costs a second decrypt. Rotated by `Matrix` here rather than
     *  on a canvas because the result has to survive as bytes. */
    private fun renderThumbnail(staging: File): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(staging.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, THUMBNAIL_MAX_DIMENSION)
        }
        val decoded = BitmapFactory.decodeFile(staging.absolutePath, options) ?: return null
        val rotation = exifRotationDegrees { ExifInterface(staging.absolutePath) }
        val upright = if (rotation == 0) {
            decoded
        } else {
            val matrix = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                .also { if (it !== decoded) decoded.recycle() }
        }
        return try {
            ByteArrayOutputStream().also { upright.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_JPEG_QUALITY, it) }
                .toByteArray()
        } finally {
            upright.recycle()
        }
    }

    /** Session and page ids are app-generated UUIDs, so this can never actually change one. It is
     *  here because both become raw filesystem path components, and the same defense-in-depth rule
     *  the repository's `sanitize` follows applies: no value reaches a path unsanitised. */
    private fun sanitizeId(id: String): String = id.replace(Regex("[^A-Za-z0-9_-]"), "_")

    companion object {
        const val MAX_PAGES = 20
    }
}
