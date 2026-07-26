package com.panepilot.remote.ui

import com.panepilot.remote.model.PanePilotSession
import com.panepilot.remote.model.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionGroupsTest {
    @Test
    fun attentionSessionsFormTheFirstGroupAcrossProjects() {
        val sessions = listOf(
            session("idle-a", "/work/a", SessionState.IDLE),
            session("attention-b", "/work/b", SessionState.NEEDS_INPUT),
            session("attention-a", "/work/a", SessionState.NEEDS_INPUT),
            session("running-b", "/work/b", SessionState.RUNNING)
        )

        val groups = sessionGroups(sessions)

        assertEquals("attention", groups.first().key)
        assertTrue(groups.first().needsAttention)
        assertEquals(
            listOf("attention-b", "attention-a"),
            groups.first().sessions.map { it.name }
        )
        assertEquals(listOf("/work/a", "/work/b"), groups.drop(1).map { it.path })
        assertTrue(groups.drop(1).all { group -> group.sessions.none {
            it.state == SessionState.NEEDS_INPUT
        } })
    }

    @Test
    fun projectsRemainGroupedWhenNothingNeedsAttention() {
        val groups = sessionGroups(
            listOf(
                session("one", "/work/a", SessionState.READY),
                session("two", "/work/a", SessionState.IDLE)
            )
        )

        assertEquals(1, groups.size)
        assertFalse(groups.single().needsAttention)
        assertEquals(listOf("one", "two"), groups.single().sessions.map { it.name })
    }

    private fun session(
        name: String,
        projectPath: String,
        state: SessionState
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
        createdAt = "",
        dangerousMode = false,
        sessionKind = "terminal",
        actionName = null,
        latexSectionTitle = null,
        state = state
    )
}
