import http from 'node:http';
import { open, constants } from 'node:fs/promises';
import { createHash, randomUUID } from 'node:crypto';
import { isAbsolute, join, resolve } from 'node:path';
import { StringDecoder } from 'node:string_decoder';
import WebSocket from 'ws';

export const NATIVE_SERVICE_CAPABILITY = 'harness.services.v1';
const CHUNK = 512 * 1024, TOTAL = 64 * 1024 * 1024, TTL = 60_000;
const CONTROL = /[\x00-\x1f\x7f-\x9f]/;
const MESSAGES = {
  INVALID_PARAMS: 'Invalid native service parameters', REGISTRATION_MISSING: 'Registration is missing',
  REGISTRATION_UNREADABLE: 'Registration cannot be read', REGISTRATION_INVALID: 'Registration is invalid',
  REGISTRATION_TOO_LARGE: 'Registration is too large', SERVICE_UNAVAILABLE: 'Native service is unavailable',
  SERVICE_MISMATCH: 'Native service identity does not match registration', STALE_SERVICE: 'Native service registration changed',
  AUTH_FAILED: 'Native service authentication failed', ROUTE_DENIED: 'Native service route is not allowed',
  REDIRECT_DENIED: 'Native service redirects are not allowed', RESPONSE_TOO_LARGE: 'Native service response exceeds limit',
  RESOURCE_LIMIT: 'Native service resource limit reached', NOT_FOUND: 'Native service handle not found',
  PEER_CLOSED: 'Native service connection is closed', STREAM_FAILED: 'Native service stream failed',
  SLOW_CONSUMER: 'Native service stream consumer is too slow', METHOD_NOT_FOUND: 'Native service method not found',
};
export class NativeServiceError extends Error {
  constructor(code) { super(Object.hasOwn(MESSAGES, code) ? MESSAGES[code] : 'Native service operation failed'); this.name = 'NativeServiceError'; this.code = Object.hasOwn(MESSAGES, code) ? code : 'STREAM_FAILED'; }
}
const fail = code => { throw new NativeServiceError(code); };
const object = v => v !== null && typeof v === 'object' && !Array.isArray(v);
const hash = value => createHash('sha256').update(value).digest('hex');
const integer = (n, min, max) => Number.isSafeInteger(n) && n >= min && n <= max;
const handle = s => typeof s === 'string' && /^[A-Za-z0-9_-]{1,128}$/.test(s);
function base64(value, max = CHUNK) {
  if (typeof value !== 'string' || value.length > Math.ceil(max / 3) * 4 ||
      value.length % 4 !== 0 || !/^[A-Za-z0-9+/]*={0,2}$/.test(value)) fail('INVALID_PARAMS');
  const bytes = Buffer.from(value, 'base64');
  if (bytes.length > max || bytes.toString('base64') !== value) fail('INVALID_PARAMS');
  return bytes;
}
// The official registration is a flat JSON object. Reject duplicate keys, null and coercion.
function registrationJSON(text) {
  let at = 0; const result = Object.create(null);
  const ws = () => { while (/\s/.test(text[at] || '') && at < text.length) { if (!/[ \t\r\n]/.test(text[at])) fail('REGISTRATION_INVALID'); at++; } };
  const take = c => { ws(); if (text[at] === c) { at++; return true; } return false; };
  const string = () => { ws(); const m = /^"(?:[^"\\\x00-\x1f]|\\(?:["\\/bfnrt]|u[0-9a-fA-F]{4}))*"/.exec(text.slice(at)); if (!m) fail('REGISTRATION_INVALID'); at += m[0].length; return JSON.parse(m[0]); };
  if (!take('{')) fail('REGISTRATION_INVALID');
  if (!take('}')) {
    do {
      const key = string(); if (Object.hasOwn(result, key) || !take(':')) fail('REGISTRATION_INVALID'); ws();
      if (text[at] === '"') result[key] = string();
      else { const m = /^-?(?:0|[1-9][0-9]*)/.exec(text.slice(at)); if (!m) fail('REGISTRATION_INVALID'); at += m[0].length; result[key] = Number(m[0]); }
    } while (take(','));
    if (!take('}')) fail('REGISTRATION_INVALID');
  }
  ws(); if (at !== text.length) fail('REGISTRATION_INVALID');
  return result;
}
async function registration(path) {
  let file;
  try {
    if (!isAbsolute(path) || CONTROL.test(path)) fail('REGISTRATION_INVALID');
    file = await open(path, constants.O_RDONLY | constants.O_NONBLOCK);
    if (!(await file.stat()).isFile()) fail('REGISTRATION_INVALID');
    const bytes = Buffer.alloc(16 * 1024 + 1);
    let length = 0;
    while (length < bytes.length) { const r = await file.read(bytes, length, bytes.length - length, null); if (!r.bytesRead) break; length += r.bytesRead; }
    if (length > 16 * 1024) fail('REGISTRATION_TOO_LARGE');
    const r = registrationJSON(new TextDecoder('utf-8', { fatal: true }).decode(bytes.subarray(0, length)));
    if (Object.keys(r).some(k => !['url', 'pid', 'id', 'version', 'password'].includes(k)) || !integer(r.pid, 1, 2147483647)) fail('REGISTRATION_INVALID');
    for (const [k, max] of [['id', 512], ['version', 128], ['password', 4096]])
      if (k in r && (typeof r[k] !== 'string' || !r[k].trim() || r[k].length > max || CONTROL.test(r[k]))) fail('REGISTRATION_INVALID');
    if (typeof r.url !== 'string' || CONTROL.test(r.url)) fail('REGISTRATION_INVALID');
    const m = /^http:\/\/(127\.0\.0\.1|localhost|\[::1\]):([1-9][0-9]{0,4})\/?$/.exec(r.url);
    if (!m || !integer(Number(m[2]), 1, 65535)) fail('REGISTRATION_INVALID');
    const generation = hash(JSON.stringify([r.url, r.pid, r.id ?? null, r.version ?? null, r.password ?? null]));
    return { ...r, path, hostname: m[1] === '[::1]' ? '::1' : '127.0.0.1', port: Number(m[2]), generation };
  } catch (error) {
    if (error instanceof NativeServiceError) throw error;
    fail(error?.code === 'ENOENT' ? 'REGISTRATION_MISSING' : error?.code === 'EACCES' ? 'REGISTRATION_UNREADABLE' : 'REGISTRATION_INVALID');
  } finally { await file?.close().catch(() => {}); }
}
function requestHeaders(input = {}) {
  if (!object(input)) fail('INVALID_PARAMS');
  const out = {};
  for (const [key, value] of Object.entries(input)) {
    const lower = key.toLowerCase();
    if (!['accept', 'content-type'].includes(lower) || Object.hasOwn(out, lower) || typeof value !== 'string' || value.length > 4096 || CONTROL.test(value)) fail('INVALID_PARAMS');
    out[lower] = value;
  }
  return out;
}
function responseHeaders(input) {
  const out = {};
  for (const key of ['content-type', 'content-length', 'content-encoding', 'cache-control', 'etag', 'last-modified'])
    if (typeof input[key] === 'string' && !CONTROL.test(input[key]) && input[key].length <= 4096) out[key] = input[key];
  return out;
}
function routePath(path) {
  if (typeof path !== 'string' || path.length > 8192 || !path.startsWith('/') || path.startsWith('//') || /[\\#]/.test(path) || /[^\x21-\x7e]/.test(path) || /%(?:0[0-9a-f]|1[0-9a-f]|7f)/i.test(path)) fail('ROUTE_DENIED');
  const pathname = path.split('?')[0], segments = pathname.slice(1).split('/');
  // Only the existing native session-ID segment may contain encoded separators.
  const sessionIndex = segments.length >= 3 && segments[0] === 'api' && segments[1] === 'session' && segments[2] ? 2 : -1;
  for (const [index, segment] of segments.entries()) {
    let decoded = segment;
    for (;;) {
      if (/[\\?#:]/.test(decoded) || CONTROL.test(decoded) || decoded.split('/').some(s => s === '.' || s === '..') || (decoded.includes('/') && index !== sessionIndex)) fail('ROUTE_DENIED');
      if (!decoded.includes('%')) break;
      let next; try { next = decodeURIComponent(decoded); } catch { fail('ROUTE_DENIED'); }
      if (next === decoded) break; decoded = next;
    }
  }
  return pathname;
}
function routesFrom(document) {
  if (!object(document) || !object(document.paths)) fail('SERVICE_UNAVAILABLE');
  const routes = [];
  for (const [path, methods] of Object.entries(document.paths)) {
    if (routes.length >= 4096 || !object(methods)) fail('SERVICE_UNAVAILABLE');
    routePath(path);
    const parts = path.split('/').map((p, index, all) => p === '*' && index === all.length - 1 ? '.+' : /^\{[A-Za-z0-9_]+\}$/.test(p) ? '[^/]+' : p.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'));
    for (const method of Object.keys(methods)) if (['get', 'post', 'put', 'patch', 'delete', 'head', 'options'].includes(method)) routes.push([method.toUpperCase(), new RegExp(`^${parts.join('/')}$`)]);
  }
  return routes;
}

/** One instance is owned by one already-authenticated Remote connection. */
export class NativeServicePeer {
  #publish; #paths; #now; #closed = false; #services = new Map(); #streams = new Map(); #transfers = new Map(); #incoming = new Map(); #retiring = new Set();
  #controllers = new Set(); #cancelledStreams = new Map(); #bytes = 0; #pendingTransfers = 0; #timer;
  constructor({ publish, registrationPaths, now = Date.now } = {}) {
    if (typeof publish !== 'function' || typeof now !== 'function') fail('INVALID_PARAMS');
    const root = process.env.XDG_STATE_HOME || (process.env.HOME ? join(process.env.HOME, '.local', 'state') : null);
    const paths = registrationPaths ?? (root ? [join(root, 'opencode', 'service.json')] : []);
    if (!Array.isArray(paths) || paths.length > 16 || paths.some(p => typeof p !== 'string' || !isAbsolute(p) || CONTROL.test(p))) fail('INVALID_PARAMS');
    this.#paths = [...new Set(paths.map(p => resolve(p)))]; this.#publish = publish; this.#now = now;
    this.#timer = setInterval(() => this.#expire(), 1000); this.#timer.unref();
  }
  #assertOpen() { if (this.#closed) fail('PEER_CLOSED'); this.#expire(); }
  #controller() { if (this.#closed) fail('PEER_CLOSED'); if (this.#controllers.size >= 36) fail('RESOURCE_LIMIT'); const c = new AbortController(); this.#controllers.add(c); return c; }
  #expire() {
    const now = this.#now();
    for (const [id, at] of this.#cancelledStreams) if (now - at >= TTL) this.#cancelledStreams.delete(id);
    for (const [id, t] of this.#transfers) if (now - t.touched >= TTL) this.#dropTransfer(id);
    for (const [id, t] of this.#incoming) if (now - t.touched >= TTL) this.#dropIncoming(id);
    for (const s of this.#streams.values()) if (now - s.touched >= TTL) this.#finish(s, 'STREAM_FAILED');
  }
  #reserve(n) { if (this.#bytes + n > TOTAL) fail('RESOURCE_LIMIT'); this.#bytes += n; }
  #dropTransfer(id) { const t = this.#transfers.get(id); if (t) { this.#bytes -= t.bytes.length; t.bytes.fill(0); this.#transfers.delete(id); } }
  #dropIncoming(id) { const t = this.#incoming.get(id); if (t) { this.#bytes -= t.bytes.length; t.bytes.fill(0); this.#incoming.delete(id); } }
  #headers(r, input, path) { return { ...input, ...(r.password === undefined || path.split('?')[0] === '/api/info' ? {} : { authorization: `Basic ${Buffer.from(`opencode:${r.password}`).toString('base64')}` }) }; }
  #request(r, method, path, headers, body, controller, authenticate = true, onBodyQueued, onRequestStarted) {
    return new Promise((resolveRequest, reject) => {
      const requestHeaders = authenticate ? this.#headers(r, headers, path) : headers;
      const req = http.request({ hostname: r.hostname, port: r.port, method, path, headers: { ...requestHeaders, ...(body === undefined ? {} : { 'content-length': Buffer.byteLength(body) }) }, signal: controller.signal, agent: false }, res => {
        if (res.statusCode >= 300 && res.statusCode < 400) { res.destroy(); reject(new NativeServiceError('REDIRECT_DENIED')); }
        else resolveRequest(res);
      });
      req.setTimeout(15_000, () => req.destroy(new NativeServiceError('SERVICE_UNAVAILABLE')));
      req.on('error', error => reject(error instanceof NativeServiceError ? error : new NativeServiceError(this.#closed ? 'PEER_CLOSED' : 'SERVICE_UNAVAILABLE')));
      // An early response is not proof the socket released its request buffer.
      if (onBodyQueued) { req.once('finish', onBodyQueued); req.once('close', onBodyQueued); }
      onRequestStarted?.();
      req.end(body);
    });
  }
  async #read(res, max, cacheBudget = false) {
    const chunks = []; let size = 0, held = 0;
    try {
      if (res.headers['content-length'] && (!/^\d+$/.test(res.headers['content-length']) || Number(res.headers['content-length']) > max)) fail('RESPONSE_TOO_LARGE');
      for await (const chunk of res) {
        size += chunk.length; if (size > max) fail('RESPONSE_TOO_LARGE');
        if (cacheBudget) { if (this.#bytes + chunk.length > TOTAL) fail('RESPONSE_TOO_LARGE'); this.#reserve(chunk.length); held += chunk.length; }
        chunks.push(chunk);
      }
      return { bytes: Buffer.concat(chunks, size), held };
    } catch (error) { this.#bytes -= held; res.destroy(); if (error instanceof NativeServiceError) throw error; fail(this.#closed ? 'PEER_CLOSED' : 'SERVICE_UNAVAILABLE'); }
  }
  async #probe(r) {
    const c = this.#controller();
    try {
      // This public identity request must not disclose a stale registration's password.
      const infoResponse = await this.#request(r, 'GET', '/api/info', {}, undefined, c, false);
      const info = await this.#read(infoResponse, CHUNK);
      let parsed; try { parsed = JSON.parse(info.bytes.toString('utf8')); } catch { fail('SERVICE_MISMATCH'); }
      if (infoResponse.statusCode !== 200 || !object(parsed) || parsed.pid !== r.pid || typeof parsed.version !== 'string' || !parsed.version.trim() || (r.version !== undefined && parsed.version !== r.version)) fail('SERVICE_MISMATCH');
      const projects = await this.#request(r, 'GET', '/api/project', {}, undefined, c);
      await this.#read(projects, CHUNK);
      if ([401, 403].includes(projects.statusCode)) fail('AUTH_FAILED');
      if (projects.statusCode !== 200) fail('SERVICE_UNAVAILABLE');
      const schema = await this.#request(r, 'GET', '/openapi.json', {}, undefined, c);
      const data = await this.#read(schema, CHUNK);
      if ([401, 403].includes(schema.statusCode)) fail('AUTH_FAILED');
      if (schema.statusCode !== 200) fail('SERVICE_UNAVAILABLE');
      try { return routesFrom(JSON.parse(data.bytes.toString('utf8'))); } catch (e) { if (e instanceof NativeServiceError) throw e; fail('SERVICE_UNAVAILABLE'); }
    } finally { this.#controllers.delete(c); c.abort(); }
  }
  async describe() {
    this.#assertOpen(); const services = [], issues = [];
    if (!this.#paths.length) issues.push({ code: 'REGISTRATION_MISSING' });
    for (const path of this.#paths) {
      const id = `native-${hash(path)}`; let r;
      try {
        r = await registration(path); this.#assertOpen();
        let routes, available = true;
        try { routes = await this.#probe(r); } catch (error) { available = false; issues.push({ code: error instanceof NativeServiceError ? error.code : 'SERVICE_UNAVAILABLE' }); }
        this.#assertOpen(); this.#services.set(id, { path, generation: r.generation, routes, available });
        this.#retireObsolete(id);
        services.push({ id, name: 'OpenCode', protocol: 'opencode-native', generation: r.generation, available, capabilities: available ? ['http', 'binary', 'sse', 'websocket'] : [] });
      } catch (error) { this.#services.delete(id); this.#retireObsolete(id); if (this.#closed) fail('PEER_CLOSED'); issues.push({ code: error instanceof NativeServiceError ? error.code : 'REGISTRATION_INVALID' }); }
    }
    return { version: 1, completeness: issues.length ? 'partial' : 'complete', services, ...(issues.length ? { issues } : {}) };
  }
  async #service(params, method) {
    if (!object(params) || typeof params.serviceId !== 'string' || typeof params.generation !== 'string') fail('INVALID_PARAMS');
    let service = this.#services.get(params.serviceId);
    if (!service) { await this.describe(); service = this.#services.get(params.serviceId); }
    if (!service) fail('NOT_FOUND');
    let r; try { r = await registration(service.path); } catch { fail('STALE_SERVICE'); }
    this.#assertOpen();
    if (r.generation !== params.generation || service.generation !== params.generation) fail('STALE_SERVICE');
    if (!service.available) fail('SERVICE_UNAVAILABLE');
    const path = routePath(params.path);
    if (!(method === 'GET' && ['/api/info', '/openapi.json', '/api/event'].includes(path)) && !service.routes.some(([m, re]) => m === method && re.test(path))) fail('ROUTE_DENIED');
    return r;
  }
  async call(method, params) {
    this.#assertOpen();
    if (method === 'harness.services.describe') return this.describe();
    if (!object(params)) fail('INVALID_PARAMS');
    switch (method) {
      case 'harness.services.call': return this.#callHTTP(params);
      case 'harness.services.transfer.open': return this.#openUpload(params);
      case 'harness.services.transfer.chunk': return this.#uploadChunk(params);
      case 'harness.services.transfer.commit': return this.#commitUpload(params);
      case 'harness.services.stream.open': return this.#openStream(params);
      case 'harness.services.stream.send': return this.#send(params);
      case 'harness.services.stream.close': {
        if (!handle(params.streamId)) fail('INVALID_PARAMS');
        this.#rememberCancelled(params.streamId);
        const s = this.#streams.get(params.streamId);
        // Cancellation is unconditional, including a pending open or a stale generation.
        if (s) await this.#finish(s);
        return { closed: true };
      }
      case 'harness.services.transfer.read': {
        const t = this.#transfers.get(params.transferId); if (!t) fail('NOT_FOUND');
        await this.#currentTransfer(t); this.#assertOpen();
        if (!integer(params.index, 0, Math.ceil(t.bytes.length / CHUNK) - 1) || params.index !== t.next) fail('INVALID_PARAMS');
        t.next++; t.touched = this.#now();
        return { index: params.index, totalChunks: Math.ceil(t.bytes.length / CHUNK), data: t.bytes.subarray(params.index * CHUNK, (params.index + 1) * CHUNK).toString('base64') };
      }
      case 'harness.services.transfer.close': {
        if (!object(params) || !handle(params.transferId)) fail('INVALID_PARAMS');
        if (this.#incoming.has(params.transferId)) { this.#dropIncoming(params.transferId); return { closed: true }; }
        const t = this.#transfers.get(params.transferId); if (t) await this.#currentTransfer(t);
        this.#dropTransfer(params.transferId); return { closed: true };
      }
      default: fail('METHOD_NOT_FOUND');
    }
  }
  async #currentTransfer(t) { try { if ((await registration(t.path)).generation === t.generation) return; } catch {} this.#dropTransfer(t.id); fail('STALE_SERVICE'); }
  async #currentStream(s) { try { if ((await registration(s.path)).generation === s.generation) return; } catch {} await this.#finish(s, 'STALE_SERVICE'); fail('STALE_SERVICE'); }
  async #callHTTP(p, options = {}) {
    if (typeof p.method !== 'string' || !/^(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)$/.test(p.method) || !integer(p.maxResponseBytes, 1, TOTAL)) fail('INVALID_PARAMS');
    const headers = requestHeaders(p.headers), body = options.bodyOverride === undefined ? (p.body === undefined ? undefined : base64(p.body)) : options.bodyOverride;
    const r = await this.#service(p, p.method); this.#assertOpen();
    if (!options.slotReserved && this.#transfers.size + this.#incoming.size + this.#pendingTransfers >= 4) fail('RESOURCE_LIMIT');
    const c = this.#controller(); if (!options.slotReserved) this.#pendingTransfers++; let held = 0;
    try {
      const res = await this.#request(r, p.method, p.path, headers, body, c, p.path.split('?')[0] !== '/api/info', options.onBodyQueued, options.onRequestStarted);
      const read = await this.#read(res, p.maxResponseBytes, true); held = read.held;
      this.#assertOpen();
      if ((await registration(r.path)).generation !== r.generation) fail('STALE_SERVICE');
      this.#assertOpen();
      const result = { status: res.statusCode, headers: responseHeaders(res.headers) };
      if (read.bytes.length <= CHUNK) return { ...result, body: read.bytes.toString('base64') };
      const id = randomUUID();
      this.#transfers.set(id, { id, serviceId: p.serviceId, path: r.path, generation: r.generation, bytes: read.bytes, next: 0, touched: this.#now() }); held = 0;
      return { ...result, transferId: id, totalBytes: read.bytes.length };
    } catch (e) { if (e instanceof NativeServiceError) throw e; fail(this.#closed ? 'PEER_CLOSED' : 'STALE_SERVICE'); }
    finally { this.#bytes -= held; if (!options.slotReserved) this.#pendingTransfers--; this.#controllers.delete(c); c.abort(); }
  }
  async #openUpload(p) {
    this.#assertOpen();
    if (typeof p.method !== 'string' || !/^(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)$/.test(p.method) ||
        !integer(p.maxResponseBytes, 1, TOTAL) || !Number.isSafeInteger(p.totalBytes) || p.totalBytes < 0) fail('INVALID_PARAMS');
    if (p.totalBytes > TOTAL) fail('RESOURCE_LIMIT');
    const headers = requestHeaders(p.headers), r = await this.#service(p, p.method); this.#assertOpen();
    if (this.#transfers.size + this.#incoming.size + this.#pendingTransfers >= 4) fail('RESOURCE_LIMIT');
    this.#reserve(p.totalBytes);
    let bytes;
    try { bytes = Buffer.alloc(p.totalBytes); }
    catch { this.#bytes -= p.totalBytes; fail('RESOURCE_LIMIT'); }
    const transferId = randomUUID();
    this.#incoming.set(transferId, { id: transferId, serviceId: p.serviceId, generation: r.generation, path: p.path, method: p.method, headers, maxResponseBytes: p.maxResponseBytes, totalBytes: p.totalBytes, bytes, next: 0, touched: this.#now() });
    return { transferId, chunkBytes: CHUNK };
  }
  #uploadChunk(p) {
    this.#assertOpen();
    if (!handle(p.transferId)) fail('INVALID_PARAMS');
    const t = this.#incoming.get(p.transferId); if (!t) fail('NOT_FOUND');
    if (!integer(p.index, 0, Math.ceil(t.totalBytes / CHUNK) - 1) || p.index !== t.next) fail('INVALID_PARAMS');
    const bytes = base64(p.data), expected = Math.min(CHUNK, t.totalBytes - p.index * CHUNK);
    if (bytes.length !== expected) fail('INVALID_PARAMS');
    bytes.copy(t.bytes, p.index * CHUNK); t.next++; t.touched = this.#now();
    return { accepted: true };
  }
  async #commitUpload(p) {
    this.#assertOpen();
    if (!handle(p.transferId)) fail('INVALID_PARAMS');
    const t = this.#incoming.get(p.transferId); if (!t) fail('NOT_FOUND');
    if (t.next !== Math.ceil(t.totalBytes / CHUNK)) fail('INVALID_PARAMS');
    // Consume before validation or I/O: concurrent/retried commits cannot replay a mutation.
    this.#incoming.delete(t.id); this.#pendingTransfers++;
    let released = false, started = false, resolveReleased;
    const bodyReleased = new Promise(resolve => { resolveReleased = resolve; });
    const releaseBodyBudget = () => { if (!released) { this.#bytes -= t.totalBytes; released = true; t.bytes.fill(0); resolveReleased(); } };
    try {
      return await this.#callHTTP({ serviceId: t.serviceId, generation: t.generation, method: t.method, path: t.path, headers: t.headers, maxResponseBytes: t.maxResponseBytes }, {
        bodyOverride: t.bytes, slotReserved: true, onBodyQueued: releaseBodyBudget, onRequestStarted: () => { started = true; },
      });
    } finally {
      if (!started) releaseBodyBudget();
      await bodyReleased; this.#pendingTransfers--;
    }
  }
  #rememberCancelled(id) {
    this.#cancelledStreams.delete(id); this.#cancelledStreams.set(id, this.#now());
    while (this.#cancelledStreams.size > 128) this.#cancelledStreams.delete(this.#cancelledStreams.keys().next().value);
  }
  #retireObsolete(serviceId) {
    const current = this.#services.get(serviceId);
    for (const s of this.#streams.values()) if (s.serviceId === serviceId &&
      (!current?.available || current.generation !== s.generation)) void this.#finish(s, 'STALE_SERVICE');
    for (const [id, t] of this.#transfers) if (t.serviceId === serviceId &&
      (!current?.available || current.generation !== t.generation)) this.#dropTransfer(id);
    for (const [id, t] of this.#incoming) if (t.serviceId === serviceId &&
      (!current?.available || current.generation !== t.generation)) this.#dropIncoming(id);
  }
  async #validIngress(s) {
    if (s.closed || this.#closed) return false;
    const current = this.#services.get(s.serviceId);
    let valid = current?.available && current.generation === s.generation;
    if (valid) { try { valid = (await registration(s.path)).generation === s.generation; } catch { valid = false; } }
    if (!valid) { void this.#finish(s, 'STALE_SERVICE'); return false; }
    return !s.closed && !this.#closed;
  }
  #newStream(p) {
    if (!handle(p.streamId) || this.#streams.has(p.streamId) || [...this.#retiring].some(s => s.id === p.streamId)) fail('INVALID_PARAMS');
    if (this.#cancelledStreams.has(p.streamId)) fail('STREAM_FAILED');
    if (this.#streams.size + this.#retiring.size >= 16) fail('RESOURCE_LIMIT');
    const s = { id: p.streamId, serviceId: p.serviceId, kind: p.kind, path: null, generation: p.generation, seq: 0, touched: this.#now(), pending: 0, frames: 0, chain: Promise.resolve(), closed: false, controller: this.#controller() };
    this.#streams.set(s.id, s); return s;
  }
  #frame(s, type, data = {}, terminal = false) {
    if (s.closed && !terminal) return Promise.resolve();
    const bytes = Buffer.byteLength(JSON.stringify(data)) + 128;
    if (!terminal && (s.pending + bytes > 2 * CHUNK + 1024 || s.frames >= 256)) { this.#finish(s, 'SLOW_CONSUMER'); return Promise.reject(new NativeServiceError('SLOW_CONSUMER')); }
    if (!terminal) { try { this.#reserve(bytes); } catch { this.#finish(s, 'RESOURCE_LIMIT'); return Promise.reject(new NativeServiceError('RESOURCE_LIMIT')); } }
    s.pending += bytes; s.frames++; s.touched = this.#now();
    s.chain = s.chain.then(async () => {
      if (this.#closed || (!terminal && !(await this.#validIngress(s)))) return;
      // Allocate sequence only at publication: discarded stale frames cannot leave a gap before
      // the terminal error. The serialized chain also preserves asynchronous registration checks.
      await this.#publish('harness.services.frame', { streamId: s.id, seq: s.seq++, type, ...data });
    }).finally(() => { s.pending -= bytes; s.frames--; if (!terminal) this.#bytes -= bytes; });
    // A failing transport is connection-local; do not create unhandled rejections from event listeners.
    s.chain.catch(() => { this.#dispose(s); }); return s.chain;
  }
  #dispose(s) {
    this.#streams.delete(s.id); this.#controllers.delete(s.controller); s.closed = true; s.controller.abort(); s.ws?.terminate(); s.response?.destroy();
  }
  #finish(s, error, details = {}) {
    if (s.closed) return s.chain.catch(() => {});
    s.closed = true; this.#streams.delete(s.id); this.#controllers.delete(s.controller); this.#retiring.add(s);
    s.controller.abort(); s.ws?.terminate(); s.response?.destroy();
    if (error) this.#frame(s, 'error', { code: error, message: MESSAGES[error] || MESSAGES.STREAM_FAILED }, true).catch(() => {});
    return this.#frame(s, 'closed', details, true).catch(() => {}).finally(() => this.#retiring.delete(s));
  }
  async #openStream(p) {
    if (!['sse', 'websocket'].includes(p.kind)) fail('INVALID_PARAMS');
    // Metadata endpoints are unary, never SSE/WS. Reject before registration reads or sockets,
    // so a caller cannot use a different carrier to bypass public identity credential policy.
    if (['/api/info', '/openapi.json'].includes(routePath(p.path))) fail('ROUTE_DENIED');
    const headers = requestHeaders(p.headers);
    const s = this.#newStream(p); // Reserve BEFORE registration I/O so close can cancel pending opens.
    try {
      const r = await this.#service(p, 'GET'); this.#assertOpen();
      if (s.closed || s.controller.signal.aborted) fail('STREAM_FAILED');
      s.path = r.path;
      if (p.kind === 'websocket') await this.#websocket(s, p, r, headers);
      else {
        const res = await this.#request(r, 'GET', p.path, { accept: 'text/event-stream', ...headers }, undefined, s.controller);
        if (res.statusCode !== 200 || !/^text\/event-stream(?:;|$)/i.test(res.headers['content-type'] || '')) { res.destroy(); fail('STREAM_FAILED'); }
        s.response = res; res.socket?.setTimeout(0);
        await this.#frame(s, 'open'); this.#sse(s, res).catch(() => this.#finish(s, 'STREAM_FAILED'));
      }
      return { streamId: s.id };
    } catch (error) { await this.#finish(s, error instanceof NativeServiceError ? error.code : 'STREAM_FAILED'); if (error instanceof NativeServiceError) throw error; fail('STREAM_FAILED'); }
  }
  async #sse(s, res) {
    const decoder = new StringDecoder('utf8'); let buffer = '', fields = [], event, id, size = 0;
    const line = async text => {
      if (!text) {
        if (fields.length) await this.#frame(s, 'event', { ...(id === undefined ? {} : { id }), ...(event ? { event } : {}), data: fields.join('\n') });
        fields = []; event = undefined; size = 0; return;
      }
      size += Buffer.byteLength(text) + 1; if (size > CHUNK) fail('RESPONSE_TOO_LARGE');
      if (text.startsWith(':')) return;
      const colon = text.indexOf(':'), key = colon < 0 ? text : text.slice(0, colon);
      let value = colon < 0 ? '' : text.slice(colon + 1); if (value.startsWith(' ')) value = value.slice(1);
      if (key === 'data') fields.push(value); else if (key === 'event') event = value; else if (key === 'id' && !value.includes('\0')) id = value;
    };
    const consume = async (final = false) => {
      while (buffer.length) {
        const at = buffer.search(/[\r\n]/); if (at < 0 || (!final && buffer[at] === '\r' && at === buffer.length - 1)) break;
        const end = at + (buffer[at] === '\r' && buffer[at + 1] === '\n' ? 2 : 1), text = buffer.slice(0, at); buffer = buffer.slice(end); await line(text);
      }
      if (Buffer.byteLength(buffer) + size > CHUNK) fail('RESPONSE_TOO_LARGE');
    };
    try {
      let first = true;
      for await (const chunk of res) {
        if (s.closed) return; s.touched = this.#now(); let text = decoder.write(chunk);
        if (first && text.length) { first = false; if (text[0] === '\ufeff') text = text.slice(1); }
        buffer += text; await consume();
      }
      buffer += decoder.end(); await consume(true); await this.#finish(s);
    } catch (error) { if (!s.closed) await this.#finish(s, error instanceof NativeServiceError ? error.code : 'STREAM_FAILED'); }
  }
  #websocket(s, p, r, headers) {
    return new Promise((resolveOpen, reject) => {
      const host = r.hostname === '::1' ? '[::1]' : r.hostname;
      const ws = new WebSocket(`ws://${host}:${r.port}${p.path}`, { headers: this.#headers(r, headers, p.path), followRedirects: false, maxPayload: CHUNK, handshakeTimeout: 15_000, perMessageDeflate: false });
      s.ws = ws;
      const abort = () => { ws.terminate(); reject(new NativeServiceError(this.#closed ? 'PEER_CLOSED' : 'STREAM_FAILED')); };
      s.controller.signal.addEventListener('abort', abort, { once: true });
      ws.on('open', () => { this.#frame(s, 'open').then(resolveOpen, reject); });
      ws.on('message', (data, binary) => { this.#frame(s, binary ? 'bytes' : 'text', { data: binary ? data.toString('base64') : data.toString('utf8') }).catch(() => {}); });
      ws.on('error', () => { reject(new NativeServiceError('STREAM_FAILED')); this.#finish(s, 'STREAM_FAILED'); });
      ws.on('unexpected-response', (_req, res) => { const code = res.statusCode >= 300 && res.statusCode < 400 ? 'REDIRECT_DENIED' : 'STREAM_FAILED'; res.destroy(); reject(new NativeServiceError(code)); this.#finish(s, code); });
      ws.on('close', (code, reason) => { s.controller.signal.removeEventListener('abort', abort); this.#finish(s, undefined, { code, reason: reason.toString('utf8') }); });
    });
  }
  async #send(p) {
    const s = this.#streams.get(p.streamId); if (!s) fail('NOT_FOUND');
    await this.#currentStream(s); this.#assertOpen();
    if (s.kind !== 'websocket' || s.ws?.readyState !== WebSocket.OPEN) fail('INVALID_PARAMS');
    s.touched = this.#now();
    if (p.type === 'close') {
      const code = p.code ?? 1000, reason = p.reason ?? '';
      if (!integer(code, 1000, 4999) || !([1000, 1001, 1002, 1003, 1007, 1008, 1009, 1010, 1011, 1012, 1013, 1014].includes(code) || code >= 3000) || typeof reason !== 'string' || Buffer.byteLength(reason) > 123 || CONTROL.test(reason)) fail('INVALID_PARAMS');
      await this.#frame(s, 'closing', { code }); s.ws.close(code, reason);
    } else {
      const data = p.type === 'bytes' ? base64(p.data) : p.type === 'text' && typeof p.data === 'string' && Buffer.byteLength(p.data) <= CHUNK ? p.data : fail('INVALID_PARAMS');
      if (s.ws.bufferedAmount + Buffer.byteLength(data) > CHUNK) { await this.#finish(s, 'SLOW_CONSUMER'); fail('SLOW_CONSUMER'); }
      await new Promise((resolveSend, reject) => s.ws.send(data, { binary: p.type === 'bytes' }, error => error ? reject(new NativeServiceError('STREAM_FAILED')) : resolveSend()));
    }
    return { accepted: true };
  }
  closeAll() {
    if (this.#closed) return;
    this.#closed = true; clearInterval(this.#timer);
    for (const s of this.#streams.values()) this.#dispose(s); this.#retiring.clear();
    for (const c of this.#controllers) c.abort(); this.#controllers.clear();
    for (const id of this.#transfers.keys()) this.#dropTransfer(id);
    for (const id of this.#incoming.keys()) this.#dropIncoming(id);
    this.#services.clear(); this.#cancelledStreams.clear();
  }
}
