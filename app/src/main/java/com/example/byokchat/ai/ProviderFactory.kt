package com.example.byokchat.ai

import com.example.byokchat.data.ProviderConfig
import com.example.byokchat.data.ProviderType

object ProviderFactory {
    fun create(cfg: ProviderConfig): AiProvider = when (cfg.type) {
        ProviderType.CLAUDE -> ClaudeProvider(cfg)
        ProviderType.GEMINI -> GeminiProvider(cfg)
        ProviderType.OPENAI, ProviderType.CUSTOM -> OpenAiProvider(cfg)
    }
}
