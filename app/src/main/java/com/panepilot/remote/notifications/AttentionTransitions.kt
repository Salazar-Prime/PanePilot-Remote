package com.panepilot.remote.notifications

import com.panepilot.remote.model.PanePilotSession
import com.panepilot.remote.model.SessionState

enum class AttentionEventType {
    NEEDS_INPUT,
    RESPONSE_READY
}

data class AttentionEvent(
    val session: PanePilotSession,
    val type: AttentionEventType
)

internal fun newAttentionEvents(
    previousStates: Map<String, SessionState>,
    sessions: List<PanePilotSession>
): List<AttentionEvent> =
    sessions.mapNotNull { session ->
        val previous = previousStates[session.terminalId]
        when {
            session.state == SessionState.NEEDS_INPUT &&
                previous != SessionState.NEEDS_INPUT ->
                AttentionEvent(session, AttentionEventType.NEEDS_INPUT)

            session.state == SessionState.READY &&
                previous == SessionState.RUNNING ->
                AttentionEvent(session, AttentionEventType.RESPONSE_READY)

            else -> null
        }
    }

internal fun noLongerNeedsAttention(
    sessions: List<PanePilotSession>
): List<PanePilotSession> =
    sessions.filter {
        it.state in setOf(
            SessionState.RUNNING,
            SessionState.IDLE,
            SessionState.LIVE,
            SessionState.STOPPED
        )
    }
