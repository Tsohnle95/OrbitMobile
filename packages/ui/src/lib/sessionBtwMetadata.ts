import type { Session } from '@opencode-ai/sdk/v2';
import { getSessionMetadata, type SessionMetadataRecord } from '@/lib/sessionReviewMetadata';

/**
 * Session-metadata contract for the `/btw` flow, mirroring the review-session
 * link in `sessionReviewMetadata`:
 *
 * - The parent (the session `/btw` was typed into) carries
 *   `orbit.btwSessionID` pointing at its active btw fork. The panel is
 *   derived from this link, so it appears only in the parent session and
 *   survives reloads.
 * - The fork itself is marked `orbit.kind = 'btw'` with
 *   `originalSessionID` (its parent) and `btwBoundaryMessageID` — the id of
 *   the last message cloned from the parent. Messages with a greater id are
 *   the fork's own tail and are what the panel renders. Message ids are
 *   server-generated ascending identifiers, so the boundary is a plain string
 *   comparison and immune to client clock skew.
 */
type BtwMetadata = {
  kind?: string;
  originalSessionID?: string;
  btwSessionID?: string;
  btwBoundaryMessageID?: string;
};

const getOrbitMetadata = (metadata: SessionMetadataRecord): BtwMetadata => {
  const value = metadata.orbit;
  if (!value || typeof value !== 'object' || Array.isArray(value)) return {};
  // SAFETY: session metadata is persisted, externally writable data; this is
  // its parsing boundary. `BtwMetadata` only declares optional fields and
  // every reader re-validates the field it consumes in `nonEmpty`.
  return value as BtwMetadata;
};

const nonEmpty = (value: string | undefined): string | null =>
  typeof value === 'string' && value.trim().length > 0 ? value : null;

/** The parent's link to its active btw fork, or null. */
export const getBtwSessionID = (session: Session | null | undefined): string | null =>
  nonEmpty(getOrbitMetadata(getSessionMetadata(session)).btwSessionID);

export const isBtwSession = (session: Session | null | undefined): boolean =>
  getOrbitMetadata(getSessionMetadata(session)).kind === 'btw'
  && Boolean(getBtwOriginalSessionID(session));

/** The fork's back-pointer to the session `/btw` was typed into. */
export const getBtwOriginalSessionID = (session: Session | null | undefined): string | null => {
  const orbit = getOrbitMetadata(getSessionMetadata(session));
  return orbit.kind === 'btw' ? nonEmpty(orbit.originalSessionID) : null;
};

/**
 * The id of the last message the fork inherited from the parent. `null` means
 * the fork inherited nothing (empty parent) and every message is its own.
 */
export const getBtwBoundaryMessageID = (session: Session | null | undefined): string | null => {
  const orbit = getOrbitMetadata(getSessionMetadata(session));
  return orbit.kind === 'btw' ? nonEmpty(orbit.btwBoundaryMessageID) : null;
};

export const withBtwSessionLink = (
  metadata: SessionMetadataRecord,
  btwSessionID: string,
): SessionMetadataRecord => ({
  ...metadata,
  orbit: {
    ...getOrbitMetadata(metadata),
    btwSessionID,
  },
});

/**
 * Mark the fork as a btw session. The fork clones the parent's metadata
 * wholesale (including review links or a stale `btwSessionID`), so the
 * inherited `orbit` object is replaced, not merged.
 */
export const withBtwSessionMarker = (
  metadata: SessionMetadataRecord,
  originalSessionID: string,
  boundaryMessageID: string | null,
): SessionMetadataRecord => {
  const orbit: BtwMetadata = { kind: 'btw', originalSessionID };
  if (boundaryMessageID) orbit.btwBoundaryMessageID = boundaryMessageID;
  return { ...metadata, orbit };
};

/** Remove the btw marker so a promoted fork becomes a plain session. */
export const withoutBtwSessionMarker = (metadata: SessionMetadataRecord): SessionMetadataRecord => {
  const orbit = getOrbitMetadata(metadata);
  if (orbit.kind !== 'btw') return metadata;
  const rest: BtwMetadata = { ...orbit };
  delete rest.kind;
  delete rest.originalSessionID;
  delete rest.btwBoundaryMessageID;
  const next: SessionMetadataRecord = { ...metadata };
  if (Object.keys(rest).length > 0) {
    next.orbit = rest;
  } else {
    delete next.orbit;
  }
  return next;
};

/** Unlink the parent, but only if it still points at this fork. */
export const withoutBtwSessionLink = (
  metadata: SessionMetadataRecord,
  btwSessionID: string,
): SessionMetadataRecord => {
  const orbit = getOrbitMetadata(metadata);
  if (orbit.btwSessionID !== btwSessionID) return metadata;
  const rest: BtwMetadata = { ...orbit };
  delete rest.btwSessionID;
  const next: SessionMetadataRecord = { ...metadata };
  if (Object.keys(rest).length > 0) {
    next.orbit = rest;
  } else {
    delete next.orbit;
  }
  return next;
};
