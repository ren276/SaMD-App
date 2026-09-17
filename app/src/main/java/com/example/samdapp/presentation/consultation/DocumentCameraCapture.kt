package com.example.samdapp.presentation.consultation

import android.util.Size
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * A3: copies the JPEG bytes out of [image]'s single plane, then closes [image] in a `finally` -
 * on the success path AND if the copy itself throws - so the [ImageProxy] never outlives this
 * call regardless of outcome. Package-visible (not private) so a unit test can exercise it
 * against a fake [ImageProxy] without a real camera.
 */
internal fun extractJpegBytes(image: ImageProxy): ByteArray = try {
    val buffer = image.planes[0].buffer
    ByteArray(buffer.remaining()).also { buffer.get(it) }
} finally {
    image.close()
}

/**
 * H-18, Build 3b, Option A. The in-process camera viewfinder that replaced the external
 * `ActivityResultContracts.TakePicture` hand-off - see
 * `scratchpad/capture-process-death-memo.md` for why: the camera app taking the foreground was
 * what made this process a low-memory-killer target between shots, and there is no plaintext
 * staging file for a killed process to leave behind (the frame never touches disk unencrypted).
 *
 * Owns exactly the CameraX plumbing: binding [Preview]/[ImageCapture] to the lifecycle, the
 * shutter, and handing a captured frame's bytes and rotation up to the caller. Encryption,
 * thumbnailing and all persistence stay in `ConsultationViewModel`/`DocumentCaptureStore` - this
 * composable never touches disk and never retains a reference to a frame's bytes past the single
 * [onCaptured] call.
 */
@Composable
internal fun DocumentCameraCapture(
    shutterEnabled: Boolean,
    onCaptured: (jpegBytes: ByteArray, rotationDegrees: Int) -> Unit,
    onCaptureFailed: (message: String?) -> Unit,
    onUnavailable: (message: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current
    // The callback below runs on captureExecutor's background thread; every ViewModel-touching
    // call is marshalled back onto this scope (Compose's UI dispatcher) so `onCaptured` /
    // `onCaptureFailed` / `onUnavailable` are never invoked off the main thread - the same
    // threading guarantee every other Compose callback in this screen already has.
    val mainScope = rememberCoroutineScope()

    // A4: a dedicated single-thread executor for the takePicture callback - never the main
    // executor, so copying a frame's bytes off its ImageProxy can never contend with the UI
    // thread. Shut down when this leaves composition.
    val captureExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { captureExecutor.shutdown() } }

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(3264, 2448),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                        ),
                    )
                    .build(),
            )
            .setFlashMode(ImageCapture.FLASH_MODE_AUTO)
            .build()
    }
    // A1: JPEG orientation metadata follows the display's rotation at bind time. The rotation
    // actually applied at thumbnail/assembly time comes from each captured frame's own
    // `imageInfo.rotationDegrees` (below), not from this. ponytail: read once at bind, not
    // observed live - a worker rotating the device mid-scan is not a supported posture for this
    // portrait scan dialog; add a rotation listener if that changes.
    LaunchedEffect(Unit) {
        view.display?.rotation?.let { imageCapture.targetRotation = it }
    }

    val previewUseCase = remember { Preview.Builder().build() }
    var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }
    LaunchedEffect(previewUseCase) {
        previewUseCase.setSurfaceProvider { request -> surfaceRequest = request }
    }

    // A5: provider init failure, no back camera, or the camera already in use elsewhere all land
    // here as `onUnavailable`. No fallback capture path is offered - the caller (
    // DocumentCaptureSurface) hides this composable and shows an explicit error pointing at the
    // existing file-upload affordance instead.
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    LaunchedEffect(lifecycleOwner) {
        try {
            val provider = ProcessCameraProvider.awaitInstance(context)
            if (!provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                onUnavailable("No back camera is available on this device.")
                return@LaunchedEffect
            }
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, previewUseCase, imageCapture)
            cameraProvider = provider
        } catch (e: Exception) {
            onUnavailable(e.message ?: "The camera could not be started.")
        }
    }
    // A4: unbind on leaving composition (the dialog closing) rather than relying on
    // `bindToLifecycle`'s own lifecycle-driven unbind - the enclosing Activity's Lifecycle
    // outlives this dialog, so nothing else would ever unbind the camera when the worker is done
    // scanning.
    DisposableEffect(Unit) { onDispose { cameraProvider?.unbindAll() } }

    fun takePicture() {
        imageCapture.takePicture(
            captureExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val rotationDegrees = image.imageInfo.rotationDegrees
                    val bytes = extractJpegBytes(image)
                    mainScope.launch { onCaptured(bytes, rotationDegrees) }
                }

                override fun onError(exception: ImageCaptureException) {
                    mainScope.launch { onCaptureFailed(exception.message) }
                }
            },
        )
    }

    Box(modifier = modifier.background(Color.Black)) {
        surfaceRequest?.let { request ->
            CameraXViewfinder(surfaceRequest = request, modifier = Modifier.background(Color.Black))
        }
        FloatingActionButton(
            onClick = { if (shutterEnabled) takePicture() },
            modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
        ) {
            if (shutterEnabled) {
                Icon(Icons.Filled.PhotoCamera, contentDescription = "Take photo")
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.padding(8.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}
