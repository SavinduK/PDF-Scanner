package com.example.processing

import android.content.Context
import android.net.Uri
import com.example.model.ScannedPage

object PdfImporter {

    suspend fun importPdf(
        context: Context,
        uri: Uri
    ): Pair<String, List<ScannedPage>> = DocumentImporter.importDocument(context, uri)
}

