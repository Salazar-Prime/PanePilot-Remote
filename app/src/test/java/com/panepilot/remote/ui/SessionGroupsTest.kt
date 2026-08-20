package com.panepilot.remote.ui

import com.panepilot.remote.model.PanePilotSession
import com.panepilot.remote.model.SessionState
import com.panepilot.remote.model.TerminalSortMode
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionGroupsTest {
    @Test
    fun activitySortKeepsPinnedAheadOfAttentionAndSortedSessions() {
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
            sortMode = TerminalSortMode.ACTIVITY
        )

        assertEquals(
            listOf(
                SessionGroupKind.PINNED,
                SessionGroupKind.ATTENTION,
                SessionGroupKind.TERMINALS
            ),
            groups.map { it.kind }
        )
        assertEquals(listOf("Pinned terminal"), groups[0].sessions.map { it.name })
        assertEquals(
            listOf("Direct question", "Ready response"),
            groups[1].sessions.map { it.name }
        )
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
    fun activitySortPrioritizesWorkingBeforeReadyThenUsesLatestActivity() {
        val groups = sessionGroups(
            sessions = listOf(
                session("Older ready", "/work/a", SessionState.READY),
                session("Working", "/work/a", SessionState.RUNNING),
                session("Latest ready", "/work/a", SessionState.READY),
                session("Idle", "/work/a", SessionState.IDLE)
            ),
            pinnedTerminalIds = emptySet(),
            unreadAttentionTerminalIds = emptySet(),
            sortMode = TerminalSortMode.ACTIVITY,
            activityTimes = mapOf(
                "terminal-Older ready" to 200L,
                "terminal-Working" to 100L,
                "terminal-Latest ready" to 300L,
                "terminal-Idle" to 400L
            )
        )

        assertEquals(
            listOf("Working", "Latest ready", "Older ready", "Idle"),
            groups.single().sessions.map { it.name }
        )
    }

    @Test
    fun recentSortIsNotOverriddenByUnreadAttention() {
        val groups = sessionGroups(
            sessions = listOf(
                session("Recently opened", "/work/a", SessionState.IDLE),
                session("Unread ready", "/work/a", SessionState.READY)
            ),
            pinnedTerminalIds = emptySet(),
            unreadAttentionTerminalIds = setOf("terminal-Unread ready"),
            sortMode = TerminalSortMode.NEWEST,
            interactionTimes = mapOf(
                "terminal-Recently opened" to 300L,
                "terminal-Unread ready" to 200L
            )
        )

        assertEquals(
            listOf("Recently opened", "Unread ready"),
            groups.single().sessions.map { it.name }
        )
    }

    @Test
    fun navigationListExcludesStoppedAndCapabilityOwnedSessions() {
        val groups = sessionGroups(
            sessions = listOf(
                session("Ordinary", "/work/a", SessionState.IDLE),
                session("LaTeX", "/work/a", SessionState.RUNNING, kind = "latex-chat"),
                session("Action", "/work/a", SessionState.LIVE, kind = "action"),
                session("Project Q&A", "/work/a", SessionState.IDLE, kind = "project-qna"),
                session("Stopped", "/work/a", SessionState.STOPPED, paneDead = true)
            ),
            pinnedTerminalIds = setOf("terminal-Action"),
            unreadAttentionTerminalIds = setOf("terminal-Project Q&A"),
            sortMode = TerminalSortMode.NAME
        )

        assertEquals(
            listOf("LaTeX", "Ordinary"),
            groups.single().sessions.map { it.name }
        )
    }

    private fun session(
        name: String,
        projectPath: String,
        state: SessionState,
        createdAt: String = "2026-07-01T00:00:00Z",
        kind: String = "terminal",
        paneDead: Boolean = false
    ) = PanePilotSession(
        name = name,
        attachedClients = 0,
        paneTitle = "",
        currentCommand = "",
        paneDead = paneDead,
        terminalId = "terminal-$name",
        projectId = "project-$projectPath",
        projectPath = projectPath,
        profile = "codex",
        createdAt = createdAt,
        dangerousMode = false,
        sessionKind = kind,
        actionName = null,
        latexSectionTitle = null,
        state = state
    )
}
