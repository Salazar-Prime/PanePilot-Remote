package com.panepilot.remote.data

import android.content.Context

class AttentionPreferenceStore(context: Context) {
    private val preferences =
        context.getSharedPreferences("panepilot_remote_attention", Context.MODE_PRIVATE)

    fun enabledTerminalIds(profileId: String): Set<String> =
        preferences.getStringSet(key(profileId), emptySet()).orEmpty().toSet()

    fun setEnabled(profileId: String, terminalId: String, enabled: Boolean): Set<String> {
        val updated = enabledTerminalIds(profileId).toMutableSet().apply {
            if (enabled) add(terminalId) else remove(terminalId)
        }
        preferences.edit().putStringSet(key(profileId), updated).apply()
        return updated
    }

    fun removeProfile(profileId: String) {
        preferences.edit().remove(key(profileId)).apply()
    }

    private fun key(profileId: String) = "profile:$profileId"
}
