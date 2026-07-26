package com.panepilot.remote

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.panepilot.remote.data.AttentionPreferenceStore
import com.panepilot.remote.data.CredentialStore
import com.panepilot.remote.data.ProfileStore
import com.panepilot.remote.model.AuthMode
import com.panepilot.remote.model.ConnectionSecret
import com.panepilot.remote.model.PanePilotSession
import com.panepilot.remote.model.RemoteDownloadRequest
import com.panepilot.remote.model.RemoteFileEntry
import com.panepilot.remote.model.ServerProfile
import com.panepilot.remote.model.SessionState
import com.panepilot.remote.model.TerminalKey
import com.panepilot.remote.notifications.AttentionNotifier
import com.panepilot.remote.notifications.newlyAttentionRequired
import com.panepilot.remote.ssh.RemoteFileGateway
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
}

data class HostKeyPrompt(
    val id: String,
    val message: String
)

data class AppUiState(
    val profiles: List<ServerProfile> = emptyList(),
    val screen: AppScreen = AppScreen.Servers,
    val connectedProfile: ServerProfile? = null,
    val sessions: List<PanePilotSession> = emptyList(),
    val selectedSession: PanePilotSession? = null,
    val transcript: String = "",
    val paneTitle: String = "",
    val composer: String = "",
    val isBusy: Boolean = false,
    val isSending: Boolean = false,
    val attentionNotificationTerminalIds: Set<String> = emptySet(),
    val remoteFileRoot: String = "",
    val remoteFilePath: String = "",
    val remoteFiles: List<RemoteFileEntry> = emptyList(),
    val highlightedRemoteFilePath: String? = null,
    val isLoadingFiles: Boolean = false,
    val pendingDownload: RemoteDownloadRequest? = null,
    val isDownloading: Boolean = false,
    val downloadProgress: Int? = null,
    val hostKeyPrompt: HostKeyPrompt? = null,
    val notice: String? = null,
    val error: String? = null
)

private data class PendingTrust(
    val id: String,
    val latch: CountDownLatch = CountDownLatch(1),
    @Volatile var accepted: Boolean = false
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val profileStore = ProfileStore(application)
    private val credentialStore = CredentialStore(application)
    private val attentionPreferences = AttentionPreferenceStore(application)
    private val attentionNotifier = AttentionNotifier(application)
    private val pendingTrust = AtomicReference<PendingTrust?>(null)
    private val ssh = SshConnection(application, profileStore, ::awaitHostKeyDecision)
    private val tmux = TmuxGateway(ssh)
    private val remoteFiles = RemoteFileGateway(ssh)
    private val _state = MutableStateFlow(AppUiState(profiles = profileStore.load()))
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    private var consolePolling: Job? = null
    private var sessionPolling: Job? = null
    private var fileLoading: Job? = null
    private var consoleFailureReported = false
    private var lastSessionStates: Map<String, SessionState> = emptyMap()

    fun addServer() {
        _state.update { it.copy(screen = AppScreen.EditServer(null), error = null) }
    }

    fun editServer(profileId: String) {
        _state.update { it.copy(screen = AppScreen.EditServer(profileId), error = null) }
    }

    fun openCredentials(profileId: String) {
        _state.update { it.copy(screen = AppScreen.Credentials(profileId), error = null) }
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
            val profiles = withContext(Dispatchers.IO) {
                credentialStore.remove(profileId)
                profileStore.delete(profileId)
            }
            val notificationIds = attentionPreferences.enabledTerminalIds(profileId)
            attentionNotifier.cancelProfile(profileId, notificationIds)
            attentionPreferences.removeProfile(profileId)
            _state.update {
                it.copy(profiles = profiles, screen = AppScreen.Servers, error = null)
            }
        }
    }

    fun forgetHostKey(profileId: String) {
        val profile = profile(profileId) ?: return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { ssh.forgetHostKey(profile) }
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
        rememberPassword: Boolean = false
    ) {
        val profile = profile(profileId) ?: return
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, error = null) }
            runCatching {
                withContext(Dispatchers.IO) {
                    ssh.connect(profile, secret)
                    tmux.reset()
                    val sessions = tmux.listSessions()
                    if (profile.authMode == AuthMode.PASSWORD) {
                        if (rememberPassword) {
                            credentialStore.savePassword(profile.id, secret.password)
                        } else {
                            credentialStore.remove(profile.id)
                        }
                    }
                    sessions
                }
            }.onSuccess { sessions ->
                lastSessionStates = sessions.associate { it.terminalId to it.state }
                _state.update {
                    it.copy(
                        connectedProfile = profile,
                        sessions = sessions,
                        attentionNotificationTerminalIds =
                            attentionPreferences.enabledTerminalIds(profile.id),
                        screen = AppScreen.Sessions,
                        isBusy = false,
                        composer = "",
                        transcript = "",
                        notice = null
                    )
                }
                startSessionPolling()
            }.onFailure { error ->
                ssh.disconnect()
                finishWithError(error)
            }
        }
    }

    fun disconnect() {
        consolePolling?.cancel()
        consolePolling = null
        sessionPolling?.cancel()
        sessionPolling = null
        fileLoading?.cancel()
        fileLoading = null
        lastSessionStates = emptyMap()
        ssh.disconnect()
        tmux.reset()
        _state.update {
            it.copy(
                connectedProfile = null,
                sessions = emptyList(),
                selectedSession = null,
                transcript = "",
                paneTitle = "",
                composer = "",
                isBusy = false,
                isSending = false,
                attentionNotificationTerminalIds = emptySet(),
                remoteFileRoot = "",
                remoteFilePath = "",
                remoteFiles = emptyList(),
                highlightedRemoteFilePath = null,
                isLoadingFiles = false,
                pendingDownload = null,
                isDownloading = false,
                downloadProgress = null,
                screen = AppScreen.Servers
            )
        }
    }

    fun refreshSessions() {
        if (!ssh.isConnected || _state.value.isBusy) return
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, error = null) }
            runCatching {
                withContext(Dispatchers.IO) { tmux.listSessions() }
            }.onSuccess { sessions ->
                applySessionScan(sessions, detectTransitions = true)
                _state.update { it.copy(isBusy = false) }
            }.onFailure(::finishWithError)
        }
    }

    fun openConsole(sessionName: String) {
        val selected = _state.value.sessions.firstOrNull { it.name == sessionName } ?: return
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
                pendingDownload = null,
                error = null
            )
        }
        loadRemoteDirectory("")
    }

    fun openFilesAtPath(reference: String) {
        val session = _state.value.selectedSession ?: return
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
                isLoadingFiles = true,
                pendingDownload = null,
                error = null
            )
        }
        fileLoading = viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val location = remoteFiles.locate(session.projectPath, reference)
                    location to remoteFiles.list(
                        session.projectPath,
                        location.directoryRelativePath
                    )
                }
            }.onSuccess { (location, files) ->
                _state.update {
                    it.copy(
                        remoteFilePath = location.directoryRelativePath,
                        remoteFiles = files,
                        highlightedRemoteFilePath = location.selectedFileRelativePath,
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
                        remoteFiles.download(
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
            attentionNotifier.show(profile, session)
        } else if (!enabled) {
            attentionNotifier.cancel(profile.id, terminalId)
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
        val sessionName = current.selectedSession?.name ?: return
        val message = current.composer
        if (message.isBlank() || current.isSending) return
        viewModelScope.launch {
            _state.update { it.copy(isSending = true, error = null) }
            runCatching {
                withContext(Dispatchers.IO) { tmux.send(sessionName, message) }
            }.onSuccess {
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
        val sessionName = _state.value.selectedSession?.name ?: return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { tmux.sendKey(sessionName, key) }
            }.onSuccess {
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

            AppScreen.Sessions -> disconnect()
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
                        isLoadingFiles = false,
                        pendingDownload = null,
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
    }

    override fun onCleared() {
        pendingTrust.getAndSet(null)?.latch?.countDown()
        consolePolling?.cancel()
        sessionPolling?.cancel()
        fileLoading?.cancel()
        ssh.disconnect()
        super.onCleared()
    }

    private fun loadRemoteDirectory(relativePath: String) {
        if (!ssh.isConnected || _state.value.isLoadingFiles) return
        val root = _state.value.remoteFileRoot
        fileLoading = viewModelScope.launch {
            _state.update {
                it.copy(
                    isLoadingFiles = true,
                    highlightedRemoteFilePath = null,
                    error = null
                )
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    remoteFiles.list(root, relativePath)
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
        sessionPolling = viewModelScope.launch {
            delay(SESSION_POLL_INTERVAL_MS)
            while (ssh.isConnected && _state.value.connectedProfile != null) {
                if (!_state.value.isBusy && !_state.value.isDownloading) {
                    runCatching {
                        withContext(Dispatchers.IO) { tmux.listSessions() }
                    }.onSuccess { sessions ->
                        applySessionScan(sessions, detectTransitions = true)
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
        if (detectTransitions && profile != null) {
            val enabledIds = current.attentionNotificationTerminalIds
            newlyAttentionRequired(lastSessionStates, sessions)
                .filter { it.terminalId in enabledIds }
                .forEach { attentionNotifier.show(profile, it) }
            sessions
                .filter {
                    lastSessionStates[it.terminalId] == SessionState.NEEDS_INPUT &&
                        it.state != SessionState.NEEDS_INPUT
                }
                .forEach { attentionNotifier.cancel(profile.id, it.terminalId) }
        }
        lastSessionStates = sessions.associate { it.terminalId to it.state }
        _state.update { state ->
            val selected = state.selectedSession?.let { selectedSession ->
                sessions.firstOrNull { it.terminalId == selectedSession.terminalId }
                    ?: selectedSession
            }
            state.copy(
                sessions = sessions,
                selectedSession = selected,
                paneTitle = selected?.paneTitle ?: state.paneTitle
            )
        }
    }

    private suspend fun refreshConsole(sessionName: String, reportError: Boolean) {
        runCatching {
            withContext(Dispatchers.IO) { tmux.capture(sessionName) }
        }.onSuccess { snapshot ->
            consoleFailureReported = false
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
                    transcript = snapshot.transcript
                )
            }
        }.onFailure { error ->
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
        return null
    }

    private fun finishWithError(error: Throwable) {
        _state.update {
            it.copy(
                isBusy = false,
                isSending = false,
                isLoadingFiles = false,
                isDownloading = false,
                downloadProgress = null,
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
