package com.example.processing

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.model.FilterType
import com.example.model.PdfQuality
import com.example.model.ScannedPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

object PdfGenerator {

    private class PreparedPdfPage(
        val pageWidthPt: Int,
        val pageHeightPt: Int,
        val imgWidth: Int,
        val imgHeight: Int,
        val isGrayscale: Boolean,
        val isJpeg: Boolean, // true = DCTDecode, false = FlateDecode
        val streamBytes: ByteArray
    )

    suspend fun generatePdf(
        context: Context,
        pages: List<ScannedPage>,
        filename: String,
        quality: PdfQuality
    ): File? = withContext(Dispatchers.IO) {
        if (pages.isEmpty()) return@withContext null

        try {
            val preparedPages = ArrayList<PreparedPdfPage>(pages.size)

            // Decode cap: raised above 2400px ONLY for HIGH quality, kept at 2400px otherwise
            val decodeMaxDim = when (quality) {
                PdfQuality.HIGH -> 3200
                PdfQuality.MEDIUM -> 2400
                PdfQuality.LOW -> 2400
            }

            // Explicit JPEG quality and max-dimension cap per quality tier
            val (maxDim, jpegQuality) = when (quality) {
                PdfQuality.HIGH -> 2800 to 90
                PdfQuality.MEDIUM -> 1500 to 75
                PdfQuality.LOW -> 950 to 60
            }

            for (i in pages.indices) {
                val pageData = pages[i]

                // For HIGH quality, re-derive from original image at >2400px if available
                val loadedBitmap = if (quality == PdfQuality.HIGH && File(pageData.originalImagePath).exists()) {
                    val origBmp = ImageUtils.loadBitmapFromFile(pageData.originalImagePath, maxDimension = decodeMaxDim)
                    if (origBmp != null) {
                        val rotated = ImageUtils.rotateBitmap(origBmp, pageData.rotationDegrees.toFloat())
                        if (rotated != origBmp) origBmp.recycle()
                        val filtered = ImageFilterEngine.applyFilter(rotated, pageData.filterType)
                        if (filtered != rotated) rotated.recycle()
                        filtered
                    } else {
                        ImageUtils.loadBitmapFromFile(pageData.processedImagePath, maxDimension = decodeMaxDim)
                    }
                } else {
                    ImageUtils.loadBitmapFromFile(pageData.processedImagePath, maxDimension = decodeMaxDim)
                } ?: continue

                val scaledBmp = downscaleForPdf(loadedBitmap, maxDim)
                if (scaledBmp != loadedBitmap) {
                    loadedBitmap.recycle()
                }

                // Standard A4 dimensions in points (72 points = 1 inch)
                val a4Width = 595
                val a4Height = 842
                val isLandscape = scaledBmp.width > scaledBmp.height
                val pWidth = if (isLandscape) a4Height else a4Width
                val pHeight = if (isLandscape) a4Width else a4Height

                val useGrayscale = (pageData.filterType == FilterType.BLACK_WHITE ||
                        pageData.filterType == FilterType.GRAYSCALE)

                val prepared = if (useGrayscale) {
                    encodeGrayscalePage(scaledBmp, pWidth, pHeight, jpegQuality)
                } else {
                    encodeColorJpegPage(scaledBmp, pWidth, pHeight, jpegQuality)
                }

                scaledBmp.recycle()
                preparedPages.add(prepared)
            }

            if (preparedPages.isEmpty()) return@withContext null

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
                writePdfDocument(out, preparedPages)
                out.flush()
            }
            outputFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Downscales a bitmap proportionally if either dimension exceeds maxDim.
     */
    private fun downscaleForPdf(source: Bitmap, maxDim: Int): Bitmap {
        if (source.width <= maxDim && source.height <= maxDim) return source
        val scale = maxDim.toFloat() / maxOf(source.width, source.height).toFloat()
        val newW = (source.width * scale).toInt().coerceAtLeast(1)
        val newH = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, newW, newH, true)
    }

    /**
     * Encodes a RGB page using DCTDecode (JPEG) at the explicit quality setting.
     */
    private fun encodeColorJpegPage(
        bmp: Bitmap,
        pageW: Int,
        pageH: Int,
        jpegQuality: Int
    ): PreparedPdfPage {
        val stream = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, jpegQuality, stream)
        return PreparedPdfPage(
            pageWidthPt = pageW,
            pageHeightPt = pageH,
            imgWidth = bmp.width,
            imgHeight = bmp.height,
            isGrayscale = false,
            isJpeg = true,
            streamBytes = stream.toByteArray()
        )
    }

    /**
     * Encodes BLACK_WHITE and GRAYSCALE pages as true 8-bit single-channel (/DeviceGray) streams.
     * Compares single-channel Flate-compressed 8-bit grayscale vs quantized 8-bit grayscale
     * to guarantee genuine 1-byte-per-pixel grayscale storage without 3-channel RGB overhead.
     */
    private fun encodeGrayscalePage(
        bmp: Bitmap,
        pageW: Int,
        pageH: Int,
        jpegQuality: Int
    ): PreparedPdfPage {
        // Compress & decode once at target JPEG quality so photographic noise is smoothed according to quality tier
        val jpegBuf = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, jpegQuality, jpegBuf)
        val jpegBytes = jpegBuf.toByteArray()
        val smoothed = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: bmp

        val grayRaw = ImageFilterEngine.extractGrayscaleBytes(smoothed)
        if (smoothed != bmp) {
            smoothed.recycle()
        }

        // Quantize subtle compression ringing on pure white background back to 255 for high deflate ratio
        val step = when {
            jpegQuality >= 85 -> 1
            jpegQuality >= 70 -> 4
            else -> 8
        }
        if (step > 1) {
            for (i in grayRaw.indices) {
                val v = grayRaw[i].toInt() and 0xFF
                val q = if (v >= 248) 255 else ((v / step) * step).coerceIn(0, 255)
                grayRaw[i] = q.toByte()
            }
        } else {
            for (i in grayRaw.indices) {
                val v = grayRaw[i].toInt() and 0xFF
                if (v >= 252) grayRaw[i] = 255.toByte()
            }
        }

        val flateStream = ByteArrayOutputStream()
        DeflaterOutputStream(flateStream, Deflater(Deflater.BEST_COMPRESSION)).use { defOut ->
            defOut.write(grayRaw)
        }

        return PreparedPdfPage(
            pageWidthPt = pageW,
            pageHeightPt = pageH,
            imgWidth = bmp.width,
            imgHeight = bmp.height,
            isGrayscale = true,
            isJpeg = false,
            streamBytes = flateStream.toByteArray()
        )
    }

    /**
     * Writes a standards-compliant PDF 1.4 file directly so `/DeviceGray` 8-bit single-channel
     * and `/DeviceRGB` JPEG XObjects are embedded natively without Android PdfDocument's ARGB expansion.
     */
    private fun writePdfDocument(rawOut: OutputStream, pages: List<PreparedPdfPage>) {
        class CountingOutputStream(private val delegate: OutputStream) : OutputStream() {
            var bytesWritten: Long = 0L
            override fun write(b: Int) {
                delegate.write(b)
                bytesWritten++
            }
            override fun write(b: ByteArray, off: Int, len: Int) {
                delegate.write(b, off, len)
                bytesWritten += len
            }
        }

        val out = CountingOutputStream(rawOut)
        fun writeAscii(s: String) {
            out.write(s.toByteArray(StandardCharsets.US_ASCII))
        }

        // Object numbering:
        // 1 = Catalog, 2 = Pages
        // For page index i (0-based):
        //   pageObj = 3 + i * 3
        //   contentObj = 4 + i * 3
        //   imageObj = 5 + i * 3
        val totalObjects = 2 + pages.size * 3
        val offsets = LongArray(totalObjects + 1)

        writeAscii("%PDF-1.4\n")
        // Binary comment marker per PDF spec
        out.write(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))

        // Object 1: Catalog
        offsets[1] = out.bytesWritten
        writeAscii("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")

        // Object 2: Pages root
        val kidsRefs = pages.indices.joinToString(" ") { "${3 + it * 3} 0 R" }
        offsets[2] = out.bytesWritten
        writeAscii("2 0 obj\n<< /Type /Pages /Count ${pages.size} /Kids [ $kidsRefs ] >>\nendobj\n")

        for (i in pages.indices) {
            val p = pages[i]
            val pageObj = 3 + i * 3
            val contentObj = 4 + i * 3
            val imgObj = 5 + i * 3

            // Page object
            offsets[pageObj] = out.bytesWritten
            writeAscii(
                "$pageObj 0 obj\n" +
                        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${p.pageWidthPt} ${p.pageHeightPt}] " +
                        "/Resources << /XObject << /Im0 $imgObj 0 R >> >> " +
                        "/Contents $contentObj 0 R >>\n" +
                        "endobj\n"
            )

            // Content stream drawing /Im0 stretched across full page
            val contentStr = "q\n${p.pageWidthPt} 0 0 ${p.pageHeightPt} 0 0 cm\n/Im0 Do\nQ\n"
            val contentBytes = contentStr.toByteArray(StandardCharsets.US_ASCII)
            offsets[contentObj] = out.bytesWritten
            writeAscii("$contentObj 0 obj\n<< /Length ${contentBytes.size} >>\nstream\n")
            out.write(contentBytes)
            writeAscii("\nendstream\nendobj\n")

            // Image XObject (8-bit /DeviceGray or 8-bit /DeviceRGB)
            val colorSpace = if (p.isGrayscale) "/DeviceGray" else "/DeviceRGB"
            val filter = if (p.isJpeg) "/DCTDecode" else "/FlateDecode"
            offsets[imgObj] = out.bytesWritten
            writeAscii(
                "$imgObj 0 obj\n" +
                        "<< /Type /XObject /Subtype /Image " +
                        "/Width ${p.imgWidth} /Height ${p.imgHeight} " +
                        "/ColorSpace $colorSpace /BitsPerComponent 8 " +
                        "/Filter $filter /Length ${p.streamBytes.size} >>\n" +
                        "stream\n"
            )
            out.write(p.streamBytes)
            writeAscii("\nendstream\nendobj\n")
        }

        // Cross-reference table
        val xrefOffset = out.bytesWritten
        writeAscii("xref\n0 ${totalObjects + 1}\n")
        writeAscii("0000000000 65535 f \n")
        for (objId in 1..totalObjects) {
            val formatted = String.format(java.util.Locale.US, "%010d 00000 n \n", offsets[objId])
            writeAscii(formatted)
        }

        writeAscii(
            "trailer\n" +
                    "<< /Size ${totalObjects + 1} /Root 1 0 R >>\n" +
                    "startxref\n" +
                    "$xrefOffset\n" +
                    "%%EOF\n"
        )
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
