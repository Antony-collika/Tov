package com.example.byokchat.ai

import com.example.byokchat.data.ProviderConfig
import com.example.byokchat.data.ProviderType

object ProviderFactory {
    fun create(
        cfg: ProviderConfig,
        log: RequestLog? = null,
    ): AiProvider = when (cfg.type) {
        ProviderType.GEMINI -> GeminiProvider(cfg, log)
        // Đợt provider 12/11/2026: OpenAI chính hãng dùng Responses API;
        // Custom (kể cả DeepSeek, thêm qua Custom với địa chỉ
        // https://api.deepseek.com) ở lại Chat Completions.
        ProviderType.OPENAI -> ResponsesProvider(cfg, log)
        ProviderType.CUSTOM -> OpenAiProvider(cfg, log)
    }
}
