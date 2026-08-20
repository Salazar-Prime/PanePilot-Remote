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
    sortMode: TerminalSortMode,
    interactionTimes: Map<String, Long> = emptyMap(),
    activityTimes: Map<String, Long> = emptyMap()
): List<SessionGroup> {
    val activeSessions = sessions.filter { it.isActiveNavigationSession }
    val attentionIds = activeSessions
        .filter {
            it.state == SessionState.NEEDS_INPUT ||
                it.terminalId in unreadAttentionTerminalIds
        }
        .mapTo(linkedSetOf()) { it.terminalId }
    val comparator = sessionComparator(
        sortMode,
        interactionTimes,
        activityTimes,
        attentionIds
    )
    val pinned = activeSessions
        .filter { it.terminalId in pinnedTerminalIds }
        .sortedWith(comparator)
    val unpinned = activeSessions.filterNot { it.terminalId in pinnedTerminalIds }
    val attention = if (sortMode == TerminalSortMode.ACTIVITY) {
        unpinned.filter { it.terminalId in attentionIds }.sortedWith(comparator)
    } else {
        emptyList()
    }
    val promotedIds = pinned.mapTo(mutableSetOf()) { it.terminalId } +
        attention.map { it.terminalId }
    val remaining = activeSessions.filterNot { it.terminalId in promotedIds }

    return buildList {
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
                                sessionComparator(
                                    TerminalSortMode.NAME,
                                    interactionTimes,
                                    activityTimes,
                                    attentionIds
                                )
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
                    sessions = remaining.sortedWith(
                        comparator
                    )
                )
            )
        }
    }
}

private fun sessionComparator(
    sortMode: TerminalSortMode,
    interactionTimes: Map<String, Long>,
    activityTimes: Map<String, Long>,
    attentionIds: Set<String>
): Comparator<PanePilotSession> =
    when (sortMode) {
        TerminalSortMode.ACTIVITY -> compareBy<PanePilotSession> {
            activityPriority(it, attentionIds)
        }.thenByDescending {
            activityTimes[it.terminalId] ?: createdAtMillis(it.createdAt)
        }.thenBy { it.name.lowercase() }

        TerminalSortMode.NAME -> compareBy { it.name.lowercase() }
        TerminalSortMode.NEWEST -> compareByDescending<PanePilotSession> {
            interactionTimes[it.terminalId] ?: createdAtMillis(it.createdAt)
        }.thenBy { it.name.lowercase() }

        TerminalSortMode.PROJECT -> compareBy<PanePilotSession>(
            { it.projectName.lowercase() },
            { it.name.lowercase() }
        )
    }

private fun activityPriority(
    session: PanePilotSession,
    attentionIds: Set<String>
): Int = when {
    session.terminalId in attentionIds -> 0
    session.state == SessionState.RUNNING -> 1
    session.state == SessionState.READY -> 2
    session.state == SessionState.IDLE -> 3
    session.state == SessionState.LIVE -> 4
    else -> 5
}

private fun createdAtMillis(value: String): Long =
    runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrDefault(0L)
