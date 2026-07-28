package com.panepilot.remote.data

import android.content.Context

class AttentionStateStore(context: Context) {
    private val preferences =
        context.getSharedPreferences("panepilot_remote_attention_state", Context.MODE_PRIVATE)

    fun unreadTerminalIds(profileId: String): Set<String> =
        preferences.getStringSet(key(profileId), emptySet()).orEmpty().toSet()

    fun markUnread(profileId: String, terminalId: String): Set<String> =
        update(profileId) { add(terminalId) }

    fun markRead(profileId: String, terminalId: String): Set<String> =
        update(profileId) { remove(terminalId) }

    fun markRead(profileId: String, terminalIds: Collection<String>): Set<String> =
        update(profileId) { removeAll(terminalIds.toSet()) }

    fun removeProfile(profileId: String) {
        preferences.edit().remove(key(profileId)).apply()
    }

    private fun update(
        profileId: String,
        change: MutableSet<String>.() -> Unit
    ): Set<String> {
        val updated = unreadTerminalIds(profileId).toMutableSet().apply(change)
        preferences.edit().putStringSet(key(profileId), updated).apply()
        return updated
    }

    private fun key(profileId: String) = "profile:$profileId"
}
