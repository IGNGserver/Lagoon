import { useSyncExternalStore } from "react";

export type Route =
  | { name: "home" }
  | { name: "chat"; serverId: string; sessionId: string }
  | { name: "new"; serverId?: string; projectId?: string }
  | { name: "archived" }
  | { name: "settings" }
  | { name: "server"; id: string | null }
  | { name: "missing" };

export function parseRoute(pathname: string, search = ""): Route {
  const parts = pathname.split("/").filter(Boolean).map(decodeURIComponent);
  const query = new URLSearchParams(search);
  if (parts.length === 0) return { name: "home" };
  if (parts[0] === "s" && parts.length === 3) return { name: "chat", serverId: parts[1], sessionId: parts[2] };
  if (parts[0] === "new" && parts.length === 1) {
    return { name: "new", serverId: query.get("server") ?? undefined, projectId: query.get("project") ?? undefined };
  }
  if (parts[0] === "archived" && parts.length === 1) return { name: "archived" };
  if (parts[0] === "settings" && parts.length === 1) return { name: "settings" };
  if (parts[0] === "settings" && parts[1] === "servers" && parts.length === 3) return { name: "server", id: parts[2] === "new" ? null : parts[2] };
  return { name: "missing" };
}

export const chatPath = (serverId: string, sessionId: string) => `/s/${encodeURIComponent(serverId)}/${encodeURIComponent(sessionId)}`;
export const serverEditPath = (id: string | null) => `/settings/servers/${id ? encodeURIComponent(id) : "new"}`;

const listeners = new Set<() => void>();
const notify = () => listeners.forEach((listener) => listener());
window.addEventListener("popstate", notify);

/** In-app history depth, so 返回 never leaves the app when it was opened on a deep link. */
function depth(): number {
  return (window.history.state as { depth?: number } | null)?.depth ?? 0;
}

export function navigate(path: string, options: { replace?: boolean } = {}): void {
  const current = window.location.pathname + window.location.search;
  if (path === current) return;
  if (options.replace) window.history.replaceState({ depth: depth() }, "", path);
  else window.history.pushState({ depth: depth() + 1 }, "", path);
  notify();
}

export function back(fallback = "/"): void {
  if (depth() > 0) window.history.back();
  else navigate(fallback, { replace: true });
}

let snapshot = { path: "", route: parseRoute("/") as Route };
function read() {
  const path = window.location.pathname + window.location.search;
  if (snapshot.path !== path) snapshot = { path, route: parseRoute(window.location.pathname, window.location.search) };
  return snapshot.route;
}

export function useRoute(): Route {
  return useSyncExternalStore((listener) => {
    listeners.add(listener);
    return () => listeners.delete(listener);
  }, read);
}
