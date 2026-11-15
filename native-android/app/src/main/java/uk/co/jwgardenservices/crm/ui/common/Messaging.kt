package uk.co.jwgardenservices.crm.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * Pre-filled text / WhatsApp messages.
 *
 * Both open the user's own app with the number and draft ready; nothing is
 * sent automatically, so the owner still reads it and taps send. That keeps
 * the CRM free of a messaging API and of any chance of sending the wrong
 * client the wrong message.
 */
object Messaging {

    fun sms(context: Context, phone: String, message: String): Boolean {
        val number = normalize(phone) ?: return false
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).apply {
            putExtra("sms_body", message)
        }
        return launch(context, intent, "No messaging app found")
    }

    fun whatsapp(context: Context, phone: String, message: String): Boolean {
        val number = whatsappNumber(phone) ?: return false
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://wa.me/$number?text=${Uri.encode(message)}"),
        )
        return launch(context, intent, "WhatsApp is not available on this phone")
    }

    /**
     * wa.me needs an international number. Most numbers here are stored the
     * UK way ("07700 900123"), so a leading 0 becomes 44; already-international
     * numbers are left alone.
     */
    private fun whatsappNumber(phone: String): String? {
        val digits = normalize(phone)?.filter { it.isDigit() } ?: return null
        return when {
            digits.startsWith("44") -> digits
            digits.startsWith("0") && digits.length >= 10 -> "44" + digits.drop(1)
            else -> digits
        }
    }

    /** Keep digits and a leading +; null when there is no usable number. */
    fun normalize(phone: String): String? {
        val cleaned = phone.filter { it.isDigit() || it == '+' }
        return cleaned.takeIf { candidate -> candidate.count { it.isDigit() } >= 6 }
    }

    private fun launch(context: Context, intent: Intent, missing: String): Boolean = try {
        context.startActivity(intent)
        true
    } catch (error: ActivityNotFoundException) {
        Toast.makeText(context, missing, Toast.LENGTH_SHORT).show()
        false
    }
}
