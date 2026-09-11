#!/usr/bin/env node
/**
 * Install (or remove) the macOS launchd services that keep Orbit Mobile
 * reachable over Tailscale, speaking opencode v2.
 *
 *   node scripts/install-service.mjs install   # write + load the services
 *   node scripts/install-service.mjs uninstall # unload + remove them
 *
 * Services:
 *   com.orbitmobile.opencode2  opencode2 v2 backend on 127.0.0.1:4099
 *                              (isolated XDG_DATA_HOME)
 *   com.orbitmobile.server     Orbit web/API server on 0.0.0.0:3011,
 *                              v2 compat, pointed at the backend above
 *
 * Configuration lives in the script below (ports, password, data dir).
 */

import { mkdirSync, writeFileSync, rmSync, existsSync, chmodSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const SUPPORT = join(homedir(), 'Library', 'Application Support', 'OrbitMobile');
const LAUNCH_AGENTS = join(homedir(), 'Library', 'LaunchAgents');

const OPENCODE2_BIN = join(homedir(), '.local', 'lib', 'node_modules', '@opencode-ai', 'cli', 'bin', 'opencode2.exe');
const NODE_BIN = process.execPath; // node running this script
const CLI_ENTRY = join(ROOT, 'packages', 'web', 'bin', 'cli.js');

const BACKEND_PORT = 4099;
const SERVER_PORT = 3011;
const PASSWORD = 'orbit2026'; // change me
const DATA_DIR = join(SUPPORT, 'data');

const LABELS = { backend: 'com.orbitmobile.opencode2', server: 'com.orbitmobile.server' };

const plist = (label, scriptPath, logFile) => `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>${label}</string>
  <key>ProgramArguments</key>
  <array>
    <string>/bin/sh</string>
    <string>${scriptPath}</string>
  </array>
  <key>RunAtLoad</key>
  <true/>
  <key>KeepAlive</key>
  <true/>
  <key>ProcessType</key>
  <string>Background</string>
  <key>StandardOutPath</key>
  <string>${join(SUPPORT, 'logs', logFile)}</string>
  <key>StandardErrorPath</key>
  <string>${join(SUPPORT, 'logs', logFile.replace(/\.log$/, '.err.log'))}</string>
</dict>
</plist>
`;

const backendScript = `#!/bin/sh
export PATH="${homedir()}/.local/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:$PATH"
export HOME="${homedir()}"
export XDG_DATA_HOME="${DATA_DIR}"
export OPENCODE_SERVER_PASSWORD="${PASSWORD}"
exec "${OPENCODE2_BIN}" serve --hostname 127.0.0.1 --port ${BACKEND_PORT}
`;

const serverScript = `#!/bin/sh
export PATH="${homedir()}/.local/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:$PATH"
export HOME="${homedir()}"
export ORBIT_OPENCODE_V2="1"
export OPENCODE_HOST="http://127.0.0.1:${BACKEND_PORT}"
export OPENCODE_SKIP_START="true"
export OPENCODE_SERVER_PASSWORD="${PASSWORD}"
export ORBIT_USER_HOME="${homedir()}"
export ORBIT_HOST="0.0.0.0"
export ORBIT_UI_PASSWORD="${PASSWORD}"
i=0
while [ $i -lt 30 ]; do
  curl -fsS -u "opencode:${PASSWORD}" "http://127.0.0.1:${BACKEND_PORT}/api/health" >/dev/null 2>&1 && break
  i=$((i+1)); sleep 1
done
exec "${NODE_BIN}" "${CLI_ENTRY}" serve --foreground --port ${SERVER_PORT}
`;

const run = (cmd, args) => spawnSync(cmd, args, { stdio: 'inherit' });

const uid = process.getuid();
const domain = `gui/${uid}`;

const install = () => {
  mkdirSync(join(SUPPORT, 'bin'), { recursive: true });
  mkdirSync(join(SUPPORT, 'logs'), { recursive: true });
  mkdirSync(DATA_DIR, { recursive: true });
  mkdirSync(LAUNCH_AGENTS, { recursive: true });

  const backendPath = join(SUPPORT, 'bin', 'opencode2');
  const serverPath = join(SUPPORT, 'bin', 'orbit-server');
  writeFileSync(backendPath, backendScript);
  writeFileSync(serverPath, serverScript);
  chmodSync(backendPath, 0o755);
  chmodSync(serverPath, 0o755);

  const backendPlist = join(LAUNCH_AGENTS, `${LABELS.backend}.plist`);
  const serverPlist = join(LAUNCH_AGENTS, `${LABELS.server}.plist`);
  writeFileSync(backendPlist, plist(LABELS.backend, backendPath, 'opencode2.log'));
  writeFileSync(serverPlist, plist(LABELS.server, serverPath, 'server.log'));

  for (const label of [LABELS.backend, LABELS.server]) run('launchctl', ['bootout', `${domain}/${label}`]);
  run('launchctl', ['bootstrap', domain, backendPlist]);
  run('launchctl', ['bootstrap', domain, serverPlist]);

  console.log(`\nInstalled. Backend :${BACKEND_PORT}, server :${SERVER_PORT} (bound to 0.0.0.0 / tailnet).`);
  console.log(`Logs: ${join(SUPPORT, 'logs')}`);
  console.log('Reach it from the phone at http://<tailscale-ip-or-name>:' + SERVER_PORT);
};

const uninstall = () => {
  for (const label of [LABELS.backend, LABELS.server]) run('launchctl', ['bootout', `${domain}/${label}`]);
  for (const label of [LABELS.backend, LABELS.server]) {
    const p = join(LAUNCH_AGENTS, `${label}.plist`);
    if (existsSync(p)) rmSync(p);
  }
  console.log('Uninstalled launchd services (backup/keystore and data dir left in place).');
};

const command = process.argv[2];
if (command === 'install') install();
else if (command === 'uninstall') uninstall();
else {
  console.error('Usage: node scripts/install-service.mjs <install|uninstall>');
  process.exit(1);
}
