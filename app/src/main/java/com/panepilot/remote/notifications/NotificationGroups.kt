package com.panepilot.remote.notifications

import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.os.Build

object NotificationGroups {
    const val AGENT_CHANNEL_GROUP_ID = "agent-activity"
    const val CONNECTION_CHANNEL_GROUP_ID = "connection-status"
    const val AGENT_SHADE_GROUP_KEY = "com.panepilot.remote.group.AGENT_ACTIVITY"
    const val CONNECTION_SHADE_GROUP_KEY = "com.panepilot.remote.group.CONNECTION_STATUS"

    fun ensureCreated(manager: NotificationManager) {
        val agentGroup = NotificationChannelGroup(
            AGENT_CHANNEL_GROUP_ID,
            "Agent activity"
        )
        val connectionGroup = NotificationChannelGroup(
            CONNECTION_CHANNEL_GROUP_ID,
            "Connection status"
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            agentGroup.description = "Agent completions and requests for your attention"
            connectionGroup.description = "Persistent SSH background-monitoring status"
        }
        manager.createNotificationChannelGroups(listOf(agentGroup, connectionGroup))
    }
}
