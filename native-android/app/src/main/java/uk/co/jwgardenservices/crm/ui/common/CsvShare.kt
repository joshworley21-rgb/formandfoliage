package uk.co.jwgardenservices.crm.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.time.LocalDate

/** Writes [content] to the app cache and offers it to Drive, email, WhatsApp and so on. */
fun shareCsv(context: Context, baseName: String, content: String) {
    try {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "$baseName-${LocalDate.now()}.csv")
        // Byte-order mark so Excel opens the file as UTF-8.
        file.writeText("\uFEFF$content", Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Export $baseName"))
    } catch (error: ActivityNotFoundException) {
        Toast.makeText(context, "Nothing on this phone can receive the file", Toast.LENGTH_SHORT).show()
    } catch (error: Exception) {
        Toast.makeText(context, "Could not create the export", Toast.LENGTH_SHORT).show()
    }
}
