package com.example.byokchat.data

import java.util.UUID

class ConversationRepository(
    private val db: AppDatabase,
) {
    /** Tạo cuộc trò chuyện mới. Nhà cung cấp và mô hình dùng cho cuộc này là lựa chọn toàn cục trong Settings — không lưu riêng theo cuộc nữa. */
    suspend fun create(): String {
        // Never let empty drafts pile up: drop any conversation that has no messages first.
        db.conversations().deleteEmpty()
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        db.conversations().insert(
            ConversationEntity(
                id = id,
                title = DEFAULT_TITLE,
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
        // Ảnh có thể được dùng chung giữa cuộc gốc và cuộc nhánh (Branch) nên chỉ xóa file
        // nào không còn được cuộc nào khác dùng. Đọc danh sách của các cuộc khác một lần.
        val usedElsewhere = db.messages().imagePathsOutside(id).flatMap { decodeImages(it) }.toHashSet()
        db.messages().imagePathsOf(id).flatMap { decodeImages(it) }.distinct()
            .forEach { path -> if (path !in usedElsewhere) java.io.File(path).delete() }
        db.conversations().delete(id)
    }
}
