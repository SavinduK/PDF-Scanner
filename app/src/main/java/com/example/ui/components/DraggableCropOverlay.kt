package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.model.CropQuad
import com.example.model.Point2D
import com.example.ui.theme.EmeraldPrimary
import com.example.ui.theme.ScannerCropHandle
import kotlin.math.hypot

@Composable
fun DraggableCropOverlay(
    bitmap: Bitmap,
    cropQuad: CropQuad,
    onCornerMoved: (cornerIndex: Int, newX: Float, newY: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize().testTag("crop_overlay_container")) {
        val containerW = constraints.maxWidth.toFloat()
        val containerH = constraints.maxHeight.toFloat()

        val bmpW = bitmap.width.toFloat()
        val bmpH = bitmap.height.toFloat()

        if (containerW <= 0 || containerH <= 0 || bmpW <= 0 || bmpH <= 0) return@BoxWithConstraints

        val scale = minOf(containerW / bmpW, containerH / bmpH)
        val displayedW = bmpW * scale
        val displayedH = bmpH * scale
        val offsetX = (containerW - displayedW) / 2f
        val offsetY = (containerH - displayedH) / 2f

        // Convert normalized corners to container pixel coordinates
        fun toPixel(p: Point2D): Offset {
            return Offset(offsetX + p.x * displayedW, offsetY + p.y * displayedH)
        }

        var activeCornerIndex by remember { mutableIntStateOf(-1) }
        var currentTouchPos by remember { mutableStateOf<Offset?>(null) }

        val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }

        val currentCropQuad by rememberUpdatedState(cropQuad)
        val currentOnCornerMoved by rememberUpdatedState(onCornerMoved)
        val currentDisplayedW by rememberUpdatedState(displayedW)
        val currentDisplayedH by rememberUpdatedState(displayedH)
        val currentOffsetX by rememberUpdatedState(offsetX)
        val currentOffsetY by rememberUpdatedState(offsetY)

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .testTag("crop_canvas")
                .pointerInput(bitmap) {
                    detectDragGestures(
                        onDragStart = { startOffset ->
                            val q = currentCropQuad
                            val dW = currentDisplayedW
                            val dH = currentDisplayedH
                            val oX = currentOffsetX
                            val oY = currentOffsetY
                            fun toPixel(p: Point2D): Offset = Offset(oX + p.x * dW, oY + p.y * dH)

                            val corners = listOf(
                                toPixel(q.topLeft),
                                toPixel(q.topRight),
                                toPixel(q.bottomRight),
                                toPixel(q.bottomLeft)
                            )

                            // Find nearest corner within 60dp touch radius
                            val touchRadiusPx = 60.dp.toPx()
                            var bestIndex = -1
                            var bestDist = Float.MAX_VALUE

                            for (i in corners.indices) {
                                val c = corners[i]
                                val dist = hypot(startOffset.x - c.x, startOffset.y - c.y)
                                if (dist < touchRadiusPx && dist < bestDist) {
                                    bestDist = dist
                                    bestIndex = i
                                }
                            }

                            activeCornerIndex = bestIndex
                            if (bestIndex != -1) {
                                currentTouchPos = startOffset
                            }
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            if (activeCornerIndex != -1) {
                                val q = currentCropQuad
                                val dW = currentDisplayedW
                                val dH = currentDisplayedH
                                val oX = currentOffsetX
                                val oY = currentOffsetY
                                fun toPixel(p: Point2D): Offset = Offset(oX + p.x * dW, oY + p.y * dH)

                                val currentP = when (activeCornerIndex) {
                                    0 -> toPixel(q.topLeft)
                                    1 -> toPixel(q.topRight)
                                    2 -> toPixel(q.bottomRight)
                                    3 -> toPixel(q.bottomLeft)
                                    else -> Offset.Zero
                                }
                                val targetX = currentP.x + dragAmount.x
                                val targetY = currentP.y + dragAmount.y
                                currentTouchPos = Offset(targetX, targetY)

                                val normX = ((targetX - oX) / dW).coerceIn(0f, 1f)
                                val normY = ((targetY - oY) / dH).coerceIn(0f, 1f)
                                currentOnCornerMoved(activeCornerIndex, normX, normY)
                            }
                        },
                        onDragEnd = {
                            activeCornerIndex = -1
                            currentTouchPos = null
                        },
                        onDragCancel = {
                            activeCornerIndex = -1
                            currentTouchPos = null
                        }
                    )
                }
        ) {
            // 1. Draw scaled background bitmap
            drawImage(
                image = imageBitmap,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(bitmap.width, bitmap.height),
                dstOffset = IntOffset(offsetX.toInt(), offsetY.toInt()),
                dstSize = IntSize(displayedW.toInt(), displayedH.toInt())
            )

            val tl = toPixel(cropQuad.topLeft)
            val tr = toPixel(cropQuad.topRight)
            val br = toPixel(cropQuad.bottomRight)
            val bl = toPixel(cropQuad.bottomLeft)

            // 2. Draw dark scrim outside the crop quad
            val quadPath = Path().apply {
                moveTo(tl.x, tl.y)
                lineTo(tr.x, tr.y)
                lineTo(br.x, br.y)
                lineTo(bl.x, bl.y)
                close()
            }

            // Outer dark mask
            val scrimColor = Color(0x99000000)
            drawRect(color = scrimColor)

            // Clear the crop area to show image brightly
            drawPath(
                path = quadPath,
                color = Color.Transparent,
                blendMode = BlendMode.Clear
            )

            // Re-draw the document area inside the crop quad with clear visibility
            clipPath(quadPath) {
                drawImage(
                    image = imageBitmap,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(bitmap.width, bitmap.height),
                    dstOffset = IntOffset(offsetX.toInt(), offsetY.toInt()),
                    dstSize = IntSize(displayedW.toInt(), displayedH.toInt())
                )

                // Draw 3x3 grid guidelines inside quadrilateral
                val gridColor = Color(0x5500E5FF)
                // 1/3 and 2/3 interpolated lines
                val t1 = 0.333f
                val t2 = 0.666f

                val h1Start = lerp(tl, bl, t1)
                val h1End = lerp(tr, br, t1)
                drawLine(gridColor, h1Start, h1End, strokeWidth = 1.5.dp.toPx())

                val h2Start = lerp(tl, bl, t2)
                val h2End = lerp(tr, br, t2)
                drawLine(gridColor, h2Start, h2End, strokeWidth = 1.5.dp.toPx())

                val v1Start = lerp(tl, tr, t1)
                val v1End = lerp(bl, br, t1)
                drawLine(gridColor, v1Start, v1End, strokeWidth = 1.5.dp.toPx())

                val v2Start = lerp(tl, tr, t2)
                val v2End = lerp(bl, br, t2)
                drawLine(gridColor, v2Start, v2End, strokeWidth = 1.5.dp.toPx())
            }

            // 3. Draw quadrilateral border lines
            val lineColor = ScannerCropHandle
            drawPath(
                path = quadPath,
                color = lineColor,
                style = Stroke(
                    width = 2.5.dp.toPx(),
                    cap = StrokeCap.Round
                )
            )

            // 4. Draw corner circular handles
            val corners = listOf(tl, tr, br, bl)
            val handleRadius = 14.dp.toPx()
            val innerRadius = 7.dp.toPx()

            for (i in corners.indices) {
                val pt = corners[i]
                val isActive = (i == activeCornerIndex)

                // Outer glow / shadow
                drawCircle(
                    color = Color.Black.copy(alpha = 0.35f),
                    radius = handleRadius + 3.dp.toPx(),
                    center = pt
                )
                // Outer circle
                drawCircle(
                    color = if (isActive) Color.White else ScannerCropHandle,
                    radius = if (isActive) handleRadius + 2.dp.toPx() else handleRadius,
                    center = pt
                )
                // Inner accent core
                drawCircle(
                    color = if (isActive) ScannerCropHandle else EmeraldPrimary,
                    radius = innerRadius,
                    center = pt
                )

                // Corner L-brackets for precision framing
                drawCornerBracket(pt, i, handleRadius * 1.6f)
            }

            // 5. Magnifier Loupe when corner is being dragged!
            if (activeCornerIndex != -1 && currentTouchPos != null) {
                val activePoint = corners[activeCornerIndex]
                val normX = ((activePoint.x - offsetX) / displayedW).coerceIn(0f, 1f)
                val normY = ((activePoint.y - offsetY) / displayedH).coerceIn(0f, 1f)

                val loupeRadius = 48.dp.toPx()
                // Position loupe offset from finger so it isn't blocked by the hand
                val loupeCenter = if (activePoint.y > loupeRadius * 2.5f) {
                    Offset(activePoint.x.coerceIn(loupeRadius + 16.dp.toPx(), size.width - loupeRadius - 16.dp.toPx()), activePoint.y - loupeRadius * 1.8f)
                } else {
                    Offset(activePoint.x.coerceIn(loupeRadius + 16.dp.toPx(), size.width - loupeRadius - 16.dp.toPx()), activePoint.y + loupeRadius * 1.8f)
                }

                // Loupe clip circle
                val loupePath = Path().apply {
                    addOval(Rect(loupeCenter, loupeRadius))
                }

                // Loupe shadow
                drawCircle(
                    color = Color.Black.copy(alpha = 0.5f),
                    radius = loupeRadius + 4.dp.toPx(),
                    center = loupeCenter
                )

                // Draw magnified region inside loupe
                clipPath(loupePath) {
                    val zoomFactor = 2.2f
                    val srcBmpX = (normX * bmpW).toInt()
                    val srcBmpY = (normY * bmpH).toInt()

                    val sampleW = (loupeRadius * 2 / (scale * zoomFactor)).toInt().coerceAtLeast(20)
                    val sampleH = (loupeRadius * 2 / (scale * zoomFactor)).toInt().coerceAtLeast(20)

                    val srcRectLeft = (srcBmpX - sampleW / 2).coerceIn(0, bitmap.width - sampleW)
                    val srcRectTop = (srcBmpY - sampleH / 2).coerceIn(0, bitmap.height - sampleH)

                    drawImage(
                        image = imageBitmap,
                        srcOffset = IntOffset(srcRectLeft, srcRectTop),
                        srcSize = IntSize(sampleW, sampleH),
                        dstOffset = IntOffset((loupeCenter.x - loupeRadius).toInt(), (loupeCenter.y - loupeRadius).toInt()),
                        dstSize = IntSize((loupeRadius * 2).toInt(), (loupeRadius * 2).toInt())
                    )

                    // Loupe crosshairs for exact corner alignment
                    val crosshairColor = Color.Red.copy(alpha = 0.85f)
                    drawLine(
                        crosshairColor,
                        Offset(loupeCenter.x - 14.dp.toPx(), loupeCenter.y),
                        Offset(loupeCenter.x + 14.dp.toPx(), loupeCenter.y),
                        strokeWidth = 2.dp.toPx()
                    )
                    drawLine(
                        crosshairColor,
                        Offset(loupeCenter.x, loupeCenter.y - 14.dp.toPx()),
                        Offset(loupeCenter.x, loupeCenter.y + 14.dp.toPx()),
                        strokeWidth = 2.dp.toPx()
                    )
                }

                // Loupe border ring
                drawCircle(
                    color = Color.White,
                    radius = loupeRadius,
                    center = loupeCenter,
                    style = Stroke(width = 3.dp.toPx())
                )
            }
        }
    }
}

private fun lerp(start: Offset, end: Offset, fraction: Float): Offset {
    return Offset(
        start.x + (end.x - start.x) * fraction,
        start.y + (end.y - start.y) * fraction
    )
}

private fun DrawScope.drawCornerBracket(center: Offset, cornerIndex: Int, size: Float) {
    val stroke = 3.dp.toPx()
    val color = Color.White
    when (cornerIndex) {
        0 -> { // Top-Left
            drawLine(color, center, Offset(center.x + size, center.y), stroke)
            drawLine(color, center, Offset(center.x, center.y + size), stroke)
        }
        1 -> { // Top-Right
            drawLine(color, center, Offset(center.x - size, center.y), stroke)
            drawLine(color, center, Offset(center.x, center.y + size), stroke)
        }
        2 -> { // Bottom-Right
            drawLine(color, center, Offset(center.x - size, center.y), stroke)
            drawLine(color, center, Offset(center.x, center.y - size), stroke)
        }
        3 -> { // Bottom-Left
            drawLine(color, center, Offset(center.x + size, center.y), stroke)
            drawLine(color, center, Offset(center.x, center.y - size), stroke)
        }
    }
}
