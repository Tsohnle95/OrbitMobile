import express from 'express';
import os from 'node:os';

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

// v2 session list items omit the working directory, but the Orbit UI groups,
// resolves, and opens sessions by directory (sessions without one are dropped
// from directory maps). Project ids map to canonical paths via /api/project.
const projectDirectoryCache = { at: 0, map: new Map() };

const fetchProjectDirectories = async (targetBase, authHeaders) => {
  if (projectDirectoryCache.map.size > 0 && Date.now() - projectDirectoryCache.at < 10_000) {
    return projectDirectoryCache.map;
  }
  try {
    const map = new Map();
    const response = await fetch(`${targetBase}/api/project`, {
      headers: { accept: 'application/json', ...authHeaders },
    });
    const body = await response.json().catch(() => null);
    const list = Array.isArray(body?.data) ? body.data : (Array.isArray(body) ? body : []);
    for (const project of list) {
      const canonical = typeof project?.canonical === 'string' ? project.canonical : null;
      if (project?.id && canonical) map.set(project.id, canonical);
    }
    projectDirectoryCache.at = Date.now();
    projectDirectoryCache.map = map;
    return map;
  } catch {
    // A transient failure must not strip directories from every session; reuse
    // the last good map.
    return projectDirectoryCache.map;
  }
};

const withSessionDirectory = (sessions, projectDirectories) => {
  if (!Array.isArray(sessions)) return sessions;
  return sessions.map((session) => {
    if (!session || typeof session !== 'object') return session;
    const worktree = typeof session.project?.worktree === 'string' ? session.project.worktree : null;
    const existing = typeof session.directory === 'string' && session.directory ? session.directory : null;
    // `location.directory` is the authoritative cwd the backend reports; the
    // project canonical is only a fallback (project-root sessions).
    const locationDirectory = typeof session.location?.directory === 'string' && session.location.directory
      ? session.location.directory
      : null;
    const directory = existing ?? locationDirectory ?? worktree ?? projectDirectories.get(session.projectID) ?? null;
    if (!directory) return session;
    return {
      ...session,
      directory,
      project: {
        ...(session.project ?? {}),
        id: session.project?.id ?? session.projectID,
        worktree: session.project?.worktree ?? directory,
      },
    };
  });
};

// Fetch up to a bounded number of session pages so the mobile list shows every
// session, not just the backend's default first page.
const fetchAllV2Sessions = async (targetBase, headers) => {
  const all = [];
  let cursor = null;
  for (let page = 0; page < 12; page += 1) {
    const params = new URLSearchParams({ limit: '200' });
    if (cursor) params.set('cursor', cursor);
    const response = await fetch(`${targetBase}/api/session?${params.toString()}`, { headers });
    const body = await response.json().catch(() => null);
    const list = Array.isArray(body?.data) ? body.data : (Array.isArray(body) ? body : []);
    all.push(...list);
    cursor = body?.cursor?.next;
    if (!cursor || list.length === 0) break;
  }
  return all;
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

const mapToolStateToV1 = (state) => {
  if (!state || typeof state !== 'object') return state;
  const output = Array.isArray(state.content)
    ? state.content.filter((piece) => piece?.type === 'text').map((piece) => piece.text ?? '').join('')
    : (typeof state.output === 'string' ? state.output : undefined);
  const error = state.error && typeof state.error === 'object'
    ? (state.error.message ?? JSON.stringify(state.error))
    : state.error;
  return {
    ...state,
    ...(output !== undefined ? { output } : {}),
    ...(error !== undefined ? { error } : {}),
  };
};

const translateSessionMessageToV1 = (entry, parentID) => {
  if (!entry || typeof entry !== 'object') return entry;
  const messageID = entry.id;
  if (entry.type === 'user' || entry.role === 'user') {
    return {
      info: {
        id: entry.id,
        sessionID: entry.sessionID,
        role: 'user',
        time: entry.time ?? {},
      },
      parts: [{
        id: `${messageID}:text:0`,
        messageID,
        sessionID: entry.sessionID,
        type: 'text',
        text: entry.text ?? '',
      }],
    };
  }
  const content = Array.isArray(entry.content) ? entry.content : [];
  const parts = [];
  content.forEach((piece, index) => {
    const kind = piece?.type === 'reasoning' ? 'reasoning' : piece?.type === 'tool' ? 'tool' : 'text';
    const text = piece?.text ?? '';
    // Skip empty reasoning placeholders; they add nothing and can confuse the
    // UI's part grouping.
    if (kind === 'reasoning' && !text) return;
    parts.push({
      id: `${messageID}:${kind}:${index}`,
      messageID,
      sessionID: entry.sessionID,
      type: kind,
      ...(kind === 'text' || kind === 'reasoning' ? { text, time: piece?.time } : {}),
      ...(kind === 'tool' ? {
        // v2 names the tool `name`; v1 parts expose it as `tool`.
        tool: piece?.name ?? piece?.tool,
        callID: piece?.id,
        state: mapToolStateToV1(piece?.state),
      } : {}),
    });
  });
  // The UI drops parts without an id, so always emit at least one identified
  // part even for an empty assistant message — otherwise the whole transcript
  // renders blank.
  if (parts.length === 0) {
    parts.push({ id: `${messageID}:text:0`, messageID, sessionID: entry.sessionID, type: 'text', text: '' });
  }
  return {
    info: {
      id: entry.id,
      sessionID: entry.sessionID,
      role: 'assistant',
      // The UI groups assistant messages into turns by parentID (the user
      // message that triggered them). v1 provides it; derive it from the
      // preceding user message when the v2 payload omits it.
      parentID: entry.parentID ?? parentID,
      agent: entry.agent,
      mode: entry.mode ?? 'build',
      // v1 exposes the model as top-level modelID/providerID; the UI reads those.
      modelID: entry.model?.id,
      providerID: entry.model?.providerID,
      model: entry.model,
      cost: entry.cost,
      tokens: entry.tokens,
      time: entry.time ?? {},
      finish: entry.finish,
    },
    parts,
  };
};

const createV2EventTranslator = () => {
  const turns = new Map();
  const lastUserMessageBySession = new Map();

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
      case 'session.created':
      case 'session.renamed':
      case 'session.moved':
      case 'session.updated': {
        // v2 emits session lifecycle events with flat fields (no `info`). Wrap
        // them in the v1 `{info}` envelope the UI reducer expects, and carry
        // `location.directory` so the session can be grouped.
        const info = data.info && typeof data.info === 'object'
          ? { ...data.info }
          : {
              id: data.sessionID ?? data.id,
              sessionID: data.sessionID ?? data.id,
              slug: data.slug,
              title: data.title,
              projectID: data.projectID,
              parentID: data.parentID,
              time: data.time ?? { created: Date.now(), updated: Date.now() },
            };
        if (!info.directory && data.location?.directory) info.directory = data.location.directory;
        out.push(emit(eventId, parsed.type === 'session.created' ? 'session.created' : 'session.updated', { info }));
        break;
      }
      case 'session.deleted': {
        const sessionID = data.sessionID ?? data.id;
        out.push(emit(eventId, 'session.deleted', { sessionID, info: { id: sessionID } }));
        break;
      }
      case 'session.inbox.enqueued': {
        // Remember the pending user message so assistant messages emitted by
        // later step events can carry the parentID the UI groups turns by.
        // Only user items are message ids that can parent a turn.
        const itemType = data.item?.type;
        if (data.sessionID && data.inboxID && (itemType === undefined || itemType === 'user')) {
          lastUserMessageBySession.set(data.sessionID, data.inboxID);
        }
        out.push(emit(eventId, parsed.type, data));
        break;
      }
      case 'session.execution.started': {
        out.push(emit(eventId, 'session.status', { ...(data.sessionID ? { sessionID: data.sessionID } : {}), status: { type: 'busy' }, ...data }));
        break;
      }
      case 'session.execution.succeeded':
      case 'session.execution.failed':
      case 'session.execution.interrupted': {
        out.push(emit(eventId, 'session.status', { ...(data.sessionID ? { sessionID: data.sessionID } : {}), status: { type: 'idle' }, ...data }));
        break;
      }
      case 'session.step.failed': {
        // A failed step must settle the session out of "busy" and surface the
        // error; otherwise the turn stays pinned as working forever.
        out.push(emit(eventId, 'session.error', {
          ...(data.sessionID ? { sessionID: data.sessionID } : {}),
          error: data.error,
        }));
        break;
      }
      case 'session.step.started': {
        turnFor(data);
        out.push(emit(eventId, 'message.updated', {
          info: {
            id: data.assistantMessageID,
            sessionID: data.sessionID,
            role: 'assistant',
            parentID: lastUserMessageBySession.get(data.sessionID),
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
              parentID: lastUserMessageBySession.get(data.sessionID),
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
      case 'session.tool.input.started': {
        const turn = turnFor(data);
        if (!turn.tools) turn.tools = {};
        const part = {
          id: data.id,
          callID: data.id,
          tool: data.name ?? 'tool',
          type: 'tool',
          messageID: data.assistantMessageID,
          sessionID: data.sessionID,
          state: { status: 'pending', input: {}, metadata: {}, time: { start: Date.now() } },
        };
        turn.tools[data.id] = part;
        out.push(emit(eventId, 'message.part.updated', { part }));
        break;
      }
      case 'session.tool.input.ended': {
        const turn = turnFor(data);
        const stored = turn.tools?.[data.id];
        let input = {};
        try {
          input = typeof data.text === 'string' ? JSON.parse(data.text) : (data.text ?? {});
        } catch {
          input = { raw: data.text };
        }
        if (stored) {
          stored.state.input = input;
          out.push(emit(eventId, 'message.part.updated', { part: { ...stored } }));
        }
        break;
      }
      case 'session.tool.called': {
        const turn = turnFor(data);
        const stored = turn.tools?.[data.id];
        if (stored) {
          stored.state = { ...stored.state, status: 'running', input: data.input ?? stored.state.input };
          out.push(emit(eventId, 'message.part.updated', { part: { ...stored } }));
        }
        break;
      }
      case 'session.tool.progress': {
        const turn = turnFor(data);
        const stored = turn.tools?.[data.id];
        if (stored) {
          stored.state.metadata = { ...(stored.state.metadata ?? {}), ...(data.metadata ?? {}) };
          out.push(emit(eventId, 'message.part.updated', { part: { ...stored } }));
        }
        break;
      }
      case 'session.tool.success': {
        const turn = turnFor(data);
        const stored = turn.tools?.[data.id];
        if (stored) {
          const outputText = Array.isArray(data.content)
            ? data.content.filter((piece) => piece?.type === 'text').map((piece) => piece.text ?? '').join('')
            : '';
          stored.state = {
            ...stored.state,
            status: 'completed',
            output: outputText,
            time: { ...stored.state.time, end: Date.now() },
          };
          out.push(emit(eventId, 'message.part.updated', { part: { ...stored } }));
        }
        break;
      }
      case 'session.tool.failed':
      case 'session.tool.error': {
        const turn = turnFor(data);
        const stored = turn.tools?.[data.id];
        if (stored) {
          stored.state = {
            ...stored.state,
            status: 'error',
            error: data.error && typeof data.error === 'object'
              ? (data.error.message ?? JSON.stringify(data.error))
              : (typeof data.error === 'string' ? data.error : 'tool failed'),
            time: { ...stored.state.time, end: Date.now() },
          };
          out.push(emit(eventId, 'message.part.updated', { part: { ...stored } }));
        }
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

export const registerV2CompatRoutes = (app, { resolveTargetBase, getAuthHeaders, compatToken }) => {
  const router = express.Router();

  // This layer forwards arbitrary paths to the backend WITH backend credentials,
  // so it must never be reachable by anything but the server's own internal
  // calls. Peer address alone is not a boundary: cloudflared/ngrok tunnels
  // connect from loopback and would pass it. Require the per-process secret the
  // server embeds in its internal base URL.
  router.use((req, res, next) => {
    const remote = req.socket.remoteAddress || '';
    if (!LOOPBACK_ADDRESSES.has(remote)) {
      return res.status(403).json({ error: 'internal compatibility endpoint' });
    }
    const requestPath = req.originalUrl.replace(/^\/internal\/oc2/, '').split('?')[0];
    if (!compatToken || !requestPath.startsWith(`/${compatToken}`)) {
      return res.status(403).json({ error: 'internal compatibility endpoint' });
    }
    next();
  });

  router.use(express.json({ limit: '25mb', type: () => true }));

  router.all('/*splat', async (req, res) => {
    const targetBase = resolveTargetBase();
    const authHeaders = getAuthHeaders();
    const legacyPath = (req.originalUrl.replace(/^\/internal\/oc2/, '') || '/')
      .replace(new RegExp(`^/${compatToken}`), '') || '/';
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
        if (legacyPath === '/provider') return sendJson(200, providers);
        const modelRef = await resolveDefaultModel(targetBase, authHeaders);
        const { providerID, id: modelId } = splitModelRef(modelRef);
        return sendJson(200, { providers, default: { [providerID]: modelId } });
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

      const forwardSessionPrompt = async (sessionId, parsed) => {
        // v2 ignores `model` in the prompt body — it must be selected on the
        // session first. Apply it best-effort so the model picker takes effect.
        const model = parsed.model;
        const providerID = model?.providerID ?? model?.providerId;
        const modelID = model?.modelID ?? model?.modelId ?? model?.id;
        if (providerID && modelID) {
          try {
            await fetch(`${targetBase}/api/session/${sessionId}/model`, {
              method: 'POST',
              headers,
              body: JSON.stringify({ model: { providerID, id: modelID } }),
            });
          } catch {
            // Best-effort; still send the prompt.
          }
        }
        const parts = Array.isArray(parsed.parts) ? parsed.parts : [];
        const text = parts
          .filter((part) => part?.type === 'text')
          .map((part) => part.text ?? '')
          .join('\n')
          .trim() || parsed.text || '';
        const outbound = { text };
        // Preserve the client message id so optimistic user messages dedupe,
        // and the delivery mode so "queue" does not preempt a running turn.
        if (parsed.messageID) outbound.id = parsed.messageID;
        if (parsed.delivery) outbound.delivery = parsed.delivery;
        if (parsed.metadata) outbound.metadata = parsed.metadata;
        if (Array.isArray(parsed.files)) outbound.files = parsed.files;
        if (parsed.agent) outbound.agent = parsed.agent;
        const query = directory ? `?directory=${encodeURIComponent(directory)}` : '';
        return fetch(`${targetBase}/api/session/${sessionId}/prompt${query}`, {
          method: 'POST',
          headers,
          body: JSON.stringify(outbound),
        });
      };

      const sessionMatch = legacyRoute.match(/^\/session\/([^/]+)\/message$/);
      if (sessionMatch && req.method === 'POST') {
        const parsed = typeof req.body === 'object' && req.body !== null ? req.body : {};
        const response = await forwardSessionPrompt(sessionMatch[1], parsed);
        const body = await response.json().catch(() => null);
        return sendJson(response.status, body ?? {});
      }

      const promptAsyncMatch = legacyRoute.match(/^\/session\/([^/]+)\/prompt_async$/);
      if (promptAsyncMatch && req.method === 'POST') {
        const parsed = typeof req.body === 'object' && req.body !== null ? req.body : {};
        const response = await forwardSessionPrompt(promptAsyncMatch[1], parsed);
        const body = await response.json().catch(() => null);
        return sendJson(response.status, body ?? {});
      }

      const messageListMatch = legacyRoute.match(/^\/session\/([^/]+)\/message$/);
      if (messageListMatch && req.method === 'GET') {
        const sessionId = messageListMatch[1];
        // v1 paging: the client sends `limit` and an opaque `before` cursor and
        // reads the next cursor from `x-next-cursor`. v2 paging: `limit` +
        // `cursor` (order defaults to newest-first), exposing `cursor.next`.
        const limit = Number(url.searchParams.get('limit'));
        const before = url.searchParams.get('before');
        const params = new URLSearchParams();
        if (Number.isFinite(limit) && limit > 0) params.set('limit', String(Math.min(limit, 200)));
        if (before) params.set('cursor', before);
        if (directory) params.set('directory', directory);
        const v2Response = await fetch(
          `${targetBase}/api/session/${sessionId}/message${params.toString() ? `?${params.toString()}` : ''}`,
          { headers },
        );
        const body = await v2Response.json().catch(() => null);
        const entries = unwrapData(body) ?? [];
        const nextCursor = body?.cursor?.next;
        if (typeof nextCursor === 'string' && nextCursor) res.set('x-next-cursor', nextCursor);
        // v2 returns newest-first; v1 expects chronological order. Derive each
        // assistant message's parentID from the preceding user message so the
        // UI can group turns.
        const chronological = Array.isArray(entries) ? entries.slice().reverse() : [];
        const translated = [];
        let lastUserId;
        for (const entry of chronological) {
          const isUser = entry?.type === 'user' || entry?.role === 'user';
          translated.push(translateSessionMessageToV1(entry, isUser ? undefined : lastUserId));
          if (isUser) lastUserId = entry.id;
        }
        return sendJson(v2Response.status, translated);
      }

      if (legacyRoute === '/event' || legacyRoute === '/global/event') {
        // Fetch first: a failed/HTML upstream must surface as an error, not a
        // 200 stream that immediately closes.
        const upstreamHeaders = { ...authHeaders, accept: 'text/event-stream' };
        if (req.headers['last-event-id']) upstreamHeaders['last-event-id'] = req.headers['last-event-id'];
        let upstream;
        try {
          upstream = await fetch(`${targetBase}/api/event`, { headers: upstreamHeaders });
        } catch (error) {
          return sendJson(502, { error: `upstream event stream unavailable: ${error.message}` });
        }
        if (!upstream.ok || !upstream.body) {
          return sendJson(upstream.status === 200 ? 502 : upstream.status, { error: 'upstream event stream unavailable' });
        }
        res.writeHead(200, {
          'content-type': 'text/event-stream',
          'cache-control': 'no-cache',
          connection: 'keep-alive',
        });
        const reader = upstream.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';
        const flushBlock = (block) => {
          const lines = block
            .split('\n')
            .flatMap((line) => translateEventLine(line).split('\n'))
            .filter((line, index, all) => !(line === '' && index === all.length - 1));
          return res.write(`${lines.join('\n')}\n\n`);
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
              if (block.trim() && flushBlock(block) === false) {
                // Backpressure: wait for the socket to drain before reading more.
                await new Promise((resolve) => res.once('drain', resolve));
              }
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

      if (legacyRoute === '/session' && req.method === 'POST') {
        // v2 ignores `directory` in the query/header at creation time; it reads
        // the working directory from the body's `location.directory`. Without
        // this the session is created in the server's cwd and the agent has to
        // hunt for the project.
        const parsed = typeof req.body === 'object' && req.body !== null ? { ...req.body } : {};
        if (directory && parsed.location?.directory === undefined) {
          parsed.location = { ...(parsed.location ?? {}), directory };
        }
        const response = await fetch(`${targetBase}/api/session${url.search}`, {
          method: 'POST',
          headers,
          body: JSON.stringify(parsed),
        });
        const text = await response.text();
        let body = text;
        try { body = JSON.parse(text); } catch { /* non-JSON passes through */ }
        if (body && typeof body === 'object' && !Array.isArray(body) && 'data' in body && Object.keys(body).length <= 3) {
          body = body.data;
        }
        if (body && typeof body === 'object' && !Array.isArray(body) && body.id) {
          const [enriched] = withSessionDirectory([body], await fetchProjectDirectories(targetBase, authHeaders));
          body = enriched;
        }
        return sendJson(response.status, body ?? {});
      }

      if (legacyRoute === '/session' && req.method === 'GET') {
        const list = await fetchAllV2Sessions(targetBase, headers);
        return sendJson(200, withSessionDirectory(list, await fetchProjectDirectories(targetBase, authHeaders)));
      }

      const sessionDetailMatch = legacyRoute.match(/^\/session\/([^/]+)$/);
      if (sessionDetailMatch && req.method === 'GET') {
        const response = await fetch(`${targetBase}/api/session/${sessionDetailMatch[1]}${url.search}`, { headers });
        const body = await response.json().catch(() => null);
        const one = unwrapData(body);
        if (!one || typeof one !== 'object' || Array.isArray(one)) return sendJson(response.status, body ?? {});
        const [enriched] = withSessionDirectory([one], await fetchProjectDirectories(targetBase, authHeaders));
        return sendJson(response.status, enriched);
      }

      if (legacyRoute === '/experimental/session' && req.method === 'GET') {
        // The beta scopes sessions by exact directory; the app may ask from a
        // different working dir than the one a session was created under.
        // Drop the filter so every session stays visible. The SDK expects a
        // bare array; honor limit/cursor so client paging terminates.
        let sessions = await fetchAllV2Sessions(targetBase, headers);
        sessions = withSessionDirectory(sessions, await fetchProjectDirectories(targetBase, authHeaders));
        sessions.sort((a, b) => ((b.time?.updated ?? b.time?.created ?? 0) || 0) - ((a.time?.updated ?? a.time?.created ?? 0) || 0));
        const cursorValue = Number(url.searchParams.get('cursor'));
        if (url.searchParams.get('cursor') && Number.isFinite(cursorValue)) {
          sessions = sessions.filter((session) => ((session.time?.updated ?? 0) || 0) < cursorValue);
        }
        const limit = Number(url.searchParams.get('limit'));
        if (Number.isFinite(limit) && limit > 0) {
          sessions = sessions.slice(0, limit);
        }
        return sendJson(200, sessions);
      }

      if (legacyRoute === '/global/config' && req.method === 'GET') {
        const modelRef = await resolveDefaultModel(targetBase, authHeaders);
        return sendJson(200, { model: modelRef, default_agent: 'build', default_agent_name: 'build' });
      }

      if (legacyRoute === '/question' && req.method === 'GET') {
        return sendJson(200, []);
      }

      if (legacyRoute === '/path' && req.method === 'GET') {
        return sendJson(200, { home: process.env.ORBIT_USER_HOME || os.homedir() });
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
