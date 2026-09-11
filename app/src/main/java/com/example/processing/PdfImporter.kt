package com.example.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.example.model.CropQuad
import com.example.model.FilterType
import com.example.model.ScannedPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object PdfImporter {

    suspend fun importPdf(
        context: Context,
        uri: Uri
    ): Pair<String, List<ScannedPage>> = withContext(Dispatchers.IO) {
        val originalFileName = getFileNameFromUri(context, uri)
            ?: "Doc_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())}.pdf"

        val sanitizedName = if (originalFileName.endsWith(".pdf", ignoreCase = true)) {
            originalFileName
        } else {
            "$originalFileName.pdf"
        }

        // Save PDF to documents directory
        val pdfDir = File(context.getExternalFilesDir(null), "documents").apply {
            if (!exists()) mkdirs()
        }
        val targetPdfFile = File(pdfDir, "${System.currentTimeMillis()}_$sanitizedName")

        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(targetPdfFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw Exception("Unable to open PDF stream")

        // Render each page into bitmap
        val scansDir = File(context.filesDir, "scans").apply {
            if (!exists()) mkdirs()
        }

        val pages = mutableListOf<ScannedPage>()
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null

        try {
            pfd = ParcelFileDescriptor.open(targetPdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            val pageCount = renderer.pageCount

            for (i in 0 until pageCount) {
                val page = renderer.openPage(i)
                // Calculate rendering dimensions for crisp display
                val renderWidth = (page.width * 2).coerceIn(600, 2048)
                val renderHeight = (page.height * 2).coerceIn(800, 2800)

                val bitmap = Bitmap.createBitmap(renderWidth, renderHeight, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()

                val pageFile = File(scansDir, "pdf_page_${UUID.randomUUID()}_$i.jpg")
                FileOutputStream(pageFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                    out.flush()
                }
                bitmap.recycle()

                pages.add(
                    ScannedPage(
                        id = UUID.randomUUID().toString(),
                        originalImagePath = pageFile.absolutePath,
                        processedImagePath = pageFile.absolutePath,
                        cropQuad = CropQuad.fullImage(),
                        filterType = FilterType.ORIGINAL,
                        width = renderWidth,
                        height = renderHeight
                    )
                )
            }
        } finally {
            try {
                renderer?.close()
                pfd?.close()
            } catch (_: Exception) {}
        }

        Pair(targetPdfFile.absolutePath, pages)
    }

    private fun getFileNameFromUri(context: Context, uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index != -1) {
                            result = cursor.getString(index)
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        if (result == null) {
            val path = uri.path
            val cut = path?.lastIndexOf('/')
            if (cut != null && cut != -1) {
                result = path.substring(cut + 1)
            }
        }
        return result
    }
}
