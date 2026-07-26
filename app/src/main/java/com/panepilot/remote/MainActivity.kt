package com.panepilot.remote

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.panepilot.remote.ui.CredentialsScreen
import com.panepilot.remote.ui.HostKeyDialog
import com.panepilot.remote.ui.PanePilotTheme
import com.panepilot.remote.ui.RemoteFilesScreen
import com.panepilot.remote.ui.ServerEditorScreen
import com.panepilot.remote.ui.ServerListScreen
import com.panepilot.remote.ui.SessionWorkspaceScreen
import com.panepilot.remote.ui.Ink

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(0x00000000),
            navigationBarStyle = SystemBarStyle.dark(0x00000000)
        )
        setContent {
            PanePilotTheme {
                val appViewModel: AppViewModel = viewModel()
                val state by appViewModel.state.collectAsStateWithLifecycle()
                val snackbar = remember { SnackbarHostState() }
                var pendingNotificationTerminalId by remember {
                    mutableStateOf<String?>(null)
                }
                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted ->
                    pendingNotificationTerminalId?.let { terminalId ->
                        if (granted) {
                            appViewModel.setAttentionNotificationsEnabled(terminalId, true)
                        } else {
                            appViewModel.notificationPermissionDenied()
                        }
                    }
                    pendingNotificationTerminalId = null
                }
                val downloadDestinationLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("*/*")
                ) { destination ->
                    appViewModel.finishDownloadDestination(destination)
                }

                LaunchedEffect(state.error) {
                    state.error?.let {
                        snackbar.showSnackbar(it)
                        appViewModel.clearError()
                    }
                }

                LaunchedEffect(state.notice) {
                    state.notice?.let {
                        snackbar.showSnackbar(it)
                        appViewModel.clearNotice()
                    }
                }

                LaunchedEffect(state.pendingDownload?.id) {
                    state.pendingDownload?.let { request ->
                        downloadDestinationLauncher.launch(request.file.name)
                    }
                }

                LaunchedEffect(state.connectedProfile?.id) {
                    if (
                        state.connectedProfile != null &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermissionLauncher.launch(
                            Manifest.permission.POST_NOTIFICATIONS
                        )
                    }
                }

                BackHandler(enabled = state.screen == AppScreen.Sessions) {
                    moveTaskToBack(true)
                }

                BackHandler(
                    enabled =
                        state.screen != AppScreen.Servers &&
                            state.screen != AppScreen.Sessions
                ) {
                    appViewModel.goBack()
                }

                Scaffold(
                    containerColor = Ink,
                    snackbarHost = { SnackbarHost(snackbar) },
                    contentWindowInsets = WindowInsets(0, 0, 0, 0)
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        when (val screen = state.screen) {
                            AppScreen.Servers -> ServerListScreen(
                                profiles = state.profiles,
                                onAdd = appViewModel::addServer,
                                onEdit = appViewModel::editServer,
                                onConnect = appViewModel::openCredentials
                            )

                            is AppScreen.EditServer -> ServerEditorScreen(
                                profile = screen.profileId?.let(appViewModel::profile),
                                hasStoredKey = screen.profileId?.let(appViewModel::hasPrivateKey) == true,
                                isBusy = state.isBusy,
                                onBack = appViewModel::goBack,
                                onSave = appViewModel::saveServer,
                                onDelete = screen.profileId?.let { id ->
                                    { appViewModel.deleteServer(id) }
                                },
                                onForgetHostKey = screen.profileId?.let { id ->
                                    { appViewModel.forgetHostKey(id) }
                                }
                            )

                            is AppScreen.Credentials -> {
                                appViewModel.profile(screen.profileId)?.let { profile ->
                                    val rememberedPassword = remember(screen.profileId) {
                                        appViewModel.rememberedPassword(screen.profileId)
                                    }
                                    CredentialsScreen(
                                        profile = profile,
                                        rememberedPassword = rememberedPassword,
                                        isBusy = state.isBusy,
                                        onBack = appViewModel::goBack,
                                        onForgetPassword = {
                                            appViewModel.forgetPassword(profile.id)
                                        },
                                        onConnect = { secret, rememberPassword ->
                                            appViewModel.connect(
                                                profile.id,
                                                secret,
                                                rememberPassword
                                            )
                                        }
                                    )
                                }
                            }

                            AppScreen.Sessions, is AppScreen.Console -> SessionWorkspaceScreen(
                                profile = state.connectedProfile,
                                sessions = state.sessions,
                                selectedSession = state.selectedSession,
                                transcript = state.transcript,
                                paneTitle = state.paneTitle,
                                composer = state.composer,
                                isRefreshing = state.isBusy,
                                isSending = state.isSending,
                                onRefresh = appViewModel::refreshSessions,
                                onDisconnect = appViewModel::disconnect,
                                onOpenSession = appViewModel::openConsole,
                                onComposerChange = appViewModel::updateComposer,
                                onSend = appViewModel::sendMessage,
                                onTerminalKey = appViewModel::sendTerminalKey,
                                notificationTerminalIds =
                                    state.attentionNotificationTerminalIds,
                                onToggleNotifications = { terminalId, enabled ->
                                    if (!enabled) {
                                        appViewModel.setAttentionNotificationsEnabled(
                                            terminalId,
                                            false
                                        )
                                    } else if (
                                        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                                        PackageManager.PERMISSION_GRANTED
                                    ) {
                                        appViewModel.setAttentionNotificationsEnabled(
                                            terminalId,
                                            true
                                        )
                                    } else {
                                        pendingNotificationTerminalId = terminalId
                                        notificationPermissionLauncher.launch(
                                            Manifest.permission.POST_NOTIFICATIONS
                                        )
                                    }
                                },
                                onBrowseFiles = appViewModel::openFiles,
                                onOpenUrl = { value ->
                                    val uri = Uri.parse(value)
                                    if (uri.scheme !in setOf("http", "https")) {
                                        appViewModel.externalUrlOpenFailed()
                                    } else {
                                        runCatching {
                                            startActivity(
                                                Intent(Intent.ACTION_VIEW, uri).apply {
                                                    addCategory(Intent.CATEGORY_BROWSABLE)
                                                }
                                            )
                                        }.onFailure {
                                            appViewModel.externalUrlOpenFailed()
                                        }
                                    }
                                },
                                onOpenPath = appViewModel::openFilesAtPath
                            )

                            is AppScreen.Files -> RemoteFilesScreen(
                                projectName = state.selectedSession?.projectName.orEmpty(),
                                rootPath = state.remoteFileRoot,
                                relativePath = state.remoteFilePath,
                                files = state.remoteFiles,
                                highlightedPath = state.highlightedRemoteFilePath,
                                isLoading = state.isLoadingFiles,
                                isDownloading = state.isDownloading,
                                downloadProgress = state.downloadProgress,
                                onBack = appViewModel::goBack,
                                onUp = appViewModel::goUpRemoteDirectory,
                                onOpenDirectory = appViewModel::openRemoteDirectory,
                                onDownload = appViewModel::requestDownload
                            )
                        }
                    }
                }

                state.hostKeyPrompt?.let { prompt ->
                    HostKeyDialog(
                        prompt = prompt,
                        onAnswer = { appViewModel.answerHostKey(prompt.id, it) }
                    )
                }
            }
        }
    }
}
