package uk.co.jwgardenservices.crm.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The workspace's own logo, kept beside the app's data.
 *
 * Invoices and their PDFs are rendered on this phone, so a local file is
 * enough for the document that is shared or emailed, and each signed-in
 * business has its own copy — the app itself is Form & Foliage, the paperwork
 * carries whoever is using it.
 *
 * Images are downscaled to 512 px on the long edge and stored as PNG, which
 * keeps transparency for marks on a coloured header and keeps the emailed PDF
 * small.
 */
object Branding {

    private const val FOLDER = "branding"
    private const val FILE = "logo.png"
    private const val MAX_EDGE = 512

    fun logoFile(context: Context): File =
        File(File(context.filesDir, FOLDER), FILE)

    fun hasLogo(context: Context): Boolean = logoFile(context).isFile

    /** Decoded once for previews; null when nothing has been uploaded. */
    fun load(context: Context): Bitmap? = runCatching {
        logoFile(context).takeIf { it.isFile }
            ?.let { BitmapFactory.decodeFile(it.absolutePath) }
    }.getOrNull()

    /** Copy the picked image in, downscaled. False when it could not be read. */
    suspend fun save(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching false

            var sample = 1
            while (bounds.outWidth / sample > MAX_EDGE * 2 ||
                bounds.outHeight / sample > MAX_EDGE * 2
            ) {
                sample *= 2
            }
            val decoded = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(
                    it,
                    null,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                )
            } ?: return@runCatching false

            val scale = MAX_EDGE.toFloat() / maxOf(decoded.width, decoded.height)
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * scale).toInt().coerceAtLeast(1),
                    (decoded.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
            } else {
                decoded
            }

            val target = logoFile(context)
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, "logo.tmp")
            temp.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            if (scaled !== decoded) decoded.recycle()
            scaled.recycle()
            true
        }.getOrDefault(false)
    }

    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        runCatching { logoFile(context).delete() }
    }
}
