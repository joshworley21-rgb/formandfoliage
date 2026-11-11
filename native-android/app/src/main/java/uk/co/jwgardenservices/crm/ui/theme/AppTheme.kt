package uk.co.jwgardenservices.crm.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mode by ThemePreferences.flow(context)
        .collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)

    val dark = when (mode) {
        // "Follow system" now leans light: the bright, fresh look is the app's
        // identity, and dark mode is opt-in via the appearance setting.
        ThemeMode.SYSTEM -> false
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    CompositionLocalProvider(
        LocalThemeMode provides mode,
        LocalSetThemeMode provides { next ->
            scope.launch { ThemePreferences.set(context, next) }
        },
    ) {
        JwCrmTheme(darkTheme = dark, content = content)
    }
}
