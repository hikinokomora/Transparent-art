package io.github.transparentart

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlin.math.max
import kotlin.math.min

/**
 * Decodes a gallery image scaled so its longest side is [maxSide] (small images
 * are enlarged at most 4×) and rotated upright according to its EXIF data.
 */
fun loadBitmap(context: Context, uri: Uri, maxSide: Int = 1280): Bitmap? {
    val resolver = context.contentResolver

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val decoded = resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, options)
    } ?: return null

    val rotation = try {
        resolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
    } catch (e: Exception) {
        0
    }

    // Small pictures are enlarged too: the sketch filter is tuned for this working size.
    val scale = min(4f, maxSide.toFloat() / max(decoded.width, decoded.height))
    val matrix = Matrix().apply {
        if (scale != 1f) postScale(scale, scale)
        if (rotation != 0) postRotate(rotation.toFloat())
    }
    if (matrix.isIdentity) return decoded
    return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
}
