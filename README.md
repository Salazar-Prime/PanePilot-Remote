# PanePilot Remote for Android

PanePilot Remote is a deliberately small Android companion for the desktop app. It
connects directly to an SSH server, discovers live tmux sessions carrying PanePilot's
versioned `@panepilot_*` metadata, shows a recent pane snapshot, and sends text back to
the exact tmux pane.

It does not require a relay, PanePilot daemon, web service, or a copy of the desktop
SQLite database. Tmux remains authoritative for live-session presence.

## What it supports

- Multiple manually configured SSH servers
- Multiple simultaneous SSH/tmux connections with fast switching between warm servers
- Password or imported private-key authentication
- Optional password storage encrypted with Android Keystore
- SSH host-key verification and saved `known_hosts`
- PanePilot session discovery with activity, name, recent-use, and project sorting
- Persisted per-server terminal pinning for quick access
- A compact hideable side pane with header-based history and sorting controls
- Optional emoji identities for SSH server sessions, configured when editing a server
- Codex progress state from the tmux pane title
- Bounded, auto-refreshing pane snapshots with ANSI terminal colors
- Multiline agent messages sent through a temporary tmux buffer
- A top-mounted mobile key strip for Enter, Esc, Tab, arrows, and common Ctrl combinations
- Project-scoped remote file browsing with bounded text and image previews
- Downloads for every file type through Android's system file picker
- Project Actions loaded from `.panepilot/actions.json` and run in tagged tmux sessions
- Per-terminal alerts when a Codex or Claude session needs input or finishes a response
- A dedicated unread attention queue that stays above pinned and sorted terminals
- Separate Android categories and notification groups for connection status, agent
  completions, and needs-input alerts
- Exact-session notification links and a persistent in-app history of the last 200
  agent alerts sent on the phone
- Foreground SSH/tmux monitoring that continues after leaving or swiping away the app
- Clickable HTTP(S) links and project file paths in ANSI-colored terminal output

The app is a focused monitor and message composer, not a full terminal emulator. It
can launch shared project Actions, but does not create, rename, stop, archive, or
delete ordinary terminal sessions.

## Requirements

- Android 8.0 (API 26) or newer
- Network access from the phone to the SSH server, directly or through your VPN
- An SSH account with access to the same user's default tmux server
- `tmux` installed on the server
- Live tmux-backed sessions created by a current PanePilot desktop client

PanePilot tags only tmux-backed sessions. Plain PTY fallback sessions cannot be
discovered from another device.

There is no separate project-import step. Project groups are derived from the
`@panepilot_project_path` metadata on live sessions, so every tagged session visible
to the SSH user's default tmux server appears automatically.

## Tailscale connections

Using a Tailscale IP keeps the SSH server private to the tailnet, but ordinary SSH on
the destination still requires a password or SSH key. Enable **Remember on this
phone** to encrypt the password with Android Keystore and reuse it on later
connections, or import a dedicated private key.

Tailscale SSH can replace SSH passwords with tailnet identity when its server
component and access policy are configured. On macOS, its server component requires
the open-source `tailscale` + `tailscaled` variant rather than the standard macOS
client. See the [Tailscale SSH documentation](https://tailscale.com/docs/features/tailscale-ssh).

## Build and install from the command line

Install Android Studio or the Android command-line tools with Android SDK 34 and
Build Tools 34. Then:

```bash
git clone https://github.com/Salazar-Prime/PanePilot-Remote.git
cd PanePilot-Remote
export ANDROID_HOME="$HOME/Library/Android/sdk"
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew assembleDebug
```

If Homebrew installed the command-line tools on macOS, the SDK path is commonly:

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
```

Connect an Android phone with USB debugging enabled and install the APK:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

You can also copy `app/build/outputs/apk/debug/app-debug.apk` to the phone, open it,
and allow installation from that file source when Android asks.

## Build with Android Studio

1. Open the repository folder in Android Studio.
2. Let Gradle sync.
3. Select a connected phone.
4. Run the `app` configuration.

## First connection

1. Tap **Add server** and enter a host, SSH port, username, and authentication method.
2. For key authentication, import the private key. The app copies it into private app
   storage; it never stores the key passphrase.
3. Tap the server, enter the password or optional key passphrase, and connect.
4. On first connection, compare the displayed SSH fingerprint with the fingerprint
   from the server or hosting provider before choosing **Trust and connect**.
5. Choose a live session, watch its pane snapshot, and use the bottom composer to send
   a prompt.
6. Use the folder button in a terminal header to browse its remote project. Tap UTF-8
   text or a common image to preview it; use the download button for any file type.
7. Use the play button next to the folder button to run Actions shared by PanePilot
   desktop through `.panepilot/actions.json`. Each run opens as a tagged tmux session.
8. Connect any additional servers you want to keep warm. Return to the server list to
   switch between them without disconnecting the others. Edit a server to assign it
   an emoji identity.
9. Tap a terminal's bell to enable or disable attention alerts. A terminal that needs
   input or finishes a response moves into the **Needs attention** section at the top
   of the list until you open it. Open **History** to review alerts sent since version
   0.6.0; tapping a history entry opens its exact server and terminal. The persistent
   monitor keeps every connected server online and stops an individual connection
   only when you explicitly disconnect it. On Android 13 and newer, approve the
   notification permission the first time.
10. Tap an HTTP(S) link in the terminal to open it in the browser. Tap a project path to
    open its folder in Remote Files; supported files open directly in the viewer.
11. Pin frequently used terminals with the top button in each card and control alerts
    with the bell below it. Use the sort icon for **Activity**, **Name**, **Recent**, or
    **Project**. Recent follows taps and input made in this Android app; Activity keeps
    the latest output/state transition at the top, including after Working becomes Ready.

If a legitimate server is rebuilt and its host key changes, edit that server and use
**Forget saved host key** only after independently verifying the new fingerprint.

## Security and behavior

- Passwords stay in memory unless **Remember on this phone** is enabled. Remembered
  passwords are encrypted using a non-exportable Android Keystore key, and Android
  backup is disabled for the app.
- Private-key passphrases always stay in memory only for their connected server.
- Imported private keys live in Android's app-private storage and are removed when the
  server profile is deleted.
- Message text is sent as SSH channel input to `tmux load-buffer`; it is not interpolated
  into the remote shell command.
- Session names are always passed as exact tmux targets (`=session-name:`).
- The transcript is capped to the most recent 300 pane lines and 768 KB per refresh.
- File browsing is bounded to the session's project folder. Symbolic links are omitted,
  UTF-8 text previews are capped at 1 MB, image previews at 12 MB, and file bytes stream
  directly from SFTP into the Android destination you select.
- Action definitions are read-only on Android. A run is tagged with the same versioned
  tmux metadata as PanePilot desktop and replaces the prior tmux run for that Action.
- A low-priority foreground-service notification keeps every connected server's SSH
  and attention polling active after leaving or swiping away the app, even when no
  terminal bell is enabled. Its **Stop all** action disables terminal bells and ends
  all background monitoring; disconnecting one server leaves the others connected.
- Android keeps that foreground connection status separate from agent activity.
  Response completions and needs-input events have their own configurable categories
  and appear in a dedicated agent-activity group in the notification shade.
- Successfully posted agent alerts are saved in private app storage, newest first,
  and capped at 200 entries. The persistent connection-status notification is not
  included in this history.
- Pressing Android Back from the session list backgrounds PanePilot instead of
  disconnecting. If Android recreates the activity while the service remains active,
  PanePilot restores the monitored SSH connection automatically.
- The monitor reconnects with bounded backoff after network loss. Remembered passwords
  can survive a process restart because they are encrypted with Android Keystore.
  Unremembered passwords and private-key passphrases remain only in service memory and
  require reopening PanePilot if Android kills the service process.
- Last-seen agent states are saved locally, so a brief SSH reconnect does not lose the
  `Working` to `Ready` transition used for response-complete alerts.
- Android force-stop always disables foreground monitoring until PanePilot is opened
  and connected again. Monitoring does not start automatically after a phone reboot.
- Leaving the app does not stop the remote tmux session.
