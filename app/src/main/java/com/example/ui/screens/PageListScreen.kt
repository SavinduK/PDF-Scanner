package com.example.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.example.model.ScannedPage
import com.example.model.ScreenState
import com.example.ui.ScannerUiState
import com.example.ui.ScannerViewModel
import com.example.ui.theme.EmeraldPrimary
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageListScreen(
    uiState: ScannerUiState,
    viewModel: ScannerViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Auto-save session on system back gesture
    BackHandler {
        viewModel.saveCurrentDocumentSession()
        viewModel.navigateTo(ScreenState.HOME)
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            viewModel.onBatchImagesSelected(uris)
        }
    }

    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.importPdfToCurrentSession(uri)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.currentDocumentTitle.ifBlank { "Document Pages" },
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            viewModel.saveCurrentDocumentSession()
                            viewModel.navigateTo(ScreenState.HOME)
                        },
                        modifier = Modifier.testTag("page_list_home_btn")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to Home"
                        )
                    }
                },
                actions = {
                    // Share icon
                    IconButton(
                        onClick = { viewModel.shareCurrentSessionPdf() },
                        enabled = uiState.currentPages.isNotEmpty(),
                        modifier = Modifier.testTag("page_list_share_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share PDF",
                            tint = if (uiState.currentPages.isNotEmpty()) EmeraldPrimary else Color.Gray
                        )
                    }

                    // Export icon
                    IconButton(
                        onClick = { viewModel.openExportDialog() },
                        enabled = uiState.currentPages.isNotEmpty(),
                        modifier = Modifier.testTag("page_list_export_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PictureAsPdf,
                            contentDescription = "Export PDF",
                            tint = if (uiState.currentPages.isNotEmpty()) EmeraldPrimary else Color.Gray
                        )
                    }

                    // Scan icon (opens camera view to add new pages)
                    IconButton(
                        onClick = { viewModel.navigateTo(ScreenState.CAMERA) },
                        modifier = Modifier.testTag("page_list_scan_camera_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = "Scan new pages",
                            tint = EmeraldPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        if (uiState.currentPages.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No pages in current document", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { viewModel.navigateTo(ScreenState.CAMERA) },
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary)
                    ) {
                        Text("Add First Page")
                    }
                }
            }
        } else {
            var draggedIndex by remember { mutableStateOf<Int?>(null) }
            var dragOffset by remember { mutableStateOf(Offset.Zero) }
            val gridState = rememberLazyGridState()
            val haptic = LocalHapticFeedback.current

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // Info banner indicating hold and drag reordering
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.DragHandle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Hold & drag pages to move",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "${uiState.currentPages.size} page${if (uiState.currentPages.size == 1) "" else "s"}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = EmeraldPrimary
                    )
                }

                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    state = gridState,
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("pages_grid")
                        .pointerInput(uiState.currentPages.size) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { startOffset ->
                                    val hitItem = gridState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                                        startOffset.x.toInt() in item.offset.x..(item.offset.x + item.size.width) &&
                                        startOffset.y.toInt() in item.offset.y..(item.offset.y + item.size.height)
                                    }
                                    if (hitItem != null && hitItem.index in uiState.currentPages.indices) {
                                        draggedIndex = hitItem.index
                                        dragOffset = Offset.Zero
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragOffset += dragAmount

                                    val currentIndex = draggedIndex ?: return@detectDragGesturesAfterLongPress
                                    val currentItem = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == currentIndex }
                                    if (currentItem != null) {
                                        val currentCenterX = currentItem.offset.x + currentItem.size.width / 2 + dragOffset.x
                                        val currentCenterY = currentItem.offset.y + currentItem.size.height / 2 + dragOffset.y

                                        val targetItem = gridState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                                            item.index != currentIndex &&
                                            item.index in uiState.currentPages.indices &&
                                            currentCenterX.toInt() in item.offset.x..(item.offset.x + item.size.width) &&
                                            currentCenterY.toInt() in item.offset.y..(item.offset.y + item.size.height)
                                        }

                                        if (targetItem != null) {
                                            viewModel.movePage(currentIndex, targetItem.index)
                                            val deltaX = currentItem.offset.x - targetItem.offset.x
                                            val deltaY = currentItem.offset.y - targetItem.offset.y
                                            dragOffset += Offset(deltaX.toFloat(), deltaY.toFloat())
                                            draggedIndex = targetItem.index
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                    }
                                },
                                onDragEnd = {
                                    draggedIndex = null
                                    dragOffset = Offset.Zero
                                },
                                onDragCancel = {
                                    draggedIndex = null
                                    dragOffset = Offset.Zero
                                }
                            )
                        }
                ) {
                    itemsIndexed(uiState.currentPages, key = { _, page -> page.id }) { index, page ->
                        val isDragging = draggedIndex == index
                        PageThumbnailCard(
                            page = page,
                            pageNumber = index + 1,
                            isDragging = isDragging,
                            dragOffset = if (isDragging) dragOffset else Offset.Zero,
                            onReCrop = { viewModel.reCropPage(index) },
                            onReFilter = { viewModel.reFilterPage(index) },
                            onDelete = { viewModel.deletePage(index) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PageThumbnailCard(
    page: ScannedPage,
    pageNumber: Int,
    isDragging: Boolean,
    dragOffset: Offset,
    onReCrop: () -> Unit,
    onReFilter: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDragging) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        border = if (isDragging) BorderStroke(2.dp, EmeraldPrimary) else null,
        elevation = CardDefaults.cardElevation(defaultElevation = if (isDragging) 16.dp else 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (isDragging) 10f else 1f)
            .graphicsLayer {
                if (isDragging) {
                    translationX = dragOffset.x
                    translationY = dragOffset.y
                    scaleX = 1.06f
                    scaleY = 1.06f
                    shadowElevation = 24f
                }
            }
            .testTag("page_card_$pageNumber")
    ) {
        Column {
            // Thumbnail preview with page badge and delete
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.72f)
                    .background(Color.DarkGray)
            ) {
                AsyncImage(
                    model = File(page.processedImagePath),
                    contentDescription = "Page $pageNumber",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { onReCrop() }
                )

                // Page number chip (Top-Left)
                Surface(
                    shape = RoundedCornerShape(topStart = 8.dp, bottomEnd = 8.dp),
                    color = EmeraldPrimary,
                    modifier = Modifier.align(Alignment.TopStart)
                ) {
                    Text(
                        text = "Page $pageNumber",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                // Delete button (Top-Right)
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(32.dp)
                        .background(Color(0x88000000), CircleShape)
                        .testTag("page_delete_btn_$pageNumber")
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete Page",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Filter label pill (Bottom-Left)
                Surface(
                    shape = RoundedCornerShape(topEnd = 6.dp),
                    color = Color(0x99000000),
                    modifier = Modifier.align(Alignment.BottomStart)
                ) {
                    Text(
                        text = page.filterType.displayName,
                        color = Color.White,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // Controls Bar under thumbnail: Re-crop, Re-filter, Hold & Drag handle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Re-crop button
                IconButton(
                    onClick = onReCrop,
                    modifier = Modifier.size(36.dp).testTag("page_recrop_btn_$pageNumber")
                ) {
                    Icon(
                        imageVector = Icons.Default.Crop,
                        contentDescription = "Re-crop",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Re-filter button
                IconButton(
                    onClick = onReFilter,
                    modifier = Modifier.size(36.dp).testTag("page_refilter_btn_$pageNumber")
                ) {
                    Icon(
                        imageVector = Icons.Default.FilterAlt,
                        contentDescription = "Re-filter",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Hold & Drag Handle Indicator
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("page_drag_handle_$pageNumber"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.DragHandle,
                        contentDescription = "Hold and drag to move",
                        tint = if (isDragging) EmeraldPrimary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
