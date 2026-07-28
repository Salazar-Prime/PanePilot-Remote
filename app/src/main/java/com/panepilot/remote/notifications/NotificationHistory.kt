package com.panepilot.remote.notifications

data class NotificationHistoryEntry(
    val id: String,
    val sentAtMillis: Long,
    val profileId: String,
    val profileName: String,
    val terminalId: String,
    val terminalName: String,
    val projectName: String,
    val paneTitle: String,
    val type: AttentionEventType
) {
    val title: String
        get() = when (type) {
            AttentionEventType.NEEDS_INPUT -> "$terminalName needs your input"
            AttentionEventType.RESPONSE_READY -> "$terminalName finished"
        }
}

internal fun appendNotificationHistory(
    existing: List<NotificationHistoryEntry>,
    entry: NotificationHistoryEntry,
    limit: Int = 200,
    duplicateWindowMillis: Long = 8_000L
): List<NotificationHistoryEntry> {
    val duplicate = existing.any {
        it.profileId == entry.profileId &&
            it.terminalId == entry.terminalId &&
            it.type == entry.type &&
            entry.sentAtMillis - it.sentAtMillis in 0..duplicateWindowMillis
    }
    if (duplicate) return existing
    return (existing + entry)
        .sortedByDescending { it.sentAtMillis }
        .take(limit)
}
