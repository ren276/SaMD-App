package com.example.samdapp.data.local.document

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Matrix
import android.graphics.pdf.PdfDocument
import com.example.samdapp.data.local.security.DocumentEncryptionProvider
import com.example.samdapp.data.repository.ConsultationDocumentRepositoryImpl.Companion.MAX_DOCUMENT_SIZE_BYTES
import com.example.samdapp.domain.document.CapturedPage
import com.example.samdapp.domain.document.DocumentBytes
import com.example.samdapp.domain.document.DocumentCaptureStore
import com.example.samdapp.domain.document.DocumentPageUnreadableException
import com.example.samdapp.domain.document.OrderedPage
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
 * H-18, Build 3b. Deletes every capture-session directory.
 *
 * **Policy: sweep everything, unconditionally, at app start.** A capture session's page list
 * lives only in `ConsultationViewModel` state, so it cannot survive process death; any session
 * directory that exists when the process starts is by definition orphaned, and there is no live
 * session for the sweep to damage. That makes an age heuristic or a liveness registry pointless
 * complexity here. Re-verified unchanged under Option A (in-process CameraX capture,
 * `scratchpad/capture-process-death-memo.md` section 1): Option A persists nothing new, so this
 * premise still holds exactly as before.
 *
 * Deliberately a SECOND, separate sweep rather than an extension of Build 3a's
 * [com.example.samdapp.presentation.documents.sweepOrphanedViewerTempFiles]. They cover disjoint
 * directories (`cacheDir/document_viewer_temp` there, `filesDir/documents/.capture` here) and
 * answer different questions, so merging them would only hide which one failed. Both are called
 * from `SaMDApplication.onCreate`.
 *
 * Only one directory as of Option A: capture no longer hands a plaintext staging file to an
 * external camera process, so there is no `cacheDir/document_capture_staging` for this to sweep.
 */
fun sweepOrphanedCaptureSessions(context: Context) {
    File(File(context.filesDir, "documents"), CAPTURE_DIR).deleteRecursively()
}

@Singleton
class AndroidDocumentCaptureStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val encryptionProvider: DocumentEncryptionProvider,
) : DocumentCaptureStore {

    override val maxPages: Int = MAX_PAGES

    private fun sessionDir(sessionId: String): File =
        File(File(File(context.filesDir, "documents"), CAPTURE_DIR), sanitizeId(sessionId)).apply { mkdirs() }

    private fun pageFile(sessionId: String, pageId: String): File =
        File(sessionDir(sessionId), "${sanitizeId(pageId)}.enc")

    internal fun assembledFile(sessionId: String): File = File(sessionDir(sessionId), ASSEMBLED_FILE)

    override fun newSession(): String = UUID.randomUUID().toString()

    /**
     * R2, the encrypt-when rule, Option A shape. [jpegBytes] is the frame CameraX's
     * `OnImageCapturedCallback` delivered, already copied out of its `ImageProxy` by the caller
     * (which owns closing that proxy); nothing here retains a reference to [jpegBytes] past this
     * call; encryption happens through the same [ByteArrayInputStream]-into-`encryptToFile` path
     * the old staging-file overload used, so the bytes never touch disk unencrypted. The caller
     * zeroes [jpegBytes] once this returns - the residual plaintext window is now bounded to one
     * heap array for the duration of one encrypt call, never a file.
     */
    override suspend fun ingestPage(
        sessionId: String,
        pageId: String,
        jpegBytes: ByteArray,
        rotationDegrees: Int,
    ): Result<CapturedPage> =
        withContext(Dispatchers.IO) {
            try {
                if (jpegBytes.isEmpty()) {
                    return@withContext Result.failure(IllegalStateException("The camera returned no image"))
                }
                // DocumentPageUnreadableException is deliberately NOT used here: it is the
                // assembly-abort signal (R4), and a page that fails at capture time simply never
                // enters the page list, so there is nothing to abort.
                val thumbnail = renderThumbnail(jpegBytes, rotationDegrees)
                    ?: return@withContext Result.failure(IllegalStateException("The captured image could not be read"))
                ByteArrayInputStream(jpegBytes).use { plaintext ->
                    encryptionProvider.encryptToFile(plaintext, pageFile(sessionId, pageId), MAX_DOCUMENT_SIZE_BYTES)
                }
                Result.success(CapturedPage(pageId, thumbnail, rotationDegrees))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pageFile(sessionId, pageId).delete()
                Result.failure(e)
            }
        }

    override suspend fun deletePage(sessionId: String, pageId: String) {
        withContext(Dispatchers.IO) { pageFile(sessionId, pageId).delete() }
    }

    override suspend fun discardSession(sessionId: String) {
        withContext(Dispatchers.IO) { sessionDir(sessionId).deleteRecursively() }
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
        orderedPages: List<OrderedPage>,
        onProgress: (Int, Int) -> Unit,
    ): Result<DocumentBytes.AssembledCapture> = withContext(Dispatchers.Default) {
        if (orderedPages.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("No pages to assemble"))
        }
        if (orderedPages.size > MAX_PAGES) {
            return@withContext Result.failure(IllegalArgumentException("At most $MAX_PAGES pages per document"))
        }
        val dest = assembledFile(sessionId)
        // Captured here, in the suspend frame, so the non-suspend PDF-writing lambda below can
        // still honour cancellation: DocumentEncryptionProvider's push-mode sink takes an
        // ordinary (OutputStream) -> Unit, not a suspend function.
        val callerContext = coroutineContext
        try {
            val encrypted = encryptionProvider.encryptToFile(dest, MAX_DOCUMENT_SIZE_BYTES) { sink ->
                writePdf(sessionId, orderedPages, sink, callerContext, onProgress)
            }
            Result.success(
                DocumentBytes.AssembledCapture(
                    captureSessionId = sessionId,
                    pageCount = orderedPages.size,
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
        orderedPages: List<OrderedPage>,
        sink: OutputStream,
        callerContext: CoroutineContext,
        onProgress: (Int, Int) -> Unit,
    ) {
        val document = PdfDocument()
        try {
            // Index order IS the worker's final reordered order: this list is the ViewModel's
            // ordered page list, read once, after every move and delete has been applied.
            orderedPages.forEachIndexed { index, page ->
                callerContext.ensureActive()
                drawPage(document, sessionId, page.pageId, page.rotationDegrees, index)
                onProgress(index + 1, orderedPages.size)
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
    private fun drawPage(document: PdfDocument, sessionId: String, pageId: String, rotationDegrees: Int, index: Int) {
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
                // A1: rotation is CameraX's own ImageInfo.rotationDegrees, carried by the caller
                // from capture through to here (OrderedPage.rotationDegrees) - not re-derived from
                // EXIF. See the isolated diff in the Phase B1 report for why this replaced the old
                // ExifInterface(ByteArrayInputStream(plaintext)) read.
                drawFitted(page.canvas, bitmap, rotationDegrees)
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

    /** Small in-memory JPEG for the thumbnail strip, decoded from the just-captured frame before
     *  it is encrypted, so a thumbnail never costs a second decrypt. [rotationDegrees] is CameraX's
     *  own `ImageInfo.rotationDegrees` (A1) - not read from EXIF, since a raw `ImageProxy` capture
     *  is not guaranteed to carry it the way a saved-to-disk JPEG would. Rotated by `Matrix` here
     *  rather than on a canvas because the result has to survive as bytes. */
    private fun renderThumbnail(jpegBytes: ByteArray, rotationDegrees: Int): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, THUMBNAIL_MAX_DIMENSION)
        }
        val decoded = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, options) ?: return null
        val upright = if (rotationDegrees == 0) {
            decoded
        } else {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
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
