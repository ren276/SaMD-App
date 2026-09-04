@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.samdapp.presentation.documents

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.samdapp.presentation.common.SamdLoadingIndicator

/**
 * H-18, Build 3a: the safe in-app viewer — [android.graphics.pdf.PdfRenderer] for PDF,
 * `BitmapFactory` for JPEG/PNG, never an external handler and never `Intent.ACTION_VIEW`. The
 * cadre gate ([DocumentViewerUiState.canViewContent], Build 3c —
 * [com.example.samdapp.domain.document.DocumentAccessAuthorizer]) shows metadata to every role but
 * decrypted content only to the uploader or a [com.example.samdapp.domain.auth.CadreTier.PHYSICIAN].
 */
@Composable
fun DocumentViewerScreen(
    documentId: String,
    viewModel: DocumentViewerViewModel = hiltViewModel<DocumentViewerViewModel, DocumentViewerViewModel.Factory>(
        creationCallback = { factory -> factory.create(documentId) },
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopAppBar(title = { Text(uiState.document?.canonicalName ?: "Document") }) }) { padding: PaddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            when {
                uiState.isLoading -> SamdLoadingIndicator()
                uiState.errorMessage != null -> Text(
                    uiState.errorMessage!!,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
                !uiState.canViewContent -> DocumentMetadataOnly(uiState)
                else -> when (val content = uiState.content) {
                    is DocumentViewerContent.Image -> Image(
                        bitmap = content.bitmap.asImageBitmap(),
                        contentDescription = uiState.document?.label,
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                    )
                    is DocumentViewerContent.Pdf -> Column(modifier = Modifier.fillMaxSize()) {
                        Image(
                            bitmap = content.pageBitmap.asImageBitmap(),
                            contentDescription = uiState.document?.label,
                            modifier = Modifier.fillMaxSize().weight(1f).padding(16.dp),
                        )
                        if (content.pageCount > 1) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                OutlinedButton(onClick = viewModel::onPreviousPage, enabled = content.pageIndex > 0) { Text("Previous") }
                                Text("Page ${content.pageIndex + 1} of ${content.pageCount}", modifier = Modifier.padding(top = 12.dp))
                                OutlinedButton(onClick = viewModel::onNextPage, enabled = content.pageIndex < content.pageCount - 1) { Text("Next") }
                            }
                        }
                    }
                    null -> Text("Nothing to display")
                }
            }
        }
    }
}

/** The cadre gate's non-content path: metadata (label, department, record type, that a document
 *  exists) is always visible, never the decrypted bytes. */
@Composable
private fun DocumentMetadataOnly(uiState: DocumentViewerUiState) {
    val document = uiState.document ?: return
    Column(modifier = Modifier.padding(16.dp)) {
        Text("You do not have permission to view this document's content.", style = MaterialTheme.typography.bodyMedium)
        Text("Label: ${document.label.ifBlank { "(none)" }}", modifier = Modifier.padding(top = 8.dp))
        Text("Department: ${document.departmentCode.name}")
        Text("Record type: ${document.recordTypeCode.name}")
    }
}
