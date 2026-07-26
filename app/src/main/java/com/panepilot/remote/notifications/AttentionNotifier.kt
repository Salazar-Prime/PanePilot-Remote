package com.panepilot.remote.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
                description = "PanePilot terminal sessions that need your input"
            }
        )
    }

    fun show(profile: ServerProfile, session: PanePilotSession) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val openApp = PendingIntent.getActivity(
            context,
            notificationId(profile.id, session.terminalId),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val detail = buildString {
            append(session.projectName)
            session.paneTitle.takeIf { it.isNotBlank() }?.let {
                append(" · ")
                append(it)
            }
        }
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("${session.name} needs attention")
            .setContentText(detail)
            .setStyle(Notification.BigTextStyle().bigText(detail))
            .setCategory(Notification.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .build()
        runCatching {
            manager.notify(notificationId(profile.id, session.terminalId), notification)
        }
    }

    fun cancel(profileId: String, terminalId: String) {
        manager.cancel(notificationId(profileId, terminalId))
    }

    fun cancelProfile(profileId: String, terminalIds: Collection<String>) {
        terminalIds.forEach { terminalId -> cancel(profileId, terminalId) }
    }

    companion object {
        private const val CHANNEL_ID = "agent-attention"

        internal fun notificationId(profileId: String, terminalId: String): Int =
            "$profileId:$terminalId".hashCode()
    }
}
