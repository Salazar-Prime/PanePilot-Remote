package com.panepilot.remote.ui

import com.panepilot.remote.model.PanePilotSession
import com.panepilot.remote.model.SessionState

internal data class SessionGroup(
    val key: String,
    val title: String,
    val path: String?,
    val needsAttention: Boolean,
    val sessions: List<PanePilotSession>
)

internal fun sessionGroups(sessions: List<PanePilotSession>): List<SessionGroup> {
    val attention = sessions.filter { it.state == SessionState.NEEDS_INPUT }
    val projectGroups = sessions
        .filterNot { it.state == SessionState.NEEDS_INPUT }
        .groupBy { it.projectPath }
        .map { (path, projectSessions) ->
            SessionGroup(
                key = "project:$path",
                title = projectSessions.first().projectName,
                path = path,
                needsAttention = false,
                sessions = projectSessions
            )
        }
    return buildList {
        if (attention.isNotEmpty()) {
            add(
                SessionGroup(
                    key = "attention",
                    title = "Needs attention",
                    path = null,
                    needsAttention = true,
                    sessions = attention
                )
            )
        }
        addAll(projectGroups)
    }
}
