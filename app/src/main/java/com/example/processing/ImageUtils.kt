package com.example.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

object ImageUtils {

    /**
     * Copies a content:// or file:// Uri stream directly into app-private storage.
     */
    suspend fun copyUriToAppStorage(
        context: Context,
        uri: Uri,
        subDir: String = "scans",
        prefix: String = "mlkit",
        extension: String = "jpg"
    ): String? = withContext(Dispatchers.IO) {
        try {
            val targetDir = File(context.filesDir, subDir).apply { if (!exists()) mkdirs() }
            val destFile = File(targetDir, "${prefix}_${UUID.randomUUID()}.$extension")

            val inputStream = context.contentResolver.openInputStream(uri)
                ?: if (uri.scheme == "file" && !uri.path.isNullOrEmpty()) {
                    FileInputStream(File(uri.path!!))
                } else {
                    null
                }

            inputStream?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            } ?: return@withContext null

            if (destFile.exists() && destFile.length() > 0L) destFile.absolutePath else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun loadBitmapFromUri(context: Context, uri: Uri, maxDimension: Int = 2400): Bitmap? =
        withContext(Dispatchers.IO) {
            try {
                // First read bounds and orientation
                var orientation = ExifInterface.ORIENTATION_NORMAL
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    try {
                        val exif = ExifInterface(stream)
                        orientation = exif.getAttributeInt(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL
                        )
                    } catch (_: Exception) { }
                }

                // Decode bounds
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, options)
                }

                if (options.outWidth <= 0 || options.outHeight <= 0) return@withContext null

                // Calculate sample size
                var sampleSize = 1
                var w = options.outWidth
                var h = options.outHeight
                while (w > maxDimension || h > maxDimension) {
                    sampleSize *= 2
                    w /= 2
                    h /= 2
                }

                val decodeOptions = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }

                val rawBitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, decodeOptions)
                } ?: return@withContext null

                applyExifOrientation(rawBitmap, orientation)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

    suspend fun loadBitmapFromFile(filePath: String, maxDimension: Int = 2400): Bitmap? =
        withContext(Dispatchers.IO) {
            try {
                val file = File(filePath)
                if (!file.exists()) return@withContext null

                var orientation = ExifInterface.ORIENTATION_NORMAL
                try {
                    val exif = ExifInterface(filePath)
                    orientation = exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                } catch (_: Exception) { }

                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(filePath, options)
                if (options.outWidth <= 0 || options.outHeight <= 0) return@withContext null

                var sampleSize = 1
                var w = options.outWidth
                var h = options.outHeight
                while (w > maxDimension || h > maxDimension) {
                    sampleSize *= 2
                    w /= 2
                    h /= 2
                }

                val decodeOptions = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val decoded = BitmapFactory.decodeFile(filePath, decodeOptions) ?: return@withContext null
                applyExifOrientation(decoded, orientation)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val rotationAngle = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (rotationAngle == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(rotationAngle) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated != bitmap) {
            bitmap.recycle()
        }
        return rotated
    }

    suspend fun saveBitmapToFile(context: Context, bitmap: Bitmap, quality: Int = 92): String? =
        withContext(Dispatchers.IO) {
            try {
                val scansDir = File(context.filesDir, "scans").apply { if (!exists()) mkdirs() }
                val file = File(scansDir, "page_${UUID.randomUUID()}.jpg")
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                    out.flush()
                }
                file.absolutePath
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

    fun rotateBitmap(source: Bitmap, degrees: Float): Bitmap {
        val normalized = ((degrees % 360f) + 360f) % 360f
        if (normalized == 0f) return source
        val matrix = Matrix().apply { postRotate(normalized) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    suspend fun saveBitmapToGallery(
        context: Context,
        bitmap: Bitmap,
        title: String = "Scanned_Doc_${System.currentTimeMillis()}"
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            val contentResolver = context.contentResolver
            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "$title.jpg")
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/PDFScanner")
                    put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val imageUri = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                ?: return@withContext null

            contentResolver.openOutputStream(imageUri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                contentResolver.update(imageUri, contentValues, null, null)
            }

            imageUri
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
