package com.example.ui.components

import android.app.Activity
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.example.processing.DocumentScannerManager
import com.example.processing.findActivity
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult

/**
 * Registers StartIntentSenderForResult for the ML Kit Document Scanner and returns a trigger lambda.
 * Handles RESULT_CANCELED quietly and reports Play services / launch errors clearly via Toast + callback.
 */
@Composable
fun rememberDocumentScannerLauncher(
    onScanSuccess: (pageImageUris: List<Uri>, readyPdfUri: Uri?) -> Unit,
    onError: (String) -> Unit = {}
): () -> Unit {
    val context = LocalContext.current
    val currentOnScanSuccess by rememberUpdatedState(onScanSuccess)
    val currentOnError by rememberUpdatedState(onError)

    val intentSenderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        when (result.resultCode) {
            Activity.RESULT_OK -> {
                val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
                val pageUris = scanResult?.pages?.map { it.imageUri }.orEmpty()
                val pdfUri = scanResult?.pdf?.uri
                if (pageUris.isNotEmpty() || pdfUri != null) {
                    currentOnScanSuccess(pageUris, pdfUri)
                } else {
                    val msg = "No scanned pages were returned."
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    currentOnError(msg)
                }
            }
            Activity.RESULT_CANCELED -> {
                // Handle user cancellation quietly
            }
        }
    }

    return remember(context, intentSenderLauncher) {
        {
            val playError = DocumentScannerManager.getPlayServicesErrorMessage(context)
            if (playError != null) {
                Toast.makeText(context, playError, Toast.LENGTH_LONG).show()
                currentOnError(playError)
            } else {
                val activity = context.findActivity()
                if (activity == null) {
                    val msg = "Unable to start scanner: host Activity unavailable."
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    currentOnError(msg)
                } else {
                    DocumentScannerManager.scanner.getStartScanIntent(activity)
                        .addOnSuccessListener { intentSender ->
                            intentSenderLauncher.launch(
                                IntentSenderRequest.Builder(intentSender).build()
                            )
                        }
                        .addOnFailureListener { e ->
                            val msg = DocumentScannerManager.formatLaunchError(context, e)
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            currentOnError(msg)
                        }
                }
            }
        }
    }
}
