package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PostAdd
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.model.GeneratedPdfItem
import com.example.model.HomeTab
import com.example.model.ScannedDocument
import com.example.model.ScreenState
import com.example.ui.ScannerUiState
import com.example.ui.ScannerViewModel
import com.example.ui.theme.EmeraldPrimary
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    uiState: ScannerUiState,
    viewModel: ScannerViewModel,
    modifier: Modifier = Modifier
) {
    // Multi-select image picker for batch import (HEIC, JPG, PNG)
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            viewModel.onBatchImagesSelected(uris)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            Column {
                CenterAlignedTopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = EmeraldPrimary,
                                modifier = Modifier.size(34.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Description,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "PDF Scanner",
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )

                // Navigation between Separate Screens: Document Mode and PDF Files
                TabRow(
                    selectedTabIndex = if (uiState.selectedHomeTab == HomeTab.DOCUMENTS) 0 else 1,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = EmeraldPrimary,
                    indicator = { tabPositions ->
                        val selectedIndex = if (uiState.selectedHomeTab == HomeTab.DOCUMENTS) 0 else 1
                        if (selectedIndex in tabPositions.indices) {
                            TabRowDefaults.SecondaryIndicator(
                                modifier = Modifier.tabIndicatorOffset(tabPositions[selectedIndex]),
                                color = EmeraldPrimary
                            )
                        }
                    }
                ) {
                    Tab(
                        selected = uiState.selectedHomeTab == HomeTab.DOCUMENTS,
                        onClick = { viewModel.selectHomeTab(HomeTab.DOCUMENTS) },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Description,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Document Mode (${uiState.savedDocuments.size})",
                                    fontWeight = if (uiState.selectedHomeTab == HomeTab.DOCUMENTS) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 13.5.sp
                                )
                            }
                        },
                        modifier = Modifier.testTag("tab_document_mode")
                    )

                    Tab(
                        selected = uiState.selectedHomeTab == HomeTab.PDF_FILES,
                        onClick = { viewModel.selectHomeTab(HomeTab.PDF_FILES) },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.PictureAsPdf,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "PDF Files (${uiState.pdfFiles.size})",
                                    fontWeight = if (uiState.selectedHomeTab == HomeTab.PDF_FILES) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 13.5.sp
                                )
                            }
                        },
                        modifier = Modifier.testTag("tab_pdf_files")
                    )
                }
            }
        },
        bottomBar = {
            Surface(
                tonalElevation = 8.dp,
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Batch import from gallery
                    OutlinedButton(
                        onClick = {
                            galleryLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .testTag("home_batch_import_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhotoLibrary,
                            contentDescription = "Import Gallery",
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Import Photos", fontWeight = FontWeight.SemiBold)
                    }

                    // Primary Scan Camera button
                    Button(
                        onClick = { viewModel.startNewScanSession() },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .testTag("home_camera_scan_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = "Camera Scan",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("New Scan", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Active session banner if in progress
            if (uiState.currentPages.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .clickable { viewModel.navigateTo(ScreenState.PAGE_LIST) }
                        .testTag("resume_session_card")
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Document Session in Progress",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontSize = 15.sp
                            )
                            Text(
                                text = "${uiState.currentPages.size} page(s) • Saved permanently in Document Mode",
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                                fontSize = 13.sp
                            )
                        }
                        Button(
                            onClick = { viewModel.navigateTo(ScreenState.PAGE_LIST) },
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Resume", color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // Render separate screen content based on selected tab
            when (uiState.selectedHomeTab) {
                HomeTab.DOCUMENTS -> {
                    DocumentModeScreenContent(
                        uiState = uiState,
                        viewModel = viewModel,
                        onScanClick = { viewModel.startNewScanSession() },
                        onGalleryClick = {
                            galleryLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                    )
                }
                HomeTab.PDF_FILES -> {
                    PdfFilesScreenContent(
                        uiState = uiState,
                        viewModel = viewModel
                    )
                }
            }
        }
    }
}

@Composable
private fun DocumentModeScreenContent(
    uiState: ScannerUiState,
    viewModel: ScannerViewModel,
    onScanClick: () -> Unit,
    onGalleryClick: () -> Unit
) {
    if (uiState.savedDocuments.isEmpty() && uiState.currentPages.isEmpty()) {
        EmptyStateView(
            title = "No Saved Documents in Document Mode",
            description = "Scanned and imported documents are saved here permanently. You can re-open, add or remove pages, and convert them to PDF anytime.",
            onScanClick = onScanClick,
            onGalleryClick = onGalleryClick
        )
    } else {
        Text(
            text = "Saved Documents in Document Mode (${uiState.savedDocuments.size})",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(uiState.savedDocuments, key = { it.id }) { doc ->
                DocumentItemCard(
                    document = doc,
                    onEditPages = {
                        viewModel.loadDocumentForEditing(doc)
                    },
                    onExportPdf = {
                        viewModel.openExportDialogForDocument(doc)
                    },
                    onShare = {
                        doc.lastPdfPath?.let { path ->
                            val file = File(path)
                            if (file.exists()) viewModel.sharePdf(file)
                        }
                    },
                    onOpen = {
                        if (doc.lastPdfPath != null && File(doc.lastPdfPath).exists()) {
                            viewModel.viewPdf(File(doc.lastPdfPath))
                        } else if (doc.pages.isNotEmpty()) {
                            viewModel.loadDocumentForEditing(doc)
                        }
                    },
                    onDelete = { viewModel.deleteDocument(doc) }
                )
            }
        }
    }
}

@Composable
private fun PdfFilesScreenContent(
    uiState: ScannerUiState,
    viewModel: ScannerViewModel
) {
    if (uiState.pdfFiles.isEmpty()) {
        PdfFilesEmptyState(
            onGoToDocuments = { viewModel.selectHomeTab(HomeTab.DOCUMENTS) }
        )
    } else {
        Text(
            text = "Generated PDF Files (${uiState.pdfFiles.size})",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(uiState.pdfFiles, key = { it.id }) { pdfItem ->
                PdfFileItemCard(
                    pdfItem = pdfItem,
                    onView = { viewModel.viewPdf(File(pdfItem.filePath)) },
                    onShare = { viewModel.sharePdf(File(pdfItem.filePath)) },
                    onEditInDocumentMode = {
                        pdfItem.documentId?.let { docId ->
                            viewModel.loadDocumentForEditingById(docId)
                        }
                    },
                    onDelete = { viewModel.deletePdfFile(pdfItem) }
                )
            }
        }
    }
}

@Composable
fun DocumentItemCard(
    document: ScannedDocument,
    onEditPages: () -> Unit,
    onShare: () -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onExportPdf: () -> Unit = onEditPages
) {
    val dateFormat = SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.getDefault())
    val dateStr = dateFormat.format(Date(document.updatedAt))
    val sizeKb = (document.pdfFileSizeBytes / 1024).coerceAtLeast(1)
    val pageCount = document.pages.size
    val hasRelatedPdf = document.lastPdfPath != null && File(document.lastPdfPath).exists()
    val firstPage = document.pages.firstOrNull()

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() }
            .testTag("doc_card_${document.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail preview or Document icon
            if (firstPage != null && File(firstPage.processedImagePath).exists()) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.DarkGray)
                ) {
                    AsyncImage(
                        model = File(firstPage.processedImagePath),
                        contentDescription = document.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = EmeraldPrimary.copy(alpha = 0.15f),
                    modifier = Modifier.size(52.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Description,
                            contentDescription = null,
                            tint = EmeraldPrimary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = document.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "$pageCount page(s) • $dateStr",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                // Status pill
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (hasRelatedPdf) EmeraldPrimary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Text(
                        text = if (hasRelatedPdf) "PDF Linked ($sizeKb KB)" else "Editable Document",
                        color = if (hasRelatedPdf) EmeraldPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // Edit / Add pages button
            IconButton(
                onClick = onEditPages,
                modifier = Modifier.size(38.dp).testTag("doc_edit_pages_btn")
            ) {
                Icon(
                    imageVector = Icons.Default.PostAdd,
                    contentDescription = "Edit in Document Mode",
                    tint = EmeraldPrimary,
                    modifier = Modifier.size(22.dp)
                )
            }

            // Convert to PDF button
            IconButton(
                onClick = onExportPdf,
                modifier = Modifier.size(38.dp).testTag("doc_convert_pdf_btn_${document.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.PictureAsPdf,
                    contentDescription = "Convert to PDF",
                    tint = if (hasRelatedPdf) EmeraldPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Quick share button
            IconButton(
                onClick = onShare,
                enabled = hasRelatedPdf,
                modifier = Modifier.size(38.dp).testTag("doc_share_btn")
            ) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = "Share PDF",
                    tint = if (hasRelatedPdf) MaterialTheme.colorScheme.onSurfaceVariant else Color.Gray.copy(alpha = 0.3f),
                    modifier = Modifier.size(20.dp)
                )
            }

            // Delete button
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(38.dp).testTag("doc_delete_btn")
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete Document",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun PdfFileItemCard(
    pdfItem: GeneratedPdfItem,
    onView: () -> Unit,
    onShare: () -> Unit,
    onEditInDocumentMode: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.getDefault())
    val dateStr = dateFormat.format(Date(pdfItem.lastModified))
    val sizeKb = (pdfItem.fileSizeBytes / 1024).coerceAtLeast(1)

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onView() }
            .testTag("pdf_item_card_${pdfItem.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFFE53935).copy(alpha = 0.15f),
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.PictureAsPdf,
                        contentDescription = null,
                        tint = Color(0xFFE53935),
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = pdfItem.fileName,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = if (pdfItem.pageCount > 0) "${pdfItem.pageCount} page(s) • $sizeKb KB • $dateStr" else "$sizeKb KB • $dateStr",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (pdfItem.documentId != null) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = "Source: ${pdfItem.documentTitle}",
                        fontSize = 11.sp,
                        color = EmeraldPrimary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // View / Open PDF
            IconButton(
                onClick = onView,
                modifier = Modifier.size(38.dp).testTag("pdf_view_btn_${pdfItem.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.Visibility,
                    contentDescription = "Open PDF",
                    tint = EmeraldPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Share PDF
            IconButton(
                onClick = onShare,
                modifier = Modifier.size(38.dp).testTag("pdf_share_btn_${pdfItem.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = "Share PDF",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Edit source document in Document Mode
            if (pdfItem.documentId != null) {
                IconButton(
                    onClick = onEditInDocumentMode,
                    modifier = Modifier.size(38.dp).testTag("pdf_edit_doc_btn_${pdfItem.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit in Document Mode",
                        tint = EmeraldPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Delete PDF file
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(38.dp).testTag("pdf_delete_btn_${pdfItem.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete PDF",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun EmptyStateView(
    title: String = "No Scanned Documents Yet",
    description: String = "Capture receipts, documents, book pages, or import images to create multi-page PDFs with automatic edge deskewing and enhancement.",
    onScanClick: () -> Unit,
    onGalleryClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = CircleShape,
            color = EmeraldPrimary.copy(alpha = 0.12f),
            modifier = Modifier.size(96.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Description,
                    contentDescription = null,
                    tint = EmeraldPrimary,
                    modifier = Modifier.size(48.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = title,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = description,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 20.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        Spacer(modifier = Modifier.height(28.dp))

        Button(
            onClick = onScanClick,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .height(48.dp)
                .testTag("empty_state_scan_btn")
        ) {
            Icon(Icons.Default.CameraAlt, contentDescription = null, tint = Color.White)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Start Scanning", fontWeight = FontWeight.Bold, color = Color.White)
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = onGalleryClick,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .height(48.dp)
                .testTag("empty_state_gallery_btn")
        ) {
            Icon(Icons.Default.PhotoLibrary, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Batch Import Gallery", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun PdfFilesEmptyState(
    onGoToDocuments: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = CircleShape,
            color = Color(0xFFE53935).copy(alpha = 0.12f),
            modifier = Modifier.size(96.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.PictureAsPdf,
                    contentDescription = null,
                    tint = Color(0xFFE53935),
                    modifier = Modifier.size(48.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "No PDF Files Generated Yet",
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Convert your scanned documents in Document Mode to PDF. Your generated PDF files will be stored here for easy viewing, sharing, or editing.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 20.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        Spacer(modifier = Modifier.height(28.dp))

        Button(
            onClick = onGoToDocuments,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .height(48.dp)
                .testTag("pdf_empty_go_to_docs_btn")
        ) {
            Icon(Icons.Default.Description, contentDescription = null, tint = Color.White)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Go to Document Mode", fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}
