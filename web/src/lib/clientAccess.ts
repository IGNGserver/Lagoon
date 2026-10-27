import type { ClientKey } from "@/api/types";

/**
 * The one string a client pastes to import this hub's servers: the hub address with the access key in
 * the fragment, which browsers never send. Must match `HubImportLink.build` in shared/…/HubExport.kt.
 */
export function importLink(origin: string, secret: string): string {
  return `${origin.replace(/\/+$/, "")}/#key=${secret}`;
}

/** Opening an import link in a browser lands on the hub; drop the key from the address bar and history. */
export function stripKeyFragment(location: Pick<Location, "hash" | "pathname" | "search">, history: Pick<History, "replaceState" | "state">): boolean {
  if (!/(^#|&)key=lgk1\./.test(location.hash)) return false;
  history.replaceState(history.state, "", location.pathname + location.search);
  return true;
}

const pad = (value: number) => String(value).padStart(2, "0");

function when(time: number, now: number): string {
  const date = new Date(time);
  const today = new Date(now);
  const clock = `${pad(date.getHours())}:${pad(date.getMinutes())}`;
  if (date.toDateString() === today.toDateString()) return `今天 ${clock}`;
  const day = `${date.getMonth() + 1}月${date.getDate()}日`;
  return date.getFullYear() === today.getFullYear() ? `${day} ${clock}` : `${date.getFullYear()}年${day}`;
}

/** The second line of a key in the list: when it was created and when and from where it was last used. */
export function keySummary(key: ClientKey, now = Date.now()): string {
  const created = `创建于 ${when(key.created, now)}`;
  if (key.lastUsed <= 0) return `${created} · 从未使用`;
  return `${created} · 最近导入 ${when(key.lastUsed, now)}${key.lastAddress ? ` · 来自 ${key.lastAddress}` : ""}`;
}
