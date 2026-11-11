package uk.co.jwgardenservices.crm.ui.common

import android.content.Context
import android.content.Intent

object ShareText {
    fun share(context: Context, text: String, title: String = "Share") {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, title))
    }
}
