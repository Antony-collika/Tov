package com.example.byokchat.data

import java.util.UUID

class ConversationRepository(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
) {
    /** Tạo cuộc trò chuyện mới với nhà cung cấp / mô hình / prompt mặc định. */
    suspend fun create(): String {
        // Never let empty drafts pile up: drop any conversation that has no messages first.
        db.conversations().deleteEmpty()
        val p = settings.defaultProvider()
        val model = p?.defaultModel?.takeIf { it.isNotBlank() } ?: p?.models?.firstOrNull() ?: ""
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        db.conversations().insert(
            ConversationEntity(
                id = id,
                title = DEFAULT_TITLE,
                providerId = p?.id ?: "",
                model = model,
                systemPrompt = settings.defaultSystemPrompt.value,
                createdAt = now,
                updatedAt = now,
            )
        )
        return id
    }

    suspend fun rename(id: String, title: String) {
        if (title.isNotBlank()) db.conversations().rename(id, title.trim())
    }

    suspend fun delete(id: String) {
        db.messages().getAll(id).forEach { m ->
            // Xóa file ảnh đính kèm của cuộc trò chuyện (nếu không được cuộc khác dùng chung)
            m.images().forEach { path -> if (!isImageUsedElsewhere(path, id)) java.io.File(path).delete() }
        }
        db.conversations().delete(id)
    }

    // Ảnh có thể được dùng chung giữa cuộc gốc và cuộc nhánh (Branch) nên cần kiểm tra trước khi xóa.
    private suspend fun isImageUsedElsewhere(path: String, exceptConversationId: String): Boolean {
        val all = db.conversations().getAllIds()
        for (cid in all) {
            if (cid == exceptConversationId) continue
            if (db.messages().getAll(cid).any { path in it.images() }) return true
        }
        return false
    }
}
