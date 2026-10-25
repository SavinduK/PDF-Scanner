package com.example

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.model.FilterType
import com.example.model.PdfQuality
import com.example.model.ScannedPage
import com.example.model.ScreenState
import com.example.processing.DocumentScannerManager
import com.example.processing.ImageFilterEngine
import com.example.processing.ImageUtils
import com.example.processing.PdfGenerator
import com.example.ui.ScannerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
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

        val shadowPaper = outPixels[50 * w + 20] and 0xFF
        assertTrue("Shadowed paper should be whitened to 255, got $shadowPaper", shadowPaper == 255)

        val shadowInk = outPixels[102 * w + 60] and 0xFF
        assertTrue("Ink in shadowed region should remain dark, got $shadowInk", shadowInk < 55)

        val auto = ImageFilterEngine.applyFilter(bmp, FilterType.AUTO_ENHANCE)
        assertNotNull(auto)
    }

    @Test
    fun `DocumentScannerManager options and ViewModel ML Kit scan ingestion copy pages as ORIGINAL`() = runBlocking {
        assertNotNull(DocumentScannerManager.options)

        val app = ApplicationProvider.getApplicationContext<Application>()
        val tempJpeg = File(app.cacheDir, "mock_mlkit_page.jpg")
        val bmp = Bitmap.createBitmap(800, 1100, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(245, 245, 245))
        }
        FileOutputStream(tempJpeg).use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }

        val tempPdf = File(app.cacheDir, "mock_mlkit_ready.pdf")
        tempPdf.writeBytes("%PDF-1.4\n%%EOF\n".toByteArray(StandardCharsets.US_ASCII))

        val viewModel = ScannerViewModel(app)
        viewModel.onMlKitScanCompleted(
            pageUris = listOf(Uri.fromFile(tempJpeg)),
            readyPdfUri = Uri.fromFile(tempPdf),
            startNewSession = true
        )

        var attempts = 0
        while ((viewModel.uiState.value.isLoading || viewModel.uiState.value.currentPages.isEmpty()) && attempts < 40) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            delay(25)
            attempts++
        }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        val state = viewModel.uiState.value
        assertEquals(ScreenState.PAGE_LIST, state.currentScreen)
        assertEquals(1, state.currentPages.size)
        assertEquals(FilterType.ORIGINAL, state.currentPages[0].filterType)
        assertTrue(File(state.currentPages[0].originalImagePath).exists())
        assertTrue(File(state.currentPages[0].processedImagePath).exists())
        assertNotNull(state.mlKitReadyPdfPath)
        assertTrue(File(state.mlKitReadyPdfPath!!).exists())
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
