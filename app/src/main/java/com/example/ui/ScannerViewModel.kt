package com.example.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.model.CropQuad
import com.example.model.FilterType
import com.example.model.PdfQuality
import com.example.model.Point2D
import com.example.model.ScannedDocument
import com.example.model.ScannedPage
import com.example.model.ScreenState
import com.example.processing.EdgeDetector
import com.example.processing.ImageFilterEngine
import com.example.processing.ImageUtils
import com.example.processing.PdfGenerator
import com.example.processing.PerspectiveTransformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ScannerUiState(
    val currentScreen: ScreenState = ScreenState.HOME,
    val savedDocuments: List<ScannedDocument> = emptyList(),
    val currentPages: List<ScannedPage> = emptyList(),

    // Active page editing state
    val activePageIndex: Int = -1,
    val activeOriginalBitmap: Bitmap? = null,
    val activeCropQuad: CropQuad = CropQuad.defaultQuad(),
    val activeFilterType: FilterType = FilterType.ORIGINAL,
    val activePreviewBitmap: Bitmap? = null,

    // Loading & progress
    val isLoading: Boolean = false,
    val loadingMessage: String = "",
    val errorMessage: String? = null,

    // Export dialog
    val isExportDialogOpen: Boolean = false,
    val exportFilename: String = "",
    val selectedQuality: PdfQuality = PdfQuality.MEDIUM,
    val lastExportedFile: File? = null,

    // Batch processing queue for multi-import
    val pendingImportUris: List<Uri> = emptyList()
)

class ScannerViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ScannerUiState())
    val uiState: StateFlow<ScannerUiState> = _uiState.asStateFlow()

    private val prefs = application.getSharedPreferences("pdf_scanner_prefs", Context.MODE_PRIVATE)

    init {
        loadSavedDocuments()
    }

    private fun loadSavedDocuments() {
        val jsonString = prefs.getString("saved_docs", "[]") ?: "[]"
        try {
            val jsonArray = JSONArray(jsonString)
            val docs = mutableListOf<ScannedDocument>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val docId = obj.optString("id", "")
                val title = obj.optString("title", "Untitled")
                val createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                val pdfPath = obj.optString("pdfPath", null)
                val fileSize = obj.optLong("fileSize", 0L)

                if (pdfPath != null && File(pdfPath).exists()) {
                    docs.add(
                        ScannedDocument(
                            id = docId,
                            title = title,
                            createdAt = createdAt,
                            lastPdfPath = pdfPath,
                            pdfFileSizeBytes = fileSize
                        )
                    )
                }
            }
            _uiState.update { it.copy(savedDocuments = docs) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun persistDocuments(docs: List<ScannedDocument>) {
        try {
            val jsonArray = JSONArray()
            for (doc in docs) {
                val obj = JSONObject().apply {
                    put("id", doc.id)
                    put("title", doc.title)
                    put("createdAt", doc.createdAt)
                    put("pdfPath", doc.lastPdfPath)
                    put("fileSize", doc.pdfFileSizeBytes)
                }
                jsonArray.put(obj)
            }
            prefs.edit().putString("saved_docs", jsonArray.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun navigateTo(screen: ScreenState) {
        _uiState.update { it.copy(currentScreen = screen, errorMessage = null) }
    }

    fun startNewScanSession() {
        _uiState.update {
            it.copy(
                currentPages = emptyList(),
                activePageIndex = -1,
                activeOriginalBitmap = null,
                activePreviewBitmap = null,
                currentScreen = ScreenState.CAMERA
            )
        }
    }

    fun openGalleryImport() {
        // Navigates or initiates gallery multi-select
    }

    /**
     * Called when a camera capture or single gallery image is acquired.
     */
    fun onImageCaptured(bitmap: Bitmap) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loadingMessage = "Detecting document boundaries...") }
            try {
                val context = getApplication<Application>()
                val origPath = ImageUtils.saveBitmapToFile(context, bitmap, 95)
                    ?: throw Exception("Failed to save captured photo")

                // Edge detection
                val quad = EdgeDetector.detectEdges(bitmap)

                // Perspective deskew
                val deskewed = PerspectiveTransformer.transform(bitmap, quad)

                // Default ORIGINAL filter
                val filtered = ImageFilterEngine.applyFilter(deskewed, FilterType.ORIGINAL)
                val processedPath = ImageUtils.saveBitmapToFile(context, filtered, 92)
                    ?: throw Exception("Failed to save processed page")

                val newPage = ScannedPage(
                    originalImagePath = origPath,
                    processedImagePath = processedPath,
                    cropQuad = quad,
                    filterType = FilterType.ORIGINAL,
                    width = filtered.width,
                    height = filtered.height
                )

                val updatedPages = _uiState.value.currentPages + newPage
                val newIndex = updatedPages.size - 1

                _uiState.update {
                    it.copy(
                        currentPages = updatedPages,
                        activePageIndex = newIndex,
                        activeOriginalBitmap = bitmap,
                        activeCropQuad = quad,
                        activeFilterType = FilterType.ORIGINAL,
                        activePreviewBitmap = filtered,
                        isLoading = false,
                        currentScreen = ScreenState.CROP_ADJUST
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message ?: "Failed to process image") }
            }
        }
    }

    /**
     * Batch import multiple images from gallery
     */
    fun onBatchImagesSelected(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loadingMessage = "Importing ${uris.size} images...") }
            val context = getApplication<Application>()
            val newPages = mutableListOf<ScannedPage>()

            for (i in uris.indices) {
                _uiState.update {
                    it.copy(loadingMessage = "Processing page ${i + 1} of ${uris.size}...")
                }
                val bmp = ImageUtils.loadBitmapFromUri(context, uris[i]) ?: continue
                val origPath = ImageUtils.saveBitmapToFile(context, bmp, 95) ?: continue
                val quad = EdgeDetector.detectEdges(bmp)
                val deskewed = PerspectiveTransformer.transform(bmp, quad)
                val filtered = ImageFilterEngine.applyFilter(deskewed, FilterType.ORIGINAL)
                val processedPath = ImageUtils.saveBitmapToFile(context, filtered, 92) ?: continue

                newPages.add(
                    ScannedPage(
                        originalImagePath = origPath,
                        processedImagePath = processedPath,
                        cropQuad = quad,
                        filterType = FilterType.ORIGINAL,
                        width = filtered.width,
                        height = filtered.height
                    )
                )
                deskewed.recycle()
                filtered.recycle()
                bmp.recycle()
            }

            val mergedPages = _uiState.value.currentPages + newPages
            _uiState.update {
                it.copy(
                    currentPages = mergedPages,
                    isLoading = false,
                    currentScreen = ScreenState.PAGE_LIST
                )
            }
        }
    }

    // Crop adjustment
    fun updateCropCorner(cornerIndex: Int, newX: Float, newY: Float) {
        val clampedX = newX.coerceIn(0f, 1f)
        val clampedY = newY.coerceIn(0f, 1f)
        val current = _uiState.value.activeCropQuad

        val updated = when (cornerIndex) {
            0 -> current.copy(topLeft = Point2D(clampedX, clampedY))
            1 -> current.copy(topRight = Point2D(clampedX, clampedY))
            2 -> current.copy(bottomRight = Point2D(clampedX, clampedY))
            3 -> current.copy(bottomLeft = Point2D(clampedX, clampedY))
            else -> current
        }
        _uiState.update { it.copy(activeCropQuad = updated) }
    }

    fun autoDetectActivePageEdges() {
        val bmp = _uiState.value.activeOriginalBitmap ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loadingMessage = "Auto-detecting edges...") }
            val quad = EdgeDetector.detectEdges(bmp)
            _uiState.update { it.copy(activeCropQuad = quad, isLoading = false) }
        }
    }

    fun resetCropToFull() {
        _uiState.update { it.copy(activeCropQuad = CropQuad.fullImage()) }
    }

    fun rotateActivePage() {
        val bmp = _uiState.value.activeOriginalBitmap ?: return
        val rotated = ImageUtils.rotateBitmap(bmp, 90f)
        _uiState.update {
            it.copy(
                activeOriginalBitmap = rotated,
                activeCropQuad = CropQuad.defaultQuad()
            )
        }
    }

    fun saveActiveImageToGallery(context: Context, onResult: (Boolean, String) -> Unit) {
        val bmp = _uiState.value.activeOriginalBitmap ?: run {
            onResult(false, "No active image to save")
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loadingMessage = "Saving image to gallery...") }
            val uri = ImageUtils.saveBitmapToGallery(context, bmp)
            _uiState.update { it.copy(isLoading = false) }
            if (uri != null) {
                onResult(true, "Image saved to Gallery successfully")
            } else {
                onResult(false, "Failed to save image to Gallery")
            }
        }
    }

    /**
     * Confirms crop quadrilateral and advances to Filter screen
     */
    fun applyCropAndProceedToFilter() {
        val bmp = _uiState.value.activeOriginalBitmap ?: return
        val quad = _uiState.value.activeCropQuad
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loadingMessage = "Deskewing document...") }
            try {
                val deskewed = PerspectiveTransformer.transform(bmp, quad)
                val currentFilter = _uiState.value.activeFilterType
                val filtered = ImageFilterEngine.applyFilter(deskewed, currentFilter)

                _uiState.update {
                    it.copy(
                        activePreviewBitmap = filtered,
                        isLoading = false,
                        currentScreen = ScreenState.FILTER_ENHANCE
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message) }
            }
        }
    }

    fun selectFilter(filterType: FilterType) {
        val bmp = _uiState.value.activeOriginalBitmap ?: return
        val quad = _uiState.value.activeCropQuad
        viewModelScope.launch {
            _uiState.update { it.copy(activeFilterType = filterType, isLoading = true, loadingMessage = "Applying filter...") }
            try {
                val deskewed = PerspectiveTransformer.transform(bmp, quad)
                val filtered = ImageFilterEngine.applyFilter(deskewed, filterType)
                _uiState.update {
                    it.copy(
                        activePreviewBitmap = filtered,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message) }
            }
        }
    }

    /**
     * Saves changes to current page and goes to PAGE_LIST
     */
    fun saveActivePageChanges() {
        val filtered = _uiState.value.activePreviewBitmap ?: return
        val idx = _uiState.value.activePageIndex
        val currentList = _uiState.value.currentPages.toMutableList()
        val context = getApplication<Application>()

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loadingMessage = "Saving page...") }
            val newProcessedPath = ImageUtils.saveBitmapToFile(context, filtered, 92)
            if (newProcessedPath != null && idx in currentList.indices) {
                val orig = currentList[idx]
                currentList[idx] = orig.copy(
                    processedImagePath = newProcessedPath,
                    cropQuad = _uiState.value.activeCropQuad,
                    filterType = _uiState.value.activeFilterType,
                    width = filtered.width,
                    height = filtered.height
                )
            }
            _uiState.update {
                it.copy(
                    currentPages = currentList,
                    isLoading = false,
                    currentScreen = ScreenState.PAGE_LIST
                )
            }
        }
    }

    // Page List Operations
    fun movePage(fromIndex: Int, toIndex: Int) {
        val list = _uiState.value.currentPages.toMutableList()
        if (fromIndex in list.indices && toIndex in list.indices) {
            val item = list.removeAt(fromIndex)
            list.add(toIndex, item)
            _uiState.update { it.copy(currentPages = list) }
        }
    }

    fun deletePage(pageIndex: Int) {
        val list = _uiState.value.currentPages.toMutableList()
        if (pageIndex in list.indices) {
            list.removeAt(pageIndex)
            _uiState.update {
                it.copy(
                    currentPages = list,
                    currentScreen = if (list.isEmpty()) ScreenState.HOME else ScreenState.PAGE_LIST
                )
            }
        }
    }

    fun reCropPage(pageIndex: Int) {
        val list = _uiState.value.currentPages
        if (pageIndex !in list.indices) return
        val page = list[pageIndex]

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loadingMessage = "Loading page...") }
            val origBmp = ImageUtils.loadBitmapFromFile(page.originalImagePath)
            if (origBmp != null) {
                _uiState.update {
                    it.copy(
                        activePageIndex = pageIndex,
                        activeOriginalBitmap = origBmp,
                        activeCropQuad = page.cropQuad,
                        activeFilterType = page.filterType,
                        isLoading = false,
                        currentScreen = ScreenState.CROP_ADJUST
                    )
                }
            } else {
                _uiState.update { it.copy(isLoading = false, errorMessage = "Original image not found") }
            }
        }
    }

    fun reFilterPage(pageIndex: Int) {
        val list = _uiState.value.currentPages
        if (pageIndex !in list.indices) return
        val page = list[pageIndex]

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loadingMessage = "Loading page...") }
            val origBmp = ImageUtils.loadBitmapFromFile(page.originalImagePath)
            if (origBmp != null) {
                val deskewed = PerspectiveTransformer.transform(origBmp, page.cropQuad)
                val filtered = ImageFilterEngine.applyFilter(deskewed, page.filterType)
                _uiState.update {
                    it.copy(
                        activePageIndex = pageIndex,
                        activeOriginalBitmap = origBmp,
                        activeCropQuad = page.cropQuad,
                        activeFilterType = page.filterType,
                        activePreviewBitmap = filtered,
                        isLoading = false,
                        currentScreen = ScreenState.FILTER_ENHANCE
                    )
                }
            } else {
                _uiState.update { it.copy(isLoading = false, errorMessage = "Original image not found") }
            }
        }
    }

    // Export PDF
    fun openExportDialog() {
        val dateStr = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
        val defaultName = "Scan_$dateStr"
        _uiState.update {
            it.copy(
                isExportDialogOpen = true,
                exportFilename = defaultName,
                lastExportedFile = null
            )
        }
    }

    fun closeExportDialog() {
        _uiState.update { it.copy(isExportDialogOpen = false) }
    }

    fun updateExportFilename(name: String) {
        _uiState.update { it.copy(exportFilename = name) }
    }

    fun selectPdfQuality(quality: PdfQuality) {
        _uiState.update { it.copy(selectedQuality = quality) }
    }

    fun generatePdf(onSuccess: (File) -> Unit = {}) {
        val pages = _uiState.value.currentPages
        if (pages.isEmpty()) return
        val filename = _uiState.value.exportFilename.ifBlank { "Scan_${System.currentTimeMillis()}" }
        val quality = _uiState.value.selectedQuality
        val context = getApplication<Application>()

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loadingMessage = "Generating multi-page PDF...") }
            val pdfFile = PdfGenerator.generatePdf(context, pages, filename, quality)
            if (pdfFile != null && pdfFile.exists()) {
                val newDoc = ScannedDocument(
                    title = filename,
                    pages = pages,
                    lastPdfPath = pdfFile.absolutePath,
                    pdfFileSizeBytes = pdfFile.length()
                )
                val updatedDocs = listOf(newDoc) + _uiState.value.savedDocuments
                persistDocuments(updatedDocs)

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        savedDocuments = updatedDocs,
                        lastExportedFile = pdfFile
                    )
                }
                onSuccess(pdfFile)
            } else {
                _uiState.update { it.copy(isLoading = false, errorMessage = "Failed to generate PDF") }
            }
        }
    }

    fun sharePdf(file: File) {
        PdfGenerator.sharePdf(getApplication(), file)
    }

    fun viewPdf(file: File) {
        PdfGenerator.viewPdf(getApplication(), file)
    }

    fun deleteDocument(doc: ScannedDocument) {
        doc.lastPdfPath?.let { File(it).delete() }
        val updated = _uiState.value.savedDocuments.filter { it.id != doc.id }
        _uiState.update { it.copy(savedDocuments = updated) }
        persistDocuments(updated)
    }
}
