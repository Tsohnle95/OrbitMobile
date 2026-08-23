#!/bin/bash
# omni-serve.sh — launch a headless opencode server and print a pairing QR.
# Auto-detects opencode 2.x (beta /api/* wire) vs 1.x (v1 wire) and picks the
# right binary so your real saved workspaces and sessions show up in the app.
#
# Usage: ./omni-serve.sh [project-dir]
# Env:   OMNI_PORT (default 4096), OMNI_PASSWORD, OMNI_USERNAME (default opencode)
set -euo pipefail

PROJECT_DIR="${1:-$PWD}"
if [ $# -gt 0 ]; then shift; fi
PORT="${OMNI_PORT:-4096}"
USER_NAME="${OMNI_USERNAME:-opencode}"
PASS="${OMNI_PASSWORD:-$(LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 16)}"

OC_BIN=""
for candidate in "/Users/ty/.local/lib/node_modules/@opencode-ai/cli/bin/opencode2.exe" \
                 "$(command -v opencode || true)"; do
  [ -n "$candidate" ] && [ -x "$candidate" ] || continue
  OC_BIN="$candidate"
  break
done
[ -n "$OC_BIN" ] || { echo "opencode not found"; exit 1; }

LAN_IP=$(ifconfig 2>/dev/null | awk '/inet / && !/127\.0\.0\.1/ {print $2}' | while read -r ip; do
  case "$ip" in 100.*) echo "$ip"; break;; *) best="${best:-$ip}";; esac
done; echo "${best:-127.0.0.1}")

echo "Project : $PROJECT_DIR"
echo "Binary  : $OC_BIN"
echo "Server  : http://$LAN_IP:$PORT  (user: $USER_NAME)"
echo "Password: $PASS"
echo

URL="http://$LAN_IP:$PORT/$USER_NAME:$PASS"
QR_PATH="/tmp/omni-pair-$PORT.png"
if command -v qrencode >/dev/null; then
  qrencode -o "$QR_PATH" -s 8 -m 2 "$URL"
  osascript -e "tell application \"Preview\" to open POSIX file \"$QR_PATH\"" 2>/dev/null || true
  qrencode -t ANSIUTF8 "$URL"
else
  echo "(install qrencode for a scannable QR: brew install qrencode)"
fi

export OPENCODE_SERVER_USERNAME="$USER_NAME"
export OPENCODE_SERVER_PASSWORD="$PASS"
exec "$OC_BIN" serve --port "$PORT" --hostname 0.0.0.0 "$PROJECT_DIR"
