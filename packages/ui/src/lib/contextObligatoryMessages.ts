import type { Session } from '@opencode-ai/sdk/v2';

import { getSessionMetadata, type SessionMetadataRecord } from './sessionReviewMetadata';

export type ContextObligatoryMessage = {
  id: string;
  createdAt: number;
  role: 'user' | 'assistant';
};

const isRecord = (value: unknown): value is Record<string, unknown> =>
  Boolean(value && typeof value === 'object' && !Array.isArray(value));

export const getContextObligatoryMessages = (
  session: Session | null | undefined,
): ContextObligatoryMessage[] => {
  const orbit = getSessionMetadata(session).orbit;
  if (!isRecord(orbit) || !Array.isArray(orbit.context_obligatory_messages)) return [];

  return orbit.context_obligatory_messages.filter((value): value is ContextObligatoryMessage =>
    isRecord(value)
    && typeof value.id === 'string'
    && typeof value.createdAt === 'number'
    && Number.isFinite(value.createdAt)
    && (value.role === 'user' || value.role === 'assistant'));
};

export const withContextObligatoryMessage = (
  metadata: SessionMetadataRecord,
  message: ContextObligatoryMessage,
  pinned: boolean,
): SessionMetadataRecord => {
  const orbit = isRecord(metadata.orbit) ? metadata.orbit : {};
  const current = Array.isArray(orbit.context_obligatory_messages)
    ? orbit.context_obligatory_messages.filter((value): value is ContextObligatoryMessage =>
      isRecord(value) && typeof value.id === 'string')
    : [];
  const withoutMessage = current.filter((value) => value.id !== message.id);
  const nextMessages = pinned ? [...withoutMessage, message] : withoutMessage;

  return {
    ...metadata,
    orbit: {
      ...orbit,
      context_obligatory_messages: nextMessages,
    },
  };
};
