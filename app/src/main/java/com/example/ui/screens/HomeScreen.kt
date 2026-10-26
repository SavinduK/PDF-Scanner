package com.example.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.ScannedDocument
import com.example.ui.ScannerUiState
import com.example.ui.ScannerViewModel
import com.example.ui.components.rememberDocumentScannerLauncher
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
    val context = LocalContext.current

    // ML Kit Document Scanner launcher (handles both camera scan and gallery image import)
    val launchDocumentScanner = rememberDocumentScannerLauncher(
        onScanSuccess = { pageUris, readyPdfUri ->
            viewModel.onMlKitScanCompleted(
                pageUris = pageUris,
                readyPdfUri = readyPdfUri,
                startNewSession = true
            )
        },
        onError = { errorMsg ->
            viewModel.showError(errorMsg)
        }
    )

    val supportedDocumentMimes = arrayOf(
        "application/pdf",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/msword",
        "application/vnd.ms-powerpoint",
        "*/*"
    )

    // Document import launcher (PDF, DOCX, PPTX) — kept unchanged
    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.importDocument(uri)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Description,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "PDF Scanner",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    // New scan icon (launches ML Kit Document Scanner)
                    IconButton(
                        onClick = launchDocumentScanner,
                        modifier = Modifier.testTag("home_new_scan_action_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = "New Scan",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    // Upload document button (PDF, DOCX, PPTX)
                    IconButton(
                        onClick = { documentPickerLauncher.launch(supportedDocumentMimes) },
                        modifier = Modifier.testTag("home_import_pdf_action_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Upload,
                            contentDescription = "Upload Document",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            if (uiState.savedDocuments.isNotEmpty()) {
                FloatingActionButton(
                    onClick = launchDocumentScanner,
                    shape = RoundedCornerShape(18.dp),
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier
                        .size(56.dp)
                        .testTag("home_scan_fab")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.NoteAdd,
                        contentDescription = "Scan New Document",
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (uiState.savedDocuments.isEmpty()) {
                EmptyStateView(
                    onScanClick = launchDocumentScanner,
                    onGalleryClick = launchDocumentScanner,
                    onImportDocumentClick = {
                        documentPickerLauncher.launch(supportedDocumentMimes)
                    }
                )
            } else {
                Text(
                    text = "Saved Documents (${uiState.savedDocuments.size})",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                LazyColumn(
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = 88.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(uiState.savedDocuments, key = { it.id }) { doc ->
                        DocumentItemCard(
                            document = doc,
                            onEditPages = {
                                if (doc.pages.isNotEmpty()) {
                                    viewModel.loadDocumentForEditing(doc)
                                }
                            },
                            onShare = {
                                doc.lastPdfPath?.let { path ->
                                    val file = File(path)
                                    if (file.exists()) viewModel.sharePdf(file)
                                }
                            },
                            onSaveToDevice = {
                                viewModel.savePdfToDeviceStorage(context, doc) { _, message ->
                                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
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
    }
}

@Composable
fun DocumentItemCard(
    document: ScannedDocument,
    onEditPages: () -> Unit,
    onShare: () -> Unit,
    onSaveToDevice: () -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    // Format date as e.g. "15 SEP"
    val dateStr = SimpleDateFormat("dd MMM", Locale.US).format(Date(document.createdAt)).uppercase()

    // Format file size
    val calculatedSizeBytes = if (document.pdfFileSizeBytes > 0L) {
        document.pdfFileSizeBytes
    } else if (document.lastPdfPath != null && File(document.lastPdfPath).exists()) {
        File(document.lastPdfPath).length()
    } else {
        val totalPageBytes = document.pages.sumOf { page ->
            val f = File(page.processedImagePath)
            if (f.exists()) f.length() else 0L
        }
        if (totalPageBytes > 0L) totalPageBytes else (document.pages.size * 350L * 1024L)
    }

    val formattedSize = when {
        calculatedSizeBytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", calculatedSizeBytes / (1024f * 1024f))
        else -> "${(calculatedSizeBytes / 1024).coerceAtLeast(1)} KB"
    }

    val pageCount = document.pages.size
    val pageText = if (pageCount == 1) "1 page" else "$pageCount pages"

    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() }
            .testTag("doc_card_${document.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // PDF icon badge
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.PictureAsPdf,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // 3 separate lines:
            // 1. title in bold text
            // 2. date (eg 15 SEP )
            // 3. file size and no of pages
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 14.dp, end = 8.dp)
            ) {
                Text(
                    text = document.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = dateStr,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "$formattedSize • $pageText",
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                )
            }

            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("doc_menu_btn_${document.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More Options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.testTag("doc_dropdown_${document.id}")
                ) {
                    DropdownMenuItem(
                        text = { Text("Edit Pages") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onEditPages()
                        },
                        modifier = Modifier.testTag("doc_menu_edit_${document.id}")
                    )

                    DropdownMenuItem(
                        text = { Text("Share PDF") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onShare()
                        },
                        modifier = Modifier.testTag("doc_menu_share_${document.id}")
                    )

                    DropdownMenuItem(
                        text = { Text("Save to Device Storage") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.FileDownload,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onSaveToDevice()
                        },
                        modifier = Modifier.testTag("doc_menu_save_device_${document.id}")
                    )

                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                        modifier = Modifier.testTag("doc_menu_delete_${document.id}")
                    )
                }
            }
        }
    }
}

@Composable
fun EmptyStateView(
    onScanClick: () -> Unit,
    onGalleryClick: () -> Unit,
    onImportDocumentClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(96.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(46.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "No Scanned Documents Yet",
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Capture receipts, documents, book pages, or upload PDF, Word (DOCX), or PowerPoint (PPTX) files to create and edit multi-page documents.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 20.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.widthIn(max = 460.dp)
        )

        Spacer(modifier = Modifier.height(30.dp))

        Button(
            onClick = onScanClick,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ),
            modifier = Modifier
                .widthIn(max = 360.dp)
                .fillMaxWidth(0.88f)
                .height(52.dp)
                .testTag("empty_state_scan_btn")
        ) {
            Icon(
                imageVector = Icons.Default.CameraAlt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Start Scanning",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = onImportDocumentClick,
            shape = CircleShape,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurface
            ),
            modifier = Modifier
                .widthIn(max = 360.dp)
                .fillMaxWidth(0.88f)
                .height(52.dp)
                .testTag("empty_state_import_pdf_btn")
        ) {
            Text(
                text = "Upload Document",
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = onGalleryClick,
            shape = CircleShape,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurface
            ),
            modifier = Modifier
                .widthIn(max = 360.dp)
                .fillMaxWidth(0.88f)
                .height(52.dp)
                .testTag("empty_state_gallery_btn")
        ) {
            Icon(
                imageVector = Icons.Default.PhotoLibrary,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Import from Photos",
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
