package com.panepilot.remote.notifications

import com.panepilot.remote.model.PanePilotSession
import com.panepilot.remote.model.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttentionTransitionsTest {
    @Test
    fun reportsNewNeedsInputAndCompletedResponseTransitions() {
        val prior = mapOf(
            "terminal-1" to SessionState.RUNNING,
            "terminal-2" to SessionState.NEEDS_INPUT,
            "terminal-3" to SessionState.RUNNING
        )
        val current = listOf(
            session("terminal-1", SessionState.NEEDS_INPUT),
            session("terminal-2", SessionState.NEEDS_INPUT),
            session("terminal-3", SessionState.READY)
        )

        assertEquals(
            listOf(
                "terminal-1" to AttentionEventType.NEEDS_INPUT,
                "terminal-3" to AttentionEventType.RESPONSE_READY
            ),
            newAttentionEvents(prior, current).map { it.session.terminalId to it.type }
        )
    }

    @Test
    fun newlyDiscoveredAttentionSessionIsReported() {
        val transitions = newAttentionEvents(
            previousStates = emptyMap(),
            sessions = listOf(session("terminal-1", SessionState.NEEDS_INPUT))
        )

        assertEquals("terminal-1", transitions.single().session.terminalId)
        assertEquals(AttentionEventType.NEEDS_INPUT, transitions.single().type)
    }

    @Test
    fun readyWithoutAWorkingTransitionDoesNotAlertOnStartup() {
        val transitions = newAttentionEvents(
            previousStates = emptyMap(),
            sessions = listOf(session("terminal-1", SessionState.READY))
        )

        assertTrue(transitions.isEmpty())
    }

    @Test
    fun runningAndIdleSessionsResolveUnreadAttention() {
        val resolved = noLongerNeedsAttention(
            listOf(
                session("terminal-1", SessionState.RUNNING),
                session("terminal-2", SessionState.IDLE),
                session("terminal-3", SessionState.READY),
                session("terminal-4", SessionState.NEEDS_INPUT)
            )
        )

        assertEquals(
            listOf("terminal-1", "terminal-2"),
            resolved.map { it.terminalId }
        )
    }

    @Test
    fun completionAndNeedsInputUseDistinctAndroidChannelsAndIds() {
        assertNotEquals(
            AttentionNotifier.channelIdFor(AttentionEventType.NEEDS_INPUT),
            AttentionNotifier.channelIdFor(AttentionEventType.RESPONSE_READY)
        )
        assertNotEquals(
            AttentionNotifier.notificationId(
                "profile",
                "terminal",
                AttentionEventType.NEEDS_INPUT
            ),
            AttentionNotifier.notificationId(
                "profile",
                "terminal",
                AttentionEventType.RESPONSE_READY
            )
        )
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
