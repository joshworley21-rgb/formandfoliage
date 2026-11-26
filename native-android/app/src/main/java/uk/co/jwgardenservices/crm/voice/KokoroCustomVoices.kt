package uk.co.jwgardenservices.crm.voice

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * Custom Kokoro speaker vectors dropped into app storage.
 *
 * A custom file is one 510 × 256 float32 style vector (522,240 bytes). The
 * loader writes it into an unused speaker slot (52) so the model metadata
 * and expected bank length stay untouched. Full 54-speaker banks are not
 * accepted here; blend and per-speaker selection remain deterministic.
 */
object KokoroCustomVoices {
    const val SLOT = 52
    const val SINGLE_SPEC_BYTES = KokoroVoiceBlend.STYLE_COUNT *
        KokoroVoiceBlend.STYLE_DIM * 4

    fun dir(context: Context): File = File(context.filesDir, "kokoro-custom").apply {
        if (!isDirectory) mkdirs()
    }

    fun list(context: Context): List<File> = dir(context)
        .listFiles { file -> file.isFile && file.extension.equals("bin", true) }
        .orEmpty()
        .filter { it.length() == SINGLE_SPEC_BYTES.toLong() }
        .sortedBy { it.name.lowercase() }

    fun resolve(context: Context, name: String?): File? {
        val clean = name?.trim().orEmpty()
        if (clean.isEmpty()) return null
        val file = File(dir(context), clean)
        return file.takeIf { it.isFile && it.length() == SINGLE_SPEC_BYTES.toLong() }
    }

    /**
     * Copy an imported .bin into app storage. Returns the stored name, or
     * null when the file is not a single-speaker Kokoro vector.
     */
    fun import(context: Context, uri: Uri): String? = runCatching {
        val display = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
        val fallback = "custom-${System.currentTimeMillis()}.bin"
        val raw = display?.substringAfterLast('/')?.substringAfterLast('\\') ?: fallback
        val name = raw
            .replace(Regex("[^A-Za-z0-9._-]"), "-")
            .takeIf { it.endsWith(".bin", true) }
            ?: "$raw.bin".replace(Regex("[^A-Za-z0-9._-]"), "-")
        val target = File(dir(context), name)
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
        } ?: return@runCatching null
        if (target.length() != SINGLE_SPEC_BYTES.toLong()) {
            target.delete()
            return@runCatching null
        }
        target.name
    }.getOrNull()

    fun delete(context: Context, name: String): Boolean {
        val file = File(dir(context), name)
        return file.isFile && file.delete()
    }
}
