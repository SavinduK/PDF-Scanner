package com.example.processing

import android.graphics.Bitmap
import com.example.model.CropQuad
import com.example.model.Point2D
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

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

            // 4. Sobel Gradients (Gx, Gy) and Magnitude Calculation
            val gxArr = FloatArray(targetW * targetH)
            val gyArr = FloatArray(targetW * targetH)
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

                    val idx = rowCurr + x
                    gxArr[idx] = gx
                    gyArr[idx] = gy
                    val mag = abs(gx) + abs(gy)
                    edges[idx] = mag
                    if (mag > maxEdge) maxEdge = mag
                }
            }

            // Fallback if background has almost zero contrast
            if (maxEdge < 15f) {
                return@withContext CropQuad.defaultQuad()
            }

            val highThreshold = maxEdge * 0.20f
            val lowThreshold = highThreshold * 0.40f

            // 5. Phase 5: Canny NMS + Hysteresis + Largest 4-sided Contour Detection
            val cannyEdges = cannyEdgeMap(edges, gxArr, gyArr, targetW, targetH, highThreshold, lowThreshold)
            var coarseQuad = detectContourQuad(cannyEdges, targetW, targetH)

            // Fallback to existing ray-casting method if contour detection did not find a valid 4-gon
            if (coarseQuad == null) {
                coarseQuad = detectRayCastQuad(edges, targetW, targetH, highThreshold)
            }

            // If ray-casting also failed, return default frame
            if (coarseQuad == null) {
                return@withContext CropQuad.defaultQuad()
            }

            // 6. Phase 2: Full-resolution corner refinement around scaled coarse corners
            val refinedQuad = refineCornersFullRes(bitmap, coarseQuad, targetW, targetH)

            // Final sanity & dimension validation
            if (isValidQuadDimensions(refinedQuad)) {
                refinedQuad
            } else if (isValidQuadDimensions(coarseQuad)) {
                coarseQuad
            } else {
                CropQuad.defaultQuad()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            CropQuad.defaultQuad()
        }
    }

    /**
     * Performs Canny-style Non-Maximum Suppression along quantized gradient directions
     * followed by Hysteresis Thresholding (high = 20% max, low = 40% high).
     */
    private fun cannyEdgeMap(
        mag: FloatArray,
        gx: FloatArray,
        gy: FloatArray,
        w: Int,
        h: Int,
        highThresh: Float,
        lowThresh: Float
    ): BooleanArray {
        val nms = FloatArray(w * h)
        // tan(22.5°) ≈ 0.4142, tan(67.5°) ≈ 2.4142
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val idx = row + x
                val m = mag[idx]
                if (m < lowThresh) continue

                val dx = gx[idx]
                val dy = gy[idx]
                val absDx = abs(dx)
                val absDy = abs(dy)

                val n1: Float
                val n2: Float
                if (absDy <= 0.4142f * absDx) {
                    // 0 degrees (horizontal gradient -> vertical edge)
                    n1 = mag[idx - 1]
                    n2 = mag[idx + 1]
                } else if (absDy >= 2.4142f * absDx) {
                    // 90 degrees (vertical gradient -> horizontal edge)
                    n1 = mag[idx - w]
                    n2 = mag[idx + w]
                } else if ((dx > 0f && dy > 0f) || (dx < 0f && dy < 0f)) {
                    // 45 degrees diagonal
                    n1 = mag[idx - w - 1]
                    n2 = mag[idx + w + 1]
                } else {
                    // 135 degrees diagonal
                    n1 = mag[idx - w + 1]
                    n2 = mag[idx + w - 1]
                }

                if (m >= n1 && m >= n2) {
                    nms[idx] = m
                }
            }
        }

        // Hysteresis thresholding via BFS from strong edges
        val state = ByteArray(w * h) // 0 = none, 1 = weak, 2 = strong
        val queue = IntArray(w * h)
        var head = 0
        var tail = 0

        for (i in nms.indices) {
            val v = nms[i]
            if (v >= highThresh) {
                state[i] = 2
                queue[tail++] = i
            } else if (v >= lowThresh) {
                state[i] = 1
            }
        }

        while (head < tail) {
            val curr = queue[head++]
            val cx = curr % w
            val cy = curr / w
            for (ny in max(1, cy - 1)..min(h - 2, cy + 1)) {
                val nRow = ny * w
                for (nx in max(1, cx - 1)..min(w - 2, cx + 1)) {
                    val nIdx = nRow + nx
                    if (state[nIdx] == 1.toByte()) {
                        state[nIdx] = 2
                        queue[tail++] = nIdx
                    }
                }
            }
        }

        val edgeOut = BooleanArray(w * h)
        for (i in state.indices) {
            edgeOut[i] = (state[i] == 2.toByte())
        }
        return edgeOut
    }

    /**
     * Traces 8-connected edge components, computes each component's convex hull,
     * simplifies the hull to 4 vertices via Douglas-Peucker, orders TL/TR/BR/BL,
     * and returns the largest valid quadrilateral (>= 20% frame area, ~60°-120° angles).
     */
    private fun detectContourQuad(edges: BooleanArray, w: Int, h: Int): CropQuad? {
        val visited = BooleanArray(w * h)
        val queue = IntArray(w * h)
        val compX = IntArray(w * h)
        val compY = IntArray(w * h)

        val frameArea = (w * h).toFloat()
        var bestArea = 0f
        var bestQuad: CropQuad? = null

        for (y in 2 until h - 2) {
            val row = y * w
            for (x in 2 until w - 2) {
                val startIdx = row + x
                if (!edges[startIdx] || visited[startIdx]) continue

                var head = 0
                var tail = 0
                var count = 0
                visited[startIdx] = true
                queue[tail++] = startIdx

                while (head < tail) {
                    val curr = queue[head++]
                    val cx = curr % w
                    val cy = curr / w
                    compX[count] = cx
                    compY[count] = cy
                    count++

                    for (ny in max(1, cy - 1)..min(h - 2, cy + 1)) {
                        val nRow = ny * w
                        for (nx in max(1, cx - 1)..min(w - 2, cx + 1)) {
                            val nIdx = nRow + nx
                            if (edges[nIdx] && !visited[nIdx]) {
                                visited[nIdx] = true
                                queue[tail++] = nIdx
                            }
                        }
                    }
                }

                // Ignore tiny edge fragments
                if (count < 40) continue

                val hull = computeConvexHull(compX, compY, count)
                if (hull.size < 4) continue

                val quadPts = simplifyHullToQuad(hull) ?: continue
                val ordered = orderQuadCorners(quadPts)

                val area = polygonArea(ordered)
                if (area < frameArea * 0.20f) continue
                if (!hasValidInteriorAngles(ordered, minDeg = 60f, maxDeg = 120f)) continue

                if (area > bestArea) {
                    bestArea = area
                    bestQuad = CropQuad(
                        topLeft = Point2D((ordered[0].x / w).coerceIn(0.01f, 0.99f), (ordered[0].y / h).coerceIn(0.01f, 0.99f)),
                        topRight = Point2D((ordered[1].x / w).coerceIn(0.01f, 0.99f), (ordered[1].y / h).coerceIn(0.01f, 0.99f)),
                        bottomRight = Point2D((ordered[2].x / w).coerceIn(0.01f, 0.99f), (ordered[2].y / h).coerceIn(0.01f, 0.99f)),
                        bottomLeft = Point2D((ordered[3].x / w).coerceIn(0.01f, 0.99f), (ordered[3].y / h).coerceIn(0.01f, 0.99f))
                    )
                }
            }
        }
        return bestQuad
    }

    /**
     * Computes the 2D convex hull of a set of integer points using Andrew's Monotone Chain.
     */
    private fun computeConvexHull(xs: IntArray, ys: IntArray, count: Int): List<Point2D> {
        val packed = LongArray(count)
        for (i in 0 until count) {
            packed[i] = (xs[i].toLong() shl 32) or (ys[i].toLong() and 0xFFFFFFFFL)
        }
        packed.sort()

        val pts = ArrayList<Point2D>(count)
        var prev = -1L
        for (i in 0 until count) {
            if (packed[i] != prev) {
                prev = packed[i]
                val px = (prev ushr 32).toInt().toFloat()
                val py = (prev and 0xFFFFFFFFL).toInt().toFloat()
                pts.add(Point2D(px, py))
            }
        }
        val n = pts.size
        if (n < 3) return pts

        fun cross(o: Point2D, a: Point2D, b: Point2D): Float {
            return (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
        }

        val hull = ArrayList<Point2D>(2 * n)
        for (i in 0 until n) {
            while (hull.size >= 2 && cross(hull[hull.size - 2], hull[hull.size - 1], pts[i]) <= 0f) {
                hull.removeAt(hull.size - 1)
            }
            hull.add(pts[i])
        }
        val lowerSize = hull.size + 1
        for (i in n - 2 downTo 0) {
            while (hull.size >= lowerSize && cross(hull[hull.size - 2], hull[hull.size - 1], pts[i]) <= 0f) {
                hull.removeAt(hull.size - 1)
            }
            hull.add(pts[i])
        }
        hull.removeAt(hull.size - 1)
        return hull
    }

    /**
     * Simplifies a closed convex hull polygon to 4 corners using binary-searched
     * Douglas-Peucker epsilon tuning.
     */
    private fun simplifyHullToQuad(hull: List<Point2D>): List<Point2D>? {
        if (hull.size == 4) return hull

        var perimeter = 0f
        for (i in hull.indices) {
            val a = hull[i]
            val b = hull[(i + 1) % hull.size]
            perimeter += hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()).toFloat()
        }

        var lowEps = perimeter * 0.005f
        var highEps = perimeter * 0.25f
        var bestFallback: List<Point2D>? = null

        repeat(18) {
            val midEps = (lowEps + highEps) * 0.5f
            val simplified = douglasPeuckerClosed(hull, midEps)
            when {
                simplified.size == 4 -> return simplified
                simplified.size > 4 -> {
                    if (bestFallback == null || simplified.size < bestFallback!!.size) {
                        bestFallback = simplified
                    }
                    lowEps = midEps
                }
                else -> highEps = midEps
            }
        }

        // If binary search landed on 5 or 6 vertices, iteratively drop vertex with smallest triangle area
        val candidate = bestFallback?.toMutableList() ?: return null
        while (candidate.size > 4) {
            var minTriArea = Float.MAX_VALUE
            var removeIdx = 0
            for (i in candidate.indices) {
                val prev = candidate[(i - 1 + candidate.size) % candidate.size]
                val curr = candidate[i]
                val next = candidate[(i + 1) % candidate.size]
                val triArea = abs((curr.x - prev.x) * (next.y - prev.y) - (curr.y - prev.y) * (next.x - prev.x))
                if (triArea < minTriArea) {
                    minTriArea = triArea
                    removeIdx = i
                }
            }
            candidate.removeAt(removeIdx)
        }
        return if (candidate.size == 4) candidate else null
    }

    /**
     * Closed-polygon Ramer-Douglas-Peucker simplification anchored at the two farthest hull points.
     */
    private fun douglasPeuckerClosed(poly: List<Point2D>, epsilon: Float): List<Point2D> {
        val n = poly.size
        if (n <= 4) return poly

        // Find diametrically opposite pair of points on the hull
        var idxA = 0
        var idxB = n / 2
        var maxDistSq = -1f
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val dx = poly[i].x - poly[j].x
                val dy = poly[i].y - poly[j].y
                val dSq = dx * dx + dy * dy
                if (dSq > maxDistSq) {
                    maxDistSq = dSq
                    idxA = i
                    idxB = j
                }
            }
        }

        val chain1 = ArrayList<Point2D>()
        var idx = idxA
        while (true) {
            chain1.add(poly[idx])
            if (idx == idxB) break
            idx = (idx + 1) % n
        }

        val chain2 = ArrayList<Point2D>()
        idx = idxB
        while (true) {
            chain2.add(poly[idx])
            if (idx == idxA) break
            idx = (idx + 1) % n
        }

        val simp1 = douglasPeuckerOpen(chain1, epsilon)
        val simp2 = douglasPeuckerOpen(chain2, epsilon)

        val merged = ArrayList<Point2D>(simp1.size + simp2.size - 2)
        for (i in 0 until simp1.size - 1) merged.add(simp1[i])
        for (i in 0 until simp2.size - 1) merged.add(simp2[i])
        return merged
    }

    /**
     * Standard open-polyline Ramer-Douglas-Peucker recursion.
     */
    private fun douglasPeuckerOpen(pts: List<Point2D>, epsilon: Float): List<Point2D> {
        if (pts.size <= 2) return pts
        val first = pts.first()
        val last = pts.last()
        var maxDist = 0f
        var index = 0

        val dx = last.x - first.x
        val dy = last.y - first.y
        val len = hypot(dx.toDouble(), dy.toDouble()).toFloat()

        for (i in 1 until pts.size - 1) {
            val p = pts[i]
            val dist = if (len < 1e-4f) {
                hypot((p.x - first.x).toDouble(), (p.y - first.y).toDouble()).toFloat()
            } else {
                abs(dy * p.x - dx * p.y + last.x * first.y - last.y * first.x) / len
            }
            if (dist > maxDist) {
                maxDist = dist
                index = i
            }
        }

        return if (maxDist > epsilon) {
            val left = douglasPeuckerOpen(pts.subList(0, index + 1), epsilon)
            val right = douglasPeuckerOpen(pts.subList(index, pts.size), epsilon)
            left.dropLast(1) + right
        } else {
            listOf(first, last)
        }
    }

    /**
     * Orders 4 vertices consistently as Top-Left, Top-Right, Bottom-Right, Bottom-Left.
     */
    private fun orderQuadCorners(pts: List<Point2D>): List<Point2D> {
        val sortedByY = pts.sortedBy { it.y }
        val topTwo = sortedByY.take(2).sortedBy { it.x }
        val bottomTwo = sortedByY.takeLast(2).sortedBy { it.x }
        return listOf(topTwo[0], topTwo[1], bottomTwo[1], bottomTwo[0])
    }

    /**
     * Computes the area of a polygon using the Shoelace formula.
     */
    private fun polygonArea(pts: List<Point2D>): Float {
        var sum = 0f
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[(i + 1) % pts.size]
            sum += (a.x * b.y - b.x * a.y)
        }
        return abs(sum) * 0.5f
    }

    /**
     * Checks that all 4 interior angles lie within [minDeg, maxDeg] (e.g., 60°..120°).
     */
    private fun hasValidInteriorAngles(pts: List<Point2D>, minDeg: Float, maxDeg: Float): Boolean {
        if (pts.size != 4) return false
        for (i in 0 until 4) {
            val prev = pts[(i + 3) % 4]
            val curr = pts[i]
            val next = pts[(i + 1) % 4]

            val v1x = prev.x - curr.x
            val v1y = prev.y - curr.y
            val v2x = next.x - curr.x
            val v2y = next.y - curr.y

            val len1 = sqrt(v1x * v1x + v1y * v1y)
            val len2 = sqrt(v2x * v2x + v2y * v2y)
            if (len1 < 8f || len2 < 8f) return false

            val cosAngle = ((v1x * v2x + v1y * v2y) / (len1 * len2)).coerceIn(-1f, 1f)
            val deg = (acos(cosAngle.toDouble()) * 180.0 / PI).toFloat()
            if (deg < minDeg || deg > maxDeg) return false
        }
        return true
    }

    /**
     * Fallback ray-casting boundary search from 4 margins inward with local outward-biased corner scoring.
     */
    private fun detectRayCastQuad(edges: FloatArray, targetW: Int, targetH: Int, threshold: Float): CropQuad? {
        val minMarginX = (targetW * 0.03f).toInt()
        val minMarginY = (targetH * 0.03f).toInt()
        val maxMarginX = (targetW * 0.42f).toInt()
        val maxMarginY = (targetH * 0.42f).toInt()

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
            topBoundaryY = topCandidates[topCandidates.size / 4]
        }

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
            bottomBoundaryY = bottomCandidates[(bottomCandidates.size * 3) / 4]
        }

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

        fun findLocalCorner(searchCenterX: Int, searchCenterY: Int, dxSign: Int, dySign: Int): Point2D {
            var bestX = searchCenterX.toFloat()
            var bestY = searchCenterY.toFloat()
            var maxScore = -1f
            val range = 25

            for (dy in -range..range) {
                val cy = (searchCenterY + dy).coerceIn(1, targetH - 2)
                for (dx in -range..range) {
                    val cx = (searchCenterX + dx).coerceIn(1, targetW - 2)
                    val edgeVal = edges[cy * targetW + cx]
                    if (edgeVal > threshold * 0.7f) {
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

        val quad = CropQuad(
            topLeft = Point2D(rawTL.x.coerceIn(0.02f, 0.45f), rawTL.y.coerceIn(0.02f, 0.45f)),
            topRight = Point2D(rawTR.x.coerceIn(0.55f, 0.98f), rawTR.y.coerceIn(0.02f, 0.45f)),
            bottomRight = Point2D(rawBR.x.coerceIn(0.55f, 0.98f), rawBR.y.coerceIn(0.55f, 0.98f)),
            bottomLeft = Point2D(rawBL.x.coerceIn(0.02f, 0.45f), rawBL.y.coerceIn(0.55f, 0.98f))
        )
        return if (isValidQuadDimensions(quad)) quad else null
    }

    /**
     * Refines each coarse corner at full bitmap resolution within a ±(scaleFactor + 3) px window,
     * computing local Sobel gradients and reusing the outward-biased corner score.
     */
    private fun refineCornersFullRes(
        bitmap: Bitmap,
        coarse: CropQuad,
        smallW: Int,
        smallH: Int
    ): CropQuad {
        val fullW = bitmap.width
        val fullH = bitmap.height
        val scaleFactor = max(
            (fullW.toFloat() / smallW.toFloat()).roundToInt(),
            (fullH.toFloat() / smallH.toFloat()).roundToInt()
        ).coerceAtLeast(1)

        if (scaleFactor <= 1) return coarse
        val searchRadius = (scaleFactor + 3).coerceIn(4, 48)

        fun refineSingleCorner(normPt: Point2D, dxSign: Int, dySign: Int): Point2D {
            val centerX = (normPt.x * fullW).roundToInt().coerceIn(0, fullW - 1)
            val centerY = (normPt.y * fullH).roundToInt().coerceIn(0, fullH - 1)

            // Extract patch with 1-px border for Sobel 3x3 neighborhood
            val patchLeft = (centerX - searchRadius - 1).coerceIn(0, fullW - 1)
            val patchTop = (centerY - searchRadius - 1).coerceIn(0, fullH - 1)
            val patchRight = (centerX + searchRadius + 1).coerceIn(0, fullW - 1)
            val patchBottom = (centerY + searchRadius + 1).coerceIn(0, fullH - 1)

            val pw = patchRight - patchLeft + 1
            val ph = patchBottom - patchTop + 1
            if (pw < 5 || ph < 5) return normPt

            val patchPixels = IntArray(pw * ph)
            bitmap.getPixels(patchPixels, 0, pw, patchLeft, patchTop, pw, ph)

            val lum = FloatArray(pw * ph)
            for (i in patchPixels.indices) {
                val c = patchPixels[i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                lum[i] = 0.299f * r + 0.587f * g + 0.114f * b
            }

            val grad = FloatArray(pw * ph)
            var localMaxEdge = 0f
            for (py in 1 until ph - 1) {
                val rPrev = (py - 1) * pw
                val rCurr = py * pw
                val rNext = (py + 1) * pw
                for (px in 1 until pw - 1) {
                    val gx = (lum[rPrev + px + 1] + 2f * lum[rCurr + px + 1] + lum[rNext + px + 1]) -
                            (lum[rPrev + px - 1] + 2f * lum[rCurr + px - 1] + lum[rNext + px - 1])
                    val gy = (lum[rNext + px - 1] + 2f * lum[rNext + px] + lum[rNext + px + 1]) -
                            (lum[rPrev + px - 1] + 2f * lum[rPrev + px] + lum[rPrev + px + 1])
                    val mag = abs(gx) + abs(gy)
                    grad[rCurr + px] = mag
                    if (mag > localMaxEdge) localMaxEdge = mag
                }
            }

            if (localMaxEdge < 15f) return normPt
            val localThresh = localMaxEdge * 0.20f

            var bestX = centerX.toFloat()
            var bestY = centerY.toFloat()
            var bestScore = -Float.MAX_VALUE

            for (py in 1 until ph - 1) {
                val imgY = patchTop + py
                val rCurr = py * pw
                for (px in 1 until pw - 1) {
                    val imgX = patchLeft + px
                    val edgeVal = grad[rCurr + px]
                    if (edgeVal >= localThresh * 0.7f) {
                        val relX = (imgX - centerX).toFloat()
                        val relY = (imgY - centerY).toFloat()
                        val outwardBias = (relX * dxSign + relY * dySign) * 0.15f
                        val score = edgeVal + outwardBias
                        if (score > bestScore) {
                            bestScore = score
                            bestX = imgX.toFloat()
                            bestY = imgY.toFloat()
                        }
                    }
                }
            }

            return Point2D(
                (bestX / fullW.toFloat()).coerceIn(0f, 1f),
                (bestY / fullH.toFloat()).coerceIn(0f, 1f)
            )
        }

        return CropQuad(
            topLeft = refineSingleCorner(coarse.topLeft, -1, -1),
            topRight = refineSingleCorner(coarse.topRight, 1, -1),
            bottomRight = refineSingleCorner(coarse.bottomRight, 1, 1),
            bottomLeft = refineSingleCorner(coarse.bottomLeft, -1, 1)
        )
    }

    /**
     * Validates that the detected quadrilateral spans a reasonable portion of the frame.
     */
    private fun isValidQuadDimensions(quad: CropQuad): Boolean {
        val topWidth = quad.topRight.x - quad.topLeft.x
        val bottomWidth = quad.bottomRight.x - quad.bottomLeft.x
        val leftHeight = quad.bottomLeft.y - quad.topLeft.y
        val rightHeight = quad.bottomRight.y - quad.topRight.y
        return topWidth > 0.30f && bottomWidth > 0.30f && leftHeight > 0.30f && rightHeight > 0.30f
    }
}
