# claude-car

Talk to any of your Claude Code sessions on your Mac from Android Auto. You speak, and Claude's replies are read aloud.

```
Car (Android Auto)  ⇄  phone app: the "Claude Car" audio app (pick a session, press play, talk)
                       + one message conversation per session
                    ⇄  HTTP/SSE over Tailscale (100.122.179.88:8788, bearer token)
                    ⇄  claude-car service on the Mac (launchd, starts at login)
                    ⇄  claude -p --resume <session> …   one headless turn per message, in that session's folder
```

## How it works

- **Sessions:** the service lists your recent Claude Code sessions from `~/.claude/projects`, across all folders, including sessions from the desktop app.
- **Turns:** when you talk to a session, the service resumes it headless with Claude Code's own print mode, in the session's folder. Claude's text comes back to the car.
- **Nothing to start per session.** There are no channels and no tmux. The service is installed once and runs in the background.
- **In Android Auto, Claude Car is an audio app** (next to Spotify and the like). It lists your sessions; pick one, press play and talk. The phone's own speech recognition turns what you say into text, and the app reads Claude's whole reply aloud. It doesn't use Gemini or Android Auto's message reply, so it works with Google Workspace accounts too (whose message replies Android Auto currently blocks).
- **Each session is also a message conversation**, from a contact named after the session, for replying through Android Auto's own voice reply when that works.
- **The Claude Car message conversation takes commands:**
  - "list sessions"
  - "open two" or "open refund bug"
  - "new session in payments"
- **Sessions open on the Mac:** if a session is open in the desktop app or a terminal (or was just active), the car continues in a copy named "… (car)". Two processes never write to the same conversation.
- **Permissions:** turns run in **auto** permission mode, since nobody can answer prompts while you drive. Safe actions go ahead, and risky ones are blocked; Claude tells you, and you can do them at the Mac.

## 1. Mac setup (once)

You need [Bun](https://bun.sh), Tailscale, and Claude Code signed in with claude.ai.

```bash
scripts/setup.sh
```

The script:

- creates `~/.claude-car/` with a random token
- copies the service there
- installs it as a launchd agent (`com.theglobulus.claude-car`) that starts at login and restarts if it crashes

Re-run it after changing `bridge/src` or the options:

```bash
CAR_EFFORT=low scripts/setup.sh                  # faster replies
CAR_MODEL=sonnet scripts/setup.sh                # a different model for car turns
CAR_PERMISSION_MODE=acceptEdits scripts/setup.sh # instead of auto
```

**macOS privacy:** background services can't read `~/Documents`, `~/Desktop` or `~/Downloads` until you allow them. If your projects live there, allow **bun** once:

1. If macOS shows a prompt like "bun would like to access files in your Documents folder", click **Allow**.
2. If there's no prompt, open System Settings, then **Privacy & Security**, then **Files & Folders** (or **Full Disk Access**), and turn on **bun**. It's at `~/.bun/bin/bun`; press Cmd-Shift-G in the file picker to type the path.

Until then, the car tells you that macOS is blocking that folder.

Manage the service:

```bash
scripts/service.sh status    # or: restart | stop | start | logs | uninstall
```

## 2. Phone setup (once)

1. Install Tailscale on the phone, sign in to the same tailnet, and set it to **Always-on VPN**.
2. Build the app on the Mac and install it. The token from setup is baked into the APK, so **don't share the APK**.
   ```bash
   cd android
   JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
   Connect the phone by USB, or pair it with wireless debugging (`adb pair` then `adb connect`).
3. Open **Claude Car**, allow notifications and the microphone, and tap **Battery** to allow unrestricted use. Do the same for Tailscale.
4. Turn on **Stay connected to your Mac**. Tap **Test**: it should say "Mac reachable". The status notification says "microphone ready" once the car can listen.
   - Android only lets an app listen in the background if it was opened first, so **open Claude Car once after the phone restarts or the app updates**. The car tells you if the microphone is off.
   - **Speech language** is what you speak in the car (default `en-US`).
5. Your sessions are listed below the settings:
   - Tap one to chat from the phone, or tap **Show in car** to put its conversation in Android Auto.
   - **New** starts a session in a folder you've used before.

### Updating the app without a cable

```bash
scripts/publish-app.sh
```

builds the app and puts it on the Mac service. On the phone, tap **Update app** (the first time, allow Claude Car to install apps). It downloads over Tailscale, so it works from anywhere. Then open the app once, so the microphone is back on.

For the very first install without a cable, run `scripts/share-apk.sh`: it prints a download link that works only on your tailnet, for 20 minutes. Open it on the phone, then open the download and allow installing. (Taildrop works too, `tailscale file cp ~/.claude-car/app/claude-car.apk <phone-name>:`, but stalls unless the Tailscale app is open on the phone.)

## 3. Android Auto setup (once)

1. In the Android Auto settings on your phone, tap **Version and permission info** 10 times to enable developer mode.
2. Open the three-dot menu, then **Developer settings**, and turn on **Unknown sources**.
3. In Android Auto's settings, open **Customize launcher** and make sure **Claude Car** is ticked.
4. Recommended: turn off AI message summaries in Android Auto's notification settings, so longer message replies are read in full.

## In the car

### The Claude Car audio app (recommended)

1. Open **Claude Car** from the car's app list.
2. **Sessions** lists your recent sessions; **New session** lists folders to start one in. Tap one.
3. It says the session's name, beeps, and listens. Speak; a second tone means it got it.
4. While Claude works the player shows a spinner; you can play music meanwhile. Claude's reply is read aloud in full, then it beeps and listens for a follow-up (say nothing to stop).

| Control | Does |
|---|---|
| Play, **Talk**, or the steering wheel's next button | talk (interrupts any reading) |
| Pause | stop reading or listening (Claude keeps working; its reply is still read out) |
| Previous, or **Repeat** | read Claude's last reply again |
| **Stop Claude** (while it works) | cancel what Claude is doing |
| The queue (list icon) | the conversation, a sentence per row, to read when parked; tap a row to hear it |

### Message conversations

- **When Android Auto connects**, you get a Claude Car message saying it's ready. The session you used last also appears, ready to continue.
- **Reply to Claude Car** with a command:
  - "list" reads your five most recent sessions.
  - "open two" or "open refund bug" brings that session's conversation up.
  - "new session in payments" starts a fresh one in that folder.
- **Reply to a session's conversation** to talk to that session. Say "stop" to cancel what it's doing.
- **Long work:** Claude says what it's about to do, then reports when it's done. If it's quiet for a while, you'll hear "Still working on it".

## Testing without a car

- **Service tests:** `cd bridge && bun test`
- **Android unit tests:** `cd android && ./gradlew :app:testDebugUnitTest` (the voice loop is tested in `VoiceControllerTest`)
- **On a device or emulator:** `cd android && ./gradlew :app:connectedDebugAndroidTest` browses the audio app the way Android Auto does and opens a session (it sends nothing to it). It uninstalls the app afterwards.
- **Poke the service like the phone would:**
  ```bash
  scripts/smoke.sh sessions
  scripts/smoke.sh events
  scripts/smoke.sh say hub "list sessions"
  scripts/smoke.sh say <session-id> "what are you working on?"
  ```
- **Desktop Head Unit (DHU):**
  1. Install it with `sdkmanager "extras;google;auto"`.
  2. On the phone, in Android Auto's developer menu, tap **Start head unit server**.
  3. Run `adb forward tcp:5277 tcp:5277`, then `~/Library/Android/sdk/extras/google/auto/desktop-head-unit --usb`.


## Security

- The service listens only on the Mac's Tailscale address and requires the 64-character bearer token. Plain HTTP is used because WireGuard already encrypts the tailnet.
- Anyone with the token can run prompts in any of your sessions, in auto mode. Keep `~/.claude-car/token` and the APK private.
- To rotate the token: delete the file, re-run `setup.sh`, and rebuild the APK.
- New sessions can only start in folders that already have Claude Code sessions.
- The service also serves the latest APK (with the token inside) to the phone, behind the same token.

## Troubleshooting

| Symptom | Check |
|---|---|
| Test says the Mac refused the connection | `scripts/service.sh status`. Is the service running? |
| "Reconnecting…" on the phone | Is Tailscale on (phone and Mac)? Is the Mac awake? |
| "macOS is blocking the car service from that folder" | Allow bun in Privacy & Security (see Mac setup). |
| "That session is open on your Mac, so I'm continuing in a copy" | Expected: close it in the desktop app if you want the car to continue the original. |
| Claude Car isn't in the car's app list | Android Auto: Unknown sources on, and Claude Car ticked in Customize launcher. |
| "The microphone is off" | Open Claude Car on the phone once (needed after a restart or update). |
| Replying to a message says to ask your Google Workspace administrator | An Android Auto bug with Workspace accounts. Use the Claude Car audio app instead. |
| Anything else | `scripts/service.sh logs` |

## Layout

- `bridge/`: the Mac service (Bun + TypeScript, no dependencies, `bun test`)
  - `sessions.ts`: lists sessions
  - `runner.ts`: runs headless turns
  - `car.ts`: routing, copies and queueing
  - `commands.ts`: hub commands
  - `http.ts`: the API
- `scripts/`: setup (launchd), service control, smoke tests, publishing the app (in-app updates) and sharing it (first install)
- `android/`: the phone app (Kotlin, plain Views)
  - the audio app: `car/ClaudeMediaService.kt`; the spoken conversation: `voice/`
  - per-session notifications: `notify/`
  - connection (and the microphone): `net/`, `service/`
  - session picker, chat and settings: `ui/`
  - in-app updates: `update/`
