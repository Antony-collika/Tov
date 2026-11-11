package com.example.byokchat.tools

import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Test cho Đợt E phần A: parser tools.md. */
class ToolsMdParserTest {

    private val origins = mapOf(
        "txt.write" to "Write text to a file.",
        "txt.read" to "Read a bounded section of a UTF-8 text file. Large files require user approval.",
        "time.now" to "Get the current date and time.",
    )

    private val sample = """
        > Tools card. One block per tool.
        > Lines starting with ">" are notes.

        ## txt.write
        > Do not change the two lines below.
        uid: txt.write
        origin_description: Write text to a file.
        > What the model sees.
        name: write_file
        description: Write text to a file.

        ## txt.read
          > Indented notes are skipped too.
        uid: txt.read
        origin_description: Read a bounded section of a UTF-8 text file.
          Large files require user approval.
        name: read_file
        description: Read a bounded section of a UTF-8 text file.
        Large files require user approval.
        Use start_line/end_line for line ranges.
    """.trimIndent()

    @Test
    fun parsesSampleInBlockOrderWithMultilineFields() {
        val tools = ToolsMdParser.parse(sample, origins)
        assertEquals(listOf("txt.write", "txt.read"), tools.map { it.uid })
        assertEquals("write_file", tools[0].name)
        assertEquals("Write text to a file.", tools[0].description)
        assertEquals("read_file", tools[1].name)
        // origin_description và description đa dòng: nối bằng \n, chuẩn hóa khoảng trắng khi đối chiếu.
        assertEquals(
            "Read a bounded section of a UTF-8 text file.\nLarge files require user approval.\nUse start_line/end_line for line ranges.",
            tools[1].description,
        )
    }

    @Test
    fun emptyFileIsAllowedAndMeansZeroTools() {
        assertEquals(emptyList<ParsedTool>(), ToolsMdParser.parse("", origins))
        assertEquals(emptyList<ParsedTool>(), ToolsMdParser.parse("> only notes\n\n> nothing else\n", origins))
    }

    @Test
    fun unknownUidIsAnErrorNamingTheUid() {
        val bad = "## nope\nuid: nope\nname: nope\ndescription: No such tool.\n"
        val e = assertThrows(ToolsMdParseException::class.java) { ToolsMdParser.parse(bad, origins) }
        assertTrue(e.message!!.contains("nope"))
    }

    @Test
    fun originMismatchIsAnError() {
        val bad = "## txt.write\nuid: txt.write\norigin_description: Something else entirely.\nname: write_file\ndescription: Write text to a file.\n"
        val e = assertThrows(ToolsMdParseException::class.java) { ToolsMdParser.parse(bad, origins) }
        assertTrue(e.message!!.contains("txt.write"))
        assertTrue(e.message!!.contains("origin_description"))
    }

    @Test
    fun missingOriginIsAcceptedForOldFormat() {
        val old = "## txt.write\nname: write_file\ndescription: Write text to a file.\n"
        assertEquals(listOf(ParsedTool("txt.write", "write_file", "Write text to a file.")), ToolsMdParser.parse(old, origins))
    }

    @Test
    fun uidDefaultsToHeadingWhenUidLineIsMissing() {
        val old = "## time.now\nname: current_time\ndescription: Get the current date and time.\n"
        assertEquals(listOf(ParsedTool("time.now", "current_time", "Get the current date and time.")), ToolsMdParser.parse(old, origins))
    }

    @Test
    fun duplicateNameDuplicateUidEmptyNameAndEmptyDescriptionAreErrors() {
        val dupName = "## txt.write\nuid: txt.write\nname: same\ndescription: Write text to a file.\n\n## txt.read\nuid: txt.read\nname: same\ndescription: Read a bounded section of a UTF-8 text file. Large files require user approval.\n"
        assertThrows(ToolsMdParseException::class.java) { ToolsMdParser.parse(dupName, origins) }
        val dupUid = "## txt.write\nuid: txt.write\nname: a\ndescription: Write text to a file.\n\n## txt.write\nuid: txt.write\nname: b\ndescription: Write text to a file.\n"
        assertThrows(ToolsMdParseException::class.java) { ToolsMdParser.parse(dupUid, origins) }
        val noName = "## txt.write\nuid: txt.write\ndescription: Write text to a file.\n"
        assertThrows(ToolsMdParseException::class.java) { ToolsMdParser.parse(noName, origins) }
        val badName = "## txt.write\nuid: txt.write\nname: bad name!\ndescription: Write text to a file.\n"
        assertThrows(ToolsMdParseException::class.java) { ToolsMdParser.parse(badName, origins) }
        val noDesc = "## txt.write\nuid: txt.write\nname: write_file\n"
        assertThrows(ToolsMdParseException::class.java) { ToolsMdParser.parse(noDesc, origins) }
    }

    @Test
    fun textBeforeFirstBlockAndStrayTextAfterUidAreErrors() {
        assertThrows(ToolsMdParseException::class.java) {
            ToolsMdParser.parse("hello\n\n## txt.write\nuid: txt.write\nname: write_file\ndescription: Write text to a file.\n", origins)
        }
        assertThrows(ToolsMdParseException::class.java) {
            ToolsMdParser.parse("## txt.write\nuid: txt.write\nstray line\nname: write_file\ndescription: Write text to a file.\n", origins)
        }
    }

    @Test
    fun templateRoundTrips() {
        val tools = listOf(
            Tool("txt.write", ToolGroup.TEXT, Risk.WRITE, "write_file", "Write text to a file.", buildJsonObject {}) { _, _ -> "" },
            Tool("time.now", ToolGroup.DAILY, Risk.READ, "current_time", "Get the current date and time.", buildJsonObject {}) { _, _ -> "" },
        )
        val template = ToolsMdParser.buildTemplate(tools)
        val parsed = ToolsMdParser.parse(template, origins)
        assertEquals(
            listOf(
                ParsedTool("txt.write", "write_file", "Write text to a file."),
                ParsedTool("time.now", "current_time", "Get the current date and time."),
            ),
            parsed,
        )
    }
}
