package com.example.processing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.example.model.FilterType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object ImageFilterEngine {

    suspend fun applyFilter(source: Bitmap, filterType: FilterType): Bitmap = withContext(Dispatchers.Default) {
        when (filterType) {
            FilterType.ORIGINAL -> {
                source.copy(source.config ?: Bitmap.Config.ARGB_8888, true)
            }
            FilterType.GRAYSCALE -> {
                applyGrayscale(source)
            }
            FilterType.BLACK_WHITE -> {
                applyBlackAndWhite(source)
            }
            FilterType.AUTO_ENHANCE -> {
                applyAutoEnhance(source)
            }
        }
    }

    private fun applyGrayscale(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint()
        val colorMatrix = ColorMatrix().apply { setSaturation(0f) }
        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
        canvas.drawBitmap(source, 0f, 0f, paint)
        return output
    }

    /**
     * Estimates local paper illumination by downscaling ~1/8x, applying multi-pass box blur,
     * and bilinearly upscaling back to full resolution.
     */
    private fun estimateBackground(lum: IntArray, width: Int, height: Int): IntArray {
        val smallW = max(8, width / 8)
        val smallH = max(8, height / 8)
        val small = FloatArray(smallW * smallH)
        val counts = IntArray(smallW * smallH)

        // Area-average downsample to 1/8 scale
        for (y in 0 until height) {
            val sy = (y * smallH) / height
            val rowOff = y * width
            val sRowOff = sy * smallW
            for (x in 0 until width) {
                val sx = (x * smallW) / width
                val idx = sRowOff + sx
                small[idx] += lum[rowOff + x].toFloat()
                counts[idx]++
            }
        }
        for (i in small.indices) {
            if (counts[i] > 0) small[i] /= counts[i].toFloat()
        }

        // 3 passes of separable 1D box blur (approximates a strong Gaussian blur)
        val radius = max(3, min(smallW, smallH) / 10)
        val temp = FloatArray(smallW * smallH)
        repeat(3) {
            // Horizontal pass
            for (y in 0 until smallH) {
                val row = y * smallW
                var sum = 0f
                var count = 0
                for (x in 0..min(radius, smallW - 1)) {
                    sum += small[row + x]
                    count++
                }
                for (x in 0 until smallW) {
                    temp[row + x] = sum / count
                    val removeIdx = x - radius
                    if (removeIdx >= 0) {
                        sum -= small[row + removeIdx]
                        count--
                    }
                    val addIdx = x + radius + 1
                    if (addIdx < smallW) {
                        sum += small[row + addIdx]
                        count++
                    }
                }
            }
            // Vertical pass
            for (x in 0 until smallW) {
                var sum = 0f
                var count = 0
                for (y in 0..min(radius, smallH - 1)) {
                    sum += temp[y * smallW + x]
                    count++
                }
                for (y in 0 until smallH) {
                    small[y * smallW + x] = sum / count
                    val removeIdx = y - radius
                    if (removeIdx >= 0) {
                        sum -= temp[removeIdx * smallW + x]
                        count--
                    }
                    val addIdx = y + radius + 1
                    if (addIdx < smallH) {
                        sum += temp[addIdx * smallW + x]
                        count++
                    }
                }
            }
        }

        // Bilinear upscale back to full resolution
        val bg = IntArray(width * height)
        val scaleX = smallW.toFloat() / width.toFloat()
        val scaleY = smallH.toFloat() / height.toFloat()

        for (y in 0 until height) {
            val fy = (y + 0.5f) * scaleY - 0.5f
            val y0 = fy.toInt().coerceIn(0, smallH - 1)
            val y1 = (y0 + 1).coerceIn(0, smallH - 1)
            val wy = (fy - y0).coerceIn(0f, 1f)
            val row0 = y0 * smallW
            val row1 = y1 * smallW
            val outRow = y * width

            for (x in 0 until width) {
                val fx = (x + 0.5f) * scaleX - 0.5f
                val x0 = fx.toInt().coerceIn(0, smallW - 1)
                val x1 = (x0 + 1).coerceIn(0, smallW - 1)
                val wx = (fx - x0).coerceIn(0f, 1f)

                val top = small[row0 + x0] * (1f - wx) + small[row0 + x1] * wx
                val bot = small[row1 + x0] * (1f - wx) + small[row1 + x1] * wx
                val interp = top * (1f - wy) + bot * wy
                bg[outRow + x] = interp.toInt().coerceIn(1, 255)
            }
        }
        return bg
    }

    /**
     * Normalizes luminance using the estimated background illumination:
     * out = clamp(255 * luminance / background, 0, 255).
     */
    private fun normalizeIllumination(lum: IntArray, width: Int, height: Int): IntArray {
        val bg = estimateBackground(lum, width, height)
        val norm = IntArray(lum.size)
        for (i in lum.indices) {
            val b = max(1, bg[i])
            norm[i] = ((255 * lum[i]) / b).coerceIn(0, 255)
        }
        return norm
    }

    /**
     * High-contrast document binarization using background illumination normalization
     * followed by O(n) Sauvola local thresholding via integral images, preserving soft ink roll-off.
     */
    private fun applyBlackAndWhite(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val size = width * height
        val pixels = IntArray(size)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        val rawLum = IntArray(size)
        for (i in 0 until size) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            rawLum[i] = ((77 * r + 150 * g + 29 * b) shr 8).coerceIn(0, 255)
        }

        // Normalize shadows and uneven lighting first
        val normLum = normalizeIllumination(rawLum, width, height)

        // Build integral images (sum and squared sum) with width+1, height+1 stride
        val stride = width + 1
        val integralSize = stride * (height + 1)
        val integralSum = LongArray(integralSize)
        val integralSqSum = LongArray(integralSize)

        for (y in 1..height) {
            var rowSum = 0L
            var rowSqSum = 0L
            val srcRow = (y - 1) * width
            val currRow = y * stride
            val prevRow = (y - 1) * stride
            for (x in 1..width) {
                val v = normLum[srcRow + (x - 1)].toLong()
                rowSum += v
                rowSqSum += v * v
                integralSum[currRow + x] = integralSum[prevRow + x] + rowSum
                integralSqSum[currRow + x] = integralSqSum[prevRow + x] + rowSqSum
            }
        }

        // Sauvola parameters: window = 29px (halfRadius = 14), k = 0.25, R = 128
        val halfWin = 14
        val k = 0.25f
        val rDynamic = 128f

        for (y in 0 until height) {
            val y0 = max(0, y - halfWin)
            val y1 = min(height - 1, y + halfWin) + 1
            val row0 = y0 * stride
            val row1 = y1 * stride
            val outRow = y * width

            for (x in 0 until width) {
                val x0 = max(0, x - halfWin)
                val x1 = min(width - 1, x + halfWin) + 1
                val area = (x1 - x0) * (y1 - y0)

                val sum = integralSum[row1 + x1] - integralSum[row0 + x1] -
                        integralSum[row1 + x0] + integralSum[row0 + x0]
                val sqSum = integralSqSum[row1 + x1] - integralSqSum[row0 + x1] -
                        integralSqSum[row1 + x0] + integralSqSum[row0 + x0]

                val mean = sum.toFloat() / area.toFloat()
                val variance = max(0f, (sqSum.toFloat() / area.toFloat()) - mean * mean)
                val stdDev = sqrt(variance)

                val threshold = (mean * (1f + k * ((stdDev / rDynamic) - 1f))).coerceIn(40f, 245f)
                val idx = outRow + x
                val l = normLum[idx]

                val colorVal = if (l < threshold) {
                    // Soft anti-aliased roll-off for smooth character strokes
                    val factor = l.toFloat() / max(1f, threshold)
                    (factor * 38f).toInt().coerceIn(0, 48)
                } else {
                    255
                }
                pixels[idx] = (0xFF shl 24) or (colorVal shl 16) or (colorVal shl 8) or colorVal
            }
        }

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(pixels, 0, width, 0, 0, width, height)
        return output
    }

    /**
     * Magic Color / Auto-Enhance:
     * Normalizes background illumination to remove shadows first, then applies percentile
     * levels stretching and saturation boost while preserving colored stamps and ink.
     */
    private fun applyAutoEnhance(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val size = width * height
        val pixels = IntArray(size)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        val rawLum = IntArray(size)
        for (i in 0 until size) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            rawLum[i] = ((77 * r + 150 * g + 29 * b) shr 8).coerceIn(0, 255)
        }

        // Background estimate to flatten uneven lighting across RGB channels
        val bg = estimateBackground(rawLum, width, height)
        val normLum = IntArray(size)
        for (i in 0 until size) {
            normLum[i] = ((255 * rawLum[i]) / max(1, bg[i])).coerceIn(0, 255)
        }

        // Sample normalized luminance to compute white and black points
        val step = max(1, size / 20000)
        val sampleCount = (size + step - 1) / step
        val sampleLuminance = IntArray(sampleCount)
        var sIdx = 0
        var pIdx = 0
        while (pIdx < size && sIdx < sampleCount) {
            sampleLuminance[sIdx++] = normLum[pIdx]
            pIdx += step
        }
        sampleLuminance.sort(0, sIdx)

        val validCount = max(1, sIdx)
        val blackIdx = (validCount * 0.03f).toInt().coerceIn(0, validCount - 1)
        val whiteIdx = (validCount * 0.88f).toInt().coerceIn(0, validCount - 1)

        val blackPt = sampleLuminance[blackIdx].toFloat()
        val whitePt = max(blackPt + 30f, sampleLuminance[whiteIdx].toFloat())
        val range = whitePt - blackPt

        // Apply illumination gain + levels mapping + slight saturation boost
        for (i in 0 until size) {
            val c = pixels[i]
            val a = (c shr 24) and 0xFF
            var r = (c shr 16) and 0xFF
            var g = (c shr 8) and 0xFF
            var b = c and 0xFF

            val bgVal = max(1, bg[i])
            r = ((255 * r) / bgVal).coerceIn(0, 255)
            g = ((255 * g) / bgVal).coerceIn(0, 255)
            b = ((255 * b) / bgVal).coerceIn(0, 255)

            r = (((r - blackPt) / range) * 255f).toInt().coerceIn(0, 255)
            g = (((g - blackPt) / range) * 255f).toInt().coerceIn(0, 255)
            b = (((b - blackPt) / range) * 255f).toInt().coerceIn(0, 255)

            val gray = 0.299f * r + 0.587f * g + 0.114f * b
            val satBoost = 1.15f
            r = (gray + (r - gray) * satBoost).toInt().coerceIn(0, 255)
            g = (gray + (g - gray) * satBoost).toInt().coerceIn(0, 255)
            b = (gray + (b - gray) * satBoost).toInt().coerceIn(0, 255)

            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(pixels, 0, width, 0, 0, width, height)
        return output
    }

    /**
     * Converts an ARGB bitmap into a true 8-bit single-channel grayscale PNG/JPEG-ready bitmap
     * or returns grayscale-packed pixels for compact 8-bit grayscale PDF/image encoding.
     */
    fun extractGrayscaleBytes(source: Bitmap): ByteArray {
        val width = source.width
        val height = source.height
        val size = width * height
        val pixels = IntArray(size)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val gray = ByteArray(size)
        for (i in 0 until size) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            gray[i] = (((77 * r + 150 * g + 29 * b) shr 8).coerceIn(0, 255)).toByte()
        }
        return gray
    }
}
