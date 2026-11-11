package com.example.byokchat.chat

import com.example.byokchat.ai.AiToolCall
import com.example.byokchat.data.MessageEntity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Test cho Đợt 4: trích file model đã dùng từ tool rounds (thẻ Files). */
class ModelFilesTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val callsSer = ListSerializer(AiToolCall.serializer())

    private fun row(
        id: String,
        turnId: String,
        seq: Long,
        role: String,
        toolCallsJson: String? = null,
        toolCallId: String? = null,
        toolIsError: Boolean = false,
    ) = MessageEntity(
        id = id, conversationId = "c1", turnId = turnId, seq = seq, role = role,
        text = "", toolCallsJson = toolCallsJson, toolCallId = toolCallId,
        toolIsError = toolIsError, createdAt = seq,
    )

    private fun calls(vararg c: AiToolCall) = json.encodeToString(callsSer, c.toList())

    @Test
    fun onlySuccessfulCallsWithPathsAreListed() {
        val rows = listOf(
            // Đọc file thành công -> có.
            row("a1", "t1", 1, "assistant", calls(AiToolCall("c1", "read_file", """{"path":"notes/a.md"}"""))),
            row("r1", "t1", 2, "tool", toolCallId = "c1"),
            // Xóa lỗi -> không.
            row("a2", "t2", 3, "assistant", calls(AiToolCall("c2", "delete_file", """{"path":"gone.md"}"""))),
            row("r2", "t2", 4, "tool", toolCallId = "c2", toolIsError = true),
            // Không có kết quả khớp -> không.
            row("a3", "t3", 5, "assistant", calls(AiToolCall("c3", "read_file", """{"path":"no-result.md"}"""))),
            // Convert có output_path -> cả nguồn lẫn đích.
            row("a4", "t4", 6, "assistant", calls(AiToolCall("c4", "convert.csv_to_json", """{"path":"d.csv","output_path":"d.json"}"""))),
            row("r4", "t4", 7, "tool", toolCallId = "c4"),
            // Đường dẫn rỗng (vd fs.list ở gốc) -> không.
            row("a5", "t5", 8, "assistant", calls(AiToolCall("c5", "list_files", """{"path":""}"""))),
            row("r5", "t5", 9, "tool", toolCallId = "c5"),
            // Trùng đường dẫn -> chỉ một lần.
            row("a6", "t6", 10, "assistant", calls(AiToolCall("c6", "read_file", """{"path":"notes/a.md"}"""))),
            row("r6", "t6", 11, "tool", toolCallId = "c6"),
        )
        val out = ModelFiles.extractUsedFiles(rows)
        assertEquals(
            listOf(
                UsedWorkspaceFile("notes/a.md", "read_file", "t1"),
                UsedWorkspaceFile("d.csv", "convert.csv_to_json", "t4"),
                UsedWorkspaceFile("d.json", "convert.csv_to_json", "t4"),
            ),
            out,
        )
        assertTrue(out.none { it.path == "gone.md" || it.path == "no-result.md" })
    }
}
