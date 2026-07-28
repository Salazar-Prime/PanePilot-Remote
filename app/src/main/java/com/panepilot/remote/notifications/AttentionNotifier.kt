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
import com.panepilot.remote.model.PanePilotSession
import com.panepilot.remote.model.ServerProfile

class AttentionNotifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Agent attention",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Agent responses and terminal sessions that need your input"
                enableVibration(true)
                vibrationPattern = longArrayOf(0L, 180L, 100L, 240L)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    Notification.AUDIO_ATTRIBUTES_DEFAULT
                )
            }
        )
        manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
    }

    fun show(profile: ServerProfile, event: AttentionEvent): Boolean {
        if (!canNotify()) return false
        val session = event.session
        val openApp = PendingIntent.getActivity(
            context,
            notificationId(profile.id, session.terminalId),
            Intent(context, MainActivity::class.java).apply {
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
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(
                when (event.type) {
                    AttentionEventType.NEEDS_INPUT -> "${session.name} needs your input"
                    AttentionEventType.RESPONSE_READY -> "${session.name} finished"
                }
            )
            .setContentText(detail)
            .setStyle(Notification.BigTextStyle().bigText(detail))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .build()
        return runCatching {
            manager.notify(notificationId(profile.id, session.terminalId), notification)
            true
        }.getOrDefault(false)
    }

    fun showTest(profile: ServerProfile): Boolean {
        if (!canNotify()) return false
        val openApp = PendingIntent.getActivity(
            context,
            TEST_NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("PanePilot alerts are working")
            .setContentText("Agent alerts are enabled for ${profile.name}.")
            .setCategory(Notification.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .setTimeoutAfter(15_000L)
            .build()
        return runCatching {
            manager.notify(TEST_NOTIFICATION_ID, notification)
            true
        }.getOrDefault(false)
    }

    fun cancel(profileId: String, terminalId: String) {
        manager.cancel(notificationId(profileId, terminalId))
    }

    fun cancelProfile(profileId: String, terminalIds: Collection<String>) {
        terminalIds.forEach { terminalId -> cancel(profileId, terminalId) }
    }

    private fun canNotify(): Boolean {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(CHANNEL_ID)?.importance !=
            NotificationManager.IMPORTANCE_NONE
    }

    companion object {
        const val EXTRA_PROFILE_ID = "attention_profile_id"
        const val EXTRA_TERMINAL_ID = "attention_terminal_id"
        private const val CHANNEL_ID = "agent-attention-v2"
        private const val LEGACY_CHANNEL_ID = "agent-attention"
        private const val TEST_NOTIFICATION_ID = 8_422

        internal fun notificationId(profileId: String, terminalId: String): Int =
            "$profileId:$terminalId".hashCode()
    }
}
