package com.example.samdapp.presentation.report

import android.content.Context
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.samdapp.domain.report.ClinicalReport
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Renders a [ClinicalReport] to a real PDF via Android's native [PdfDocument] (no external PDF
 * library, no iText/AGPL exposure — REQ-RPT-02) using the same [ReportCanvasRenderer] as the
 * on-screen preview. Writes to `cacheDir/reports/` and returns a `FileProvider` content URI so the
 * file can be shared/opened without exposing a raw file path.
 */
@Singleton
class ReportPdfExporter @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val logoBitmap by lazy { decodeReportLogo(context) }

    fun export(report: ClinicalReport): Result<Uri> = runCatching {
        val attachmentBitmaps = report.attachments.associate { it.uri to decodeAttachmentBitmap(context, it.uri) }
        val renderer = ReportCanvasRenderer(logoBitmap = logoBitmap, imageLoader = { uri -> attachmentBitmaps[uri] })
        val document = PdfDocument()
        try {
            val pageCount = renderer.pageCount(report)
            for (i in 0 until pageCount) {
                val pageInfo = PdfDocument.PageInfo.Builder(
                    ReportCanvasRenderer.PAGE_WIDTH.toInt(),
                    ReportCanvasRenderer.PAGE_HEIGHT.toInt(),
                    i + 1,
                ).create()
                val page = document.startPage(pageInfo)
                renderer.drawPage(page.canvas, report, i)
                document.finishPage(page)
            }
            val dir = File(context.cacheDir, "reports").apply { mkdirs() }
            val safeName = report.header.consultationRecordNo.replace(Regex("[^A-Za-z0-9_-]"), "_")
            val file = File(dir, "report-$safeName.pdf")
            file.outputStream().use { document.writeTo(it) }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } finally {
            document.close()
        }
    }
}
