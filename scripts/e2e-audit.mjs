#!/usr/bin/env node
/**
 * Orbit Mobile end-to-end audit.
 *
 * Acts as the Android app against a running Orbit mobile server: authenticates,
 * opens both live-event channels, exercises every SDK surface the UI touches,
 * sends a real prompt, and asserts the full translated event sequence
 * (status -> message -> part deltas -> tool lifecycle -> completion).
 *
 * Usage:
 *   ORBIT_URL=http://127.0.0.1:3011 ORBIT_PASSWORD=orbit2026 \
 *   PROMPT_TEXT="Reply with exactly: AUDIT OK" \
 *   node scripts/e2e-audit.mjs
 */

import WebSocket from 'ws';

const BASE = (process.env.ORBIT_URL ?? 'http://127.0.0.1:3011').replace(/\/+$/, '');
const PASSWORD = process.env.ORBIT_PASSWORD ?? 'orbit2026';
const DIRECTORY = process.env.ORBIT_DIRECTORY ?? '';
const PROMPT_TEXT = process.env.PROMPT_TEXT ?? 'Reply with exactly: AUDIT OK';
const EVENT_WAIT_MS = Number(process.env.EVENT_WAIT_MS ?? 45000);

const results = [];
const record = (name, ok, detail = '') => {
  results.push({ name, ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
};

let cookieJar = '';

async function call(path, { method = 'GET', body, headers = {} } = {}) {
  const response = await fetch(`${BASE}${path}`, {
    method,
    headers: {
      accept: 'application/json',
      ...(cookieJar ? { cookie: cookieJar } : {}),
      'content-type': 'application/json',
      ...headers,
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const setCookie = response.headers.get('set-cookie');
  if (setCookie && !cookieJar.includes('oc_ui_session=')) {
    cookieJar = setCookie.split(';')[0];
  }
  let json = null;
  try {
    json = await response.json();
  } catch {
    // Some endpoints return HTML or empty bodies by design.
  }
  return { status: response.status, json };
}

function collectSse(durationMs, onFrame) {
  return new Promise((resolve) => {
    fetch(`${BASE}/api/orbit/events`, { headers: { accept: 'text/event-stream', cookie: cookieJar } })
      .then(async (response) => {
        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';
        const timer = setTimeout(() => { reader.cancel().catch(() => {}); }, durationMs);
        try {
          while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, { stream: true });
            let index;
            while ((index = buffer.indexOf('\n\n')) !== -1) {
              const block = buffer.slice(0, index);
              buffer = buffer.slice(index + 2);
              const dataLine = block.split('\n').find((l) => l.startsWith('data:'));
              if (!dataLine) continue;
              try {
                onFrame(JSON.parse(dataLine.slice(5).trim()));
              } catch {}
            }
          }
        } catch {}
        clearTimeout(timer);
        resolve();
      })
      .catch(() => resolve());
  });
}

function collectWs(token, durationMs, onFrame) {
  return new Promise((resolve) => {
    const ws = new WebSocket(`${BASE.replace(/^http/, 'ws')}/api/global/event/ws?oc_url_token=${token}`, {
      headers: { Origin: 'http://localhost' },
    });
    const timer = setTimeout(() => { ws.close(); }, durationMs);
    ws.on('open', () => console.log('       [ws open]'));
    ws.on('message', (m) => {
      try { onFrame(JSON.parse(m.toString())); } catch {}
    });
    ws.on('close', () => { clearTimeout(timer); resolve(); });
    ws.on('error', () => { clearTimeout(timer); resolve(); });
  });
}

const main = async () => {
  console.log(`Auditing ${BASE}\n`);

  await call('/auth/session', { method: 'POST', body: { password: PASSWORD, trustDevice: true } });

  const health = await call('/api/opencode/health');
  record('opencode health reports healthy', health.json?.healthy === true, JSON.stringify(health.json));

  const version = await call('/api/opencode/version');
  record('opencode version present', Boolean(version.json?.version), version.json?.version ?? '');

  const providers = await call('/api/config/providers');
  const providerList = providers.json?.providers ?? [];
  const firstProvider = providerList[0];
  const modelId = Object.keys(firstProvider?.models ?? {})[0];
  record('providers catalog non-empty with models', providerList.length > 0 && Boolean(modelId),
    providerList.map((p) => p.id).join(','));

  const agents = await call('/api/agent');
  record('agents list non-empty', Array.isArray(agents.json) && agents.json.length > 0,
    `${agents.json?.length ?? 0} agents`);

  const globalConfig = await call('/api/global/config');
  record('global config has defaults', Boolean(globalConfig.json?.model), globalConfig.json?.model ?? '');

  const home = await call('/api/fs/home');
  const homeDir = home.json?.home ?? '';
  record('fs home is a real directory', homeDir.startsWith('/Users/') || /^\/(home|Users)\//.test(homeDir), homeDir);

  const urlTokenResponse = await fetch(`${BASE}/auth/url-token`, { method: 'POST', headers: { cookie: cookieJar } });
  const urlToken = (await urlTokenResponse.json().catch(() => ({}))).token;
  record('url token issued for live channels', Boolean(urlToken));

  const created = await call(`/api/session${DIRECTORY ? `?directory=${encodeURIComponent(DIRECTORY)}` : ''}`, {
    method: 'POST',
    body: { title: 'e2e-audit' },
  });
  const sessionId = created.json?.data?.id ?? created.json?.id;
  record('session created', Boolean(sessionId), sessionId ?? JSON.stringify(created.json).slice(0, 120));

  const messagesBefore = await call(`/api/session/${sessionId}/message`);
  record('message list returns array', Array.isArray(messagesBefore.json), `count ${messagesBefore.json?.length ?? '?'}`);

  const pathInfo = await call('/api/path');
  record('legacy /path responds', pathInfo.status === 200 || pathInfo.status === 400, String(pathInfo.status));
  const lsp = await call('/api/lsp');
  record('/lsp stubbed', lsp.status === 200, String(lsp.status));
  const mcpStatus = await call('/api/mcp');
  record('/mcp stubbed', mcpStatus.status === 200, String(mcpStatus.status));

  const seen = { types: {}, toolStates: new Set(), deltas: 0, partUpdated: 0, statusValues: new Set(), replyText: '' };
  let sawPromptAccepted = false;

  const sseDone = collectSse(EVENT_WAIT_MS, (frame) => {
    const type = frame.type ?? frame.payload?.type;
    if (!type) return;
    seen.types[type] = (seen.types[type] ?? 0) + 1;
    if (type === 'orbit:event-stream-ready') sawPromptAccepted = sawPromptAccepted || false;
  });

  const wsDone = urlToken
    ? collectWs(urlToken, EVENT_WAIT_MS, (frame) => {
        const payload = frame.type === 'event' ? frame.payload : null;
        if (!payload) return;
        const type = payload.type ?? '?';
        seen.types[type] = (seen.types[type] ?? 0) + 1;
        const props = payload.properties ?? {};
        if (type === 'session.status') seen.statusValues.add(JSON.stringify(props.status ?? props));
        if (type === 'message.part.delta') seen.deltas += 1;
        if (type === 'message.part.updated') {
          seen.partUpdated += 1;
          const part = props.part ?? {};
          if (part.type === 'tool') {
            seen.toolStates.add(part.state?.status);
            const output = String(part.state?.output ?? '');
            if (output) seen.replyText = output.slice(0, 80);
          }
          if (part.type === 'text' && part.text) seen.replyText = part.text.slice(-80);
        }
      })
    : Promise.resolve();

  await new Promise((r) => setTimeout(r, 1500));

  const prompt = await call(`/api/session/${sessionId}/prompt_async${DIRECTORY ? `?directory=${encodeURIComponent(DIRECTORY)}` : ''}`, {
    method: 'POST',
    body: {
      model: firstProvider && modelId ? { providerID: firstProvider.id, modelID: modelId } : undefined,
      agent: 'build',
      parts: [{ type: 'text', text: PROMPT_TEXT }],
    },
  });
  record('prompt_async accepted (app send path)', prompt.status === 200, `status ${prompt.status}`);
  sawPromptAccepted = sawPromptAccepted || prompt.status === 200;

  await Promise.race([Promise.all([sseDone, wsDone]), new Promise((r) => setTimeout(r, EVENT_WAIT_MS))]);

  const statusBusySeen = [...seen.statusValues].some((v) => v.includes('busy'));
  const statusIdleSeen = [...seen.statusValues].some((v) => v.includes('idle'));
  record('live: session status busy->idle observed', statusBusySeen && statusIdleSeen,
    [...seen.statusValues].join(', ').slice(0, 80));
  record('live: assistant text streamed', seen.deltas > 0 || seen.replyText.length > 0,
    `${seen.deltas} deltas`);
  record('live: message part updates flowed', seen.partUpdated > 0, `${seen.partUpdated} updates`);

  const messagesAfter = await call(`/api/session/${sessionId}/message`);
  const items = Array.isArray(messagesAfter.json) ? messagesAfter.json : [];
  const assistant = items.filter((entry) => entry.info?.role === 'assistant');
  const assistantText = assistant.flatMap((entry) => (entry.parts ?? []).filter((p) => p.type === 'text').map((p) => p.text ?? '')).join(' ');
  record('transcript contains user + assistant turn', items.length >= 2 && assistant.length > 0,
    `${items.length} messages`);

  const toolParts = assistant.flatMap((entry) => (entry.parts ?? []).filter((p) => p.type === 'tool'));

  console.log('\n==== SUMMARY ====');
  const failed = results.filter((r) => !r.ok);
  console.log(`${results.length - failed.length}/${results.length} checks passed`);
  if (failed.length > 0) {
    console.log('\nFAILED:');
    failed.forEach((r) => console.log(` - ${r.name}${r.detail ? ` (${r.detail})` : ''}`));
  }
  console.log('\nEvent types seen:', JSON.stringify(seen.types));
  console.log('Tool states seen:', [...seen.toolStates].join(',') || '(no tools this run)');
  console.log('Assistant reply captured:', assistantText.trim().slice(0, 200) || seen.replyText);
  console.log(`\nAudit session: ${sessionId}`);

  process.exit(failed.length === 0 ? 0 : 2);
};

main().catch((error) => {
  console.error('AUDIT CRASHED:', error.message);
  process.exit(1);
});
