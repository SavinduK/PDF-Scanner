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
import com.example.processing.ImageFilterEngine
import com.example.processing.ImageUtils
import com.example.processing.PdfGenerator
import com.example.processing.PdfImporter
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
    val activeRotationDegrees: Int = 0,
    val activeFilterType: FilterType = FilterType.ORIGINAL,
    val activePreviewBitmap: Bitmap? = null,

    // Loading & progress
    val isLoading: Boolean = false,
    val loadingMessage: String = "",
    val errorMessage: String? = null,

    // Export dialog & optional ready-made ML Kit PDF
    val isExportDialogOpen: Boolean = false,
    val exportFilename: String = "",
    val selectedQuality: PdfQuality = PdfQuality.MEDIUM,
    val lastExportedFile: File? = null,
    val mlKitReadyPdfPath: String? = null,
    val isMlKitPdfInSync: Boolean = false
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
        val pageId = obj.optString("id", UUID.randomUUID().toString())
        val filterStr = obj.optString("filterType", "ORIGINAL")
        val filterType = try { FilterType.valueOf(filterStr) } catch (_: Exception) { FilterType.ORIGINAL }
        val rot = obj.optInt("rotationDegrees", 0)
        val w = obj.optInt("width", 0)
        val h = obj.optInt("height", 0)

        val quadObj = obj.optJSONObject("cropQuad")
        val quad = if (quadObj != null) {
            CropQuad(
                topLeft = Point2D(quadObj.optDouble("tlX", 0.0).toFloat(), quadObj.optDouble("tlY", 0.0).toFloat()),
                topRight = Point2D(quadObj.optDouble("trX", 1.0).toFloat(), quadObj.optDouble("trY", 0.0).toFloat()),
                bottomRight = Point2D(quadObj.optDouble("brX", 1.0).toFloat(), quadObj.optDouble("brY", 1.0).toFloat()),
                bottomLeft = Point2D(quadObj.optDouble("blX", 0.0).toFloat(), quadObj.optDouble("blY", 1.0).toFloat())
            )
        } else {
            CropQuad.fullImage()
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
                val docId = obj.optString("id", UUID.randomUUID().toString())
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

    fun showError(message: String) {
        _uiState.update { it.copy(errorMessage = message) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun navigateTo(screen: ScreenState) {
        _uiState.update { it.copy(currentScreen = screen, errorMessage = null) }
    }

    /**
     * Ingests cropped, perspective-corrected page JPEGs and the optional ready-made PDF
     * returned by the ML Kit Document Scanner API.
     */
    fun onMlKitScanCompleted(
        pageUris: List<Uri>,
        readyPdfUri: Uri?,
        startNewSession: Boolean
    ) {
        if (pageUris.isEmpty() && readyPdfUri == null) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    loadingMessage = "Importing ${pageUris.size.coerceAtLeast(1)} scanned page(s)...",
                    errorMessage = null
                )
            }
            try {
                val context = getApplication<Application>()
                val importedPages = mutableListOf<ScannedPage>()

                for (i in pageUris.indices) {
                    _uiState.update {
                        it.copy(loadingMessage = "Processing page ${i + 1} of ${pageUris.size}...")
                    }
                    // 1. Copy ML Kit's cropped/perspective-corrected JPEG into app storage
                    val origPath = ImageUtils.copyUriToAppStorage(
                        context = context,
                        uri = pageUris[i],
                        subDir = "scans",
                        prefix = "mlkit_orig",
                        extension = "jpg"
                    ) ?: continue

                    // 2. Decode with existing subsampled ImageUtils
                    val decoded = ImageUtils.loadBitmapFromFile(origPath) ?: continue
                    val filtered = ImageFilterEngine.applyFilter(decoded, FilterType.ORIGINAL)
                    val procPath = ImageUtils.saveBitmapToFile(context, filtered, 92) ?: origPath
                    val pageWidth = filtered.width
                    val pageHeight = filtered.height

                    if (filtered != decoded) filtered.recycle()
                    decoded.recycle()

                    // 3. Add to document defaulting to ORIGINAL filter
                    importedPages.add(
                        ScannedPage(
                            originalImagePath = origPath,
                            processedImagePath = procPath,
                            cropQuad = CropQuad.fullImage(),
                            filterType = FilterType.ORIGINAL,
                            rotationDegrees = 0,
                            width = pageWidth,
                            height = pageHeight
                        )
                    )
                }

                // Copy optional ready-made ML Kit PDF into app storage if provided
                val copiedPdfPath = if (readyPdfUri != null) {
                    ImageUtils.copyUriToAppStorage(
                        context = context,
                        uri = readyPdfUri,
                        subDir = "mlkit_pdfs",
                        prefix = "mlkit_ready",
                        extension = "pdf"
                    )
                } else {
                    null
                }

                if (importedPages.isEmpty() && copiedPdfPath != null) {
                    // Fallback: if only a PDF was returned, render pages via PdfImporter
                    val (_, renderedPages) = PdfImporter.importPdf(context, Uri.fromFile(File(copiedPdfPath)))
                    importedPages.addAll(renderedPages)
                }

                if (importedPages.isEmpty()) {
                    throw Exception("Failed to load scanned page images.")
                }

                val dateStr = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
                _uiState.update { state ->
                    if (startNewSession) {
                        state.copy(
                            currentPages = importedPages,
                            currentDocumentId = null,
                            currentDocumentTitle = "Doc_$dateStr",
                            activePageIndex = -1,
                            activeOriginalBitmap = null,
                            activePreviewBitmap = null,
                            mlKitReadyPdfPath = copiedPdfPath,
                            isMlKitPdfInSync = copiedPdfPath != null,
                            isLoading = false,
                            currentScreen = ScreenState.PAGE_LIST
                        )
                    } else {
                        val wasEmpty = state.currentPages.isEmpty()
                        state.copy(
                            currentPages = state.currentPages + importedPages,
                            currentDocumentTitle = state.currentDocumentTitle.ifBlank { "Doc_$dateStr" },
                            mlKitReadyPdfPath = copiedPdfPath ?: state.mlKitReadyPdfPath,
                            isMlKitPdfInSync = wasEmpty && copiedPdfPath != null,
                            isLoading = false,
                            currentScreen = ScreenState.PAGE_LIST
                        )
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = e.localizedMessage ?: "Failed to import scanned pages"
                    )
                }
            }
        }
    }

    fun saveCurrentDocumentSession(customTitle: String? = null): ScannedDocument? {
        val pages = _uiState.value.currentPages
        if (pages.isEmpty()) return null

        val docId = _uiState.value.currentDocumentId ?: UUID.randomUUID().toString()
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
                mlKitReadyPdfPath = null,
                isMlKitPdfInSync = false,
                currentScreen = ScreenState.PAGE_LIST
            )
        }
    }

    fun rotateActivePage() {
        val bmp = _uiState.value.activeOriginalBitmap ?: return
        val newRotation = (_uiState.value.activeRotationDegrees + 90) % 360
        val filterType = _uiState.value.activeFilterType
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loadingMessage = "Rotating page...") }
            try {
                val rotated = ImageUtils.rotateBitmap(bmp, newRotation.toFloat())
                val filtered = ImageFilterEngine.applyFilter(rotated, filterType)
                if (filtered != rotated && rotated != bmp) rotated.recycle()
                _uiState.update {
                    it.copy(
                        activeRotationDegrees = newRotation,
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
     * Rotates a page 90 degrees clockwise directly from the page list.
     */
    fun rotatePageAt(pageIndex: Int) {
        val list = _uiState.value.currentPages.toMutableList()
        if (pageIndex !in list.indices) return
        val page = list[pageIndex]
        val context = getApplication<Application>()

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loadingMessage = "Rotating page...") }
            try {
                val origBmp = ImageUtils.loadBitmapFromFile(page.originalImagePath)
                    ?: ImageUtils.loadBitmapFromFile(page.processedImagePath)
                    ?: throw Exception("Page image not found")

                val newRotation = (page.rotationDegrees + 90) % 360
                val rotated = ImageUtils.rotateBitmap(origBmp, newRotation.toFloat())
                val filtered = ImageFilterEngine.applyFilter(rotated, page.filterType)
                val newProcPath = ImageUtils.saveBitmapToFile(context, filtered, 92)
                    ?: throw Exception("Failed to save rotated page")

                val newW = filtered.width
                val newH = filtered.height
                if (filtered != rotated && filtered != origBmp) filtered.recycle()
                if (rotated != origBmp) rotated.recycle()
                origBmp.recycle()

                list[pageIndex] = page.copy(
                    processedImagePath = newProcPath,
                    rotationDegrees = newRotation,
                    width = newW,
                    height = newH
                )
                _uiState.update {
                    it.copy(
                        currentPages = list,
                        isMlKitPdfInSync = false,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message) }
            }
        }
    }

    fun saveActiveImageToGallery(context: Context, onResult: (Boolean, String) -> Unit) {
        val bmp = _uiState.value.activePreviewBitmap ?: _uiState.value.activeOriginalBitmap ?: run {
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

    fun selectFilter(filterType: FilterType) {
        val bmp = _uiState.value.activeOriginalBitmap ?: return
        val rotation = _uiState.value.activeRotationDegrees
        viewModelScope.launch {
            _uiState.update { it.copy(activeFilterType = filterType, isLoading = true, loadingMessage = "Applying filter...") }
            try {
                val rotated = ImageUtils.rotateBitmap(bmp, rotation.toFloat())
                val filtered = ImageFilterEngine.applyFilter(rotated, filterType)
                if (filtered != rotated && rotated != bmp) rotated.recycle()
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
     * Saves filter/rotation changes to the active page and navigates to the target screen.
     */
    fun saveActivePageChanges(
        navigateTo: ScreenState = ScreenState.PAGE_LIST,
        onSaved: () -> Unit = {}
    ) {
        val filtered = _uiState.value.activePreviewBitmap ?: return
        val idx = _uiState.value.activePageIndex
        val currentList = _uiState.value.currentPages.toMutableList()
        val context = getApplication<Application>()

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loadingMessage = "Saving page...") }
            var modified = false
            val newProcessedPath = ImageUtils.saveBitmapToFile(context, filtered, 92)
            if (newProcessedPath != null && idx in currentList.indices) {
                val orig = currentList[idx]
                val newFilter = _uiState.value.activeFilterType
                val newRotation = _uiState.value.activeRotationDegrees
                modified = (newFilter != orig.filterType || newRotation != orig.rotationDegrees)
                currentList[idx] = orig.copy(
                    processedImagePath = newProcessedPath,
                    filterType = newFilter,
                    rotationDegrees = newRotation,
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
                    isMlKitPdfInSync = if (modified) false else it.isMlKitPdfInSync,
                    isLoading = false,
                    currentScreen = navigateTo
                )
            }
            onSaved()
        }
    }

    fun saveActivePageAndExit() {
        saveActivePageChanges(navigateTo = ScreenState.PAGE_LIST)
    }

    fun cancelPageEdit() {
        _uiState.update {
            it.copy(
                activeOriginalBitmap = null,
                activePreviewBitmap = null,
                activePageIndex = -1,
                currentScreen = ScreenState.PAGE_LIST
            )
        }
    }

    // Page List Operations
    fun movePage(fromIndex: Int, toIndex: Int) {
        val list = _uiState.value.currentPages.toMutableList()
        if (fromIndex in list.indices && toIndex in list.indices) {
            val item = list.removeAt(fromIndex)
            list.add(toIndex, item)
            _uiState.update { it.copy(currentPages = list, isMlKitPdfInSync = false) }
        }
    }

    fun deletePage(pageIndex: Int) {
        val list = _uiState.value.currentPages.toMutableList()
        if (pageIndex in list.indices) {
            list.removeAt(pageIndex)
            _uiState.update {
                it.copy(
                    currentPages = list,
                    isMlKitPdfInSync = false,
                    currentScreen = if (list.isEmpty()) ScreenState.HOME else ScreenState.PAGE_LIST
                )
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
                ?: ImageUtils.loadBitmapFromFile(page.processedImagePath)
            if (origBmp != null) {
                val rotated = ImageUtils.rotateBitmap(origBmp, page.rotationDegrees.toFloat())
                val filtered = ImageFilterEngine.applyFilter(rotated, page.filterType)
                if (filtered != rotated && rotated != origBmp) rotated.recycle()
                _uiState.update {
                    it.copy(
                        activePageIndex = pageIndex,
                        activeOriginalBitmap = origBmp,
                        activeRotationDegrees = page.rotationDegrees,
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
                recordSavedPdf(pdfFile, filename, pages)
                onSuccess(pdfFile)
            } else {
                _uiState.update { it.copy(isLoading = false, errorMessage = "Failed to generate PDF") }
            }
        }
    }

    /**
     * Exports the optional ready-made PDF directly produced by ML Kit Document Scanner (result.pdf?.uri).
     */
    fun exportMlKitReadyPdf(onSuccess: (File) -> Unit = {}) {
        val readyPath = _uiState.value.mlKitReadyPdfPath ?: return
        val sourceFile = File(readyPath)
        if (!sourceFile.exists()) {
            _uiState.update { it.copy(errorMessage = "Ready-made scanner PDF is no longer available") }
            return
        }
        val pages = _uiState.value.currentPages
        val filename = _uiState.value.exportFilename.ifBlank { "Scan_${System.currentTimeMillis()}" }
        val context = getApplication<Application>()

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loadingMessage = "Saving ready-made PDF...") }
            val exported = withContext(Dispatchers.IO) {
                try {
                    val pdfDir = File(context.getExternalFilesDir(null), "documents").apply {
                        if (!exists()) mkdirs()
                    }
                    val sanitizedName = if (filename.endsWith(".pdf", ignoreCase = true)) {
                        filename
                    } else {
                        "$filename.pdf"
                    }
                    val outFile = File(pdfDir, sanitizedName)
                    sourceFile.copyTo(outFile, overwrite = true)
                    outFile
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }
            if (exported != null && exported.exists()) {
                recordSavedPdf(exported, filename, pages)
                onSuccess(exported)
            } else {
                _uiState.update { it.copy(isLoading = false, errorMessage = "Failed to export ready-made PDF") }
            }
        }
    }

    private fun recordSavedPdf(pdfFile: File, title: String, pages: List<ScannedPage>) {
        val docId = _uiState.value.currentDocumentId ?: UUID.randomUUID().toString()
        val existingDoc = _uiState.value.savedDocuments.find { it.id == docId }
        val newDoc = ScannedDocument(
            id = docId,
            title = title,
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
                currentDocumentTitle = title,
                savedDocuments = updatedDocs,
                lastExportedFile = pdfFile
            )
        }
    }

    fun sharePdf(file: File) {
        PdfGenerator.sharePdf(getApplication(), file)
    }

    fun shareCurrentSessionPdf() {
        val pages = _uiState.value.currentPages
        if (pages.isEmpty()) return

        val existingDoc = _uiState.value.savedDocuments.find { it.id == _uiState.value.currentDocumentId }
        val existingFile = existingDoc?.lastPdfPath?.let { File(it) }

        if (existingFile != null && existingFile.exists() && existingDoc.pages == pages) {
            sharePdf(existingFile)
        } else {
            generatePdf { file ->
                sharePdf(file)
            }
        }
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

    // Document Import feature (PDF, DOCX, PPTX) — kept unchanged
    fun importPdf(uri: Uri) {
        importDocument(uri)
    }

    fun importDocument(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loadingMessage = "Importing document...") }
            try {
                val context = getApplication<Application>()
                val (pdfPath, pages) = PdfImporter.importPdf(context, uri)
                if (pages.isEmpty()) {
                    throw Exception("No renderable pages found in document")
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
                        mlKitReadyPdfPath = null,
                        isMlKitPdfInSync = false,
                        currentScreen = ScreenState.PAGE_LIST,
                        errorMessage = null
                    )
                }
                persistDocuments(updatedList)
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to import document: ${e.localizedMessage ?: e.message}"
                    )
                }
            }
        }
    }

    fun importPdfToCurrentSession(uri: Uri) {
        importDocumentToCurrentSession(uri)
    }

    fun importDocumentToCurrentSession(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loadingMessage = "Importing pages...") }
            try {
                val context = getApplication<Application>()
                val (_, pages) = PdfImporter.importPdf(context, uri)
                if (pages.isEmpty()) {
                    throw Exception("No renderable pages found in document")
                }
                val updatedPages = _uiState.value.currentPages + pages
                _uiState.update {
                    it.copy(
                        currentPages = updatedPages,
                        isMlKitPdfInSync = false,
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
