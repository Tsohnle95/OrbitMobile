#!/usr/bin/env bash
#
# omni-serve.sh — launch a headless opencode server for the OmniAgent mobile
# app and print/QR the pairing URL.
#
# Usage:
#   ./scripts/omni-serve.sh [project-dir]
#
# Environment:
#   OMNI_PORT       port to listen on (default: 4096)
#   OMNI_PASSWORD   pairing password (default: generated, printed in QR)
set -euo pipefail

PROJECT_DIR="${1:-$PWD}"
if [ $# -gt 0 ]; then shift; fi
PORT="${OMNI_PORT:-4096}"
PASSWORD="${OMNI_PASSWORD:-$(LC_ALL=C tr -dc 'A-HJ-NP-Za-km-z2-9' </dev/urandom | head -c 16)}"

if ! command -v opencode >/dev/null 2>&1; then
  echo "error: opencode not found on PATH" >&2
  exit 1
fi

LAN_IP=$(ifconfig 2>/dev/null | awk '
  /inet / && $2 !~ /^127\./ { for (i = 1; i <= NF; i++) if ($i == "inet") { split($0, a, " inet "); split(a[2], b, " "); print b[1]; exit } }
')
if [ -z "${LAN_IP}" ]; then
  LAN_IP=$(ipconfig getifaddr en0 2>/dev/null || echo "127.0.0.1")
fi

PAIR_URL="http://opencode:${PASSWORD}@${LAN_IP}:${PORT}"

echo ""
echo "  OmniAgent mobile pairing"
echo "  ────────────────────────"
echo "  project : ${PROJECT_DIR}"
echo "  local   : http://localhost:${PORT}"
echo "  network : http://${LAN_IP}:${PORT}"
echo ""

if command -v qrencode >/dev/null 2>&1; then
  qrencode -t UTF8 "${PAIR_URL}"
else
  echo "  (install qrencode via 'brew install qrencode' to display the QR here)"
fi

echo "  Scan with OmniAgent on your phone, or enter manually:"
echo "    host ${LAN_IP}  port ${PORT}  password ${PASSWORD}"
echo ""

OPENCODE_SERVER_USERNAME=opencode \
OPENCODE_SERVER_PASSWORD="${PASSWORD}" \
exec opencode serve --port "${PORT}" --hostname 0.0.0.0 "$@"
