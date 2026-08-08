package com.panepilot.remote.ssh

import com.panepilot.remote.model.SessionState
import com.panepilot.remote.model.TerminalKey
import com.panepilot.remote.model.ProjectAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class TmuxGatewayTest {
    @Test
    fun `parses only versioned PanePilot sessions`() {
        val separator = TmuxGateway.FIELD_SEPARATOR
        val path = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("/srv/panepilot".toByteArray())
        val fields = listOf(
            "codex-main",
            "1",
            "Thinking · 2/4 tasks",
            "codex",
            "0",
            "1",
            "1",
            "2c4f2d29-2985-4f48-a846-85ff0638984c",
            "b50a1e21-fbb4-4103-b287-7586411e6716",
            path,
            "codex",
            "2026-07-25T00:00:00.000Z",
            "0",
            "terminal",
            "",
            "",
            "",
            "",
            ""
        )

        val sessions = TmuxGateway.parseSessionList(fields.joinToString(separator.toString()))

        assertEquals(1, sessions.size)
        assertEquals("/srv/panepilot", sessions.single().projectPath)
        assertEquals(SessionState.RUNNING, sessions.single().state)
    }

    @Test
    fun `parses the escaped separator emitted by tmux over a shell`() {
        val separator = TmuxGateway.ESCAPED_FIELD_SEPARATOR
        val path = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("/Users/varun/Work/personal/sal3000".toByteArray())
        val fields = listOf(
            "Android App",
            "1",
            "Working · Tasks 0/3",
            "node",
            "0",
            "1",
            "1",
            "0b81366d-bdc5-4d96-aeea-25d568ad3a86",
            "a64c512b-a1a5-46c8-8d75-b8e43a9fe30f",
            path,
            "codex",
            "2026-07-26T03:32:06.581Z",
            "1",
            "terminal",
            "",
            "",
            "",
            "",
            ""
        )

        val sessions = TmuxGateway.parseSessionList(fields.joinToString(separator))

        assertEquals(1, sessions.size)
        assertEquals("Android App", sessions.single().name)
        assertEquals("/Users/varun/Work/personal/sal3000", sessions.single().projectPath)
    }

    @Test
    fun `maps PanePilot title snapshots without treating shells as agents`() {
        assertEquals(
            SessionState.NEEDS_INPUT,
            TmuxGateway.stateFrom("Action required · approve command", "codex", false)
        )
        assertEquals(
            SessionState.NEEDS_INPUT,
            TmuxGateway.stateFrom("Needs attention · tool failed", "codex", false)
        )
        assertEquals(
            SessionState.READY,
            TmuxGateway.stateFrom("Ready · 4/4 tasks", "codex", false)
        )
        assertEquals(SessionState.LIVE, TmuxGateway.stateFrom("project", "shell", false))
        assertEquals(SessionState.STOPPED, TmuxGateway.stateFrom("Ready", "codex", true))
    }

    @Test
    fun `shell quoting contains special text in one argument`() {
        val quoted = TmuxGateway.shellQuote("name'; touch /tmp/nope; echo '")

        assertTrue(quoted.startsWith("'"))
        assertTrue(quoted.endsWith("'"))
        assertTrue(quoted.contains("'\\''"))
        assertFalse(quoted.contains("\n"))
    }

    @Test
    fun `message submit waits after paste and sends an explicit carriage return`() {
        val command = TmuxGateway.buildSendCommand(
            tmux = "/usr/bin/tmux",
            target = "=Codex:",
            bufferName = "mobile-buffer"
        )

        val pasteIndex = command.indexOf("paste-buffer")
        val delayIndex = command.indexOf("sleep 0.20")
        val submitIndex = command.indexOf("send-keys")
        assertTrue(pasteIndex >= 0)
        assertTrue(delayIndex > pasteIndex)
        assertTrue(submitIndex > delayIndex)
        assertTrue(command.endsWith(" C-m"))
    }

    @Test
    fun `mobile terminal buttons map only to exact tmux key names`() {
        assertEquals("C-m", TmuxGateway.tmuxKeyName(TerminalKey.ENTER))
        assertEquals("Escape", TmuxGateway.tmuxKeyName(TerminalKey.ESCAPE))
        assertEquals("Tab", TmuxGateway.tmuxKeyName(TerminalKey.TAB))
        assertEquals("Up", TmuxGateway.tmuxKeyName(TerminalKey.ARROW_UP))
        assertEquals("C-c", TmuxGateway.tmuxKeyName(TerminalKey.CTRL_C))
        assertEquals("C-d", TmuxGateway.tmuxKeyName(TerminalKey.CTRL_D))
        assertEquals("C-l", TmuxGateway.tmuxKeyName(TerminalKey.CTRL_L))
    }

    @Test
    fun `action launch tags the tmux pane before running the shared command`() {
        val command = TmuxGateway.actionLaunchCommand(
            tmux = "/usr/bin/tmux",
            terminalId = "2c4f2d29-2985-4f48-a846-85ff0638984c",
            projectId = "b50a1e21-fbb4-4103-b287-7586411e6716",
            projectPath = "/srv/panepilot",
            createdAt = "2026-08-08T00:00:00Z",
            action = ProjectAction(
                id = "9af2aa17-6a84-4215-91fe-7dbddf6b348f",
                name = "Run tests",
                command = "npm test"
            )
        )

        assertTrue(command.contains("@panepilot_session_kind"))
        assertTrue(command.contains("@panepilot_action_id"))
        assertTrue(command.contains("remain-on-exit on"))
        assertTrue(command.contains("( npm test"))
        assertTrue(command.indexOf("@panepilot_managed") < command.indexOf("( npm test"))
    }
}
