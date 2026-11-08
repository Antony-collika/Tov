package com.example.byokchat.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolSummaryTest {
    @Test fun readFileWithRange() {
        assertEquals("Read file notes/todo.md (lines 3\u201310)", toolSummary("txt.read", "read_file", """{"path":"notes/todo.md","start_line":3,"end_line":10}"""))
        assertEquals("Read file a.txt", toolSummary("txt.read", "read_file", """{"path":"a.txt"}"""))
    }

    @Test fun moveAndDelete() {
        assertEquals("Move a.txt \u2192 b/a.txt", toolSummary("fs.move", "move", """{"source":"a.txt","dest":"b/a.txt"}"""))
        assertEquals("Delete old.log", toolSummary("fs.delete", "delete", """{"path":"old.log"}"""))
    }

    @Test fun listRootFolder() {
        assertEquals("List workspace folder", toolSummary("fs.list", "list_dir", "{}"))
        assertEquals("List workspace folder", toolSummary("fs.list", "list_dir", ""))
    }

    @Test fun zipCountsItems() {
        assertEquals("Zip 2 item(s) into out.zip", toolSummary("fs.zip", "zip", """{"sources":["a","b"],"dest":"out.zip"}"""))
    }

    @Test fun alarmIsFormatted() {
        assertEquals("Set an alarm for 07:05", toolSummary("alarm.set", "set_alarm", """{"hour":7,"minute":5}"""))
    }

    @Test fun longValuesAreShortened() {
        val s = toolSummary("fs.mkdir", "make_dir", """{"path":"${"x".repeat(200)}"}""")
        assertTrue(s.length < 90)
        assertTrue(s.endsWith("\u2026"))
    }

    @Test fun unknownToolFallsBackToItsName() {
        assertEquals("my_tool", toolSummary("some.unknown", "my_tool", "{}"))
        assertEquals("old_name", toolSummary(null, "old_name", "{}"))
    }

    @Test fun brokenJsonDoesNotCrash() {
        assertEquals("Read file ", toolSummary("txt.read", "read_file", "{not json"))
    }

    @Test fun prettyArgsKeepsRawTextWhenNotJson() {
        assertEquals("{not json", prettyArgs("{not json"))
        assertEquals("(no arguments)", prettyArgs(""))
        assertTrue(prettyArgs("""{"a":1}""").contains("\n"))
    }
}
