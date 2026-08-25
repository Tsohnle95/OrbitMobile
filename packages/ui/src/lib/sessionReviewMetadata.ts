import type { Session } from '@opencode-ai/sdk/v2';

export type SessionMetadataRecord = Record<string, unknown>;

type OrbitMetadata = {
  kind?: 'review';
  originalSessionID?: string;
  reviewSessionID?: string;
};

const isRecord = (value: unknown): value is Record<string, unknown> =>
  Boolean(value && typeof value === 'object' && !Array.isArray(value));

export const getSessionMetadata = (session: Session | null | undefined): SessionMetadataRecord => {
  const metadata = (session as (Session & { metadata?: unknown }) | null | undefined)?.metadata;
  return isRecord(metadata) ? metadata : {};
};

const getOrbitMetadata = (metadata: SessionMetadataRecord): OrbitMetadata => {
  const value = metadata.orbit;
  return isRecord(value) ? value as OrbitMetadata : {};
};

export const getReviewSessionID = (session: Session | null | undefined): string | null => {
  const value = getOrbitMetadata(getSessionMetadata(session)).reviewSessionID;
  return typeof value === 'string' && value.trim().length > 0 ? value : null;
};

export const getOriginalSessionID = (session: Session | null | undefined): string | null => {
  const value = getOrbitMetadata(getSessionMetadata(session)).originalSessionID;
  return typeof value === 'string' && value.trim().length > 0 ? value : null;
};

export const isReviewSession = (session: Session | null | undefined): boolean =>
  getOrbitMetadata(getSessionMetadata(session)).kind === 'review' && Boolean(getOriginalSessionID(session));

export const withReviewSessionLink = (
  metadata: SessionMetadataRecord,
  reviewSessionID: string,
): SessionMetadataRecord => {
  const current = getOrbitMetadata(metadata);
  return {
    ...metadata,
    orbit: {
      ...current,
      reviewSessionID,
    },
  };
};

export const withReviewSessionMarker = (
  metadata: SessionMetadataRecord,
  originalSessionID: string,
): SessionMetadataRecord => {
  const current = getOrbitMetadata(metadata);
  return {
    ...metadata,
    orbit: {
      ...current,
      kind: 'review' as const,
      originalSessionID,
    },
  };
};

export const withoutReviewSessionLink = (
  metadata: SessionMetadataRecord,
  reviewSessionID: string,
): SessionMetadataRecord => {
  const current = getOrbitMetadata(metadata);
  if (current.reviewSessionID !== reviewSessionID) return metadata;

  const restOrbit = { ...current };
  delete restOrbit.reviewSessionID;
  const next: SessionMetadataRecord = { ...metadata };
  if (Object.keys(restOrbit).length > 0) {
    next.orbit = restOrbit;
  } else {
    delete next.orbit;
  }
  return next;
};
