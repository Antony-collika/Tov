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
        // Đợt 7: include_usage đọc từ chính provider (cfg.includeUsage),
        // không còn giá trị toàn cục truyền vào đây nữa.
        ProviderType.OPENAI, ProviderType.CUSTOM -> OpenAiProvider(cfg, log)
    }
}
