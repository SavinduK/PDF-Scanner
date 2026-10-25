package com.example.processing

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.mlkit.vision.documentscanner.GmsDocumentScanner
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_JPEG
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_PDF
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.SCANNER_MODE_FULL
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult

/**
 * Configures and provides the ML Kit Document Scanner client and Google Play services checks.
 */
object DocumentScannerManager {

    val options: GmsDocumentScannerOptions = GmsDocumentScannerOptions.Builder()
        .setGalleryImportAllowed(true)
        .setPageLimit(20)
        .setResultFormats(RESULT_FORMAT_JPEG, RESULT_FORMAT_PDF)
        .setScannerMode(SCANNER_MODE_FULL)
        .build()

    val scanner: GmsDocumentScanner by lazy {
        GmsDocumentScanning.getClient(options)
    }

    /**
     * Checks Google Play services availability and returns a clear user-facing error if unavailable.
     */
    fun getPlayServicesErrorMessage(context: Context): String? {
        val availability = GoogleApiAvailability.getInstance()
        val status = availability.isGooglePlayServicesAvailable(context)
        if (status == ConnectionResult.SUCCESS) return null

        return when (status) {
            ConnectionResult.SERVICE_MISSING ->
                "Google Play services is not installed on this device. Document scanning requires Google Play services."
            ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED ->
                "Google Play services must be updated to use the document scanner."
            ConnectionResult.SERVICE_DISABLED ->
                "Google Play services is disabled. Please enable it in device settings to scan documents."
            ConnectionResult.SERVICE_INVALID ->
                "Google Play services on this device is not supported."
            else ->
                "Google Play services is unavailable (${availability.getErrorString(status)})."
        }
    }

    /**
     * Formats scanner launch failures into a clear user-facing message.
     */
    fun formatLaunchError(context: Context, error: Throwable): String {
        getPlayServicesErrorMessage(context)?.let { return it }
        if (error is ApiException) {
            return "Document scanner unavailable via Google Play services (code ${error.statusCode})."
        }
        val detail = error.localizedMessage?.trim().orEmpty()
        return if (detail.isNotEmpty()) {
            "Unable to start document scanner: $detail"
        } else {
            "Unable to start document scanner. Please verify Google Play services is enabled."
        }
    }

    /**
     * Extracts ML Kit scan result from Activity result Intent.
     */
    fun parseResult(data: Intent?): GmsDocumentScanningResult? {
        return GmsDocumentScanningResult.fromActivityResultIntent(data)
    }
}

/**
 * Unwraps a Compose Context to find the hosting Activity required by ML Kit Document Scanner.
 */
fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
