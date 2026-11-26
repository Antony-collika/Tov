package com.example.byokchat.ui

import com.example.byokchat.chat.Mention
import com.example.byokchat.chat.encodeSubRunStrings
import com.example.byokchat.data.SubRunEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Session conversation (17/11/2026, mockup v2): tapping a sub's avatar
 * in ANY turn opens the same screen — the whole Main↔sub exchange of
 * the session, in time order. The brief and the report live inside the
 * conversation as messages. The messages the sub sent to the user are
 * NOT in this timeline: they live on the main chat screen.
 *
 * Inbox model (20/11/2026): every conversation screen is that
 * character's inbox — each bubble carries its real speaker, so a
 * peer's brief (Bob→Danny) shows under Bob, not Main. Main's inbox
 * holds only exchanges involving Main, with the recipient on Main's
 * briefs ("Main tới Bob").
 */
class SubConversationTest {

    private fun run(
        id: Long, turnNo: Int, startedAt: Long, finishedAt: Long,
        agent: String = "monica", agentName: String = "Monica",
        startedBy: String = "main", startedByName: String = "Main",
        brief: String = "Find cheerful morning music.",
        title: String = "Find music",
        outbox: List<String> = emptyList(),
        report: String = "Sub-agent Monica finished: done.\nTask: Find cheerful morning music.\nRounds: 2/10, estimated tokens: ~100/32000.\n\nFound it: Mùa xuân đầu tiên.",
    ) = SubRunEntity(
        id = id, conversationId = "conv-1", turnNo = turnNo, agent = agent, agentName = agentName,
        startedBy = startedBy, startedByName = startedByName,
        title = title, brief = brief, status = "done", rounds = 2, tokens = 100,
        toolCallsJson = "[]", messagesToMainJson = encodeSubRunStrings(outbox),
        fullReport = report, reasoning = "", startedAt = startedAt, finishedAt = finishedAt,
    )

    @Test
    fun turnsOneAndTenShareOneTimelineInOrder() {
        // The avatar in turn 1 and the avatar in turn 10 open the same
        // screen: every run of the session is in it, in time order.
        val items = buildSubConversation(
            runs = listOf(
                run(id = 1, turnNo = 1, startedAt = 1000, finishedAt = 2000),
                run(id = 2, turnNo = 10, startedAt = 5000, finishedAt = 6000, brief = "Find a calm song."),
            ),
        )
        // The sub's messages to the user ("Đang phát … cho cậu đây")
        // are NOT in this timeline: they live on the main chat screen.
        assertEquals(
            listOf(
                "Find cheerful morning music." to "main",
                "Found it: Mùa xuân đầu tiên." to "monica",
                "Find a calm song." to "main",
                "Found it: Mùa xuân đầu tiên." to "monica",
            ),
            items.map { it.text to it.senderId },
        )
    }

    @Test
    fun reportHeaderAndOutboxSectionAreNotRepeated() {
        val report = "Sub-agent Monica finished: done.\n" +
            "Task: Find cheerful morning music.\n" +
            "Tools called: youtube_search\n" +
            "Rounds: 2/10, estimated tokens: ~100/32000.\n" +
            "\n" +
            "Messages from Monica to Main:\n" +
            "- Rõ ạ, em tìm ngay đây.\n" +
            "- Báo cáo: đã tìm xong.\n" +
            "\n" +
            "Found it: Mùa xuân đầu tiên."
        val items = buildSubConversation(
            runs = listOf(run(id = 1, turnNo = 1, startedAt = 1000, finishedAt = 2000, outbox = listOf("Rõ ạ, em tìm ngay đây.", "Báo cáo: đã tìm xong."), report = report)),
        )
        // Outbox lines appear once, as their own bubbles; the report
        // bubble carries only the report body, no header, no outbox list.
        assertEquals(
            listOf(
                "Find cheerful morning music." to "main",
                "Rõ ạ, em tìm ngay đây." to "monica",
                "Báo cáo: đã tìm xong." to "monica",
                "Found it: Mùa xuân đầu tiên." to "monica",
            ),
            items.map { it.text to it.senderId },
        )
        assertTrue(items.none { it.text.contains("finished:") || it.text.contains("Rounds:") })
    }

    @Test
    fun multilineBriefDoesNotLeakIntoTheReport() {
        val report = "Sub-agent Monica finished: done.\n" +
            "Task: Find music.\n" +
            "Line two of the brief.\n" +
            "Rounds: 2/10, estimated tokens: ~100/32000.\n" +
            "\n" +
            "Found it."
        assertEquals("Found it.", stripReportHeader(report, "Monica"))
    }

    @Test
    fun runsBeforeTheTableExistedShowNothing() {
        // No sub_runs record and no message source anymore: the screen
        // is empty for runs from before the table existed (18/11/2026).
        val items = buildSubConversation(runs = emptyList())
        assertEquals(emptyList<Pair<String, String>>(), items.map { it.text to it.senderId })
    }

    @Test
    fun blankReportAddsNoBubble() {
        val items = buildSubConversation(
            runs = listOf(
                run(
                    id = 1, turnNo = 1, startedAt = 1000, finishedAt = 2000,
                    report = "Sub-agent Monica finished: failed.\nTask: Find music.\nRounds: 1/10, estimated tokens: ~10/32000.\n\n(no report text)",
                ),
            ),
        )
        assertEquals(listOf("Find cheerful morning music." to "main"), items.map { it.text to it.senderId })
    }

    @Test
    fun blankBriefFallsBackToTitle() {
        val items = buildSubConversation(
            runs = listOf(run(id = 1, turnNo = 1, startedAt = 1000, finishedAt = 2000, brief = "", title = "Find music")),
        )
        assertEquals("Find music", items.first().text)
        assertEquals("main", items.first().senderId)
    }

    @Test
    fun peerBriefShowsItsRealSenderNotMain() {
        // The 19/11/2026 device bug: in Danny's screen Bob's delegate
        // brief showed as Main's message, and in Bob's screen Danny's
        // wake showed as Main's. A brief now carries its starter.
        val items = buildSubConversation(
            runs = listOf(
                run(
                    id = 1, turnNo = 1, startedAt = 1000, finishedAt = 2000,
                    agent = "danny", agentName = "Danny",
                    startedBy = "bob", startedByName = "Bob",
                    brief = "Make a table from the news.",
                    report = "Sub-agent Danny finished: done.\nTask: Make a table.\nRounds: 1/10, estimated tokens: ~10/32000.\n\nTable done.",
                ),
                run(
                    id = 2, turnNo = 1, startedAt = 3000, finishedAt = 4000,
                    agent = "bob", agentName = "Bob",
                    startedBy = "danny", startedByName = "Danny",
                    brief = "Message from Danny:\nTable done, here it is.",
                    report = "Sub-agent Bob finished: done.\nTask: Find news.\nRounds: 3/10, estimated tokens: ~30/32000.\n\nAll verified.",
                ),
            ),
        )
        assertEquals(
            listOf(
                "Make a table from the news." to "bob",
                "Table done." to "danny",
                "Message from Danny:\nTable done, here it is." to "danny",
                "All verified." to "bob",
            ),
            items.map { it.text to it.senderId },
        )
        assertEquals(listOf("Bob", "Danny", "Danny", "Bob"), items.map { it.senderName })
    }

    @Test
    fun mainInboxShowsOnlyMainExchangesWithRecipient() {
        // Main's inbox: Main's briefs (with who they are to) plus each
        // sub's messages to Main and reports. A peer's brief is neither
        // Main's words nor addressed to Main, so it stays out.
        val items = buildMainConversation(
            runs = listOf(
                run(
                    id = 1, turnNo = 1, startedAt = 1000, finishedAt = 2000,
                    agent = "bob", agentName = "Bob",
                    brief = "Find sports news.",
                    outbox = listOf("Found two sources."),
                    report = "Sub-agent Bob finished: done.\nTask: Find sports news.\nRounds: 2/10, estimated tokens: ~20/32000.\n\nTwo sources verified.",
                ),
                run(
                    id = 2, turnNo = 1, startedAt = 3000, finishedAt = 4000,
                    agent = "danny", agentName = "Danny",
                    startedBy = "bob", startedByName = "Bob",
                    brief = "Make a table from the news.",
                    report = "Sub-agent Danny finished: done.\nTask: Make a table.\nRounds: 1/10, estimated tokens: ~10/32000.\n\nTable done.",
                ),
                run(
                    id = 3, turnNo = 1, startedAt = 5000, finishedAt = 6000,
                    agent = "bob", agentName = "Bob",
                    startedBy = "danny", startedByName = "Danny",
                    brief = "Message from Danny:\nTable done, here it is.",
                    outbox = listOf("All checked, reporting back."),
                    report = "Sub-agent Bob finished: done.\nTask: Find news.\nRounds: 3/10, estimated tokens: ~30/32000.\n\nAll verified.",
                ),
            ),
        )
        // No peer brief appears: neither Bob's brief to Danny nor
        // Danny's wake message to Bob.
        assertTrue(items.none { it.text == "Make a table from the news." || it.text.startsWith("Message from Danny:") })
        // Main's brief carries its recipient; the subs' messages and
        // reports carry their own names — including Danny's report from
        // the run Bob started, which is Danny reporting to Main.
        assertEquals(
            listOf(
                Triple("Find sports news.", "main", "Bob"),
                Triple("Found two sources.", "bob", null),
                Triple("Two sources verified.", "bob", null),
                Triple("Table done.", "danny", null),
                Triple("All checked, reporting back.", "bob", null),
                Triple("All verified.", "bob", null),
            ),
            items.map { Triple(it.text, it.senderId, it.toName) },
        )
    }

    @Test
    fun runWithoutOriginReadsAsMain() {
        // Old rows (and old backups) have no origin: the default is
        // Main, which is right — before peer mode every run was
        // Main-delegated.
        val row = SubRunEntity(
            id = 1, conversationId = "conv-1", turnNo = 1, agent = "monica", agentName = "Monica",
            title = "Find music", brief = "Find cheerful morning music.", status = "done", rounds = 2, tokens = 100,
            toolCallsJson = "[]", messagesToMainJson = "[]",
            fullReport = "", reasoning = "", startedAt = 1000, finishedAt = 2000,
        )
        assertEquals("main", row.startedBy)
        assertEquals("Main", row.startedByName)
    }

    @Test
    fun mainInboxExcludesUserStartedRun() {
        // @mention (GĐ5): a run the user started is a direct exchange
        // between the user and that sub — it never appears in Main's
        // inbox, brief, outbox and report alike.
        val items = buildMainConversation(
            runs = listOf(
                run(
                    id = 1, turnNo = 1, startedAt = 1000, finishedAt = 2000,
                    agent = "bob", agentName = "Bob",
                    startedBy = Mention.STARTED_BY, startedByName = Mention.STARTED_BY_NAME,
                    brief = "find news", title = "find news",
                    outbox = listOf("Short note."),
                    report = "Sub-agent Bob finished: done.\nTask: find news.\nRounds: 1/10, estimated tokens: ~10/32000.\n\nDetailed report.",
                ),
            ),
        )
        assertEquals(emptyList<SubConversationItem>(), items)
    }

    @Test
    fun mainInboxMixedRunsShowOnlyMainStarted() {
        val items = buildMainConversation(
            runs = listOf(
                run(
                    id = 1, turnNo = 1, startedAt = 1000, finishedAt = 2000,
                    agent = "bob", agentName = "Bob",
                    startedBy = Mention.STARTED_BY, startedByName = Mention.STARTED_BY_NAME,
                    brief = "find news", title = "find news",
                    report = "Sub-agent Bob finished: done.\nTask: find news.\nRounds: 1/10, estimated tokens: ~10/32000.\n\nDetailed report.",
                ),
                run(
                    id = 2, turnNo = 1, startedAt = 3000, finishedAt = 4000,
                    agent = "bob", agentName = "Bob",
                    brief = "Find sports news.",
                    outbox = listOf("Found two sources."),
                    report = "Sub-agent Bob finished: done.\nTask: Find sports news.\nRounds: 2/10, estimated tokens: ~20/32000.\n\nTwo sources verified.",
                ),
            ),
        )
        assertTrue(items.none { it.senderName == Mention.STARTED_BY_NAME })
        assertEquals(
            listOf(
                Triple("Find sports news.", "main", "Bob"),
                Triple("Found two sources.", "bob", null),
                Triple("Two sources verified.", "bob", null),
            ),
            items.map { Triple(it.text, it.senderId, it.toName) },
        )
    }

    @Test
    fun subScreenShowsUserStartedRunUnderYou() {
        // The same run shows in the sub's own screen, the brief under "You".
        val items = buildSubConversation(
            runs = listOf(
                run(
                    id = 1, turnNo = 1, startedAt = 1000, finishedAt = 2000,
                    agent = "bob", agentName = "Bob",
                    startedBy = Mention.STARTED_BY, startedByName = Mention.STARTED_BY_NAME,
                    brief = "find news", title = "find news",
                    outbox = listOf("Short note."),
                    report = "Sub-agent Bob finished: done.\nTask: find news.\nRounds: 1/10, estimated tokens: ~10/32000.\n\nDetailed report.",
                ),
            ),
        )
        assertEquals(
            listOf(
                Triple("find news", "user", "You"),
                Triple("Short note.", "bob", "Bob"),
                Triple("Detailed report.", "bob", "Bob"),
            ),
            items.map { Triple(it.text, it.senderId, it.senderName) },
        )
    }

}
