package com.example.samdapp.presentation.documents

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.samdapp.data.local.security.DocumentDecryptionFailedException
import com.example.samdapp.domain.audit.AuditAction
import com.example.samdapp.domain.audit.AuditLogger
import com.example.samdapp.domain.audit.auditPayload
import com.example.samdapp.domain.auth.AuthSession
import com.example.samdapp.domain.auth.UserRole
import com.example.samdapp.domain.document.DocumentAccessAuthorizer
import com.example.samdapp.domain.document.DocumentAccessOutcome
import com.example.samdapp.domain.document.computeInSampleSize
import com.example.samdapp.domain.model.ConsultationDocument
import com.example.samdapp.domain.repository.ConsultationDocumentRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/** H-18, Build 3a. The one plaintext-on-disk window in this design (see the DAO/repository KDoc
 *  chain): [PdfRenderer] needs a real seekable file, so viewing decrypts to a `cacheDir` temp
 *  file. Lives under a dedicated subdirectory so [sweepOrphanedViewerTempFiles] can find and
 *  delete anything a process death left behind. */
private const val VIEWER_TEMP_DIR = "document_viewer_temp"

/** Deletes every file under the viewer's temp directory. Call once at app start — a `finally`
 *  block only covers a clean exit; process death (very possible, this is a rural-PHC field app)
 *  does not run it, so a stale plaintext temp file can otherwise outlive the screen that made it. */
fun sweepOrphanedViewerTempFiles(context: Context) {
    File(context.cacheDir, VIEWER_TEMP_DIR).listFiles()?.forEach { it.delete() }
}

sealed interface DocumentViewerContent {
    data class Image(val bitmap: Bitmap) : DocumentViewerContent
    data class Pdf(val pageBitmap: Bitmap, val pageIndex: Int, val pageCount: Int) : DocumentViewerContent
}

data class DocumentViewerUiState(
    val isLoading: Boolean = true,
    val document: ConsultationDocument? = null,
    /** H-18 cadre gate (Build 3c): [com.example.samdapp.domain.document.DocumentAccessAuthorizer]
     *  decides — the uploader, or a [com.example.samdapp.domain.auth.CadreTier.PHYSICIAN], sees
     *  raw content. Every other role sees [document]'s metadata (label, department, record type,
     *  that it exists) but never [content]. */
    val canViewContent: Boolean = false,
    val content: DocumentViewerContent? = null,
    /** Explicit, never a blank view — set on a corrupt/tampered file
     *  ([DocumentDecryptionFailedException]) or an unrecognised rendered type. */
    val errorMessage: String? = null,
)

@HiltViewModel(assistedFactory = DocumentViewerViewModel.Factory::class)
class DocumentViewerViewModel @AssistedInject constructor(
    @Assisted private val documentId: String,
    @ApplicationContext private val context: Context,
    private val repository: ConsultationDocumentRepository,
    private val authSession: AuthSession,
    private val auditLogger: AuditLogger,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(documentId: String): DocumentViewerViewModel
    }

    private val _uiState = MutableStateFlow(DocumentViewerUiState())
    val uiState: StateFlow<DocumentViewerUiState> = _uiState.asStateFlow()

    private var tempFile: File? = null
    private var pdfRenderer: PdfRenderer? = null
    private var pdfFileDescriptor: ParcelFileDescriptor? = null
    private var viewedAudited = false
    private val pdfPageMutex = Mutex()

    init {
        viewModelScope.launch {
            val document = repository.getById(documentId)
            if (document == null) {
                _uiState.update { it.copy(isLoading = false, errorMessage = "Document not found") }
                return@launch
            }
            val session = authSession.currentUser().first()
            val outcome = session?.let { DocumentAccessAuthorizer.authorize(document, it) } ?: DocumentAccessOutcome.DENIED_TIER
            _uiState.update { it.copy(document = document, canViewContent = outcome.granted) }
            if (!outcome.granted) {
                auditViewAttempt(document, session?.role, outcome)
                _uiState.update { it.copy(isLoading = false) }
                return@launch
            }
            loadContent(document, session?.role, outcome)
        }
    }

    /** Audit persistence failing must never crash this coroutine or corrupt the view state the
     *  gate already decided (granted/denied, rendered/error) — losing one audit row is bad,
     *  wiping an already-successfully-rendered document because logging it failed is worse. */
    private suspend fun auditViewAttempt(document: ConsultationDocument, viewerRole: UserRole?, outcome: DocumentAccessOutcome) {
        try {
            auditLogger.log(
                action = AuditAction.DOCUMENT_VIEWED,
                patientId = document.patientId,
                payload = auditPayload(
                    "documentId" to documentId,
                    "viewerRole" to viewerRole?.name,
                    "accessResult" to outcome.auditValue,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Swallowed deliberately - see KDoc above.
        }
    }

    private suspend fun loadContent(document: ConsultationDocument, viewerRole: UserRole?, outcome: DocumentAccessOutcome) {
        try {
            val content = withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, VIEWER_TEMP_DIR).apply { mkdirs() }
                val temp = File(dir, "$documentId.${extensionFor(document.mimeType)}")
                FileOutputStream(temp).use { out -> repository.readDecrypted(documentId, out) }
                tempFile = temp
                when (document.mimeType) {
                    "application/pdf" -> renderPdfFirstPage(temp)
                    else -> renderImage(temp)
                }
            }
            _uiState.update { it.copy(isLoading = false, content = content) }
            if (!viewedAudited) {
                viewedAudited = true
                auditViewAttempt(document, viewerRole, outcome)
            }
        } catch (e: DocumentDecryptionFailedException) {
            _uiState.update { it.copy(isLoading = false, errorMessage = "This document cannot be opened on this device") }
        } catch (e: Exception) {
            _uiState.update { it.copy(isLoading = false, errorMessage = "This document cannot be opened on this device") }
        }
    }

    private fun renderImage(file: File): DocumentViewerContent.Image {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val sampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, MAX_DIMENSION)
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
            ?: throw IllegalStateException("Could not decode image")
        return DocumentViewerContent.Image(bitmap)
    }

    private fun renderPdfFirstPage(file: File): DocumentViewerContent.Pdf {
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        pdfFileDescriptor = fd
        val renderer = PdfRenderer(fd)
        pdfRenderer = renderer
        return renderPdfPage(renderer, 0)
    }

    private fun renderPdfPage(renderer: PdfRenderer, index: Int): DocumentViewerContent.Pdf {
        renderer.openPage(index).use { page ->
            val scale = MAX_DIMENSION.toFloat() / maxOf(page.width, page.height)
            val bitmap = Bitmap.createBitmap(
                (page.width * scale).toInt().coerceAtLeast(1),
                (page.height * scale).toInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
            )
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return DocumentViewerContent.Pdf(bitmap, index, renderer.pageCount)
        }
    }

    fun onNextPage() = changePdfPage(+1)
    fun onPreviousPage() = changePdfPage(-1)

    /** `PdfRenderer.Page.render` is `@WorkerThread` (blocking, not safe on the main thread for a
     *  large page) and `PdfRenderer` itself is not safe to use from two coroutines at once, so
     *  page changes run on IO under [pdfPageMutex] rather than directly on the button's caller
     *  thread — serialized, so a fast double-tap on Next/Previous can't overlap two renders
     *  against the same renderer. */
    private fun changePdfPage(delta: Int) {
        val renderer = pdfRenderer ?: return
        viewModelScope.launch {
            pdfPageMutex.withLock {
                val current = (_uiState.value.content as? DocumentViewerContent.Pdf) ?: return@withLock
                val next = (current.pageIndex + delta).coerceIn(0, renderer.pageCount - 1)
                if (next == current.pageIndex) return@withLock
                val rendered = withContext(Dispatchers.IO) { renderPdfPage(renderer, next) }
                _uiState.update { it.copy(content = rendered) }
            }
        }
    }

    private fun extensionFor(mimeType: String): String = when (mimeType) {
        "application/pdf" -> "pdf"
        "image/png" -> "png"
        else -> "jpg"
    }

    override fun onCleared() {
        // The one plaintext-on-disk window is closed as soon as the viewer goes away — never
        // left for the app-start sweep to find in the common case, only in a process-death case.
        pdfRenderer?.close()
        pdfFileDescriptor?.close()
        tempFile?.delete()
        super.onCleared()
    }

    private companion object {
        const val MAX_DIMENSION = 2048
    }
}
