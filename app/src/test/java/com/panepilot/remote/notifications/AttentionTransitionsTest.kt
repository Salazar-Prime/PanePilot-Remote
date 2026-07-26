package com.panepilot.remote.notifications

import com.panepilot.remote.model.PanePilotSession
import com.panepilot.remote.model.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttentionTransitionsTest {
    @Test
    fun reportsOnlyNewNeedsInputTransitions() {
        val prior = mapOf(
            "terminal-1" to SessionState.RUNNING,
            "terminal-2" to SessionState.NEEDS_INPUT
        )
        val current = listOf(
            session("terminal-1", SessionState.NEEDS_INPUT),
            session("terminal-2", SessionState.NEEDS_INPUT),
            session("terminal-3", SessionState.READY)
        )

        assertEquals(
            listOf("terminal-1"),
            newlyAttentionRequired(prior, current).map { it.terminalId }
        )
    }

    @Test
    fun newlyDiscoveredAttentionSessionIsReported() {
        val transitions = newlyAttentionRequired(
            previousStates = emptyMap(),
            sessions = listOf(session("terminal-1", SessionState.NEEDS_INPUT))
        )

        assertEquals("terminal-1", transitions.single().terminalId)
    }

    @Test
    fun nonAttentionStatesAreIgnored() {
        val transitions = newlyAttentionRequired(
            previousStates = mapOf("terminal-1" to SessionState.RUNNING),
            sessions = listOf(session("terminal-1", SessionState.READY))
        )

        assertTrue(transitions.isEmpty())
    }

    private fun session(id: String, state: SessionState) = PanePilotSession(
        name = id,
        attachedClients = 0,
        paneTitle = "",
        currentCommand = "codex",
        paneDead = false,
        terminalId = id,
        projectId = "project-id",
        projectPath = "/work/project",
        profile = "codex",
        createdAt = "",
        dangerousMode = false,
        sessionKind = "terminal",
        actionName = null,
        latexSectionTitle = null,
        state = state
    )
}
