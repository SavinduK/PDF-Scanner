package com.example.ui

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
import com.example.processing.PdfImporter
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
import java.util.UUID

data class ScannerUiState(
    val currentScreen: ScreenState = ScreenState.HOME,
    val savedDocuments: List<ScannedDocument> = emptyList(),
    val currentPages: List<ScannedPage> = emptyList(),
    val currentDocumentId: String? = null,
    val currentDocumentTitle: String = "",

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
    val pendingImportUris: List<Uri> = emptyList(),

    // Camera flash & continuous mode
    val isFlashEnabled: Boolean = false,
    val isContinuousMode: Boolean = false
)

class ScannerViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ScannerUiState())
    val uiState: StateFlow<ScannerUiState> = _uiState.asStateFlow()

    private val prefs = application.getSharedPreferences("pdf_scanner_prefs", Context.MODE_PRIVATE)

    init {
        loadSavedDocuments()
    }

    private fun pageToJson(page: ScannedPage): JSONObject {
        return JSONObject().apply {
            put("id", page.id)
            put("originalImagePath", page.originalImagePath)
            put("processedImagePath", page.processedImagePath)
            put("filterType", page.filterType.name)
            put("rotationDegrees", page.rotationDegrees)
            put("width", page.width)
            put("height", page.height)
            put("cropQuad", JSONObject().apply {
                put("tlX", page.cropQuad.topLeft.x)
                put("tlY", page.cropQuad.topLeft.y)
                put("trX", page.cropQuad.topRight.x)
                put("trY", page.cropQuad.topRight.y)
                put("brX", page.cropQuad.bottomRight.x)
                put("brY", page.cropQuad.bottomRight.y)
                put("blX", page.cropQuad.bottomLeft.x)
                put("blY", page.cropQuad.bottomLeft.y)
            })
        }
    }

    private fun jsonToPage(obj: JSONObject): ScannedPage? {
        val orig = obj.optString("originalImagePath", "")
        val proc = obj.optString("processedImagePath", "")
        if (!File(proc).exists() && !File(orig).exists()) return null
        val pageId = obj.optString("id", java.util.UUID.randomUUID().toString())
        val filterStr = obj.optString("filterType", "ORIGINAL")
        val filterType = try { FilterType.valueOf(filterStr) } catch (_: Exception) { FilterType.ORIGINAL }
        val rot = obj.optInt("rotationDegrees", 0)
        val w = obj.optInt("width", 0)
        val h = obj.optInt("height", 0)

        val quadObj = obj.optJSONObject("cropQuad")
        val quad = if (quadObj != null) {
            CropQuad(
                topLeft = Point2D(quadObj.optDouble("tlX", 0.05).toFloat(), quadObj.optDouble("tlY", 0.05).toFloat()),
                topRight = Point2D(quadObj.optDouble("trX", 0.95).toFloat(), quadObj.optDouble("trY", 0.05).toFloat()),
                bottomRight = Point2D(quadObj.optDouble("brX", 0.95).toFloat(), quadObj.optDouble("brY", 0.95).toFloat()),
                bottomLeft = Point2D(quadObj.optDouble("blX", 0.05).toFloat(), quadObj.optDouble("blY", 0.95).toFloat())
            )
        } else {
            CropQuad.defaultQuad()
        }

        val finalOrig = if (File(orig).exists()) orig else proc
        val finalProc = if (File(proc).exists()) proc else orig

        return ScannedPage(
            id = pageId,
            originalImagePath = finalOrig,
            processedImagePath = finalProc,
            cropQuad = quad,
            filterType = filterType,
            rotationDegrees = rot,
            width = w,
            height = h
        )
    }

    private fun loadSavedDocuments() {
        val jsonString = prefs.getString("saved_docs", "[]") ?: "[]"
        try {
            val jsonArray = JSONArray(jsonString)
            val docs = mutableListOf<ScannedDocument>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val docId = obj.optString("id", java.util.UUID.randomUUID().toString())
                val title = obj.optString("title", "Untitled")
                val createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                val pdfPath = if (obj.has("pdfPath") && !obj.isNull("pdfPath")) obj.getString("pdfPath") else null
                val fileSize = obj.optLong("fileSize", 0L)

                val pagesArray = obj.optJSONArray("pages")
                val pagesList = mutableListOf<ScannedPage>()
                if (pagesArray != null) {
                    for (j in 0 until pagesArray.length()) {
                        val pObj = pagesArray.getJSONObject(j)
                        val page = jsonToPage(pObj)
                        if (page != null) {
                            pagesList.add(page)
                        }
                    }
                }

                val hasPdf = pdfPath != null && File(pdfPath).exists()
                if (hasPdf || pagesList.isNotEmpty()) {
                    docs.add(
                        ScannedDocument(
                            id = docId,
                            title = title,
                            createdAt = createdAt,
                            pages = pagesList,
                            lastPdfPath = if (hasPdf) pdfPath else null,
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

                    val pArray = JSONArray()
                    for (p in doc.pages) {
                        pArray.put(pageToJson(p))
                    }
                    put("pages", pArray)
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
        val dateStr = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
        _uiState.update {
            it.copy(
                currentPages = emptyList(),
                currentDocumentId = null,
                currentDocumentTitle = "Doc_$dateStr",
                activePageIndex = -1,
                activeOriginalBitmap = null,
                activePreviewBitmap = null,
                currentScreen = ScreenState.CAMERA
            )
        }
    }

    fun saveCurrentDocumentSession(customTitle: String? = null): ScannedDocument? {
        val pages = _uiState.value.currentPages
        if (pages.isEmpty()) return null

        val docId = _uiState.value.currentDocumentId ?: java.util.UUID.randomUUID().toString()
        val defaultTitle = _uiState.value.currentDocumentTitle.ifBlank {
            "Doc_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())}"
        }
        val title = customTitle?.ifBlank { defaultTitle } ?: defaultTitle

        val existingDoc = _uiState.value.savedDocuments.find { it.id == docId }
        val updatedDoc = ScannedDocument(
            id = docId,
            title = title,
            createdAt = existingDoc?.createdAt ?: System.currentTimeMillis(),
            pages = pages,
            lastPdfPath = existingDoc?.lastPdfPath,
            pdfFileSizeBytes = existingDoc?.pdfFileSizeBytes ?: 0L
        )

        val updatedList = _uiState.value.savedDocuments.filter { it.id != docId }.toMutableList().apply {
            add(0, updatedDoc)
        }

        _uiState.update {
            it.copy(
                currentDocumentId = docId,
                currentDocumentTitle = title,
                savedDocuments = updatedList
            )
        }
        persistDocuments(updatedList)
        return updatedDoc
    }

    fun loadDocumentForEditing(doc: ScannedDocument) {
        _uiState.update {
            it.copy(
                currentDocumentId = doc.id,
                currentDocumentTitle = doc.title,
                currentPages = doc.pages,
                activePageIndex = -1,
                activeOriginalBitmap = null,
                activePreviewBitmap = null,
                currentScreen = ScreenState.PAGE_LIST
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

                if (_uiState.value.isContinuousMode) {
                    // In continuous mode, keep in camera ready for next capture with flash preserved
                    _uiState.update {
                        it.copy(
                            currentPages = updatedPages,
                            activePageIndex = newIndex,
                            isLoading = false,
                            currentScreen = ScreenState.CAMERA
                        )
                    }
                } else {
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
     * Saves changes to current page and goes to specified screen
     */
    fun saveActivePageChanges(navigateTo: ScreenState = ScreenState.PAGE_LIST) {
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
                    activeOriginalBitmap = null,
                    activePreviewBitmap = null,
                    activePageIndex = -1,
                    isLoading = false,
                    currentScreen = navigateTo
                )
            }
        }
    }

    /**
     * Trigger capture of next image immediately (keeping flash state intact)
     */
    fun saveActivePageAndScanNext() {
        saveActivePageChanges(navigateTo = ScreenState.CAMERA)
    }

    /**
     * Save active page and exit scan flow to page review list
     */
    fun saveActivePageAndExit() {
        saveActivePageChanges(navigateTo = ScreenState.PAGE_LIST)
    }

    fun exitCropAdjust() {
        _uiState.update {
            it.copy(
                activeOriginalBitmap = null,
                activePreviewBitmap = null,
                activePageIndex = -1,
                currentScreen = if (it.currentPages.isNotEmpty()) ScreenState.PAGE_LIST else ScreenState.HOME
            )
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
                val docId = _uiState.value.currentDocumentId ?: java.util.UUID.randomUUID().toString()
                val existingDoc = _uiState.value.savedDocuments.find { it.id == docId }
                val newDoc = ScannedDocument(
                    id = docId,
                    title = filename,
                    createdAt = existingDoc?.createdAt ?: System.currentTimeMillis(),
                    pages = pages,
                    lastPdfPath = pdfFile.absolutePath,
                    pdfFileSizeBytes = pdfFile.length()
                )
                val updatedDocs = listOf(newDoc) + _uiState.value.savedDocuments.filter { it.id != docId }
                persistDocuments(updatedDocs)

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        currentDocumentId = docId,
                        currentDocumentTitle = filename,
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

    /**
     * Saves the document PDF file to public device storage (Downloads/PDFScanner)
     */
    fun savePdfToDeviceStorage(context: Context, document: ScannedDocument, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val pdfPath = document.lastPdfPath
            if (pdfPath == null || !File(pdfPath).exists()) {
                withContext(Dispatchers.Main) {
                    onResult(false, "PDF file not found on device")
                }
                return@launch
            }
            val sourceFile = File(pdfPath)
            try {
                val cleanTitle = document.title.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "Scan_${System.currentTimeMillis()}" }
                val fileName = if (cleanTitle.endsWith(".pdf", ignoreCase = true)) cleanTitle else "$cleanTitle.pdf"

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PDFScanner")
                    }
                    val resolver = context.contentResolver
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    if (uri != null) {
                        resolver.openOutputStream(uri)?.use { out ->
                            sourceFile.inputStream().use { input ->
                                input.copyTo(out)
                            }
                        }
                        withContext(Dispatchers.Main) {
                            onResult(true, "Saved to Downloads/PDFScanner/$fileName")
                        }
                        return@launch
                    }
                }

                // Fallback for older Android or direct file system write
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val targetDir = File(downloadsDir, "PDFScanner").apply { mkdirs() }
                val targetFile = File(targetDir, fileName)
                sourceFile.copyTo(targetFile, overwrite = true)
                withContext(Dispatchers.Main) {
                    onResult(true, "Saved to Downloads/PDFScanner/$fileName")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    onResult(false, "Failed to save to device: ${e.localizedMessage ?: e.message}")
                }
            }
        }
    }

    // Flash and continuous scan controls
    fun toggleFlash() {
        _uiState.update { it.copy(isFlashEnabled = !it.isFlashEnabled) }
    }

    fun setFlash(enabled: Boolean) {
        _uiState.update { it.copy(isFlashEnabled = enabled) }
    }

    fun toggleContinuousMode() {
        _uiState.update { it.copy(isContinuousMode = !it.isContinuousMode) }
    }

    // PDF Import feature
    fun importPdf(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loadingMessage = "Importing PDF document...") }
            try {
                val context = getApplication<Application>()
                val (pdfPath, pages) = PdfImporter.importPdf(context, uri)
                if (pages.isEmpty()) {
                    throw Exception("No renderable pages found in PDF")
                }
                val pdfFile = File(pdfPath)
                val docTitle = pdfFile.nameWithoutExtension.ifBlank { "Imported Document" }
                val docId = UUID.randomUUID().toString()

                val doc = ScannedDocument(
                    id = docId,
                    title = docTitle,
                    createdAt = System.currentTimeMillis(),
                    pages = pages,
                    lastPdfPath = pdfPath,
                    pdfFileSizeBytes = pdfFile.length()
                )

                val updatedList = _uiState.value.savedDocuments.toMutableList().apply {
                    add(0, doc)
                }
                _uiState.update {
                    it.copy(
                        savedDocuments = updatedList,
                        isLoading = false,
                        currentDocumentId = docId,
                        currentDocumentTitle = docTitle,
                        currentPages = pages,
                        errorMessage = null
                    )
                }
                persistDocuments(updatedList)
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to import PDF: ${e.localizedMessage ?: e.message}"
                    )
                }
            }
        }
    }

    fun importPdfToCurrentSession(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loadingMessage = "Importing pages from PDF...") }
            try {
                val context = getApplication<Application>()
                val (_, pages) = PdfImporter.importPdf(context, uri)
                if (pages.isEmpty()) {
                    throw Exception("No renderable pages found in PDF")
                }
                val updatedPages = _uiState.value.currentPages + pages
                _uiState.update {
                    it.copy(
                        currentPages = updatedPages,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to import PDF pages: ${e.localizedMessage ?: e.message}"
                    )
                }
            }
        }
    }
}
