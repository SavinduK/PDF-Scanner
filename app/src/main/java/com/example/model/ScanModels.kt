package com.example.model

import android.graphics.PointF

data class Point2D(val x: Float, val y: Float) {
    fun toPointF(): PointF = PointF(x, y)
}

/**
 * Normalized quadrilateral coordinates (0.0f to 1.0f relative to image width and height)
 */
data class CropQuad(
    val topLeft: Point2D = Point2D(0.05f, 0.05f),
    val topRight: Point2D = Point2D(0.95f, 0.05f),
    val bottomRight: Point2D = Point2D(0.95f, 0.95f),
    val bottomLeft: Point2D = Point2D(0.05f, 0.95f)
) {
    companion object {
        fun defaultQuad(): CropQuad = CropQuad()
        fun fullImage(): CropQuad = CropQuad(
            topLeft = Point2D(0f, 0f),
            topRight = Point2D(1f, 0f),
            bottomRight = Point2D(1f, 1f),
            bottomLeft = Point2D(0f, 1f)
        )
    }
}

enum class FilterType(val displayName: String) {
    ORIGINAL("Original"),
    AUTO_ENHANCE("Magic Color"),
    BLACK_WHITE("B & W"),
    GRAYSCALE("Grayscale")
}

enum class PdfQuality(val label: String, val compressionQuality: Int, val description: String) {
    HIGH("High Quality", 92, "Best for text & fine details (~1-3 MB/page)"),
    MEDIUM("Standard", 75, "Balanced size & clarity (~400-800 KB/page)"),
    LOW("Compact", 50, "Smallest file size (~150-300 KB/page)")
}

data class ScannedPage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val originalImagePath: String,
    val processedImagePath: String,
    val cropQuad: CropQuad = CropQuad.defaultQuad(),
    val filterType: FilterType = FilterType.ORIGINAL,
    val rotationDegrees: Int = 0,
    val width: Int = 0,
    val height: Int = 0
)

data class ScannedDocument(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val pages: List<ScannedPage> = emptyList(),
    val lastPdfPath: String? = null,
    val pdfFileSizeBytes: Long = 0L,
    val pdfQuality: PdfQuality = PdfQuality.MEDIUM
)

enum class HomeTab(val title: String) {
    DOCUMENTS("Document Mode"),
    PDF_FILES("PDF Files")
}

data class GeneratedPdfItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val documentId: String? = null,
    val documentTitle: String = "",
    val filePath: String,
    val fileName: String,
    val fileSizeBytes: Long = 0L,
    val pageCount: Int = 0,
    val lastModified: Long = System.currentTimeMillis(),
    val quality: PdfQuality = PdfQuality.MEDIUM
)

enum class ScreenState {
    HOME,
    CAMERA,
    CROP_ADJUST,
    FILTER_ENHANCE,
    PAGE_LIST
}

