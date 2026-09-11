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
most-recent session default. Live streaming (text deltas, reasoning,
tool lifecycle, status transitions) is fully translated.

### Auditing the stack

With the server running, verify every layer the app depends on:

```sh
ORBIT_URL=http://127.0.0.1:3011 ORBIT_PASSWORD=… node scripts/e2e-audit.mjs
```

It authenticates, exercises each SDK surface, sends a real prompt over the
app's send path, and asserts the full live event sequence. Exit code 2 lists
failing layers.

## Build

Pinned toolchain (verified working):

- bun 1.3.14 (`packageManager`), Node 22+ (`.nvmrc`)
- JDK 21 — default `/opt/homebrew/opt/openjdk@21`, override with `JAVA_HOME`
- Android SDK — default `/opt/homebrew/share/android-commandlinetools`,
  override with `ANDROID_HOME` / `ANDROID_SDK_ROOT`

Every mobile command runs through `scripts/with-mobile-env.mjs`, which resolves
`DEVELOPER_DIR` / `JAVA_HOME` / `ANDROID_HOME` and prepends the tool dirs to
`PATH`. Override those env vars instead of editing the script.

```sh
bun install                      # workspace deps + patches
bun run type-check               # tsc over ui, web, mobile configs

# Android — no Xcode/CocoaPods required:
bun run build:android:debug      # web build → prepare assets → cap sync android → gradle assembleDebug
bun run android:run              # install + launch on the connected device
```

APK output: `packages/mobile/android/app/build/outputs/apk/debug/app-debug.apk`.

`sync` targets **Android only**, so a machine without CocoaPods can build the
Android app. Run `bun run sync:ios` (requires CocoaPods + Xcode) only when
working on iOS. `node scripts/generate-orbit-assets.mjs` regenerates icon/splash
sources from `resources/` when branding assets change.

### Signing and versioning

Sideload and release builds share **one identity**. When release keystore
credentials are available — env vars `ORBIT_ANDROID_KEYSTORE_PATH` /
`_KEYSTORE_PASSWORD` / `_KEY_ALIAS` / `_KEY_PASSWORD`, or a `keystore.properties`
found in `android/`, `packages/mobile/`, or the repo root — both the `debug` and
`release` build types sign with it, so each new build installs **in place** over
the previous one. Without it, `debug` falls back to `android/debug.keystore`
(auto-created, gitignored). The release keystore (`omni-release.jks`, alias
`omniagent`) is untracked — back it up off-machine.

`versionName` comes from the root `package.json`; `versionCode` defaults to the
git commit count. Override with `ORBIT_ANDROID_VERSION_NAME` /
`ORBIT_ANDROID_VERSION_CODE` (CI). Build a shareable, signed APK:

```sh
bun run dist:android   # release build → dist/orbit-mobile-<version>.apk (+ SHA-256)
```

## Run

1. On the Mac, start the mobile server in front of opencode:

   ```sh
   cd packages/web
   ORBIT_HOST=0.0.0.0 ORBIT_UI_PASSWORD=… OPENCODE_HOST=http://127.0.0.1:<port> \
     node bin/cli.js serve --foreground --port 3010
   ```

2. Install the APK on the phone, enter the server URL (e.g. Tailscale
   `http://100.x.y.z:3010`) and password, or scan the pairing QR.

### opencode v2 backend (recommended)

The app/server work best against a v2 (`opencode2`) backend. Run it as an
**external** server with a fixed password and point the mobile server at it
with the compat layer enabled:

```sh
# 1) v2 backend
OPENCODE_SERVER_PASSWORD=changeme opencode2 serve --port 4099 --hostname 127.0.0.1

# 2) mobile server (v2 compat, external backend)
ORBIT_OPENCODE_V2=1 \
OPENCODE_HOST=http://127.0.0.1:4099 \
OPENCODE_SKIP_START=true \
OPENCODE_SERVER_PASSWORD=changeme \
ORBIT_HOST=0.0.0.0 ORBIT_UI_PASSWORD=… \
  node bin/cli.js serve --foreground --port 3010
```

`OPENCODE_SERVER_PASSWORD` must match on both processes (the server sends it as
Basic auth). With this stack `scripts/e2e-audit.mjs` passes 17/17, including
live streaming and the app's send path.

Known limitation: letting the mobile server **manage** its own v2 backend
(without `OPENCODE_HOST`) currently fails readiness — the v2 compat layer reads
a not-yet-populated port and builds `http://127.0.0.1:null/api/health`. Use the
external-backend form above until that is fixed.

### Reach it from anywhere (Tailscale) — follows the Orbit desktop app

The phone connects to the Mac's Tailscale address only while the **Orbit desktop
app is open**. Orbit runs the mobile server (opencode2 + Orbit web server) for as
long as the app is running and stops it when you quit — so "desktop app open =
mobile works, desktop app closed = it can't connect".

```sh
tailscale up            # once, on the Mac; install + log in on the phone too
# then just open Orbit.app
```

The desktop app spawns `scripts/desktop-service.mjs`, which watches the app's
process id and tears the whole stack down if the app exits or crashes. Nothing
runs while the app is closed.

**Sessions are shared with the desktop.** The agent attaches to the desktop
app's global opencode2 daemon (`ORBIT_EXTERNAL_BACKEND=1`, discovered via the
`@opencode-ai/client` service registration), so you can resume a desktop
conversation on the phone and phone-created sessions show up on the desktop.
If no desktop daemon is available, it falls back to its own isolated backend
(`XDG_DATA_HOME=~/Library/Application Support/OrbitMobile/data`).

| Process | What | Endpoint |
|---|---|---|
| desktop daemon | shared with the desktop app | `127.0.0.1:<assigned>` |
| Orbit server | v2 compat, attached to the daemon | `0.0.0.0:3011` |

In the app, add the Mac's Tailscale address as an instance —
`http://100.x.y.z:3011` or `http://<machine>.<tailnet>.ts.net:3011` — and unlock
with the UI password. It reconnects automatically once Orbit is open.

The UI password is a **per-install random secret** stored at
`~/Library/Application Support/OrbitMobile/ui-password`, not a shared default.
Read it (or set `ORBIT_PASSWORD` to override):

```sh
cat "$HOME/Library/Application Support/OrbitMobile/ui-password"
```

Because the server binds `0.0.0.0`, that password is the boundary; keep it
private and keep the tailnet closed.

Paired devices hold a **non-expiring** client token (stored in the phone's
keychain). It is invalidated only by explicit revocation or by reinstalling the
app — not by time, restarts, or password changes. List and revoke paired
devices:

```sh
# list (authenticated): ids, labels, last use
curl -s -b "$JAR" http://127.0.0.1:3011/api/client-auth/clients
# revoke one, or purge all revoked records
curl -s -b "$JAR" -X DELETE http://127.0.0.1:3011/api/client-auth/clients/<id>
curl -s -b "$JAR" -X DELETE http://127.0.0.1:3011/api/client-auth/clients
```

#### Without the desktop app (manual)

If you want the stack up without opening Orbit, run it yourself:

```sh
bun run server:start    # start opencode2 + Orbit server in the background
bun run server:stop     # stop both
bun run server:status   # show pids + the tailnet URL
bun run server:restart
```

Double-click **`Orbit Mobile.command`** in the repo root to toggle start/stop.

```sh
tail -f "~/Library/Application Support/OrbitMobile/logs/server.log"
```

The backend is deliberately isolated from the desktop Orbit app's opencode data
(`XDG_DATA_HOME=~/Library/Application Support/OrbitMobile/data`) so the two
don't collide. Because the server binds `0.0.0.0`, `ORBIT_UI_PASSWORD` is what
gates access — keep it set, and keep the tailnet private.

Notes:

- Push notifications are disabled until a `google-services.json` for our own
  Firebase project is added at `packages/mobile/android/app/` — the Google
  Services plugin is applied conditionally and registration failures are
  swallowed by design.
- The Capacitor shell serves the app from an `http://localhost` origin so
  plain-http LAN/tailnet servers are reachable without mixed-content blocks.
- iOS is vendored but not yet rebranded end-to-end (widget/notification
  extension targets keep upstream identifiers); treat it as a follow-up.
