#!/bin/zsh
# Double-click to start Orbit Mobile; double-click again to stop it.
cd "$(dirname "$0")" || exit 1
node scripts/service.mjs toggle
echo
echo "Press any key to close this window…"
read -k1 -s
