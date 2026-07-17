package com.example.samdapp.presentation.report

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import com.example.samdapp.domain.model.AttachmentType
import com.example.samdapp.domain.model.MeasurementType
import com.example.samdapp.domain.report.ClinicalReport
import com.example.samdapp.domain.report.ReportAttachmentEntry
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The single source of truth for report layout (REQ-RPT-02). Draws a [ClinicalReport] onto a plain
 * [android.graphics.Canvas], so the SAME code renders the in-app Compose preview
 * (`drawIntoCanvas { render(it.nativeCanvas, ...) }`) and each page of the exported
 * [android.graphics.pdf.PdfDocument] — no bitmap capture, no divergent layouts.
 *
 * Coordinate space is A5 points (420 × 595, 1pt = 1/72"), matching the PDF page box; the preview
 * scales this space to its view width. Content is laid out as a list of atomic [Block]s that are
 * packed onto pages and never split mid-block — that's the "paginate at section boundaries"
 * guarantee. The legal footer is pinned to the bottom of the last page.
 *
 * Emulates a standard AIIMS outpatient card: header metadata (logo slot / centre title+CR No /
 * UID barcode), a two-column demographic matrix under a divider, the clinical summary + Rx/Advice
 * block, and the consent + physician-verification footer.
 *
 * [logoBitmap] and [imageLoader] are supplied by the caller (`ReportScreen`/`ReportPdfExporter`)
 * because decoding either a drawable resource or a content:// attachment URI needs a `Context`,
 * which this renderer deliberately doesn't hold — it stays plain `android.graphics.*` so the same
 * instance can be constructed once and reused for both the preview and the PDF export.
 */
class ReportCanvasRenderer(
    private val logoBitmap: Bitmap? = null,
    private val imageLoader: (String) -> Bitmap? = { null },
) {

    companion object {
        const val PAGE_WIDTH = 420f
        const val PAGE_HEIGHT = 595f
        private const val MARGIN = 26f
        private const val CONTENT_WIDTH = PAGE_WIDTH - 2 * MARGIN
        private val DATE_FMT = DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm", Locale.ENGLISH)
    }

    private val titlePaint = paint(11f, bold = true)
    private val phcPaint = paint(8.5f, bold = true)
    private val metaPaint = paint(7f, color = Color.DKGRAY)
    private val sectionPaint = paint(9.5f, bold = true)
    private val labelPaint = paint(7f, bold = true, color = Color.DKGRAY)
    private val bodyPaint = paint(8f)
    private val quotePaint = paint(8.5f, italic = true)
    private val smallPaint = paint(6.5f, color = Color.DKGRAY)
    private val rxPaint = paint(12f, bold = true)
    private val linePaint = Paint().apply { color = Color.DKGRAY; strokeWidth = 0.7f; isAntiAlias = true }
    private val boxPaint = Paint().apply {
        color = Color.LTGRAY; style = Paint.Style.STROKE; strokeWidth = 0.7f; isAntiAlias = true
    }
    private val barPaint = Paint().apply { color = Color.BLACK; isAntiAlias = false }
    private val verifiedPaint = paint(6f, bold = true, color = Color.rgb(0x1B, 0x5E, 0x20))

    private fun paint(size: Float, bold: Boolean = false, italic: Boolean = false, color: Int = Color.BLACK) =
        Paint().apply {
            this.color = color
            textSize = size
            isAntiAlias = true
            typeface = Typeface.create(
                Typeface.DEFAULT,
                when {
                    bold && italic -> Typeface.BOLD_ITALIC
                    bold -> Typeface.BOLD
                    italic -> Typeface.ITALIC
                    else -> Typeface.NORMAL
                },
            )
        }

    private fun lineHeight(p: Paint): Float = (p.descent() - p.ascent()) * 1.12f

    private data class Block(val height: Float, val draw: (Canvas, Float) -> Unit)

    /** Number of pages [report] occupies at A5. Call [drawPage] once per index. */
    fun pageCount(report: ClinicalReport): Int = paginate(report).size

    fun drawPage(canvas: Canvas, report: ClinicalReport, pageIndex: Int) {
        canvas.drawColor(Color.WHITE)
        val pages = paginate(report)
        val page = pages.getOrNull(pageIndex) ?: return
        val isLast = pageIndex == pages.size - 1

        var y = MARGIN
        if (pageIndex > 0) y = drawRunningHeader(canvas, report)
        page.forEach { block ->
            block.draw(canvas, y)
            y += block.height
        }
        if (isLast) drawFooter(canvas, report)

        // Page x-of-n marker, bottom center.
        val marker = "Page ${pageIndex + 1} of ${pages.size}"
        canvas.drawText(marker, (PAGE_WIDTH - smallPaint.measureText(marker)) / 2f, PAGE_HEIGHT - 8f, smallPaint)
    }

    // ---- pagination -------------------------------------------------------

    private fun paginate(report: ClinicalReport): List<List<Block>> {
        val blocks = buildBlocks(report)
        val footerReserve = footerHeight(report) + 12f
        val bottom = PAGE_HEIGHT - MARGIN
        val pages = mutableListOf<MutableList<Block>>()
        var current = mutableListOf<Block>()
        var y = MARGIN
        blocks.forEachIndexed { index, block ->
            val isLastBlock = index == blocks.lastIndex
            // Reserve footer room only when placing content on what may become the last page.
            val limit = bottom - if (isLastBlock) footerReserve else 0f
            if (y + block.height > limit && current.isNotEmpty()) {
                pages.add(current)
                current = mutableListOf()
                y = MARGIN
            }
            current.add(block)
            y += block.height
        }
        pages.add(current)
        return pages
    }

    // ---- block construction ----------------------------------------------

    private fun buildBlocks(report: ClinicalReport): List<Block> = buildList {
        add(headerBlock(report))
        add(demographicBlock(report))
        add(gap(6f))
        add(sectionHeaderBlock("Chief Complaints & Clinical Findings"))
        add(complaintBlock(report))
        addAll(ailmentBlocks(report))
        if (report.vitals.isNotEmpty()) {
            add(gap(4f))
            add(sectionHeaderBlock("Vitals"))
            add(vitalsBlock(report))
        }
        if (report.attachments.isNotEmpty()) {
            add(gap(4f))
            add(sectionHeaderBlock("Attachments"))
            addAll(attachmentBlocks(report))
        }
        report.kernelOutput?.let {
            add(gap(4f))
            add(sectionHeaderBlock("Kernel AI Assessment (model ${it.modelVersion})"))
            add(kernelBlock(report))
        }
        if (report.prescription.isNotEmpty() || report.diagnosis != null) {
            add(gap(6f))
            add(rxBlock(report))
        }
    }

    private fun gap(h: Float) = Block(h) { _, _ -> }

    private fun headerBlock(report: ClinicalReport): Block = Block(74f) { c, top ->
        // Top-left: institutional logo slot. Falls back to a bordered "LOGO" placeholder box if
        // no bitmap was supplied (e.g. decode failed) — never a blank gap.
        val logo = logoBitmap
        if (logo != null) {
            val dst = RectF(MARGIN, top, MARGIN + 46f, top + 46f)
            c.drawBitmap(logo, null, dst, null)
        } else {
            c.drawRect(MARGIN, top, MARGIN + 46f, top + 46f, boxPaint)
            drawCentered(c, "LOGO", MARGIN, MARGIN + 46f, top + 27f, smallPaint)
        }

        // Top-center: system title, PHC name, CR No.
        val centerLeft = MARGIN + 54f
        val centerRight = PAGE_WIDTH - MARGIN - 128f
        var ty = top + titlePaint.textSize
        wrap("PRIMARY HEALTH CENTER DIGITAL HEALTH SYSTEM", titlePaint, centerRight - centerLeft).forEach {
            drawCentered(c, it, centerLeft, centerRight, ty, titlePaint); ty += lineHeight(titlePaint)
        }
        drawCentered(c, report.header.phcName, centerLeft, centerRight, ty + 2f, phcPaint)
        ty += lineHeight(phcPaint) + 4f
        drawCentered(c, "CR No: ${report.header.consultationRecordNo}", centerLeft, centerRight, ty, metaPaint)

        // Top-right: barcode of the Patient UID + human-readable UID beneath.
        val barcodeRight = PAGE_WIDTH - MARGIN
        val barcodeLeft = barcodeRight - 118f
        drawBarcode(c, report.header.patientUid, barcodeLeft, top + 2f, 118f, 30f)
        drawCentered(c, "UID: ${report.header.patientUid}", barcodeLeft, barcodeRight, top + 42f, smallPaint)
    }

    private fun demographicBlock(report: ClinicalReport): Block {
        val p = report.patient
        val leftRows = buildList {
            add("Patient" to p.fullName)
            if (p.guardianName != null) add((p.guardianRelation ?: "Guardian") to p.guardianName)
            p.address?.let { add("Address" to it) }
            p.mobileNumber?.let { add("Mobile" to it) }
            p.category?.let { add("Category" to it) }
        }
        val rightRows = buildList {
            add("Age / Sex" to p.ageSex)
            add("Visit" to report.header.visitDateTime.atZone(ZoneId.systemDefault()).format(DATE_FMT))
            if (p.abhaNumberFormatted != null) add("ABHA No." to p.abhaNumberFormatted)
            if (p.abhaAddress != null) add("ABHA Address" to p.abhaAddress)
            // Encounter Information (Part A addendum): device/app identity that produced the AI
            // section below — captured for the report artifact, never shown in any in-app UI.
            report.kernelOutput?.let { k ->
                add("Device" to k.deviceId)
                add("App version" to k.softwareVersion)
            }
        }
        val rowH = lineHeight(bodyPaint) + 3f
        val abhaTagRows = if (p.abhaVerified) 1 else 0
        val rows = maxOf(leftRows.size, rightRows.size) + abhaTagRows
        val height = rows * rowH + 8f
        return Block(height) { c, top ->
            val colGap = 10f
            val colWidth = (CONTENT_WIDTH - colGap) / 2f
            drawFieldColumn(c, leftRows, MARGIN, top + rowH, colWidth, rowH)
            var ry = top + rowH
            rightRows.forEach { (label, value) ->
                drawField(c, label, value, MARGIN + colWidth + colGap, ry, colWidth); ry += rowH
            }
            if (p.abhaVerified) {
                c.drawText("✓ Verified via ABHA", MARGIN + colWidth + colGap, ry, verifiedPaint)
            }
            c.drawLine(MARGIN, top + height - 3f, PAGE_WIDTH - MARGIN, top + height - 3f, linePaint)
        }
    }

    private fun sectionHeaderBlock(title: String) = Block(lineHeight(sectionPaint) + 4f) { c, top ->
        c.drawText(title, MARGIN, top + sectionPaint.textSize, sectionPaint)
    }

    private fun complaintBlock(report: ClinicalReport): Block {
        val text = "“${report.chiefComplaintVerbatim.ifBlank { "—" }}”"
        val lines = wrap(text, quotePaint, CONTENT_WIDTH)
        return Block(lines.size * lineHeight(quotePaint) + 4f) { c, top ->
            var ty = top + quotePaint.textSize
            lines.forEach { c.drawText(it, MARGIN, ty, quotePaint); ty += lineHeight(quotePaint) }
        }
    }

    private fun ailmentBlocks(report: ClinicalReport): List<Block> = report.ailments.map { line ->
        val prefix = if (line.measurementType == MeasurementType.MEASURABLE) "◆" else "•"
        val text = when {
            line.isRedacted -> "🔒 Private entry (hidden from health worker)"
            line.detail != null -> "$prefix ${line.description}  —  ${line.detail}"
            else -> "$prefix ${line.description}"
        }
        val paint = if (line.isRedacted) smallPaint else bodyPaint
        val lines = wrap(text, paint, CONTENT_WIDTH - 6f)
        Block(lines.size * lineHeight(paint) + 2f) { c, top ->
            var ty = top + paint.textSize
            lines.forEach { c.drawText(it, MARGIN + 6f, ty, paint); ty += lineHeight(paint) }
        }
    }

    /**
     * Same "pass through unmodified" posture as [com.example.samdapp.domain.model.KernelPayload]'s
     * attachment handling — every consultation attachment appears here, image types rendered
     * inline, audio/video listed by label (a static canvas/PDF page can't play either back).
     */
    private fun attachmentBlocks(report: ClinicalReport): List<Block> = report.attachments.map { attachment ->
        when (attachment.type) {
            AttachmentType.IMAGE, AttachmentType.AFFECTED_AREA_PHOTO -> imageBlock(attachment)
            AttachmentType.AUDIO -> mediaLineBlock("🎤 ${attachment.label}")
            AttachmentType.VIDEO -> mediaLineBlock("🎥 ${attachment.label}")
        }
    }

    private fun imageBlock(attachment: ReportAttachmentEntry): Block {
        val boxWidth = 140f
        val boxHeight = 105f
        val height = boxHeight + lineHeight(smallPaint) + 4f
        return Block(height) { c, top ->
            val bitmap = imageLoader(attachment.uri)
            if (bitmap != null) {
                val src = Rect(0, 0, bitmap.width, bitmap.height)
                val dst = RectF(MARGIN, top, MARGIN + boxWidth, top + boxHeight)
                c.drawBitmap(bitmap, src, dst, null)
            } else {
                c.drawRect(MARGIN, top, MARGIN + boxWidth, top + boxHeight, boxPaint)
                drawCentered(c, "Image unavailable", MARGIN, MARGIN + boxWidth, top + boxHeight / 2f, smallPaint)
            }
            c.drawText(attachment.label, MARGIN, top + boxHeight + smallPaint.textSize + 2f, smallPaint)
        }
    }

    private fun mediaLineBlock(text: String) = Block(lineHeight(bodyPaint) + 2f) { c, top ->
        c.drawText(text, MARGIN, top + bodyPaint.textSize, bodyPaint)
    }

    private fun vitalsBlock(report: ClinicalReport): Block {
        val rowH = lineHeight(bodyPaint) + 2f
        val rowsPerColumn = (report.vitals.size + 1) / 2
        return Block(rowsPerColumn * rowH + 4f) { c, top ->
            val colWidth = CONTENT_WIDTH / 2f
            report.vitals.forEachIndexed { i, v ->
                val col = i / rowsPerColumn
                val row = i % rowsPerColumn
                drawField(c, v.label, v.value, MARGIN + col * colWidth, top + (row + 1) * rowH, colWidth)
            }
        }
    }

    private fun kernelBlock(report: ClinicalReport): Block {
        val k = report.kernelOutput!!
        val inferenceMillis = java.time.Duration.between(k.inferenceStartedAt, k.inferenceEndedAt).toMillis()
        val paras = buildList {
            // Risk/urgency near the top, most visible — Part A addendum.
            add("Risk: ${k.riskCategory}   Urgency: ${k.urgencyLevel}")
            add(
                "Predicted: ${k.predictedCondition}${k.icdCode?.let { " (ICD-10: $it)" } ?: ""}  " +
                    "(confidence ${(k.confidenceScore * 100).toInt()}%)",
            )
            add("Inference time: ${inferenceMillis} ms")
            if (k.differentials.isNotEmpty()) add("Differentials: ${k.differentials.joinToString(", ")}")
            add("Reasoning: ${k.reasoningSummary}")
            if (k.requiredHumanVerification) add("⚠ Requires physician verification before any diagnosis is final.")
        }
        val wrapped = paras.flatMap { wrap(it, bodyPaint, CONTENT_WIDTH) }
        return Block(wrapped.size * lineHeight(bodyPaint) + 4f) { c, top ->
            var ty = top + bodyPaint.textSize
            wrapped.forEach { c.drawText(it, MARGIN, ty, bodyPaint); ty += lineHeight(bodyPaint) }
        }
    }

    private fun rxBlock(report: ClinicalReport): Block {
        val diagnosisLines = report.diagnosis?.let { wrap("Diagnosis: $it", bodyPaint, CONTENT_WIDTH) }.orEmpty()
        val decisionLines = report.kernelDecision?.let {
            wrap("Doctor's review of AI assessment: $it", smallPaint, CONTENT_WIDTH)
        }.orEmpty()
        val medLines = report.prescription.flatMap { line ->
            wrap("${line.index}. ${line.text}", bodyPaint, CONTENT_WIDTH - 6f)
        }
        val h = lineHeight(rxPaint) + 4f +
            diagnosisLines.size * lineHeight(bodyPaint) +
            decisionLines.size * lineHeight(smallPaint) +
            medLines.size * lineHeight(bodyPaint) + 6f
        return Block(h) { c, top ->
            var ty = top + rxPaint.textSize
            c.drawText("Rx / Advice", MARGIN, ty, rxPaint)
            ty += lineHeight(rxPaint)
            diagnosisLines.forEach { c.drawText(it, MARGIN, ty + bodyPaint.textSize, bodyPaint); ty += lineHeight(bodyPaint) }
            decisionLines.forEach { c.drawText(it, MARGIN, ty + smallPaint.textSize, smallPaint); ty += lineHeight(smallPaint) }
            medLines.forEach { c.drawText(it, MARGIN + 6f, ty + bodyPaint.textSize, bodyPaint); ty += lineHeight(bodyPaint) }
        }
    }

    // ---- footer (pinned bottom of last page) ------------------------------

    private fun footerHeight(report: ClinicalReport): Float {
        val consentLines = wrap(report.consentStatement, smallPaint, CONTENT_WIDTH)
        return consentLines.size * lineHeight(smallPaint) + 46f
    }

    private fun drawFooter(canvas: Canvas, report: ClinicalReport) {
        val consentLines = wrap(report.consentStatement, smallPaint, CONTENT_WIDTH)
        val h = footerHeight(report)
        var ty = PAGE_HEIGHT - MARGIN - h + smallPaint.textSize
        canvas.drawLine(MARGIN, PAGE_HEIGHT - MARGIN - h, PAGE_WIDTH - MARGIN, PAGE_HEIGHT - MARGIN - h, linePaint)
        consentLines.forEach { canvas.drawText(it, MARGIN, ty + 4f, smallPaint); ty += lineHeight(smallPaint) }

        // Bottom-right signature block, double-underlined.
        val sigRight = PAGE_WIDTH - MARGIN
        val sigLeft = PAGE_WIDTH / 2f
        val sigLineY = PAGE_HEIGHT - MARGIN - 22f
        canvas.drawLine(sigLeft, sigLineY, sigRight, sigLineY, linePaint)
        canvas.drawLine(sigLeft, sigLineY + 2.5f, sigRight, sigLineY + 2.5f, linePaint)
        val reg = report.signature?.registrationNumber
        val sigText = when {
            report.signature != null -> "Physician Verification Node / Reg No: ${reg ?: "—"}"
            else -> "Physician Verification Node — pending"
        }
        canvas.drawText(sigText, sigLeft, sigLineY + 12f, smallPaint)
        canvas.drawText(report.disclaimer, sigLeft, sigLineY + 12f + lineHeight(smallPaint), verifiedPaint)
    }

    private fun drawRunningHeader(canvas: Canvas, report: ClinicalReport): Float {
        canvas.drawText("CR No: ${report.header.consultationRecordNo}", MARGIN, MARGIN + smallPaint.textSize, smallPaint)
        val uid = "UID: ${report.header.patientUid}"
        canvas.drawText(uid, PAGE_WIDTH - MARGIN - smallPaint.measureText(uid), MARGIN + smallPaint.textSize, smallPaint)
        val y = MARGIN + lineHeight(smallPaint) + 4f
        canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, linePaint)
        return y + 6f
    }

    // ---- primitives -------------------------------------------------------

    private fun drawField(c: Canvas, label: String, value: String, x: Float, baselineY: Float, maxWidth: Float) {
        c.drawText("$label:", x, baselineY, labelPaint)
        val labelW = labelPaint.measureText("$label:") + 4f
        val valuePaint = bodyPaint
        val clipped = ellipsize(value, valuePaint, maxWidth - labelW)
        c.drawText(clipped, x + labelW, baselineY, valuePaint)
    }

    private fun drawFieldColumn(c: Canvas, rows: List<Pair<String, String>>, x: Float, firstBaseline: Float, colWidth: Float, rowH: Float) {
        var ry = firstBaseline
        rows.forEach { (label, value) -> drawField(c, label, value, x, ry, colWidth); ry += rowH }
    }

    private fun drawCentered(c: Canvas, text: String, left: Float, right: Float, baselineY: Float, paint: Paint) {
        val w = paint.measureText(text)
        c.drawText(text, left + (right - left - w) / 2f, baselineY, paint)
    }

    private fun drawBarcode(c: Canvas, data: String, x: Float, y: Float, width: Float, height: Float) {
        val bars = Code128.encodeB(data)
        if (bars.isEmpty()) return
        val moduleWidth = width / Code128.totalModules(bars)
        var cursor = x
        bars.forEach { bar ->
            val w = bar.modules * moduleWidth
            if (bar.isBar) c.drawRect(cursor, y, cursor + w, y + height, barPaint)
            cursor += w
        }
    }

    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (text.isBlank()) return listOf("")
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var line = StringBuilder()
        words.forEach { word ->
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(candidate) <= maxWidth || line.isEmpty()) {
                line = StringBuilder(candidate)
            } else {
                lines.add(line.toString())
                line = StringBuilder(word)
            }
        }
        if (line.isNotEmpty()) lines.add(line.toString())
        return lines
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 0 && paint.measureText(text.substring(0, end) + "…") > maxWidth) end--
        return text.substring(0, end) + "…"
    }
}
