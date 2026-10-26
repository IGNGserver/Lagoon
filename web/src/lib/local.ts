/**
 * Per-device conveniences in localStorage (theme, home filter, drafts, folded sections, the composer's
 * agent/model choice). Everything that must be shared or survive a cleared browser lives on the hub.
 * Storage can be unavailable (private mode, blocked site data), so every access is guarded.
 */
const PREFIX = "lagoon:";

export function readLocal<T>(key: string, fallback: T): T {
  try {
    const raw = window.localStorage.getItem(PREFIX + key);
    return raw == null ? fallback : (JSON.parse(raw) as T);
  } catch {
    return fallback;
  }
}

export function writeLocal(key: string, value: unknown): void {
  try {
    if (value === undefined || value === null || value === "") window.localStorage.removeItem(PREFIX + key);
    else window.localStorage.setItem(PREFIX + key, JSON.stringify(value));
  } catch {
    // Not persisted; the page still works for this visit.
  }
}

export const draftKey = (serverId: string, sessionId: string | null) => `draft:${serverId}:${sessionId ?? "new"}`;
export const choiceKey = (serverId: string, sessionId: string | null) => `choice:${serverId}:${sessionId ?? "new"}`;
