package com.openminis.app.data.model

/**
 * [MINIS-OZEL] A one-tap prefill for adding a chat provider.
 *
 * Deliberately a PRESET, not a new [ProviderType]. A new enum case is a
 * database migration and a backup/restore compatibility risk (see the
 * ProviderType docs: an unknown name aborts the whole provider_config.json
 * decode), while a preset is just a filled-in form: same protocol, same
 * storage, same code path. This mirrors [VoiceProviderTemplate], which has
 * pre-filled vendor entries for the voice vendors on the same screen.
 *
 * The model list is NOT carried here. `OpenAIModelsApi.fetchModels()` already
 * accepts a custom base URL, so once [baseURL] is filled in, the ordinary
 * "Refresh models" action pulls whatever the vendor serves. Hard-coding model
 * ids would go stale the moment the vendor added or renamed one.
 */
data class ChatProviderPreset(
    val id: String,
    val displayName: String,
    /** Which wire protocol the vendor speaks — decides the provider class. */
    val providerType: ProviderType,
    /** Pre-filled into "Custom API Base URL". */
    val baseURL: String,
    /** Whether "/v1" is appended to [baseURL] when building requests. */
    val appendV1: Boolean,
    /** Row subtitle, as a string resource so it is localized like every other row. */
    val subtitleRes: Int,
) {
    companion object {
        /**
         * Evren — an OpenAI-compatible gateway serving 15 models
         * (glm-5.3, deepseek-v4.1-flash, qwen3.8-flash-next, gemma-4-31b,
         * auto, qwen3-vl-30b, plus OCR/ASR/embedding models).
         *
         * `appendV1 = true` because the host serves `/v1/models` and
         * `/v1/chat/completions` — the base is the host root, not `/v1`.
         */
        val EVREN = ChatProviderPreset(
            id = "evren",
            displayName = "Evren API",
            providerType = ProviderType.openAI,
            baseURL = "https://evren-llmapi.ssyz.org.tr",
            appendV1 = true,
            subtitleRes = com.openminis.app.R.string.add_provider_subtitle_evren,
        )

        /** Every preset the picker offers, in display order. */
        val all: List<ChatProviderPreset> = listOf(EVREN)

        fun byId(id: String?): ChatProviderPreset? =
            id?.let { key -> all.firstOrNull { it.id == key } }
    }
}
