package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ui.theme.ScannerAccent
import com.example.ui.theme.ScannerCropHandle
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.max

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
    var qualityHint: String? by remember { mutableStateOf(null) }

    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose {
            analysisExecutor.shutdown()
        }
    }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    var viewSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var viewfinderRect by remember { mutableStateOf<Rect?>(null) }

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

                var lastAnalyzedMs = 0L
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(analysisExecutor) { proxy ->
                            val now = System.currentTimeMillis()
                            if (now - lastAnalyzedMs >= 250L) {
                                lastAnalyzedMs = now
                                val hint = evaluateFrameQuality(proxy)
                                ContextCompat.getMainExecutor(context).execute {
                                    qualityHint = hint
                                }
                            }
                            proxy.close()
                        }
                    }

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
                    capture,
                    imageAnalysis
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
                    val rawBitmap = imageProxyToBitmap(image)
                    image.close()
                    if (rawBitmap != null) {
                        val vRect = viewfinderRect
                        val vW = viewSize.width
                        val vH = viewSize.height
                        val finalBitmap = if (vRect != null && vW > 0 && vH > 0) {
                            cropBitmapToViewfinder(rawBitmap, vRect, vW.toFloat(), vH.toFloat())
                        } else {
                            rawBitmap
                        }
                        onImageCaptured(finalBitmap)
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
            if (viewfinderRect != frameRect || viewSize.width != w.toInt() || viewSize.height != h.toInt()) {
                viewfinderRect = frameRect
                viewSize = androidx.compose.ui.unit.IntSize(w.toInt(), h.toInt())
            }
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

        // Small non-blocking capture quality feedback hint
        qualityHint?.let { hintText ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xAA000000),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 128.dp)
                    .testTag("camera_quality_hint")
            ) {
                Text(
                    text = hintText,
                    color = Color(0xFFFFD54F),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }
    }
}

/**
 * Evaluates a downscaled Y-plane luminance buffer (~160px wide) from YUV_420_888 preview frames
 * to check exposure (mean luminance) and motion/focus blur (variance of the 3x3 Laplacian).
 */
private fun evaluateFrameQuality(image: ImageProxy): String? {
    val planes = image.planes
    if (planes.isEmpty()) return null
    val yPlane = planes[0]
    val buffer = yPlane.buffer
    val rowStride = yPlane.rowStride
    val pixelStride = yPlane.pixelStride
    val srcW = image.width
    val srcH = image.height
    if (srcW < 32 || srcH < 32) return null

    // Sample central 70% region downscaled to ~160px wide
    val cropLeft = (srcW * 0.15f).toInt()
    val cropTop = (srcH * 0.15f).toInt()
    val cropW = (srcW * 0.70f).toInt()
    val cropH = (srcH * 0.70f).toInt()
    val step = max(1, cropW / 160)
    val dstW = cropW / step
    val dstH = cropH / step
    if (dstW < 10 || dstH < 10) return null

    val lum = IntArray(dstW * dstH)
    var lumSum = 0L
    val limit = buffer.limit()

    for (dy in 0 until dstH) {
        val sy = cropTop + dy * step
        val srcRowStart = sy * rowStride
        val dstRowStart = dy * dstW
        for (dx in 0 until dstW) {
            val sx = cropLeft + dx * step
            val pos = srcRowStart + sx * pixelStride
            val yVal = if (pos in 0 until limit) (buffer.get(pos).toInt() and 0xFF) else 128
            lum[dstRowStart + dx] = yVal
            lumSum += yVal
        }
    }

    val count = dstW * dstH
    val meanLum = lumSum.toFloat() / count.toFloat()
    if (meanLum < 60f) return "Too dark"
    if (meanLum > 200f) return "Too bright"

    // Compute variance of 3x3 discrete Laplacian: L = 4*c - (top + bottom + left + right)
    var lapSum = 0.0
    var lapSqSum = 0.0
    var lapCount = 0
    for (y in 1 until dstH - 1) {
        val rPrev = (y - 1) * dstW
        val rCurr = y * dstW
        val rNext = (y + 1) * dstW
        for (x in 1 until dstW - 1) {
            val c = lum[rCurr + x]
            val lap = (4 * c - lum[rPrev + x] - lum[rNext + x] - lum[rCurr + x - 1] - lum[rCurr + x + 1]).toDouble()
            lapSum += lap
            lapSqSum += lap * lap
            lapCount++
        }
    }

    if (lapCount > 0) {
        val lapMean = lapSum / lapCount
        val lapVariance = (lapSqSum / lapCount) - (lapMean * lapMean)
        if (lapVariance < 90.0) {
            return "Hold steady"
        }
    }
    return null
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

private fun cropBitmapToViewfinder(
    bitmap: Bitmap,
    rect: Rect,
    viewWidth: Float,
    viewHeight: Float
): Bitmap {
    val bw = bitmap.width.toFloat()
    val bh = bitmap.height.toFloat()
    if (bw <= 0 || bh <= 0 || viewWidth <= 0 || viewHeight <= 0) return bitmap

    // PreviewView uses ScaleType.FILL_CENTER
    val scale = maxOf(viewWidth / bw, viewHeight / bh)
    val scaledW = bw * scale
    val scaledH = bh * scale
    val offsetX = (viewWidth - scaledW) / 2f
    val offsetY = (viewHeight - scaledH) / 2f

    val x1 = (((rect.left - offsetX) / scale).toInt()).coerceIn(0, bitmap.width - 1)
    val y1 = (((rect.top - offsetY) / scale).toInt()).coerceIn(0, bitmap.height - 1)
    val x2 = (((rect.right - offsetX) / scale).toInt()).coerceIn(x1 + 10, bitmap.width)
    val y2 = (((rect.bottom - offsetY) / scale).toInt()).coerceIn(y1 + 10, bitmap.height)

    val cropW = x2 - x1
    val cropH = y2 - y1

    return try {
        val cropped = Bitmap.createBitmap(bitmap, x1, y1, cropW, cropH)
        if (cropped != bitmap) {
            bitmap.recycle()
        }
        cropped
    } catch (e: Exception) {
        e.printStackTrace()
        bitmap
    }
}
