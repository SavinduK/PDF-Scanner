package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.ScreenState
import com.example.ui.ScannerUiState
import com.example.ui.ScannerViewModel
import com.example.ui.screens.CameraCaptureScreen
import com.example.ui.screens.CropAdjustScreen
import com.example.ui.screens.ExportShareDialog
import com.example.ui.screens.FilterEnhanceScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.PageListScreen
import com.example.ui.theme.EmeraldPrimary
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private val viewModel: ScannerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                ScannerApp(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun ScannerApp(viewModel: ScannerViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearErrorMessage()
        }
    }

    LaunchedEffect(uiState.infoMessage) {
        uiState.infoMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearInfoMessage()
        }
    }

    // System Back Press handling
    BackHandler(enabled = uiState.currentScreen != ScreenState.HOME) {
        when (uiState.currentScreen) {
            ScreenState.CAMERA -> {
                if (uiState.currentPages.isNotEmpty()) {
                    viewModel.navigateTo(ScreenState.PAGE_LIST)
                } else {
                    viewModel.navigateTo(ScreenState.HOME)
                }
            }
            ScreenState.CROP_ADJUST -> {
                if (uiState.currentPages.isNotEmpty()) {
                    viewModel.navigateTo(ScreenState.PAGE_LIST)
                } else {
                    viewModel.navigateTo(ScreenState.CAMERA)
                }
            }
            ScreenState.FILTER_ENHANCE -> viewModel.navigateTo(ScreenState.CROP_ADJUST)
            ScreenState.PAGE_LIST -> {
                if (uiState.currentPages.isNotEmpty()) {
                    viewModel.saveCurrentDocumentSession()
                }
                viewModel.navigateTo(ScreenState.HOME)
            }
            ScreenState.HOME -> { /* system handles exiting app */ }
        }
    }

    Box(modifier = Modifier.fillMaxSize().testTag("scanner_app_root")) {
        // Screen Navigation
        when (uiState.currentScreen) {
            ScreenState.HOME -> HomeScreen(uiState = uiState, viewModel = viewModel)
            ScreenState.CAMERA -> CameraCaptureScreen(uiState = uiState, viewModel = viewModel)
            ScreenState.CROP_ADJUST -> CropAdjustScreen(uiState = uiState, viewModel = viewModel)
            ScreenState.FILTER_ENHANCE -> FilterEnhanceScreen(uiState = uiState, viewModel = viewModel)
            ScreenState.PAGE_LIST -> PageListScreen(uiState = uiState, viewModel = viewModel)
        }

        // Export & Share Dialog
        if (uiState.isExportDialogOpen) {
            ExportShareDialog(
                uiState = uiState,
                viewModel = viewModel,
                onDismiss = { viewModel.closeExportDialog() }
            )
        }

        // Global Loading Overlay
        AnimatedVisibility(
            visible = uiState.isLoading,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.65f))
                    .testTag("loading_overlay"),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 8.dp,
                    shadowElevation = 8.dp,
                    modifier = Modifier.padding(32.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            color = EmeraldPrimary,
                            strokeWidth = 3.5.dp,
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = uiState.loadingMessage.ifBlank { "Processing..." },
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )
    }
}
