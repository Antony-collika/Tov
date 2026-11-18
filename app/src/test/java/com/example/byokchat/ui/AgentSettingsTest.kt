package com.example.byokchat.ui

import com.example.byokchat.chat.agentIdSlug
import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests for the new-agent id slug (Agent settings screen). */
class AgentSettingsTest {

    @Test fun agentIdSlugLowercasesAndDashesSpaces() {
        assertEquals("my-reviewer", agentIdSlug("My Reviewer"))
        assertEquals("research-2", agentIdSlug("Research 2!"))
        assertEquals("agent", agentIdSlug("!!!"))
        assertEquals("agent", agentIdSlug(""))
        assertEquals("a-b", agentIdSlug("  A--B  "))
    }
}
