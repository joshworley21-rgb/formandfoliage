package uk.co.jwgardenservices.crm.voice

/**
 * The on-device models the assistant can think with, all LiteRT-LM files from
 * the litert-community repositories on Hugging Face. The Qwen3 files download
 * directly; Gemma needs a login there, so it is imported from a file instead.
 */
enum class LlmModel(
    val id: String,
    val label: String,
    val fileName: String,
    /** Null when the file needs a login to download and has to be imported instead. */
    val url: String?,
    val bytes: Long,
    /** Memory the system must report as available before this model is loaded. */
    val minFreeMb: Long,
    /** Qwen3-1.7B reasons by default; the answer would be buried in a long preamble. */
    val reasonsByDefault: Boolean,
    /** Slower models take a short prompt: time to the first word is mostly prefill. */
    val leanPrompts: Boolean,
    /** A model that only picks app actions (function calling); it never answers or chats. */
    val routesTools: Boolean = false,
) {
    Small(
        id = "qwen3-0.6b",
        label = "Qwen3 0.6B",
        fileName = "qwen3-0.6b-int4.litertlm",
        url = "https://huggingface.co/litert-community/Qwen3-0.6B-int4/resolve/main/" +
            "qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm",
        bytes = 347_251_840L,
        minFreeMb = 900L,
        reasonsByDefault = false,
        leanPrompts = false,
    ),
    Gemma(
        id = "gemma3-1b",
        label = "Gemma 3 1B",
        fileName = "gemma3-1b-it-int4.litertlm",
        url = null,
        bytes = 584_417_280L,
        minFreeMb = 1_500L,
        reasonsByDefault = false,
        leanPrompts = true,
    ),
    Large(
        id = "qwen3-1.7b",
        label = "Qwen3 1.7B",
        fileName = "qwen3-1.7b-wi4b32.litertlm",
        url = "https://huggingface.co/litert-community/Qwen3-1.7B/resolve/main/" +
            "Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm",
        bytes = 977_184_032L,
        minFreeMb = 3_000L,
        reasonsByDefault = true,
        leanPrompts = true,
    ),
    Router(
        id = "functiongemma-mobile-actions",
        label = "FunctionGemma 270M",
        fileName = "functiongemma-mobile-actions-q8.litertlm",
        url = "https://huggingface.co/litert-community/functiongemma-mobile-actions_q8_ekv1024.litertlm/" +
            "resolve/main/mobile-actions_q8_ekv1024.litertlm",
        bytes = 284_426_240L,
        minFreeMb = 700L,
        reasonsByDefault = false,
        leanPrompts = true,
        routesTools = true,
    ),
    ;

    val downloadable: Boolean get() = url != null

    companion object {
        fun fromId(id: String?): LlmModel = entries.firstOrNull { it.id == id } ?: Small

        /**
         * The models to try, best first: the one the owner chose when it is
         * installed and memory allows, then the small one as a safety net.
         */
        fun candidates(preferred: LlmModel, installed: Set<LlmModel>, availableMb: Long): List<LlmModel> =
            listOf(preferred, Small)
                .distinct()
                .filter { !it.routesTools && it in installed && availableMb >= it.minFreeMb }
    }
}
