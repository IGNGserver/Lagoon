/** A failed hub request; [message] is already written for the user. */
export class ApiError extends Error {
  constructor(readonly status: number, readonly code: string, message: string) {
    super(message);
  }
}

type Listener = () => void;
const unauthorizedListeners = new Set<Listener>();

/** Called whenever the hub answers 401: the session cookie expired or was revoked elsewhere. */
export function onUnauthorized(listener: Listener): () => void {
  unauthorizedListeners.add(listener);
  return () => { unauthorizedListeners.delete(listener); };
}

export async function api<T = unknown>(method: string, path: string, body?: unknown, init?: { signal?: AbortSignal }): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`/api/v1${path}`, {
      method,
      credentials: "same-origin",
      headers: body === undefined ? undefined : { "Content-Type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: init?.signal,
    });
  } catch (error) {
    if ((error as Error).name === "AbortError") throw error;
    throw new ApiError(0, "network", "无法连接到中枢，请检查网络");
  }
  const text = await response.text();
  let json: unknown = null;
  if (text) {
    try {
      json = JSON.parse(text);
    } catch {
      json = null;
    }
  }
  if (!response.ok) {
    const error = (json as { error?: { code?: string; message?: string } } | null)?.error;
    if (response.status === 401 && path !== "/auth/login" && path !== "/auth/setup") unauthorizedListeners.forEach((listener) => listener());
    throw new ApiError(response.status, error?.code ?? "http", error?.message ?? `请求失败（HTTP ${response.status}）`);
  }
  return json as T;
}

export const get = <T>(path: string, init?: { signal?: AbortSignal }) => api<T>("GET", path, undefined, init);
export const post = <T = { ok: boolean }>(path: string, body: unknown = {}) => api<T>("POST", path, body);
export const put = <T = { ok: boolean }>(path: string, body: unknown = {}) => api<T>("PUT", path, body);
export const del = <T = { ok: boolean }>(path: string, body?: unknown) => api<T>("DELETE", path, body);

export const enc = encodeURIComponent;

/** `/servers/{id}` prefix for per-server routes. */
export const serverPath = (serverId: string) => `/servers/${enc(serverId)}`;
export const sessionPath = (serverId: string, sessionId: string) => `${serverPath(serverId)}/sessions/${enc(sessionId)}`;

export function errorText(error: unknown, fallback = "操作失败"): string {
  if (error instanceof ApiError) return error.message;
  if (error instanceof Error && /[一-鿿]/.test(error.message)) return error.message;
  return fallback;
}
