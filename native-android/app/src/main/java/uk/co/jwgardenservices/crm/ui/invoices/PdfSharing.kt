package uk.co.jwgardenservices.crm.ui.invoices

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Hand the generated PDF to whichever app the phone uses for files or email. */
object PdfSharing {
    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share invoice"))
    }

    private fun pdfIntent(context: Context, file: File, message: String): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, message)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Send the PDF straight into WhatsApp (or WhatsApp Business) with the
     * message prefilled — the user just picks the chat. No WhatsApp API, no
     * server work: it is the same share WhatsApp itself handles when a PDF is
     * shared from the Files app. Falls back to the generic chooser when
     * neither WhatsApp variant is installed.
     */
    fun shareViaWhatsApp(context: Context, file: File, message: String) {
        for (target in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            try {
                context.startActivity(pdfIntent(context, file, message).apply { setPackage(target) })
                return
            } catch (error: ActivityNotFoundException) {
                // Try the next WhatsApp variant, then the chooser.
            }
        }
        context.startActivity(
            Intent.createChooser(pdfIntent(context, file, message), "Share invoice via WhatsApp"),
        )
    }
}
