package com.example.ui.screens

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
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Crop
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.FilterType
import com.example.model.ScreenState
import com.example.ui.ScannerUiState
import com.example.ui.ScannerViewModel
import com.example.ui.theme.EmeraldPrimary
import com.example.ui.theme.ScannerAccent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterEnhanceScreen(
    uiState: ScannerUiState,
    viewModel: ScannerViewModel,
    modifier: Modifier = Modifier
) {
    val previewBitmap = uiState.activePreviewBitmap

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color(0xFF0F1413),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "Document Filters",
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = Color.White
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = { viewModel.navigateTo(ScreenState.CROP_ADJUST) },
                        modifier = Modifier.testTag("filter_back_to_crop_btn")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to Crop",
                            tint = Color.White
                        )
                    }
                },
                actions = {
                    // Quick re-crop button
                    IconButton(
                        onClick = { viewModel.navigateTo(ScreenState.CROP_ADJUST) },
                        modifier = Modifier.testTag("filter_recrop_action_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Crop,
                            contentDescription = "Re-crop",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color(0xFF141D1B)
                )
            )
        },
        bottomBar = {
            Surface(
                color = Color(0xFF141D1B),
                tonalElevation = 8.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    // Filter options row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterCard(
                            title = "Magic Color",
                            filterType = FilterType.AUTO_ENHANCE,
                            icon = Icons.Default.AutoFixHigh,
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

                    // Primary Save/Next Button
                    Button(
                        onClick = { viewModel.saveActivePageChanges() },
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("filter_save_page_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Save Page to Document",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            if (previewBitmap != null) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    shadowElevation = 8.dp,
                    color = Color.White,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(8.dp))
                ) {
                    Image(
                        bitmap = previewBitmap.asImageBitmap(),
                        contentDescription = "Processed Scanned Document Preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            } else {
                Text("Processing document...", color = Color.White)
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
    val borderColor = if (isSelected) ScannerAccent else Color.White.copy(alpha = 0.2f)
    val bgColor = if (isSelected) EmeraldPrimary.copy(alpha = 0.25f) else Color(0xFF1E2826)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .border(if (isSelected) 2.dp else 1.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(vertical = 10.dp, horizontal = 4.dp)
            .testTag("filter_option_${filterType.name}")
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = if (isSelected) ScannerAccent else Color.White,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = title,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) ScannerAccent else Color.White.copy(alpha = 0.85f),
            maxLines = 1
        )
    }
}
