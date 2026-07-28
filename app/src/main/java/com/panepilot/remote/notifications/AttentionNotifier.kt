package com.panepilot.remote.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.os.Build
import com.panepilot.remote.MainActivity
import com.panepilot.remote.R
import com.panepilot.remote.data.NotificationHistoryStore
import com.panepilot.remote.model.PanePilotSession
import com.panepilot.remote.model.ServerProfile

class AttentionNotifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val history = NotificationHistoryStore(context)

    init {
        NotificationGroups.ensureCreated(manager)
        manager.createNotificationChannels(
            listOf(
                agentChannel(
                    id = ATTENTION_CHANNEL_ID,
                    name = "Agent needs attention",
                    description = "Agent questions, permission prompts, and requests for input"
                ),
                agentChannel(
                    id = COMPLETION_CHANNEL_ID,
                    name = "Agent completions",
                    description = "Completed agent responses"
                ),
                NotificationChannel(
                    SUMMARY_CHANNEL_ID,
                    "Agent alert summary",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Groups active agent alerts separately from connection status"
                    group = NotificationGroups.AGENT_CHANNEL_GROUP_ID
                    enableVibration(false)
                    setSound(null, null)
                    setShowBadge(false)
                }
            )
        )
        LEGACY_CHANNEL_IDS.forEach(manager::deleteNotificationChannel)
    }

    fun show(profile: ServerProfile, event: AttentionEvent): Boolean {
        val channelId = channelIdFor(event.type)
        if (!canNotify(channelId)) return false
        val session = event.session
        val id = notificationId(profile.id, session.terminalId, event.type)
        val openApp = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java).apply {
                action =
                    "$ACTION_OPEN_TERMINAL:${profile.id}:${session.terminalId}:${event.type.name}"
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_PROFILE_ID, profile.id)
                putExtra(EXTRA_TERMINAL_ID, session.terminalId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val detail = buildString {
            append(profile.name)
            append(" · ")
            append(session.projectName)
            session.paneTitle.takeIf { it.isNotBlank() }?.let {
                append(" · ")
                append(it)
            }
        }
        val notification = Notification.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(notificationTitle(session, event.type))
            .setContentText(detail)
            .setStyle(Notification.BigTextStyle().bigText(detail))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setGroup(NotificationGroups.AGENT_SHADE_GROUP_KEY)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .build()
        return runCatching {
            manager.notify(id, notification)
            updateAgentSummary()
            history.record(profile, event)
            true
        }.getOrDefault(false)
    }

    fun cancel(profileId: String, terminalId: String) {
        val ids = AttentionEventType.entries.mapTo(linkedSetOf()) {
            notificationId(profileId, terminalId, it)
        }
        ids.forEach { manager.cancel(it) }
        updateAgentSummary(excludedNotificationIds = ids)
    }

    fun cancelProfile(profileId: String, terminalIds: Collection<String>) {
        val ids = terminalIds.flatMapTo(linkedSetOf()) { terminalId ->
            AttentionEventType.entries.map {
                notificationId(profileId, terminalId, it)
            }
        }
        ids.forEach { manager.cancel(it) }
        updateAgentSummary(excludedNotificationIds = ids)
    }

    private fun agentChannel(
        id: String,
        name: String,
        description: String
    ): NotificationChannel =
        NotificationChannel(
            id,
            name,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            this.description = description
            group = NotificationGroups.AGENT_CHANNEL_GROUP_ID
            enableVibration(true)
            vibrationPattern = longArrayOf(0L, 180L, 100L, 240L)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                Notification.AUDIO_ATTRIBUTES_DEFAULT
            )
        }

    private fun canNotify(channelId: String): Boolean {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(channelId)?.importance !=
            NotificationManager.IMPORTANCE_NONE
    }

    private fun updateAgentSummary(
        excludedNotificationIds: Set<Int> = emptySet()
    ) {
        val activeAgentNotifications = manager.activeNotifications.filter {
            it.id !in excludedNotificationIds &&
                it.notification.group == NotificationGroups.AGENT_SHADE_GROUP_KEY &&
                it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0
        }
        if (activeAgentNotifications.isEmpty()) {
            manager.cancel(AGENT_SUMMARY_NOTIFICATION_ID)
            return
        }
        val attentionCount = activeAgentNotifications.count {
            it.notification.channelId == ATTENTION_CHANNEL_ID
        }
        val completionCount = activeAgentNotifications.count {
            it.notification.channelId == COMPLETION_CHANNEL_ID
        }
        val detail = buildList {
            if (attentionCount > 0) {
                add(
                    "$attentionCount terminal${if (attentionCount == 1) "" else "s"} " +
                        "need${if (attentionCount == 1) "s" else ""} input"
                )
            }
            if (completionCount > 0) {
                add("$completionCount response${if (completionCount == 1) "" else "s"} finished")
            }
        }.joinToString(" · ")
        val openApp = PendingIntent.getActivity(
            context,
            AGENT_SUMMARY_NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val summary = Notification.Builder(context, SUMMARY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Agent activity")
            .setContentText(detail)
            .setCategory(Notification.CATEGORY_STATUS)
            .setGroup(NotificationGroups.AGENT_SHADE_GROUP_KEY)
            .setGroupSummary(true)
            .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .build()
        manager.notify(AGENT_SUMMARY_NOTIFICATION_ID, summary)
    }

    companion object {
        const val EXTRA_PROFILE_ID = "attention_profile_id"
        const val EXTRA_TERMINAL_ID = "attention_terminal_id"
        private const val ACTION_OPEN_TERMINAL =
            "com.panepilot.remote.action.OPEN_AGENT_TERMINAL"
        private const val ATTENTION_CHANNEL_ID = "agent-attention-v3"
        private const val COMPLETION_CHANNEL_ID = "agent-completions-v1"
        private const val SUMMARY_CHANNEL_ID = "agent-summary-v1"
        private const val AGENT_SUMMARY_NOTIFICATION_ID = 8_423
        private val LEGACY_CHANNEL_IDS = listOf("agent-attention", "agent-attention-v2")

        internal fun notificationId(
            profileId: String,
            terminalId: String,
            type: AttentionEventType
        ): Int = "$profileId:$terminalId:${type.name}".hashCode()

        internal fun channelIdFor(type: AttentionEventType): String = when (type) {
            AttentionEventType.NEEDS_INPUT -> ATTENTION_CHANNEL_ID
            AttentionEventType.RESPONSE_READY -> COMPLETION_CHANNEL_ID
        }

        internal fun notificationTitle(
            session: PanePilotSession,
            type: AttentionEventType
        ): String = when (type) {
            AttentionEventType.NEEDS_INPUT -> "${session.name} needs your input"
            AttentionEventType.RESPONSE_READY -> "${session.name} finished"
        }
    }
}
