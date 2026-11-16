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
         * NVIDIA NIM — OpenAI-compatible inference for the hosted catalogue
         * (Llama, Nemotron, DeepSeek, Mistral, Qwen and more). Key prefix `nvapi-`.
         */
        val NVIDIA = ChatProviderPreset(
            id = "nvidia",
            displayName = "NVIDIA NIM",
            providerType = ProviderType.openAI,
            baseURL = "https://integrate.api.nvidia.com",
            appendV1 = true,
            subtitleRes = com.openminis.app.R.string.add_provider_subtitle_nvidia,
        )

        /**
         * Mistral — OpenAI-compatible. Base already carries `/v1`, so the
         * suffix is not appended again (Mistral serves /v1/chat/completions).
         */
        val MISTRAL = ChatProviderPreset(
            id = "mistral",
            displayName = "Mistral AI",
            providerType = ProviderType.openAI,
            baseURL = "https://api.mistral.ai/v1",
            appendV1 = false,
            subtitleRes = com.openminis.app.R.string.add_provider_subtitle_mistral,
        )

        /**
         * DeepSeek — OpenAI-compatible. The base is the host root; both
         * `/v1/models` and `/v1/chat/completions` answer under it (verified:
         * unauthenticated GET /v1/models returns a 401 JSON envelope, i.e. the
         * route exists and only the key is missing). Chat models:
         * `deepseek-chat`, `deepseek-reasoner`.
         *
         * Why a preset rather than "just use OpenAI": DeepSeek is cheaper than
         * the first-party APIs for the same capability, which is exactly the
         * kind of provider a user should be able to try without assembling a
         * base URL from a docs page.
         */
        val DEEPSEEK = ChatProviderPreset(
            id = "deepseek",
            displayName = "DeepSeek",
            providerType = ProviderType.openAI,
            baseURL = "https://api.deepseek.com",
            appendV1 = true,
            subtitleRes = com.openminis.app.R.string.add_provider_subtitle_deepseek,
        )

        /**
         * Together AI — OpenAI-compatible at `/v1`; the bare host serves
         * nothing, so the suffix is appended. Large hosted catalogue (Llama,
         * Qwen, DeepSeek, Mistral, Mixtral) plus image models the chat path
         * ignores.
         */
        val TOGETHER = ChatProviderPreset(
            id = "together",
            displayName = "Together AI",
            providerType = ProviderType.openAI,
            baseURL = "https://api.together.xyz",
            appendV1 = true,
            subtitleRes = com.openminis.app.R.string.add_provider_subtitle_together,
        )

        /**
         * Fireworks AI — OpenAI-compatible, but the path is
         * `/inference/v1`, not `/v1`: the base carries `inference` and the
         * suffix adds `/v1`.
         */
        val FIREWORKS = ChatProviderPreset(
            id = "fireworks",
            displayName = "Fireworks AI",
            providerType = ProviderType.openAI,
            baseURL = "https://api.fireworks.ai/inference",
            appendV1 = true,
            subtitleRes = com.openminis.app.R.string.add_provider_subtitle_fireworks,
        )

        /**
         * Hugging Face Inference — the ROUTER endpoint, not the old
         * `api-inference.huggingface.co` host. The router speaks the OpenAI
         * protocol and `/v1` is part of the base, so nothing is appended.
         *
         * The legacy host is not offered on purpose: it answers per-model with
         * its own payload shape and has no `/v1/models`, so a preset pointing
         * there would fail at the first refresh with a parse error.
         */
        val HUGGING_FACE = ChatProviderPreset(
            id = "huggingface",
            displayName = "Hugging Face",
            providerType = ProviderType.openAI,
            baseURL = "https://router.huggingface.co/v1",
            appendV1 = false,
            subtitleRes = com.openminis.app.R.string.add_provider_subtitle_huggingface,
        )

        /** Every preset the picker offers, in display order. */
        val all: List<ChatProviderPreset> = listOf(
            NVIDIA, MISTRAL, DEEPSEEK, TOGETHER, FIREWORKS, HUGGING_FACE,
        )

        fun byId(id: String?): ChatProviderPreset? =
            id?.let { key -> all.firstOrNull { it.id == key } }
    }
}
