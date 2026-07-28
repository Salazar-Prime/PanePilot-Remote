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
import com.panepilot.remote.data.AttentionStateStore
import com.panepilot.remote.data.BackgroundMonitorStore
import com.panepilot.remote.data.CredentialStore
import com.panepilot.remote.data.ProfileStore
import com.panepilot.remote.data.SessionStateStore
import com.panepilot.remote.model.AuthMode
import com.panepilot.remote.model.ConnectionSecret
import com.panepilot.remote.model.SessionState
import com.panepilot.remote.notifications.AttentionNotifier
import com.panepilot.remote.notifications.NotificationGroups
import com.panepilot.remote.notifications.newAttentionEvents
import com.panepilot.remote.notifications.noLongerNeedsAttention
import com.panepilot.remote.ssh.SshConnection
import com.panepilot.remote.ssh.TmuxGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

class AgentMonitorService : Service() {
    private data class ProfileMonitor(
        val profileId: String,
        val ssh: SshConnection,
        val tmux: TmuxGateway,
        val liveSecret: AtomicReference<ConnectionSecret?> = AtomicReference(null),
        var job: Job? = null
    )

    private data class MonitorStatus(
        val profileName: String,
        val connected: Boolean,
        val reconnecting: Boolean,
        val alertCount: Int
    )

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val monitors = ConcurrentHashMap<String, ProfileMonitor>()
    private val statuses = ConcurrentHashMap<String, MonitorStatus>()

    private lateinit var profiles: ProfileStore
    private lateinit var credentials: CredentialStore
    private lateinit var attentionPreferences: AttentionPreferenceStore
    private lateinit var attentionState: AttentionStateStore
    private lateinit var sessionStates: SessionStateStore
    private lateinit var monitorStore: BackgroundMonitorStore
    private lateinit var attentionNotifier: AttentionNotifier
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        activeInstance.set(this)
        profiles = ProfileStore(this)
        credentials = CredentialStore(this)
        attentionPreferences = AttentionPreferenceStore(this)
        attentionState = AttentionStateStore(this)
        sessionStates = SessionStateStore(this)
        monitorStore = BackgroundMonitorStore(this)
        attentionNotifier = AttentionNotifier(this)
        notificationManager = getSystemService(NotificationManager::class.java)
        createMonitoringChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground(
            title = "Starting SSH monitoring",
            detail = "Restoring background connections…"
        )

        when (intent?.action) {
            ACTION_STOP_ALL -> {
                monitorStore.monitoredProfileIds().forEach { profileId ->
                    val terminalIds = attentionPreferences.enabledTerminalIds(profileId)
                    attentionNotifier.cancelProfile(profileId, terminalIds)
                    attentionPreferences.removeProfile(profileId)
                    attentionState.removeProfile(profileId)
                    sessionStates.removeProfile(profileId)
                }
                stopAll(clearConfiguration = true)
                return START_NOT_STICKY
            }

            ACTION_STOP_PROFILE -> {
                intent.getStringExtra(EXTRA_PROFILE_ID)?.let(::stopProfile)
                if (monitorStore.monitoredProfileIds().isEmpty()) {
                    stopAll(clearConfiguration = false)
                    return START_NOT_STICKY
                }
            }

            ACTION_START -> {
                val profileId = intent.getStringExtra(EXTRA_PROFILE_ID)
                if (!profileId.isNullOrBlank()) {
                    val secret = ConnectionSecret(
                        password = intent.getStringExtra(EXTRA_PASSWORD).orEmpty(),
                        keyPassphrase = intent.getStringExtra(EXTRA_KEY_PASSPHRASE).orEmpty()
                    )
                    processSecrets[profileId] = secret
                    monitorStore.addProfile(profileId)
                    startProfile(profileId, secret)
                }
            }
        }

        monitorStore.monitoredProfileIds().forEach { profileId ->
            startProfile(profileId, processSecrets[profileId])
        }
        if (monitorStore.monitoredProfileIds().isEmpty()) {
            stopAll(clearConfiguration = false)
            return START_NOT_STICKY
        }
        updateAggregateForeground()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        monitors.values.forEach { monitor ->
            monitor.job?.cancel()
            monitor.ssh.disconnect()
        }
        monitors.clear()
        statuses.clear()
        serviceScope.cancel()
        activeInstance.compareAndSet(this, null)
        super.onDestroy()
    }

    private fun startProfile(profileId: String, secret: ConnectionSecret?) {
        val existing = monitors[profileId]
        if (existing != null) {
            if (secret != null) existing.liveSecret.set(secret)
            if (existing.job?.isActive == true) return
        }
        val monitor = existing ?: run {
            val sharedSsh = SshConnection(this, profiles) { false }
            ProfileMonitor(
                profileId = profileId,
                ssh = sharedSsh,
                tmux = TmuxGateway(sharedSsh)
            )
        }.also { monitors[profileId] = it }
        if (secret != null) monitor.liveSecret.set(secret)
        monitor.job = serviceScope.launch { monitorProfile(monitor) }
    }

    private suspend fun monitorProfile(monitor: ProfileMonitor) {
        val profileId = monitor.profileId
        var previousStates: Map<String, SessionState> = sessionStates.load(profileId)
        var failedAttempts = 0

        while (
            currentCoroutineContext().isActive &&
            profileId in monitorStore.monitoredProfileIds()
        ) {
            val profile = profiles.load().firstOrNull { it.id == profileId }
            if (profile == null) {
                monitorStore.removeProfile(profileId)
                stopProfile(profileId)
                return
            }
            val enabledIds = attentionPreferences.enabledTerminalIds(profileId)

            try {
                if (!monitor.ssh.isConnected) {
                    val secret = connectionSecret(
                        authMode = profile.authMode,
                        profileId = profileId,
                        monitor = monitor
                    )
                    if (secret == null) {
                        statuses[profileId] = MonitorStatus(
                            profileName = profile.name,
                            connected = false,
                            reconnecting = false,
                            alertCount = enabledIds.size
                        )
                        updateAggregateForeground()
                        delay(PAUSED_RETRY_MS)
                        continue
                    }
                    processSecrets[profileId] = secret
                    statuses[profileId] = MonitorStatus(
                        profileName = profile.name,
                        connected = false,
                        reconnecting = true,
                        alertCount = enabledIds.size
                    )
                    updateAggregateForeground()
                    monitor.ssh.connect(profile, secret)
                    monitor.tmux.reset()
                }

                val sessions = monitor.tmux.listSessions()
                val currentStates = sessions.associate { it.terminalId to it.state }
                newAttentionEvents(previousStates, sessions)
                    .filter { it.session.terminalId in enabledIds }
                    .forEach { event ->
                        attentionState.markUnread(profileId, event.session.terminalId)
                        attentionNotifier.show(profile, event)
                    }
                noLongerNeedsAttention(sessions).forEach { session ->
                    attentionState.markRead(profileId, session.terminalId)
                    attentionNotifier.cancel(profileId, session.terminalId)
                }
                previousStates = currentStates
                sessionStates.save(profileId, currentStates)
                failedAttempts = 0
                statuses[profileId] = MonitorStatus(
                    profileName = profile.name,
                    connected = true,
                    reconnecting = false,
                    alertCount = enabledIds.size
                )
                updateAggregateForeground()
                delay(POLL_INTERVAL_MS)
            } catch (_: Exception) {
                monitor.ssh.disconnect()
                monitor.tmux.reset()
                failedAttempts += 1
                statuses[profileId] = MonitorStatus(
                    profileName = profile.name,
                    connected = false,
                    reconnecting = true,
                    alertCount = enabledIds.size
                )
                updateAggregateForeground()
                delay(reconnectDelay(failedAttempts))
            }
        }
    }

    private fun connectionSecret(
        authMode: AuthMode,
        profileId: String,
        monitor: ProfileMonitor
    ): ConnectionSecret? {
        monitor.liveSecret.get()?.let { secret ->
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
        }?.also {
            monitor.liveSecret.set(it)
            processSecrets[profileId] = it
        }
    }

    private fun stopProfile(profileId: String) {
        monitorStore.removeProfile(profileId)
        statuses.remove(profileId)
        monitors.remove(profileId)?.let { monitor ->
            monitor.job?.cancel()
            monitor.ssh.disconnect()
        }
        processSecrets.remove(profileId)
        if (monitorStore.monitoredProfileIds().isEmpty()) {
            stopAll(clearConfiguration = false)
        } else {
            updateAggregateForeground()
        }
    }

    private fun createMonitoringChannel() {
        NotificationGroups.ensureCreated(notificationManager)
        notificationManager.createNotificationChannel(
            NotificationChannel(
                MONITOR_CHANNEL_ID,
                "Background monitoring",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps SSH agent-attention monitoring active"
                group = NotificationGroups.CONNECTION_CHANNEL_GROUP_ID
                setShowBadge(false)
            }
        )
        notificationManager.deleteNotificationChannel(LEGACY_MONITOR_CHANNEL_ID)
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

    @Synchronized
    private fun updateAggregateForeground() {
        val profileIds = monitorStore.monitoredProfileIds()
        if (profileIds.isEmpty()) return
        val activeStatuses = profileIds.mapNotNull(statuses::get)
        val connectedCount = activeStatuses.count { it.connected }
        val reconnectingCount = activeStatuses.count { it.reconnecting }
        val alertCount = activeStatuses.sumOf { it.alertCount }
        val title = monitoringTitle(
            serverCount = profileIds.size,
            singleServerName = activeStatuses.singleOrNull()?.profileName
        )
        val detail = aggregateStatusLabel(
            serverCount = profileIds.size,
            connectedCount = connectedCount,
            reconnectingCount = reconnectingCount,
            alertCount = alertCount
        )
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
            Intent(this, AgentMonitorService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, MONITOR_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(detail)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setGroup(NotificationGroups.CONNECTION_SHADE_GROUP_KEY)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .addAction(
                Notification.Action.Builder(
                    null,
                    "Stop all",
                    stopMonitoring
                ).build()
            )
            .build()
    }

    private fun stopAll(clearConfiguration: Boolean) {
        if (clearConfiguration) monitorStore.clear()
        monitors.values.forEach { monitor ->
            monitor.job?.cancel()
            monitor.ssh.disconnect()
        }
        monitors.clear()
        statuses.clear()
        processSecrets.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val ACTION_START =
            "com.panepilot.remote.action.START_BACKGROUND_MONITOR"
        private const val ACTION_STOP_PROFILE =
            "com.panepilot.remote.action.STOP_PROFILE_MONITOR"
        private const val ACTION_STOP_ALL =
            "com.panepilot.remote.action.STOP_ALL_BACKGROUND_MONITORS"
        private const val EXTRA_PROFILE_ID = "profile_id"
        private const val EXTRA_PASSWORD = "password"
        private const val EXTRA_KEY_PASSPHRASE = "key_passphrase"
        private const val MONITOR_CHANNEL_ID = "background-monitor-v2"
        private const val LEGACY_MONITOR_CHANNEL_ID = "background-monitor"
        private const val MONITOR_NOTIFICATION_ID = 8_421
        private const val POLL_INTERVAL_MS = 6_000L
        private const val PAUSED_RETRY_MS = 30_000L
        private const val MAX_RECONNECT_DELAY_MS = 60_000L
        private val processSecrets = ConcurrentHashMap<String, ConnectionSecret>()
        private val activeInstance = AtomicReference<AgentMonitorService?>(null)

        fun start(
            context: Context,
            profileId: String,
            secret: ConnectionSecret
        ) {
            processSecrets[profileId] = secret
            BackgroundMonitorStore(context).addProfile(profileId)
            val intent = Intent(context, AgentMonitorService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PROFILE_ID, profileId)
                .putExtra(EXTRA_PASSWORD, secret.password)
                .putExtra(EXTRA_KEY_PASSPHRASE, secret.keyPassphrase)
            context.startForegroundService(intent)
        }

        fun stop(context: Context, profileId: String) {
            val store = BackgroundMonitorStore(context)
            if (profileId !in store.monitoredProfileIds()) return
            store.removeProfile(profileId)
            processSecrets.remove(profileId)
            if (store.monitoredProfileIds().isEmpty()) {
                context.stopService(Intent(context, AgentMonitorService::class.java))
            } else {
                context.startService(
                    Intent(context, AgentMonitorService::class.java)
                        .setAction(ACTION_STOP_PROFILE)
                        .putExtra(EXTRA_PROFILE_ID, profileId)
                )
            }
        }

        fun connectionSecretFor(profileId: String): ConnectionSecret? =
            processSecrets[profileId]

        fun connectedSshFor(profileId: String): SshConnection? =
            activeInstance.get()
                ?.monitors
                ?.get(profileId)
                ?.ssh
                ?.takeIf { it.isConnected }

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

        internal fun monitoringTitle(serverCount: Int, singleServerName: String?): String =
            if (serverCount == 1 && !singleServerName.isNullOrBlank()) {
                "Monitoring $singleServerName"
            } else if (serverCount == 1) {
                "Monitoring SSH server"
            } else {
                "Monitoring $serverCount SSH servers"
            }

        internal fun aggregateStatusLabel(
            serverCount: Int,
            connectedCount: Int,
            reconnectingCount: Int,
            alertCount: Int
        ): String {
            if (serverCount == 1 && connectedCount == 1) {
                return monitoredTerminalLabel(alertCount)
            }
            val connectionLabel = "$connectedCount/$serverCount connected"
            val retryLabel = if (reconnectingCount > 0) " · $reconnectingCount reconnecting" else ""
            val alertLabel = when (alertCount) {
                0 -> " · no terminal alerts"
                1 -> " · 1 terminal alert"
                else -> " · $alertCount terminal alerts"
            }
            return connectionLabel + retryLabel + alertLabel
        }
    }
}
