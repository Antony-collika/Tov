package com.example.byokchat.data

import com.example.byokchat.ai.ToolDef
import com.example.byokchat.chat.SystemPart
import com.example.byokchat.chat.SystemSource
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Test cho Đợt C: mô hình thẻ, luật kiểm tra, bố cục mặc định. */
class RequestLayoutTest {

    private fun defs() = listOf(
        ToolDef("current_time", "Get the current date and time", buildJsonObject {}),
        ToolDef("calculator", "Evaluate a math expression", buildJsonObject {}),
    )

    @Test
    fun defaultLayoutReproducesTheOldRequest() {
        val layout = RequestLayout.layoutFromSettings("Be helpful")
        assertEquals(
            listOf(CardKind.SYSTEM, CardKind.TIME, CardKind.TOOLS, CardKind.HISTORY, CardKind.LAST_TURN),
            layout.cards.map { it.kind },
        )
        assertTrue(layout.cards.all { it.enabled })
        assertTrue(layout.isValid())
        assertEquals(emptyList<String>(), layout.validate())
        assertEquals("Be helpful", layout.systemCard()?.text)
        // Đợt 2: thẻ Time mặc định BẬT nên dòng giờ được chèn vào cuối system.
        val vars = mapOf("date" to "2026-11-10", "time" to "14:32")
        assertEquals("Be helpful\n\nCurrent time: 2026-11-10 14:32", layout.systemText(vars))
        assertEquals(
            listOf(
                SystemPart("Be helpful", SystemSource.USER_PROMPT),
                SystemPart("Current time: 2026-11-10 14:32", SystemSource.APP_ADDED),
            ),
            layout.systemParts(vars),
        )
    }

    @Test
    fun historyBelowLastTurnIsAnError() {
        val layout = RequestLayout.defaultLayout("x").copy(
            cards = listOf(
                RequestCard(RequestLayout.SYSTEM_ID, CardKind.SYSTEM, text = "x"),
                RequestCard(RequestLayout.LAST_TURN_ID, CardKind.LAST_TURN),
                RequestCard(RequestLayout.HISTORY_ID, CardKind.HISTORY),
            )
        )
        val errors = layout.validate()
        assertFalse(layout.isValid())
        assertTrue(errors.any { it.contains("History") && it.contains("above") })
    }

    @Test
    fun duplicateFixedCardsAreErrors() {
        val base = RequestLayout.defaultLayout("x").cards
        val dup = RequestLayout(base + RequestCard("history-2", CardKind.HISTORY))
        assertTrue(dup.validate().any { it.contains("History") && it.contains("exactly once") })
        val noHistory = RequestLayout(base.filter { it.kind != CardKind.HISTORY })
        assertTrue(noHistory.validate().any { it.contains("History") })
    }

    @Test
    fun fixedCardsCannotBeTurnedOff() {
        val layout = RequestLayout.defaultLayout("x").copy(
            cards = RequestLayout.defaultLayout("x").cards.map {
                if (it.kind == CardKind.HISTORY) it.copy(enabled = false) else it
            }
        )
        assertTrue(layout.validate().any { it.contains("History") && it.contains("turned off") })
    }

    @Test
    fun emptyFreeCardIsAnErrorNamingTheCard() {
        val layout = RequestLayout.defaultLayout("x").copy(
            cards = RequestLayout.defaultLayout("x").cards +
                RequestCard("free-1", CardKind.FREE, label = "My note", text = "  ")
        )
        assertTrue(layout.validate().any { it.contains("My note") })
    }

    @Test
    fun timeCardOffKeepsTimeTool() {
        // Đợt 2: tắt thẻ Time chỉ ngừng chèn dòng giờ; time.now vẫn được offer.
        val layout = RequestLayout.defaultLayout("x").copy(
            cards = RequestLayout.defaultLayout("x").cards.map {
                if (it.kind == CardKind.TIME) it.copy(enabled = false) else it
            }
        )
        assertEquals(defs(), layout.effectiveToolDefs(defs()))
    }

    @Test
    fun timeCardOnAppendsTimeLineAtEndOfSystem() {
        val layout = RequestLayout.defaultLayout("Be helpful")
        val vars = mapOf("date" to "2026-11-10", "time" to "14:32")
        val parts = layout.systemParts(vars)
        assertEquals(SystemPart("Current time: 2026-11-10 14:32", SystemSource.APP_ADDED), parts.last())
        assertEquals("Be helpful\n\nCurrent time: 2026-11-10 14:32", layout.systemText(vars))
    }

    @Test
    fun timeCardOffAddsNoTimeLine() {
        val layout = RequestLayout.defaultLayout("Be helpful").copy(
            cards = RequestLayout.defaultLayout("Be helpful").cards.map {
                if (it.kind == CardKind.TIME) it.copy(enabled = false) else it
            }
        )
        val vars = mapOf("date" to "2026-11-10", "time" to "14:32")
        assertEquals(
            listOf(SystemPart("Be helpful", SystemSource.USER_PROMPT)),
            layout.systemParts(vars),
        )
        assertEquals("Be helpful", layout.systemText(vars))
    }

    @Test
    fun noTimeCardAddsNoTimeLine() {
        val layout = RequestLayout(
            listOf(
                RequestCard(RequestLayout.SYSTEM_ID, CardKind.SYSTEM, text = "Be helpful"),
                RequestCard(RequestLayout.HISTORY_ID, CardKind.HISTORY),
                RequestCard(RequestLayout.LAST_TURN_ID, CardKind.LAST_TURN),
            )
        )
        assertEquals(
            listOf(SystemPart("Be helpful", SystemSource.USER_PROMPT)),
            layout.systemParts(mapOf("date" to "2026-11-10", "time" to "14:32")),
        )
    }

    @Test
    fun timeLineHasDateAndTimeOnly() {
        // Chỉ ngày + giờ: không giây, không weekday, không múi giờ/vị trí.
        assertEquals(
            "Current time: 2026-11-10 14:32",
            RequestLayout.currentTimeLine(mapOf("date" to "2026-11-10", "time" to "14:32")),
        )
    }

    @Test
    fun toolsCardOffRemovesAllTools() {
        val layout = RequestLayout.defaultLayout("x").copy(
            cards = RequestLayout.defaultLayout("x").cards.map {
                if (it.kind == CardKind.TOOLS) it.copy(enabled = false) else it
            }
        )
        assertEquals(emptyList<ToolDef>(), layout.effectiveToolDefs(defs()))
    }

    @Test
    fun noTimeOrToolsCardKeepsTools() {
        val layout = RequestLayout(
            listOf(
                RequestCard(RequestLayout.SYSTEM_ID, CardKind.SYSTEM, text = "x"),
                RequestCard(RequestLayout.HISTORY_ID, CardKind.HISTORY),
                RequestCard(RequestLayout.LAST_TURN_ID, CardKind.LAST_TURN),
            )
        )
        assertEquals(defs(), layout.effectiveToolDefs(defs()))
    }

    @Test
    fun variablesAreSubstitutedInCardText() {
        val layout = RequestLayout.defaultLayout("You are {model} from {provider}").copy(
            cards = RequestLayout.defaultLayout("You are {model} from {provider}").cards +
                RequestCard("free-1", CardKind.FREE, text = "Today is {date} {time}.", role = FreeRole.SYSTEM)
        )
        val vars = mapOf("date" to "2026-11-10", "time" to "14:32", "model" to "claude-x", "provider" to "Anthropic")
        assertEquals("You are claude-x from Anthropic", layout.systemParts(vars)[0].text)
        assertEquals("Today is 2026-11-10 14:32.", layout.systemParts(vars)[1].text)
        // Thẻ Time BẬT nên dòng giờ đứng cuối, sau thẻ Free vai system.
        assertEquals("Current time: 2026-11-10 14:32", layout.systemParts(vars)[2].text)
        assertEquals(SystemSource.APP_ADDED, layout.systemParts(vars)[2].source)
        assertEquals(
            "You are claude-x from Anthropic\n\nToday is 2026-11-10 14:32.\n\nCurrent time: 2026-11-10 14:32.",
            layout.systemText(vars),
        )
    }

    @Test
    fun resolveFilesReplacesTextAndReportsErrors() {
        val layout = RequestLayout.defaultLayout("typed").copy(
            cards = RequestLayout.defaultLayout("typed").cards.map {
                if (it.kind == CardKind.SYSTEM) it.copy(file = "Tov/prompts/system.md") else it
            } + RequestCard("free-1", CardKind.FREE, text = "old", file = "Tov/prompts/missing.md")
        )
        val (resolved, errors) = layout.resolveFiles { path ->
            if (path == "Tov/prompts/system.md") Result.success("from file")
            else Result.failure(Exception("Not found: $path"))
        }
        assertEquals("from file", resolved.systemCard()?.text)
        assertEquals("old", resolved.card("free-1")?.text)
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("missing.md"))
    }

    @Test
    fun fileMustEndWithMd() {
        val layout = RequestLayout.defaultLayout("x").copy(
            cards = RequestLayout.defaultLayout("x").cards.map {
                if (it.kind == CardKind.SYSTEM) it.copy(file = "Tov/prompts/system.txt") else it
            }
        )
        assertTrue(layout.validate().any { it.contains(".md") })
    }

    @Test
    fun freeSystemCardSitsInOrderWithSystem() {
        val layout = RequestLayout.defaultLayout("Base").copy(
            cards = listOf(
                RequestCard(RequestLayout.SYSTEM_ID, CardKind.SYSTEM, text = "Base"),
                RequestCard("free-1", CardKind.FREE, text = "Extra rules", role = FreeRole.SYSTEM),
                RequestCard(RequestLayout.HISTORY_ID, CardKind.HISTORY),
                RequestCard(RequestLayout.LAST_TURN_ID, CardKind.LAST_TURN),
            )
        )
        assertEquals(listOf("Base", "Extra rules"), layout.systemParts(emptyMap()).map { it.text })
    }
}
