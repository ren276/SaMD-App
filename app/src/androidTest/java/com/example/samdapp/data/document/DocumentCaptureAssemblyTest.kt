package com.example.samdapp.data.document

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import com.example.samdapp.data.local.document.AndroidDocumentCaptureStore
import com.example.samdapp.data.local.document.sweepOrphanedCaptureSessions
import com.example.samdapp.data.local.security.DocumentEncryptionProvider
import com.example.samdapp.domain.document.DocumentBytes
import com.example.samdapp.domain.document.DocumentPageUnreadableException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * H-18, Build 3b. The bytes-level guarantees of the camera-to-PDF path, against the REAL Keystore,
 * the REAL `BitmapFactory` and the REAL `PdfDocument`/`PdfRenderer` - none of which exist on the
 * plain JVM, which is why these are instrumented rather than unit tests.
 *
 * The assertions that matter here are ABSENCES: no plaintext page survives a capture, no partial
 * document survives an abort, and no session directory survives an abandon. A test that only
 * checked the returned `Result` would pass against an implementation that left all three behind.
 */
class DocumentCaptureAssemblyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: AndroidDocumentCaptureStore
    private val sessions = mutableListOf<String>()

    @Before
    fun setUp() {
        store = AndroidDocumentCaptureStore(context, DocumentEncryptionProvider())
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        sessions.forEach { store.discardSession(it) }
        sweepOrphanedCaptureSessions(context)
    }

    private fun newSession(): String = store.newSession().also { sessions += it }

    private fun captureDir(sessionId: String) =
        File(File(File(context.filesDir, "documents"), ".capture"), sessionId)

    private fun stagingDir() = File(context.cacheDir, "document_capture_staging")

    /** A solid-colour page, so the assembled PDF's page order can be read back by sampling a
     *  pixel: page identity survives JPEG and PDF re-encoding as a hue, where a drawn number
     *  would not survive downscaling reliably. */
    private fun writeStagingPage(sessionId: String, color: Int, width: Int = 1200, height: Int = 1600): String =
        runBlocking {
            val pageId = java.util.UUID.randomUUID().toString()
            val path = store.stagingPathFor(sessionId, pageId)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            bitmap.eraseColor(color)
            File(path).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bitmap.recycle()
            pageId
        }

    private fun ingest(sessionId: String, color: Int): String = runBlocking {
        val pageId = writeStagingPage(sessionId, color)
        assertTrue(store.ingestPage(sessionId, pageId).isSuccess)
        pageId
    }

    private fun decryptAssembledTo(sessionId: String): File {
        val out = File.createTempFile("assembled", ".pdf", context.cacheDir)
        out.outputStream().use { DocumentEncryptionProvider().decryptToStream(store.assembledFile(sessionId), it) }
        return out
    }

    /** The dominant primary channel of the page's centre pixel - the identity written by
     *  [writeStagingPage], read back through JPEG, PDF and render round trips. */
    private fun dominantChannelOfPage(pdf: File, index: Int): Char {
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                renderer.openPage(index).use { page ->
                    val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                    bitmap.recycle()
                    val r = Color.red(pixel)
                    val g = Color.green(pixel)
                    val b = Color.blue(pixel)
                    return when (maxOf(r, g, b)) {
                        r -> 'R'
                        g -> 'G'
                        else -> 'B'
                    }
                }
            }
        }
    }

    // ── R2: encrypt as captured ───────────────────────────────────────────────────────────────

    /** The plaintext staging file must be gone by the time `ingestPage` returns, and what remains
     *  must not be a readable image. */
    @Test
    fun ingestingAPageEncryptsItAndLeavesNoPlaintextBehind() = runBlocking<Unit> {
        val sessionId = newSession()
        val pageId = writeStagingPage(sessionId, Color.RED)
        val stagingPath = store.stagingPathFor(sessionId, pageId)
        assertTrue("fixture precondition: the staging file exists", File(stagingPath).exists())

        val result = store.ingestPage(sessionId, pageId)

        assertTrue(result.isSuccess)
        assertFalse("the plaintext staging file must not survive ingest", File(stagingPath).exists())
        val encrypted = File(captureDir(sessionId), "$pageId.enc")
        assertTrue("the encrypted page must exist", encrypted.exists())
        val header = encrypted.readBytes().copyOf(3)
        assertNotEquals(
            "the stored page must not be a readable JPEG",
            listOf<Byte>(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()),
            header.toList(),
        )
    }

    /** R2's other half: only ever ONE page of plaintext, and only during the ingest call. After a
     *  full multi-page capture the staging directory holds nothing for this session. */
    @Test
    fun afterAMultiPageCaptureNoPlaintextPageRemains() = runBlocking<Unit> {
        val sessionId = newSession()
        repeat(4) { ingest(sessionId, Color.RED) }

        val stranded = stagingDir().listFiles()?.filter { it.name.startsWith(sessionId) }.orEmpty()

        assertTrue("no plaintext page may survive the capture loop: $stranded", stranded.isEmpty())
        assertEquals(4, captureDir(sessionId).listFiles()?.size)
    }

    // ── R5: abandoning discards everything ────────────────────────────────────────────────────

    @Test
    fun abandoningACaptureSessionDeletesEveryPageAndTheDirectory() = runBlocking<Unit> {
        val sessionId = newSession()
        repeat(4) { ingest(sessionId, Color.GREEN) }
        // A page the camera was mid-way through, which never reached ingest and so never ran the
        // delete-in-finally: the abandon path has to collect this too.
        val abandonedPageId = writeStagingPage(sessionId, Color.GREEN)
        assertTrue(File(store.stagingPathFor(sessionId, abandonedPageId)).exists())

        store.discardSession(sessionId)

        assertFalse("the session directory must be gone", captureDir(sessionId).exists())
        assertTrue(
            "no staging file may survive an abandoned capture",
            stagingDir().listFiles()?.none { it.name.startsWith(sessionId) } ?: true,
        )
    }

    // ── R6: the startup sweep ─────────────────────────────────────────────────────────────────

    /** The sweep must reach every orphaned capture session and NOTHING else - in particular not
     *  the stored documents that live one directory up, under the same `filesDir/documents` root.
     */
    @Test
    fun theStartupSweepClearsCaptureSessionsButNeverStoredDocuments() = runBlocking<Unit> {
        val sessionId = newSession()
        repeat(2) { ingest(sessionId, Color.BLUE) }
        val storedDocument = File(File(File(context.filesDir, "documents"), "consultation-1"), "LAB_REPORT_1_x.pdf")
        storedDocument.parentFile?.mkdirs()
        storedDocument.writeBytes(byteArrayOf(1, 2, 3))

        sweepOrphanedCaptureSessions(context)

        assertFalse("an orphaned capture session must be swept", captureDir(sessionId).exists())
        assertTrue("a stored document must survive the sweep", storedDocument.exists())
        storedDocument.parentFile?.deleteRecursively()
    }

    // ── R4: an unreadable page aborts the WHOLE assembly ──────────────────────────────────────

    /** The hazard this exists for: a lab report whose page 3 is silently missing, with nothing to
     *  tell the reader it ever existed. The assembly must abort, not shorten. */
    @Test
    fun aCorruptedPageAbortsTheAssemblyAndProducesNoDocument() = runBlocking<Unit> {
        val sessionId = newSession()
        val pages = listOf(ingest(sessionId, Color.RED), ingest(sessionId, Color.GREEN), ingest(sessionId, Color.BLUE))
        // Tamper with the middle page's ciphertext: GCM authentication now fails on it.
        val victim = File(captureDir(sessionId), "${pages[1]}.enc")
        val bytes = victim.readBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte()
        victim.writeBytes(bytes)

        val result = store.assemble(sessionId, pages) { _, _ -> }

        assertTrue("an unreadable page must abort the assembly", result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue("the abort must name the page: $error", error is DocumentPageUnreadableException)
        assertEquals(1, (error as DocumentPageUnreadableException).pageIndex)
        assertFalse("no partial document may survive the abort", store.assembledFile(sessionId).exists())
    }

    @Test
    fun aMissingPageFileAbortsTheAssemblyRatherThanShorteningTheDocument() = runBlocking<Unit> {
        val sessionId = newSession()
        val pages = listOf(ingest(sessionId, Color.RED), ingest(sessionId, Color.GREEN), ingest(sessionId, Color.BLUE))
        assertTrue(File(captureDir(sessionId), "${pages[2]}.enc").delete())

        val result = store.assemble(sessionId, pages) { _, _ -> }

        assertTrue(result.isFailure)
        assertEquals(2, (result.exceptionOrNull() as DocumentPageUnreadableException).pageIndex)
        assertFalse(store.assembledFile(sessionId).exists())
    }

    /** A page that decrypts cleanly but is not an image at all - the other half of "unreadable".
     *  Encrypted garbage passes GCM and still must not be skipped. */
    @Test
    fun anUndecodablePageAbortsEvenThoughItsCiphertextIsIntact() = runBlocking<Unit> {
        val sessionId = newSession()
        val good = ingest(sessionId, Color.RED)
        val notAnImage = java.util.UUID.randomUUID().toString()
        DocumentEncryptionProvider().encryptToFile(
            "this is not an image".byteInputStream(),
            File(captureDir(sessionId), "$notAnImage.enc"),
            1024L,
        )

        val result = store.assemble(sessionId, listOf(good, notAnImage)) { _, _ -> }

        assertTrue(result.isFailure)
        assertEquals(1, (result.exceptionOrNull() as DocumentPageUnreadableException).pageIndex)
        assertFalse(store.assembledFile(sessionId).exists())
    }

    // ── R7: the worker's final order is the document's order ──────────────────────────────────

    @Test
    fun pagesAppearInTheOrderTheWorkerFinishedWithNotTheOrderTheyWereCaptured() = runBlocking<Unit> {
        val sessionId = newSession()
        val red = ingest(sessionId, Color.RED)
        val green = ingest(sessionId, Color.GREEN)
        val blue = ingest(sessionId, Color.BLUE)

        // The worker reorders to blue, red, green before tapping done.
        val result = store.assemble(sessionId, listOf(blue, red, green)) { _, _ -> }

        assertTrue(result.isSuccess)
        val pdf = decryptAssembledTo(sessionId)
        try {
            assertEquals('B', dominantChannelOfPage(pdf, 0))
            assertEquals('R', dominantChannelOfPage(pdf, 1))
            assertEquals('G', dominantChannelOfPage(pdf, 2))
        } finally {
            pdf.delete()
        }
    }

    @Test
    fun progressIsReportedOncePerPageInOrder() = runBlocking<Unit> {
        val sessionId = newSession()
        val pages = List(3) { ingest(sessionId, Color.RED) }
        val progress = mutableListOf<Pair<Int, Int>>()

        assertTrue(store.assemble(sessionId, pages) { done, total -> progress += done to total }.isSuccess)

        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), progress)
    }

    // ── R3: the page cap, at full size, must not exhaust memory ───────────────────────────────

    /**
     * The field-failure mode, run for real: twenty 8 MP pages, the documented maximum, assembled
     * on-device. Full-resolution decoding would allocate ~32 MB per page here; the assertion is
     * simply that it completes, because an implementation that held even a few undownscaled pages
     * at once would die with an `OutOfMemoryError` before reaching the end.
     *
     * A peak-heap threshold assertion was considered and rejected: it would be a flake on a shared
     * emulator, not a control. The deterministic half of R3 is
     * `ImageDownscaleTest`, which pins the sampling arithmetic this depends on.
     */
    @Test
    fun assemblingTheMaximumPageCountOfLargePagesDoesNotExhaustMemory() = runBlocking<Unit> {
        val sessionId = newSession()
        // One large fixture bitmap, compressed once and reused, so the TEST's own allocation does
        // not become the thing under test.
        val fixture = Bitmap.createBitmap(3264, 2448, Bitmap.Config.RGB_565).apply { eraseColor(Color.RED) }
        val jpeg = java.io.ByteArrayOutputStream()
            .also { fixture.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            .toByteArray()
        fixture.recycle()

        val pages = List(store.maxPages) {
            val pageId = java.util.UUID.randomUUID().toString()
            File(store.stagingPathFor(sessionId, pageId)).writeBytes(jpeg)
            assertTrue(store.ingestPage(sessionId, pageId).isSuccess)
            pageId
        }

        val result = store.assemble(sessionId, pages) { _, _ -> }

        assertTrue("a full-cap assembly must complete: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals(store.maxPages, result.getOrThrow().pageCount)
        val pdf = decryptAssembledTo(sessionId)
        try {
            ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { assertEquals(store.maxPages, it.pageCount) }
            }
        } finally {
            pdf.delete()
        }
    }

    @Test
    fun aPageCountOverTheCapIsRefusedOutright() = runBlocking<Unit> {
        val sessionId = newSession()
        val page = ingest(sessionId, Color.RED)

        val result = store.assemble(sessionId, List(store.maxPages + 1) { page }) { _, _ -> }

        assertTrue(result.isFailure)
        assertFalse(store.assembledFile(sessionId).exists())
    }

    @Test
    fun anEmptyPageListProducesNoDocument() = runBlocking<Unit> {
        val sessionId = newSession()

        assertTrue(store.assemble(sessionId, emptyList()) { _, _ -> }.isFailure)
        assertFalse(store.assembledFile(sessionId).exists())
    }

    /** The measurements the metadata row and the audit payload will carry are of the PLAINTEXT
     *  assembled PDF, measured in the same pass that encrypted it. */
    @Test
    fun theAssembledResultMeasuresThePlaintextPdfNotTheCiphertext() = runBlocking<Unit> {
        val sessionId = newSession()
        val pages = List(2) { ingest(sessionId, Color.RED) }

        val assembled = store.assemble(sessionId, pages) { _, _ -> }.getOrThrow()

        val pdf = decryptAssembledTo(sessionId)
        try {
            assertEquals(pdf.length(), assembled.sizeBytes)
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(pdf.readBytes())
            assertEquals(digest.joinToString("") { "%02x".format(it) }, assembled.sha256)
            assertEquals(2, (assembled as DocumentBytes.AssembledCapture).pageCount)
        } finally {
            pdf.delete()
        }
    }
}
