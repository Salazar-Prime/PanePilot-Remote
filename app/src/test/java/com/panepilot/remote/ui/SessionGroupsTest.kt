package com.panepilot.remote.ui

import com.panepilot.remote.model.PanePilotSession
import com.panepilot.remote.model.SessionState
import com.panepilot.remote.model.TerminalSortMode
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionGroupsTest {
    @Test
    fun unreadAttentionStaysAheadOfPinnedAndSortedSessions() {
        val sessions = listOf(
            session("Zulu", "/work/a", SessionState.IDLE),
            session("Ready response", "/work/b", SessionState.READY),
            session("Pinned terminal", "/work/a", SessionState.RUNNING),
            session("Direct question", "/work/c", SessionState.NEEDS_INPUT)
        )

        val groups = sessionGroups(
            sessions = sessions,
            pinnedTerminalIds = setOf("terminal-Pinned terminal"),
            unreadAttentionTerminalIds = setOf("terminal-Ready response"),
            sortMode = TerminalSortMode.NAME
        )

        assertEquals(
            listOf(
                SessionGroupKind.ATTENTION,
                SessionGroupKind.PINNED,
                SessionGroupKind.TERMINALS
            ),
            groups.map { it.kind }
        )
        assertEquals(
            listOf("Direct question", "Ready response"),
            groups[0].sessions.map { it.name }
        )
        assertEquals(listOf("Pinned terminal"), groups[1].sessions.map { it.name })
        assertEquals(listOf("Zulu"), groups[2].sessions.map { it.name })
    }

    @Test
    fun projectSortKeepsRemainingSessionsInAlphabeticalProjectGroups() {
        val groups = sessionGroups(
            sessions = listOf(
                session("Zulu", "/work/beta", SessionState.IDLE),
                session("Alpha", "/work/alpha", SessionState.READY),
                session("Beta", "/work/alpha", SessionState.RUNNING)
            ),
            pinnedTerminalIds = emptySet(),
            unreadAttentionTerminalIds = emptySet(),
            sortMode = TerminalSortMode.PROJECT
        )

        assertEquals(listOf("alpha", "beta"), groups.map { it.title })
        assertEquals(listOf("Alpha", "Beta"), groups.first().sessions.map { it.name })
    }

    @Test
    fun newestSortUsesDescendingCreationTimestamp() {
        val groups = sessionGroups(
            sessions = listOf(
                session("Old", "/work/a", SessionState.IDLE, "2026-01-01T00:00:00Z"),
                session("New", "/work/a", SessionState.IDLE, "2026-07-28T00:00:00Z")
            ),
            pinnedTerminalIds = emptySet(),
            unreadAttentionTerminalIds = emptySet(),
            sortMode = TerminalSortMode.NEWEST
        )

        assertEquals(listOf("New", "Old"), groups.single().sessions.map { it.name })
    }

    @Test
    fun recentSortUsesLatestInAppInteraction() {
        val groups = sessionGroups(
            sessions = listOf(
                session("Created later", "/work/a", SessionState.IDLE, "2026-08-01T00:00:00Z"),
                session("Touched", "/work/a", SessionState.IDLE, "2026-01-01T00:00:00Z")
            ),
            pinnedTerminalIds = emptySet(),
            unreadAttentionTerminalIds = emptySet(),
            sortMode = TerminalSortMode.NEWEST,
            interactionTimes = mapOf("terminal-Touched" to 2_000_000_000_000L)
        )

        assertEquals(listOf("Touched", "Created later"), groups.single().sessions.map { it.name })
    }

    @Test
    fun activitySortKeepsReadySessionAtItsStickyActivityPosition() {
        val groups = sessionGroups(
            sessions = listOf(
                session("Ready", "/work/a", SessionState.READY),
                session("Working", "/work/a", SessionState.RUNNING)
            ),
            pinnedTerminalIds = emptySet(),
            unreadAttentionTerminalIds = emptySet(),
            sortMode = TerminalSortMode.ACTIVITY,
            activityTimes = mapOf(
                "terminal-Ready" to 200L,
                "terminal-Working" to 100L
            )
        )

        assertEquals(listOf("Ready", "Working"), groups.single().sessions.map { it.name })
    }

    private fun session(
        name: String,
        projectPath: String,
        state: SessionState,
        createdAt: String = "2026-07-01T00:00:00Z"
    ) = PanePilotSession(
        name = name,
        attachedClients = 0,
        paneTitle = "",
        currentCommand = "",
        paneDead = false,
        terminalId = "terminal-$name",
        projectId = "project-$projectPath",
        projectPath = projectPath,
        profile = "codex",
        createdAt = createdAt,
        dangerousMode = false,
        sessionKind = "terminal",
        actionName = null,
        latexSectionTitle = null,
        state = state
    )
}
