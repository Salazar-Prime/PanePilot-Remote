package com.panepilot.remote.model

import java.util.UUID

enum class AuthMode {
    PASSWORD,
    PRIVATE_KEY
}

data class ServerProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val authMode: AuthMode,
    val keyDisplayName: String? = null
)

data class ConnectionSecret(
    val password: String = "",
    val keyPassphrase: String = ""
)

data class RemoteFileEntry(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val modifiedAtMillis: Long
)

data class RemoteDownloadRequest(
    val id: String = UUID.randomUUID().toString(),
    val file: RemoteFileEntry
)

enum class SessionState {
    NEEDS_INPUT,
    RUNNING,
    READY,
    IDLE,
    LIVE,
    STOPPED
}

enum class TerminalKey(val label: String) {
    ENTER("Enter"),
    ESCAPE("Esc"),
    TAB("Tab"),
    ARROW_UP("↑"),
    ARROW_DOWN("↓"),
    ARROW_LEFT("←"),
    ARROW_RIGHT("→"),
    CTRL_C("Ctrl+C"),
    CTRL_D("Ctrl+D"),
    CTRL_L("Ctrl+L")
}

data class PanePilotSession(
    val name: String,
    val attachedClients: Int,
    val paneTitle: String,
    val currentCommand: String,
    val paneDead: Boolean,
    val terminalId: String,
    val projectId: String,
    val projectPath: String,
    val profile: String,
    val createdAt: String,
    val dangerousMode: Boolean,
    val sessionKind: String,
    val actionName: String?,
    val latexSectionTitle: String?,
    val state: SessionState
) {
    val projectName: String
        get() = projectPath.trimEnd('/').substringAfterLast('/').ifBlank { projectPath }

    val typeLabel: String
        get() = when (sessionKind) {
            "project-qna" -> "Project Q&A"
            "latex-chat" -> latexSectionTitle?.let { "LaTeX · $it" } ?: "LaTeX chat"
            "action" -> actionName?.let { "Action · $it" } ?: "Action"
            else -> profile.replaceFirstChar { it.uppercase() }
        }
}
