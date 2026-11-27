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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uk.co.jwgardenservices.crm.data.repository.ServiceLocator
import uk.co.jwgardenservices.crm.voice.AssistantService
import uk.co.jwgardenservices.crm.voice.KokoroEngine
import uk.co.jwgardenservices.crm.voice.SpeechEngine
import uk.co.jwgardenservices.crm.ui.AppRoot
import uk.co.jwgardenservices.crm.ui.theme.AppTheme

class MainActivity : FragmentActivity() {

    /** Delayed release of the assistant's models while the app is backgrounded. */
    private var releaseJob: Job? = null

    override fun onStart() {
        super.onStart()
        releaseJob?.cancel()
        AppLock.onForeground()
    }

    override fun onStop() {
        super.onStop()
        AppLock.onBackground()
        // A long background stay with the 190-660 MB recogniser resident makes
        // this the first process Android reclaims, which cold-starts the app
        // and shows the splash again. After half a minute away, and only when
        // no call is connected, drop the models so the process stays small.
        if (AssistantService.callActiveNow) return
        releaseJob = lifecycleScope.launch {
            delay(30_000)
            if (AssistantService.callActiveNow) return@launch
            runCatching { SpeechEngine.get(this@MainActivity).releaseIdle() }
            runCatching { KokoroEngine.get(this@MainActivity).releaseIdle() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleDeepLink(intent)
        startApp()
    }

    private fun startApp() {
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

}
