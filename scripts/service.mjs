#!/usr/bin/env node
/**
 * Orbit Mobile service control — start / stop the stack yourself.
 *
 *   node scripts/service.mjs start     # opencode2 + Orbit server (background)
 *   node scripts/service.mjs stop
 *   node scripts/service.mjs restart
 *   node scripts/service.mjs status
 *   node scripts/service.mjs toggle    # start if stopped, stop if running
 *
 * Nothing runs until you start it. `start` launches both processes detached,
 * writes pid files, and waits until healthy. `stop` shuts both down.
 *
 * Ports: Orbit server :3011 (tailnet-facing), opencode2 :4099 (loopback).
 */

import { spawn, spawnSync } from 'node:child_process';
import { closeSync, existsSync, mkdirSync, openSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
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
  try { mkdirSync(SUPPORT, { recursive: true }); writeFileSync(secretFile, generated, { mode: 0o600 }); } catch {}
  return generated;
};
const PASSWORD = resolveUiPassword();
const START_TIMEOUT_MS = 60 * 1000;

const OPENCODE2_BIN = join(homedir(), '.local', 'lib', 'node_modules', '@opencode-ai', 'cli', 'bin', 'opencode2.exe');
const NODE_BIN = process.execPath;
const CLI_ENTRY = join(ROOT, 'packages', 'web', 'bin', 'cli.js');

const pidPath = (name) => join(RUN_DIR, `${name}.pid`);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const log = (...a) => console.log('[orbit-mobile]', ...a);

const readPid = (name) => {
  try {
    const pid = Number(readFileSync(pidPath(name), 'utf8').trim());
    if (!Number.isFinite(pid)) return null;
    process.kill(pid, 0); // throws if not alive
    return pid;
  } catch { return null; }
};

const isRunning = (name) => readPid(name) !== null;

const isOrbitProcess = (pid) => {
  try {
    const res = spawnSync('ps', ['-o', 'command=', '-p', String(pid)], { encoding: 'utf8' });
    return /cli\.js serve|desktop-service\.mjs|opencode2|orbit-mobile/.test((res.stdout || '').trim());
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
    if (!isOrbitProcess(pid)) continue;
    try { process.kill(pid, 'SIGTERM'); killed += 1; } catch {}
  }
  return killed;
};

const waitForHealth = async (port, path, headers = {}, validate) => {
  const deadline = Date.now() + START_TIMEOUT_MS;
  while (Date.now() < deadline) {
    try {
      const res = await fetch(`http://127.0.0.1:${port}${path}`, { headers, signal: AbortSignal.timeout(3000) });
      if (res.ok) {
        const contentType = res.headers.get('content-type') || '';
        if (contentType.includes('application/json')) {
          const body = await res.json().catch(() => null);
          if (!validate || validate(body)) return true;
        }
      }
    } catch {}
    await sleep(400);
  }
  return false;
};

const tailnetUrls = () => {
  const res = spawnSync('tailscale', ['ip', '-4'], { encoding: 'utf8' });
  const ip = (res.stdout || '').trim().split('\n')[0];
  return ip ? [`http://${ip}:${SERVER_PORT}`] : [];
};

const notify = (title, message) => {
  try { spawnSync('osascript', ['-e', `display notification ${JSON.stringify(message)} with title ${JSON.stringify(title)}`]); } catch {}
};

const startOne = (name, command, args, env, logFile, errFile) => {
  const outFd = openSync(join(LOG_DIR, logFile), 'a');
  const errFd = openSync(join(LOG_DIR, errFile), 'a');
  const child = spawn(command, args, {
    cwd: ROOT,
    env: { ...process.env, HOME: homedir(), PATH: `${homedir()}/.local/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:${process.env.PATH || ''}`, ...env },
    detached: true,
    stdio: ['ignore', outFd, errFd],
  });
  child.unref();
  closeSync(outFd);
  closeSync(errFd);
  child.on('error', (error) => log(`${name} spawn error: ${error.message}`));
  if (Number.isFinite(child.pid) && child.pid > 1) {
    writeFileSync(pidPath(name), String(child.pid));
  }
  return child.pid;
};

const startAction = async () => {
  mkdirSync(RUN_DIR, { recursive: true });
  mkdirSync(LOG_DIR, { recursive: true });
  mkdirSync(DATA_DIR, { recursive: true });

  if (isRunning('server') && isRunning('backend')) {
    log('already running.');
    return statusAction();
  }
  log('starting…');
  for (const p of [SERVER_PORT, BACKEND_PORT]) { const n = killPort(p); if (n) log(`cleared stale listener(s) on :${p}`); }

  const backendPid = startOne('backend', OPENCODE2_BIN, ['serve', '--hostname', '127.0.0.1', '--port', String(BACKEND_PORT)], {
    XDG_DATA_HOME: DATA_DIR,
    OPENCODE_SERVER_PASSWORD: PASSWORD,
  }, 'opencode2.log', 'opencode2.err.log');

  const backendAuth = { authorization: `Basic ${Buffer.from(`opencode:${PASSWORD}`).toString('base64')}` };
  if (!await waitForHealth(BACKEND_PORT, '/api/health', backendAuth, (body) => body?.healthy === true)) {
    log('backend failed to become healthy — see logs/opencode2.err.log');
    stopAction({ quiet: true });
    process.exitCode = 1;
    return;
  }

  const serverPid = startOne('server', NODE_BIN, [CLI_ENTRY, 'serve', '--foreground', '--port', String(SERVER_PORT)], {
    ORBIT_OPENCODE_V2: '1',
    OPENCODE_HOST: `http://127.0.0.1:${BACKEND_PORT}`,
    OPENCODE_SKIP_START: 'true',
    OPENCODE_SERVER_PASSWORD: PASSWORD,
    ORBIT_USER_HOME: homedir(),
    ORBIT_HOST: '0.0.0.0',
    ORBIT_UI_PASSWORD: PASSWORD,
  }, 'server.log', 'server.err.log');

  if (!await waitForHealth(SERVER_PORT, '/health', {}, (body) => body?.status === 'ok' || typeof body?.openCodePort !== 'undefined')) {
    log('server failed to become healthy — see logs/server.err.log');
    stopAction({ quiet: true });
    process.exitCode = 1;
    return;
  }

  const urls = tailnetUrls();
  log('running.');
  console.log(`  backend  pid ${backendPid}  http://127.0.0.1:${BACKEND_PORT}`);
  console.log(`  server   pid ${serverPid}  http://127.0.0.1:${SERVER_PORT}`);
  for (const url of urls) console.log(`  phone    ${url}  (password: ${PASSWORD})`);
  notify('Orbit Mobile', 'Servers started');
};

const stopAction = ({ quiet = false } = {}) => {
  let stopped = false;
  for (const name of ['server', 'backend']) {
    const pid = readPid(name);
    if (pid) {
      try { process.kill(pid, 'SIGTERM'); } catch {}
      stopped = true;
    }
    try { rmSync(pidPath(name)); } catch {}
  }
  // Reap anything still holding the ports (children, stale listeners).
  for (const p of [SERVER_PORT, BACKEND_PORT]) killPort(p);
  if (!quiet) {
    if (stopped) { log('stopped.'); notify('Orbit Mobile', 'Servers stopped'); }
    else log('not running.');
  }
};

const statusAction = () => {
  const backend = isRunning('backend');
  const server = isRunning('server');
  if (!backend && !server) { log('not running.'); return; }
  log('running.');
  console.log(`  backend  ${backend ? `pid ${readPid('backend')}` : 'stopped'}  http://127.0.0.1:${BACKEND_PORT}`);
  console.log(`  server   ${server ? `pid ${readPid('server')}` : 'stopped'}  http://127.0.0.1:${SERVER_PORT}`);
  for (const url of tailnetUrls()) console.log(`  phone    ${url}`);
};

const command = process.argv[2];
switch (command) {
  case 'start': await startAction(); break;
  case 'stop': stopAction(); break;
  case 'restart': stopAction({ quiet: true }); await sleep(500); await startAction(); break;
  case 'status': statusAction(); break;
  case 'toggle': (isRunning('server') || isRunning('backend')) ? stopAction() : await startAction(); break;
  default:
    console.error('Usage: node scripts/service.mjs <start|stop|restart|status|toggle>');
    process.exit(1);
}
