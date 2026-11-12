package com.example.byokchat.ai

import com.example.byokchat.data.ProviderConfig
import com.example.byokchat.data.ProviderType

object ProviderFactory {
    fun create(
        cfg: ProviderConfig,
        log: RequestLog? = null,
    ): AiProvider = when (cfg.type) {
        ProviderType.CLAUDE -> ClaudeProvider(cfg, log)
        ProviderType.GEMINI -> GeminiProvider(cfg, log)
        // Đợt provider 12/11/2026: OpenAI chính hãng dùng Responses API;
        // DeepSeek + Custom ở lại Chat Completions (họ chỉ có endpoint đó).
        ProviderType.OPENAI -> ResponsesProvider(cfg, log)
        ProviderType.DEEPSEEK, ProviderType.CUSTOM -> OpenAiProvider(cfg, log)
    }
}
