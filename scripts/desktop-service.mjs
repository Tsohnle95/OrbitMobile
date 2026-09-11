#!/usr/bin/env node
/**
 * Desktop-tied Orbit Mobile service.
 *
 * Runs the v2 opencode2 backend + Orbit web server in the foreground, owned by
 * a parent process (the Orbit desktop app). It watches the parent and shuts
 * everything down the moment the parent exits — including a hard kill — so
 * "desktop app open = mobile works, desktop app closed = it stops" holds even
 * if the app crashes.
 *
 *   node scripts/desktop-service.mjs --parent-pid <pid>
 *
 * Writes run/desktop-status.json while running so the desktop app can show the
 * connection URL.
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
const PASSWORD = process.env.ORBIT_PASSWORD || 'orbit2026';
const START_TIMEOUT_MS = 60 * 1000;

const OPENCODE2_BIN = join(homedir(), '.local', 'lib', 'node_modules', '@opencode-ai', 'cli', 'bin', 'opencode2.exe');
const NODE_BIN = process.execPath;
const CLI_ENTRY = join(ROOT, 'packages', 'web', 'bin', 'cli.js');
const STATUS_FILE = join(RUN_DIR, 'desktop-status.json');

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

const waitForHealth = async (port, path, headers = {}) => {
  const deadline = Date.now() + START_TIMEOUT_MS;
  while (Date.now() < deadline) {
    try {
      const res = await fetch(`http://127.0.0.1:${port}${path}`, { headers, signal: AbortSignal.timeout(3000) });
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
  for (const child of [server, backend]) { if (child) { try { child.kill('SIGTERM'); } catch {} } }
  try { rmSync(STATUS_FILE); } catch {}
};

process.on('SIGTERM', () => { stop('SIGTERM'); setTimeout(() => process.exit(0), 300); });
process.on('SIGINT', () => { stop('SIGINT'); setTimeout(() => process.exit(0), 300); });
process.on('exit', () => stop('exit'));

// Watch the parent: if it dies, take the whole stack with it.
const parentAlive = () => {
  if (!PARENT_PID) return true;
  try { process.kill(PARENT_PID, 0); return true; } catch { return false; }
};
setInterval(() => {
  if (!parentAlive()) { stop('parent exited'); process.exit(0); }
}, 2000);

// Start the stack.
for (const port of [SERVER_PORT, BACKEND_PORT]) { try { killPort(port); } catch {} }

backend = spawnLogged('opencode2', OPENCODE2_BIN, ['serve', '--hostname', '127.0.0.1', '--port', String(BACKEND_PORT)], {
  XDG_DATA_HOME: DATA_DIR,
  OPENCODE_SERVER_PASSWORD: PASSWORD,
});

const backendAuth = { authorization: `Basic ${Buffer.from(`opencode:${PASSWORD}`).toString('base64')}` };
if (!await waitForHealth(BACKEND_PORT, '/api/health', backendAuth)) {
  log('backend failed to start');
  stop('backend unhealthy');
  process.exit(1);
}

server = spawnLogged('server', NODE_BIN, [CLI_ENTRY, 'serve', '--foreground', '--port', String(SERVER_PORT)], {
  ORBIT_OPENCODE_V2: '1',
  OPENCODE_HOST: `http://127.0.0.1:${BACKEND_PORT}`,
  OPENCODE_SKIP_START: 'true',
  OPENCODE_SERVER_PASSWORD: PASSWORD,
  ORBIT_USER_HOME: homedir(),
  ORBIT_HOST: '0.0.0.0',
  ORBIT_UI_PASSWORD: PASSWORD,
});

if (!await waitForHealth(SERVER_PORT, '/health')) {
  log('server failed to start');
  stop('server unhealthy');
  process.exit(1);
}

writeFileSync(STATUS_FILE, JSON.stringify({
  pid: process.pid,
  parentPid: PARENT_PID,
  port: SERVER_PORT,
  password: PASSWORD,
  startedAt: new Date().toISOString(),
}, null, 2));

log(`ready on :${SERVER_PORT} (backend :${BACKEND_PORT}), parent ${PARENT_PID}`);
