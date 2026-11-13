package uk.co.jwgardenservices.crm.voice

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Kokoro voice profiles exposed in the assistant settings.
 *
 * The speaker ids are positions in `kokoro-multi-lang-v1_0`'s 53-voice bank.
 * Ids 20–27 are the eight British English voices; the rest of the bank is
 * American, Chinese, Japanese and so on. The bank is ordered
 * af_… → am_… → bf_… → bm_…, which is why alice is 20 and fable is 25.
 */
enum class KokoroVoice(val speakerId: Int, val label: String) {
    BfAlice(20, "bf_alice — British female"),
    BfEmma(21, "bf_emma — British female"),
    BfIsabella(22, "bf_isabella — British female"),
    BfLily(23, "bf_lily — British female"),
    BmDaniel(24, "bm_daniel — British male"),
    BmFable(25, "bm_fable — British male"),
    BmGeorge(26, "bm_george — British male"),
    BmLewis(27, "bm_lewis — British male"),
}

private val Context.assistantDataStore by preferencesDataStore(name = "assistant")
private val VOICE_KEY = stringPreferencesKey("kokoro_voice")
private val HIGH_QUALITY_KEY = booleanPreferencesKey("kokoro_high_quality")
private val SPEED_KEY = floatPreferencesKey("kokoro_speed")

object AssistantPreferences {

    const val MIN_SPEED = 0.8f
    const val MAX_SPEED = 1.3f
    const val DEFAULT_SPEED = 1.0f

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

    /** Kokoro's speaking rate: below 1.0 is slower, above 1.0 is brisker. */
    fun speedFlow(context: Context): Flow<Float> = context.assistantDataStore.data.map { preferences ->
        (preferences[SPEED_KEY] ?: DEFAULT_SPEED).coerceIn(MIN_SPEED, MAX_SPEED)
    }

    suspend fun setSpeed(context: Context, speed: Float) {
        context.assistantDataStore.edit { preferences ->
            preferences[SPEED_KEY] = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        }
    }
}
