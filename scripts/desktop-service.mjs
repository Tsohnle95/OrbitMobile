#!/usr/bin/env node
/**
 * Desktop-tied Orbit Mobile service.
 *
 * Runs the Orbit web server for as long as a parent process (the Orbit desktop
 * app) is alive, and tears it down the moment the parent exits — including a
 * hard kill — so "desktop app open = mobile works, desktop app closed = it
 * stops" holds even if the app crashes.
 *
 * Two modes:
 *   - External backend (ORBIT_EXTERNAL_BACKEND=1 + OPENCODE_HOST): attach to
 *     the desktop app's shared OpenCode daemon so mobile and desktop share
 *     sessions. This is the normal desktop-tied mode.
 *   - Own backend (fallback): spawn an isolated opencode2 on BACKEND_PORT.
 *
 *   node scripts/desktop-service.mjs --parent-pid <pid>
 */

import { spawn, spawnSync } from 'node:child_process';
import { closeSync, mkdirSync, openSync, rmSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const SUPPORT = join(homedir(), 'Library', 'Application Support', 'OrbitMobile');
const RUN_DIR = join(SUPPORT, 'run');
const LOG_DIR = join(SUPPORT, 'logs');
const DATA_DIR = join(SUPPORT, 'data');

const SERVER_PORT = Number(process.env.ORBIT_PORT || 3011);
const BACKEND_PORT = Number(process.env.ORBIT_BACKEND_PORT || 4099);
const UI_PASSWORD = process.env.ORBIT_PASSWORD || 'orbit2026';
const START_TIMEOUT_MS = 60 * 1000;

const OPENCODE2_BIN = join(homedir(), '.local', 'lib', 'node_modules', '@opencode-ai', 'cli', 'bin', 'opencode2.exe');
const NODE_BIN = process.execPath;
const CLI_ENTRY = join(ROOT, 'packages', 'web', 'bin', 'cli.js');
const STATUS_FILE = join(RUN_DIR, 'desktop-status.json');

// Attach to the desktop app's shared daemon when it hands us one.
const externalUrl = process.env.ORBIT_EXTERNAL_BACKEND === '1' ? (process.env.OPENCODE_HOST || '').trim() : '';
const EXTERNAL = externalUrl.length > 0;
const BACKEND_USERNAME = process.env.OPENCODE_SERVER_USERNAME || 'opencode';
const BACKEND_PASSWORD = process.env.OPENCODE_SERVER_PASSWORD || '';

const argParent = (() => {
  const i = process.argv.indexOf('--parent-pid');
  return i >= 0 ? Number(process.argv[i + 1]) : NaN;
})();
const PARENT_PID = Number.isFinite(argParent) ? argParent : Number(process.env.ORBIT_PARENT_PID || 0);

const log = (...a) => console.log(new Date().toISOString(), '[desktop-service]', ...a);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

mkdirSync(RUN_DIR, { recursive: true });
mkdirSync(LOG_DIR, { recursive: true });
mkdirSync(DATA_DIR, { recursive: true });

const spawnLogged = (name, command, args, env, cwd = ROOT) => {
  const out = openSync(join(LOG_DIR, `${name}.log`), 'a');
  const err = openSync(join(LOG_DIR, `${name}.err.log`), 'a');
  const child = spawn(command, args, {
    cwd,
    env: { ...process.env, HOME: homedir(), PATH: `${homedir()}/.local/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:${process.env.PATH || ''}`, ...env },
    stdio: ['ignore', out, err],
  });
  closeSync(out);
  closeSync(err);
  child.on('exit', (code) => log(`${name} exited`, code));
  return child;
};

const waitForHealth = async (url, headers = {}) => {
  const deadline = Date.now() + START_TIMEOUT_MS;
  while (Date.now() < deadline) {
    try {
      const res = await fetch(url, { headers, signal: AbortSignal.timeout(3000) });
      if (res.ok) return true;
    } catch {}
    await sleep(400);
  }
  return false;
};

const killPort = (port) => {
  const res = spawnSync('lsof', ['-ti', `tcp:${port}`, '-sTCP:LISTEN'], { encoding: 'utf8' });
  for (const pid of (res.stdout || '').trim().split(/\s+/).filter(Boolean)) {
    try { process.kill(Number(pid), 'SIGTERM'); } catch {}
  }
};

let backend = null;
let server = null;
let stopping = false;

const stop = (reason) => {
  if (stopping) return;
  stopping = true;
  log(`stopping (${reason})`);
  // Only ever stop processes we spawned — never the desktop app's daemon.
  for (const child of [server, backend]) { if (child) { try { child.kill('SIGTERM'); } catch {} } }
  try { rmSync(STATUS_FILE); } catch {}
};

process.on('SIGTERM', () => { stop('SIGTERM'); setTimeout(() => process.exit(0), 300); });
process.on('SIGINT', () => { stop('SIGINT'); setTimeout(() => process.exit(0), 300); });
process.on('exit', () => stop('exit'));

const parentAlive = () => {
  if (!PARENT_PID) return true;
  try { process.kill(PARENT_PID, 0); return true; } catch { return false; }
};
setInterval(() => {
  if (!parentAlive()) { stop('parent exited'); process.exit(0); }
}, 2000);

// Start. In external mode the desktop app owns the daemon; we own only the web
// server, and must not touch the daemon's port.
killPort(SERVER_PORT);
if (!EXTERNAL) killPort(BACKEND_PORT);

if (EXTERNAL) {
  log(`attaching to desktop backend ${externalUrl}`);
} else {
  backend = spawnLogged('opencode2', OPENCODE2_BIN, ['serve', '--hostname', '127.0.0.1', '--port', String(BACKEND_PORT)], {
    XDG_DATA_HOME: DATA_DIR,
    OPENCODE_SERVER_PASSWORD: BACKEND_PASSWORD || UI_PASSWORD,
  });
  const auth = { authorization: `Basic ${Buffer.from(`opencode:${BACKEND_PASSWORD || UI_PASSWORD}`).toString('base64')}` };
  if (!await waitForHealth(`http://127.0.0.1:${BACKEND_PORT}/api/health`, auth)) {
    log('backend failed to start');
    stop('backend unhealthy');
    process.exit(1);
  }
}

const backendUrl = EXTERNAL ? externalUrl : `http://127.0.0.1:${BACKEND_PORT}`;
const backendPassword = EXTERNAL ? BACKEND_PASSWORD : (BACKEND_PASSWORD || UI_PASSWORD);

server = spawnLogged('server', NODE_BIN, [CLI_ENTRY, 'serve', '--foreground', '--port', String(SERVER_PORT)], {
  ORBIT_OPENCODE_V2: '1',
  OPENCODE_HOST: backendUrl,
  OPENCODE_SKIP_START: 'true',
  OPENCODE_SERVER_USERNAME: BACKEND_USERNAME,
  OPENCODE_SERVER_PASSWORD: backendPassword,
  ORBIT_USER_HOME: homedir(),
  ORBIT_HOST: '0.0.0.0',
  ORBIT_UI_PASSWORD: UI_PASSWORD,
});

if (!await waitForHealth(`http://127.0.0.1:${SERVER_PORT}/health`)) {
  log('server failed to start');
  stop('server unhealthy');
  process.exit(1);
}

writeFileSync(STATUS_FILE, JSON.stringify({
  pid: process.pid,
  parentPid: PARENT_PID,
  port: SERVER_PORT,
  password: UI_PASSWORD,
  sharedBackend: EXTERNAL ? backendUrl : null,
  startedAt: new Date().toISOString(),
}, null, 2));

log(`ready on :${SERVER_PORT} (${EXTERNAL ? `shared backend ${backendUrl}` : `own backend :${BACKEND_PORT}`}), parent ${PARENT_PID}`);
