package uk.co.jwgardenservices.crm.voice

import android.content.Context

/**
 * The setup the app recommends: enough to work well on a phone like a Galaxy
 * S22+, without everything loaded at once. Live listening (Nemotron), the
 * small whisper.cpp as the second look, the fast model on calls, the clever
 * model for typed chat, and the standard voice. Parakeet, the ONNX Whisper and
 * Phonon 40m stay optional.
 */
object RecommendedSetup {
    enum class Item(val label: String, val role: String, val megabytes: Long) {
        Voice("Phonon 31m", "the voice", PhononModel.Phonon31.totalBytes / MB),
        Listening("Nemotron", "live listening", NemotronDownloader.TOTAL_BYTES / MB),
        SecondLook("whisper.cpp small.en", "second look at each sentence", WhisperCppDownloader.TOTAL_BYTES / MB),
        FastModel("Qwen3 0.6B", "understanding, and answers on calls", LlmModel.Small.bytes / MB),
        CleverModel("Qwen3 1.7B", "typed chat", LlmModel.Large.bytes / MB),
    }

    private const val MB = 1024L * 1024L

    fun installed(context: Context, item: Item): Boolean = when (item) {
        Item.Voice -> PhononDownloader.isInstalled(context, PhononModel.Phonon31)
        Item.Listening -> NemotronDownloader.isInstalled(context)
        Item.SecondLook -> WhisperCppDownloader.isInstalled(context)
        Item.FastModel -> LocalLlm.isInstalled(context, LlmModel.Small)
        Item.CleverModel -> LocalLlm.isInstalled(context, LlmModel.Large)
    }

    fun missing(context: Context): List<Item> = Item.entries.filterNot { installed(context, it) }

    /** Start every missing download. Phonon downloads one model at a time, so it goes first. */
    fun startMissing(context: Context) {
        missing(context).forEach { item ->
            when (item) {
                Item.Voice -> PhononDownloader.start(context, PhononModel.Phonon31)
                Item.Listening -> NemotronDownloader.start(context)
                Item.SecondLook -> WhisperCppDownloader.start(context)
                Item.FastModel -> LocalLlm.start(context, LlmModel.Small)
                Item.CleverModel -> LocalLlm.start(context, LlmModel.Large)
            }
        }
    }

    /** The settings that go with it. Safe before the downloads finish: each falls back until its files arrive. */
    suspend fun apply(context: Context) {
        AssistantPreferences.setPhonon(context, true)
        AssistantPreferences.setPhononModel(context, PhononModel.Phonon31)
        AssistantPreferences.setAsrEngine(context, AsrEngine.Nemotron)
        AssistantPreferences.setFinalPass(context, FinalPass.WhisperCpp)
        AssistantPreferences.setLocalThinking(context, true)
    }
}
