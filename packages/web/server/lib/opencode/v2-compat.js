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

const createV2EventTranslator = () => {
  const turns = new Map();

  const turnFor = (data) => {
    const key = `${data.sessionID ?? ''}:${data.assistantMessageID ?? 'global'}`;
    let turn = turns.get(key);
    if (!turn) {
      turn = { assistantMessageID: null, sessionID: null };
      turns.set(key, turn);
      if (turns.size > 64) {
        turns.delete(turns.keys().next().value);
      }
    }
    if (data.assistantMessageID) turn.assistantMessageID = data.assistantMessageID;
    if (data.sessionID) turn.sessionID = data.sessionID;
    return turn;
  };

  const emit = (id, type, properties) =>
    `data: ${JSON.stringify({ id, type, properties })}`;

  return (rawLine) => {
    if (!rawLine.startsWith('data:')) return [rawLine];
    const payload = rawLine.slice(5).trim();
    if (!payload) return [rawLine];
    let parsed;
    try {
      parsed = JSON.parse(payload);
    } catch {
      return [rawLine];
    }

    const data = parsed.data ?? {};
    const eventId = parsed.id;
    const out = [];

    switch (parsed.type) {
      case 'session.execution.started': {
        out.push(emit(eventId, 'session.status', { ...(data.sessionID ? { sessionID: data.sessionID } : {}), status: { type: 'busy' }, ...data }));
        break;
      }
      case 'session.execution.succeeded':
      case 'session.execution.failed': {
        out.push(emit(eventId, 'session.status', { ...(data.sessionID ? { sessionID: data.sessionID } : {}), status: { type: 'idle' }, ...data }));
        break;
      }
      case 'session.step.started': {
        turnFor(data);
        out.push(emit(eventId, 'message.updated', {
          info: {
            id: data.assistantMessageID,
            sessionID: data.sessionID,
            role: 'assistant',
            agent: data.agent ?? 'build',
            model: data.model ?? null,
            time: { created: Date.now() },
          },
        }));
        break;
      }
      case 'session.step.ended': {
        if (data.assistantMessageID) {
          out.push(emit(eventId, 'message.updated', {
            info: {
              id: data.assistantMessageID,
              sessionID: data.sessionID,
              role: 'assistant',
              finish: data.finish ?? 'stop',
              cost: data.cost,
              tokens: data.tokens,
              time: { created: Date.now(), completed: Date.now() },
            },
          }));
        }
        break;
      }
      case 'session.text.started':
      case 'session.reasoning.started': {
        const turn = turnFor(data);
        const kind = parsed.type === 'session.text.started' ? 'text' : 'reasoning';
        const partID = `${turn.assistantMessageID}:${kind}:${data.ordinal ?? 0}`;
        const part = {
          id: partID,
          messageID: turn.assistantMessageID,
          sessionID: turn.sessionID,
          type: kind,
          text: '',
          time: { start: Date.now() },
        };
        out.push(emit(eventId, 'message.part.updated', { part }));
        break;
      }
      case 'session.text.delta':
      case 'session.reasoning.delta': {
        const turn = turnFor(data);
        const kind = parsed.type === 'session.text.delta' ? 'text' : 'reasoning';
        out.push(emit(eventId, 'message.part.delta', {
          sessionID: data.sessionID,
          messageID: data.assistantMessageID,
          partID: `${turn.assistantMessageID}:${kind}:${data.ordinal ?? 0}`,
          field: 'text',
          delta: data.delta ?? '',
        }));
        break;
      }
      case 'session.text.ended':
      case 'session.reasoning.ended': {
        const turn = turnFor(data);
        const kind = parsed.type === 'session.text.ended' ? 'text' : 'reasoning';
        const partID = `${turn.assistantMessageID}:${kind}:${data.ordinal ?? 0}`;
        out.push(emit(eventId, 'message.part.updated', {
          part: {
            id: partID,
            messageID: turn.assistantMessageID,
            sessionID: turn.sessionID,
            type: kind,
            text: typeof data.text === 'string' ? data.text : '',
          },
        }));
        break;
      }
      default: {
        // Unknown v2 types pass through in v1 envelope shape; the UI ignores
        // types it does not handle.
        out.push(emit(eventId, parsed.type, data));
      }
    }

    return out;
  };
};

let activeTranslator = null;

const translateEventLine = (rawLine) => {
  if (!activeTranslator) {
    activeTranslator = createV2EventTranslator();
  }
  const lines = activeTranslator(rawLine);
  return lines.join('\n');
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
        const flushBlock = (block) => {
          const lines = block
            .split('\n')
            .flatMap((line) => translateEventLine(line).split('\n'))
            .filter((line, index, all) => !(line === '' && index === all.length - 1));
          res.write(`${lines.join('\n')}\n\n`);
        };
        const pump = async () => {
          while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n');
            let separatorIndex = buffer.indexOf('\n\n');
            while (separatorIndex !== -1) {
              const block = buffer.slice(0, separatorIndex);
              buffer = buffer.slice(separatorIndex + 2);
              if (block.trim()) flushBlock(block);
              separatorIndex = buffer.indexOf('\n\n');
            }
          }
          if (buffer.trim()) flushBlock(buffer.trim());
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
