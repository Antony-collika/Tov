package com.example.byokchat.ui

import com.example.byokchat.data.ConversationEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ConversationGroupsTest {
    private val zone = ZoneId.of("UTC")
    private fun at(day: Int, hour: Int = 12) = ZonedDateTime.of(2026, 11, day, hour, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val now = at(20, 9)

    private fun conv(id: String, updatedAt: Long, pinned: Boolean = false) = ConversationEntity(
        id = id, title = id, providerId = "", model = "", systemPrompt = "",
        createdAt = updatedAt, updatedAt = updatedAt, pinned = pinned,
    )

    @Test fun todayIncludesLaterHoursAndFuture() {
        assertEquals(DateBucket.TODAY, dateBucket(at(20, 0), now, zone))
        assertEquals(DateBucket.TODAY, dateBucket(at(21), now, zone))
    }

    @Test fun yesterdayIsPreviousCalendarDay() {
        assertEquals(DateBucket.YESTERDAY, dateBucket(at(19, 23), now, zone))
    }

    @Test fun previousSevenDays() {
        assertEquals(DateBucket.PREVIOUS_7_DAYS, dateBucket(at(18), now, zone))
        assertEquals(DateBucket.PREVIOUS_7_DAYS, dateBucket(at(13), now, zone))
    }

    @Test fun olderAfterSevenDays() {
        assertEquals(DateBucket.OLDER, dateBucket(at(12), now, zone))
    }

    @Test fun pinnedFirstThenDatesAndEmptySectionsAreSkipped() {
        val list = listOf(conv("a", at(20)), conv("b", at(1), pinned = true), conv("c", at(19)), conv("d", at(2)))
        val groups = groupConversations(list, now, zone)
        assertEquals(listOf(DateBucket.PINNED, DateBucket.TODAY, DateBucket.YESTERDAY, DateBucket.OLDER), groups.map { it.first })
        assertEquals(listOf("b"), groups[0].second.map { it.id })
    }

    @Test fun previewCollapsesWhitespace() {
        assertEquals("a b c", previewOf("  a\n\n b \t c  "))
    }
}
