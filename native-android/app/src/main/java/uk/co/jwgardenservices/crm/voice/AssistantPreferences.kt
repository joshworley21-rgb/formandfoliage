package uk.co.jwgardenservices.crm.voice

import android.content.Context
import android.media.MediaRecorder
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Kokoro voice profiles exposed in the assistant settings.
 *
 * The speaker ids are positions in `kokoro-multi-lang-v1_0`'s 54-slot bank.
 * Ids 20–27 are the eight British English voices; the last slot is replaced
 * at load time with the cached Fable + George cab blend.
 */
/** How the assistant sounds, beyond the voice itself. */
enum class PersonaTone(val label: String) {
    Warm("Warm and chatty"),
    Brisk("Brisk and efficient"),
}

enum class PersonaHumour(val label: String) {
    Low("Straight"),
    Normal("A little dry"),
    High("Playful"),
}

enum class PersonaLength(val label: String) {
    Short("Keep it short"),
    Normal("Normal"),
}

enum class KokoroVoice(val speakerId: Int, val label: String) {
    BfAlice(20, "bf_alice — British female"),
    BfEmma(21, "bf_emma — British female"),
    BfIsabella(22, "bf_isabella — British female"),
    BfLily(23, "bf_lily — British female"),
    BmDaniel(24, "bm_daniel — British male"),
    BmFable(25, "bm_fable — British male"),
    BmGeorge(26, "bm_george — British male"),
    BmLewis(27, "bm_lewis — British male"),

    /** 0.70 × bm_fable + 0.30 × bm_george, written into slot 53 at load. */
    CabBlend(53, "Fable + George (Cab Blend)"),
    ;

    companion object {
        val DEFAULT = CabBlend
    }
}

private val Context.assistantDataStore by preferencesDataStore(name = "assistant")
private val VOICE_KEY = stringPreferencesKey("kokoro_voice")
private val HIGH_QUALITY_KEY = booleanPreferencesKey("kokoro_high_quality")
private val SPEED_KEY = floatPreferencesKey("kokoro_speed")
private val ONLINE_VOICE_KEY = booleanPreferencesKey("online_voice")
private val PIPER_KEY = stringPreferencesKey("piper_voice")
private val MIC_SOURCE_KEY = stringPreferencesKey("mic_source")
private val PHONON_KEY = booleanPreferencesKey("phonon_enabled")
private val PICKUP_KEY = booleanPreferencesKey("pickup_greeting")
private val USED_KEY = booleanPreferencesKey("assistant_used")
private val LOCAL_THINKING_KEY = booleanPreferencesKey("local_thinking")
private val PRONUNCIATION_KEY = stringPreferencesKey("pronunciation")
private val DRIVING_KEY = booleanPreferencesKey("driving_mode")
private val AUTO_DRIVING_KEY = booleanPreferencesKey("auto_driving_mode")
private val PERSONA_TONE_KEY = stringPreferencesKey("persona_tone")
private val PERSONA_HUMOUR_KEY = stringPreferencesKey("persona_humour")
private val PERSONA_LENGTH_KEY = stringPreferencesKey("persona_length")
private val PHONON_VOICE_KEY = stringPreferencesKey("phonon_voice")
private val ASR_ENGINE_KEY = stringPreferencesKey("asr_engine")
private val FOLLOW_UP_KEY = booleanPreferencesKey("follow_up")
private val OWNER_NAME_KEY = stringPreferencesKey("owner_name")
private val CUSTOM_VOICE_KEY = stringPreferencesKey("kokoro_custom_voice")

/** The on-device speech recogniser. Streaming is the default; Moonshine is a test. */
enum class AsrEngine(val label: String) {
    Moonshine("Moonshine v2 — offline, English (en)"),
    Nemotron("Nemotron — streaming, live words, English (en)"),
}

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
        "Barge-in (may be silent on some phones)",
    ),
    Recognition(
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        "Best recognition (no barge-in)",
    ),
    Mic(
        MediaRecorder.AudioSource.MIC,
        "Raw microphone (no noise processing)",
    ),
    Phone(
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        "Phone mic (ignore Bluetooth mic)",
    ),
}

object AssistantPreferences {

    const val MIN_SPEED = 0.8f
    const val MAX_SPEED = 1.3f
    const val DEFAULT_SPEED = 1.0f

    fun voiceFlow(context: Context): Flow<KokoroVoice> = context.assistantDataStore.data.map { preferences ->
        KokoroVoice.entries.firstOrNull { it.name == preferences[VOICE_KEY] } ?: KokoroVoice.DEFAULT
    }

    suspend fun setVoice(context: Context, voice: KokoroVoice) {
        context.assistantDataStore.edit { preferences ->
            preferences[VOICE_KEY] = voice.name
        }
    }

    /** A single-speaker .bin from app storage, or blank for built-ins. */
    fun customKokoroVoiceFlow(context: Context): Flow<String> =
        context.assistantDataStore.data.map { it[CUSTOM_VOICE_KEY].orEmpty() }

    suspend fun setCustomKokoroVoice(context: Context, name: String?) {
        context.assistantDataStore.edit { it[CUSTOM_VOICE_KEY] = name?.trim().orEmpty() }
    }

    /** Optional fp32 model, downloaded separately; int8 is the default. */
    /** The fp32 Kokoro download was removed; int8 is the only fallback. */
    fun highQualityFlow(context: Context): Flow<Boolean> = kotlinx.coroutines.flow.flowOf(false)

    suspend fun setHighQuality(context: Context, enabled: Boolean) = Unit

    /** Kokoro's speaking rate: below 1.0 is slower, above 1.0 is brisker. */
    fun speedFlow(context: Context): Flow<Float> = context.assistantDataStore.data.map { preferences ->
        (preferences[SPEED_KEY] ?: DEFAULT_SPEED).coerceIn(MIN_SPEED, MAX_SPEED)
    }

    suspend fun setSpeed(context: Context, speed: Float) {
        context.assistantDataStore.edit { preferences ->
            preferences[SPEED_KEY] = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        }
    }

    fun asrEngineFlow(context: Context): Flow<AsrEngine> = context.assistantDataStore.data.map { preferences ->
        AsrEngine.entries.firstOrNull { it.name == preferences[ASR_ENGINE_KEY] } ?: AsrEngine.Moonshine
    }

    suspend fun setAsrEngine(context: Context, engine: AsrEngine) {
        context.assistantDataStore.edit { preferences ->
            preferences[ASR_ENGINE_KEY] = engine.name
        }
    }

    fun micSourceFlow(context: Context): Flow<MicSource> = context.assistantDataStore.data.map { preferences ->
        MicSource.entries.firstOrNull { it.name == preferences[MIC_SOURCE_KEY] }
            // Communication is silent on this Samsung (raw peak 0.00); Recognition
            // is the reliable default. Communication stays available for barge-in.
            ?: MicSource.Recognition
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
     * The selected Phonon voice name. Catalogue voices use their enum name;
     * imported custom voices use the file name without `.safetensors`.
     */
    fun phononVoiceFlow(context: Context): Flow<String> = context.assistantDataStore.data.map { preferences ->
        preferences[PHONON_VOICE_KEY] ?: PhononVoice.Freya.name
    }

    suspend fun setPhononVoice(context: Context, voice: PhononVoice) =
        setPhononVoice(context, voice.name)

    suspend fun setPhononVoice(context: Context, name: String) {
        context.assistantDataStore.edit { preferences ->
            preferences[PHONON_VOICE_KEY] = name
        }
    }

    /**
     * Use the OpenAI voice through the voice-speak function when online.
     * Falls back to Kokoro when the request fails, so the assistant is never
     * silent.
     */
    /** Online voices were removed: Phonon 31m is the only offered voice. */
    fun onlineVoiceFlow(context: Context): Flow<Boolean> = kotlinx.coroutines.flow.flowOf(false)

    suspend fun setOnlineVoice(context: Context, enabled: Boolean) = Unit

    /**
     * A downloaded local voice (Piper VITS or Supertonic), or null for the
     * default Phonon voice. Selecting one replaces Phonon entirely; clearing
     * it goes back to Phonon.
     */
    fun piperVoiceFlow(context: Context): Flow<PiperVoice?> =
        kotlinx.coroutines.flow.flowOf(null)

    suspend fun setPiperVoice(context: Context, voice: PiperVoice?) = Unit

    /**
     * Open a fresh call with a short schedule briefing ("you've got Simon's
     * estate first at nine…"). On by default: it is what makes the tab feel
     * like ringing someone rather than opening a form, and it names the active
     * client so the next sentence needs no name. It is skipped when the caller
     * has already started speaking.
     */
    /** Free on-device answers; the model loads only when a question needs it. */
    fun localThinkingFlow(context: Context): Flow<Boolean> =
        context.assistantDataStore.data.map { it[LOCAL_THINKING_KEY] ?: true }

    suspend fun setLocalThinking(context: Context, enabled: Boolean) {
        context.assistantDataStore.edit { it[LOCAL_THINKING_KEY] = enabled }
        if (!enabled) LocalLlm.release()
    }

    /** User pronunciation overrides: lower-case word -> how it should sound. */
    /** Concise, slower, no follow-up nudge: for use in the van. */
    fun drivingModeFlow(context: Context): Flow<Boolean> =
        context.assistantDataStore.data.map { it[DRIVING_KEY] ?: false }

    suspend fun setDrivingMode(context: Context, enabled: Boolean) {
        context.assistantDataStore.edit { it[DRIVING_KEY] = enabled }
    }

    /** The first name used in greetings and asides; blank means "work it out". */
    fun ownerNameFlow(context: Context): Flow<String> =
        context.assistantDataStore.data.map { it[OWNER_NAME_KEY].orEmpty() }

    suspend fun setOwnerName(context: Context, name: String) {
        context.assistantDataStore.edit { it[OWNER_NAME_KEY] = name.trim().take(40) }
    }

    fun personaToneFlow(context: Context): Flow<PersonaTone> =
        context.assistantDataStore.data.map {
            PersonaTone.entries.firstOrNull { tone -> tone.name == it[PERSONA_TONE_KEY] }
                ?: PersonaTone.Warm
        }

    suspend fun setPersonaTone(context: Context, tone: PersonaTone) {
        context.assistantDataStore.edit { it[PERSONA_TONE_KEY] = tone.name }
    }

    fun personaHumourFlow(context: Context): Flow<PersonaHumour> =
        context.assistantDataStore.data.map {
            PersonaHumour.entries.firstOrNull { humour -> humour.name == it[PERSONA_HUMOUR_KEY] }
                ?: PersonaHumour.Normal
        }

    suspend fun setPersonaHumour(context: Context, humour: PersonaHumour) {
        context.assistantDataStore.edit { it[PERSONA_HUMOUR_KEY] = humour.name }
    }

    fun personaLengthFlow(context: Context): Flow<PersonaLength> =
        context.assistantDataStore.data.map {
            PersonaLength.entries.firstOrNull { length -> length.name == it[PERSONA_LENGTH_KEY] }
                ?: PersonaLength.Normal
        }

    suspend fun setPersonaLength(context: Context, length: PersonaLength) {
        context.assistantDataStore.edit { it[PERSONA_LENGTH_KEY] = length.name }
    }

    /** Switch driving mode on automatically while a Bluetooth car kit is connected. */
    fun autoDrivingModeFlow(context: Context): Flow<Boolean> =
        context.assistantDataStore.data.map { it[AUTO_DRIVING_KEY] ?: true }

    suspend fun setAutoDrivingMode(context: Context, enabled: Boolean) {
        context.assistantDataStore.edit { it[AUTO_DRIVING_KEY] = enabled }
    }

    fun pronunciationsFlow(context: Context): Flow<Map<String, String>> =
        context.assistantDataStore.data.map { preferences ->
            preferences[PRONUNCIATION_KEY]
                ?.lineSequence()
                ?.mapNotNull { line ->
                    val word = line.substringBefore('=', "").trim()
                    val sound = line.substringAfter('=', "").trim()
                    if (word.isBlank() || sound.isBlank()) null else word.lowercase() to sound
                }
                ?.toMap()
                ?: emptyMap()
        }

    suspend fun addPronunciation(context: Context, word: String, soundsLike: String) {
        val cleanWord = word.trim().lowercase()
        val cleanSound = soundsLike.trim()
        if (cleanWord.isBlank() || cleanSound.isBlank()) return
        context.assistantDataStore.edit { preferences ->
            val current = preferences[PRONUNCIATION_KEY]
                ?.lineSequence()
                ?.filterNot { it.substringBefore('=').trim().equals(cleanWord, true) }
                ?.toMutableList()
                ?: mutableListOf()
            current.add("$cleanWord=$cleanSound")
            preferences[PRONUNCIATION_KEY] = current.joinToString("\n")
        }
    }

    suspend fun removePronunciation(context: Context, word: String) {
        context.assistantDataStore.edit { preferences ->
            val current = preferences[PRONUNCIATION_KEY]
                ?.lineSequence()
                ?.filterNot { it.substringBefore('=').trim().equals(word, true) }
                ?.toList()
                ?: return@edit
            preferences[PRONUNCIATION_KEY] = current.joinToString("\n")
        }
    }

    fun pickupFlow(context: Context): Flow<Boolean> = kotlinx.coroutines.flow.flowOf(true)

    suspend fun setPickup(context: Context, enabled: Boolean) {
        context.assistantDataStore.edit { preferences ->
            preferences[PICKUP_KEY] = enabled
        }
    }

    /**
     * Ask "anything else?" after a finished answer, so the call keeps its
     * conversational rhythm instead of going quiet until the next command.
     */
    fun followUpFlow(context: Context): Flow<Boolean> = kotlinx.coroutines.flow.flowOf(true)

    suspend fun setFollowUp(context: Context, enabled: Boolean) {
        context.assistantDataStore.edit { preferences ->
            preferences[FOLLOW_UP_KEY] = enabled
        }
    }

    /**
     * Remember that the assistant has been used, so the next app launch can
     * load the recogniser in the background. The 190 MB model then does not
     * have to load in front of the first command.
     */
    suspend fun markUsed(context: Context) {
        context.assistantDataStore.edit { preferences -> preferences[USED_KEY] = true }
    }

    suspend fun usedBefore(context: Context): Boolean =
        context.assistantDataStore.data.first()[USED_KEY] ?: false
}
