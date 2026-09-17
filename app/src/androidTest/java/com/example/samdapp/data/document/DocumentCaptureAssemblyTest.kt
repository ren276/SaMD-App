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
import com.example.samdapp.domain.document.OrderedPage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * H-18, Build 3b (Option A, in-process CameraX capture). The bytes-level guarantees of the
 * camera-to-PDF path, against the REAL Keystore, the REAL `BitmapFactory` and the REAL
 * `PdfDocument`/`PdfRenderer` - none of which exist on the plain JVM, which is why these are
 * instrumented rather than unit tests.
 *
 * The assertions that matter here are ABSENCES: no plaintext page survives a capture, no partial
 * document survives an abort, and no session directory survives an abandon. A test that only
 * checked the returned `Result` would pass against an implementation that left all three behind.
 *
 * Phase B1/B2 note (scratchpad/capture-process-death-memo.md): every fixture helper here was
 * rewritten for Option A - `stagingPathFor`/the old two-argument `ingestPage` no longer exist, so
 * this went beyond the rotation-parameter plumbing the brief anticipated. Three tests
 * (`ingestingAPageEncryptsItAndLeavesNoPlaintextBehind`, `afterAMultiPageCaptureNoPlaintextPageRemains`,
 * `abandoningACaptureSessionDeletesEveryPageAndTheDirectory`) had their bodies narrowed because the
 * scenario they exercised (a plaintext staging file mid-flight) no longer exists to exercise;
 * each keeps its original name and its original R2/R5 claim, and the removed half is now
 * impossible by construction rather than untested. Every other test changed only by wrapping its
 * page-id list in [ordered] for `assemble`'s new signature.
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

    /** A solid-colour page, so the assembled PDF's page order can be read back by sampling a
     *  pixel: page identity survives JPEG and PDF re-encoding as a hue, where a drawn number
     *  would not survive downscaling reliably. Returns JPEG bytes directly - Option A's whole
     *  point is that a captured frame never needs to touch disk before `ingestPage`. */
    private fun fixtureJpegBytes(color: Int, width: Int = 1200, height: Int = 1600): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        bitmap.eraseColor(color)
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        bitmap.recycle()
        return bytes
    }

    private fun ingest(sessionId: String, color: Int, rotationDegrees: Int = 0): String = runBlocking {
        val pageId = java.util.UUID.randomUUID().toString()
        assertTrue(store.ingestPage(sessionId, pageId, fixtureJpegBytes(color), rotationDegrees).isSuccess)
        pageId
    }

    /** `assemble`'s Option A signature takes rotation per page; every test below is about
     *  ordering, corruption or memory, not rotation, so this pins rotation at 0 uniformly rather
     *  than repeating `OrderedPage(id, 0)` at every call site. */
    private fun ordered(pageIds: List<String>): List<OrderedPage> = pageIds.map { OrderedPage(it, 0) }

    private fun decryptAssembledTo(sessionId: String): File {
        val out = File.createTempFile("assembled", ".pdf", context.cacheDir)
        out.outputStream().use { DocumentEncryptionProvider().decryptToStream(store.assembledFile(sessionId), it) }
        return out
    }

    /** The dominant primary channel of the page's centre pixel - the identity written by
     *  [fixtureJpegBytes], read back through JPEG, PDF and render round trips. */
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

    /** Option A narrowing: there is no staging file to assert gone any more - the frame arrives
     *  as an in-memory `ByteArray` and the encrypted page is what `ingestPage` produces from it
     *  directly. What survives from the original claim: the output is encrypted, not a readable
     *  JPEG. The stronger "no file anywhere" claim is [ingestPageWritesNothingUnderCacheDir]. */
    @Test
    fun ingestingAPageEncryptsItAndLeavesNoPlaintextBehind() = runBlocking<Unit> {
        val sessionId = newSession()
        val pageId = java.util.UUID.randomUUID().toString()

        val result = store.ingestPage(sessionId, pageId, fixtureJpegBytes(Color.RED), rotationDegrees = 0)

        assertTrue(result.isSuccess)
        val encrypted = File(captureDir(sessionId), "$pageId.enc")
        assertTrue("the encrypted page must exist", encrypted.exists())
        val header = encrypted.readBytes().copyOf(3)
        assertNotEquals(
            "the stored page must not be a readable JPEG",
            listOf<Byte>(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()),
            header.toList(),
        )
    }

    /** New for Option A (memo 5.7): the frame never touches disk unencrypted at all, so ingesting
     *  a page must create no file anywhere under `cacheDir`, staging directory or otherwise - a
     *  strictly stronger claim than the old "the ONE staging file is gone afterward". */
    @Test
    fun ingestPageWritesNothingUnderCacheDir() = runBlocking<Unit> {
        val sessionId = newSession()
        val before = context.cacheDir.listFiles()?.map { it.name }?.toSet().orEmpty()

        val pageId = ingest(sessionId, Color.RED)

        val after = context.cacheDir.listFiles()?.map { it.name }?.toSet().orEmpty()
        assertEquals("ingestPage must create no file under cacheDir: was $before, now $after", before, after)
        assertTrue(File(captureDir(sessionId), "$pageId.enc").exists())
    }

    /** R2's other half: only ever one page of plaintext, alive only for the duration of one
     *  `ingestPage` call, on the heap, never as a file (see [ingestPageWritesNothingUnderCacheDir]
     *  for the disk-side proof). After a full multi-page capture the session directory holds
     *  exactly one encrypted page per ingest and nothing else. */
    @Test
    fun afterAMultiPageCaptureNoPlaintextPageRemains() = runBlocking<Unit> {
        val sessionId = newSession()
        repeat(4) { ingest(sessionId, Color.RED) }

        assertEquals(4, captureDir(sessionId).listFiles()?.size)
    }

    // ── R5: abandoning discards everything ────────────────────────────────────────────────────

    /** Option A narrowing: the original test also simulated "a page the camera was mid-way
     *  through, which never reached ingest" as a stranded staging file. That window no longer
     *  exists to simulate - a captured frame is either ingested (and lands in the session
     *  directory, covered here) or its capture failed with no bytes produced at all
     *  (`onDocumentPageCaptureFailed`, which writes nothing anywhere). What survives: discarding a
     *  session deletes every page already ingested into it. */
    @Test
    fun abandoningACaptureSessionDeletesEveryPageAndTheDirectory() = runBlocking<Unit> {
        val sessionId = newSession()
        repeat(4) { ingest(sessionId, Color.GREEN) }

        store.discardSession(sessionId)

        assertFalse("the session directory must be gone", captureDir(sessionId).exists())
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

        val result = store.assemble(sessionId, ordered(pages)) { _, _ -> }

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

        val result = store.assemble(sessionId, ordered(pages)) { _, _ -> }

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

        val result = store.assemble(sessionId, ordered(listOf(good, notAnImage))) { _, _ -> }

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
        val result = store.assemble(sessionId, ordered(listOf(blue, red, green))) { _, _ -> }

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

    /** A four-quadrant fixture (distinct colour per corner), so a rotation can be verified by
     *  where each colour LANDS, not merely that assembly still succeeds with a non-zero rotation
     *  value. A solid-colour page (the [fixtureJpegBytes]/[dominantChannelOfPage] pair used
     *  elsewhere in this file) cannot distinguish "rotated correctly" from "rotated backwards" or
     *  "not rotated at all", since every sampled pixel is the same colour regardless. */
    /** SQUARE by default, deliberately: `drawFitted` fits a quarter-turned source using its
     *  SWAPPED width/height, so a non-square source draws at a different size (and therefore
     *  leaves a different margin) at 0/180 than it does at 90/270. A square's bounding box is
     *  rotation-invariant, so fixed sample fractions land on real content at every rotation. */
    private fun fixtureQuadrantJpegBytes(width: Int = 400, height: Int = 400): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint()
        val halfW = width / 2f
        val halfH = height / 2f
        paint.color = Color.RED; canvas.drawRect(0f, 0f, halfW, halfH, paint) // top-left
        paint.color = Color.GREEN; canvas.drawRect(halfW, 0f, width.toFloat(), halfH, paint) // top-right
        paint.color = Color.BLUE; canvas.drawRect(0f, halfH, halfW, height.toFloat(), paint) // bottom-left
        paint.color = Color.YELLOW; canvas.drawRect(halfW, halfH, width.toFloat(), height.toFloat(), paint) // bottom-right
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
        bitmap.recycle()
        return bytes
    }

    /** Which of the four fixture colours occupies each corner of the rendered page, sampled well
     *  inside each quadrant (not at the crossing point, where JPEG chroma subsampling and PDF
     *  scaling both blur the boundary). */
    private fun quadrantColors(pdf: File, index: Int): Map<String, Char> {
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                renderer.openPage(index).use { page ->
                    val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    fun colorAt(fx: Float, fy: Float): Char {
                        val x = (bitmap.width * fx).toInt().coerceIn(0, bitmap.width - 1)
                        val y = (bitmap.height * fy).toInt().coerceIn(0, bitmap.height - 1)
                        val pixel = bitmap.getPixel(x, y)
                        val r = Color.red(pixel)
                        val g = Color.green(pixel)
                        val b = Color.blue(pixel)
                        return when {
                            r > 180 && g > 180 && b < 100 -> 'Y'
                            r > 180 && g < 100 && b < 100 -> 'R'
                            r < 100 && g > 180 && b < 100 -> 'G'
                            r < 100 && g < 100 && b > 180 -> 'B'
                            else -> '?'
                        }
                    }
                    val result = mapOf(
                        "TL" to colorAt(0.2f, 0.2f),
                        "TR" to colorAt(0.8f, 0.2f),
                        "BL" to colorAt(0.2f, 0.8f),
                        "BR" to colorAt(0.8f, 0.8f),
                    )
                    bitmap.recycle()
                    return result
                }
            }
        }
    }

    /**
     * A1, the geometric half. `drawFitted`'s `canvas.rotate(rotationDegrees, centerX, centerY)`
     * (unchanged by this fix - only `drawPage`'s rotation SOURCE changed, from EXIF to the
     * CameraX-supplied parameter) rotates the page's coordinate frame clockwise for a positive
     * value; the pre-existing EXIF path already relied on this (`ORIENTATION_ROTATE_90` is
     * documented as "needs 90 degrees clockwise to display correctly", and the old code fed it
     * straight into this same `canvas.rotate` call). A clockwise rotation of a square cycles its
     * corners TL->TR->BR->BL for +90, and swaps opposite corners for +180 - both asserted here
     * against the real `Canvas`/`PdfRenderer`, not reasoned about in a comment.
     */
    @Test
    fun eachPageIsRotatedByItsOwnCaptureRotationNinety() = runBlocking<Unit> {
        val sessionId = newSession()
        val pageId = java.util.UUID.randomUUID().toString()
        assertTrue(store.ingestPage(sessionId, pageId, fixtureQuadrantJpegBytes(), rotationDegrees = 90).isSuccess)

        val result = store.assemble(sessionId, listOf(OrderedPage(pageId, 90))) { _, _ -> }

        assertTrue("a rotated page must still assemble: ${result.exceptionOrNull()}", result.isSuccess)
        val pdf = decryptAssembledTo(sessionId)
        try {
            val corners = quadrantColors(pdf, 0)
            assertEquals("TL after +90", 'B', corners.getValue("TL"))
            assertEquals("TR after +90", 'R', corners.getValue("TR"))
            assertEquals("BR after +90", 'G', corners.getValue("BR"))
            assertEquals("BL after +90", 'Y', corners.getValue("BL"))
        } finally {
            pdf.delete()
        }
    }

    @Test
    fun eachPageIsRotatedByItsOwnCaptureRotationOneEighty() = runBlocking<Unit> {
        val sessionId = newSession()
        val pageId = java.util.UUID.randomUUID().toString()
        assertTrue(store.ingestPage(sessionId, pageId, fixtureQuadrantJpegBytes(), rotationDegrees = 180).isSuccess)

        val result = store.assemble(sessionId, listOf(OrderedPage(pageId, 180))) { _, _ -> }

        assertTrue("a rotated page must still assemble: ${result.exceptionOrNull()}", result.isSuccess)
        val pdf = decryptAssembledTo(sessionId)
        try {
            val corners = quadrantColors(pdf, 0)
            assertEquals("TL after +180", 'Y', corners.getValue("TL"))
            assertEquals("TR after +180", 'B', corners.getValue("TR"))
            assertEquals("BR after +180", 'R', corners.getValue("BR"))
            assertEquals("BL after +180", 'G', corners.getValue("BL"))
        } finally {
            pdf.delete()
        }
    }

    @Test
    fun eachPageIsRotatedByItsOwnCaptureRotationTwoSeventy() = runBlocking<Unit> {
        val sessionId = newSession()
        val pageId = java.util.UUID.randomUUID().toString()
        assertTrue(store.ingestPage(sessionId, pageId, fixtureQuadrantJpegBytes(), rotationDegrees = 270).isSuccess)

        val result = store.assemble(sessionId, listOf(OrderedPage(pageId, 270))) { _, _ -> }

        assertTrue("a rotated page must still assemble: ${result.exceptionOrNull()}", result.isSuccess)
        val pdf = decryptAssembledTo(sessionId)
        try {
            val corners = quadrantColors(pdf, 0)
            assertEquals("TL after +270", 'G', corners.getValue("TL"))
            assertEquals("TR after +270", 'Y', corners.getValue("TR"))
            assertEquals("BR after +270", 'B', corners.getValue("BR"))
            assertEquals("BL after +270", 'R', corners.getValue("BL"))
        } finally {
            pdf.delete()
        }
    }

    /** Mixed rotations in one document - order and per-page rotation must not interfere with
     *  each other. Solid-colour pages here (not the quadrant fixture) since this test's job is
     *  page IDENTITY across a reorder, already proven safe by [pagesAppearInTheOrderTheWorkerFinishedWithNotTheOrderTheyWereCaptured];
     *  the geometry itself is covered per-rotation above. */
    @Test
    fun differentPagesInOneDocumentCanCarryDifferentRotations() = runBlocking<Unit> {
        val sessionId = newSession()
        val upright = ingest(sessionId, Color.RED, rotationDegrees = 0)
        val quarterTurned = ingest(sessionId, Color.GREEN, rotationDegrees = 90)
        val halfTurned = ingest(sessionId, Color.BLUE, rotationDegrees = 180)

        val result = store.assemble(
            sessionId,
            listOf(OrderedPage(upright, 0), OrderedPage(quarterTurned, 90), OrderedPage(halfTurned, 180)),
        ) { _, _ -> }

        assertTrue("mixed-rotation pages must still assemble: ${result.exceptionOrNull()}", result.isSuccess)
        val pdf = decryptAssembledTo(sessionId)
        try {
            assertEquals('R', dominantChannelOfPage(pdf, 0))
            assertEquals('G', dominantChannelOfPage(pdf, 1))
            assertEquals('B', dominantChannelOfPage(pdf, 2))
        } finally {
            pdf.delete()
        }
    }

    @Test
    fun progressIsReportedOncePerPageInOrder() = runBlocking<Unit> {
        val sessionId = newSession()
        val pages = List(3) { ingest(sessionId, Color.RED) }
        val progress = mutableListOf<Pair<Int, Int>>()

        assertTrue(store.assemble(sessionId, ordered(pages)) { done, total -> progress += done to total }.isSuccess)

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
     *
     * Option A simplifies the fixture setup slightly: the JPEG bytes are already in memory (no
     * staging file round trip needed before `ingestPage`), which is the architecture's own point.
     */
    @Test
    fun assemblingTheMaximumPageCountOfLargePagesDoesNotExhaustMemory() = runBlocking<Unit> {
        val sessionId = newSession()
        // One large fixture, reused across all pages, so the TEST's own allocation does not
        // become the thing under test.
        val jpeg = fixtureJpegBytes(Color.RED, width = 3264, height = 2448)

        val pages = List(store.maxPages) {
            val pageId = java.util.UUID.randomUUID().toString()
            assertTrue(store.ingestPage(sessionId, pageId, jpeg, rotationDegrees = 0).isSuccess)
            pageId
        }

        val result = store.assemble(sessionId, ordered(pages)) { _, _ -> }

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

        val result = store.assemble(sessionId, ordered(List(store.maxPages + 1) { page })) { _, _ -> }

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

        val assembled = store.assemble(sessionId, ordered(pages)) { _, _ -> }.getOrThrow()

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
