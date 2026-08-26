import express from 'express';

const LOOPBACK_ADDRESSES = new Set(['127.0.0.1', '::1', '::ffff:127.0.0.1']);

const DEFAULT_V2_MODEL = 'opencode-go/ox-alpha-free';

let cachedDefaultModel = null;

const resolveDefaultModel = async (targetBase, authHeaders) => {
  if (process.env.ORBIT_OPENCODE_DEFAULT_MODEL) {
    return process.env.ORBIT_OPENCODE_DEFAULT_MODEL;
  }
  if (cachedDefaultModel) {
    return cachedDefaultModel;
  }
  try {
    const response = await fetch(`${targetBase}/api/session?limit=20`, {
      headers: { accept: 'application/json', ...authHeaders },
    });
    const body = await response.json().catch(() => null);
    const sessions = body?.data ?? body ?? [];
    for (const session of sessions) {
      if (!session?.id) continue;
      const messagesResponse = await fetch(
        `${targetBase}/api/session/${session.id}/message`,
        { headers: { accept: 'application/json', ...authHeaders } },
      );
      const messagesBody = await messagesResponse.json().catch(() => null);
      const messages = messagesBody?.data ?? messagesBody ?? [];
      for (const entry of messages) {
        const model = entry?.model;
        if (model?.providerID && model?.id) {
          cachedDefaultModel = `${model.providerID}/${model.id}`;
          return cachedDefaultModel;
        }
      }
    }
  } catch {
    // Fall through to the static default below.
  }
  return DEFAULT_V2_MODEL;
};

const splitModelRef = (modelRef) => {
  const [providerID, ...rest] = String(modelRef).split('/');
  return { providerID: providerID || 'opencode-go', id: rest.join('/') || 'default' };
};

const unwrapData = (body) => {
  if (body && typeof body === 'object' && !Array.isArray(body) && 'data' in body) {
    return body.data;
  }
  return body;
};

const synthesizeProviderSnapshot = async (targetBase, authHeaders) => {
  const modelRef = await resolveDefaultModel(targetBase, authHeaders);
  const { providerID, id: modelId } = splitModelRef(modelRef);
  let displayName = providerID;
  try {
    const response = await fetch(`${targetBase}/api/provider`, {
      headers: { accept: 'application/json', ...authHeaders },
    });
    const body = await response.json().catch(() => null);
    const meta = unwrapData(body);
    const match = Array.isArray(meta) ? meta.find((entry) => entry?.id === providerID) : null;
    if (match?.name) displayName = match.name;
  } catch {
    // Display name is cosmetic; the id alone is fine when metadata fails.
  }
  return [
    {
      id: providerID,
      name: displayName,
      models: {
        [modelId]: {
          id: modelId,
          name: modelId,
          release_date: '',
          attachment: false,
          reasoning: true,
          temperature: true,
          tool_call: true,
          cost: {},
        },
      },
    },
  ];
};

const translateSessionMessageToV1 = (entry) => {
  if (!entry || typeof entry !== 'object') return entry;
  if (entry.type === 'user' || entry.role === 'user') {
    return {
      info: {
        id: entry.id,
        sessionID: entry.sessionID,
        role: 'user',
        time: entry.time ?? {},
      },
      parts: [{ type: 'text', text: entry.text ?? '' }],
    };
  }
  const content = Array.isArray(entry.content) ? entry.content : [];
  const text = content
    .filter((piece) => piece?.type === 'text')
    .map((piece) => piece.text ?? '')
    .join('');
  return {
    info: {
      id: entry.id,
      sessionID: entry.sessionID,
      role: 'assistant',
      agent: entry.agent,
      model: entry.model,
      time: entry.time ?? {},
      finish: entry.finish,
    },
    parts: [{ type: 'text', text }],
  };
};

const V2_EVENT_TYPE_MAP = {
  'session.execution.started': { type: 'session.status', properties: (data) => ({ status: 'busy' }) },
  'session.execution.succeeded': { type: 'session.status', properties: () => ({ status: 'idle' }) },
  'session.execution.failed': { type: 'session.status', properties: () => ({ status: 'idle' }) },
  'server.connected': { type: 'server.connected', properties: (data) => data },
};

const translateEventLine = (rawLine) => {
  if (!rawLine.startsWith('data:')) return rawLine;
  const payload = rawLine.slice(5).trim();
  if (!payload) return rawLine;
  let parsed;
  try {
    parsed = JSON.parse(payload);
  } catch {
    return rawLine;
  }
  const mapped = V2_EVENT_TYPE_MAP[parsed.type];
  if (!mapped) {
    return `data: ${JSON.stringify({ id: parsed.id, type: parsed.type, properties: parsed.data ?? {} })}`;
  }
  return `data: ${JSON.stringify({
    id: parsed.id,
    type: mapped.type,
    properties: { ...(mapped.properties(parsed.data ?? {}) ?? {}), ...(parsed.data ?? {}) },
  })}`;
};

export const isV2BackendMode = () => process.env.ORBIT_OPENCODE_V2 === '1';

export const registerV2CompatRoutes = (app, { resolveTargetBase, getAuthHeaders }) => {
  const router = express.Router();
  router.use(express.json({ limit: '25mb', type: () => true }));

  router.use((req, res, next) => {
    const remote = req.socket.remoteAddress || '';
    if (!LOOPBACK_ADDRESSES.has(remote)) {
      return res.status(403).json({ error: 'internal compatibility endpoint' });
    }
    next();
  });

  router.all('/*splat', async (req, res) => {
    const targetBase = resolveTargetBase();
    const authHeaders = getAuthHeaders();
    const legacyPath = req.originalUrl.replace(/^\/internal\/oc2/, '') || '/';
    const queryIndex = legacyPath.indexOf('?');
    const legacyRoute = queryIndex === -1 ? legacyPath : legacyPath.slice(0, queryIndex);
    const url = new URL(legacyPath, 'http://compat.local');
    url.searchParams.set('directory', url.searchParams.get('directory') ?? '');
    const directory = url.searchParams.get('directory');
    const stripDirectory = () => {
      url.searchParams.delete('directory');
      return url.searchParams.toString() ? `?${url.searchParams.toString()}` : '';
    };
    const headers = { accept: 'application/json', ...authHeaders };
    if (req.headers['content-type']) headers['content-type'] = req.headers['content-type'];
    if (directory) headers['x-opencode-directory'] = directory;

    const sendJson = (status, body) => {
      res.status(status).json(body);
    };

    try {
      if (legacyRoute === '/global/health' && req.method === 'GET') {
        const response = await fetch(`${targetBase}/api/health`, { headers });
        const body = await response.json().catch(() => null);
        return sendJson(response.status, {
          healthy: body?.healthy === true,
          version: body?.version ?? null,
          ...(body ?? {}),
        });
      }

      if ((legacyRoute === '/provider' || legacyRoute === '/config/providers') && req.method === 'GET') {
        const providers = await synthesizeProviderSnapshot(targetBase, authHeaders);
        return sendJson(200, legacyPath === '/provider' ? providers : { providers });
      }

      if (legacyRoute === '/model' && req.method === 'GET') {
        const providers = await synthesizeProviderSnapshot(targetBase, authHeaders);
        const models = providers.flatMap((provider) =>
          Object.values(provider.models).map((model) => ({
            id: model.id,
            providerID: provider.id,
            name: model.name,
          })),
        );
        return sendJson(200, models);
      }

      if (legacyRoute === '/config' && req.method === 'GET') {
        const modelRef = await resolveDefaultModel(targetBase, authHeaders);
        return sendJson(200, { model: modelRef, default_agent: 'build', default_agent_name: 'build' });
      }

      if (legacyRoute === '/agent' && req.method === 'GET') {
        const response = await fetch(`${targetBase}/api/agent${url.search}`, { headers });
        const body = await response.json().catch(() => null);
        return sendJson(response.status, unwrapData(body) ?? []);
      }

      const sessionMatch = legacyRoute.match(/^\/session\/([^/]+)\/message$/);
      if (sessionMatch && req.method === 'POST') {
        const sessionId = sessionMatch[1];
        const parsed = typeof req.body === 'object' && req.body !== null ? req.body : {};
        const parts = Array.isArray(parsed.parts) ? parsed.parts : [];
        const text = parts
          .filter((part) => part?.type === 'text')
          .map((part) => part.text ?? '')
          .join('\n')
          .trim() || parsed.text || '';
        const outbound = { text };
        const query = directory ? `?directory=${encodeURIComponent(directory)}` : '';
        const response = await fetch(`${targetBase}/api/session/${sessionId}/prompt${query}`, {
          method: 'POST',
          headers,
          body: JSON.stringify(outbound),
        });
        const body = await response.json().catch(() => null);
        return sendJson(response.status, body ?? {});
      }

      const promptAsyncMatch = legacyRoute.match(/^\/session\/([^/]+)\/prompt_async$/);
      if (promptAsyncMatch && req.method === 'POST') {
        const sessionId = promptAsyncMatch[1];
        const parsed = typeof req.body === 'object' && req.body !== null ? req.body : {};
        const parts = Array.isArray(parsed.parts) ? parsed.parts : [];
        const text = parts
          .filter((part) => part?.type === 'text')
          .map((part) => part.text ?? '')
          .join('\n')
          .trim() || parsed.text || '';
        const outbound = { text };
        if (parsed.agent) outbound.agent = parsed.agent;
        const query = directory ? `?directory=${encodeURIComponent(directory)}` : '';
        const response = await fetch(`${targetBase}/api/session/${sessionId}/prompt${query}`, {
          method: 'POST',
          headers,
          body: JSON.stringify(outbound),
        });
        const body = await response.json().catch(() => null);
        return sendJson(response.status, body ?? {});
      }

      const messageListMatch = legacyRoute.match(/^\/session\/([^/]+)\/message$/);
      if (messageListMatch && req.method === 'GET') {
        const sessionId = messageListMatch[1];
        const query = directory ? `?directory=${encodeURIComponent(directory)}` : '';
        const response = await fetch(`${targetBase}/api/session/${sessionId}/message${query}`, { headers });
        const body = await response.json().catch(() => null);
        const entries = unwrapData(body) ?? [];
        const translated = Array.isArray(entries) ? entries.map(translateSessionMessageToV1) : [];
        return sendJson(response.status, translated.reverse());
      }

      if (legacyRoute === '/event' || legacyRoute === '/global/event') {
        res.writeHead(200, {
          'content-type': 'text/event-stream',
          'cache-control': 'no-cache',
          connection: 'keep-alive',
        });
        const upstream = await fetch(`${targetBase}/api/event`, {
          headers: { ...authHeaders, accept: 'text/event-stream' },
        });
        const reader = upstream.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';
        const pump = async () => {
          while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, { stream: true });
            const lines = buffer.split('\n');
            buffer = lines.pop() ?? '';
            for (const line of lines) res.write(`${translateEventLine(line)}\n`);
          }
          res.end();
        };
        pump().catch(() => res.end());
        req.on('close', () => reader.cancel().catch(() => {}));
        return;
      }

      if (legacyRoute === '/experimental/session' && req.method === 'GET') {
        // The beta scopes sessions by exact directory; the app may ask from a
        // different working dir than the one a session was created under.
        // Drop the filter so every session stays visible.
        const allUrl = new URL(legacyPath, 'http://compat.local');
        const response = await fetch(`${targetBase}/api/session`, { headers });
        const body = await response.json().catch(() => null);
        return sendJson(response.status, body ?? { data: [] });
      }

      if (legacyRoute === '/path' && req.method === 'GET') {
        return sendJson(200, { home: process.env.ORBIT_USER_HOME || '/Users/ty' });
      }

      if (legacyRoute === '/lsp' && req.method === 'GET') {
        return sendJson(200, []);
      }

      if (legacyRoute === '/mcp' && req.method === 'GET') {
        return sendJson(200, []);
      }

      if (legacyRoute === '/command' && req.method === 'GET') {
        return sendJson(200, []);
      }

      if (legacyRoute === '/skill' && req.method === 'GET') {
        return sendJson(200, []);
      }

      if (legacyRoute === '/global/upgrade' && req.method === 'GET') {
        return sendJson(200, { available: false });
      }

      if (legacyRoute === '/lsp/status' && req.method === 'GET') {
        return sendJson(200, []);
      }

      const response = await fetch(`${targetBase}/api${legacyPath}${url.search}`, {
        method: req.method,
        headers,
        body: ['GET', 'HEAD'].includes(req.method) || req.body === undefined
          ? undefined
          : JSON.stringify(req.body),
      });
      const body = await response.text();
      res.status(response.status);
      const contentType = response.headers.get('content-type');
      if (contentType) res.set('content-type', contentType);
      let parsed = body;
      try {
        parsed = JSON.parse(body);
        if (
          parsed &&
          typeof parsed === 'object' &&
          !Array.isArray(parsed) &&
          'data' in parsed &&
          Object.keys(parsed).length <= 3
        ) {
          parsed = parsed.data;
        }
      } catch {
        // Non-JSON passes through verbatim.
      }
      res.send(parsed);
    } catch (error) {
      sendJson(502, { error: error instanceof Error ? error.message : 'v2 compat failure' });
    }
  });

  return router;
};
