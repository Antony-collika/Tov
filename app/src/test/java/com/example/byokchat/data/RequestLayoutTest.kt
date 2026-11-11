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
            listOf(CardKind.METADATA, CardKind.SYSTEM, CardKind.TIME, CardKind.TOOLS, CardKind.HISTORY, CardKind.LAST_TURN),
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

    // ---------- Đợt E phần A: thẻ Tools nhiều file ----------

    private val origins = mapOf(
        "txt.write" to "Write text to a file.",
        "txt.read" to "Read a bounded section of a UTF-8 text file.",
        "time.now" to "Get the current date and time.",
    )

    private fun toolsLayout(
        vararg files: String,
        enabled: Boolean = true,
        toolsSource: ToolsSource = ToolsSource.FILE,
        text: String = "",
    ): RequestLayout =
        RequestLayout.defaultLayout("x").copy(
            cards = RequestLayout.defaultLayout("x").cards.map {
                if (it.kind == CardKind.TOOLS) {
                    it.copy(enabled = enabled, files = files.toList(), toolsSource = toolsSource, text = text)
                } else it
            }
        )

    private fun toolsFile(vararg blocks: String): String = blocks.joinToString("\n\n")

    private fun block(uid: String, name: String, desc: String): String =
        "## $uid\nuid: $uid\norigin_description: ${origins.getValue(uid)}\nname: $name\ndescription: $desc"

    @Test
    fun toolsFilesMergeInFileAndBlockOrder() {
        val files = mapOf(
            "a.md" to toolsFile(block("txt.write", "write_file", "Write text to a file."), block("time.now", "current_time", "Get the current date and time.")),
            "b.md" to toolsFile(block("txt.read", "read_file", "Read a bounded section of a UTF-8 text file.")),
        )
        val res = toolsLayout("a.md", "b.md").resolveToolFiles({ Result.success(files.getValue(it)) }, origins)
        assertTrue(res.errors.isEmpty())
        assertEquals(listOf("txt.write", "time.now", "txt.read"), res.exposed?.map { it.uid })
    }

    @Test
    fun identicalToolInTwoFilesDedupesButDifferentDescriptionIsAnError() {
        val same = toolsFile(block("txt.write", "write_file", "Write text to a file."))
        val files = mapOf("a.md" to same, "b.md" to same)
        val ok = toolsLayout("a.md", "b.md").resolveToolFiles({ Result.success(files.getValue(it)) }, origins)
        assertTrue(ok.errors.isEmpty())
        assertEquals(listOf("txt.write"), ok.exposed?.map { it.uid })

        val different = toolsFile(block("txt.write", "write_file", "Write something else."))
        val bad = toolsLayout("a.md", "b.md").resolveToolFiles(
            { Result.success(if (it == "a.md") same else different) }, origins,
        )
        assertTrue(bad.errors.any { it.contains("txt.write") && it.contains("a.md") && it.contains("b.md") })
    }

    @Test
    fun noToolsCardIsLegacyDisabledCardIsZeroToolsEmptyFileIsZeroTools() {
        val noCard = RequestLayout(listOf(RequestCard(RequestLayout.SYSTEM_ID, CardKind.SYSTEM, text = "x")))
        val legacy = noCard.resolveToolFiles({ Result.success("") }, origins)
        assertEquals(null, legacy.exposed)
        assertTrue(legacy.errors.isEmpty())

        val disabled = toolsLayout("a.md", enabled = false).resolveToolFiles({ Result.success("## x") }, origins)
        assertEquals(emptyList<com.example.byokchat.tools.ParsedTool>(), disabled.exposed)

        val empty = toolsLayout("a.md").resolveToolFiles({ Result.success("> only notes\n") }, origins)
        assertTrue(empty.errors.isEmpty())
        assertEquals(emptyList<com.example.byokchat.tools.ParsedTool>(), empty.exposed)
    }

    @Test
    fun fileSourceWithNoFilesIsAnError() {
        // Sửa luật nguồn 11/11/2026: nguồn File mà chưa chọn file nào thì
        // chặn gửi + báo rõ, không còn đường "legacy" offer tất cả tool.
        val res = toolsLayout().resolveToolFiles({ Result.success("") }, origins)
        assertTrue(res.errors.any { it.contains("No file selected for the Tools card") })
        assertEquals(emptyList<com.example.byokchat.tools.ParsedTool>(), res.exposed)
    }

    @Test
    fun textSourceBlankIsZeroTools() {
        val blank = toolsLayout(toolsSource = ToolsSource.TEXT, text = "").resolveToolFiles({ Result.success("") }, origins)
        assertTrue(blank.errors.isEmpty())
        assertEquals(emptyList<com.example.byokchat.tools.ParsedTool>(), blank.exposed)

        val notesOnly = toolsLayout(toolsSource = ToolsSource.TEXT, text = "> just a note\n").resolveToolFiles({ Result.success("") }, origins)
        assertTrue(notesOnly.errors.isEmpty())
        assertEquals(emptyList<com.example.byokchat.tools.ParsedTool>(), notesOnly.exposed)
    }

    @Test
    fun textSourceParsesContentAndReportsSyntaxErrors() {
        val ok = toolsLayout(
            toolsSource = ToolsSource.TEXT,
            text = block("time.now", "current_time", "Get the current date and time."),
        ).resolveToolFiles({ Result.success("") }, origins)
        assertTrue(ok.errors.isEmpty())
        assertEquals(listOf("time.now"), ok.exposed?.map { it.uid })

        val bad = toolsLayout(
            toolsSource = ToolsSource.TEXT,
            text = "## nope\nuid: nope\nname: nope\ndescription: No such tool.\n",
        ).resolveToolFiles({ Result.success("") }, origins)
        assertTrue(bad.errors.any { it.contains("Tools card text") && it.contains("nope") })
        assertEquals(emptyList<com.example.byokchat.tools.ParsedTool>(), bad.exposed)
    }

    @Test
    fun unreadableOrUnparsableToolsFileIsAnError() {
        val missing = toolsLayout("gone.md").resolveToolFiles({ Result.failure(RuntimeException("Not found")) }, origins)
        assertTrue(missing.errors.any { it.contains("gone.md") })
        val bad = toolsLayout("a.md").resolveToolFiles(
            { Result.success("## nope\nuid: nope\nname: nope\ndescription: No such tool.\n") }, origins,
        )
        assertTrue(bad.errors.any { it.contains("a.md") && it.contains("nope") })
    }

    @Test
    fun legacyToolsFileFieldStillWorksAsOneFile() {
        val card = RequestCard(RequestLayout.TOOLS_ID, CardKind.TOOLS, file = "old.md")
        assertEquals(listOf("old.md"), card.toolFiles())
        val layout = RequestLayout(listOf(card))
        val res = layout.resolveToolFiles(
            { Result.success(block("time.now", "current_time", "Get the current date and time.")) }, origins,
        )
        assertTrue(res.errors.isEmpty())
        assertEquals(listOf("time.now"), res.exposed?.map { it.uid })
    }

    @Test
    fun toolsFileMustEndWithMd() {
        val layout = toolsLayout("tools.txt")
        assertTrue(layout.validate().any { it.contains("Tools") && it.contains(".md") })
    }

    @Test
    fun metadataCardMustBeExactlyOnceFirstAndAlwaysOn() {
        // Đợt 6: Metadata cố định như FPilot — đúng 1, đầu tiên, không tắt.
        val base = RequestLayout.defaultLayout("x").cards
        val noMetadata = RequestLayout(base.filter { it.kind != CardKind.METADATA })
        assertTrue(noMetadata.validate().any { it.contains("Metadata") && it.contains("exactly once") })
        val dup = RequestLayout(base + RequestCard("metadata-2", CardKind.METADATA))
        assertTrue(dup.validate().any { it.contains("Metadata") && it.contains("exactly once") })
        val notFirst = RequestLayout(base.filter { it.kind != CardKind.METADATA } + RequestCard(RequestLayout.METADATA_ID, CardKind.METADATA))
        assertTrue(notFirst.validate().any { it.contains("Metadata") && it.contains("first") })
        val off = RequestLayout(base.map { if (it.kind == CardKind.METADATA) it.copy(enabled = false) else it })
        assertTrue(off.validate().any { it.contains("Metadata") && it.contains("turned off") })
        assertTrue(RequestLayout.defaultLayout("x").metadataCard()?.isFixed() == true)
    }

    // ---------- Đợt 8 (ui-polish): tên thẻ System dùng label ----------

    @Test
    fun systemDisplayNameUsesLabelWhenSet() {
        // Mặc định vẫn là "System" (bố cục cũ có label rỗng không đổi tên).
        assertEquals("System", RequestLayout.defaultLayout("x").systemCard()?.displayName())
        val named = RequestCard(RequestLayout.SYSTEM_ID, CardKind.SYSTEM, label = "My rules", text = "x")
        assertEquals("My rules", named.displayName())
        val blank = RequestCard(RequestLayout.SYSTEM_ID, CardKind.SYSTEM, label = "   ", text = "x")
        assertEquals("System", blank.displayName())
        // Thẻ Free giữ nguyên luật cũ.
        assertEquals("Free card", RequestCard("f", CardKind.FREE).displayName())
        assertEquals("Note", RequestCard("f", CardKind.FREE, label = "Note").displayName())
    }
}
