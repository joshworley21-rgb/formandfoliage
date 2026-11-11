package uk.co.jwgardenservices.crm

import android.content.Intent
import android.os.Bundle
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.TextView
import android.widget.Toast
import uk.co.jwgardenservices.crm.core.AppLock
import uk.co.jwgardenservices.crm.core.CrashLog
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import uk.co.jwgardenservices.crm.data.repository.ServiceLocator
import uk.co.jwgardenservices.crm.ui.AppRoot
import uk.co.jwgardenservices.crm.ui.theme.AppTheme

class MainActivity : FragmentActivity() {
    override fun onStop() {
        super.onStop()
        AppLock.lock()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        showCrashReportIfPresent()
        handleDeepLink(intent)
        setContent {
            AppTheme {
                AppRoot()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    /**
     * Password-reset and confirmation links come back through the app's custom
     * scheme. Supabase exchanges the code for a session; the auth flow then
     * flips AppRoot over to the workspace on its own.
     */
    private fun handleDeepLink(intent: Intent?) {
        if (intent?.data == null) return
        lifecycleScope.launch {
            runCatching { ServiceLocator.authRepository.handleDeepLink(intent) }
        }
    }

    /**
     * Shown from the Activity, not Compose: if Compose itself is what crashes,
     * a Compose dialog would never get a frame to draw. This one always shows
     * on the launch after a crash.
     */
    private fun showCrashReportIfPresent() {
        val report = CrashLog.read(this) ?: return
        val text = TextView(this).apply {
            text = report.take(6000)
            textSize = 11f
            setPadding(48, 24, 48, 24)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Last crash report")
            .setView(android.widget.ScrollView(this).apply { addView(text) })
            .setPositiveButton("Copy") { _, _ ->
                getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                    ClipData.newPlainText("crash", report),
                )
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close") { dialog, _ ->
                CrashLog.clear(this)
                dialog.dismiss()
            }
            .show()
    }
}
