package com.example.processing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import com.example.model.CropQuad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.max

object PerspectiveTransformer {

    suspend fun transform(source: Bitmap, quad: CropQuad): Bitmap = withContext(Dispatchers.Default) {
        val srcW = source.width.toFloat()
        val srcH = source.height.toFloat()

        // Absolute corner coordinates in source bitmap
        val tlX = quad.topLeft.x * srcW
        val tlY = quad.topLeft.y * srcH

        val trX = quad.topRight.x * srcW
        val trY = quad.topRight.y * srcH

        val brX = quad.bottomRight.x * srcW
        val brY = quad.bottomRight.y * srcH

        val blX = quad.bottomLeft.x * srcW
        val blY = quad.bottomLeft.y * srcH

        // Calculate destination dimensions
        val widthBottom = hypot((brX - blX).toDouble(), (brY - blY).toDouble()).toFloat()
        val widthTop = hypot((trX - tlX).toDouble(), (trY - tlY).toDouble()).toFloat()
        val targetWidth = max(widthTop, widthBottom).toInt().coerceIn(100, 4096)

        val heightRight = hypot((trX - brX).toDouble(), (trY - brY).toDouble()).toFloat()
        val heightLeft = hypot((tlX - blX).toDouble(), (tlY - blY).toDouble()).toFloat()
        val targetHeight = max(heightLeft, heightRight).toInt().coerceIn(100, 4096)

        val srcPoints = floatArrayOf(
            tlX, tlY,
            trX, trY,
            brX, brY,
            blX, blY
        )

        val dstPoints = floatArrayOf(
            0f, 0f,
            targetWidth.toFloat(), 0f,
            targetWidth.toFloat(), targetHeight.toFloat(),
            0f, targetHeight.toFloat()
        )

        val matrix = Matrix()
        val success = matrix.setPolyToPoly(srcPoints, 0, dstPoints, 0, 4)

        if (!success) {
            // Fallback to bounding rect crop
            val minX = max(0f, minOf(tlX, trX, brX, blX)).toInt()
            val minY = max(0f, minOf(tlY, trY, brY, blY)).toInt()
            val maxX = minOf(srcW - 1, maxOf(tlX, trX, brX, blX)).toInt()
            val maxY = minOf(srcH - 1, maxOf(tlY, trY, brY, blY)).toInt()
            val w = max(10, maxX - minX)
            val h = max(10, maxY - minY)
            return@withContext Bitmap.createBitmap(source, minX, minY, w, h)
        }

        val output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)

        val clipPath = Path().apply {
            moveTo(0f, 0f)
            lineTo(targetWidth.toFloat(), 0f)
            lineTo(targetWidth.toFloat(), targetHeight.toFloat())
            lineTo(0f, targetHeight.toFloat())
            close()
        }
        canvas.clipPath(clipPath)
        canvas.drawBitmap(source, matrix, paint)

        output
    }
}
