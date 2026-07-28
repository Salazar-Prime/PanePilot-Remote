package com.panepilot.remote.data

import android.content.Context
import com.panepilot.remote.model.SessionState
import org.json.JSONObject

class SessionStateStore(context: Context) {
    private val preferences =
        context.getSharedPreferences("panepilot_remote_session_states", Context.MODE_PRIVATE)

    fun load(profileId: String): Map<String, SessionState> {
        val raw = preferences.getString(key(profileId), null) ?: return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            buildMap {
                json.keys().asSequence().take(MAX_SESSIONS).forEach { terminalId ->
                    runCatching { SessionState.valueOf(json.getString(terminalId)) }
                        .getOrNull()
                        ?.let { put(terminalId, it) }
                }
            }
        }.getOrDefault(emptyMap())
    }

    fun save(profileId: String, states: Map<String, SessionState>) {
        val json = JSONObject()
        states.entries.take(MAX_SESSIONS).forEach { (terminalId, state) ->
            json.put(terminalId, state.name)
        }
        preferences.edit().putString(key(profileId), json.toString()).apply()
    }

    fun removeProfile(profileId: String) {
        preferences.edit().remove(key(profileId)).apply()
    }

    private fun key(profileId: String) = "profile:$profileId"

    private companion object {
        const val MAX_SESSIONS = 1_000
    }
}
