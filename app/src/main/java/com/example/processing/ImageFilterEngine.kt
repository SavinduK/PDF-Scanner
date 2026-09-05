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
     * High-contrast document binarization:
     * Whitens the paper background and darkens ink/text for crisp readability.
     */
    private fun applyBlackAndWhite(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        // Calculate histogram of luminance
        val histogram = IntArray(256)
        val lum = IntArray(pixels.size)

        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val l = (0.299f * r + 0.587f * g + 0.114f * b).toInt().coerceIn(0, 255)
            lum[i] = l
            histogram[l]++
        }

        // Otsu's thresholding
        var total = pixels.size
        var sum = 0f
        for (t in 0..255) sum += t * histogram[t]

        var sumB = 0f
        var wB = 0
        var varMax = 0f
        var threshold = 128

        for (t in 0..255) {
            wB += histogram[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break

            sumB += (t * histogram[t]).toFloat()
            val mB = sumB / wB
            val mF = (sum - sumB) / wF

            val varBetween = wB.toFloat() * wF.toFloat() * (mB - mF) * (mB - mF)
            if (varBetween > varMax) {
                varMax = varBetween
                threshold = t
            }
        }

        // Bias threshold slightly upward so grayish paper becomes clean white
        val adjustedThreshold = (threshold * 1.08f).toInt().coerceIn(70, 210)

        for (i in pixels.indices) {
            val l = lum[i]
            val colorVal = if (l < adjustedThreshold) {
                // Smooth falloff for text edges
                val factor = l.toFloat() / adjustedThreshold
                (factor * 35).toInt().coerceIn(0, 45)
            } else {
                255
            }
            pixels[i] = (0xFF shl 24) or (colorVal shl 16) or (colorVal shl 8) or colorVal
        }

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(pixels, 0, width, 0, 0, width, height)
        return output
    }

    /**
     * Magic Color / Auto-Enhance:
     * Levels adjustment to whiten paper backgrounds while retaining colors of stamps/signatures
     * and boosting clarity.
     */
    private fun applyAutoEnhance(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        // Sample luminance to compute white and black points
        val step = max(1, pixels.size / 20000)
        val sampleLuminance = ArrayList<Int>(pixels.size / step + 1)
        for (i in 0 until pixels.size step step) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            sampleLuminance.add((0.299f * r + 0.587f * g + 0.114f * b).toInt())
        }
        sampleLuminance.sort()

        // 3rd percentile for black point, 88th percentile for paper white point
        val blackIdx = (sampleLuminance.size * 0.03f).toInt().coerceIn(0, sampleLuminance.size - 1)
        val whiteIdx = (sampleLuminance.size * 0.88f).toInt().coerceIn(0, sampleLuminance.size - 1)

        val blackPt = sampleLuminance[blackIdx].toFloat()
        val whitePt = max(blackPt + 30f, sampleLuminance[whiteIdx].toFloat())
        val range = whitePt - blackPt

        // Apply levels mapping with slight gamma boost for contrast
        for (i in pixels.indices) {
            val c = pixels[i]
            val a = (c shr 24) and 0xFF
            var r = (c shr 16) and 0xFF
            var g = (c shr 8) and 0xFF
            var b = c and 0xFF

            // Map each channel through levels
            r = (((r - blackPt) / range) * 255f).toInt().coerceIn(0, 255)
            g = (((g - blackPt) / range) * 255f).toInt().coerceIn(0, 255)
            b = (((b - blackPt) / range) * 255f).toInt().coerceIn(0, 255)

            // Slight saturation boost
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
}
