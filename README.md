# OmniAgent Mobile

A calm, minimal Android companion for your OmniAgent desktop workflow. It
connects directly to a headless `opencode serve` instance on your Mac over
Wi-Fi, so you can create, watch, and steer agent sessions from your phone.

- Native Android app: Kotlin + Compose Material 3, no webviews
- Design system: OmniAgent's own tokens (default: light "Paper" theme —
  white/cream surfaces, sage accent; auto-matches the desktop "Dusk" theme in
  system dark mode). Tokens transcribed from
  `src/renderer/src/styles/_foundation.scss`.
- Pairing: scan a QR code printed by the launcher script (URL + basic-auth
  password), stored in EncryptedSharedPreferences
- Live streaming via the opencode `/event` SSE endpoint with reconnect +
  backoff, message/part deltas, session status, and permission prompts

## Quick start

1. On your Mac, from this folder:

   ```sh
   ./scripts/omni-serve.sh /path/to/your/project
   ```

   It prints a LAN URL and renders a QR code in the terminal. (Install
   `brew install qrencode` for the QR; otherwise type the shown host/port/
   password manually.)

2. On your phone, install the APK from `dist/` (see Releases below), open
   OmniAgent, tap **Scan pairing code**, point it at the terminal.

3. Create a session or open an existing one and start guiding your agent.

Both devices must be on the same Wi-Fi network. The server is protected by
the generated pairing password (opencode's built-in
`OPENCODE_SERVER_PASSWORD` basic auth).

## Build

```sh
./gradlew :app:assembleDebug          # debug APK  -> app/build/outputs/apk/debug/
./gradlew :app:assembleRelease        # signed if keystore.properties exists
./gradlew :app:testDebugUnitTest      # unit tests
```

A `keystore.properties` file (gitignored) with `storeFile`, `storePassword`,
`keyAlias`, `keyPassword` signs release builds; without it, release builds
are unsigned.

## Verified API contract (opencode 1.18.21)

| Purpose | Call |
|---|---|
| Health | `GET /global/health` → `{healthy, version}` |
| Projects | `GET /project` → `[{id, worktree, vcs}]` |
| Sessions | `GET /session?directory=…`, `POST /session {title?, directory?}` |
| Rename | `PATCH /session/:id {title}` |
| Messages | `GET /session/:id/message` → `[{info, parts}]` ascending |
| Send prompt | `POST /session/:id/message {parts:[{type:"text", text}]}` |
| Abort | `POST /session/:id/abort` |
| Permissions | `GET /permission`, `POST /permission/:requestID/reply {reply: once\|always\|reject}` |
| Event stream | `GET /event` (SSE, `data: {id, type, properties}` frames) |

Handled events: `message.updated`, `message.part.updated`, `session.status`,
`permission.asked`, `permission.replied`. Unknown events are ignored, so
forward-compatible with newer servers.

Note: the prompt POST stays open until the agent turn completes; the app
treats it as fire-and-forget and tracks progress via SSE instead.

## Layout

```
app/src/main/java/com/omniagent/mobile/
  MainActivity.kt          navigation: pair → sessions → chat
  app/                     ViewModels (pairing, sessions, chat) + UI state
  data/                    OpenCodeClient (Ktor), SSEStream, DTOs, PairingStore
  qr/                      ZXing QR generate (launcher) + camera analyze
  ui/theme/                OmniAgent design tokens (Paper + Dusk)
  ui/pairing/              Pair screen + CameraX QR scanner
  ui/sessions/             Session list
  ui/chat/                 Transcript, tool activity, composer, permission card
scripts/omni-serve.sh      launcher: server + QR pairing code
```
