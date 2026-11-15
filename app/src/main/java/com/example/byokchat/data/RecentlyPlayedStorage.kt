package com.example.byokchat.data

import com.example.byokchat.media.PlayedSource
import com.example.byokchat.media.PlayedTrack
import com.example.byokchat.media.RecentlyPlayedStorage

/**
 * Cầu nối Room cho RecentlyPlayedStore: store giữ luật (mới nhất trước,
 * chống trùng liên tiếp, tối đa 50), đây chỉ đọc/ghi nguyên danh sách.
 * Hàng có `source` lạ (không có trong enum nữa) thì bỏ qua khi đọc.
 */
class RoomRecentlyPlayedStorage(private val dao: RecentlyPlayedDao) : RecentlyPlayedStorage {
    override suspend fun load(conversationId: String): List<PlayedTrack> =
        dao.getAll(conversationId).mapNotNull { row ->
            val source = runCatching { PlayedSource.valueOf(row.source) }.getOrNull()
                ?: return@mapNotNull null
            PlayedTrack(source, row.replayKey, row.name)
        }

    override suspend fun save(conversationId: String, tracks: List<PlayedTrack>) {
        dao.replaceAll(
            conversationId,
            tracks.mapIndexed { index, track ->
                RecentlyPlayedEntity(
                    conversationId = conversationId,
                    source = track.source.name,
                    replayKey = track.key,
                    name = track.name,
                    position = index,
                )
            },
        )
    }
}
