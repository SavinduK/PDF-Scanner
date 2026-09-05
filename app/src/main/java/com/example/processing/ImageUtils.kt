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
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

object ImageUtils {

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

                val decodedBitmap: Bitmap = rawBitmap

                // Apply EXIF rotation if needed
                val rotationAngle = when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }

                if (rotationAngle != 0f) {
                    val matrix = Matrix().apply { postRotate(rotationAngle) }
                    val rotated = Bitmap.createBitmap(
                        decodedBitmap, 0, 0,
                        decodedBitmap.width, decodedBitmap.height,
                        matrix, true
                    )
                    if (rotated != decodedBitmap) {
                        decodedBitmap.recycle()
                    }
                    rotated
                } else {
                    decodedBitmap
                }
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
                BitmapFactory.decodeFile(filePath, decodeOptions)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
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
        if (degrees == 0f) return source
        val matrix = Matrix().apply { postRotate(degrees) }
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
