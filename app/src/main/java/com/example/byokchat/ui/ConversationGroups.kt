package com.example.byokchat.ui

import com.example.byokchat.data.ConversationEntity
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Section of the conversation list in the drawer. */
enum class DateBucket(val label: String) {
    PINNED("Pinned"),
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    PREVIOUS_7_DAYS("Previous 7 days"),
    OLDER("Older"),
}

/** Which date section a conversation last updated at [updatedAt] belongs to (calendar days, not 24-hour blocks). */
fun dateBucket(updatedAt: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): DateBucket {
    val day = Instant.ofEpochMilli(updatedAt).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when (ChronoUnit.DAYS.between(day, today)) {
        in Long.MIN_VALUE..0L -> DateBucket.TODAY
        1L -> DateBucket.YESTERDAY
        in 2L..7L -> DateBucket.PREVIOUS_7_DAYS
        else -> DateBucket.OLDER
    }
}

/**
 * Splits the list into sections: pinned first, then by date. The order inside a section is kept as given
 * (the list is already sorted newest first), and empty sections are left out.
 */
fun groupConversations(
    list: List<ConversationEntity>,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): List<Pair<DateBucket, List<ConversationEntity>>> {
    val byBucket = list.groupBy { if (it.pinned) DateBucket.PINNED else dateBucket(it.updatedAt, now, zone) }
    return DateBucket.entries.mapNotNull { b -> byBucket[b]?.takeIf { it.isNotEmpty() }?.let { b to it } }
}

/** One-line preview of a message for the drawer: line breaks and repeated spaces become single spaces. */
fun previewOf(text: String): String = text.replace(Regex("\\s+"), " ").trim()
