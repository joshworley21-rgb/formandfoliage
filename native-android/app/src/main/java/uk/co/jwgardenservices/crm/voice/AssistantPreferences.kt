package uk.co.jwgardenservices.crm.voice

import android.content.Context
import android.media.MediaRecorder
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
private val ONLINE_VOICE_KEY = booleanPreferencesKey("online_voice")
private val PIPER_KEY = stringPreferencesKey("piper_voice")
private val MIC_SOURCE_KEY = stringPreferencesKey("mic_source")
private val PHONON_KEY = booleanPreferencesKey("phonon_enabled")

/**
 * Where the microphone audio comes from.
 *
 * VOICE_COMMUNICATION gives the platform echo canceller, which is what allows
 * barge-in but can cost recognition accuracy on some phones. VOICE_RECOGNITION
 * is the accuracy-first choice and disables barge-in so the assistant cannot
 * hear itself.
 */
enum class MicSource(val androidSource: Int, val label: String) {
    Communication(
        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        "Barge-in (echo cancelling)",
    ),
    Recognition(
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        "Best recognition (no barge-in)",
    ),
}

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

    fun micSourceFlow(context: Context): Flow<MicSource> = context.assistantDataStore.data.map { preferences ->
        MicSource.entries.firstOrNull { it.name == preferences[MIC_SOURCE_KEY] }
            ?: MicSource.Communication
    }

    suspend fun setMicSource(context: Context, source: MicSource) {
        context.assistantDataStore.edit { preferences ->
            preferences[MIC_SOURCE_KEY] = source.name
        }
    }

    /**
     * Whether the downloaded Phonon 31m model should speak instead of
     * Kokoro/Piper. Off by default; enabling it is a deliberate quality and
     * speed choice because the model is downloaded separately.
     */
    fun phononFlow(context: Context): Flow<Boolean> = context.assistantDataStore.data.map { preferences ->
        preferences[PHONON_KEY] ?: false
    }

    suspend fun setPhonon(context: Context, enabled: Boolean) {
        context.assistantDataStore.edit { preferences ->
            preferences[PHONON_KEY] = enabled
        }
    }

    /**
     * Use the OpenAI voice through the voice-speak function when online.
     * Falls back to Kokoro when the request fails, so the assistant is never
     * silent.
     */
    fun onlineVoiceFlow(context: Context): Flow<Boolean> = context.assistantDataStore.data.map { preferences ->
        preferences[ONLINE_VOICE_KEY] ?: false
    }

    suspend fun setOnlineVoice(context: Context, enabled: Boolean) {
        context.assistantDataStore.edit { preferences ->
            preferences[ONLINE_VOICE_KEY] = enabled
        }
    }

    /**
     * A downloaded Piper voice, or null for Kokoro. Selecting one replaces
     * Kokoro entirely; the eight Kokoro speakers stay available for when no
     * Piper voice is selected.
     */
    fun piperVoiceFlow(context: Context): Flow<PiperVoice?> = context.assistantDataStore.data.map { preferences ->
        PiperVoice.entries.firstOrNull { it.name == preferences[PIPER_KEY] }
    }

    suspend fun setPiperVoice(context: Context, voice: PiperVoice?) {
        context.assistantDataStore.edit { preferences ->
            if (voice == null) {
                preferences.remove(PIPER_KEY)
            } else {
                preferences[PIPER_KEY] = voice.name
            }
        }
    }
}
