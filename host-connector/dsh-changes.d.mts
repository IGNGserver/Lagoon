export declare const DSH_CHANGES_CAPABILITY: 'harness.changes.v1';
export type DshChangesErrorCode = 'INVALID_PARAMS' | 'METHOD_NOT_FOUND' | 'FEATURE_NOT_SUPPORTED' |
  'NOT_AUTHORIZED' | 'RESOURCE_UNAVAILABLE' | 'INTERNAL' | 'REDIRECT_DENIED' |
  'RESPONSE_TOO_LARGE' | 'RESOURCE_LIMIT' | 'TIMEOUT' | 'PEER_CLOSED';
/** Static, non-retryable errors with no upstream text, credentials, paths or payloads. */
export declare class DshChangesError extends Error {
  readonly code: DshChangesErrorCode;
  constructor(code: string);
}
export type DshChangesJsonValue = null | boolean | number | string | DshChangesJsonValue[] | { [key: string]: DshChangesJsonValue };
export type DshChangesResult = { status: 200; value: { [key: string]: DshChangesJsonValue } } | { status: 404 };
export type DshChangesReadParams =
  { kind: 'summary'; sessionId: string; seq: number; index?: never } |
  { kind: 'diff'; sessionId: string; seq: number; index: number };
/** Structural official service face; the Host supplies this, never Remote input. */
export interface DshChangesConnection {
  createSharedFetchHandler(channel: '/api'): {
    fetch(request: Request, peer: unknown): Promise<Response>;
  };
}
export interface DshChangesPeerOptions {
  readConnection: () => DshChangesConnection | undefined;
  /** Host-created owner Peer. Runtime validates principal.kind and optional signal. */
  peer: unknown;
  /** Host-local test seam, 1..10000 ms. Never a wire parameter. */
  timeoutMs?: number;
}
export declare class DshChangesPeer {
  constructor(options: DshChangesPeerOptions);
  readonly available: boolean;
  call(method: 'harness.changes.read', params: DshChangesReadParams): Promise<DshChangesResult>;
  call(method: string, params: unknown): Promise<DshChangesResult>;
  /** Aborts this peer's reads only, without disposing the Host connection or owner Peer. */
  closeAll(): void;
}
