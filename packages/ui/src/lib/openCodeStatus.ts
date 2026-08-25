import { useSessionUIStore } from '@/sync/session-ui-store';
import { getSyncSessions } from '@/sync/sync-refs';
import { useUIStore } from '@/stores/useUIStore';
import { getRuntimeUrlResolver } from './runtime-url';
import { opencodeClient } from './opencode/client';
import { runtimeFetch } from './runtime-fetch';

declare const __APP_VERSION__: string | undefined;

type ProbeResult = {
  ok: boolean;
  status: number;
  elapsedMs: number;
  summary: string;
};

type OrbitHealthSnapshot = {
  openCodePort?: unknown;
  openCodeRunning?: unknown;
  openCodeSecureConnection?: unknown;
  openCodeAuthSource?: unknown;
  isOpenCodeReady?: unknown;
  lastOpenCodeError?: unknown;
  lastOpenCodeLaunchDiagnostics?: unknown;
  opencodeBinaryResolved?: unknown;
  opencodeBinarySource?: unknown;
  opencodeLaunchBinary?: unknown;
  opencodeLaunchArgs?: unknown;
  opencodeLaunchWrapperType?: unknown;
  nodeBinaryResolved?: unknown;
  bunBinaryResolved?: unknown;
};

type OrbitOpencodeResolution = {
  configured?: unknown;
  resolved?: unknown;
  resolvedDir?: unknown;
  source?: unknown;
  detectedNow?: unknown;
  detectedSourceNow?: unknown;
  launchBinary?: unknown;
  launchArgs?: unknown;
  launchWrapperType?: unknown;
  node?: unknown;
  bun?: unknown;
};

const getCurrentDirectory = (): string => {
  const state = useSessionUIStore.getState();
  const currentSessionId = state.currentSessionId;
  if (!currentSessionId) return '';
  const sessions = getSyncSessions();
  const session = sessions.find((s) => s.id === currentSessionId);
  return typeof session?.directory === 'string' ? session.directory : '';
};

const safeFetch = async (input: string, timeoutMs = 6000): Promise<ProbeResult> => {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), timeoutMs);
  const startedAt = Date.now();

  try {
    const resp = await runtimeFetch(input, {
      method: 'GET',
      headers: { Accept: 'application/json' },
      signal: controller.signal,
    });

    const elapsedMs = Date.now() - startedAt;
    const contentType = resp.headers.get('content-type') || '';
    const lower = contentType.toLowerCase();
    const isJson = lower.includes('json') && !lower.includes('text/html');

    let summary = '';
    if (isJson) {
      const json = await resp.json().catch(() => null);
      if (Array.isArray(json)) {
        summary = `json[array] len=${json.length}`;
      } else if (json && typeof json === 'object') {
        const keys = Object.keys(json).slice(0, 8);
        summary = `json[object] keys=${keys.join(',')}${Object.keys(json).length > keys.length ? ',…' : ''}`;
      } else {
        summary = `json[${typeof json}]`;
      }
    } else {
      summary = contentType ? `content-type=${contentType}` : 'no content-type';
    }

    return { ok: resp.ok && isJson, status: resp.status, elapsedMs, summary };
  } catch (error) {
    const elapsedMs = Date.now() - startedAt;
    const isAbort =
      controller.signal.aborted ||
      (error instanceof Error && (error.name === 'AbortError' || error.message.toLowerCase().includes('aborted')));
    const message = isAbort
      ? `timeout after ${timeoutMs}ms`
      : error instanceof Error
        ? error.message
        : String(error);
    return { ok: false, status: 0, elapsedMs, summary: `error=${message}` };
  } finally {
    clearTimeout(timeout);
  }
};

const formatIso = (timestamp: number | null | undefined): string => {
  if (!timestamp || !Number.isFinite(timestamp)) return '(n/a)';
  try {
    return new Date(timestamp).toISOString();
  } catch {
    return '(invalid)';
  }
};

const normalizePort = (value: unknown): number | null => {
  if (typeof value === 'number' && Number.isFinite(value) && value > 0) {
    return Math.trunc(value);
  }
  if (typeof value === 'string') {
    const parsed = Number.parseInt(value, 10);
    if (Number.isFinite(parsed) && parsed > 0) {
      return parsed;
    }
  }
  return null;
};

const isRecord = (value: unknown): value is Record<string, unknown> =>
  !!value && typeof value === 'object' && !Array.isArray(value);

const formatUnknown = (value: unknown, fallback = '(n/a)'): string => {
  if (typeof value === 'string') return value.trim() || fallback;
  if (typeof value === 'number' && Number.isFinite(value)) return String(value);
  if (typeof value === 'boolean') return value ? 'true' : 'false';
  return fallback;
};

const formatLaunchRuntime = (wrapperType: string, node: string, bun: string): string => {
  if (wrapperType === 'node-shebang' || wrapperType === 'node-launcher') {
    return node ? `node (${node})` : 'node';
  }
  if (wrapperType === 'bun-shebang') {
    return bun ? `bun (${bun})` : 'bun';
  }
  if (wrapperType) {
    return wrapperType;
  }
  return 'direct executable';
};

const buildOpenCodeStatusReport = async (): Promise<string> => {
  const now = new Date();
  const appVersion = typeof __APP_VERSION__ !== 'undefined' ? __APP_VERSION__ : '(unknown)';
  const platform = typeof navigator !== 'undefined' ? navigator.userAgent : '(no navigator)';
  const directory = getCurrentDirectory();
  const eventStreamStatus = useUIStore.getState().eventStreamStatus;
  const origin = typeof window !== 'undefined' ? window.location.origin : '';
  const urls = getRuntimeUrlResolver();
  const healthUrl = urls.health();
  const apiBase = urls.api('/api/');

  const orbitHealth: OrbitHealthSnapshot | null = await (async () => {
    if (!healthUrl) return null;
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 5000);
    try {
      const resp = await runtimeFetch(healthUrl, {
        method: 'GET',
        headers: { Accept: 'application/json' },
        signal: controller.signal,
      });
      if (!resp.ok) return null;
      const json = (await resp.json().catch(() => null)) as unknown;
      if (!json || typeof json !== 'object' || Array.isArray(json)) return null;
      return json as OrbitHealthSnapshot;
    } catch {
      return null;
    } finally {
      clearTimeout(timeout);
    }
  })();

  const orbitOpencodeResolutionResult: {
    data: OrbitOpencodeResolution | null;
    status: number | null;
    error: string | null;
  } = await (async () => {
    if (!apiBase) return null;
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 7000);
    try {
      const resp = await runtimeFetch(urls.api('/api/config/opencode-resolution'), {
        method: 'GET',
        headers: { Accept: 'application/json' },
        signal: controller.signal,
      });
      const contentType = resp.headers.get('content-type') || '(none)';
      if (!resp.ok) {
        return { data: null, status: resp.status, error: `http ${resp.status} content-type=${contentType}` };
      }
      const raw = await resp.text();
      let json: unknown = null;
      try {
        json = JSON.parse(raw);
      } catch {
        const snippet = raw.replace(/\s+/g, ' ').slice(0, 120);
        return {
          data: null,
          status: resp.status,
          error: `invalid json content-type=${contentType} body=${snippet || '(empty)'}`,
        };
      }
      if (!json || typeof json !== 'object' || Array.isArray(json)) {
        return { data: null, status: resp.status, error: `invalid json-shape content-type=${contentType}` };
      }
      return { data: json as OrbitOpencodeResolution, status: resp.status, error: null };
    } catch (error) {
      return {
        data: null,
        status: null,
        error: error instanceof Error ? error.message : String(error),
      };
    } finally {
      clearTimeout(timeout);
    }
  })() || { data: null, status: null, error: null };

  const buildProbeUrl = (pathname: string, includeDirectory = true): string | null => {
    if (!apiBase) return null;
    const url = new URL(pathname.replace(/^\/+/, ''), apiBase);
    if (includeDirectory && directory) {
      url.searchParams.set('directory', directory);
    }
    return url.toString();
  };

  const probeTargets: Array<{ label: string; path: string; includeDirectory?: boolean; timeoutMs?: number }> = [
    { label: 'health', path: '/health', includeDirectory: false },
    { label: 'config', path: '/config', includeDirectory: true },
    { label: 'providers', path: '/config/providers', includeDirectory: true },
    { label: 'agents', path: '/agent', includeDirectory: true, timeoutMs: 12000 },
    { label: 'commands', path: '/command', includeDirectory: true, timeoutMs: 10000 },
    { label: 'project', path: '/project/current', includeDirectory: true },
    { label: 'path', path: '/path', includeDirectory: true },
    { label: 'sessions', path: '/session', includeDirectory: true, timeoutMs: 12000 },
    { label: 'sessionStatus', path: '/session/status', includeDirectory: true },
  ];

  const probes = apiBase
    ? await Promise.all(
        probeTargets.map(async (entry) => {
          const url = buildProbeUrl(entry.path, entry.includeDirectory !== false);
          if (!url) return { label: entry.label, url: '(none)', result: null as ProbeResult | null };
          const result = await safeFetch(url, typeof entry.timeoutMs === 'number' ? entry.timeoutMs : undefined);
          return { label: entry.label, url, result };
        })
      )
    : [];

  const lines: string[] = [];
  lines.push(`Time: ${now.toISOString()}`);
  lines.push(`Orbit version: ${appVersion}`);
  lines.push(`Runtime: ${origin || '(unknown)'} (api=${apiBase || '(unknown)'})`);
  lines.push(`OpenCode SDK base: ${opencodeClient.getBaseUrl()}`);
  lines.push(`Event stream: ${eventStreamStatus}`);
  lines.push(`Directory: ${directory || '(none)'}`);
  lines.push(`Platform: ${platform}`);

  const runtimeOpenCodePort = normalizePort(orbitHealth?.openCodePort);
  lines.push(`OpenCode runtime port: ${runtimeOpenCodePort ?? '(unknown)'}`);
  if (typeof orbitHealth?.openCodeRunning === 'boolean') {
    lines.push(`OpenCode runtime running: ${orbitHealth.openCodeRunning ? 'yes' : 'no'}`);
  }
  if (typeof orbitHealth?.openCodeSecureConnection === 'boolean') {
    lines.push(`Secure OpenCode connection: ${orbitHealth.openCodeSecureConnection ? 'true' : 'false'}`);
  }
  if (typeof orbitHealth?.openCodeAuthSource === 'string' && orbitHealth.openCodeAuthSource.trim()) {
    lines.push(`OpenCode auth source: ${orbitHealth.openCodeAuthSource}`);
  }

  if (typeof window !== 'undefined') {
    const injected = (window as unknown as { __ORBIT_MACOS_MAJOR__?: unknown }).__ORBIT_MACOS_MAJOR__;
    if (typeof injected === 'number' && Number.isFinite(injected) && injected > 0) {
      lines.push(`macOS major: ${injected}`);
    }
  }

  const isLikelyMac = /Mac OS X|Macintosh/.test(platform);
  if (isLikelyMac) {
    lines.push('');
    lines.push('OpenCode CLI resolution:');

    const launchDiagnostics = isRecord(orbitHealth?.lastOpenCodeLaunchDiagnostics)
      ? orbitHealth.lastOpenCodeLaunchDiagnostics
      : null;
    const actualLaunchArgs = launchDiagnostics && Array.isArray(launchDiagnostics.args)
      ? launchDiagnostics.args.filter((value): value is string => typeof value === 'string')
      : [];
    const orbitOpencodeResolution = orbitOpencodeResolutionResult.data;
    const configured =
      orbitOpencodeResolution && typeof orbitOpencodeResolution.configured === 'string'
        ? orbitOpencodeResolution.configured
        : null;
    const resolved =
      orbitOpencodeResolution && typeof orbitOpencodeResolution.resolved === 'string'
        ? orbitOpencodeResolution.resolved
        : (orbitHealth && typeof orbitHealth.opencodeBinaryResolved === 'string' ? orbitHealth.opencodeBinaryResolved : '');
    const resolvedDir =
      orbitOpencodeResolution && typeof orbitOpencodeResolution.resolvedDir === 'string'
        ? orbitOpencodeResolution.resolvedDir
        : '';
    const source =
      orbitOpencodeResolution && typeof orbitOpencodeResolution.source === 'string'
        ? orbitOpencodeResolution.source
        : (orbitHealth && typeof orbitHealth.opencodeBinarySource === 'string' ? orbitHealth.opencodeBinarySource : '');
    const configuredLaunchBinary =
      orbitOpencodeResolution && typeof orbitOpencodeResolution.launchBinary === 'string'
        ? orbitOpencodeResolution.launchBinary
        : (orbitHealth && typeof orbitHealth.opencodeLaunchBinary === 'string' ? orbitHealth.opencodeLaunchBinary : '');
    const configuredLaunchWrapperType =
      orbitOpencodeResolution && typeof orbitOpencodeResolution.launchWrapperType === 'string'
        ? orbitOpencodeResolution.launchWrapperType
        : (orbitHealth && typeof orbitHealth.opencodeLaunchWrapperType === 'string' ? orbitHealth.opencodeLaunchWrapperType : '');
    const configuredLaunchArgs =
      orbitOpencodeResolution && Array.isArray(orbitOpencodeResolution.launchArgs)
        ? orbitOpencodeResolution.launchArgs.filter((value): value is string => typeof value === 'string')
        : (orbitHealth && Array.isArray(orbitHealth.opencodeLaunchArgs)
          ? orbitHealth.opencodeLaunchArgs.filter((value): value is string => typeof value === 'string')
          : []);
    const node =
      orbitOpencodeResolution && typeof orbitOpencodeResolution.node === 'string'
        ? orbitOpencodeResolution.node
        : (orbitHealth && typeof orbitHealth.nodeBinaryResolved === 'string' ? orbitHealth.nodeBinaryResolved : '');
    const bun =
      orbitOpencodeResolution && typeof orbitOpencodeResolution.bun === 'string'
        ? orbitOpencodeResolution.bun
        : (orbitHealth && typeof orbitHealth.bunBinaryResolved === 'string' ? orbitHealth.bunBinaryResolved : '');
    const detectedNow =
      orbitOpencodeResolution && typeof orbitOpencodeResolution.detectedNow === 'string'
        ? orbitOpencodeResolution.detectedNow
        : '';
    const detectedSourceNow =
      orbitOpencodeResolution && typeof orbitOpencodeResolution.detectedSourceNow === 'string'
        ? orbitOpencodeResolution.detectedSourceNow
        : '';

    if (configured !== null) {
      lines.push(`- configured: ${configured.trim().length === 0 ? '(cleared)' : configured}`);
    }

    if (resolved) {
      const dir = resolvedDir || (resolved.includes('/') ? resolved.split('/').slice(0, -1).join('/') || '/' : '');
      lines.push(`- opencode: ${resolved}${dir ? ` (dir=${dir})` : ''}`);
    } else {
      lines.push('- opencode: (n/a)');
    }

    lines.push(`- source: ${source || '(n/a)'}`);
    if (detectedNow) {
      lines.push(`- detected-now: ${detectedNow}`);
      lines.push(`- detected-source: ${detectedSourceNow || '(n/a)'}`);
    }
    if (launchDiagnostics) {
      lines.push(`- launched-at: ${formatUnknown(launchDiagnostics.launchedAt)}`);
      lines.push(`- launch: ${formatUnknown(launchDiagnostics.binary)} ${actualLaunchArgs.join(' ')}`.trim());
      lines.push(`- cwd: ${formatUnknown(launchDiagnostics.cwd)}`);
      lines.push(`- wrapper: ${formatUnknown(launchDiagnostics.wrapperType)}`);
      lines.push(`- runtime: ${formatLaunchRuntime(formatUnknown(launchDiagnostics.wrapperType, ''), node, bun)}`);
      lines.push(`- PATH entries: ${formatUnknown(launchDiagnostics.pathEntryCount, '(unknown)')}`);
      lines.push(`- shell env: ${formatUnknown(launchDiagnostics.hasShellEnv, '(unknown)')} (${formatUnknown(launchDiagnostics.shellEnvKeysCount, '?')} keys)`);
    } else {
      lines.push(`- launch-binary: ${configuredLaunchBinary || '(n/a)'}`);
      lines.push(`- launch-wrapper: ${configuredLaunchWrapperType || '(n/a)'}`);
      lines.push(`- launch-args: ${configuredLaunchArgs.length ? configuredLaunchArgs.join(' ') : '(none)'}`);
      lines.push(`- runtime: ${formatLaunchRuntime(configuredLaunchWrapperType || '', node, bun)}`);
    }
    if (!orbitOpencodeResolution && orbitOpencodeResolutionResult.error) {
      lines.push(`- resolution-endpoint: ${orbitOpencodeResolutionResult.error}`);
    }
  }

  lines.push('');
  if (probes.length) {
    lines.push('OpenCode API probes:');
    for (const probe of probes) {
      if (!probe.result) {
        lines.push(`- ${probe.label}: (no url)`);
        continue;
      }
      const { ok, status, elapsedMs, summary } = probe.result;
      const suffix = ok ? '' : ` url=${probe.url}`;
      lines.push(`- ${probe.label}: ${ok ? 'ok' : 'fail'} status=${status} time=${elapsedMs}ms ${summary}${suffix}`);
    }
  } else {
    lines.push('OpenCode API probes: (skipped)');
  }

  lines.push('');
  lines.push(`Generated: ${formatIso(Date.now())}`);
  return lines.join('\n');
};

export const showOpenCodeStatus = async (): Promise<void> => {
  const text = await buildOpenCodeStatusReport();
  const ui = useUIStore.getState();
  ui.setOpenCodeStatusText(text);
  ui.setOpenCodeStatusDialogOpen(true);
};
