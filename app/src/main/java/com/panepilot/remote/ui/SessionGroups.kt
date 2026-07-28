package com.panepilot.remote.ui

import com.panepilot.remote.model.PanePilotSession
import com.panepilot.remote.model.SessionState
import com.panepilot.remote.model.TerminalSortMode

internal enum class SessionGroupKind {
    ATTENTION,
    PINNED,
    PROJECT,
    TERMINALS
}

internal data class SessionGroup(
    val key: String,
    val title: String,
    val subtitle: String,
    val kind: SessionGroupKind,
    val sessions: List<PanePilotSession>
)

internal fun sessionGroups(
    sessions: List<PanePilotSession>,
    pinnedTerminalIds: Set<String>,
    unreadAttentionTerminalIds: Set<String>,
    sortMode: TerminalSortMode
): List<SessionGroup> {
    val attentionIds = sessions
        .filter {
            it.state == SessionState.NEEDS_INPUT ||
                it.terminalId in unreadAttentionTerminalIds
        }
        .mapTo(linkedSetOf()) { it.terminalId }
    val attention = sessions
        .filter { it.terminalId in attentionIds }
        .sortedWith(sessionComparator(sortMode))
    val pinned = sessions
        .filter {
            it.terminalId in pinnedTerminalIds &&
                it.terminalId !in attentionIds
        }
        .sortedWith(sessionComparator(sortMode))
    val promotedIds = attentionIds + pinned.map { it.terminalId }
    val remaining = sessions.filterNot { it.terminalId in promotedIds }

    return buildList {
        if (attention.isNotEmpty()) {
            add(
                SessionGroup(
                    key = "attention",
                    title = "Needs attention",
                    subtitle = "UNREAD AGENT ALERTS",
                    kind = SessionGroupKind.ATTENTION,
                    sessions = attention
                )
            )
        }
        if (pinned.isNotEmpty()) {
            add(
                SessionGroup(
                    key = "pinned",
                    title = "Pinned",
                    subtitle = "QUICK ACCESS",
                    kind = SessionGroupKind.PINNED,
                    sessions = pinned
                )
            )
        }
        if (sortMode == TerminalSortMode.PROJECT) {
            remaining
                .groupBy { it.projectPath }
                .entries
                .sortedBy { (_, projectSessions) ->
                    projectSessions.first().projectName.lowercase()
                }
                .forEach { (path, projectSessions) ->
                    add(
                        SessionGroup(
                            key = "project:$path",
                            title = projectSessions.first().projectName,
                            subtitle = path,
                            kind = SessionGroupKind.PROJECT,
                            sessions = projectSessions.sortedWith(
                                sessionComparator(TerminalSortMode.NAME)
                            )
                        )
                    )
                }
        } else if (remaining.isNotEmpty()) {
            add(
                SessionGroup(
                    key = "terminals",
                    title = "Terminals",
                    subtitle = "SORTED BY ${sortMode.label.uppercase()}",
                    kind = SessionGroupKind.TERMINALS,
                    sessions = remaining.sortedWith(sessionComparator(sortMode))
                )
            )
        }
    }
}

private fun sessionComparator(sortMode: TerminalSortMode): Comparator<PanePilotSession> =
    when (sortMode) {
        TerminalSortMode.ACTIVITY -> compareBy<PanePilotSession>(
            { statePriority(it.state) },
            { it.name.lowercase() }
        )

        TerminalSortMode.NAME -> compareBy { it.name.lowercase() }
        TerminalSortMode.NEWEST -> compareByDescending<PanePilotSession> {
            it.createdAt
        }.thenBy { it.name.lowercase() }

        TerminalSortMode.PROJECT -> compareBy<PanePilotSession>(
            { it.projectName.lowercase() },
            { it.name.lowercase() }
        )
    }

private fun statePriority(state: SessionState): Int = when (state) {
    SessionState.NEEDS_INPUT -> 0
    SessionState.RUNNING -> 1
    SessionState.READY -> 2
    SessionState.IDLE -> 3
    SessionState.LIVE -> 4
    SessionState.STOPPED -> 5
}
