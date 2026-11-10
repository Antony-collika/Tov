package com.example.byokchat.ai

import com.example.byokchat.data.ProviderConfig
import com.example.byokchat.data.ProviderType

object ProviderFactory {
    fun create(
        cfg: ProviderConfig,
        log: RequestLog? = null,
        openAiIncludeUsage: Boolean = true,
    ): AiProvider = when (cfg.type) {
        ProviderType.CLAUDE -> ClaudeProvider(cfg, log)
        ProviderType.GEMINI -> GeminiProvider(cfg, log)
        ProviderType.OPENAI, ProviderType.CUSTOM -> OpenAiProvider(cfg, log, openAiIncludeUsage)
    }
}
