package com.example.processing

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.model.PdfQuality
import com.example.model.ScannedPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

object PdfGenerator {

    suspend fun generatePdf(
        context: Context,
        pages: List<ScannedPage>,
        filename: String,
        quality: PdfQuality
    ): File? = withContext(Dispatchers.IO) {
        if (pages.isEmpty()) return@withContext null

        val pdfDoc = PdfDocument()
        try {
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

            for (i in pages.indices) {
                val pageData = pages[i]
                val loadedBitmap = ImageUtils.loadBitmapFromFile(pageData.processedImagePath) ?: continue

                // Apply compression / scaling according to chosen quality
                val compressedBmp = compressForPdf(loadedBitmap, quality)

                // Standard A4 dimensions in points (72 points = 1 inch)
                val a4Width = 595
                val a4Height = 842

                // Calculate orientation: if image is wider than tall, use landscape page
                val isLandscape = compressedBmp.width > compressedBmp.height
                val pWidth = if (isLandscape) a4Height else a4Width
                val pHeight = if (isLandscape) a4Width else a4Height

                val pageInfo = PdfDocument.PageInfo.Builder(pWidth, pHeight, i + 1).create()
                val page = pdfDoc.startPage(pageInfo)
                val canvas = page.canvas

                // Draw stretched edge-to-edge covering the full page without white margins
                canvas.drawBitmap(compressedBmp, null, RectF(0f, 0f, pWidth.toFloat(), pHeight.toFloat()), paint)
                pdfDoc.finishPage(page)

                if (compressedBmp != loadedBitmap) {
                    compressedBmp.recycle()
                }
                loadedBitmap.recycle()
            }

            // Ensure destination directory
            val pdfDir = File(context.getExternalFilesDir(null), "documents").apply {
                if (!exists()) mkdirs()
            }
            val sanitizedName = if (filename.endsWith(".pdf", ignoreCase = true)) {
                filename
            } else {
                "$filename.pdf"
            }
            val outputFile = File(pdfDir, sanitizedName)

            FileOutputStream(outputFile).use { out ->
                pdfDoc.writeTo(out)
                out.flush()
            }
            outputFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            try {
                pdfDoc.close()
            } catch (_: Exception) { }
        }
    }

    private fun compressForPdf(source: Bitmap, quality: PdfQuality): Bitmap {
        val maxDim = when (quality) {
            PdfQuality.HIGH -> 2048
            PdfQuality.MEDIUM -> 1400
            PdfQuality.LOW -> 900
        }

        var bmpToCompress = source
        if (source.width > maxDim || source.height > maxDim) {
            val scale = maxDim.toFloat() / maxOf(source.width, source.height)
            val newW = (source.width * scale).toInt()
            val newH = (source.height * scale).toInt()
            bmpToCompress = Bitmap.createScaledBitmap(source, newW, newH, true)
        }

        val stream = ByteArrayOutputStream()
        bmpToCompress.compress(Bitmap.CompressFormat.JPEG, quality.compressionQuality, stream)
        val byteArray = stream.toByteArray()

        val compressed = BitmapFactory.decodeStream(ByteArrayInputStream(byteArray))
        if (bmpToCompress != source && bmpToCompress != compressed) {
            bmpToCompress.recycle()
        }
        return compressed ?: source
    }

    fun sharePdf(context: Context, pdfFile: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                pdfFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, pdfFile.nameWithoutExtension)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(shareIntent, "Share PDF Document").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun viewPdf(context: Context, pdfFile: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                pdfFile
            )

            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(viewIntent)
        } catch (e: Exception) {
            // If no native PDF viewer installed, fallback to share
            sharePdf(context, pdfFile)
        }
    }
}
