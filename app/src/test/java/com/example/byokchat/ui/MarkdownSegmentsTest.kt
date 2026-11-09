package com.example.byokchat.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownSegmentsTest {

    private val f = "```"

    @Test
    fun noCodeBlockGivesSingleText() {
        assertEquals(listOf(MdSegment.Text("xin chao\nban")), parseMarkdownSegments("xin chao\nban"))
    }

    @Test
    fun blankInputGivesNothing() {
        assertTrue(parseMarkdownSegments("").isEmpty())
        assertTrue(parseMarkdownSegments("  \n \n").isEmpty())
    }

    @Test
    fun oneClosedBlock() {
        val r = parseMarkdownSegments("$f" + "Python\nprint(1)\nprint(2)\n$f")
        assertEquals(listOf(MdSegment.Code("python", "print(1)\nprint(2)", true)), r)
    }

    @Test
    fun unclosedBlockRunsToTheEnd() {
        val r = parseMarkdownSegments("truoc\n$f" + "kotlin\nfun a() {\n    1\n")
        assertEquals(
            listOf(MdSegment.Text("truoc"), MdSegment.Code("kotlin", "fun a() {\n    1", false)),
            r,
        )
    }

    @Test
    fun twoBlocksWithTextBetween() {
        val src = "mot\n$f\na\n$f\ngiua\n$f" + "js\nb\n$f\ncuoi"
        assertEquals(
            listOf(
                MdSegment.Text("mot"),
                MdSegment.Code("", "a", true),
                MdSegment.Text("giua"),
                MdSegment.Code("js", "b", true),
                MdSegment.Text("cuoi"),
            ),
            parseMarkdownSegments(src),
        )
    }

    @Test
    fun longerClosingFenceStillCloses() {
        val r = parseMarkdownSegments("$f\nx\n$f`\ntiep")
        assertEquals(listOf(MdSegment.Code("", "x", true), MdSegment.Text("tiep")), r)
    }

    @Test
    fun shorterClosingFenceDoesNotClose() {
        val r = parseMarkdownSegments("$f`\nx\n$f\ny")
        assertEquals(listOf(MdSegment.Code("", "x\n$f\ny", false)), r)
    }

    @Test
    fun fenceInMiddleOfLineIsNotABlock() {
        val src = "dung dau $f trong cau\ntiep"
        assertEquals(listOf(MdSegment.Text(src)), parseMarkdownSegments(src))
    }

    @Test
    fun emptyBlock() {
        assertEquals(listOf(MdSegment.Code("", "", true)), parseMarkdownSegments("$f\n$f"))
    }

    @Test
    fun crlfIsHandled() {
        val r = parseMarkdownSegments("a\r\n$f" + "sh\r\nls\r\n$f\r\nb")
        assertEquals(listOf(MdSegment.Text("a"), MdSegment.Code("sh", "ls", true), MdSegment.Text("b")), r)
    }

    @Test
    fun indentedFencesWork() {
        val r = parseMarkdownSegments("  $f" + "py\n  x = 1\n  $f")
        assertEquals(listOf(MdSegment.Code("py", "  x = 1", true)), r)
    }

    @Test
    fun languageIsFirstWordLowercase() {
        val r = parseMarkdownSegments("$f" + "  Kotlin   title=abc\nz\n$f")
        assertEquals("kotlin", (r[0] as MdSegment.Code).language)
    }

    @Test
    fun infoStringWithBacktickIsNotAFence() {
        val src = "$f" + "js `x`\nabc"
        assertEquals(listOf(MdSegment.Text(src)), parseMarkdownSegments(src))
    }

    @Test
    fun indentedFenceIsStillDetected() {
        val r = parseMarkdownSegments("  $f" + "python\n  x = 1\n  $f")
        assertEquals(listOf(MdSegment.Code("python", "  x = 1", true)), r)
    }

    @Test
    fun longerFenceNeedsLongerClosingFence() {
        // Mở bằng 4 dấu huyền: dòng đóng 3 dấu chưa đóng được khối (nó là chữ trong khối).
        val four = "````"
        val r = parseMarkdownSegments(four + "\nabc\n$f\nxyz\n" + four)
        assertEquals(listOf(MdSegment.Code("", "abc\n$f\nxyz", true)), r)
    }

    @Test
    fun unclosedBlockWithoutTrailingNewlineKeepsEveryLine() {
        val r = parseMarkdownSegments("$f" + "js\nvar a\nvar b")
        assertEquals(listOf(MdSegment.Code("js", "var a\nvar b", false)), r)
    }

    @Test
    fun textWithoutBackticksIsUnchanged() {
        val src = "dong 1\n\ndong 3 co ** dam **"
        assertEquals(listOf(MdSegment.Text(src)), parseMarkdownSegments(src))
    }
}
