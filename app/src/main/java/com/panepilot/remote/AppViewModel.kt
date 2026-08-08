package com.panepilot.remote

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.panepilot.remote.data.AttentionPreferenceStore
import com.panepilot.remote.data.AttentionStateStore
import com.panepilot.remote.data.BackgroundMonitorStore
import com.panepilot.remote.data.CredentialStore
import com.panepilot.remote.data.NotificationHistoryStore
import com.panepilot.remote.data.ProfileStore
import com.panepilot.remote.data.SessionStateStore
import com.panepilot.remote.data.TerminalPreferenceStore
import com.panepilot.remote.model.AuthMode
import com.panepilot.remote.model.ConnectionSecret
import com.panepilot.remote.model.PanePilotSession
import com.panepilot.remote.model.ProjectAction
import com.panepilot.remote.model.RemoteDownloadRequest
import com.panepilot.remote.model.RemoteFileEntry
import com.panepilot.remote.model.RemoteFilePreview
import com.panepilot.remote.model.ServerProfile
import com.panepilot.remote.model.SessionState
import com.panepilot.remote.model.TerminalKey
import com.panepilot.remote.model.TerminalSortMode
import com.panepilot.remote.monitoring.AgentMonitorService
import com.panepilot.remote.notifications.AttentionEvent
import com.panepilot.remote.notifications.AttentionEventType
import com.panepilot.remote.notifications.AttentionNotifier
import com.panepilot.remote.notifications.NotificationHistoryEntry
import com.panepilot.remote.notifications.newAttentionEvents
import com.panepilot.remote.notifications.noLongerNeedsAttention
import com.panepilot.remote.ssh.RemoteFileGateway
import com.panepilot.remote.ssh.ProjectActionGateway
import com.panepilot.remote.ssh.SshConnection
import com.panepilot.remote.ssh.TmuxGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

sealed interface AppScreen {
    data object Servers : AppScreen
    data class EditServer(val profileId: String?) : AppScreen
    data class Credentials(val profileId: String) : AppScreen
    data object Sessions : AppScreen
    data class Console(val sessionName: String) : AppScreen
    data class Files(val sessionName: String) : AppScreen
    data class Actions(val sessionName: String) : AppScreen
}

data class HostKeyPrompt(
    val id: String,
    val message: String
)

data class AppUiState(
    val profiles: List<ServerProfile> = emptyList(),
    val screen: AppScreen = AppScreen.Servers,
    val connectedProfile: ServerProfile? = null,
    val connectedProfileIds: Set<String> = emptySet(),
    val sessions: List<PanePilotSession> = emptyList(),
    val selectedSession: PanePilotSession? = null,
    val transcript: String = "",
    val paneTitle: String = "",
    val composer: String = "",
    val isBusy: Boolean = false,
    val isSending: Boolean = false,
    val attentionNotificationTerminalIds: Set<String> = emptySet(),
    val unreadAttentionTerminalIds: Set<String> = emptySet(),
    val pinnedTerminalIds: Set<String> = emptySet(),
    val terminalInteractionTimes: Map<String, Long> = emptyMap(),
    val terminalActivityTimes: Map<String, Long> = emptyMap(),
    val terminalSortMode: TerminalSortMode = TerminalSortMode.ACTIVITY,
    val notificationHistory: List<NotificationHistoryEntry> = emptyList(),
    val remoteFileRoot: String = "",
    val remoteFilePath: String = "",
    val remoteFiles: List<RemoteFileEntry> = emptyList(),
    val highlightedRemoteFilePath: String? = null,
    val remoteFilePreview: RemoteFilePreview? = null,
    val isLoadingFiles: Boolean = false,
    val isLoadingFilePreview: Boolean = false,
    val pendingDownload: RemoteDownloadRequest? = null,
    val isDownloading: Boolean = false,
    val downloadProgress: Int? = null,
    val projectActions: List<ProjectAction> = emptyList(),
    val isLoadingActions: Boolean = false,
    val runningActionId: String? = null,
    val hostKeyPrompt: HostKeyPrompt? = null,
    val notice: String? = null,
    val error: String? = null
)

private data class PendingTrust(
    val id: String,
    val latch: CountDownLatch = CountDownLatch(1),
    @Volatile var accepted: Boolean = false
)

private data class LiveConnection(
    val profile: ServerProfile,
    val ssh: SshConnection,
    val tmux: TmuxGateway,
    val remoteFiles: RemoteFileGateway,
    val actions: ProjectActionGateway,
    val secret: ConnectionSecret,
    var sessions: List<PanePilotSession>,
    val ownsTransport: Boolean
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val profileStore = ProfileStore(application)
    private val credentialStore = CredentialStore(application)
    private val attentionPreferences = AttentionPreferenceStore(application)
    private val attentionState = AttentionStateStore(application)
    private val backgroundMonitorStore = BackgroundMonitorStore(application)
    private val sessionStateStore = SessionStateStore(application)
    private val terminalPreferences = TerminalPreferenceStore(application)
    private val notificationHistoryStore = NotificationHistoryStore(application)
    private val attentionNotifier = AttentionNotifier(application)
    private val pendingTrust = AtomicReference<PendingTrust?>(null)
    private val connections = linkedMapOf<String, LiveConnection>()
    private var activeProfileId: String? = null
    private val _state = MutableStateFlow(
        AppUiState(
            profiles = profileStore.load(),
            connectedProfileIds = emptySet(),
            notificationHistory = notificationHistoryStore.load()
        )
    )
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    private val activeConnection: LiveConnection?
        get() = activeProfileId?.let(connections::get)

    private var consolePolling: Job? = null
    private var sessionPolling: Job? = null
    private var fileLoading: Job? = null
    private var actionLoading: Job? = null
    private var consoleFailureReported = false
    private var lastSessionStates: Map<String, SessionState> = emptyMap()
    private var pendingAttentionTarget: Pair<String, String>? = null

    init {
        restoreBackgroundConnection()
    }

    fun addServer() {
        _state.update { it.copy(screen = AppScreen.EditServer(null), error = null) }
    }

    fun editServer(profileId: String) {
        _state.update { it.copy(screen = AppScreen.EditServer(profileId), error = null) }
    }

    fun openCredentials(profileId: String) {
        _state.update { it.copy(screen = AppScreen.Credentials(profileId), error = null) }
    }

    fun openServer(profileId: String) {
        val existing = connections[profileId]
        if (existing?.ssh?.isConnected == true) {
            activateConnection(existing)
            return
        }
        val profile = profile(profileId) ?: return
        AgentMonitorService.connectedSshFor(profileId)?.let { warmSsh ->
            attachToWarmConnection(profile, warmSsh)
            return
        }
        val secret = AgentMonitorService.connectionSecretFor(profileId) ?: when (profile.authMode) {
            AuthMode.PASSWORD -> credentialStore.password(profileId)?.let {
                ConnectionSecret(password = it)
            }

            AuthMode.PRIVATE_KEY -> ConnectionSecret()
        }
        if (profileId in backgroundMonitorStore.monitoredProfileIds() && secret != null) {
            connect(
                profileId = profileId,
                secret = secret,
                rememberPassword =
                    profile.authMode == AuthMode.PASSWORD &&
                        credentialStore.password(profileId) != null
            )
        } else {
            openCredentials(profileId)
        }
    }

    fun showServers() {
        consolePolling?.cancel()
        consolePolling = null
        fileLoading?.cancel()
        fileLoading = null
        actionLoading?.cancel()
        actionLoading = null
        _state.update {
            it.copy(
                screen = AppScreen.Servers,
                selectedSession = null,
                transcript = "",
                paneTitle = "",
                composer = "",
                remoteFileRoot = "",
                remoteFilePath = "",
                remoteFiles = emptyList(),
                highlightedRemoteFilePath = null,
                remoteFilePreview = null,
                projectActions = emptyList(),
                error = null
            )
        }
    }

    fun saveServer(profile: ServerProfile, selectedKey: Uri?) {
        val normalized = profile.copy(
            name = profile.name.trim(),
            host = profile.host.trim(),
            username = profile.username.trim()
        )
        val validationError = validateProfile(normalized, selectedKey)
        if (validationError != null) {
            showError(validationError)
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, error = null) }
            runCatching {
                withContext(Dispatchers.IO) {
                    profileStore.save(normalized, selectedKey)
                }
            }.onSuccess { profiles ->
                disconnectProfile(normalized.id, stopBackground = true)
                if (normalized.authMode != AuthMode.PASSWORD) {
                    credentialStore.remove(normalized.id)
                }
                _state.update {
                    it.copy(
                        profiles = profiles,
                        screen = AppScreen.Servers,
                        isBusy = false
                    )
                }
            }.onFailure(::finishWithError)
        }
    }

    fun deleteServer(profileId: String) {
        viewModelScope.launch {
            disconnectProfile(profileId, stopBackground = true)
            val profiles = withContext(Dispatchers.IO) {
                credentialStore.remove(profileId)
                profileStore.delete(profileId)
            }
            val notificationIds = attentionPreferences.enabledTerminalIds(profileId)
            attentionNotifier.cancelProfile(profileId, notificationIds)
            attentionPreferences.removeProfile(profileId)
            attentionState.removeProfile(profileId)
            sessionStateStore.removeProfile(profileId)
            terminalPreferences.removeProfile(profileId)
            _state.update {
                it.copy(profiles = profiles, screen = AppScreen.Servers, error = null)
            }
        }
    }

    fun forgetHostKey(profileId: String) {
        val profile = profile(profileId) ?: return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    SshConnection(
                        getApplication(),
                        profileStore,
                        ::awaitHostKeyDecision
                    ).forgetHostKey(profile)
                }
            }.onSuccess {
                _state.update {
                    it.copy(error = "Saved host key removed. Verify the fingerprint when you reconnect.")
                }
            }.onFailure(::finishWithError)
        }
    }

    fun connect(
        profileId: String,
        secret: ConnectionSecret,
        rememberPassword: Boolean = false,
        activateOnlyIfNone: Boolean = false
    ) {
        val profile = profile(profileId) ?: return
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, error = null) }
            runCatching {
                withContext(Dispatchers.IO) {
                    val nextSsh = SshConnection(
                        getApplication(),
                        profileStore,
                        ::awaitHostKeyDecision
                    )
                    try {
                        nextSsh.connect(profile, secret)
                        val nextTmux = TmuxGateway(nextSsh)
                        val sessions = nextTmux.listSessions()
                        if (profile.authMode == AuthMode.PASSWORD) {
                            if (rememberPassword) {
                                credentialStore.savePassword(profile.id, secret.password)
                            } else {
                                credentialStore.remove(profile.id)
                            }
                        }
                        LiveConnection(
                            profile = profile,
                            ssh = nextSsh,
                            tmux = nextTmux,
                            remoteFiles = RemoteFileGateway(nextSsh),
                            actions = ProjectActionGateway(nextSsh, nextTmux),
                            secret = secret,
                            sessions = sessions,
                            ownsTransport = true
                        )
                    } catch (error: Exception) {
                        nextSsh.disconnect()
                        throw error
                    }
                }
            }.onSuccess { connection ->
                connections.remove(profile.id)?.let { prior ->
                    if (prior.ownsTransport) prior.ssh.disconnect()
                }
                connections[profile.id] = connection
                AgentMonitorService.start(getApplication(), profile.id, secret)
                if (!activateOnlyIfNone || activeProfileId == null) {
                    activateConnection(connection)
                } else {
                    _state.update {
                        it.copy(
                            connectedProfileIds = connectedProfileIds(),
                            isBusy = false
                        )
                    }
                }
            }.onFailure { error ->
                finishWithError(error)
            }
        }
    }

    fun disconnect() {
        val profileId = activeProfileId ?: return
        disconnectProfile(profileId, stopBackground = true)
        consolePolling?.cancel()
        consolePolling = null
        sessionPolling?.cancel()
        sessionPolling = null
        fileLoading?.cancel()
        fileLoading = null
        actionLoading?.cancel()
        actionLoading = null
        lastSessionStates = emptyMap()
        activeProfileId = null
        _state.update {
            it.copy(
                connectedProfile = null,
                connectedProfileIds = connectedProfileIds(),
                sessions = emptyList(),
                selectedSession = null,
                transcript = "",
                paneTitle = "",
                composer = "",
                isBusy = false,
                isSending = false,
                attentionNotificationTerminalIds = emptySet(),
                unreadAttentionTerminalIds = emptySet(),
                pinnedTerminalIds = emptySet(),
                terminalInteractionTimes = emptyMap(),
                terminalActivityTimes = emptyMap(),
                terminalSortMode = TerminalSortMode.ACTIVITY,
                remoteFileRoot = "",
                remoteFilePath = "",
                remoteFiles = emptyList(),
                highlightedRemoteFilePath = null,
                remoteFilePreview = null,
                isLoadingFiles = false,
                isLoadingFilePreview = false,
                pendingDownload = null,
                isDownloading = false,
                downloadProgress = null,
                projectActions = emptyList(),
                isLoadingActions = false,
                runningActionId = null,
                screen = AppScreen.Servers
            )
        }
    }

    fun refreshSessions() {
        val connection = activeConnection ?: return
        if (!connection.ssh.isConnected || _state.value.isBusy) return
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, error = null) }
            runCatching {
                withContext(Dispatchers.IO) { connection.tmux.listSessions() }
            }.onSuccess { sessions ->
                connection.sessions = sessions
                if (activeProfileId == connection.profile.id) {
                    applySessionScan(sessions, detectTransitions = true)
                }
                _state.update { it.copy(isBusy = false) }
            }.onFailure(::finishWithError)
        }
    }

    fun openConsole(sessionName: String) {
        val selected = _state.value.sessions.firstOrNull { it.name == sessionName } ?: return
        if (activeConnection?.ssh?.isConnected != true) {
            _state.update { it.copy(connectedProfileIds = connectedProfileIds()) }
            showError("SSH is offline. Reconnect to open this session.")
            return
        }
        markTerminalRead(selected.terminalId)
        markTerminalInteraction(selected.terminalId)
        _state.update {
            it.copy(
                selectedSession = selected,
                screen = AppScreen.Console(sessionName),
                transcript = "",
                paneTitle = selected.paneTitle,
                composer = "",
                error = null
            )
        }
        startConsolePolling(sessionName)
    }

    fun openAttentionTarget(profileId: String, terminalId: String) {
        pendingAttentionTarget = profileId to terminalId
        openServer(profileId)
    }

    fun openFiles() {
        val session = _state.value.selectedSession ?: return
        consolePolling?.cancel()
        consolePolling = null
        _state.update {
            it.copy(
                screen = AppScreen.Files(session.name),
                remoteFileRoot = session.projectPath,
                remoteFilePath = "",
                remoteFiles = emptyList(),
                highlightedRemoteFilePath = null,
                remoteFilePreview = null,
                isLoadingFilePreview = false,
                pendingDownload = null,
                error = null
            )
        }
        loadRemoteDirectory("")
    }

    fun openFilesAtPath(reference: String) {
        val session = _state.value.selectedSession ?: return
        val fileGateway = activeConnection?.remoteFiles ?: return
        consolePolling?.cancel()
        consolePolling = null
        fileLoading?.cancel()
        _state.update {
            it.copy(
                screen = AppScreen.Files(session.name),
                remoteFileRoot = session.projectPath,
                remoteFilePath = "",
                remoteFiles = emptyList(),
                highlightedRemoteFilePath = null,
                remoteFilePreview = null,
                isLoadingFiles = true,
                isLoadingFilePreview = false,
                pendingDownload = null,
                error = null
            )
        }
        fileLoading = viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val location = fileGateway.locate(session.projectPath, reference)
                    val files = fileGateway.list(
                        session.projectPath,
                        location.directoryRelativePath
                    )
                    val selectedFile = location.selectedFileRelativePath?.let { path ->
                        files.firstOrNull { it.relativePath == path }
                    }
                    Triple(location, files, selectedFile?.let {
                        fileGateway.preview(session.projectPath, it)
                    })
                }
            }.onSuccess { (location, files, preview) ->
                _state.update {
                    it.copy(
                        remoteFilePath = location.directoryRelativePath,
                        remoteFiles = files,
                        highlightedRemoteFilePath = location.selectedFileRelativePath,
                        remoteFilePreview = preview,
                        isLoadingFiles = false
                    )
                }
            }.onFailure { error ->
                _state.update { it.copy(isLoadingFiles = false) }
                finishWithError(error)
            }
        }
    }

    fun openRemoteDirectory(relativePath: String) {
        loadRemoteDirectory(relativePath)
    }

    fun openRemoteFile(file: RemoteFileEntry) {
        if (file.isDirectory || _state.value.isLoadingFilePreview) return
        val connection = activeConnection ?: return
        val root = _state.value.remoteFileRoot
        fileLoading?.cancel()
        fileLoading = viewModelScope.launch {
            _state.update {
                it.copy(
                    isLoadingFilePreview = true,
                    highlightedRemoteFilePath = file.relativePath,
                    error = null
                )
            }
            runCatching {
                withContext(Dispatchers.IO) { connection.remoteFiles.preview(root, file) }
            }.onSuccess { preview ->
                if (preview == null) {
                    _state.update {
                        it.copy(
                            isLoadingFilePreview = false,
                            pendingDownload = RemoteDownloadRequest(file = file)
                        )
                    }
                } else {
                    _state.update {
                        it.copy(remoteFilePreview = preview, isLoadingFilePreview = false)
                    }
                }
            }.onFailure { error ->
                _state.update { it.copy(isLoadingFilePreview = false) }
                finishWithError(error)
            }
        }
    }

    fun closeRemoteFilePreview() {
        _state.update { it.copy(remoteFilePreview = null, isLoadingFilePreview = false) }
    }

    fun openActions() {
        val session = _state.value.selectedSession ?: return
        val connection = activeConnection ?: return
        consolePolling?.cancel()
        consolePolling = null
        actionLoading?.cancel()
        _state.update {
            it.copy(
                screen = AppScreen.Actions(session.name),
                projectActions = emptyList(),
                isLoadingActions = true,
                runningActionId = null,
                error = null
            )
        }
        actionLoading = viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { connection.actions.list(session.projectPath) }
            }.onSuccess { actions ->
                _state.update { it.copy(projectActions = actions, isLoadingActions = false) }
            }.onFailure { error ->
                _state.update { it.copy(isLoadingActions = false) }
                finishWithError(error)
            }
        }
    }

    fun runProjectAction(action: ProjectAction) {
        val session = _state.value.selectedSession ?: return
        val connection = activeConnection ?: return
        if (_state.value.runningActionId != null) return
        viewModelScope.launch {
            _state.update { it.copy(runningActionId = action.id, error = null) }
            runCatching {
                withContext(Dispatchers.IO) {
                    connection.actions.run(session.projectId, session.projectPath, action)
                    connection.tmux.listSessions()
                }
            }.onSuccess { sessions ->
                connection.sessions = sessions
                applySessionScan(sessions, detectTransitions = true)
                _state.update { it.copy(runningActionId = null, notice = "Started ${action.name}.") }
                sessions.firstOrNull { it.actionId == action.id }?.let { launched ->
                    openConsole(launched.name)
                }
            }.onFailure { error ->
                _state.update { it.copy(runningActionId = null) }
                finishWithError(error)
            }
        }
    }

    fun goUpRemoteDirectory() {
        val parent = RemoteFileGateway.parentPath(_state.value.remoteFilePath)
        loadRemoteDirectory(parent)
    }

    fun requestDownload(file: RemoteFileEntry) {
        if (file.isDirectory || _state.value.isDownloading) return
        _state.update { it.copy(pendingDownload = RemoteDownloadRequest(file = file)) }
    }

    fun finishDownloadDestination(uri: Uri?) {
        val request = _state.value.pendingDownload ?: return
        val fileGateway = activeConnection?.remoteFiles ?: return
        if (uri == null) {
            _state.update { it.copy(pendingDownload = null) }
            return
        }
        val root = _state.value.remoteFileRoot
        _state.update {
            it.copy(
                pendingDownload = null,
                isDownloading = true,
                downloadProgress = 0,
                error = null
            )
        }
        viewModelScope.launch {
            var lastProgress = -1
            runCatching {
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    resolver.openOutputStream(uri, "w")?.use { output ->
                        fileGateway.download(
                            rootPath = root,
                            relativePath = request.file.relativePath,
                            output = output
                        ) { copied, total ->
                            val progress = when {
                                total <= 0L && copied <= 0L -> 100
                                total <= 0L -> null
                                else -> ((copied * 100L) / total)
                                    .coerceIn(0L, 100L)
                                    .toInt()
                            }
                            if (progress != null && progress != lastProgress) {
                                lastProgress = progress
                                _state.update { it.copy(downloadProgress = progress) }
                            }
                        }
                    } ?: throw IllegalStateException("The selected destination could not be opened.")
                }
            }.onSuccess {
                _state.update {
                    it.copy(
                        isDownloading = false,
                        downloadProgress = null,
                        notice = "Saved ${request.file.name}."
                    )
                }
            }.onFailure { error ->
                runCatching { getApplication<Application>().contentResolver.delete(uri, null, null) }
                _state.update {
                    it.copy(isDownloading = false, downloadProgress = null)
                }
                finishWithError(error)
            }
        }
    }

    fun setAttentionNotificationsEnabled(terminalId: String, enabled: Boolean) {
        val current = _state.value
        val profile = current.connectedProfile ?: return
        val session = current.sessions.firstOrNull { it.terminalId == terminalId } ?: return
        val updated = attentionPreferences.setEnabled(profile.id, terminalId, enabled)
        _state.update { it.copy(attentionNotificationTerminalIds = updated) }
        if (enabled && session.state == SessionState.NEEDS_INPUT) {
            val unread = attentionState.markUnread(profile.id, terminalId)
            _state.update { it.copy(unreadAttentionTerminalIds = unread) }
            attentionNotifier.show(
                profile,
                AttentionEvent(session, AttentionEventType.NEEDS_INPUT)
            )
            refreshNotificationHistory()
        } else if (!enabled) {
            attentionState.markRead(profile.id, terminalId)
            attentionNotifier.cancel(profile.id, terminalId)
            _state.update {
                it.copy(
                    unreadAttentionTerminalIds =
                        attentionState.unreadTerminalIds(profile.id)
                )
            }
        }
        val secret = activeConnection?.secret ?: when (profile.authMode) {
            AuthMode.PASSWORD -> credentialStore.password(profile.id)?.let {
                ConnectionSecret(password = it)
            }

            AuthMode.PRIVATE_KEY -> ConnectionSecret()
        }
        if (secret != null) {
            AgentMonitorService.start(getApplication(), profile.id, secret)
        } else {
            showError(
                "Reconnect with Remember on this phone to keep alerts running in the background."
            )
        }
    }

    fun setTerminalPinned(terminalId: String, pinned: Boolean) {
        val profileId = _state.value.connectedProfile?.id ?: return
        val updated = terminalPreferences.setPinned(profileId, terminalId, pinned)
        _state.update { it.copy(pinnedTerminalIds = updated) }
    }

    fun setTerminalSortMode(sortMode: TerminalSortMode) {
        val profileId = _state.value.connectedProfile?.id ?: return
        terminalPreferences.setSortMode(profileId, sortMode)
        _state.update { it.copy(terminalSortMode = sortMode) }
    }

    fun refreshNotificationHistory() {
        _state.update {
            it.copy(notificationHistory = notificationHistoryStore.load())
        }
    }

    fun notificationPermissionDenied() {
        showError("Allow PanePilot notifications to enable alerts for this terminal.")
    }

    fun externalUrlOpenFailed() {
        showError("No browser is available to open this link.")
    }

    fun updateComposer(value: String) {
        if (value.toByteArray().size <= 32 * 1024) {
            _state.update { it.copy(composer = value) }
        }
    }

    fun sendMessage() {
        val current = _state.value
        val connection = activeConnection ?: return
        val sessionName = current.selectedSession?.name ?: return
        val message = current.composer
        if (message.isBlank() || current.isSending) return
        viewModelScope.launch {
            _state.update { it.copy(isSending = true, error = null) }
            runCatching {
                withContext(Dispatchers.IO) { connection.tmux.send(sessionName, message) }
            }.onSuccess {
                if (activeProfileId != connection.profile.id) return@onSuccess
                markTerminalInteraction(
                    current.selectedSession.terminalId,
                    includeActivity = true
                )
                _state.update { it.copy(composer = "", isSending = false) }
                delay(250)
                refreshConsole(sessionName, reportError = true)
            }.onFailure { error ->
                _state.update { it.copy(isSending = false) }
                finishWithError(error)
            }
        }
    }

    fun sendTerminalKey(key: TerminalKey) {
        val connection = activeConnection ?: return
        val selected = _state.value.selectedSession ?: return
        val sessionName = selected.name
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { connection.tmux.sendKey(sessionName, key) }
            }.onSuccess {
                if (activeProfileId != connection.profile.id) return@onSuccess
                markTerminalInteraction(selected.terminalId, includeActivity = true)
                delay(120)
                refreshConsole(sessionName, reportError = true)
            }.onFailure(::finishWithError)
        }
    }

    fun answerHostKey(promptId: String, accepted: Boolean) {
        val pending = pendingTrust.get() ?: return
        if (pending.id != promptId) return
        pending.accepted = accepted
        pending.latch.countDown()
        _state.update { it.copy(hostKeyPrompt = null) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun clearNotice() {
        _state.update { it.copy(notice = null) }
    }

    fun goBack() {
        when (_state.value.screen) {
            AppScreen.Servers -> Unit
            is AppScreen.EditServer, is AppScreen.Credentials ->
                _state.update { it.copy(screen = AppScreen.Servers, error = null) }

            AppScreen.Sessions -> showServers()
            is AppScreen.Console -> {
                consolePolling?.cancel()
                consolePolling = null
                _state.update {
                    it.copy(
                        screen = AppScreen.Sessions,
                        selectedSession = null,
                        transcript = "",
                        composer = "",
                        error = null
                    )
                }
                refreshSessions()
            }

            is AppScreen.Files -> {
                if (_state.value.remoteFilePreview != null) {
                    closeRemoteFilePreview()
                    return
                }
                if (_state.value.isDownloading) {
                    showError("Wait for the current file download to finish.")
                    return
                }
                val sessionName = (_state.value.screen as AppScreen.Files).sessionName
                fileLoading?.cancel()
                fileLoading = null
                _state.update {
                    it.copy(
                        screen = AppScreen.Console(sessionName),
                        remoteFileRoot = "",
                        remoteFilePath = "",
                        remoteFiles = emptyList(),
                        highlightedRemoteFilePath = null,
                        remoteFilePreview = null,
                        isLoadingFiles = false,
                        isLoadingFilePreview = false,
                        pendingDownload = null,
                        error = null
                    )
                }
                startConsolePolling(sessionName)
            }

            is AppScreen.Actions -> {
                val sessionName = (_state.value.screen as AppScreen.Actions).sessionName
                actionLoading?.cancel()
                actionLoading = null
                _state.update {
                    it.copy(
                        screen = AppScreen.Console(sessionName),
                        projectActions = emptyList(),
                        isLoadingActions = false,
                        runningActionId = null,
                        error = null
                    )
                }
                startConsolePolling(sessionName)
            }
        }
    }

    fun profile(profileId: String): ServerProfile? =
        _state.value.profiles.firstOrNull { it.id == profileId }

    fun hasPrivateKey(profileId: String): Boolean = profileStore.hasPrivateKey(profileId)

    fun rememberedPassword(profileId: String): String? = credentialStore.password(profileId)

    fun forgetPassword(profileId: String) {
        credentialStore.remove(profileId)
        disconnectProfile(profileId, stopBackground = true)
    }

    override fun onCleared() {
        pendingTrust.getAndSet(null)?.latch?.countDown()
        consolePolling?.cancel()
        sessionPolling?.cancel()
        fileLoading?.cancel()
        actionLoading?.cancel()
        connections.values.filter { it.ownsTransport }.forEach { it.ssh.disconnect() }
        connections.clear()
        activeProfileId = null
        super.onCleared()
    }

    private fun restoreBackgroundConnection() {
        val profileId = backgroundMonitorStore.lastActiveProfileId() ?: return
        val profile = profile(profileId) ?: return
        AgentMonitorService.connectedSshFor(profileId)?.let { warmSsh ->
            attachToWarmConnection(profile, warmSsh, activateOnlyIfNone = true)
            return
        }
        val secret = AgentMonitorService.connectionSecretFor(profileId) ?: when (profile.authMode) {
            AuthMode.PASSWORD -> credentialStore.password(profileId)?.let {
                ConnectionSecret(password = it)
            }

            AuthMode.PRIVATE_KEY -> ConnectionSecret()
        } ?: return
        connect(
            profileId = profileId,
            secret = secret,
            rememberPassword =
                profile.authMode == AuthMode.PASSWORD &&
                    credentialStore.password(profileId) != null,
            activateOnlyIfNone = true
        )
    }

    private fun activateConnection(connection: LiveConnection) {
        consolePolling?.cancel()
        consolePolling = null
        fileLoading?.cancel()
        fileLoading = null
        actionLoading?.cancel()
        actionLoading = null
        activeProfileId = connection.profile.id
        backgroundMonitorStore.addProfile(connection.profile.id)
        lastSessionStates = connection.sessions.associate { it.terminalId to it.state }
        _state.update {
            it.copy(
                connectedProfile = connection.profile,
                connectedProfileIds = connectedProfileIds(),
                sessions = connection.sessions,
                selectedSession = null,
                transcript = "",
                paneTitle = "",
                composer = "",
                isBusy = false,
                isSending = false,
                attentionNotificationTerminalIds =
                    attentionPreferences.enabledTerminalIds(connection.profile.id),
                unreadAttentionTerminalIds =
                    attentionState.unreadTerminalIds(connection.profile.id),
                pinnedTerminalIds =
                    terminalPreferences.pinnedTerminalIds(connection.profile.id),
                terminalInteractionTimes = terminalPreferences.interactionTimes(
                    connection.profile.id,
                    connection.sessions.map { session -> session.terminalId }
                ),
                terminalActivityTimes = terminalPreferences.activityTimes(
                    connection.profile.id,
                    connection.sessions.map { session -> session.terminalId }
                ),
                terminalSortMode =
                    terminalPreferences.sortMode(connection.profile.id),
                notificationHistory = notificationHistoryStore.load(),
                remoteFileRoot = "",
                remoteFilePath = "",
                remoteFiles = emptyList(),
                highlightedRemoteFilePath = null,
                remoteFilePreview = null,
                isLoadingFiles = false,
                isLoadingFilePreview = false,
                pendingDownload = null,
                isDownloading = false,
                downloadProgress = null,
                projectActions = emptyList(),
                isLoadingActions = false,
                runningActionId = null,
                screen = AppScreen.Sessions,
                notice = null,
                error = null
            )
        }
        startSessionPolling()
        completePendingAttentionTarget(connection)
    }

    private fun attachToWarmConnection(
        profile: ServerProfile,
        warmSsh: SshConnection,
        activateOnlyIfNone: Boolean = false
    ) {
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, error = null) }
            runCatching {
                withContext(Dispatchers.IO) {
                    val warmTmux = TmuxGateway(warmSsh)
                    LiveConnection(
                        profile = profile,
                        ssh = warmSsh,
                        tmux = warmTmux,
                        remoteFiles = RemoteFileGateway(warmSsh),
                        actions = ProjectActionGateway(warmSsh, warmTmux),
                        secret = AgentMonitorService.connectionSecretFor(profile.id)
                            ?: ConnectionSecret(),
                        sessions = warmTmux.listSessions(),
                        ownsTransport = false
                    )
                }
            }.onSuccess { connection ->
                connections.remove(profile.id)?.let { prior ->
                    if (prior.ownsTransport) prior.ssh.disconnect()
                }
                connections[profile.id] = connection
                if (!activateOnlyIfNone || activeProfileId == null) {
                    activateConnection(connection)
                } else {
                    _state.update {
                        it.copy(
                            connectedProfileIds = connectedProfileIds(),
                            isBusy = false
                        )
                    }
                }
            }.onFailure(::finishWithError)
        }
    }

    private fun disconnectProfile(profileId: String, stopBackground: Boolean) {
        connections.remove(profileId)?.let { connection ->
            connection.tmux.reset()
            if (connection.ownsTransport) connection.ssh.disconnect()
        }
        if (stopBackground) {
            AgentMonitorService.stop(getApplication(), profileId)
        }
        if (activeProfileId == profileId) {
            activeProfileId = null
            consolePolling?.cancel()
            consolePolling = null
            sessionPolling?.cancel()
            sessionPolling = null
            fileLoading?.cancel()
            fileLoading = null
            actionLoading?.cancel()
            actionLoading = null
            lastSessionStates = emptyMap()
            _state.update {
                it.copy(
                    connectedProfile = null,
                    sessions = emptyList(),
                    selectedSession = null,
                    transcript = "",
                    paneTitle = "",
                    composer = "",
                    attentionNotificationTerminalIds = emptySet(),
                    unreadAttentionTerminalIds = emptySet(),
                    pinnedTerminalIds = emptySet(),
                    terminalInteractionTimes = emptyMap(),
                    terminalActivityTimes = emptyMap(),
                    terminalSortMode = TerminalSortMode.ACTIVITY
                )
            }
        }
        _state.update { it.copy(connectedProfileIds = connectedProfileIds()) }
    }

    private fun connectedProfileIds(): Set<String> =
        connections.values.filter { it.ssh.isConnected }.mapTo(mutableSetOf()) { it.profile.id } +
            _state.value.profiles.mapNotNull { profile ->
                profile.id.takeIf { AgentMonitorService.connectedSshFor(it) != null }
            }

    private fun completePendingAttentionTarget(connection: LiveConnection) {
        val target = pendingAttentionTarget ?: return
        if (target.first != connection.profile.id) return
        val session = connection.sessions.firstOrNull { it.terminalId == target.second }
        pendingAttentionTarget = null
        if (session == null) {
            showError("That tmux session is no longer available.")
        } else {
            openConsole(session.name)
        }
    }

    private fun markTerminalRead(terminalId: String) {
        val profileId = _state.value.connectedProfile?.id ?: return
        val unread = attentionState.markRead(profileId, terminalId)
        attentionNotifier.cancel(profileId, terminalId)
        _state.update { it.copy(unreadAttentionTerminalIds = unread) }
    }

    private fun markTerminalInteraction(
        terminalId: String,
        includeActivity: Boolean = false,
        atMillis: Long = System.currentTimeMillis()
    ) {
        if (terminalId.isBlank()) return
        val profileId = _state.value.connectedProfile?.id ?: return
        terminalPreferences.markInteraction(profileId, terminalId, atMillis)
        if (includeActivity) {
            terminalPreferences.markActivity(profileId, terminalId, atMillis)
        }
        val terminalIds = _state.value.sessions.map { it.terminalId }
        _state.update {
            it.copy(
                terminalInteractionTimes = terminalPreferences.interactionTimes(
                    profileId,
                    terminalIds
                ),
                terminalActivityTimes = terminalPreferences.activityTimes(
                    profileId,
                    terminalIds
                )
            )
        }
    }

    private fun markTerminalActivity(
        terminalId: String,
        atMillis: Long = System.currentTimeMillis()
    ) {
        val profileId = _state.value.connectedProfile?.id ?: return
        terminalPreferences.markActivity(profileId, terminalId, atMillis)
        _state.update {
            it.copy(
                terminalActivityTimes = terminalPreferences.activityTimes(
                    profileId,
                    it.sessions.map { session -> session.terminalId }
                )
            )
        }
    }

    private fun loadRemoteDirectory(relativePath: String) {
        val connection = activeConnection ?: return
        if (!connection.ssh.isConnected || _state.value.isLoadingFiles) return
        val root = _state.value.remoteFileRoot
        fileLoading = viewModelScope.launch {
            _state.update {
                it.copy(
                    isLoadingFiles = true,
                    isLoadingFilePreview = false,
                    remoteFilePreview = null,
                    highlightedRemoteFilePath = null,
                    error = null
                )
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    connection.remoteFiles.list(root, relativePath)
                }
            }.onSuccess { files ->
                _state.update {
                    it.copy(
                        remoteFilePath = RemoteFileGateway.normalizeRelativePath(relativePath),
                        remoteFiles = files,
                        isLoadingFiles = false
                    )
                }
            }.onFailure { error ->
                _state.update { it.copy(isLoadingFiles = false) }
                finishWithError(error)
            }
        }
    }

    private fun startConsolePolling(sessionName: String) {
        consolePolling?.cancel()
        consoleFailureReported = false
        consolePolling = viewModelScope.launch {
            while (_state.value.screen == AppScreen.Console(sessionName)) {
                refreshConsole(sessionName, reportError = true)
                delay(2_000)
            }
        }
    }

    private fun startSessionPolling() {
        sessionPolling?.cancel()
        val profileId = activeProfileId ?: return
        val connection = connections[profileId] ?: return
        sessionPolling = viewModelScope.launch {
            delay(SESSION_POLL_INTERVAL_MS)
            while (
                activeProfileId == profileId &&
                _state.value.connectedProfile?.id == profileId
            ) {
                val liveProfileIds = connectedProfileIds()
                if (_state.value.connectedProfileIds != liveProfileIds) {
                    _state.update { it.copy(connectedProfileIds = liveProfileIds) }
                }
                if (
                    connection.ssh.isConnected &&
                    !_state.value.isBusy &&
                    !_state.value.isDownloading
                ) {
                    runCatching {
                        withContext(Dispatchers.IO) { connection.tmux.listSessions() }
                    }.onSuccess { sessions ->
                        connection.sessions = sessions
                        applySessionScan(sessions, detectTransitions = true)
                    }.onFailure {
                        _state.update { state ->
                            state.copy(connectedProfileIds = connectedProfileIds())
                        }
                    }
                }
                delay(SESSION_POLL_INTERVAL_MS)
            }
        }
    }

    private fun applySessionScan(
        sessions: List<PanePilotSession>,
        detectTransitions: Boolean
    ) {
        val current = _state.value
        val profile = current.connectedProfile
        if (profile != null) {
            val now = System.currentTimeMillis()
            sessions.forEach { session ->
                val previous = lastSessionStates[session.terminalId]
                if (
                    previous != session.state &&
                    session.state in setOf(
                        SessionState.RUNNING,
                        SessionState.NEEDS_INPUT,
                        SessionState.READY
                    )
                ) {
                    terminalPreferences.markActivity(profile.id, session.terminalId, now)
                }
            }
        }
        if (detectTransitions && profile != null) {
            val enabledIds = attentionPreferences.enabledTerminalIds(profile.id)
            newAttentionEvents(lastSessionStates, sessions)
                .filter { it.session.terminalId in enabledIds }
                .forEach { event ->
                    attentionState.markUnread(profile.id, event.session.terminalId)
                    attentionNotifier.show(profile, event)
                }
            noLongerNeedsAttention(sessions).forEach { session ->
                attentionState.markRead(profile.id, session.terminalId)
                attentionNotifier.cancel(profile.id, session.terminalId)
            }
        }
        lastSessionStates = sessions.associate { it.terminalId to it.state }
        activeConnection?.sessions = sessions
        _state.update { state ->
            val selected = state.selectedSession?.let { selectedSession ->
                sessions.firstOrNull { it.terminalId == selectedSession.terminalId }
                    ?: selectedSession
            }
            state.copy(
                sessions = sessions,
                connectedProfileIds = connectedProfileIds(),
                selectedSession = selected,
                paneTitle = selected?.paneTitle ?: state.paneTitle,
                attentionNotificationTerminalIds = profile?.let {
                    attentionPreferences.enabledTerminalIds(it.id)
                }.orEmpty(),
                unreadAttentionTerminalIds = profile?.let {
                    attentionState.unreadTerminalIds(it.id)
                }.orEmpty(),
                pinnedTerminalIds = profile?.let {
                    terminalPreferences.pinnedTerminalIds(it.id)
                }.orEmpty(),
                terminalInteractionTimes = profile?.let {
                    terminalPreferences.interactionTimes(
                        it.id,
                        sessions.map { session -> session.terminalId }
                    )
                }.orEmpty(),
                terminalActivityTimes = profile?.let {
                    terminalPreferences.activityTimes(
                        it.id,
                        sessions.map { session -> session.terminalId }
                    )
                }.orEmpty(),
                terminalSortMode = profile?.let {
                    terminalPreferences.sortMode(it.id)
                } ?: TerminalSortMode.ACTIVITY,
                notificationHistory = notificationHistoryStore.load()
            )
        }
    }

    private suspend fun refreshConsole(sessionName: String, reportError: Boolean) {
        val connection = activeConnection ?: return
        runCatching {
            withContext(Dispatchers.IO) { connection.tmux.capture(sessionName) }
        }.onSuccess { snapshot ->
            if (activeProfileId != connection.profile.id) return@onSuccess
            consoleFailureReported = false
            val priorSnapshot = _state.value
            val terminalId = priorSnapshot.selectedSession?.terminalId
            if (
                terminalId != null &&
                priorSnapshot.transcript.isNotBlank() &&
                priorSnapshot.transcript != snapshot.transcript
            ) {
                markTerminalActivity(terminalId)
            }
            _state.update { current ->
                val prior = current.selectedSession
                val updated = prior?.copy(
                    paneTitle = snapshot.paneTitle,
                    paneDead = snapshot.paneDead,
                    state = TmuxGateway.stateFrom(
                        snapshot.paneTitle,
                        prior.profile,
                        snapshot.paneDead
                    )
                )
                current.copy(
                    selectedSession = updated,
                    sessions = if (updated == null) {
                        current.sessions
                    } else {
                        current.sessions.map { session ->
                            if (session.terminalId == updated.terminalId) updated else session
                        }
                    },
                    paneTitle = snapshot.paneTitle,
                    transcript = snapshot.transcript,
                    connectedProfileIds = connectedProfileIds()
                )
            }
        }.onFailure { error ->
            _state.update { it.copy(connectedProfileIds = connectedProfileIds()) }
            if (reportError && !consoleFailureReported) {
                consoleFailureReported = true
                showError(messageFor(error))
            }
        }
    }

    private fun awaitHostKeyDecision(message: String): Boolean {
        val request = PendingTrust(UUID.randomUUID().toString())
        if (!pendingTrust.compareAndSet(null, request)) return false
        _state.update {
            it.copy(
                hostKeyPrompt = HostKeyPrompt(
                    id = request.id,
                    message = message.ifBlank {
                        "This server is not known yet. Verify its SSH fingerprint before trusting it."
                    }
                )
            )
        }
        val answered = request.latch.await(2, TimeUnit.MINUTES)
        pendingTrust.compareAndSet(request, null)
        _state.update { current ->
            if (current.hostKeyPrompt?.id == request.id) current.copy(hostKeyPrompt = null)
            else current
        }
        return answered && request.accepted
    }

    private fun validateProfile(profile: ServerProfile, selectedKey: Uri?): String? {
        if (profile.name.isBlank()) return "Give this server a name."
        if (
            profile.host.isBlank() ||
            profile.host.any { it.isWhitespace() || it.code < 32 || it.code == 127 }
        ) {
            return "Enter a valid SSH host name or IP address."
        }
        if (profile.port !in 1..65_535) return "SSH port must be between 1 and 65535."
        if (
            profile.username.isBlank() ||
            profile.username.any { it.isWhitespace() || it.code < 32 || it.code == 127 }
        ) {
            return "Enter a valid SSH username."
        }
        if (
            profile.authMode == AuthMode.PRIVATE_KEY &&
            selectedKey == null &&
            !profileStore.hasPrivateKey(profile.id)
        ) {
            return "Choose a private key for this server."
        }
        if (
            profile.icon != null &&
            (
                profile.icon.toByteArray().size > 32 ||
                    profile.icon.any { it.code < 32 || it.code == 127 }
                )
        ) {
            return "Choose a valid server icon."
        }
        return null
    }

    private fun finishWithError(error: Throwable) {
        _state.update {
            it.copy(
                isBusy = false,
                isSending = false,
                isLoadingFiles = false,
                isLoadingFilePreview = false,
                isDownloading = false,
                isLoadingActions = false,
                runningActionId = null,
                downloadProgress = null,
                connectedProfileIds = connectedProfileIds(),
                error = messageFor(error)
            )
        }
    }

    private fun showError(message: String) {
        _state.update { it.copy(error = message) }
    }

    private fun messageFor(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: "Something went wrong."

    private companion object {
        const val SESSION_POLL_INTERVAL_MS = 6_000L
    }
}
