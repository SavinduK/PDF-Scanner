package com.example.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ui.theme.ScannerAccent
import com.example.ui.theme.ScannerCropHandle
import java.nio.ByteBuffer

@Composable
fun CameraCaptureView(
    modifier: Modifier = Modifier,
    isFlashEnabled: Boolean = false,
    useFrontCamera: Boolean = false,
    onImageCaptured: (Bitmap) -> Unit,
    captureTrigger: Int = 0 // Incrementing trigger to take photo
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var imageCapture: ImageCapture? by remember { mutableStateOf(null) }
    var camera: Camera? by remember { mutableStateOf(null) }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    // Bind camera lifecycle
    LaunchedEffect(useFrontCamera) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()
                imageCapture = capture

                val cameraSelector = if (useFrontCamera) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

                cameraProvider.unbindAll()
                val boundCamera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    capture
                )
                camera = boundCamera
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    // Handle flash toggle
    LaunchedEffect(isFlashEnabled, camera) {
        try {
            camera?.cameraControl?.enableTorch(isFlashEnabled)
        } catch (_: Exception) { }
    }

    // Handle capture trigger
    LaunchedEffect(captureTrigger) {
        if (captureTrigger > 0) {
            val capture = imageCapture ?: return@LaunchedEffect
            val executor = ContextCompat.getMainExecutor(context)
            capture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        camera?.cameraControl?.enableTorch(false)
                    } catch (_: Exception) {}
                    val bitmap = imageProxyToBitmap(image)
                    image.close()
                    if (bitmap != null) {
                        onImageCaptured(bitmap)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    exception.printStackTrace()
                }
            })
        }
    }

    // Scanning laser line animation
    val infiniteTransition = rememberInfiniteTransition(label = "laser")
    val laserProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "laser_y"
    )

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        // CamScanner Viewfinder with document bounding guide overlay and laser line
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // Document frame aspect ratio (approx A4 1:1.4)
            val frameW = (w * 0.84f).coerceAtMost(560.dp.toPx())
            val frameH = (frameW * 1.38f).coerceAtMost(h * 0.72f)
            val left = (w - frameW) / 2f
            val top = (h - frameH) / 2f - 20.dp.toPx()
            val right = left + frameW
            val bottom = top + frameH

            val frameRect = Rect(left, top, right, bottom)
            val cornerRadius = CornerRadius(16.dp.toPx(), 16.dp.toPx())

            // Dim outside viewfinder
            val scrimColor = Color(0x66000000)
            drawRect(color = scrimColor)

            // Punch hole in center for crisp preview
            val clearPath = Path().apply {
                addRoundRect(RoundRect(frameRect, cornerRadius))
            }
            drawPath(clearPath, Color.Transparent, blendMode = BlendMode.Clear)

            // Document frame outline
            drawRoundRect(
                color = Color.White.copy(alpha = 0.35f),
                topLeft = Offset(left, top),
                size = androidx.compose.ui.geometry.Size(frameW, frameH),
                cornerRadius = cornerRadius,
                style = Stroke(width = 1.5.dp.toPx())
            )

            // 4 high-contrast viewfinder corner brackets
            val bracketLen = 32.dp.toPx()
            val bracketStroke = 4.dp.toPx()
            val bracketColor = ScannerCropHandle

            // Top-Left
            drawLine(bracketColor, Offset(left - 2, top + bracketLen), Offset(left - 2, top), bracketStroke, StrokeCap.Round)
            drawLine(bracketColor, Offset(left - 2, top), Offset(left + bracketLen, top), bracketStroke, StrokeCap.Round)

            // Top-Right
            drawLine(bracketColor, Offset(right + 2 - bracketLen, top), Offset(right + 2, top), bracketStroke, StrokeCap.Round)
            drawLine(bracketColor, Offset(right + 2, top), Offset(right + 2, top + bracketLen), bracketStroke, StrokeCap.Round)

            // Bottom-Right
            drawLine(bracketColor, Offset(right + 2, bottom - bracketLen), Offset(right + 2, bottom), bracketStroke, StrokeCap.Round)
            drawLine(bracketColor, Offset(right + 2, bottom), Offset(right + 2 - bracketLen, bottom), bracketStroke, StrokeCap.Round)

            // Bottom-Left
            drawLine(bracketColor, Offset(left - 2, bottom - bracketLen), Offset(left - 2, bottom), bracketStroke, StrokeCap.Round)
            drawLine(bracketColor, Offset(left - 2, bottom), Offset(left + bracketLen, bottom), bracketStroke, StrokeCap.Round)

            // Animated scanning laser line across viewfinder
            val laserY = top + (bottom - top) * laserProgress
            val laserBrush = Brush.horizontalGradient(
                colors = listOf(
                    Color.Transparent,
                    ScannerAccent.copy(alpha = 0.85f),
                    Color.White,
                    ScannerAccent.copy(alpha = 0.85f),
                    Color.Transparent
                ),
                startX = left,
                endX = right
            )
            drawLine(
                brush = laserBrush,
                start = Offset(left + 8.dp.toPx(), laserY),
                end = Offset(right - 8.dp.toPx(), laserY),
                strokeWidth = 2.5.dp.toPx()
            )
        }
    }
}

private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
    val planeProxy = image.planes[0]
    val buffer: ByteBuffer = planeProxy.buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)

    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    val rotationDegrees = image.imageInfo.rotationDegrees

    return if (rotationDegrees != 0) {
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated != bitmap) bitmap.recycle()
        rotated
    } else {
        bitmap
    }
}
