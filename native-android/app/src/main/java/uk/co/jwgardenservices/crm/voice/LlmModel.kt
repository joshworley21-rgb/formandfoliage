package uk.co.jwgardenservices.crm.voice

/**
 * The two on-device models the assistant thinks with, both LiteRT-LM files from
 * the litert-community repositories on Hugging Face, downloaded directly.
 *
 * Each has one job. [Small] answers quickly, so it labels sentences and gives
 * short replies on a phone call, where a wait is felt. [Large] is the clever
 * one, used for typed chat, where a slower reply is fine and no listening model
 * is competing for memory.
 */
enum class LlmModel(
    val id: String,
    val label: String,
    val fileName: String,
    val url: String,
    val bytes: Long,
    /** Memory the system must report as available before this model is loaded. */
    val minFreeMb: Long,
    /** Qwen3-1.7B reasons by default; the answer would be buried in a long preamble. */
    val reasonsByDefault: Boolean,
    /** Slower models take a short prompt: time to the first word is mostly prefill. */
    val leanPrompts: Boolean,
) {
    Small(
        id = "qwen3-0.6b",
        label = "Qwen3 0.6B (fast, for calls)",
        fileName = "qwen3-0.6b-int4.litertlm",
        url = "https://huggingface.co/litert-community/Qwen3-0.6B-int4/resolve/main/" +
            "qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm",
        bytes = 347_251_840L,
        minFreeMb = 900L,
        reasonsByDefault = false,
        leanPrompts = false,
    ),
    Large(
        id = "qwen3-1.7b",
        label = "Qwen3 1.7B (clever, for chat)",
        fileName = "qwen3-1.7b-wi4b32.litertlm",
        url = "https://huggingface.co/litert-community/Qwen3-1.7B/resolve/main/" +
            "Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm",
        bytes = 977_184_032L,
        minFreeMb = 2_400L,
        reasonsByDefault = true,
        leanPrompts = true,
    ),
    ;

    companion object {
        fun fromId(id: String?): LlmModel = entries.firstOrNull { it.id == id } ?: Small

        /**
         * The models to try, best first: the one wanted when it is installed and
         * memory allows, then the small one, then any other, as safety nets.
         */
        fun candidates(wanted: LlmModel, installed: Set<LlmModel>, availableMb: Long): List<LlmModel> =
            (listOf(wanted, Small) + entries)
                .distinct()
                .filter { it in installed && availableMb >= it.minFreeMb }
    }
}
