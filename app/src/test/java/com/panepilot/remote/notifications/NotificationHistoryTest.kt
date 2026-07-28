package com.panepilot.remote.notifications

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationHistoryTest {
    @Test
    fun storesNewestNotificationsFirst() {
        val history = appendNotificationHistory(
            existing = listOf(entry(id = "old", sentAt = 1_000L)),
            entry = entry(id = "new", sentAt = 10_000L)
        )

        assertEquals(listOf("new", "old"), history.map { it.id })
    }

    @Test
    fun collapsesDuplicateDeliveryFromConcurrentMonitors() {
        val original = entry(id = "first", sentAt = 10_000L)
        val history = appendNotificationHistory(
            existing = listOf(original),
            entry = entry(id = "duplicate", sentAt = 16_000L)
        )

        assertEquals(listOf(original), history)
    }

    @Test
    fun keepsDifferentAlertTypesAndLaterAgentTurns() {
        val history = appendNotificationHistory(
            existing = listOf(entry(id = "ready-1", sentAt = 10_000L)),
            entry = entry(
                id = "input",
                sentAt = 11_000L,
                type = AttentionEventType.NEEDS_INPUT
            )
        )
        val withLaterTurn = appendNotificationHistory(
            existing = history,
            entry = entry(id = "ready-2", sentAt = 20_000L)
        )

        assertEquals(listOf("ready-2", "input", "ready-1"), withLaterTurn.map { it.id })
    }

    @Test
    fun appliesConfiguredHistoryLimit() {
        val existing = (1L..5L).map { entry(id = "entry-$it", sentAt = it) }
        val history = appendNotificationHistory(
            existing = existing,
            entry = entry(id = "new", sentAt = 10L),
            limit = 3,
            duplicateWindowMillis = 0L
        )

        assertEquals(listOf("new", "entry-5", "entry-4"), history.map { it.id })
    }

    private fun entry(
        id: String,
        sentAt: Long,
        type: AttentionEventType = AttentionEventType.RESPONSE_READY
    ) = NotificationHistoryEntry(
        id = id,
        sentAtMillis = sentAt,
        profileId = "profile",
        profileName = "Server",
        terminalId = "terminal",
        terminalName = "Terminal",
        projectName = "Project",
        paneTitle = "Ready",
        type = type
    )
}
