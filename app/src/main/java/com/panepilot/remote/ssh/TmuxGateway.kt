package com.panepilot.remote.ssh

import com.panepilot.remote.model.PanePilotSession
import com.panepilot.remote.model.SessionState
import com.panepilot.remote.model.TerminalKey
import java.util.Base64
import java.util.UUID

class TmuxGateway(private val ssh: SshConnection) {
    private var tmuxPath: String? = null

    fun reset() {
        tmuxPath = null
    }

    fun listSessions(): List<PanePilotSession> {
        val tmux = resolveTmux()
        val format = listOf(
            "#{session_name}",
            "#{session_attached}",
            "#{pane_title}",
            "#{pane_current_command}",
            "#{pane_dead}",
            "#{@panepilot_managed}",
            "#{@panepilot_schema}",
            "#{@panepilot_terminal_id}",
            "#{@panepilot_project_id}",
            "#{@panepilot_project_path}",
            "#{@panepilot_profile}",
            "#{@panepilot_created_at}",
            "#{@panepilot_dangerous_mode}",
            "#{@panepilot_session_kind}",
            "#{@panepilot_action_name}",
            "#{@panepilot_latex_section_title}",
            "#{@panepilot_action_id}",
            "#{@panepilot_action_command}",
            "#{@panepilot_action_exit_status}"
        ).joinToString(FIELD_SEPARATOR.toString())
        val result = ssh.execute(
            "${shellQuote(tmux)} list-sessions -F ${shellQuote(format)}",
            maxOutputBytes = 512 * 1024
        )
        if (
            result.exitCode != 0 &&
            !result.stderr.contains("no server running", ignoreCase = true) &&
            !result.stderr.contains("failed to connect to server", ignoreCase = true)
        ) {
            throw IllegalStateException(result.stderr.trim().ifBlank {
                "Tmux sessions could not be listed."
            })
        }
        return parseSessionList(result.stdout)
    }

    fun capture(sessionName: String): PaneSnapshot {
        val tmux = resolveTmux()
        val target = "=$sessionName:"
        val command =
            "${shellQuote(tmux)} display-message -p -t ${shellQuote(target)} " +
                "${shellQuote("#{pane_title}$FIELD_SEPARATOR#{pane_dead}")} && " +
                "${shellQuote(tmux)} capture-pane -p -e -J -S -300 -t ${shellQuote(target)}"
        val result = ssh.execute(command, maxOutputBytes = 768 * 1024)
        if (result.exitCode != 0) {
            throw IllegalStateException(result.stderr.trim().ifBlank {
                "The tmux pane could not be read."
            })
        }
        val newline = result.stdout.indexOf('\n')
        val metadataLine = if (newline >= 0) result.stdout.substring(0, newline) else ""
        val transcript = if (newline >= 0) result.stdout.substring(newline + 1) else result.stdout
        val cleanedMetadata = metadataLine.trimEnd('\r')
        val metadata = if (cleanedMetadata.contains(FIELD_SEPARATOR)) {
            cleanedMetadata.split(FIELD_SEPARATOR, limit = 2)
        } else {
            cleanedMetadata.split(ESCAPED_FIELD_SEPARATOR, limit = 2)
        }
        return PaneSnapshot(
            paneTitle = metadata.getOrElse(0) { "" },
            paneDead = metadata.getOrElse(1) { "0" } == "1",
            transcript = transcript.trimEnd()
        )
    }

    fun send(sessionName: String, message: String) {
        require(message.isNotBlank()) { "Type a message first." }
        require(!message.contains('\u0000')) { "Messages cannot contain a null character." }
        val payload = message.toByteArray()
        require(payload.size <= MAX_MESSAGE_BYTES) { "Messages must be 32 KB or smaller." }

        val tmux = resolveTmux()
        val target = "=$sessionName:"
        val bufferName = "panepilot-mobile-${UUID.randomUUID()}"
        val command = buildSendCommand(tmux, target, bufferName)
        val result = ssh.execute(command, stdin = payload, maxOutputBytes = 64 * 1024)
        if (result.exitCode != 0) {
            throw IllegalStateException(result.stderr.trim().ifBlank {
                "Tmux could not send the message."
            })
        }
    }

    fun sendKey(sessionName: String, key: TerminalKey) {
        val tmux = resolveTmux()
        val target = "=$sessionName:"
        val command =
            "${shellQuote(tmux)} send-keys -t ${shellQuote(target)} " +
                shellQuote(tmuxKeyName(key))
        val result = ssh.execute(command, maxOutputBytes = 64 * 1024)
        if (result.exitCode != 0) {
            throw IllegalStateException(result.stderr.trim().ifBlank {
                "Tmux could not send ${key.label}."
            })
        }
    }

    fun runAction(
        projectId: String,
        projectPath: String,
        action: com.panepilot.remote.model.ProjectAction
    ): ActionLaunch {
        require(UUID_PATTERN.matches(projectId)) { "The project identity is invalid." }
        require(UUID_PATTERN.matches(action.id)) { "The Action identity is invalid." }
        require(action.name.isNotBlank() && action.name.length <= 80) {
            "Action names must be between 1 and 80 characters."
        }
        require(action.name.none { it.code in 0..31 || it.code == 127 }) {
            "The Action name contains unsupported characters."
        }
        require(action.command.isNotBlank() && action.command.length <= 4_096) {
            "Action commands must be between 1 and 4096 characters."
        }
        require(!action.command.contains('\u0000')) { "The Action command is invalid." }
        require(projectPath.startsWith('/')) { "The project folder is invalid." }

        val tmux = resolveTmux()
        killPriorActionSessions(tmux, action.id)
        val terminalId = UUID.randomUUID().toString()
        val suffix = terminalId.take(4)
        val baseName = "Action · ${action.name}"
            .replace(Regex("[:\\u0000-\\u001f\\u007f]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(70)
            .ifBlank { "Action" }
        val sessionName = "$baseName · $suffix"
        val createdAt = java.time.Instant.now().toString()
        val launchCommand = actionLaunchCommand(
            tmux = tmux,
            terminalId = terminalId,
            projectId = projectId,
            projectPath = projectPath,
            createdAt = createdAt,
            action = action
        )
        val command =
            "${shellQuote(tmux)} new-session -d -s ${shellQuote(sessionName)} " +
                "-c ${shellQuote(projectPath)} ${shellQuote(launchCommand)} && " +
                "panepilot_action_attempt=0; while [ \"\$panepilot_action_attempt\" -lt 40 ]; do " +
                "if [ \"\$(${shellQuote(tmux)} show-option -qv -t " +
                "${shellQuote("=$sessionName")} ${shellQuote("@panepilot_managed")})\" = 1 ]; " +
                "then exit 0; fi; panepilot_action_attempt=\$((panepilot_action_attempt + 1)); " +
                "sleep 0.05; done; exit 6"
        val result = ssh.execute(command, timeoutMs = 20_000L, maxOutputBytes = 64 * 1024)
        if (result.exitCode != 0) {
            throw IllegalStateException(result.stderr.trim().ifBlank {
                "Tmux could not start ${action.name}."
            })
        }
        return ActionLaunch(sessionName, terminalId)
    }

    private fun killPriorActionSessions(tmux: String, actionId: String) {
        val format = listOf(
            "#{session_name}",
            "#{@panepilot_action_id}",
            "#{pane_dead}",
            "#{@panepilot_action_exit_status}"
        ).joinToString(FIELD_SEPARATOR.toString())
        val listed = ssh.execute(
            "${shellQuote(tmux)} list-sessions -F ${shellQuote(format)}",
            maxOutputBytes = 128 * 1024
        )
        if (listed.exitCode != 0) return
        val priorSessions = listed.stdout.lineSequence().mapNotNull { line ->
            val fields = if (line.contains(FIELD_SEPARATOR)) {
                line.split(FIELD_SEPARATOR, limit = 4)
            } else {
                line.split(ESCAPED_FIELD_SEPARATOR, limit = 4)
            }
            val name = fields.getOrNull(0).orEmpty()
            if (
                fields.getOrNull(1) == actionId &&
                name.isNotBlank() &&
                name.length <= 80 &&
                name.none { it == ':' || it.code in 0..31 || it.code == 127 }
            ) {
                Triple(name, fields.getOrNull(2) == "1", fields.getOrNull(3).orEmpty())
            } else {
                null
            }
        }.toList()
        if (priorSessions.any { (_, paneDead, exitStatus) -> !paneDead && exitStatus.isBlank() }) {
            throw IllegalStateException("This Action is already running.")
        }
        priorSessions.forEach { (name) ->
                ssh.execute(
                    "${shellQuote(tmux)} kill-session -t ${shellQuote("=$name")}",
                    maxOutputBytes = 16 * 1024
                )
        }
    }

    private fun resolveTmux(): String {
        tmuxPath?.let { return it }
        val result = ssh.execute(RESOLVE_TMUX_COMMAND, maxOutputBytes = 16 * 1024)
        val path = result.stdout.trim().lineSequence().lastOrNull()?.trim().orEmpty()
        if (
            result.exitCode != 0 ||
            !path.startsWith("/") ||
            path.length > 4_096 ||
            path.any { it.code in 0..31 || it.code == 127 }
        ) {
            throw IllegalStateException("Tmux is not installed or is not available to this SSH user.")
        }
        return path.also { tmuxPath = it }
    }

    companion object {
        const val FIELD_SEPARATOR = '\u001f'
        const val ESCAPED_FIELD_SEPARATOR = "\\037"
        private const val MAX_MESSAGE_BYTES = 32 * 1024
        private val UUID_PATTERN =
            Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)
        private val ACTION_REQUIRED =
            Regex("\\b(action required|approval required|permission required|needs (attention|input)|waiting for (approval|input)|confirm to continue)\\b", RegexOption.IGNORE_CASE)
        private val RUNNING = Regex("\\b(working|thinking)\\b", RegexOption.IGNORE_CASE)
        private val READY = Regex("\\bready\\b", RegexOption.IGNORE_CASE)

        private const val RESOLVE_TMUX_COMMAND =
            "panepilot_tmux=\$(command -v tmux 2>/dev/null || true); " +
                "if [ -z \"\$panepilot_tmux\" ]; then " +
                "for panepilot_candidate in /opt/homebrew/bin/tmux /usr/local/bin/tmux " +
                "/usr/bin/tmux /home/linuxbrew/.linuxbrew/bin/tmux \"\$HOME/.linuxbrew/bin/tmux\"; do " +
                "if [ -x \"\$panepilot_candidate\" ]; then " +
                "panepilot_tmux=\"\$panepilot_candidate\"; break; fi; done; fi; " +
                "if [ -z \"\$panepilot_tmux\" ]; then " +
                "panepilot_tmux=\$(\"\${SHELL:-/bin/sh}\" -lic 'command -v tmux' " +
                "2>/dev/null | tail -n 1); fi; " +
                "case \"\$panepilot_tmux\" in /*) test -x \"\$panepilot_tmux\" || exit 127 ;; " +
                "*) exit 127 ;; esac; printf '%s\\n' \"\$panepilot_tmux\""

        fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

        internal fun buildSendCommand(
            tmux: String,
            target: String,
            bufferName: String
        ): String =
            "${shellQuote(tmux)} load-buffer -b ${shellQuote(bufferName)} - && " +
                "${shellQuote(tmux)} paste-buffer -d -b ${shellQuote(bufferName)} " +
                "-t ${shellQuote(target)} && sleep 0.20 && " +
                "${shellQuote(tmux)} send-keys -t ${shellQuote(target)} C-m"

        internal fun actionLaunchCommand(
            tmux: String,
            terminalId: String,
            projectId: String,
            projectPath: String,
            createdAt: String,
            action: com.panepilot.remote.model.ProjectAction
        ): String {
            val executable = shellQuote(tmux)
            fun encoded(value: String): String = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.toByteArray(Charsets.UTF_8))
            fun setOption(key: String, value: String): String =
                "$executable set-option -q -t \"\$TMUX_PANE\" ${shellQuote(key)} " +
                    shellQuote(value)
            return listOf(
                setOption("@panepilot_managed", "0"),
                setOption("@panepilot_schema", "1"),
                setOption("@panepilot_terminal_id", terminalId),
                setOption("@panepilot_project_id", projectId),
                setOption("@panepilot_project_path", encoded(projectPath)),
                setOption("@panepilot_profile", "custom"),
                setOption("@panepilot_created_at", createdAt),
                setOption("@panepilot_dangerous_mode", "0"),
                setOption("@panepilot_session_kind", "action"),
                setOption("@panepilot_action_id", action.id),
                setOption("@panepilot_action_name", encoded(action.name)),
                setOption("@panepilot_action_command", encoded(action.command)),
                setOption("@panepilot_managed", "1"),
                "$executable set-option -q -p -t \"\$TMUX_PANE\" remain-on-exit on",
                "$executable set-option -q -u -t \"\$TMUX_PANE\" " +
                    shellQuote("@panepilot_action_exit_status") + " 2>/dev/null || true",
                "( ${action.command}\n)",
                "panepilot_action_status=\$?",
                "$executable set-option -q -t \"\$TMUX_PANE\" " +
                    shellQuote("@panepilot_action_exit_status") +
                    " \"\$panepilot_action_status\" || true",
                "exit \"\$panepilot_action_status\""
            ).joinToString("; ")
        }

        internal fun tmuxKeyName(key: TerminalKey): String = when (key) {
            TerminalKey.ENTER -> "C-m"
            TerminalKey.ESCAPE -> "Escape"
            TerminalKey.TAB -> "Tab"
            TerminalKey.ARROW_UP -> "Up"
            TerminalKey.ARROW_DOWN -> "Down"
            TerminalKey.ARROW_LEFT -> "Left"
            TerminalKey.ARROW_RIGHT -> "Right"
            TerminalKey.CTRL_C -> "C-c"
            TerminalKey.CTRL_D -> "C-d"
            TerminalKey.CTRL_L -> "C-l"
        }

        fun parseSessionList(output: String): List<PanePilotSession> {
            return output.lineSequence()
                .filter { it.isNotBlank() }
                .take(1_000)
                .mapNotNull(::parseSession)
                .sortedWith(
                    compareBy<PanePilotSession>({ statePriority(it.state) }, { it.projectPath }, { it.name })
                )
                .toList()
        }

        fun stateFrom(title: String, profile: String, paneDead: Boolean): SessionState {
            if (paneDead) return SessionState.STOPPED
            if (ACTION_REQUIRED.containsMatchIn(title)) return SessionState.NEEDS_INPUT
            if (RUNNING.containsMatchIn(title)) return SessionState.RUNNING
            if (READY.containsMatchIn(title)) return SessionState.READY
            return if (profile == "codex" || profile == "claude") {
                SessionState.IDLE
            } else {
                SessionState.LIVE
            }
        }

        private fun parseSession(line: String): PanePilotSession? {
            val fields = if (line.contains(FIELD_SEPARATOR)) {
                line.split(FIELD_SEPARATOR)
            } else {
                line.split(ESCAPED_FIELD_SEPARATOR)
            }
            if (fields.size != 19 || fields[5] != "1" || fields[6] != "1") return null
            val name = fields[0]
            val attached = fields[1].toIntOrNull() ?: return null
            val title = fields[2]
            val paneDead = fields[4] == "1"
            val terminalId = fields[7]
            val projectId = fields[8]
            if (
                name.isBlank() ||
                name.length > 80 ||
                name.any { it == ':' || it.code in 0..31 || it.code == 127 } ||
                attached < 0 ||
                !UUID_PATTERN.matches(terminalId) ||
                !UUID_PATTERN.matches(projectId)
            ) {
                return null
            }
            val path = decodeMetadata(fields[9], 4_096) ?: return null
            val profile = fields[10]
            if (profile !in setOf("shell", "codex", "claude", "custom")) return null

            return PanePilotSession(
                name = name,
                attachedClients = attached,
                paneTitle = title,
                currentCommand = fields[3],
                paneDead = paneDead,
                terminalId = terminalId,
                projectId = projectId,
                projectPath = path,
                profile = profile,
                createdAt = fields[11],
                dangerousMode = fields[12] == "1",
                sessionKind = fields[13].ifBlank { "terminal" },
                actionName = decodeMetadata(fields[14], 80),
                latexSectionTitle = decodeMetadata(fields[15], 1_024),
                state = stateFrom(title, profile, paneDead),
                actionId = fields[16].takeIf(UUID_PATTERN::matches),
                actionCommand = decodeMetadata(fields[17], 4_096),
                actionExitCode = fields[18].toIntOrNull()
            )
        }

        private fun decodeMetadata(value: String, maxLength: Int): String? {
            if (value.isBlank()) return null
            return runCatching {
                String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
                    .takeIf { it.isNotBlank() && it.length <= maxLength && !it.contains('\u0000') }
            }.getOrNull()
        }

        private fun statePriority(state: SessionState): Int = when (state) {
            SessionState.NEEDS_INPUT -> 0
            SessionState.RUNNING -> 1
            SessionState.READY -> 2
            SessionState.IDLE -> 3
            SessionState.LIVE -> 4
            SessionState.STOPPED -> 5
        }
    }
}

data class ActionLaunch(
    val sessionName: String,
    val terminalId: String
)

data class PaneSnapshot(
    val paneTitle: String,
    val paneDead: Boolean,
    val transcript: String
)
