package com.example.processing

import android.graphics.Bitmap
import com.example.model.CropQuad
import com.example.model.Point2D
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

object EdgeDetector {

    suspend fun detectEdges(bitmap: Bitmap): CropQuad = withContext(Dispatchers.Default) {
        try {
            val origW = bitmap.width
            val origH = bitmap.height
            if (origW < 50 || origH < 50) return@withContext CropQuad.defaultQuad()

            // Downsample for edge detection
            val targetW = 320
            val targetH = (origH * (targetW.toFloat() / origW)).toInt().coerceIn(160, 480)
            val smallBmp = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)

            val pixels = IntArray(targetW * targetH)
            smallBmp.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
            if (smallBmp != bitmap) {
                smallBmp.recycle()
            }

            // 1. Grayscale luminance
            val gray = FloatArray(targetW * targetH)
            for (i in pixels.indices) {
                val c = pixels[i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                gray[i] = 0.299f * r + 0.587f * g + 0.114f * b
            }

            // 2. Sobel edge magnitude
            val edges = FloatArray(targetW * targetH)
            var maxEdge = 0f
            for (y in 1 until targetH - 1) {
                val rowPrev = (y - 1) * targetW
                val rowCurr = y * targetW
                val rowNext = (y + 1) * targetW

                for (x in 1 until targetW - 1) {
                    val gx = (gray[rowPrev + x + 1] + 2f * gray[rowCurr + x + 1] + gray[rowNext + x + 1]) -
                            (gray[rowPrev + x - 1] + 2f * gray[rowCurr + x - 1] + gray[rowNext + x - 1])
                    val gy = (gray[rowNext + x - 1] + 2f * gray[rowNext + x] + gray[rowNext + x + 1]) -
                            (gray[rowPrev + x - 1] + 2f * gray[rowPrev + x] + gray[rowPrev + x + 1])

                    val mag = abs(gx) + abs(gy)
                    edges[rowCurr + x] = mag
                    if (mag > maxEdge) maxEdge = mag
                }
            }

            if (maxEdge < 10f) {
                return@withContext CropQuad.defaultQuad()
            }

            val threshold = maxEdge * 0.22f

            // Search for corners in 4 quadrants
            val marginX = (targetW * 0.04f).toInt()
            val marginY = (targetH * 0.04f).toInt()
            val midX = targetW / 2
            val midY = targetH / 2

            // Top-Left corner: minimize x + y weighted by distance from corner and edge strength
            var bestTL = Point2D(0.06f, 0.06f)
            var bestTLScore = Float.MAX_VALUE
            for (y in marginY until midY) {
                val row = y * targetW
                for (x in marginX until midX) {
                    val edgeVal = edges[row + x]
                    if (edgeVal > threshold) {
                        // Project along diagonal
                        val distScore = (x.toFloat() + y.toFloat()) - (edgeVal / maxEdge) * 35f
                        if (distScore < bestTLScore) {
                            bestTLScore = distScore
                            bestTL = Point2D(x.toFloat() / targetW, y.toFloat() / targetH)
                        }
                    }
                }
            }

            // Top-Right corner: minimize (targetW - x) + y
            var bestTR = Point2D(0.94f, 0.06f)
            var bestTRScore = Float.MAX_VALUE
            for (y in marginY until midY) {
                val row = y * targetW
                for (x in midX until targetW - marginX) {
                    val edgeVal = edges[row + x]
                    if (edgeVal > threshold) {
                        val distScore = ((targetW - x).toFloat() + y.toFloat()) - (edgeVal / maxEdge) * 35f
                        if (distScore < bestTRScore) {
                            bestTRScore = distScore
                            bestTR = Point2D(x.toFloat() / targetW, y.toFloat() / targetH)
                        }
                    }
                }
            }

            // Bottom-Right corner: minimize (targetW - x) + (targetH - y)
            var bestBR = Point2D(0.94f, 0.94f)
            var bestBRScore = Float.MAX_VALUE
            for (y in midY until targetH - marginY) {
                val row = y * targetW
                for (x in midX until targetW - marginX) {
                    val edgeVal = edges[row + x]
                    if (edgeVal > threshold) {
                        val distScore = ((targetW - x).toFloat() + (targetH - y).toFloat()) - (edgeVal / maxEdge) * 35f
                        if (distScore < bestBRScore) {
                            bestBRScore = distScore
                            bestBR = Point2D(x.toFloat() / targetW, y.toFloat() / targetH)
                        }
                    }
                }
            }

            // Bottom-Left corner: minimize x + (targetH - y)
            var bestBL = Point2D(0.06f, 0.94f)
            var bestBLScore = Float.MAX_VALUE
            for (y in midY until targetH - marginY) {
                val row = y * targetW
                for (x in marginX until midX) {
                    val edgeVal = edges[row + x]
                    if (edgeVal > threshold) {
                        val distScore = (x.toFloat() + (targetH - y).toFloat()) - (edgeVal / maxEdge) * 35f
                        if (distScore < bestBLScore) {
                            bestBLScore = distScore
                            bestBL = Point2D(x.toFloat() / targetW, y.toFloat() / targetH)
                        }
                    }
                }
            }

            // Validate convexity & dimensions
            val quad = CropQuad(
                topLeft = Point2D(bestTL.x.coerceIn(0.02f, 0.45f), bestTL.y.coerceIn(0.02f, 0.45f)),
                topRight = Point2D(bestTR.x.coerceIn(0.55f, 0.98f), bestTR.y.coerceIn(0.02f, 0.45f)),
                bottomRight = Point2D(bestBR.x.coerceIn(0.55f, 0.98f), bestBR.y.coerceIn(0.55f, 0.98f)),
                bottomLeft = Point2D(bestBL.x.coerceIn(0.02f, 0.45f), bestBL.y.coerceIn(0.55f, 0.98f))
            )

            // Ensure width and height of quad are reasonable (> 30% of image)
            val topWidth = quad.topRight.x - quad.topLeft.x
            val bottomWidth = quad.bottomRight.x - quad.bottomLeft.x
            val leftHeight = quad.bottomLeft.y - quad.topLeft.y
            val rightHeight = quad.bottomRight.y - quad.topRight.y

            if (topWidth > 0.3f && bottomWidth > 0.3f && leftHeight > 0.3f && rightHeight > 0.3f) {
                quad
            } else {
                CropQuad.defaultQuad()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            CropQuad.defaultQuad()
        }
    }
}
