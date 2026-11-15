package com.example.byokchat.tools

import com.example.byokchat.media.MediaState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Live download progress shown in the tool card ("Running. Progress 42%"). */
class ToolProgressTest {

    @Test
    fun percentAndLabelWithKnownTotal() {
        val p = ToolProgress(5_000_000, 10_000_000)
        assertEquals(50, p.percent)
        assertEquals("Running. Progress 50%", p.label())
    }

    @Test
    fun percentStartsAtZeroAndClampsAtOneHundred() {
        assertEquals("Running. Progress 0%", ToolProgress(0, 10_000_000).label())
        assertEquals(100, ToolProgress(12_000_000, 10_000_000).percent)
    }

    @Test
    fun unknownTotalShowsDownloadedSizeNeverAFakePercent() {
        val p = ToolProgress(12_345_678, null)
        assertNull(p.percent)
        assertEquals("Running. Downloaded 11.8 MB…", p.label())
    }

    @Test
    fun noBytesYetIsJustRunning() {
        assertEquals("Running…", ToolProgress(0, null).label())
    }

    @Test
    fun brokerReportsPerCallIdAndClearsWhenTheCallEnds() {
        val broker = ToolProgressBroker()
        broker.report("call-1", 2_000_000, 10_000_000)
        broker.report("call-2", 500, null)
        assertEquals(ToolProgress(2_000_000, 10_000_000), broker.progress.value["call-1"])
        assertEquals(ToolProgress(500, null), broker.progress.value["call-2"])
        broker.clear("call-1")
        assertFalse(broker.progress.value.containsKey("call-1"))
        assertTrue(broker.progress.value.containsKey("call-2"))
    }

    @Test
    fun mediaStateIsNotBufferingByDefault() {
        assertFalse(MediaState().buffering)
        assertTrue(MediaState(buffering = true).copy(playing = true).buffering)
    }
}
