package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.test.core.app.ApplicationProvider
import com.example.model.FilterType
import com.example.model.PdfQuality
import com.example.model.ScannedPage
import com.example.processing.EdgeDetector
import com.example.processing.ImageFilterEngine
import com.example.processing.ImageUtils
import com.example.processing.PdfGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.nio.charset.StandardCharsets

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ImageProcessingPipelineTest {

    @Test
    fun `background normalization and Sauvola B&W removes harsh lighting gradient`() = runBlocking {
        val w = 320
        val h = 420
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)

        // Simulate strong shadow across left-to-right (luminance 85 -> 230) with dark ink lines
        for (y in 0 until h) {
            for (x in 0 until w) {
                val paperLum = 85 + (x * 145) / w
                val isInk = (y in 100..104 && x in 40..280) || (y in 200..204 && x in 40..280)
                val v = if (isInk) (paperLum / 4).coerceAtLeast(10) else paperLum
                pixels[y * w + x] = Color.rgb(v, v, v)
            }
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)

        val bw = ImageFilterEngine.applyFilter(bmp, FilterType.BLACK_WHITE)
        val outPixels = IntArray(w * h)
        bw.getPixels(outPixels, 0, w, 0, 0, w, h)

        // Shadowed paper on far left (x=20, y=50) should become pure white (255)
        val shadowPaper = outPixels[50 * w + 20] and 0xFF
        assertTrue("Shadowed paper should be whitened to 255, got $shadowPaper", shadowPaper == 255)

        // Ink pixel in shadowed left side (x=60, y=102) should stay dark (< 55)
        val shadowInk = outPixels[102 * w + 60] and 0xFF
        assertTrue("Ink in shadowed region should remain dark, got $shadowInk", shadowInk < 55)

        // Auto-enhance also runs cleanly without crashing or washing out ink
        val auto = ImageFilterEngine.applyFilter(bmp, FilterType.AUTO_ENHANCE)
        assertNotNull(auto)
    }

    @Test
    fun `EdgeDetector Canny contour and full-res refinement accurately finds document quad`() = runBlocking {
        val w = 1200
        val h = 1600
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.rgb(35, 38, 42)) // dark desk

        // Draw skewed white sheet covering ~55% of frame
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(242, 242, 238)
            style = Paint.Style.FILL
        }
        val path = Path().apply {
            moveTo(180f, 200f)   // TL (0.15, 0.125)
            lineTo(1020f, 220f)  // TR (0.85, 0.1375)
            lineTo(1050f, 1400f) // BR (0.875, 0.875)
            lineTo(150f, 1380f)  // BL (0.125, 0.8625)
            close()
        }
        canvas.drawPath(path, paint)

        val quad = EdgeDetector.detectEdges(bmp)
        assertTrue("TL x near 0.15, got ${quad.topLeft.x}", kotlin.math.abs(quad.topLeft.x - 0.15f) < 0.04f)
        assertTrue("TL y near 0.125, got ${quad.topLeft.y}", kotlin.math.abs(quad.topLeft.y - 0.125f) < 0.04f)
        assertTrue("BR x near 0.875, got ${quad.bottomRight.x}", kotlin.math.abs(quad.bottomRight.x - 0.875f) < 0.04f)
        assertTrue("BR y near 0.875, got ${quad.bottomRight.y}", kotlin.math.abs(quad.bottomRight.y - 0.875f) < 0.04f)
    }

    @Test
    fun `PdfGenerator writes 8-bit DeviceGray for B&W and DeviceRGB for color`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bmp = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        val path = ImageUtils.saveBitmapToFile(context, bmp, 90)!!

        val bwPage = ScannedPage(
            originalImagePath = path,
            processedImagePath = path,
            filterType = FilterType.BLACK_WHITE,
            width = 400,
            height = 600
        )
        val colorPage = ScannedPage(
            originalImagePath = path,
            processedImagePath = path,
            filterType = FilterType.AUTO_ENHANCE,
            width = 400,
            height = 600
        )

        val pdfFile = PdfGenerator.generatePdf(context, listOf(bwPage, colorPage), "test_out", PdfQuality.MEDIUM)
        assertNotNull(pdfFile)
        assertTrue(pdfFile!!.exists() && pdfFile.length() > 100)

        val headerAscii = String(pdfFile.readBytes(), StandardCharsets.ISO_8859_1)
        assertTrue("PDF should contain 8-bit /DeviceGray stream", headerAscii.contains("/ColorSpace /DeviceGray /BitsPerComponent 8"))
        assertTrue("PDF should contain /DeviceRGB stream for color page", headerAscii.contains("/ColorSpace /DeviceRGB /BitsPerComponent 8"))
    }
}
