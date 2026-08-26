# Orbit Mobile

A Capacitor-based iOS/Android companion for [Orbit](../README.md), Orbit's
desktop workspace for coding agents. The phone app connects to an Orbit
mobile server running on your Mac (a Node proxy in front of `opencode serve`)
and gives you live streaming sessions, permissions, model switching, QR
pairing, and push notifications from anywhere.

The Android/iOS shell, web UI (`MobileApp` renderer), and server are vendored
in this workspace and rebranded as Orbit.

**Provenance:** imported from [OpenChamber](https://github.com/openchamber/openchamber)
v1.20.0 (MIT, © Bohdan Triapitsyn) and modified. See [`LICENSE`](LICENSE).
Upstream's original handoff notes are preserved in
[`packages/mobile/HANDOFF.md`](packages/mobile/HANDOFF.md).

## Layout

```
packages/mobile   Capacitor 8 native shell: android/, ios/, scripts/, assets/
packages/web      Vite React app + Node server (the "Orbit mobile server")
packages/ui       Shared UI source; apps/renderMobileApp is the phone entry
scripts/          Workspace-level utilities
```

## Brand identity

| Token | Value |
|---|---|
| Display name | Orbit |
| App id / package | `com.orbit.mobile` |
| URL scheme | `orbit://` |
| Env prefix | `ORBIT_` (e.g. `ORBIT_HOST`, `ORBIT_UI_PASSWORD`) |
| Package scope | `@orbit/ui`, `@orbit/web`, `@orbit/mobile` |

Internal compatibility tokens (relay API host `api.openchamber.dev`,
upstream GitHub links kept in comments/docs) intentionally still reference
OpenChamber infrastructure; see the attribution note above.

### Backend health path

The server probes opencode's `/global/health` by default. Beta/newer
opencode builds that serve HTML on that path need:

```sh
ORBIT_OPENCODE_HEALTH_PATH=/api/health   # honored by every probe
```

Without it the mobile app pairs but never flips to "connected" — the
session bootstrap gates on `{"healthy":true}` from `/api/opencode/health`.

### opencode2 (beta) backends

Stable opencode 1.18.x needs nothing special. For an **opencode2 beta**
backend (new `/api/*` surface, different payload shapes), enable the
translation layer instead:

```sh
ORBIT_OPENCODE_V2=1            # mount the v1↔v2 compat proxy
ORBIT_USER_HOME=/Users/ty      # home reported to the app's path API
```

The layer maps every SDK route the app uses (health, providers/model
catalog, agents, sessions, `prompt_async` sends, event stream) onto the
beta endpoints and synthesizes a model catalog from the backend's own
most-recent session default. Known gaps: live in-chat streaming deltas are
not yet translated — reopen a session to refresh its transcript.

## Build

Requires bun 1.3+, Node 22+, JDK 21 (`/opt/homebrew/opt/openjdk@21`), and the
Android SDK (`~/Library/Android/sdk`; recorded per machine in
`packages/mobile/android/local.properties`).

```sh
bun install                         # workspace deps + patches
bun run type-check                  # tsc over ui, web, mobile configs
bun run build                       # vite web build → mobile/dist assets
bunx --cwd packages/mobile cap sync android
node scripts/generate-orbit-assets.mjs   # regenerates icon/splash sources from resources/
cd packages/mobile/android && ./gradlew assembleDebug
```

The debug build signs with `android/debug.keystore` (auto-created,
gitignored) so no `~/.android` access is needed. Release builds sign via env
vars — `ORBIT_ANDROID_KEYSTORE_PATH`, `ORBIT_ANDROID_KEYSTORE_PASSWORD`,
`ORBIT_ANDROID_KEY_ALIAS`, `ORBIT_ANDROID_KEY_PASSWORD`. A keystore for the
`com.orbit.mobile` application id already exists at the workspace root as
`omni-release.jks` (untracked; see `keystore.properties`).

APK output: `packages/mobile/android/app/build/outputs/apk/debug/app-debug.apk`.

## Run

1. On the Mac, start the mobile server in front of opencode:

   ```sh
   cd packages/web
   ORBIT_HOST=0.0.0.0 ORBIT_UI_PASSWORD=… OPENCODE_HOST=http://127.0.0.1:<port> \
     node bin/cli.js serve --foreground --port 3010
   ```

2. Install the APK on the phone, enter the server URL (e.g. Tailscale
   `http://100.x.y.z:3010`) and password, or scan the pairing QR.

Notes:

- Push notifications are disabled until a `google-services.json` for our own
  Firebase project is added at `packages/mobile/android/app/` — the Google
  Services plugin is applied conditionally and registration failures are
  swallowed by design.
- The Capacitor shell serves the app from an `http://localhost` origin so
  plain-http LAN/tailnet servers are reachable without mixed-content blocks.
- iOS is vendored but not yet rebranded end-to-end (widget/notification
  extension targets keep upstream identifiers); treat it as a follow-up.
