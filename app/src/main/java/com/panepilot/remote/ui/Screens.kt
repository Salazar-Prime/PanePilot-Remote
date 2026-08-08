package com.panepilot.remote.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.panepilot.remote.HostKeyPrompt
import com.panepilot.remote.model.AuthMode
import com.panepilot.remote.model.ConnectionSecret
import com.panepilot.remote.model.PanePilotSession
import com.panepilot.remote.model.ProjectAction
import com.panepilot.remote.model.RemoteFileEntry
import com.panepilot.remote.model.RemoteFilePreview
import com.panepilot.remote.model.RemoteFilePreviewKind
import com.panepilot.remote.model.ServerProfile
import com.panepilot.remote.model.SessionState
import com.panepilot.remote.model.TerminalKey
import com.panepilot.remote.model.TerminalSortMode
import com.panepilot.remote.notifications.AttentionEventType
import com.panepilot.remote.notifications.NotificationHistoryEntry
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

private val SERVER_ICONS = listOf("⚒️", "🖥️", "🧱", "🧭", "☁️", "🔬", "🏠", "🚀")

@Composable
fun ServerListScreen(
    profiles: List<ServerProfile>,
    connectedProfileIds: Set<String>,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    onConnect: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        ScreenHeader(
            eyebrow = "PANE / REMOTE",
            title = "Your servers",
            action = {
                FilledIconButton(onClick = onAdd) {
                    Icon(Icons.Default.Add, contentDescription = "Add server")
                }
            }
        )
        if (profiles.isEmpty()) {
            EmptyServers(onAdd)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = 8.dp,
                    bottom = 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        "Connect more than one server and keep them warm while you switch.",
                        color = Muted,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                items(profiles, key = { it.id }) { profile ->
                    ServerCard(
                        profile = profile,
                        connected = profile.id in connectedProfileIds,
                        onEdit = { onEdit(profile.id) },
                        onConnect = { onConnect(profile.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyServers(onAdd: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = Slate,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Text(
                    ">_",
                    color = Sky,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 18.dp)
                )
            }
            Spacer(Modifier.height(24.dp))
            Text(
                "Add your first SSH server",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "PanePilot Remote connects directly. It does not need a relay or remote daemon.",
                color = Muted,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(22.dp))
            Button(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Add server")
            }
        }
    }
}

@Composable
private fun ServerCard(
    profile: ServerProfile,
    connected: Boolean,
    onEdit: () -> Unit,
    onConnect: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Slate,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onConnect)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SlateRaised),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    profile.icon ?: profile.name.take(1).uppercase(),
                    color = Sky,
                    fontSize = if (profile.icon == null) 16.sp else 21.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    profile.name,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "${profile.username}@${profile.host}:${profile.port}",
                    color = Muted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (connected) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(Success)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "CONNECTED · TAP TO SWITCH",
                            color = Success,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.7.sp
                        )
                    }
                }
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Edit ${profile.name}", tint = Muted)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerEditorScreen(
    profile: ServerProfile?,
    hasStoredKey: Boolean,
    isBusy: Boolean,
    onBack: () -> Unit,
    onSave: (ServerProfile, Uri?) -> Unit,
    onDelete: (() -> Unit)?,
    onForgetHostKey: (() -> Unit)?
) {
    val draftId = remember(profile?.id) { profile?.id ?: UUID.randomUUID().toString() }
    var name by rememberSaveable(profile?.id) { mutableStateOf(profile?.name.orEmpty()) }
    var host by rememberSaveable(profile?.id) { mutableStateOf(profile?.host.orEmpty()) }
    var port by rememberSaveable(profile?.id) {
        mutableStateOf(profile?.port?.toString() ?: "22")
    }
    var username by rememberSaveable(profile?.id) {
        mutableStateOf(profile?.username.orEmpty())
    }
    var authMode by rememberSaveable(profile?.id) {
        mutableStateOf(profile?.authMode ?: AuthMode.PRIVATE_KEY)
    }
    var serverIcon by rememberSaveable(profile?.id) { mutableStateOf(profile?.icon.orEmpty()) }
    var selectedKey by remember { mutableStateOf<Uri?>(null) }
    var selectedKeyName by rememberSaveable(profile?.id) {
        mutableStateOf(profile?.keyDisplayName.orEmpty())
    }
    var deleteConfirm by remember { mutableStateOf(false) }
    var forgetConfirm by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val keyPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            selectedKey = uri
            selectedKeyName = context.displayName(uri) ?: "Selected private key"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        BackHeader(
            title = if (profile == null) "Add server" else "Edit server",
            onBack = onBack
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                placeholder = { Text("Build server") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Server icon",
                color = Muted,
                style = MaterialTheme.typography.labelLarge
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SERVER_ICONS.forEach { candidate ->
                    val selected = serverIcon == candidate
                    Surface(
                        color = if (selected) SlateRaised else Slate,
                        border = BorderStroke(
                            1.dp,
                            if (selected) Sky else MaterialTheme.colorScheme.outlineVariant
                        ),
                        shape = RoundedCornerShape(11.dp),
                        modifier = Modifier
                            .size(44.dp)
                            .clickable { serverIcon = candidate }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(candidate, fontSize = 21.sp)
                        }
                    }
                }
            }
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("Host or IP address") },
                placeholder = { Text("server.example.com") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = { Text("Port") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.width(104.dp)
                )
            }
            Text(
                "Authentication",
                color = Muted,
                style = MaterialTheme.typography.labelLarge
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(
                    selected = authMode == AuthMode.PRIVATE_KEY,
                    onClick = { authMode = AuthMode.PRIVATE_KEY },
                    label = { Text("Private key") }
                )
                FilterChip(
                    selected = authMode == AuthMode.PASSWORD,
                    onClick = { authMode = AuthMode.PASSWORD },
                    label = { Text("Password") }
                )
            }
            AnimatedVisibility(authMode == AuthMode.PRIVATE_KEY) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Slate,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            when {
                                selectedKeyName.isNotBlank() -> selectedKeyName
                                hasStoredKey -> "Private key stored"
                                else -> "No private key selected"
                            },
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(5.dp))
                        Text(
                            "The key is copied into this app's private storage. Passphrases are never saved.",
                            color = Muted,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = { keyPicker.launch(arrayOf("*/*")) }) {
                            Text(if (hasStoredKey || selectedKey != null) "Replace key" else "Choose key")
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    onSave(
                        ServerProfile(
                            id = draftId,
                            name = name,
                            host = host,
                            port = port.toIntOrNull() ?: 0,
                            username = username,
                            authMode = authMode,
                            keyDisplayName = selectedKeyName.takeIf { it.isNotBlank() },
                            icon = serverIcon.takeIf { it.isNotBlank() }
                        ),
                        selectedKey
                    )
                },
                enabled = !isBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                if (isBusy) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp),
                        color = Color.White
                    )
                } else {
                    Text("Save server")
                }
            }
            if (profile != null) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                Text(
                    "SSH identity",
                    color = Muted,
                    style = MaterialTheme.typography.labelLarge
                )
                Text(
                    "If your server is rebuilt and its verified fingerprint changes, remove the saved host key before reconnecting.",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(onClick = { forgetConfirm = true }) {
                    Text("Forget saved host key")
                }
                TextButton(
                    onClick = { deleteConfirm = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = Danger)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(Modifier.width(7.dp))
                    Text("Delete server")
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    if (deleteConfirm && onDelete != null) {
        ConfirmDialog(
            title = "Delete ${profile?.name}?",
            body = "This removes the server profile and its imported private key from this phone. It does not change the server.",
            confirmLabel = "Delete",
            destructive = true,
            onDismiss = { deleteConfirm = false },
            onConfirm = onDelete
        )
    }
    if (forgetConfirm && onForgetHostKey != null) {
        ConfirmDialog(
            title = "Forget saved host key?",
            body = "Verify the SSH fingerprint carefully the next time you connect.",
            confirmLabel = "Forget key",
            destructive = true,
            onDismiss = { forgetConfirm = false },
            onConfirm = {
                forgetConfirm = false
                onForgetHostKey()
            }
        )
    }
}

@Composable
fun CredentialsScreen(
    profile: ServerProfile,
    rememberedPassword: String?,
    isBusy: Boolean,
    onBack: () -> Unit,
    onForgetPassword: () -> Unit,
    onConnect: (ConnectionSecret, Boolean) -> Unit
) {
    var password by remember(profile.id) { mutableStateOf(rememberedPassword.orEmpty()) }
    var passphrase by remember(profile.id) { mutableStateOf("") }
    var rememberPassword by remember(profile.id) {
        mutableStateOf(rememberedPassword != null)
    }
    val isPassword = profile.authMode == AuthMode.PASSWORD

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        BackHeader(title = "Connect", onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Text(
                profile.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(5.dp))
            Text(
                "${profile.username}@${profile.host}:${profile.port}",
                color = Muted,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(28.dp))
            OutlinedTextField(
                value = if (isPassword) password else passphrase,
                onValueChange = { if (isPassword) password = it else passphrase = it },
                label = {
                    Text(if (isPassword) "SSH password" else "Private key passphrase (optional)")
                },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            Text(
                if (isPassword) {
                    "Stored only when you enable Remember on this phone."
                } else {
                    "Leave this blank if the private key has no passphrase."
                },
                color = Muted,
                style = MaterialTheme.typography.bodySmall
            )
            AnimatedVisibility(isPassword) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Slate,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 18.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(15.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Remember on this phone",
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                "Encrypted with Android Keystore. App backups are disabled.",
                                color = Muted,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = rememberPassword,
                            onCheckedChange = { checked ->
                                rememberPassword = checked
                                if (!checked) onForgetPassword()
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    onConnect(
                        ConnectionSecret(password = password, keyPassphrase = passphrase),
                        rememberPassword
                    )
                },
                enabled = !isBusy && (!isPassword || password.isNotEmpty()),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                if (isBusy) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp),
                        color = Color.White
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Connecting…")
                } else {
                    Text("Connect over SSH")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionWorkspaceScreen(
    profile: ServerProfile?,
    connectionOnline: Boolean,
    sessions: List<PanePilotSession>,
    selectedSession: PanePilotSession?,
    transcript: String,
    paneTitle: String,
    composer: String,
    isRefreshing: Boolean,
    isSending: Boolean,
    onRefresh: () -> Unit,
    onShowServers: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenSession: (String) -> Unit,
    onComposerChange: (String) -> Unit,
    onSend: () -> Unit,
    onTerminalKey: (TerminalKey) -> Unit,
    notificationTerminalIds: Set<String>,
    unreadAttentionTerminalIds: Set<String>,
    pinnedTerminalIds: Set<String>,
    terminalInteractionTimes: Map<String, Long>,
    terminalActivityTimes: Map<String, Long>,
    terminalSortMode: TerminalSortMode,
    notificationHistory: List<NotificationHistoryEntry>,
    onToggleNotifications: (terminalId: String, enabled: Boolean) -> Unit,
    onTogglePin: (terminalId: String, pinned: Boolean) -> Unit,
    onSetSortMode: (TerminalSortMode) -> Unit,
    onRefreshNotificationHistory: () -> Unit,
    onOpenNotificationHistoryEntry: (profileId: String, terminalId: String) -> Unit,
    onBrowseFiles: () -> Unit,
    onRunActions: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenPath: (String) -> Unit
) {
    val drawerState = rememberDrawerState(
        initialValue = if (selectedSession == null) DrawerValue.Open else DrawerValue.Closed
    )
    val scope = rememberCoroutineScope()
    var showNotificationHistory by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(selectedSession?.terminalId) {
        if (selectedSession == null) {
            drawerState.open()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = Ink,
                drawerContentColor = MaterialTheme.colorScheme.onBackground
            ) {
                SessionDrawerContent(
                    profile = profile,
                    connectionOnline = connectionOnline,
                    sessions = sessions,
                    selectedSession = selectedSession,
                    isRefreshing = isRefreshing,
                    onRefresh = onRefresh,
                    onShowServers = onShowServers,
                    onDisconnect = onDisconnect,
                    notificationTerminalIds = notificationTerminalIds,
                    unreadAttentionTerminalIds = unreadAttentionTerminalIds,
                    pinnedTerminalIds = pinnedTerminalIds,
                    terminalInteractionTimes = terminalInteractionTimes,
                    terminalActivityTimes = terminalActivityTimes,
                    terminalSortMode = terminalSortMode,
                    onToggleNotifications = onToggleNotifications,
                    onTogglePin = onTogglePin,
                    onSetSortMode = onSetSortMode,
                    onShowNotificationHistory = {
                        onRefreshNotificationHistory()
                        showNotificationHistory = true
                    },
                    onOpenSession = { sessionName ->
                        onOpenSession(sessionName)
                        scope.launch { drawerState.close() }
                    }
                )
            }
        }
    ) {
        if (selectedSession == null) {
            EmptySessionWorkspace(
                profile = profile,
                onShowSessions = { scope.launch { drawerState.open() } }
            )
        } else {
            SessionConsoleScreen(
                session = selectedSession,
                transcript = transcript,
                paneTitle = paneTitle,
                composer = composer,
                isSending = isSending,
                onShowSessions = { scope.launch { drawerState.open() } },
                onBrowseFiles = onBrowseFiles,
                onRunActions = onRunActions,
                onComposerChange = onComposerChange,
                onSend = onSend,
                onTerminalKey = onTerminalKey,
                onOpenUrl = onOpenUrl,
                onOpenPath = onOpenPath
            )
        }
    }
    if (showNotificationHistory) {
        NotificationHistorySheet(
            entries = notificationHistory,
            onDismiss = { showNotificationHistory = false },
            onOpenEntry = { entry ->
                showNotificationHistory = false
                onOpenNotificationHistoryEntry(entry.profileId, entry.terminalId)
                scope.launch { drawerState.close() }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionDrawerContent(
    profile: ServerProfile?,
    connectionOnline: Boolean,
    sessions: List<PanePilotSession>,
    selectedSession: PanePilotSession?,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onShowServers: () -> Unit,
    onDisconnect: () -> Unit,
    notificationTerminalIds: Set<String>,
    unreadAttentionTerminalIds: Set<String>,
    pinnedTerminalIds: Set<String>,
    terminalInteractionTimes: Map<String, Long>,
    terminalActivityTimes: Map<String, Long>,
    terminalSortMode: TerminalSortMode,
    onToggleNotifications: (terminalId: String, enabled: Boolean) -> Unit,
    onTogglePin: (terminalId: String, pinned: Boolean) -> Unit,
    onSetSortMode: (TerminalSortMode) -> Unit,
    onShowNotificationHistory: () -> Unit,
    onOpenSession: (String) -> Unit
) {
    var showSortMenu by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        ScreenHeader(
            eyebrow = if (connectionOnline) {
                "${profile?.icon.orEmpty()} SSH / ${profile?.name?.uppercase().orEmpty()}".trim()
            } else {
                "${profile?.icon.orEmpty()} SSH / ${profile?.name?.uppercase().orEmpty()} / OFFLINE".trim()
            },
            title = "Sessions",
            action = {
                Row {
                    IconButton(onClick = onShowNotificationHistory) {
                        Icon(Icons.Default.History, contentDescription = "Notification history")
                    }
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(
                                Icons.AutoMirrored.Filled.Sort,
                                contentDescription = "Sort by ${terminalSortMode.label}"
                            )
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false }
                        ) {
                            TerminalSortMode.entries.forEach { sortMode ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            sortMode.label,
                                            color = if (terminalSortMode == sortMode) Sky else {
                                                MaterialTheme.colorScheme.onSurface
                                            }
                                        )
                                    },
                                    onClick = {
                                        onSetSortMode(sortMode)
                                        showSortMenu = false
                                    }
                                )
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            DropdownMenuItem(
                                text = { Text("Disconnect server") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.PowerSettingsNew,
                                        contentDescription = null,
                                        tint = Muted
                                    )
                                },
                                onClick = {
                                    showSortMenu = false
                                    onDisconnect()
                                }
                            )
                        }
                    }
                    IconButton(onClick = onRefresh, enabled = !isRefreshing) {
                        if (isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh sessions")
                        }
                    }
                }
            },
            navigation = {
                IconButton(onClick = onShowServers) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "All servers")
                }
            }
        )
        if (sessions.isEmpty() && !isRefreshing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(28.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "No live PanePilot sessions",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Start a tmux-backed session from PanePilot desktop, then refresh.",
                        color = Muted,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        } else {
            val terminalListState = rememberLazyListState()
            LaunchedEffect(terminalSortMode, pinnedTerminalIds) {
                terminalListState.scrollToItem(0)
            }
            val groups = sessionGroups(
                sessions = sessions,
                pinnedTerminalIds = pinnedTerminalIds,
                unreadAttentionTerminalIds = unreadAttentionTerminalIds,
                sortMode = terminalSortMode,
                interactionTimes = terminalInteractionTimes,
                activityTimes = terminalActivityTimes
            )
            LazyColumn(
                state = terminalListState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    bottom = 28.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                groups.forEach { group ->
                    stickyHeader(key = group.key) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Ink)
                                .padding(top = 10.dp, bottom = 5.dp)
                        ) {
                            Text(
                                group.title,
                                color = when (group.kind) {
                                    SessionGroupKind.ATTENTION -> Attention
                                    SessionGroupKind.PINNED -> Sky
                                    else -> MaterialTheme.colorScheme.onBackground
                                },
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                letterSpacing = 0.7.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    items(group.sessions, key = { it.terminalId }) { session ->
                        SessionCard(
                            session = session,
                            selected = session.terminalId == selectedSession?.terminalId,
                            notificationsEnabled =
                                session.terminalId in notificationTerminalIds,
                            pinned = session.terminalId in pinnedTerminalIds,
                            connectionOnline = connectionOnline,
                            onToggleNotifications = {
                                onToggleNotifications(session.terminalId, it)
                            },
                            onTogglePinned = {
                                onTogglePin(session.terminalId, it)
                            },
                            onClick = { onOpenSession(session.name) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotificationHistorySheet(
    entries: List<NotificationHistoryEntry>,
    onDismiss: () -> Unit,
    onOpenEntry: (NotificationHistoryEntry) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Ink,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight(0.88f)
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = Slate,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Icon(
                        Icons.Default.Notifications,
                        contentDescription = null,
                        tint = Sky,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Alert history",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "LAST 200 ALERTS SENT ON THIS PHONE",
                        color = Muted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.7.sp
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (entries.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "No alerts sent yet",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Completion and needs-input alerts will appear here.",
                            color = Muted,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 20.dp,
                        end = 20.dp,
                        top = 16.dp,
                        bottom = 28.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(entries, key = { it.id }) { entry ->
                        NotificationHistoryCard(
                            entry = entry,
                            onClick = { onOpenEntry(entry) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationHistoryCard(
    entry: NotificationHistoryEntry,
    onClick: () -> Unit
) {
    val eventColor = when (entry.type) {
        AttentionEventType.NEEDS_INPUT -> Attention
        AttentionEventType.RESPONSE_READY -> Sky
    }
    Surface(
        color = Slate,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            Box(
                modifier = Modifier
                    .width(5.dp)
                    .fillMaxHeight()
                    .background(eventColor)
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 15.dp, vertical = 13.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (entry.type) {
                            AttentionEventType.NEEDS_INPUT -> "NEEDS INPUT"
                            AttentionEventType.RESPONSE_READY -> "RESPONSE FINISHED"
                        },
                        color = eventColor,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.7.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        formatNotificationTime(entry.sentAtMillis),
                        color = Muted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    entry.title,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    "${entry.profileName} · ${entry.projectName}",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (entry.paneTitle.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        entry.paneTitle,
                        color = Sky,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(7.dp))
                Text(
                    "Open terminal",
                    color = eventColor,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

private fun formatNotificationTime(timestampMillis: Long): String =
    runCatching {
        DateTimeFormatter
            .ofPattern("MMM d · h:mm a", Locale.getDefault())
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(timestampMillis))
    }.getOrDefault("Earlier")

@Composable
private fun EmptySessionWorkspace(
    profile: ServerProfile?,
    onShowSessions: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        SessionWorkspaceHeader(
            title = "Live sessions",
            subtitle = profile?.name,
            onShowSessions = onShowSessions
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(28.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(
                    color = Slate,
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Icon(
                        Icons.Default.Menu,
                        contentDescription = null,
                        tint = Sky,
                        modifier = Modifier.padding(18.dp)
                    )
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    "Choose a session",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Your projects and live tmux sessions are in the side pane.",
                    color = Muted,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(20.dp))
                OutlinedButton(onClick = onShowSessions) {
                    Icon(Icons.Default.Menu, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Show sessions")
                }
            }
        }
    }
}

@Composable
private fun SessionCard(
    session: PanePilotSession,
    selected: Boolean,
    notificationsEnabled: Boolean,
    pinned: Boolean,
    connectionOnline: Boolean,
    onToggleNotifications: (Boolean) -> Unit,
    onTogglePinned: (Boolean) -> Unit,
    onClick: () -> Unit
) {
    val stateColor = if (connectionOnline) stateColor(session.state) else Muted
    val status = if (connectionOnline) stateLabel(session.state) else "OFFLINE"
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) SlateRaised else Slate,
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) Sky.copy(alpha = 0.72f) else {
                MaterialTheme.colorScheme.outlineVariant
            }
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = connectionOnline && !session.paneDead, onClick = onClick)
                .padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    session.name,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        session.projectName,
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(stateColor)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        status,
                        color = stateColor,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(
                    onClick = { onTogglePinned(!pinned) },
                    modifier = Modifier.size(31.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PushPin,
                        contentDescription = if (pinned) {
                            "Unpin ${session.name}"
                        } else {
                            "Pin ${session.name}"
                        },
                        tint = if (pinned) Sky else Muted,
                        modifier = Modifier.size(17.dp)
                    )
                }
                if (session.profile == "codex" || session.profile == "claude") {
                    IconButton(
                        onClick = { onToggleNotifications(!notificationsEnabled) },
                        enabled = connectionOnline && !session.paneDead,
                        modifier = Modifier.size(31.dp)
                    ) {
                        Icon(
                            imageVector = if (notificationsEnabled) {
                                Icons.Default.Notifications
                            } else {
                                Icons.Default.NotificationsOff
                            },
                            contentDescription = if (notificationsEnabled) {
                                "Disable attention notifications for ${session.name}"
                            } else {
                                "Enable attention notifications for ${session.name}"
                            },
                            tint = if (notificationsEnabled) Attention else Muted,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SessionConsoleScreen(
    session: PanePilotSession?,
    transcript: String,
    paneTitle: String,
    composer: String,
    isSending: Boolean,
    onShowSessions: () -> Unit,
    onBrowseFiles: () -> Unit,
    onRunActions: () -> Unit,
    onComposerChange: (String) -> Unit,
    onSend: () -> Unit,
    onTerminalKey: (TerminalKey) -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenPath: (String) -> Unit
) {
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()
    val styledTranscript = remember(transcript) {
        TerminalLinkifier.annotate(AnsiTerminalParser.parse(transcript))
    }
    LaunchedEffect(transcript) {
        delay(60)
        verticalScroll.scrollTo(verticalScroll.maxValue)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        SessionWorkspaceHeader(
            title = session?.name ?: "Session",
            onShowSessions = onShowSessions,
            subtitle = paneTitle.takeIf { it.isNotBlank() },
            onBrowseFiles = onBrowseFiles,
            onRunActions = onRunActions
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Slate)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(session?.state?.let(::stateColor) ?: Muted)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                session?.typeLabel.orEmpty(),
                color = Muted,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f)
            )
            Text(
                session?.state?.let(::stateLabel).orEmpty(),
                color = session?.state?.let(::stateColor) ?: Muted,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
        }
        TerminalKeyBar(
            enabled = session?.paneDead != true,
            onTerminalKey = onTerminalKey
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(AnsiTerminalPalette.Background)
        ) {
            if (transcript.isBlank()) {
                Text(
                    "Reading tmux pane…",
                    color = Muted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    ClickableText(
                        text = styledTranscript,
                        style = TextStyle(
                            color = AnsiTerminalPalette.Foreground,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.5.sp,
                            lineHeight = 17.sp
                        ),
                        modifier = Modifier
                            .fillMaxHeight()
                            .horizontalScroll(horizontalScroll)
                            .verticalScroll(verticalScroll)
                            .padding(14.dp),
                        onClick = { offset ->
                            styledTranscript.getStringAnnotations(
                                TerminalLinkifier.URL_TAG,
                                offset,
                                offset
                            ).firstOrNull()?.let {
                                onOpenUrl(it.item)
                                return@ClickableText
                            }
                            styledTranscript.getStringAnnotations(
                                TerminalLinkifier.PATH_TAG,
                                offset,
                                offset
                            ).firstOrNull()?.let {
                                onOpenPath(it.item)
                            }
                        }
                    )
                }
            }
        }
        Surface(
            color = Slate,
            tonalElevation = 4.dp,
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                OutlinedTextField(
                    value = composer,
                    onValueChange = onComposerChange,
                    enabled = session?.paneDead != true,
                    placeholder = {
                        Text(
                            if (session?.profile in listOf("codex", "claude")) {
                                "Message the agent"
                            } else {
                                "Send terminal input"
                            }
                        )
                    },
                    minLines = 1,
                    maxLines = 4,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(10.dp))
                FilledIconButton(
                    onClick = onSend,
                    enabled = composer.isNotBlank() && !isSending && session?.paneDead != true,
                    modifier = Modifier.size(52.dp)
                ) {
                    if (isSending) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp),
                            color = Color.White
                        )
                    } else {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        }
    }
}

@Composable
private fun TerminalKeyBar(
    enabled: Boolean,
    onTerminalKey: (TerminalKey) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Slate)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TerminalKey.entries.forEach { key ->
            val shape = RoundedCornerShape(10.dp)
            val emphasized = key == TerminalKey.ENTER
            Surface(
                color = when {
                    !enabled -> Ink.copy(alpha = 0.42f)
                    emphasized -> PilotBlue.copy(alpha = 0.24f)
                    else -> Ink
                },
                border = BorderStroke(
                    1.dp,
                    if (emphasized) PilotBlue.copy(alpha = 0.7f) else {
                        MaterialTheme.colorScheme.outline
                    }
                ),
                shape = shape,
                modifier = Modifier
                    .height(40.dp)
                    .clip(shape)
                    .clickable(enabled = enabled) { onTerminalKey(key) }
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(horizontal = 13.dp)
                ) {
                    Text(
                        key.label,
                        color = if (enabled) {
                            if (emphasized) Sky else MaterialTheme.colorScheme.onSurface
                        } else {
                            Muted.copy(alpha = 0.55f)
                        },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
fun RemoteFilesScreen(
    projectName: String,
    rootPath: String,
    relativePath: String,
    files: List<RemoteFileEntry>,
    highlightedPath: String?,
    preview: RemoteFilePreview?,
    isLoading: Boolean,
    isLoadingPreview: Boolean,
    isDownloading: Boolean,
    downloadProgress: Int?,
    onBack: () -> Unit,
    onUp: () -> Unit,
    onOpenDirectory: (String) -> Unit,
    onOpenFile: (RemoteFileEntry) -> Unit,
    onClosePreview: () -> Unit,
    onDownload: (RemoteFileEntry) -> Unit
) {
    val fileListState = rememberLazyListState()
    LaunchedEffect(highlightedPath, files) {
        val highlightedIndex = files.indexOfFirst { it.relativePath == highlightedPath }
        if (highlightedIndex >= 0) {
            fileListState.animateScrollToItem(highlightedIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        BackHeader(
            title = projectName.ifBlank { "Project files" },
            subtitle = "REMOTE FILES",
            onBack = onBack
        )
        Surface(
            color = Slate,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (relativePath.isEmpty()) "PROJECT ROOT" else "REMOTE FOLDER",
                        color = Sky,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.1.sp
                    )
                    Text(
                        if (relativePath.isEmpty()) rootPath else "$rootPath/$relativePath",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (relativePath.isNotEmpty()) {
                    IconButton(onClick = onUp, enabled = !isLoading && !isDownloading) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = "Up one folder")
                    }
                }
            }
        }
        Box(modifier = Modifier.weight(1f)) {
            when {
                preview != null -> {
                    RemoteFilePreviewPane(
                        preview = preview,
                        onClose = onClosePreview,
                        onDownload = { onDownload(preview.file) }
                    )
                }

                isLoading && files.isEmpty() -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        strokeWidth = 2.dp
                    )
                }

                files.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Folder,
                            contentDescription = null,
                            tint = Muted,
                            modifier = Modifier.size(34.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "This folder is empty",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        state = fileListState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 8.dp,
                            bottom = 24.dp
                        )
                    ) {
                        items(files, key = { it.relativePath }) { file ->
                            val highlighted = file.relativePath == highlightedPath
                            Surface(
                                color = if (highlighted) SlateRaised else Color.Transparent,
                                border = if (highlighted) {
                                    BorderStroke(1.dp, Sky.copy(alpha = 0.75f))
                                } else {
                                    null
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(
                                        enabled = !isLoading && !isDownloading
                                    ) {
                                        if (file.isDirectory) {
                                            onOpenDirectory(file.relativePath)
                                        } else {
                                            onOpenFile(file)
                                        }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(
                                        horizontal = 8.dp,
                                        vertical = 12.dp
                                    ),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        color = if (file.isDirectory) {
                                            PilotBlue.copy(alpha = 0.18f)
                                        } else {
                                            Slate
                                        },
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (file.isDirectory) {
                                                Icons.Default.Folder
                                            } else {
                                                Icons.AutoMirrored.Filled.InsertDriveFile
                                            },
                                            contentDescription = null,
                                            tint = if (file.isDirectory) Sky else Muted,
                                            modifier = Modifier.padding(10.dp)
                                        )
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            file.name,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            when {
                                                file.isDirectory -> "Folder"
                                                highlighted -> {
                                                    "FROM TERMINAL · ${formatFileSize(file.sizeBytes)}"
                                                }

                                                else -> formatFileSize(file.sizeBytes)
                                            },
                                            color = if (highlighted) Sky else Muted,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            fontWeight = if (highlighted) {
                                                FontWeight.Bold
                                            } else {
                                                FontWeight.Normal
                                            }
                                        )
                                    }
                                    if (!file.isDirectory) {
                                        IconButton(onClick = { onDownload(file) }) {
                                            Icon(
                                                Icons.Default.Download,
                                                contentDescription = "Download ${file.name}",
                                                tint = Sky
                                            )
                                        }
                                    }
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                    if (isLoading) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            if (isLoadingPreview) {
                Surface(
                    color = Ink.copy(alpha = 0.72f),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(strokeWidth = 2.dp)
                    }
                }
            }
        }
        if (isDownloading) {
            Surface(
                color = SlateRaised,
                tonalElevation = 4.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Saving to this phone",
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        downloadProgress?.let {
                            Text(
                                "$it%",
                                color = Sky,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (downloadProgress == null) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(
                            progress = { downloadProgress / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteFilePreviewPane(
    preview: RemoteFilePreview,
    onClose: () -> Unit,
    onDownload: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close file preview")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    preview.file.name,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    formatFileSize(preview.file.sizeBytes),
                    color = Muted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                )
            }
            IconButton(onClick = onDownload) {
                Icon(Icons.Default.Download, contentDescription = "Download ${preview.file.name}")
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        when (preview.kind) {
            RemoteFilePreviewKind.TEXT -> {
                val vertical = rememberScrollState()
                val horizontal = rememberScrollState()
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(
                        preview.text,
                        color = AnsiTerminalPalette.Foreground,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(AnsiTerminalPalette.Background)
                            .horizontalScroll(horizontal)
                            .verticalScroll(vertical)
                            .padding(14.dp)
                    )
                }
            }

            RemoteFilePreviewKind.IMAGE -> {
                val bitmap = remember(preview.file.relativePath, preview.bytes) {
                    decodePreviewBitmap(preview.bytes)
                }
                if (bitmap == null) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("This image cannot be displayed on this phone.")
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = onDownload) { Text("Download file") }
                    }
                } else {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = preview.file.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black)
                            .padding(8.dp)
                    )
                }
            }
        }
    }
}

private fun decodePreviewBitmap(bytes: ByteArray): android.graphics.Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    var sampleSize = 1
    while (
        bounds.outWidth / sampleSize > 4_096 ||
        bounds.outHeight / sampleSize > 4_096
    ) {
        sampleSize *= 2
    }
    BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sampleSize }
    )
}.getOrNull()

@Composable
fun ProjectActionsScreen(
    projectName: String,
    actions: List<ProjectAction>,
    sessions: List<PanePilotSession>,
    isLoading: Boolean,
    runningActionId: String?,
    onBack: () -> Unit,
    onRun: (ProjectAction) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        BackHeader(
            title = projectName.ifBlank { "Project Actions" },
            subtitle = "ACTIONS",
            onBack = onBack
        )
        when {
            isLoading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(strokeWidth = 2.dp)
            }

            actions.isEmpty() -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    "No project Actions",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Create Actions in PanePilot desktop. They are shared through .panepilot/actions.json.",
                    color = Muted,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(actions, key = { it.id }) { action ->
                    val liveSession = sessions.firstOrNull {
                        it.actionId == action.id && !it.paneDead
                    }
                    Surface(
                        color = Slate,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                color = PilotBlue.copy(alpha = 0.18f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("⚡", fontSize = 20.sp, modifier = Modifier.padding(10.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(action.name, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    action.command,
                                    color = Muted,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Button(
                                onClick = { onRun(action) },
                                enabled = runningActionId == null && liveSession == null,
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    horizontal = 14.dp,
                                    vertical = 8.dp
                                )
                            ) {
                                if (runningActionId == action.id) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = Color.White
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (liveSession == null) "Run" else "Running")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionWorkspaceHeader(
    title: String,
    subtitle: String?,
    onShowSessions: () -> Unit,
    onBrowseFiles: (() -> Unit)? = null,
    onRunActions: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onShowSessions) {
            Icon(Icons.Default.Menu, contentDescription = "Show sessions")
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            subtitle?.let {
                Text(
                    it,
                    color = Sky,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        onBrowseFiles?.let { browse ->
            IconButton(onClick = browse) {
                Icon(Icons.Default.Folder, contentDescription = "Browse project files")
            }
        }
        onRunActions?.let { runActions ->
            IconButton(onClick = runActions) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Run project Actions")
            }
        }
    }
}

@Composable
fun HostKeyDialog(prompt: HostKeyPrompt, onAnswer: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        title = { Text("Verify SSH host") },
        text = {
            Column {
                Text(
                    "Compare this fingerprint with the one shown by your server provider or administrator.",
                    color = Muted,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(14.dp))
                Surface(
                    color = Color(0xFF080E19),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(
                            prompt.message,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onAnswer(true) }) {
                Text("Trust and connect")
            }
        },
        dismissButton = {
            TextButton(onClick = { onAnswer(false) }) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun ScreenHeader(
    eyebrow: String,
    title: String,
    action: @Composable () -> Unit,
    navigation: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        navigation?.invoke()
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = if (navigation == null) 8.dp else 2.dp)
        ) {
            Text(
                eyebrow,
                color = Sky,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        action()
    }
}

@Composable
private fun BackHeader(
    title: String,
    onBack: () -> Unit,
    subtitle: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            subtitle?.let {
                Text(
                    it,
                    color = Sky,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    destructive: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body, color = Muted) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = if (destructive) Danger else PilotBlue
                )
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun stateColor(state: SessionState): Color = when (state) {
    SessionState.NEEDS_INPUT -> Attention
    SessionState.RUNNING -> PilotBlue
    SessionState.READY -> Sky
    SessionState.IDLE -> Muted
    SessionState.LIVE -> Success
    SessionState.STOPPED -> Danger
}

private fun stateLabel(state: SessionState): String = when (state) {
    SessionState.NEEDS_INPUT -> "NEEDS INPUT"
    SessionState.RUNNING -> "WORKING"
    SessionState.READY -> "READY"
    SessionState.IDLE -> "IDLE"
    SessionState.LIVE -> "LIVE"
    SessionState.STOPPED -> "STOPPED"
}

private fun formatFileSize(bytes: Long): String {
    val safeBytes = bytes.coerceAtLeast(0L)
    if (safeBytes < 1_024L) return "$safeBytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = safeBytes.toDouble()
    var unit = -1
    do {
        value /= 1_024.0
        unit += 1
    } while (value >= 1_024.0 && unit < units.lastIndex)
    val precision = if (value >= 10.0) 0 else 1
    return String.format(Locale.getDefault(), "%.${precision}f %s", value, units[unit])
}

private fun Context.displayName(uri: Uri): String? {
    return contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
}
