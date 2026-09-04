package com.example.samdapp.presentation.consultation

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.samdapp.domain.document.CapturedPage

private val THUMB_WIDTH = 96.dp
private val THUMB_SPACING = 8.dp

/**
 * H-18, Build 3b. The capture loop: photograph a page, be asked for another, reorder, delete,
 * finish. A full-screen dialog rather than a nav route so the capture cannot outlive the
 * consultation whose department/record-type selections it belongs to, and so backing out lands on
 * the discard confirmation ([R5]) instead of on a different screen.
 */
@Composable
internal fun DocumentCaptureSurface(capture: DocumentCaptureUiState, actions: ConsultationActions) {
    Dialog(
        onDismissRequest = actions::onRequestDiscardDocumentCapture,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Scan report pages", style = MaterialTheme.typography.titleLarge)
                Text(
                    "${capture.pages.size} of ${capture.maxPages} pages. " +
                        "Pages are combined into one PDF in the order shown.",
                    style = MaterialTheme.typography.bodyMedium,
                )

                capture.errorMessage?.let { message ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
                            TextButton(onClick = actions::onDismissDocumentCaptureError) { Text("Dismiss") }
                        }
                    }
                }

                if (capture.pages.isEmpty()) {
                    Text("No pages yet. Take a photo of the first page.")
                } else {
                    CapturedPageStrip(capture = capture, actions = actions)
                }

                if (capture.isAssembling) {
                    Text("Combining pages… ${capture.pagesAssembled} of ${capture.pages.size}")
                    LinearProgressIndicator(
                        progress = {
                            if (capture.pages.isEmpty()) 0f
                            else capture.pagesAssembled.toFloat() / capture.pages.size
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(
                        onClick = actions::onCancelDocumentAssembly,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    ) { Text("Cancel") }
                } else {
                    OutlinedButton(
                        onClick = actions::onAddDocumentPage,
                        enabled = capture.canAddPage,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("captureAddPage"),
                    ) {
                        Text(
                            if (capture.pages.isEmpty()) "Take photo of page 1" else "Add another page",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Button(
                        onClick = actions::onFinishDocumentCapture,
                        enabled = capture.canFinish,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("captureDone"),
                    ) { Text("Done - combine into one PDF", style = MaterialTheme.typography.titleMedium) }
                    TextButton(
                        onClick = actions::onRequestDiscardDocumentCapture,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text("Cancel scan") }
                }
            }
        }
    }

    if (capture.confirmDiscard) {
        AlertDialog(
            onDismissRequest = actions::onDismissDiscardDocumentCapture,
            title = { Text("Discard ${capture.pages.size} page${if (capture.pages.size == 1) "" else "s"}?") },
            text = { Text("The photos you have taken will be deleted and cannot be recovered.") },
            confirmButton = {
                TextButton(onClick = actions::onConfirmDiscardDocumentCapture) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = actions::onDismissDiscardDocumentCapture) { Text("Keep scanning") }
            },
        )
    }
}

/**
 * R7. Page order is clinical meaning in a multi-page report, so it is worker-controlled before
 * finalising: long-press-drag to reorder, plus explicit move buttons on every thumbnail.
 *
 * Both affordances, deliberately. Drag is the fast one; the buttons are the one that works with
 * TalkBack, with gloves, and on a device where a long press competes with the LazyRow's own
 * scroll. They call the same [ConsultationActions.onMoveDocumentPage], so there is one ordering
 * operation to test rather than two.
 */
@Composable
private fun CapturedPageStrip(capture: DocumentCaptureUiState, actions: ConsultationActions) {
    val pages by rememberUpdatedState(capture.pages)
    var draggingPageId by remember { mutableStateOf<String?>(null) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    val stepPx = with(LocalDensity.current) { (THUMB_WIDTH + THUMB_SPACING).toPx() }

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(THUMB_SPACING),
        modifier = Modifier.fillMaxWidth().testTag("capturedPageStrip"),
    ) {
        itemsIndexed(capture.pages, key = { _, page -> page.pageId }) { index, page ->
            CapturedPageThumbnail(
                page = page,
                position = index + 1,
                total = capture.pages.size,
                isDragging = draggingPageId == page.pageId,
                enabled = !capture.isAssembling,
                onDelete = { actions.onDeleteDocumentPage(page.pageId) },
                onMove = { delta -> actions.onMoveDocumentPage(index, index + delta) },
                modifier = Modifier.pointerInput(page.pageId, capture.isAssembling) {
                    if (capture.isAssembling) return@pointerInput
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            draggingPageId = page.pageId
                            dragOffsetPx = 0f
                        },
                        onDragEnd = { draggingPageId = null; dragOffsetPx = 0f },
                        onDragCancel = { draggingPageId = null; dragOffsetPx = 0f },
                        onDrag = { change, amount ->
                            change.consume()
                            dragOffsetPx += amount.x
                            val steps = (dragOffsetPx / stepPx).toInt()
                            if (steps == 0) return@detectDragGesturesAfterLongPress
                            // Resolved from the live list by id, never from the `index` this
                            // lambda closed over: a reorder mid-drag changes the index and a
                            // stale one would move the wrong page.
                            val from = pages.indexOfFirst { it.pageId == page.pageId }
                            if (from < 0) return@detectDragGesturesAfterLongPress
                            val to = (from + steps).coerceIn(0, pages.lastIndex)
                            if (to != from) {
                                actions.onMoveDocumentPage(from, to)
                                dragOffsetPx -= steps * stepPx
                            }
                        },
                    )
                },
            )
        }
    }
}

@Composable
private fun CapturedPageThumbnail(
    page: CapturedPage,
    position: Int,
    total: Int,
    isDragging: Boolean,
    enabled: Boolean,
    onDelete: () -> Unit,
    onMove: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bitmap = remember(page.pageId) {
        android.graphics.BitmapFactory
            .decodeByteArray(page.thumbnailJpeg, 0, page.thumbnailJpeg.size)
            ?.asImageBitmap()
    }
    Card(
        modifier = modifier.width(THUMB_WIDTH).semantics { contentDescription = "Page $position of $total" },
        elevation = CardDefaults.cardElevation(defaultElevation = if (isDragging) 8.dp else 1.dp),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Box(modifier = Modifier.size(width = 88.dp, height = 112.dp), contentAlignment = Alignment.Center) {
                if (bitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text("Page $position")
                }
            }
            Text("$position", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(onClick = { onMove(-1) }, enabled = enabled && position > 1) { Text("◀") }
                TextButton(onClick = { onMove(+1) }, enabled = enabled && position < total) { Text("▶") }
            }
            TextButton(onClick = onDelete, enabled = enabled) { Text("Delete") }
        }
    }
}

/** Fires once per capture session so the worker lands straight in the camera instead of on an
 *  empty strip. Keyed on the session id, never on the (unchanging) empty page list, so cancelling
 *  the first shot returns to the strip rather than relaunching the camera in a loop. */
@Composable
internal fun LaunchFirstCapturePage(capture: DocumentCaptureUiState, actions: ConsultationActions) {
    LaunchedEffect(capture.sessionId) {
        if (capture.pages.isEmpty() && capture.pendingPageId == null) actions.onAddDocumentPage()
    }
}
