package com.panepilot.remote.monitoring

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.panepilot.remote.MainActivity
import com.panepilot.remote.R
import com.panepilot.remote.data.AttentionPreferenceStore
import com.panepilot.remote.data.BackgroundMonitorStore
import com.panepilot.remote.data.CredentialStore
import com.panepilot.remote.data.ProfileStore
import com.panepilot.remote.model.AuthMode
import com.panepilot.remote.model.ConnectionSecret
import com.panepilot.remote.model.SessionState
import com.panepilot.remote.notifications.AttentionNotifier
import com.panepilot.remote.notifications.newlyAttentionRequired
import com.panepilot.remote.ssh.SshConnection
import com.panepilot.remote.ssh.TmuxGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

class AgentMonitorService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val liveSecret = AtomicReference<ConnectionSecret?>(null)

    private lateinit var profiles: ProfileStore
    private lateinit var credentials: CredentialStore
    private lateinit var attentionPreferences: AttentionPreferenceStore
    private lateinit var monitorStore: BackgroundMonitorStore
    private lateinit var attentionNotifier: AttentionNotifier
    private lateinit var notificationManager: NotificationManager
    private lateinit var ssh: SshConnection
    private lateinit var tmux: TmuxGateway

    private var monitorJob: Job? = null
    private var activeProfileId: String? = null

    override fun onCreate() {
        super.onCreate()
        profiles = ProfileStore(this)
        credentials = CredentialStore(this)
        attentionPreferences = AttentionPreferenceStore(this)
        monitorStore = BackgroundMonitorStore(this)
        attentionNotifier = AttentionNotifier(this)
        notificationManager = getSystemService(NotificationManager::class.java)
        ssh = SshConnection(this, profiles) { false }
        tmux = TmuxGateway(ssh)
        createMonitoringChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            val profileId = activeProfileId ?: monitorStore.activeProfileId()
            profileId?.let(attentionPreferences::removeProfile)
            stopMonitoring(clearConfiguration = true)
            return START_NOT_STICKY
        }

        val requestedProfileId =
            intent?.getStringExtra(EXTRA_PROFILE_ID) ?: monitorStore.activeProfileId()
        if (requestedProfileId.isNullOrBlank()) {
            stopMonitoring(clearConfiguration = true)
            return START_NOT_STICKY
        }

        val profileChanged = activeProfileId != requestedProfileId
        if (profileChanged) {
            monitorJob?.cancel()
            ssh.disconnect()
            tmux.reset()
            liveSecret.set(null)
        }
        if (intent?.action == ACTION_START) {
            val secret = ConnectionSecret(
                password = intent.getStringExtra(EXTRA_PASSWORD).orEmpty(),
                keyPassphrase = intent.getStringExtra(EXTRA_KEY_PASSPHRASE).orEmpty()
            )
            liveSecret.set(secret)
            processSecret.set(requestedProfileId to secret)
        }

        monitorStore.setActiveProfile(requestedProfileId)
        startAsForeground(
            title = "Starting background monitoring",
            detail = "Connecting to the SSH server…"
        )
        if (profileChanged || monitorJob?.isActive != true) {
            activeProfileId = requestedProfileId
            startMonitor(requestedProfileId)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        monitorJob?.cancel()
        serviceScope.cancel()
        ssh.disconnect()
        liveSecret.set(null)
        super.onDestroy()
    }

    private fun startMonitor(profileId: String) {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            var previousStates: Map<String, SessionState> = emptyMap()
            var failedAttempts = 0

            while (isActive && monitorStore.activeProfileId() == profileId) {
                val profile = profiles.load().firstOrNull { it.id == profileId }
                if (profile == null) {
                    stopMonitoring(clearConfiguration = true)
                    return@launch
                }
                val enabledIds = attentionPreferences.enabledTerminalIds(profileId)

                try {
                    if (!ssh.isConnected) {
                        val secret = connectionSecret(profile.authMode, profileId)
                        if (secret == null) {
                            updateForeground(
                                title = "Background monitoring paused",
                                detail = "Open PanePilot and reconnect to unlock ${profile.name}."
                            )
                            delay(PAUSED_RETRY_MS)
                            continue
                        }
                        processSecret.set(profileId to secret)
                        updateForeground(
                            title = "Reconnecting to ${profile.name}",
                            detail = "Restoring SSH monitoring…"
                        )
                        ssh.connect(profile, secret)
                        tmux.reset()
                        previousStates = emptyMap()
                    }

                    val sessions = tmux.listSessions()
                    val currentStates = sessions.associate { it.terminalId to it.state }
                    newlyAttentionRequired(previousStates, sessions)
                        .filter { it.terminalId in enabledIds }
                        .forEach { attentionNotifier.show(profile, it) }
                    sessions
                        .filter {
                            previousStates[it.terminalId] == SessionState.NEEDS_INPUT &&
                                it.state != SessionState.NEEDS_INPUT
                        }
                        .forEach {
                            attentionNotifier.cancel(profileId, it.terminalId)
                        }
                    previousStates = currentStates
                    failedAttempts = 0
                    updateForeground(
                        title = "Monitoring ${profile.name}",
                        detail = monitoredTerminalLabel(enabledIds.size)
                    )
                    delay(POLL_INTERVAL_MS)
                } catch (_: Exception) {
                    ssh.disconnect()
                    tmux.reset()
                    previousStates = emptyMap()
                    failedAttempts += 1
                    val retryDelay = reconnectDelay(failedAttempts)
                    updateForeground(
                        title = "Reconnecting to ${profile.name}",
                        detail = "SSH is offline · retrying in ${retryDelay / 1_000}s"
                    )
                    delay(retryDelay)
                }
            }
        }
    }

    private fun connectionSecret(authMode: AuthMode, profileId: String): ConnectionSecret? {
        liveSecret.get()?.let { secret ->
            when (authMode) {
                AuthMode.PASSWORD -> if (secret.password.isNotEmpty()) return secret
                AuthMode.PRIVATE_KEY -> return secret
            }
        }
        return when (authMode) {
            AuthMode.PASSWORD -> credentials.password(profileId)?.let {
                ConnectionSecret(password = it)
            }

            AuthMode.PRIVATE_KEY -> ConnectionSecret()
        }?.also { processSecret.set(profileId to it) }
    }

    private fun createMonitoringChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                MONITOR_CHANNEL_ID,
                "Background monitoring",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps SSH agent-attention monitoring active"
                setShowBadge(false)
            }
        )
    }

    private fun startAsForeground(title: String, detail: String) {
        val notification = monitoringNotification(title, detail)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                MONITOR_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(MONITOR_NOTIFICATION_ID, notification)
        }
    }

    private fun updateForeground(title: String, detail: String) {
        notificationManager.notify(
            MONITOR_NOTIFICATION_ID,
            monitoringNotification(title, detail)
        )
    }

    private fun monitoringNotification(title: String, detail: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            MONITOR_NOTIFICATION_ID,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopMonitoring = PendingIntent.getService(
            this,
            MONITOR_NOTIFICATION_ID,
            Intent(this, AgentMonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, MONITOR_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(detail)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .addAction(
                Notification.Action.Builder(
                    null,
                    "Stop",
                    stopMonitoring
                ).build()
            )
            .build()
    }

    private fun stopMonitoring(clearConfiguration: Boolean) {
        if (clearConfiguration) {
            monitorStore.clear(activeProfileId)
        }
        monitorJob?.cancel()
        monitorJob = null
        ssh.disconnect()
        liveSecret.set(null)
        clearProcessSecret(activeProfileId)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val ACTION_START =
            "com.panepilot.remote.action.START_BACKGROUND_MONITOR"
        private const val ACTION_STOP =
            "com.panepilot.remote.action.STOP_BACKGROUND_MONITOR"
        private const val EXTRA_PROFILE_ID = "profile_id"
        private const val EXTRA_PASSWORD = "password"
        private const val EXTRA_KEY_PASSPHRASE = "key_passphrase"
        private const val MONITOR_CHANNEL_ID = "background-monitor"
        private const val MONITOR_NOTIFICATION_ID = 8_421
        private const val POLL_INTERVAL_MS = 6_000L
        private const val PAUSED_RETRY_MS = 30_000L
        private const val MAX_RECONNECT_DELAY_MS = 60_000L
        private val processSecret =
            AtomicReference<Pair<String, ConnectionSecret>?>(null)

        fun start(
            context: Context,
            profileId: String,
            secret: ConnectionSecret
        ) {
            val intent = Intent(context, AgentMonitorService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PROFILE_ID, profileId)
                .putExtra(EXTRA_PASSWORD, secret.password)
                .putExtra(EXTRA_KEY_PASSPHRASE, secret.keyPassphrase)
            context.startForegroundService(intent)
        }

        fun stop(context: Context, profileId: String? = null) {
            val store = BackgroundMonitorStore(context)
            if (profileId != null && store.activeProfileId() != profileId) return
            store.clear(profileId)
            clearProcessSecret(profileId)
            context.stopService(Intent(context, AgentMonitorService::class.java))
        }

        fun connectionSecretFor(profileId: String): ConnectionSecret? =
            processSecret.get()?.takeIf { it.first == profileId }?.second

        internal fun reconnectDelay(failedAttempts: Int): Long {
            val exponent = (failedAttempts - 1).coerceIn(0, 4)
            return (5_000L shl exponent).coerceAtMost(MAX_RECONNECT_DELAY_MS)
        }

        internal fun monitoredTerminalLabel(count: Int): String =
            when (count) {
                0 -> "SSH connected · no terminal alerts enabled"
                1 -> "Watching 1 terminal for attention"
                else -> "Watching $count terminals for attention"
            }

        private fun clearProcessSecret(profileId: String?) {
            val current = processSecret.get() ?: return
            if (profileId == null || current.first == profileId) {
                processSecret.compareAndSet(current, null)
            }
        }
    }
}
