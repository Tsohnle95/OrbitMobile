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
import { closeSync, mkdirSync, openSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { randomBytes } from 'node:crypto';
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
// The UI password gates a 0.0.0.0 bind, so it must not be a shared default.
// Prefer an explicit env override, else a persisted per-install random secret.
const resolveUiPassword = () => {
  const fromEnv = process.env.ORBIT_PASSWORD;
  if (typeof fromEnv === 'string' && fromEnv.trim()) return fromEnv.trim();
  const secretFile = join(SUPPORT, 'ui-password');
  try {
    const existing = readFileSync(secretFile, 'utf8').trim();
    if (existing) return existing;
  } catch {
    // No persisted secret yet.
  }
  const generated = randomBytes(24).toString('base64url');
  try { writeFileSync(secretFile, generated, { mode: 0o600 }); } catch {}
  return generated;
};
const UI_PASSWORD = resolveUiPassword();
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
  child.on('error', (error) => log(`${name} spawn error: ${error.message}`));
  return child;
};

const waitForHealth = async (url, headers = {}, { timeoutMs = START_TIMEOUT_MS, validate } = {}) => {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const res = await fetch(url, { headers, signal: AbortSignal.timeout(3000) });
      if (res.ok) {
        const contentType = res.headers.get('content-type') || '';
        if (!contentType.includes('application/json')) {
          // A foreign listener can answer 200 with HTML; keep waiting.
        } else {
          const body = await res.json().catch(() => null);
          if (!validate || validate(body)) return { ok: true, body };
        }
      }
    } catch {}
    await sleep(400);
  }
  return { ok: false, body: null };
};

// Only ever signal a listener we own. Anything else on the port is left alone.
const isOrbitProcess = (pid) => {
  try {
    const res = spawnSync('ps', ['-o', 'command=', '-p', String(pid)], { encoding: 'utf8' });
    const command = (res.stdout || '').trim();
    return /cli\.js serve|desktop-service\.mjs|opencode2|orbit-mobile/.test(command);
  } catch {
    return false;
  }
};

const killPort = (port) => {
  const res = spawnSync('lsof', ['-ti', `tcp:${port}`, '-sTCP:LISTEN'], { encoding: 'utf8' });
  const pids = (res.stdout || '')
    .trim()
    .split(/\s+/)
    .filter(Boolean)
    .map(Number)
    .filter((pid) => Number.isFinite(pid) && pid > 1 && pid !== process.pid);
  let killed = 0;
  for (const pid of pids) {
    if (!isOrbitProcess(pid)) {
      log(`refusing to signal unrelated pid ${pid} on :${port}`);
      continue;
    }
    try { process.kill(pid, 'SIGTERM'); killed += 1; } catch {}
  }
  return killed;
};

let backend = null;
let server = null;
let stopping = false;

const stop = (reason, { exitCode } = {}) => {
  if (stopping) {
    if (exitCode !== undefined) process.exit(exitCode);
    return;
  }
  stopping = true;
  log(`stopping (${reason})`);
  // Only ever stop processes we spawned — never the desktop app's daemon.
  const children = [server, backend].filter(Boolean);
  for (const child of children) {
    try { child.kill('SIGTERM'); } catch {}
  }
  // Escalate if a child ignores SIGTERM, but never block exit for long.
  setTimeout(() => {
    for (const child of children) {
      if (child.exitCode === null && !child.killed) {
        try { child.kill('SIGKILL'); } catch {}
      }
    }
  }, 3000).unref();
  try { rmSync(STATUS_FILE); } catch {}
  if (exitCode !== undefined) process.exit(exitCode);
};

process.on('SIGTERM', () => stop('SIGTERM', { exitCode: 0 }));
process.on('SIGINT', () => stop('SIGINT', { exitCode: 0 }));
process.on('exit', () => stop('exit'));

const parentAlive = () => {
  if (!PARENT_PID) return true;
  try { process.kill(PARENT_PID, 0); return true; } catch { return false; }
};
setInterval(() => {
  if (!parentAlive()) stop('parent exited', { exitCode: 0 });
}, 2000);

// Start. In external mode the desktop app owns the daemon; we own only the web
// server, and must not touch the daemon's port.
killPort(SERVER_PORT);
if (!EXTERNAL) killPort(BACKEND_PORT);
if (stopping) process.exit(0);

if (EXTERNAL) {
  log(`attaching to desktop backend ${externalUrl}`);
  // The desktop daemon can be down at launch; verify before advertising ready.
  const auth = { authorization: `Basic ${Buffer.from(`${BACKEND_USERNAME}:${BACKEND_PASSWORD}`).toString('base64')}` };
  const health = await waitForHealth(`${externalUrl}/api/health`, auth, {
    timeoutMs: 15_000,
    validate: (body) => body?.healthy === true,
  });
  if (!health.ok) {
    log(`external backend ${externalUrl} did not report healthy`);
    stop('external unhealthy', { exitCode: 1 });
  }
} else {
  backend = spawnLogged('opencode2', OPENCODE2_BIN, ['serve', '--hostname', '127.0.0.1', '--port', String(BACKEND_PORT)], {
    XDG_DATA_HOME: DATA_DIR,
    OPENCODE_SERVER_PASSWORD: BACKEND_PASSWORD || UI_PASSWORD,
  });
  backend.on('exit', (code) => {
    if (!stopping) stop(`backend exited (${code})`, { exitCode: 1 });
  });
  const auth = { authorization: `Basic ${Buffer.from(`opencode:${BACKEND_PASSWORD || UI_PASSWORD}`).toString('base64')}` };
  const health = await waitForHealth(`http://127.0.0.1:${BACKEND_PORT}/api/health`, auth, {
    validate: (body) => body?.healthy === true,
  });
  if (!health.ok) {
    log('backend failed to start');
    stop('backend unhealthy', { exitCode: 1 });
  }
}
if (stopping) process.exit(0);

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
server.on('exit', (code) => {
  if (!stopping) stop(`server exited (${code})`, { exitCode: 1 });
});
if (stopping) process.exit(0);

const serverHealth = await waitForHealth(`http://127.0.0.1:${SERVER_PORT}/health`, {}, {
  validate: (body) => body?.status === 'ok' || typeof body?.openCodePort !== 'undefined',
});
if (!serverHealth.ok) {
  log('server failed to start');
  stop('server unhealthy', { exitCode: 1 });
}
if (stopping) process.exit(0);

writeFileSync(STATUS_FILE, JSON.stringify({
  pid: process.pid,
  parentPid: PARENT_PID,
  port: SERVER_PORT,
  password: UI_PASSWORD,
  sharedBackend: EXTERNAL ? backendUrl : null,
  startedAt: new Date().toISOString(),
}, null, 2), { mode: 0o600 });

log(`ready on :${SERVER_PORT} (${EXTERNAL ? `shared backend ${backendUrl}` : `own backend :${BACKEND_PORT}`}), parent ${PARENT_PID}`);
