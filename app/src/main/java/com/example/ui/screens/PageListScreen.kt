package com.example.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
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
import com.example.ui.components.rememberDocumentScannerLauncher
import com.example.ui.theme.ScannerOverlayScrim
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageListScreen(
    uiState: ScannerUiState,
    viewModel: ScannerViewModel,
    modifier: Modifier = Modifier
) {
    // Auto-save session on system back gesture
    BackHandler {
        viewModel.saveCurrentDocumentSession()
        viewModel.navigateTo(ScreenState.HOME)
    }

    // ML Kit Document Scanner launcher to append pages to the current document
    val launchDocumentScanner = rememberDocumentScannerLauncher(
        onScanSuccess = { pageUris, readyPdfUri ->
            viewModel.onMlKitScanCompleted(
                pageUris = pageUris,
                readyPdfUri = readyPdfUri,
                startNewSession = false
            )
        },
        onError = { errorMsg ->
            viewModel.showError(errorMsg)
        }
    )

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.currentDocumentTitle.ifBlank { "Document Pages" },
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
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
                            contentDescription = "Back to Home",
                            tint = MaterialTheme.colorScheme.onSurface
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
                            tint = if (uiState.currentPages.isNotEmpty()) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            }
                        )
                    }

                    // Save / Export icon
                    IconButton(
                        onClick = { viewModel.openExportDialog() },
                        enabled = uiState.currentPages.isNotEmpty(),
                        modifier = Modifier.testTag("page_list_export_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Save,
                            contentDescription = "Save Document",
                            tint = if (uiState.currentPages.isNotEmpty()) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            }
                        )
                    }

                    // Scan icon (launches ML Kit Document Scanner to add pages)
                    IconButton(
                        onClick = launchDocumentScanner,
                        modifier = Modifier.testTag("page_list_scan_camera_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = "Scan new pages",
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
            if (uiState.currentPages.isNotEmpty()) {
                FloatingActionButton(
                    onClick = launchDocumentScanner,
                    shape = RoundedCornerShape(18.dp),
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier
                        .size(56.dp)
                        .testTag("page_list_add_fab")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.NoteAdd,
                        contentDescription = "Add Pages",
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
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
                    Text(
                        text = "No pages in current document",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = launchDocumentScanner,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("page_list_add_first_btn")
                    ) {
                        Text(
                            text = "Add First Page",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
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
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    state = gridState,
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        top = 12.dp,
                        bottom = 88.dp
                    ),
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
                            onRotate = { viewModel.rotatePageAt(index) },
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
    onRotate: () -> Unit,
    onReFilter: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDragging) {
                MaterialTheme.colorScheme.surfaceContainerHigh
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            }
        ),
        border = BorderStroke(
            width = if (isDragging) 2.dp else 1.dp,
            color = if (isDragging) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
        ),
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
            // Thumbnail preview with sky-blue page badge and dark circular delete overlay
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.72f)
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            ) {
                AsyncImage(
                    model = File(page.processedImagePath),
                    contentDescription = "Page $pageNumber",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { onReFilter() }
                )

                // Sky-blue page number pill badge (matching the reference screenshot thumbnail badge)
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                ) {
                    Text(
                        text = "$pageNumber",
                        color = MaterialTheme.colorScheme.onSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                    )
                }

                // Translucent dark circular delete button (Top-Right)
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(38.dp)
                        .background(ScannerOverlayScrim, CircleShape)
                        .testTag("page_delete_btn_$pageNumber")
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete Page",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Filter label pill (Bottom-Left)
                Surface(
                    shape = RoundedCornerShape(topEnd = 8.dp),
                    color = ScannerOverlayScrim,
                    modifier = Modifier.align(Alignment.BottomStart)
                ) {
                    Text(
                        text = page.filterType.displayName,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            // Controls Bar under thumbnail: Rotate, Filter, Hold & Drag handle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Rotate 90° button
                IconButton(
                    onClick = onRotate,
                    modifier = Modifier
                        .size(42.dp)
                        .testTag("page_rotate_btn_$pageNumber")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.RotateRight,
                        contentDescription = "Rotate Page",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Filter / Enhance button
                IconButton(
                    onClick = onReFilter,
                    modifier = Modifier
                        .size(42.dp)
                        .testTag("page_refilter_btn_$pageNumber")
                ) {
                    Icon(
                        imageVector = Icons.Default.FilterAlt,
                        contentDescription = "Edit Filter",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Hold & Drag Handle Indicator
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .testTag("page_drag_handle_$pageNumber"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.DragHandle,
                        contentDescription = "Hold and drag to move",
                        tint = if (isDragging) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                        },
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
