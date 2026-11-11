package com.example.byokchat.chat

import com.example.byokchat.ai.AiToolCall
import com.example.byokchat.data.ConversationEntity
import com.example.byokchat.data.MessageEntity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Test cho Đợt E phần C: parser history.md sidecar + history.meta.json. */
class HistoryMdParserTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val callsSer = ListSerializer(AiToolCall.serializer())

    // Nội dung mẫu đúng format sidecar (theo workspace/user/files/history.md + history.meta.json).
    private val sampleMd = """
        ---
        session: 591577e3
        title: "Hi"
        created: 2026-11-10T17:26:12Z
        next_turn: 2
        ---

        ## T1 user
        {"id":"1fae0291-5653-4fe9-97cf-5941559d3c09"}
        Hi

        ## T1 assistant
        {"id":"912f1bc1-7ac9-4780-99dd-22d15419dabd"}
        Hello! How can I help you today?
    """.trimIndent()

    private val sampleMeta = """
        {
            "version": 1,
            "session": "591577e3",
            "messages": {
                "1fae0291-5653-4fe9-97cf-5941559d3c09": {
                    "attachments": [],
                    "createdAt": 1794331564545,
                    "extra_content": null,
                    "tool_rounds": []
                },
                "912f1bc1-7ac9-4780-99dd-22d15419dabd": {
                    "attachments": [],
                    "createdAt": 1794331572878,
                    "extra_content": {
                        "google": {
                            "thought_signature": "abc"
                        }
                    },
                    "tool_rounds": []
                }
            }
        }
    """.trimIndent()

    @Test
    fun parsesSampleFiles() {
        val parsed = HistoryMdParser.parseMd(sampleMd)
        assertEquals("591577e3", parsed.session)
        assertEquals("Hi", parsed.title)
        assertEquals("2026-11-10T17:26:12Z", parsed.created)
        assertEquals(2, parsed.nextTurn)
        assertEquals(2, parsed.messages.size)
        assertEquals(ParsedHistoryMessage("1fae0291-5653-4fe9-97cf-5941559d3c09", 1, "user", "Hi"), parsed.messages[0])
        assertEquals(
            ParsedHistoryMessage("912f1bc1-7ac9-4780-99dd-22d15419dabd", 1, "assistant", "Hello! How can I help you today?"),
            parsed.messages[1],
        )
        val meta = HistoryMdParser.parseMeta(sampleMeta)
        assertEquals("591577e3", meta.session)
        assertEquals(2, meta.messages.size)
        assertEquals(1794331572878L, meta.messages.getValue("912f1bc1-7ac9-4780-99dd-22d15419dabd").createdAt)
    }

    @Test
    fun exportParseRoundTripKeepsTextAndToolRounds() {
        val conv = ConversationEntity("conv-abcdefgh", "Test chat", 1_700_000_000_000, 1_700_000_000_000)
        val callsJson = json.encodeToString(callsSer, listOf(AiToolCall("call-1", "current_time", "{}")))
        fun msg(id: String, turnId: String, seq: Long, role: String, text: String, extra: MessageEntity.() -> MessageEntity = { this }) =
            MessageEntity(id = id, conversationId = "conv-abcdefgh", turnId = turnId, seq = seq, role = role, text = text, createdAt = 1_700_000_000_000).extra()
        val rows = listOf(
            msg("m1", "t1", 1, "user", "What time is it?"),
            msg("m2", "t1", 2, "assistant", "", { copy(toolCallsJson = callsJson) }),
            msg("m3", "t1", 3, "tool", "2026-11-10 09:00", { copy(toolCallId = "call-1", toolUid = "time.now", toolName = "current_time") }),
            msg("m4", "t1", 4, "assistant", "It is 09:00."),
            msg("m5", "t2", 5, "user", "Thanks"),
            msg("m6", "t2", 6, "assistant", "", { copy(status = "error", error = "Network failed") }),
        )
        val parsed = HistoryMdParser.parseMd(HistoryExporter.renderHistory(conv, rows))
        assertEquals("conv-abc", parsed.session)
        assertEquals(3, parsed.nextTurn)
        assertEquals(listOf("m1", "m2", "m4", "m5", "m6"), parsed.messages.map { it.id })
        assertEquals("It is 09:00.", parsed.messages[2].text)
        assertEquals("Network failed", parsed.messages[4].error)
        val meta = HistoryMdParser.parseMeta(HistoryExporter.renderMeta(conv, rows))
        val rounds = meta.messages.getValue("m2").tool_rounds
        assertEquals(1, rounds.size)
        assertEquals("call-1", rounds[0].id)
        assertEquals("time.now", rounds[0].uid)
        assertEquals("2026-11-10 09:00", rounds[0].result)
    }

    @Test
    fun missingFrontmatterMissingIdAndRoundSectionsAreErrors() {
        assertThrows(HistoryParseException::class.java) {
            HistoryMdParser.parseMd("## T1 user\n{\"id\":\"m1\"}\nHi\n")
        }
        assertThrows(HistoryParseException::class.java) {
            HistoryMdParser.parseMd("---\nsession: abc\ntitle: \"T\"\ncreated: 2026-11-10T00:00:00Z\nnext_turn: 2\n---\n\n## T1 user\nHi\n")
        }
        assertThrows(HistoryParseException::class.java) {
            HistoryMdParser.parseMd("---\nsession: abc\ntitle: \"T\"\ncreated: 2026-11-10T00:00:00Z\nnext_turn: 2\n---\n\n## T1 user\n{\"id\":\"m1\"}\nHi\n\n## T1 round 1\ntext\n")
        }
        assertThrows(HistoryParseException::class.java) {
            HistoryMdParser.parseMd("---\nsession: abc\ntitle: \"T\"\ncreated: 2026-11-10T00:00:00Z\nnext_turn: 2\n---\n\n## T1 user\n{\"id\":\"m1\"}\nHi\n\n## T1 user\n{\"id\":\"m1\"}\nHi again\n")
        }
    }

    @Test
    fun nextTurnIsClampedUpAndUnquotedTitleIsAccepted() {
        val parsed = HistoryMdParser.parseMd(
            "---\nsession: abc\ntitle: Old export\ncreated: 2026-11-10 00:50\nnext_turn: 1\n---\n\n" +
                "## T3 user\n{\"id\":\"m1\"}\nHi\n\n## T3 assistant\n{\"id\":\"m2\"}\nHello.\n",
        )
        assertEquals("Old export", parsed.title)
        assertEquals(4, parsed.nextTurn)
    }

    @Test
    fun metaVersionMustBeOne() {
        assertThrows(HistoryParseException::class.java) {
            HistoryMdParser.parseMeta("{\"version\": 2, \"session\": \"abc\", \"messages\": {}}")
        }
        assertThrows(HistoryParseException::class.java) {
            HistoryMdParser.parseMeta("not json")
        }
    }
}
