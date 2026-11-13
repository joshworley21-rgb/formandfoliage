package uk.co.jwgardenservices.crm.voice

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Kokoro voice profiles exposed in the assistant settings. The speaker ids
 * are positions in `kokoro-multi-lang-v1_0`'s 53-voice bank.
 */
enum class KokoroVoice(val speakerId: Int, val label: String) {
    BmFable(25, "bm_fable — British male"),
    BfEmma(21, "bf_emma — British female"),
}

private val Context.assistantDataStore by preferencesDataStore(name = "assistant")
private val VOICE_KEY = stringPreferencesKey("kokoro_voice")
private val HIGH_QUALITY_KEY = booleanPreferencesKey("kokoro_high_quality")

object AssistantPreferences {

    fun voiceFlow(context: Context): Flow<KokoroVoice> = context.assistantDataStore.data.map { preferences ->
        KokoroVoice.entries.firstOrNull { it.name == preferences[VOICE_KEY] } ?: KokoroVoice.BmFable
    }

    suspend fun setVoice(context: Context, voice: KokoroVoice) {
        context.assistantDataStore.edit { preferences ->
            preferences[VOICE_KEY] = voice.name
        }
    }

    /** Optional fp32 model, downloaded separately; int8 is the default. */
    fun highQualityFlow(context: Context): Flow<Boolean> = context.assistantDataStore.data.map { preferences ->
        preferences[HIGH_QUALITY_KEY] ?: false
    }

    suspend fun setHighQuality(context: Context, enabled: Boolean) {
        context.assistantDataStore.edit { preferences ->
            preferences[HIGH_QUALITY_KEY] = enabled
        }
    }
}
