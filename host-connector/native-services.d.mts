export declare const NATIVE_SERVICE_CAPABILITY: 'harness.services.v1';
/** Safe, static message and code; contains no registration paths, credentials or upstream errors. */
export declare class NativeServiceError extends Error {
  readonly code: string;
  constructor(code: string);
}
export interface NativeServiceDescription {
  version: 1;
  completeness: 'complete' | 'partial';
  services: Array<{
    id: string;
    name: string;
    protocol: 'opencode-native';
    generation: string;
    available: boolean;
    capabilities: Array<'http' | 'binary' | 'sse' | 'websocket'>;
  }>;
  issues?: Array<{ code: string }>;
}
export interface NativeServicePeerOptions {
  publish: (event: string, data: unknown) => Promise<void>;
  /** Trusted Host configuration only. Never supplied by a Remote caller. */
  registrationPaths?: string[];
  now?: () => number;
}
export interface NativeServiceHttpParams {
  serviceId: string;
  generation: string;
  method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE' | 'HEAD' | 'OPTIONS';
  path: string;
  headers?: Record<string, string>;
  maxResponseBytes: number;
}
export type NativeServiceHttpResult = { status: number; headers: Record<string, string> } & (
  { body: string; transferId?: never; totalBytes?: never } |
  { body?: never; transferId: string; totalBytes: number }
);
export interface NativeServiceUploadParams extends NativeServiceHttpParams {
  /** Decoded request bytes, reserved against the shared 64 MiB peer budget. */
  totalBytes: number;
}
/** Create one peer per authenticated connection; never share its handles across connections. */
export declare class NativeServicePeer {
  constructor(options: NativeServicePeerOptions);
  describe(): Promise<NativeServiceDescription>;
  call(method: 'harness.services.transfer.open', params: NativeServiceUploadParams): Promise<{ transferId: string; chunkBytes: 524288 }>;
  /** Chunks are canonical standard base64, ordered, and exactly 512 KiB except the final chunk. */
  call(method: 'harness.services.transfer.chunk', params: { transferId: string; index: number; data: string }): Promise<{ accepted: true }>;
  /** A completed upload is consumed before execution; retries on its id return NOT_FOUND. */
  call(method: 'harness.services.transfer.commit', params: { transferId: string }): Promise<NativeServiceHttpResult>;
  call(method: 'harness.services.transfer.close', params: { transferId: string }): Promise<{ closed: true }>;
  call(method: string, params: unknown): Promise<unknown>;
  /** Cancels only this peer's requests and sockets, not the external native service process. */
  closeAll(): void;
}
