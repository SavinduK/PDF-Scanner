package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FilterBAndW
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.FilterType
import com.example.model.ScreenState
import com.example.ui.ScannerUiState
import com.example.ui.ScannerViewModel
import com.example.ui.components.rememberDocumentScannerLauncher
import com.example.ui.theme.ScannerOverlayScrim

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterEnhanceScreen(
    uiState: ScannerUiState,
    viewModel: ScannerViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val previewBitmap = uiState.activePreviewBitmap

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
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "Document Filters",
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = { viewModel.cancelPageEdit() },
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .size(40.dp)
                            .background(ScannerOverlayScrim, CircleShape)
                            .testTag("filter_back_btn")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to Pages",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    // Rotate 90 degrees
                    IconButton(
                        onClick = { viewModel.rotateActivePage() },
                        modifier = Modifier.testTag("filter_rotate_action_btn")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.RotateRight,
                            contentDescription = "Rotate Page",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // Save Image to Gallery
                    IconButton(
                        onClick = {
                            viewModel.saveActiveImageToGallery(context) { _, message ->
                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.testTag("filter_save_gallery_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Save Image to Gallery",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // Quick exit & save button
                    IconButton(
                        onClick = { viewModel.saveActivePageAndExit() },
                        modifier = Modifier.testTag("filter_top_exit_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Save and Exit to Document",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.background,
                tonalElevation = 4.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    // Filter options row (selected pastel-blue pill vs dark charcoal chips)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterCard(
                            title = "Enhance",
                            filterType = FilterType.AUTO_ENHANCE,
                            icon = Icons.Default.AutoAwesome,
                            isSelected = uiState.activeFilterType == FilterType.AUTO_ENHANCE,
                            onClick = { viewModel.selectFilter(FilterType.AUTO_ENHANCE) },
                            modifier = Modifier.weight(1f)
                        )
                        FilterCard(
                            title = "B & W",
                            filterType = FilterType.BLACK_WHITE,
                            icon = Icons.Default.FilterBAndW,
                            isSelected = uiState.activeFilterType == FilterType.BLACK_WHITE,
                            onClick = { viewModel.selectFilter(FilterType.BLACK_WHITE) },
                            modifier = Modifier.weight(1f)
                        )
                        FilterCard(
                            title = "Grayscale",
                            filterType = FilterType.GRAYSCALE,
                            icon = Icons.Default.Contrast,
                            isSelected = uiState.activeFilterType == FilterType.GRAYSCALE,
                            onClick = { viewModel.selectFilter(FilterType.GRAYSCALE) },
                            modifier = Modifier.weight(1f)
                        )
                        FilterCard(
                            title = "Original",
                            filterType = FilterType.ORIGINAL,
                            icon = Icons.Default.Image,
                            isSelected = uiState.activeFilterType == FilterType.ORIGINAL,
                            onClick = { viewModel.selectFilter(FilterType.ORIGINAL) },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Action buttons: Outlined dark pill + Pastel periwinkle primary pill
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.saveActivePageAndExit() },
                            shape = CircleShape,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurface
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(54.dp)
                                .testTag("filter_exit_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Save Page",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Done",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            )
                        }

                        Button(
                            onClick = {
                                viewModel.saveActivePageChanges(
                                    navigateTo = ScreenState.PAGE_LIST,
                                    onSaved = launchDocumentScanner
                                )
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            shape = CircleShape,
                            modifier = Modifier
                                .weight(1.15f)
                                .height(54.dp)
                                .testTag("filter_next_scan_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.CameraAlt,
                                contentDescription = "Scan More",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Scan More",
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            if (previewBitmap != null) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    shadowElevation = 8.dp,
                    color = Color.White,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(6.dp))
                ) {
                    Image(
                        bitmap = previewBitmap.asImageBitmap(),
                        contentDescription = "Processed Scanned Document Preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            } else {
                Text(
                    text = "Processing document...",
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
fun FilterCard(
    title: String,
    filterType: FilterType,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = if (isSelected) CircleShape else RoundedCornerShape(12.dp)
    val bgColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }
    val contentColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(shape)
            .background(bgColor)
            .then(
                if (!isSelected) {
                    Modifier.border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                        shape
                    )
                } else {
                    Modifier
                }
            )
            .clickable { onClick() }
            .padding(vertical = 10.dp, horizontal = 4.dp)
            .testTag("filter_option_${filterType.name}")
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = contentColor,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.height(5.dp))
        Text(
            text = title,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = contentColor,
            maxLines = 1
        )
    }
}
