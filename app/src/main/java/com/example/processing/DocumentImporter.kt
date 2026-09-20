package com.example.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Xml
import com.example.model.CropQuad
import com.example.model.FilterType
import com.example.model.PdfQuality
import com.example.model.ScannedPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipFile

object DocumentImporter {

    enum class DocType {
        PDF, DOCX, PPTX, UNKNOWN
    }

    suspend fun importDocument(
        context: Context,
        uri: Uri
    ): Pair<String, List<ScannedPage>> = withContext(Dispatchers.IO) {
        val originalFileName = getFileNameFromUri(context, uri)
            ?: "Doc_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())}"

        // Save incoming stream to a temporary cached file for inspection and parsing
        val tempDir = File(context.cacheDir, "imports").apply { if (!exists()) mkdirs() }
        val tempFile = File(tempDir, "temp_${System.currentTimeMillis()}_$originalFileName")

        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw Exception("Unable to read selected document")

        val docType = detectDocType(tempFile, originalFileName, context, uri)

        try {
            when (docType) {
                DocType.PDF -> {
                    importPdfFromFile(context, tempFile, originalFileName)
                }
                DocType.DOCX -> {
                    importDocxFromFile(context, tempFile, originalFileName)
                }
                DocType.PPTX -> {
                    importPptxFromFile(context, tempFile, originalFileName)
                }
                DocType.UNKNOWN -> {
                    // Try PDF first, then DOCX, then PPTX
                    try {
                        importPdfFromFile(context, tempFile, originalFileName)
                    } catch (_: Exception) {
                        try {
                            importDocxFromFile(context, tempFile, originalFileName)
                        } catch (_: Exception) {
                            importPptxFromFile(context, tempFile, originalFileName)
                        }
                    }
                }
            }
        } finally {
            tempFile.delete()
        }
    }

    private fun detectDocType(file: File, filename: String, context: Context, uri: Uri): DocType {
        val lowerName = filename.lowercase(Locale.ROOT)
        if (lowerName.endsWith(".pdf")) return DocType.PDF
        if (lowerName.endsWith(".docx") || lowerName.endsWith(".doc")) return DocType.DOCX
        if (lowerName.endsWith(".pptx") || lowerName.endsWith(".ppt")) return DocType.PPTX

        val mime = context.contentResolver.getType(uri)?.lowercase(Locale.ROOT) ?: ""
        if (mime.contains("pdf")) return DocType.PDF
        if (mime.contains("word") || mime.contains("officedocument.wordprocessingml")) return DocType.DOCX
        if (mime.contains("presentation") || mime.contains("powerpoint")) return DocType.PPTX

        // Check magic bytes
        try {
            file.inputStream().use { input ->
                val header = ByteArray(4)
                val read = input.read(header)
                if (read >= 4) {
                    // PDF magic: %PDF
                    if (header[0] == '%'.code.toByte() && header[1] == 'P'.code.toByte() &&
                        header[2] == 'D'.code.toByte() && header[3] == 'F'.code.toByte()
                    ) {
                        return DocType.PDF
                    }
                    // ZIP magic: PK\x03\x04
                    if (header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte() &&
                        header[2] == 3.toByte() && header[3] == 4.toByte()
                    ) {
                        try {
                            ZipFile(file).use { zip ->
                                if (zip.getEntry("word/document.xml") != null) return DocType.DOCX
                                if (zip.entries().asSequence().any { it.name.startsWith("ppt/slides/slide") }) {
                                    return DocType.PPTX
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
        } catch (_: Exception) {}

        return DocType.UNKNOWN
    }

    // PDF Importer
    private fun importPdfFromFile(
        context: Context,
        sourcePdfFile: File,
        originalFileName: String
    ): Pair<String, List<ScannedPage>> {
        val sanitizedName = if (originalFileName.endsWith(".pdf", ignoreCase = true)) {
            originalFileName
        } else {
            "$originalFileName.pdf"
        }

        val pdfDir = File(context.getExternalFilesDir(null), "documents").apply {
            if (!exists()) mkdirs()
        }
        val targetPdfFile = File(pdfDir, "${System.currentTimeMillis()}_$sanitizedName")
        sourcePdfFile.copyTo(targetPdfFile, overwrite = true)

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
                val renderWidth = (page.width * 2).coerceIn(600, 2048)
                val renderHeight = (page.height * 2).coerceIn(800, 2800)

                val bitmap = Bitmap.createBitmap(renderWidth, renderHeight, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
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

        return Pair(targetPdfFile.absolutePath, pages)
    }

    // DOCX Importer & Page Renderer
    private suspend fun importDocxFromFile(
        context: Context,
        sourceDocxFile: File,
        originalFileName: String
    ): Pair<String, List<ScannedPage>> {
        val docTitle = originalFileName.removeSuffix(".docx").removeSuffix(".doc").removeSuffix(".DOCX")
        val scansDir = File(context.filesDir, "scans").apply { if (!exists()) mkdirs() }

        val pages = mutableListOf<ScannedPage>()

        ZipFile(sourceDocxFile).use { zip ->
            val docEntry = zip.getEntry("word/document.xml")
                ?: throw Exception("Invalid DOCX format: missing word/document.xml")

            // Read relation IDs for images if available
            val relsMap = mutableMapOf<String, String>()
            val relsEntry = zip.getEntry("word/_rels/document.xml.rels")
            if (relsEntry != null) {
                try {
                    zip.getInputStream(relsEntry).use { relsIn ->
                        parseRelsXml(relsIn, relsMap)
                    }
                } catch (_: Exception) {}
            }

            // Extract elements from document.xml
            val elements = mutableListOf<DocxBlock>()
            zip.getInputStream(docEntry).use { docIn ->
                parseDocxXml(docIn, elements, relsMap)
            }

            // Render elements onto A4 page bitmaps (1240 x 1754 px at 150 DPI)
            val pageWidth = 1240
            val pageHeight = 1754
            val leftMargin = 90f
            val rightMargin = 90f
            val topMargin = 120f
            val bottomMargin = 120f
            val contentWidth = (pageWidth - leftMargin - rightMargin).toInt()
            val maxContentY = pageHeight - bottomMargin

            var currentPageNumber = 1
            var currentBitmap = Bitmap.createBitmap(pageWidth, pageHeight, Bitmap.Config.ARGB_8888)
            var currentCanvas = Canvas(currentBitmap)
            currentCanvas.drawColor(Color.WHITE)
            drawPageHeader(currentCanvas, docTitle, pageWidth)

            var currentY = topMargin

            fun finishAndSavePage() {
                drawPageFooter(currentCanvas, currentPageNumber, pageWidth, pageHeight)
                val pageFile = File(scansDir, "docx_page_${UUID.randomUUID()}_$currentPageNumber.jpg")
                FileOutputStream(pageFile).use { out ->
                    currentBitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                    out.flush()
                }
                currentBitmap.recycle()

                pages.add(
                    ScannedPage(
                        id = UUID.randomUUID().toString(),
                        originalImagePath = pageFile.absolutePath,
                        processedImagePath = pageFile.absolutePath,
                        cropQuad = CropQuad.fullImage(),
                        filterType = FilterType.ORIGINAL,
                        width = pageWidth,
                        height = pageHeight
                    )
                )

                currentPageNumber++
                currentBitmap = Bitmap.createBitmap(pageWidth, pageHeight, Bitmap.Config.ARGB_8888)
                currentCanvas = Canvas(currentBitmap)
                currentCanvas.drawColor(Color.WHITE)
                drawPageHeader(currentCanvas, docTitle, pageWidth)
                currentY = topMargin
            }

            if (elements.isEmpty()) {
                // Empty doc fallback
                val paint = TextPaint().apply {
                    isAntiAlias = true
                    textSize = 28f
                    color = Color.DKGRAY
                }
                currentCanvas.drawText("Empty Document: $docTitle", leftMargin, topMargin + 60f, paint)
                finishAndSavePage()
            } else {
                for (elem in elements) {
                    when (elem) {
                        is DocxBlock.PageBreak -> {
                            if (currentY > topMargin + 50f) {
                                finishAndSavePage()
                            }
                        }
                        is DocxBlock.TextParagraph -> {
                            val text = elem.text.trim()
                            if (text.isEmpty()) {
                                currentY += 16f
                                continue
                            }

                            val fontSize = when {
                                elem.isHeading && elem.headingLevel == 0 -> 40f
                                elem.isHeading && elem.headingLevel == 1 -> 34f
                                elem.isHeading && elem.headingLevel == 2 -> 28f
                                elem.isHeading -> 26f
                                else -> 23f
                            }

                            val fontColor = when {
                                elem.isHeading -> Color.rgb(24, 32, 47)
                                else -> Color.rgb(45, 55, 72)
                            }

                            val isBold = elem.isHeading || elem.isBold
                            val displayText = if (elem.isBullet) "•  $text" else text
                            val indent = if (elem.isBullet) 30f else 0f
                            val textMaxWidth = (contentWidth - indent).toInt()

                            val textPaint = TextPaint().apply {
                                isAntiAlias = true
                                this.textSize = fontSize
                                this.color = fontColor
                                this.typeface = if (isBold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
                            }

                            val layout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                StaticLayout.Builder.obtain(displayText, 0, displayText.length, textPaint, textMaxWidth)
                                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                                    .setLineSpacing(0f, 1.25f)
                                    .setIncludePad(true)
                                    .build()
                            } else {
                                @Suppress("DEPRECATION")
                                StaticLayout(displayText, textPaint, textMaxWidth, Layout.Alignment.ALIGN_NORMAL, 1.25f, 0f, true)
                            }

                            if (currentY + layout.height > maxContentY) {
                                finishAndSavePage()
                            }

                            currentCanvas.save()
                            currentCanvas.translate(leftMargin + indent, currentY)
                            layout.draw(currentCanvas)
                            currentCanvas.restore()

                            val spacing = if (elem.isHeading) 24f else 16f
                            currentY += layout.height + spacing
                        }
                        is DocxBlock.ImageBlock -> {
                            var bmp: Bitmap? = null
                            try {
                                val entry = zip.getEntry(elem.zipEntryName)
                                if (entry != null) {
                                    zip.getInputStream(entry).use { inStream ->
                                        bmp = BitmapFactory.decodeStream(inStream)
                                    }
                                }
                            } catch (_: Exception) {}

                            if (bmp != null) {
                                val maxImgW = contentWidth.toFloat()
                                val maxImgH = 500f
                                val scale = (maxImgW / bmp!!.width).coerceAtMost(maxImgH / bmp!!.height).coerceAtMost(1.0f)
                                val drawW = bmp!!.width * scale
                                val drawH = bmp!!.height * scale

                                if (currentY + drawH > maxContentY) {
                                    finishAndSavePage()
                                }

                                val drawX = leftMargin + (contentWidth - drawW) / 2f
                                currentCanvas.drawBitmap(bmp!!, null, RectF(drawX, currentY, drawX + drawW, currentY + drawH), null)
                                currentY += drawH + 20f
                                bmp?.recycle()
                            }
                        }
                    }
                }

                // Finish remaining page
                if (currentY > topMargin) {
                    finishAndSavePage()
                }
            }
        }

        // Generate corresponding PDF for persistence
        val pdfFile = PdfGenerator.generatePdf(context, pages, docTitle, PdfQuality.MEDIUM)
        val finalPdfPath = pdfFile?.absolutePath ?: sourceDocxFile.absolutePath

        return Pair(finalPdfPath, pages)
    }

    // PPTX Importer & Slide Renderer
    private suspend fun importPptxFromFile(
        context: Context,
        sourcePptxFile: File,
        originalFileName: String
    ): Pair<String, List<ScannedPage>> {
        val presentationTitle = originalFileName.removeSuffix(".pptx").removeSuffix(".ppt").removeSuffix(".PPTX")
        val scansDir = File(context.filesDir, "scans").apply { if (!exists()) mkdirs() }

        val pages = mutableListOf<ScannedPage>()

        ZipFile(sourcePptxFile).use { zip ->
            // Collect all slides in numerical order: ppt/slides/slide1.xml, slide2.xml, ...
            val slideRegex = Regex("""ppt/slides/slide(\d+)\.xml""")
            val slideEntries = zip.entries().asSequence()
                .mapNotNull { entry ->
                    val match = slideRegex.matchEntire(entry.name)
                    if (match != null) {
                        val num = match.groupValues[1].toIntOrNull() ?: 0
                        Pair(num, entry.name)
                    } else null
                }
                .sortedBy { it.first }
                .toList()

            val slideWidth = 1920
            val slideHeight = 1080
            val emeraldColor = Color.rgb(0, 137, 123)

            for ((slideIndex, slideEntryName) in slideEntries) {
                val slideEntry = zip.getEntry(slideEntryName) ?: continue

                // Check relations for embedded images
                val relsMap = mutableMapOf<String, String>()
                val relsName = slideEntryName.replace("ppt/slides/", "ppt/slides/_rels/") + ".rels"
                val relsEntry = zip.getEntry(relsName)
                if (relsEntry != null) {
                    try {
                        zip.getInputStream(relsEntry).use { relsIn ->
                            parseRelsXml(relsIn, relsMap)
                        }
                    } catch (_: Exception) {}
                }

                // Extract text and image references for this slide
                val slideContent = SlideContent()
                zip.getInputStream(slideEntry).use { slideIn ->
                    parseSlideXml(slideIn, slideContent, relsMap)
                }

                val slideBitmap = Bitmap.createBitmap(slideWidth, slideHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(slideBitmap)

                // Elegant slide background with gradient/modern surface
                canvas.drawColor(Color.rgb(250, 250, 252))

                // Top emerald accent bar
                val accentPaint = Paint().apply {
                    color = emeraldColor
                    style = Paint.Style.FILL
                }
                canvas.drawRect(0f, 0f, slideWidth.toFloat(), 12f, accentPaint)

                // Slide Title
                val titleText = slideContent.title.ifBlank {
                    if (slideIndex == 1) presentationTitle else "Slide $slideIndex"
                }
                val titlePaint = TextPaint().apply {
                    isAntiAlias = true
                    textSize = 48f
                    color = Color.rgb(17, 24, 39)
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                }
                canvas.drawText(titleText, 100f, 110f, titlePaint)

                // Subtle title separator line
                val sepPaint = Paint().apply {
                    color = Color.rgb(226, 232, 240)
                    strokeWidth = 2f
                }
                canvas.drawLine(100f, 135f, (slideWidth - 100).toFloat(), 135f, sepPaint)

                // Content Layout
                var currentY = 190f
                val contentLeft = 100f
                val contentMaxWidth = (slideWidth - 200).toInt()

                // Load first image if present
                var embeddedImage: Bitmap? = null
                val firstImgEntry = slideContent.imageEntryNames.firstOrNull()
                if (firstImgEntry != null) {
                    try {
                        val imgEntry = zip.getEntry(firstImgEntry)
                        if (imgEntry != null) {
                            zip.getInputStream(imgEntry).use { inStream ->
                                embeddedImage = BitmapFactory.decodeStream(inStream)
                            }
                        }
                    } catch (_: Exception) {}
                }

                // If image exists, use split layout: Left text, Right image
                if (embeddedImage != null) {
                    val textColWidth = (slideWidth * 0.55f).toInt()
                    val imgColWidth = slideWidth * 0.35f
                    val imgColLeft = slideWidth * 0.60f

                    for (bullet in slideContent.bullets) {
                        val bText = bullet.trim()
                        if (bText.isBlank()) continue
                        val bPaint = TextPaint().apply {
                            isAntiAlias = true
                            textSize = 28f
                            color = Color.rgb(51, 65, 85)
                            typeface = Typeface.DEFAULT
                        }
                        val formatted = "•  $bText"
                        val layout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            StaticLayout.Builder.obtain(formatted, 0, formatted.length, bPaint, textColWidth)
                                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                                .setLineSpacing(0f, 1.3f)
                                .build()
                        } else {
                            @Suppress("DEPRECATION")
                            StaticLayout(formatted, bPaint, textColWidth, Layout.Alignment.ALIGN_NORMAL, 1.3f, 0f, true)
                        }

                        if (currentY + layout.height < slideHeight - 120f) {
                            canvas.save()
                            canvas.translate(contentLeft, currentY)
                            layout.draw(canvas)
                            canvas.restore()
                            currentY += layout.height + 22f
                        }
                    }

                    // Draw image on right column
                    val img = embeddedImage!!
                    val maxH = 650f
                    val scale = (imgColWidth / img.width).coerceAtMost(maxH / img.height).coerceAtMost(1.0f)
                    val drawW = img.width * scale
                    val drawH = img.height * scale
                    val drawY = 190f + (650f - drawH) / 2f
                    canvas.drawBitmap(img, null, RectF(imgColLeft, drawY, imgColLeft + drawW, drawY + drawH), null)
                    img.recycle()
                } else {
                    // Full-width text bullets
                    for (bullet in slideContent.bullets) {
                        val bText = bullet.trim()
                        if (bText.isBlank()) continue
                        val bPaint = TextPaint().apply {
                            isAntiAlias = true
                            textSize = 32f
                            color = Color.rgb(51, 65, 85)
                            typeface = Typeface.DEFAULT
                        }
                        val formatted = "•  $bText"
                        val layout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            StaticLayout.Builder.obtain(formatted, 0, formatted.length, bPaint, contentMaxWidth)
                                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                                .setLineSpacing(0f, 1.35f)
                                .build()
                        } else {
                            @Suppress("DEPRECATION")
                            StaticLayout(formatted, bPaint, contentMaxWidth, Layout.Alignment.ALIGN_NORMAL, 1.35f, 0f, true)
                        }

                        if (currentY + layout.height < slideHeight - 100f) {
                            canvas.save()
                            canvas.translate(contentLeft, currentY)
                            layout.draw(canvas)
                            canvas.restore()
                            currentY += layout.height + 26f
                        }
                    }
                }

                // Slide Footer
                val footerPaint = TextPaint().apply {
                    isAntiAlias = true
                    textSize = 22f
                    color = Color.rgb(148, 163, 184)
                }
                canvas.drawText(presentationTitle, 100f, (slideHeight - 40).toFloat(), footerPaint)
                val slideNumText = "Slide $slideIndex of ${slideEntries.size}"
                val numWidth = footerPaint.measureText(slideNumText)
                canvas.drawText(slideNumText, slideWidth - 100f - numWidth, (slideHeight - 40).toFloat(), footerPaint)

                // Save slide image
                val slideFile = File(scansDir, "pptx_slide_${UUID.randomUUID()}_$slideIndex.jpg")
                FileOutputStream(slideFile).use { out ->
                    slideBitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                    out.flush()
                }
                slideBitmap.recycle()

                pages.add(
                    ScannedPage(
                        id = UUID.randomUUID().toString(),
                        originalImagePath = slideFile.absolutePath,
                        processedImagePath = slideFile.absolutePath,
                        cropQuad = CropQuad.fullImage(),
                        filterType = FilterType.ORIGINAL,
                        width = slideWidth,
                        height = slideHeight
                    )
                )
            }
        }

        // Generate PDF
        val pdfFile = PdfGenerator.generatePdf(context, pages, presentationTitle, PdfQuality.MEDIUM)
        val finalPdfPath = pdfFile?.absolutePath ?: sourcePptxFile.absolutePath

        return Pair(finalPdfPath, pages)
    }

    // XML Parsing Helpers
    private fun parseRelsXml(inputStream: InputStream, relsMap: MutableMap<String, String>) {
        val parser = Xml.newPullParser()
        parser.setInput(inputStream, "UTF-8")
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                val name = parser.name ?: ""
                if (name.equals("Relationship", ignoreCase = true) || name.endsWith(":Relationship")) {
                    val id = parser.getAttributeValue(null, "Id")
                    val target = parser.getAttributeValue(null, "Target")
                    if (id != null && target != null) {
                        relsMap[id] = target
                    }
                }
            }
            event = parser.next()
        }
    }

    private sealed class DocxBlock {
        data class TextParagraph(
            val text: String,
            val isHeading: Boolean = false,
            val headingLevel: Int = 0,
            val isBullet: Boolean = false,
            val isBold: Boolean = false
        ) : DocxBlock()

        data class ImageBlock(val zipEntryName: String) : DocxBlock()
        object PageBreak : DocxBlock()
    }

    private fun parseDocxXml(
        inputStream: InputStream,
        elements: MutableList<DocxBlock>,
        relsMap: Map<String, String>
    ) {
        val parser = Xml.newPullParser()
        parser.setInput(inputStream, "UTF-8")
        var event = parser.eventType

        var inParagraph = false
        var currentText = StringBuilder()
        var isHeading = false
        var headingLevel = 0
        var isBullet = false
        var isBold = false

        while (event != XmlPullParser.END_DOCUMENT) {
            val tag = (parser.name ?: "").lowercase(Locale.ROOT)
            val cleanTag = if (tag.contains(":")) tag.substringAfter(":") else tag

            when (event) {
                XmlPullParser.START_TAG -> {
                    when (cleanTag) {
                        "p" -> {
                            inParagraph = true
                            currentText.clear()
                            isHeading = false
                            headingLevel = 0
                            isBullet = false
                            isBold = false
                        }
                        "pstyle" -> {
                            val v = parser.getAttributeValue(null, "val")
                                ?: parser.getAttributeValue(null, "w:val") ?: ""
                            val low = v.lowercase(Locale.ROOT)
                            if (low.contains("heading") || low.contains("title")) {
                                isHeading = true
                                headingLevel = when {
                                    low.contains("title") -> 0
                                    low.contains("1") -> 1
                                    low.contains("2") -> 2
                                    else -> 3
                                }
                            }
                        }
                        "numpr" -> {
                            isBullet = true
                        }
                        "b" -> {
                            val v = parser.getAttributeValue(null, "val") ?: "1"
                            if (v != "0" && v != "false") isBold = true
                        }
                        "br" -> {
                            val type = parser.getAttributeValue(null, "type") ?: ""
                            if (type.equals("page", ignoreCase = true)) {
                                elements.add(DocxBlock.PageBreak)
                            }
                        }
                        "blip" -> {
                            val embedId = parser.getAttributeValue(null, "embed")
                                ?: parser.getAttributeValue(null, "r:embed")
                            if (embedId != null) {
                                val target = relsMap[embedId]
                                if (target != null) {
                                    val fullPath = "word/" + target.removePrefix("word/").removePrefix("/")
                                    elements.add(DocxBlock.ImageBlock(fullPath))
                                }
                            }
                        }
                        "t" -> {
                            if (inParagraph) {
                                try {
                                    val t = parser.nextText()
                                    currentText.append(t)
                                } catch (_: Exception) {}
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (cleanTag == "p" && inParagraph) {
                        val text = currentText.toString().trim()
                        if (text.isNotEmpty()) {
                            elements.add(
                                DocxBlock.TextParagraph(
                                    text = text,
                                    isHeading = isHeading,
                                    headingLevel = headingLevel,
                                    isBullet = isBullet,
                                    isBold = isBold
                                )
                            )
                        }
                        inParagraph = false
                        currentText.clear()
                    }
                }
            }
            event = parser.next()
        }
    }

    private class SlideContent {
        var title: String = ""
        val bullets = mutableListOf<String>()
        val imageEntryNames = mutableListOf<String>()
    }

    private fun parseSlideXml(
        inputStream: InputStream,
        content: SlideContent,
        relsMap: Map<String, String>
    ) {
        val parser = Xml.newPullParser()
        parser.setInput(inputStream, "UTF-8")
        var event = parser.eventType

        var currentText = StringBuilder()
        var isTitleShape = false

        while (event != XmlPullParser.END_DOCUMENT) {
            val tag = (parser.name ?: "").lowercase(Locale.ROOT)
            val cleanTag = if (tag.contains(":")) tag.substringAfter(":") else tag

            when (event) {
                XmlPullParser.START_TAG -> {
                    when (cleanTag) {
                        "sp" -> {
                            isTitleShape = false
                        }
                        "ph" -> {
                            val type = parser.getAttributeValue(null, "type") ?: ""
                            if (type.contains("title", ignoreCase = true) || type.contains("ctrTitle", ignoreCase = true)) {
                                isTitleShape = true
                            }
                        }
                        "p" -> {
                            currentText.clear()
                        }
                        "t" -> {
                            try {
                                val t = parser.nextText()
                                currentText.append(t)
                            } catch (_: Exception) {}
                        }
                        "blip" -> {
                            val embedId = parser.getAttributeValue(null, "embed")
                                ?: parser.getAttributeValue(null, "r:embed")
                            if (embedId != null) {
                                val target = relsMap[embedId]
                                if (target != null) {
                                    val fullPath = "ppt/" + target.removePrefix("../").removePrefix("ppt/").removePrefix("/")
                                    content.imageEntryNames.add(fullPath)
                                }
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (cleanTag == "p") {
                        val text = currentText.toString().trim()
                        if (text.isNotEmpty()) {
                            if (isTitleShape && content.title.isEmpty()) {
                                content.title = text
                            } else {
                                content.bullets.add(text)
                            }
                        }
                        currentText.clear()
                    }
                }
            }
            event = parser.next()
        }
    }

    private fun drawPageHeader(canvas: Canvas, docTitle: String, pageWidth: Int) {
        val paint = TextPaint().apply {
            isAntiAlias = true
            textSize = 20f
            color = Color.rgb(156, 163, 175)
        }
        canvas.drawText(docTitle, 90f, 65f, paint)

        val linePaint = Paint().apply {
            color = Color.rgb(229, 231, 235)
            strokeWidth = 1.5f
        }
        canvas.drawLine(90f, 85f, (pageWidth - 90).toFloat(), 85f, linePaint)
    }

    private fun drawPageFooter(canvas: Canvas, pageNumber: Int, pageWidth: Int, pageHeight: Int) {
        val linePaint = Paint().apply {
            color = Color.rgb(229, 231, 235)
            strokeWidth = 1.5f
        }
        canvas.drawLine(90f, (pageHeight - 85).toFloat(), (pageWidth - 90).toFloat(), (pageHeight - 85).toFloat(), linePaint)

        val paint = TextPaint().apply {
            isAntiAlias = true
            textSize = 20f
            color = Color.rgb(156, 163, 175)
        }
        val text = "Page $pageNumber"
        val textWidth = paint.measureText(text)
        canvas.drawText(text, (pageWidth - textWidth) / 2f, (pageHeight - 55).toFloat(), paint)
    }

    fun getFileNameFromUri(context: Context, uri: Uri): String? {
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
