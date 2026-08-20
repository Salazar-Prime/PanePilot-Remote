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

    fun interactionTimes(profileId: String, terminalIds: Collection<String>): Map<String, Long> =
        terminalIds.associateWith { terminalId ->
            preferences.getLong(interactionKey(profileId, terminalId), 0L)
        }.filterValues { it > 0L }

    fun activityTimes(profileId: String, terminalIds: Collection<String>): Map<String, Long> =
        terminalIds.associateWith { terminalId ->
            preferences.getLong(activityKey(profileId, terminalId), 0L)
        }.filterValues { it > 0L }

    fun markInteraction(profileId: String, terminalId: String, atMillis: Long): Long {
        preferences.edit().putLong(interactionKey(profileId, terminalId), atMillis).apply()
        return atMillis
    }

    fun markActivity(profileId: String, terminalId: String, atMillis: Long): Long {
        preferences.edit().putLong(activityKey(profileId, terminalId), atMillis).apply()
        return atMillis
    }

    fun lastSelectedTerminalId(profileId: String): String? =
        preferences.getString(lastSelectedKey(profileId), null)

    fun setLastSelectedTerminalId(profileId: String, terminalId: String) {
        preferences.edit().putString(lastSelectedKey(profileId), terminalId).apply()
    }

    fun removeProfile(profileId: String) {
        val editor = preferences.edit()
            .remove(pinsKey(profileId))
            .remove(sortKey(profileId))
            .remove(lastSelectedKey(profileId))
        preferences.all.keys
            .filter {
                it.startsWith("icon:$profileId:") ||
                    it.startsWith("interaction:$profileId:") ||
                    it.startsWith("activity:$profileId:")
            }
            .forEach(editor::remove)
        editor.apply()
    }

    private fun pinsKey(profileId: String) = "pins:$profileId"
    private fun sortKey(profileId: String) = "sort:$profileId"
    private fun lastSelectedKey(profileId: String) = "last-selected:$profileId"
    private fun interactionKey(profileId: String, terminalId: String) =
        "interaction:$profileId:$terminalId"
    private fun activityKey(profileId: String, terminalId: String) =
        "activity:$profileId:$terminalId"
}
