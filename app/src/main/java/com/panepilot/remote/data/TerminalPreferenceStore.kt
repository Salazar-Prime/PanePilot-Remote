package com.panepilot.remote.data

import android.content.Context
import com.panepilot.remote.model.TerminalSortMode

class TerminalPreferenceStore(context: Context) {
    private val preferences =
        context.getSharedPreferences("panepilot_remote_terminal_preferences", Context.MODE_PRIVATE)

    fun pinnedTerminalIds(profileId: String): Set<String> =
        preferences.getStringSet(pinsKey(profileId), emptySet()).orEmpty().toSet()

    fun setPinned(profileId: String, terminalId: String, pinned: Boolean): Set<String> {
        val updated = pinnedTerminalIds(profileId).toMutableSet().apply {
            if (pinned) add(terminalId) else remove(terminalId)
        }
        preferences.edit().putStringSet(pinsKey(profileId), updated).apply()
        return updated
    }

    fun sortMode(profileId: String): TerminalSortMode =
        preferences.getString(sortKey(profileId), null)
            ?.let { runCatching { TerminalSortMode.valueOf(it) }.getOrNull() }
            ?: TerminalSortMode.ACTIVITY

    fun setSortMode(profileId: String, sortMode: TerminalSortMode) {
        preferences.edit().putString(sortKey(profileId), sortMode.name).apply()
    }

    fun removeProfile(profileId: String) {
        preferences.edit()
            .remove(pinsKey(profileId))
            .remove(sortKey(profileId))
            .apply()
    }

    private fun pinsKey(profileId: String) = "pins:$profileId"
    private fun sortKey(profileId: String) = "sort:$profileId"
}
