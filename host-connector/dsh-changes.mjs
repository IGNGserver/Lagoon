export const DSH_CHANGES_CAPABILITY = 'harness.changes.v1';
const MAX_BYTES = 2 * 1024 * 1024;
const CONTROL = /[\x00-\x1f\x7f-\x9f]/;
const MESSAGES = Object.freeze({
  INVALID_PARAMS: 'Invalid changes parameters.',
  METHOD_NOT_FOUND: 'The changes method does not exist.',
  FEATURE_NOT_SUPPORTED: 'Changes are unavailable on this Host.',
  NOT_AUTHORIZED: 'Changes access is not authorized.',
  RESOURCE_UNAVAILABLE: 'Changes are temporarily unavailable.',
  INTERNAL: 'The Host could not read changes.',
  REDIRECT_DENIED: 'Changes redirects are not allowed.',
  RESPONSE_TOO_LARGE: 'Changes response exceeds the limit.',
  RESOURCE_LIMIT: 'Too many changes requests are pending.',
  TIMEOUT: 'The changes request timed out.',
  PEER_CLOSED: 'The changes connection is closed.',
});
/** Only static codes/messages cross the Remote boundary; never retain upstream errors. */
export class DshChangesError extends Error {
  constructor(code) {
    const safe = Object.hasOwn(MESSAGES, code) ? code : 'INTERNAL';
    super(MESSAGES[safe]); this.name = 'DshChangesError'; this.code = safe;
  }
}
const fail = code => { throw new DshChangesError(code); };
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
function parameters(value) {
  if (!object(value) || !['summary', 'diff'].includes(value.kind) ||
      typeof value.sessionId !== 'string' || value.sessionId.length < 1 || value.sessionId.length > 512 || CONTROL.test(value.sessionId) ||
      !Number.isSafeInteger(value.seq) || value.seq <= 0) fail('INVALID_PARAMS');
  const keys = value.kind === 'diff' ? ['kind', 'sessionId', 'seq', 'index'] : ['kind', 'sessionId', 'seq'];
  if (Reflect.ownKeys(value).length !== keys.length || keys.some(key => !Object.hasOwn(value, key))) fail('INVALID_PARAMS');
  if (value.kind === 'diff' && (!Number.isSafeInteger(value.index) || value.index < 0 || value.index > 4999)) fail('INVALID_PARAMS');
  return value;
}

/** One instance belongs to one authenticated Remote connection and its Host-created owner Peer.
 * The dummy URL is parsed ONLY by Connection's internal dispatcher; there is no network fallback. */
export class DshChangesPeer {
  #readConnection; #peer; #timeout; #closed = false; #pending = new Set();
  constructor({ readConnection, peer, timeoutMs = 10_000 } = {}) {
    if (typeof readConnection !== 'function' || !Number.isSafeInteger(timeoutMs) || timeoutMs < 1 || timeoutMs > 10_000) fail('INVALID_PARAMS');
    this.#readConnection = readConnection; this.#peer = peer; this.#timeout = timeoutMs;
  }
  #connection() {
    if (this.#closed || this.#peer === undefined || this.#peer?.principal?.kind !== 'owner' || this.#peer?.signal?.aborted) return undefined;
    try {
      const connection = this.#readConnection();
      return typeof connection?.createSharedFetchHandler === 'function' ? connection : undefined;
    } catch { return undefined; }
  }
  /** Resolve the live service without constructing/probing a fetch handler or performing I/O. */
  get available() { return this.#connection() !== undefined; }
  async call(method, params) {
    if (method !== 'harness.changes.read') fail('METHOD_NOT_FOUND');
    const p = parameters(params);
    if (this.#closed || this.#peer?.signal?.aborted) fail('PEER_CLOSED');
    const connection = this.#connection();
    if (connection === undefined) fail('FEATURE_NOT_SUPPORTED');
    if (this.#pending.size >= 4) fail('RESOURCE_LIMIT');
    const controller = new AbortController(); this.#pending.add(controller);
    const cancel = () => controller.abort(new DshChangesError('PEER_CLOSED'));
    const peerSignal = this.#peer?.signal;
    let reader, timer, rejectAbort;
    const aborted = new Promise((_resolve, reject) => { rejectAbort = reject; });
    const onAbort = () => rejectAbort(controller.signal.reason instanceof DshChangesError ? controller.signal.reason : new DshChangesError('PEER_CLOSED'));
    controller.signal.addEventListener('abort', onAbort, { once: true });
    peerSignal?.addEventListener('abort', cancel, { once: true });
    timer = setTimeout(() => controller.abort(new DshChangesError('TIMEOUT')), this.#timeout);
    if (peerSignal?.aborted) cancel();
    const work = async () => {
      controller.signal.throwIfAborted();
      const url = new URL(`http://127.0.0.1/api/changes.${p.kind}`);
      url.searchParams.set('sessionId', p.sessionId); url.searchParams.set('seq', String(p.seq));
      if (p.kind === 'diff') url.searchParams.set('index', String(p.index));
      const handler = connection.createSharedFetchHandler('/api');
      if (typeof handler?.fetch !== 'function') fail('FEATURE_NOT_SUPPORTED');
      const response = await handler.fetch(new Request(url, { method: 'GET', redirect: 'error', signal: controller.signal }), this.#peer);
      if (!(response instanceof Response)) fail('INTERNAL');
      if (controller.signal.aborted) {
        void response.body?.cancel().catch(() => {});
        controller.signal.throwIfAborted();
      }
      reader = response.body?.getReader();
      if (response.redirected || response.status >= 300 && response.status < 400) fail('REDIRECT_DENIED');
      if (response.status === 404) return { status: 404 };
      if ([401, 403].includes(response.status)) fail('NOT_AUTHORIZED');
      if (response.status === 409 || response.status === 503) fail('RESOURCE_UNAVAILABLE');
      if (response.status !== 200) fail('INTERNAL');
      if (!/^application\/json(?:\s*;|\s*$)/i.test(response.headers.get('content-type') ?? '')) fail('INTERNAL');
      const declared = response.headers.get('content-length');
      if (declared !== null && (!/^\d+$/.test(declared) || Number(declared) > MAX_BYTES)) fail('RESPONSE_TOO_LARGE');
      const chunks = []; let size = 0;
      while (reader !== undefined) {
        const { value, done } = await reader.read(); controller.signal.throwIfAborted();
        if (done) break;
        if (!(value instanceof Uint8Array)) fail('INTERNAL');
        size += value.byteLength; if (size > MAX_BYTES) fail('RESPONSE_TOO_LARGE');
        chunks.push(value);
      }
      const bytes = new Uint8Array(size); let offset = 0;
      for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
      const value = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes));
      if (!object(value)) fail('INTERNAL');
      return { status: 200, value };
    };
    try { return await Promise.race([work(), aborted]); }
    catch (error) { if (error instanceof DshChangesError) throw error; fail('INTERNAL'); }
    finally {
      clearTimeout(timer); peerSignal?.removeEventListener('abort', cancel);
      controller.signal.removeEventListener('abort', onAbort); this.#pending.delete(controller);
      controller.abort();
      // Never wait for an uncooperative provider's cancellation to finish.
      if (reader) void reader.cancel().catch(() => {});
    }
  }
  /** Cancel this connection's in-flight reads only; never dispose the owner Peer or Host service. */
  closeAll() {
    this.#closed = true;
    for (const controller of this.#pending) controller.abort(new DshChangesError('PEER_CLOSED'));
  }
}
