package com.example.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.model.CropQuad
import com.example.model.FilterType
import com.example.model.GeneratedPdfItem
import com.example.model.HomeTab
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
    val selectedHomeTab: HomeTab = HomeTab.DOCUMENTS,
    val savedDocuments: List<ScannedDocument> = emptyList(),
    val pdfFiles: List<GeneratedPdfItem> = emptyList(),
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
    val infoMessage: String? = null,

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

    private fun buildPdfFilesList(docs: List<ScannedDocument>): List<GeneratedPdfItem> {
        val list = mutableListOf<GeneratedPdfItem>()
        val seenPaths = mutableSetOf<String>()

        for (doc in docs) {
            val path = doc.lastPdfPath ?: continue
            val file = File(path)
            if (file.exists() && file.isFile) {
                seenPaths.add(file.absolutePath)
                list.add(
                    GeneratedPdfItem(
                        id = "pdf_${doc.id}",
                        documentId = doc.id,
                        documentTitle = doc.title,
                        filePath = file.absolutePath,
                        fileName = file.name,
                        fileSizeBytes = if (doc.pdfFileSizeBytes > 0) doc.pdfFileSizeBytes else file.length(),
                        pageCount = doc.pages.size,
                        lastModified = file.lastModified().let { if (it > 0) it else doc.updatedAt },
                        quality = doc.pdfQuality
                    )
                )
            }
        }

        try {
            val context = getApplication<Application>()
            val pdfDir = File(context.getExternalFilesDir(null), "documents")
            if (pdfDir.exists() && pdfDir.isDirectory) {
                val files = pdfDir.listFiles { f -> f.extension.equals("pdf", ignoreCase = true) }
                if (files != null) {
                    for (file in files) {
                        if (!seenPaths.contains(file.absolutePath)) {
                            list.add(
                                GeneratedPdfItem(
                                    id = "pdf_standalone_${file.name.hashCode()}",
                                    documentId = null,
                                    documentTitle = file.nameWithoutExtension,
                                    filePath = file.absolutePath,
                                    fileName = file.name,
                                    fileSizeBytes = file.length(),
                                    pageCount = 0,
                                    lastModified = file.lastModified()
                                )
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        return list.sortedByDescending { it.lastModified }
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
                val updatedAt = obj.optLong("updatedAt", createdAt)
                val pdfPath = obj.optString("pdfPath", null)
                val fileSize = obj.optLong("fileSize", 0L)
                val qualityStr = obj.optString("pdfQuality", "MEDIUM")
                val quality = try { PdfQuality.valueOf(qualityStr) } catch (_: Exception) { PdfQuality.MEDIUM }

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
                            updatedAt = updatedAt,
                            pages = pagesList,
                            lastPdfPath = if (hasPdf) pdfPath else null,
                            pdfFileSizeBytes = fileSize,
                            pdfQuality = quality
                        )
                    )
                }
            }
            val sortedDocs = docs.sortedByDescending { it.updatedAt }
            val pdfList = buildPdfFilesList(sortedDocs)
            _uiState.update { it.copy(savedDocuments = sortedDocs, pdfFiles = pdfList) }
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
                    put("updatedAt", doc.updatedAt)
                    put("pdfPath", doc.lastPdfPath)
                    put("fileSize", doc.pdfFileSizeBytes)
                    put("pdfQuality", doc.pdfQuality.name)

                    val pArray = JSONArray()
                    for (p in doc.pages) {
                        pArray.put(pageToJson(p))
                    }
                    put("pages", pArray)
                }
                jsonArray.put(obj)
            }
            prefs.edit().putString("saved_docs", jsonArray.toString()).apply()
            val pdfList = buildPdfFilesList(docs)
            _uiState.update { it.copy(savedDocuments = docs, pdfFiles = pdfList) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun selectHomeTab(tab: HomeTab) {
        _uiState.update { it.copy(selectedHomeTab = tab) }
    }

    fun clearInfoMessage() {
        _uiState.update { it.copy(infoMessage = null) }
    }

    fun clearErrorMessage() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun navigateTo(screen: ScreenState) {
        _uiState.update { it.copy(currentScreen = screen, errorMessage = null) }
    }

    fun startNewScanSession() {
        val dateStr = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
        val newDocId = java.util.UUID.randomUUID().toString()
        val title = "Doc_$dateStr"
        _uiState.update {
            it.copy(
                currentPages = emptyList(),
                currentDocumentId = newDocId,
                currentDocumentTitle = title,
                activePageIndex = -1,
                activeOriginalBitmap = null,
                activePreviewBitmap = null,
                currentScreen = ScreenState.CAMERA
            )
        }
    }

    fun saveCurrentDocumentSession(customTitle: String? = null, autoUpdatePdf: Boolean = true): ScannedDocument? {
        val pages = _uiState.value.currentPages
        if (pages.isEmpty()) return null

        val docId = _uiState.value.currentDocumentId ?: java.util.UUID.randomUUID().toString()
        val defaultTitle = _uiState.value.currentDocumentTitle.ifBlank {
            "Doc_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())}"
        }
        val title = customTitle?.ifBlank { defaultTitle } ?: defaultTitle

        val existingDoc = _uiState.value.savedDocuments.find { it.id == docId }
        val now = System.currentTimeMillis()
        val updatedDoc = ScannedDocument(
            id = docId,
            title = title,
            createdAt = existingDoc?.createdAt ?: now,
            updatedAt = now,
            pages = pages,
            lastPdfPath = existingDoc?.lastPdfPath,
            pdfFileSizeBytes = existingDoc?.pdfFileSizeBytes ?: 0L,
            pdfQuality = existingDoc?.pdfQuality ?: _uiState.value.selectedQuality
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

        // If this document has a related PDF generated previously, update it as well
        val pdfPath = updatedDoc.lastPdfPath
        if (autoUpdatePdf && pdfPath != null && File(pdfPath).exists()) {
            viewModelScope.launch {
                val context = getApplication<Application>()
                val pdfFile = File(pdfPath)
                val updatedPdf = PdfGenerator.generatePdf(
                    context = context,
                    pages = pages,
                    filename = pdfFile.name,
                    quality = updatedDoc.pdfQuality,
                    targetFile = pdfFile
                )
                if (updatedPdf != null && updatedPdf.exists()) {
                    val finalDoc = updatedDoc.copy(
                        pdfFileSizeBytes = updatedPdf.length(),
                        updatedAt = System.currentTimeMillis()
                    )
                    val finalList = _uiState.value.savedDocuments.map { if (it.id == docId) finalDoc else it }
                    _uiState.update {
                        it.copy(
                            savedDocuments = finalList,
                            infoMessage = "Document saved & related PDF updated! (${pages.size} pages)"
                        )
                    }
                    persistDocuments(finalList)
                }
            }
        } else {
            _uiState.update {
                it.copy(infoMessage = "Document saved permanently in Document Mode (${pages.size} pages)")
            }
        }

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
            saveCurrentDocumentSession()
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
            // Auto-persist changes to document mode (and update related PDF if present)
            saveCurrentDocumentSession()
        }
    }

    // Page List Operations
    fun movePage(fromIndex: Int, toIndex: Int) {
        val list = _uiState.value.currentPages.toMutableList()
        if (fromIndex in list.indices && toIndex in list.indices) {
            val item = list.removeAt(fromIndex)
            list.add(toIndex, item)
            _uiState.update { it.copy(currentPages = list) }
            saveCurrentDocumentSession()
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
            if (list.isNotEmpty()) {
                saveCurrentDocumentSession()
            } else {
                _uiState.value.currentDocumentId?.let { docId ->
                    val existing = _uiState.value.savedDocuments.find { it.id == docId }
                    if (existing != null) deleteDocument(existing)
                }
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
        val defaultName = _uiState.value.currentDocumentTitle.ifBlank { "Scan_$dateStr" }
        _uiState.update {
            it.copy(
                isExportDialogOpen = true,
                exportFilename = defaultName,
                lastExportedFile = null
            )
        }
    }

    fun openExportDialogForDocument(doc: ScannedDocument) {
        _uiState.update {
            it.copy(
                currentDocumentId = doc.id,
                currentDocumentTitle = doc.title,
                currentPages = doc.pages,
                isExportDialogOpen = true,
                exportFilename = doc.title,
                selectedQuality = doc.pdfQuality,
                lastExportedFile = null
            )
        }
    }

    fun loadDocumentForEditingById(documentId: String) {
        val doc = _uiState.value.savedDocuments.find { it.id == documentId }
        if (doc != null) {
            loadDocumentForEditing(doc)
        }
    }

    fun renameDocument(docId: String, newTitle: String) {
        val cleanTitle = newTitle.trim()
        if (cleanTitle.isBlank()) return
        val updated = _uiState.value.savedDocuments.map { doc ->
            if (doc.id == docId) {
                doc.copy(title = cleanTitle, updatedAt = System.currentTimeMillis())
            } else {
                doc
            }
        }
        _uiState.update {
            it.copy(
                savedDocuments = updated,
                currentDocumentTitle = if (it.currentDocumentId == docId) cleanTitle else it.currentDocumentTitle,
                infoMessage = "Document renamed to $cleanTitle"
            )
        }
        persistDocuments(updated)
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
                val now = System.currentTimeMillis()
                val newDoc = ScannedDocument(
                    id = docId,
                    title = filename.removeSuffix(".pdf"),
                    createdAt = existingDoc?.createdAt ?: now,
                    updatedAt = now,
                    pages = pages,
                    lastPdfPath = pdfFile.absolutePath,
                    pdfFileSizeBytes = pdfFile.length(),
                    pdfQuality = quality
                )
                val updatedDocs = listOf(newDoc) + _uiState.value.savedDocuments.filter { it.id != docId }
                persistDocuments(updatedDocs)

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        currentDocumentId = docId,
                        currentDocumentTitle = filename.removeSuffix(".pdf"),
                        savedDocuments = updatedDocs,
                        lastExportedFile = pdfFile,
                        infoMessage = "PDF generated and saved in PDF Files screen!"
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
        doc.lastPdfPath?.let { path ->
            val f = File(path)
            if (f.exists()) f.delete()
        }
        for (page in doc.pages) {
            File(page.processedImagePath).delete()
            File(page.originalImagePath).delete()
        }
        val updated = _uiState.value.savedDocuments.filter { it.id != doc.id }
        val isCurrent = _uiState.value.currentDocumentId == doc.id
        _uiState.update {
            it.copy(
                savedDocuments = updated,
                currentPages = if (isCurrent) emptyList() else it.currentPages,
                currentDocumentId = if (isCurrent) null else it.currentDocumentId,
                infoMessage = "Document deleted"
            )
        }
        persistDocuments(updated)
    }

    fun deletePdfFile(pdfItem: GeneratedPdfItem) {
        val file = File(pdfItem.filePath)
        if (file.exists()) {
            file.delete()
        }
        val updatedDocs = _uiState.value.savedDocuments.map { doc ->
            if (doc.lastPdfPath == pdfItem.filePath || doc.id == pdfItem.documentId) {
                doc.copy(lastPdfPath = null, pdfFileSizeBytes = 0L)
            } else {
                doc
            }
        }
        _uiState.update {
            it.copy(
                savedDocuments = updatedDocs,
                infoMessage = "PDF file deleted"
            )
        }
        persistDocuments(updatedDocs)
    }
}
