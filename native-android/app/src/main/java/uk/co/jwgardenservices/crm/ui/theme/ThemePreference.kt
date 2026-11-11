package uk.co.jwgardenservices.crm.ui.theme

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode(val label: String) {
    SYSTEM("Follow system"),
    LIGHT("Light"),
    DARK("Dark"),
}

private val Context.themeDataStore by preferencesDataStore(name = "appearance")
private val THEME_KEY = stringPreferencesKey("theme_mode")

object ThemePreferences {
    fun flow(context: Context): Flow<ThemeMode> = context.themeDataStore.data.map { preferences ->
        ThemeMode.entries.firstOrNull { it.name == preferences[THEME_KEY] } ?: ThemeMode.LIGHT
    }

    suspend fun set(context: Context, mode: ThemeMode) {
        context.themeDataStore.edit { preferences ->
            preferences[THEME_KEY] = mode.name
        }
    }
}

val LocalThemeMode = staticCompositionLocalOf { ThemeMode.LIGHT }
val LocalSetThemeMode = staticCompositionLocalOf<(ThemeMode) -> Unit> { {} }
