package uk.co.jwgardenservices.crm

import android.content.Intent
import android.os.Bundle
import uk.co.jwgardenservices.crm.core.AppLock
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
}
