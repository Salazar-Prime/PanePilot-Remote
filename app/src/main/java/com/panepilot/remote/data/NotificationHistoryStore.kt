package com.panepilot.remote.data

import android.content.Context
import com.panepilot.remote.model.ServerProfile
import com.panepilot.remote.notifications.AttentionEvent
import com.panepilot.remote.notifications.AttentionEventType
import com.panepilot.remote.notifications.NotificationHistoryEntry
import com.panepilot.remote.notifications.appendNotificationHistory
import org.json.JSONArray
import org.json.JSONObject

class NotificationHistoryStore(context: Context) {
    private val preferences =
        context.getSharedPreferences("panepilot_remote_notification_history", Context.MODE_PRIVATE)

    fun load(): List<NotificationHistoryEntry> = synchronized(LOCK) {
        loadLocked()
    }

    fun record(
        profile: ServerProfile,
        event: AttentionEvent,
        sentAtMillis: Long = System.currentTimeMillis()
    ): List<NotificationHistoryEntry> = synchronized(LOCK) {
        val session = event.session
        val entry = NotificationHistoryEntry(
            id = "$sentAtMillis:${profile.id}:${session.terminalId}:${event.type.name}",
            sentAtMillis = sentAtMillis,
            profileId = profile.id,
            profileName = profile.name.take(MAX_LABEL_LENGTH),
            terminalId = session.terminalId,
            terminalName = session.name.take(MAX_LABEL_LENGTH),
            projectName = session.projectName.take(MAX_LABEL_LENGTH),
            paneTitle = session.paneTitle.take(MAX_PANE_TITLE_LENGTH),
            type = event.type
        )
        val updated = appendNotificationHistory(loadLocked(), entry)
        preferences.edit()
            .putString(HISTORY_KEY, encode(updated).toString())
            .commit()
        updated
    }

    private fun loadLocked(): List<NotificationHistoryEntry> {
        val raw = preferences.getString(HISTORY_KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until minOf(array.length(), MAX_HISTORY_ENTRIES)) {
                    decode(array.optJSONObject(index))?.let(::add)
                }
            }.sortedByDescending { it.sentAtMillis }
        }.getOrDefault(emptyList())
    }

    private fun encode(entries: List<NotificationHistoryEntry>): JSONArray =
        JSONArray().apply {
            entries.take(MAX_HISTORY_ENTRIES).forEach { entry ->
                put(
                    JSONObject()
                        .put("id", entry.id)
                        .put("sentAtMillis", entry.sentAtMillis)
                        .put("profileId", entry.profileId)
                        .put("profileName", entry.profileName)
                        .put("terminalId", entry.terminalId)
                        .put("terminalName", entry.terminalName)
                        .put("projectName", entry.projectName)
                        .put("paneTitle", entry.paneTitle)
                        .put("type", entry.type.name)
                )
            }
        }

    private fun decode(value: JSONObject?): NotificationHistoryEntry? {
        value ?: return null
        val sentAtMillis = value.optLong("sentAtMillis").takeIf { it > 0L } ?: return null
        val profileId = value.optString("profileId").takeIf { it.isNotBlank() } ?: return null
        val terminalId = value.optString("terminalId").takeIf { it.isNotBlank() } ?: return null
        val type = runCatching {
            AttentionEventType.valueOf(value.optString("type"))
        }.getOrNull() ?: return null
        return NotificationHistoryEntry(
            id = value.optString("id").takeIf { it.isNotBlank() }
                ?: "$sentAtMillis:$profileId:$terminalId:${type.name}",
            sentAtMillis = sentAtMillis,
            profileId = profileId,
            profileName = value.optString("profileName").take(MAX_LABEL_LENGTH),
            terminalId = terminalId,
            terminalName = value.optString("terminalName").take(MAX_LABEL_LENGTH),
            projectName = value.optString("projectName").take(MAX_LABEL_LENGTH),
            paneTitle = value.optString("paneTitle").take(MAX_PANE_TITLE_LENGTH),
            type = type
        )
    }

    private companion object {
        const val HISTORY_KEY = "sent_agent_notifications"
        const val MAX_HISTORY_ENTRIES = 200
        const val MAX_LABEL_LENGTH = 200
        const val MAX_PANE_TITLE_LENGTH = 1_024
        val LOCK = Any()
    }
}
