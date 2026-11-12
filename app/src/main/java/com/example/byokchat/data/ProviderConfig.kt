package com.example.byokchat.data

import kotlinx.serialization.Serializable

@Serializable
enum class ProviderType(val label: String) {
    OPENAI("OpenAI"),
    GEMINI("Gemini (Google)"),
    CUSTOM("Custom (OpenAI-compatible)"),
}

@Serializable
data class ProviderConfig(
    val id: String,
    val type: ProviderType,
    val name: String,
    val apiKey: String = "",
    /** Để trống = dùng địa chỉ mặc định của nhà cung cấp. Bắt buộc với loại Tùy chỉnh. */
    val baseUrl: String = "",
    val models: List<String> = emptyList(),
    /** 0 = tự đoán theo tên mô hình. */
    val contextTokens: Int = 0,
    val maxOutputTokens: Int = 8192,
    /**
     * Custom: xin thêm số token thật trong luồng stream
     * (`stream_options.include_usage`). Mặc định bật; máy chủ tự dựng có
     * thể từ chối trường này thì tắt đi để gửi như cũ. Chỉ có ý nghĩa với
     * loại Custom — OpenAI Responses tự gửi usage trong event
     * response.completed, Gemini tự gửi usage nên không có trường
     * này. Có giá trị mặc định nên dữ liệu provider cũ vẫn đọc được (đợt 7
     * migrate giá trị toàn cục cũ sang đây, xem SettingsRepository).
     */
    val includeUsage: Boolean = true,
    /** Các mô hình người dùng ghim lên đầu danh sách chọn. Có giá trị mặc định nên dữ liệu cũ vẫn đọc được. */
    val pinnedModels: List<String> = emptyList(),
) {
    fun effectiveBaseUrl(): String {
        if (baseUrl.isNotBlank()) return baseUrl.trim()
        return when (type) {
            ProviderType.OPENAI -> "https://api.openai.com/v1"
            ProviderType.GEMINI -> "https://generativelanguage.googleapis.com"
            ProviderType.CUSTOM -> ""
        }
    }
}

object ContextLimits {
    /** Giới hạn ngữ cảnh (token) của một mô hình. Giá trị bạn nhập tay trong nhà cung cấp luôn được ưu tiên. */
    fun forModel(cfg: ProviderConfig, model: String): Int {
        if (cfg.contextTokens > 0) return cfg.contextTokens
        val m = model.lowercase()
        return when {
            "gemini" in m -> 1_000_000
            "claude" in m -> 200_000
            "deepseek" in m -> 64_000
            "gpt-4.1" in m -> 1_000_000
            "gpt-5" in m -> 400_000
            Regex("\\bo[134]").containsMatchIn(m) -> 200_000
            "gpt-4" in m || "gpt-3.5" in m -> 128_000
            cfg.type == ProviderType.OPENAI -> 128_000
            else -> 32_000
        }
    }
}
