package com.panepilot.remote.notifications

import com.panepilot.remote.model.PanePilotSession
import com.panepilot.remote.model.SessionState

internal fun newlyAttentionRequired(
    previousStates: Map<String, SessionState>,
    sessions: List<PanePilotSession>
): List<PanePilotSession> =
    sessions.filter { session ->
        session.state == SessionState.NEEDS_INPUT &&
            previousStates[session.terminalId] != SessionState.NEEDS_INPUT
    }
