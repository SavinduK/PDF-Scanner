package com.example.processing

import android.graphics.Bitmap
import com.example.model.CropQuad
import com.example.model.Point2D
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object EdgeDetector {

    suspend fun detectEdges(bitmap: Bitmap): CropQuad = withContext(Dispatchers.Default) {
        try {
            val origW = bitmap.width
            val origH = bitmap.height
            if (origW < 50 || origH < 50) return@withContext CropQuad.defaultQuad()

            // 1. Downsample image to ~300px wide for fast & reliable edge processing
            val targetW = 300
            val targetH = ((origH.toFloat() / origW.toFloat()) * targetW).toInt().coerceIn(180, 500)
            val smallBmp = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)

            val pixels = IntArray(targetW * targetH)
            smallBmp.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
            if (smallBmp != bitmap) {
                smallBmp.recycle()
            }

            // 2. Convert to Grayscale luminance
            val rawGray = FloatArray(targetW * targetH)
            for (i in pixels.indices) {
                val c = pixels[i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                rawGray[i] = 0.299f * r + 0.587f * g + 0.114f * b
            }

            // 3. 3x3 Box Blur Filter to remove high-frequency noise & text details
            val gray = FloatArray(targetW * targetH)
            for (y in 1 until targetH - 1) {
                val rowPrev = (y - 1) * targetW
                val rowCurr = y * targetW
                val rowNext = (y + 1) * targetW
                for (x in 1 until targetW - 1) {
                    val sum = rawGray[rowPrev + x - 1] + rawGray[rowPrev + x] + rawGray[rowPrev + x + 1] +
                            rawGray[rowCurr + x - 1] + rawGray[rowCurr + x] + rawGray[rowCurr + x + 1] +
                            rawGray[rowNext + x - 1] + rawGray[rowNext + x] + rawGray[rowNext + x + 1]
                    gray[rowCurr + x] = sum / 9f
                }
            }

            // 4. Sobel Gradient Magnitude Calculation
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

            // Fallback if background has almost zero contrast
            if (maxEdge < 15f) {
                return@withContext CropQuad.defaultQuad()
            }

            val threshold = maxEdge * 0.20f

            // 5. Detect Document Boundaries by scanning inward from 4 sides towards center
            val minMarginX = (targetW * 0.03f).toInt()
            val minMarginY = (targetH * 0.03f).toInt()
            val maxMarginX = (targetW * 0.42f).toInt()
            val maxMarginY = (targetH * 0.42f).toInt()

            val centerY = targetH / 2
            val centerX = targetW / 2

            // Top boundary search along vertical slices
            var topBoundaryY = minMarginY
            val topCandidates = mutableListOf<Int>()
            for (x in (targetW * 0.2f).toInt()..(targetW * 0.8f).toInt() step 5) {
                for (y in minMarginY..maxMarginY) {
                    if (edges[y * targetW + x] > threshold) {
                        topCandidates.add(y)
                        break
                    }
                }
            }
            if (topCandidates.isNotEmpty()) {
                topCandidates.sort()
                topBoundaryY = topCandidates[topCandidates.size / 4] // 25th percentile
            }

            // Bottom boundary search
            var bottomBoundaryY = targetH - minMarginY
            val bottomCandidates = mutableListOf<Int>()
            for (x in (targetW * 0.2f).toInt()..(targetW * 0.8f).toInt() step 5) {
                for (y in (targetH - minMarginY) downTo (targetH - maxMarginY)) {
                    if (edges[y * targetW + x] > threshold) {
                        bottomCandidates.add(y)
                        break
                    }
                }
            }
            if (bottomCandidates.isNotEmpty()) {
                bottomCandidates.sort()
                bottomBoundaryY = bottomCandidates[(bottomCandidates.size * 3) / 4] // 75th percentile
            }

            // Left boundary search
            var leftBoundaryX = minMarginX
            val leftCandidates = mutableListOf<Int>()
            for (y in (targetH * 0.2f).toInt()..(targetH * 0.8f).toInt() step 5) {
                for (x in minMarginX..maxMarginX) {
                    if (edges[y * targetW + x] > threshold) {
                        leftCandidates.add(x)
                        break
                    }
                }
            }
            if (leftCandidates.isNotEmpty()) {
                leftCandidates.sort()
                leftBoundaryX = leftCandidates[leftCandidates.size / 4]
            }

            // Right boundary search
            var rightBoundaryX = targetW - minMarginX
            val rightCandidates = mutableListOf<Int>()
            for (y in (targetH * 0.2f).toInt()..(targetH * 0.8f).toInt() step 5) {
                for (x in (targetW - minMarginX) downTo (targetW - maxMarginX)) {
                    if (edges[y * targetW + x] > threshold) {
                        rightCandidates.add(x)
                        break
                    }
                }
            }
            if (rightCandidates.isNotEmpty()) {
                rightCandidates.sort()
                rightBoundaryX = rightCandidates[(rightCandidates.size * 3) / 4]
            }

            // 6. Corner refinement based on boundary intersections and corner edge response
            fun findLocalCorner(searchCenterX: Int, searchCenterY: Int, dxSign: Int, dySign: Int): Point2D {
                var bestX = searchCenterX.toFloat()
                var bestY = searchCenterY.toFloat()
                var maxScore = -1f

                val rangeX = 25
                val rangeY = 25

                for (dy in -rangeY..rangeY) {
                    val cy = (searchCenterY + dy).coerceIn(1, targetH - 2)
                    for (dx in -rangeX..rangeX) {
                        val cx = (searchCenterX + dx).coerceIn(1, targetW - 2)
                        val edgeVal = edges[cy * targetW + cx]
                        if (edgeVal > threshold * 0.7f) {
                            // Weight score by edge value and distance outward
                            val outwardBias = (cx * dxSign + cy * dySign) * 0.15f
                            val score = edgeVal + outwardBias
                            if (score > maxScore) {
                                maxScore = score
                                bestX = cx.toFloat()
                                bestY = cy.toFloat()
                            }
                        }
                    }
                }

                return Point2D(bestX / targetW, bestY / targetH)
            }

            val rawTL = findLocalCorner(leftBoundaryX, topBoundaryY, -1, -1)
            val rawTR = findLocalCorner(rightBoundaryX, topBoundaryY, 1, -1)
            val rawBR = findLocalCorner(rightBoundaryX, bottomBoundaryY, 1, 1)
            val rawBL = findLocalCorner(leftBoundaryX, bottomBoundaryY, -1, 1)

            // Sanity check coordinates to guarantee a clean quadrilateral
            val quad = CropQuad(
                topLeft = Point2D(rawTL.x.coerceIn(0.02f, 0.45f), rawTL.y.coerceIn(0.02f, 0.45f)),
                topRight = Point2D(rawTR.x.coerceIn(0.55f, 0.98f), rawTR.y.coerceIn(0.02f, 0.45f)),
                bottomRight = Point2D(rawBR.x.coerceIn(0.55f, 0.98f), rawBR.y.coerceIn(0.55f, 0.98f)),
                bottomLeft = Point2D(rawBL.x.coerceIn(0.02f, 0.45f), rawBL.y.coerceIn(0.55f, 0.98f))
            )

            // Validate dimensions
            val topWidth = quad.topRight.x - quad.topLeft.x
            val bottomWidth = quad.bottomRight.x - quad.bottomLeft.x
            val leftHeight = quad.bottomLeft.y - quad.topLeft.y
            val rightHeight = quad.bottomRight.y - quad.topRight.y

            if (topWidth > 0.30f && bottomWidth > 0.30f && leftHeight > 0.30f && rightHeight > 0.30f) {
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
