import { del, get, post } from "@/api/http";
import { readLocal, writeLocal } from "@/lib/local";

/** iPhone / iPad (iPadOS reports a Mac with touch). */
export const isIos = () => /iPhone|iPad|iPod/.test(navigator.userAgent) || (navigator.userAgent.includes("Macintosh") && navigator.maxTouchPoints > 1);
export const isStandalone = () => window.matchMedia("(display-mode: standalone)").matches || (navigator as Navigator & { standalone?: boolean }).standalone === true;

export type PushSupport = "ok" | "insecure" | "needs-install" | "unsupported";

/** Web Push needs a secure context, a service worker, and on iOS an app added to the home screen. */
export function pushSupport(): PushSupport {
  if (!window.isSecureContext) return "insecure";
  if (isIos() && !isStandalone()) return "needs-install";
  if (!("serviceWorker" in navigator) || !("PushManager" in window) || !("Notification" in window)) return "unsupported";
  return "ok";
}

export function deviceLabel(): string {
  const ua = navigator.userAgent;
  const device = /iPhone/.test(ua) ? "iPhone" : /iPad/.test(ua) || (ua.includes("Macintosh") && navigator.maxTouchPoints > 1) ? "iPad"
    : /Android/.test(ua) ? "Android" : /Mac/.test(ua) ? "Mac" : /Windows/.test(ua) ? "Windows" : /Linux/.test(ua) ? "Linux" : "设备";
  const browser = /Edg\//.test(ua) ? "Edge" : /Firefox\//.test(ua) ? "Firefox" : /Chrome\//.test(ua) ? "Chrome" : /Safari\//.test(ua) ? "Safari" : "浏览器";
  return `${device} · ${browser}${isStandalone() ? "（应用）" : ""}`;
}

function keyBytes(base64url: string): Uint8Array<ArrayBuffer> {
  const padded = base64url.replace(/-/g, "+").replace(/_/g, "/") + "=".repeat((4 - (base64url.length % 4)) % 4);
  const raw = atob(padded);
  const bytes = new Uint8Array(new ArrayBuffer(raw.length));
  for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);
  return bytes;
}

export async function currentSubscription(): Promise<PushSubscription | null> {
  if (pushSupport() !== "ok") return null;
  const registration = await navigator.serviceWorker.getRegistration();
  return (await registration?.pushManager.getSubscription()) ?? null;
}

/** Asks for notification permission (must follow a tap on iOS) and registers this device with the hub. */
export async function enablePush(): Promise<void> {
  const permission = await Notification.requestPermission();
  if (permission !== "granted") throw new Error(permission === "denied" ? "通知权限被拒绝，请在系统或浏览器设置中允许" : "没有获得通知权限");
  const { enabled, publicKey } = await get<{ enabled: boolean; publicKey: string | null }>("/push/key");
  if (!enabled || !publicKey) throw new Error("中枢未启用推送");
  const registration = await navigator.serviceWorker.ready;
  let subscription = await registration.pushManager.getSubscription();
  if (subscription) {
    // A subscription made for another key (hub data reset) cannot receive this hub's messages.
    const current = subscription.options.applicationServerKey;
    const expected = keyBytes(publicKey);
    const same = current && new Uint8Array(current).every((byte, i) => byte === expected[i]);
    if (!same) { await subscription.unsubscribe(); subscription = null; }
  }
  subscription ??= await registration.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: keyBytes(publicKey) });
  await post("/push/subscriptions", { subscription: subscription.toJSON(), label: deviceLabel() });
}

export async function disablePush(): Promise<void> {
  const subscription = await currentSubscription();
  if (!subscription) return;
  await del("/push/subscriptions", { endpoint: subscription.endpoint }).catch(() => undefined);
  await subscription.unsubscribe();
}

export async function testPush(): Promise<void> {
  const subscription = await currentSubscription();
  await post("/push/test", { endpoint: subscription?.endpoint ?? "" });
}

const PUSH_SERVERS_KEY = "pushServers";

/** Per-device server notification preferences. Absent means enabled by default. */
export function getNotifiedServers(): Record<string, boolean> {
  return readLocal<Record<string, boolean>>(PUSH_SERVERS_KEY, {});
}

export function isServerNotified(serverId: string): boolean {
  const map = getNotifiedServers();
  return map[serverId] !== false;
}

export function setServerNotified(serverId: string, enabled: boolean): void {
  const current = getNotifiedServers();
  const next = { ...current, [serverId]: enabled };
  writeLocal(PUSH_SERVERS_KEY, next);
  syncPushServersToWorker(next);
}

export function syncPushServersToWorker(servers = getNotifiedServers()): void {
  if (!("serviceWorker" in navigator)) return;
  const send = (worker: ServiceWorker | null) => worker?.postMessage({ type: "set-push-servers", servers });
  send(navigator.serviceWorker.controller);
  void navigator.serviceWorker.ready.then((reg) => send(reg.active));
}

/** Chrome's install prompt, captured when the browser offers it. */
let installEvent: (Event & { prompt: () => Promise<void> }) | null = null;
const installListeners = new Set<() => void>();
window.addEventListener("beforeinstallprompt", (event) => {
  event.preventDefault();
  installEvent = event as Event & { prompt: () => Promise<void> };
  installListeners.forEach((listener) => listener());
});
window.addEventListener("appinstalled", () => { installEvent = null; installListeners.forEach((listener) => listener()); });

export const canInstall = () => installEvent != null;
export const onInstallChange = (listener: () => void) => { installListeners.add(listener); return () => { installListeners.delete(listener); }; };
export async function install(): Promise<void> {
  await installEvent?.prompt();
  installEvent = null;
  installListeners.forEach((listener) => listener());
}

/** Shows the number of things that need the user on the app icon, where the platform supports it. */
export function setBadge(count: number): void {
  const nav = navigator as Navigator & { setAppBadge?: (n: number) => Promise<void>; clearAppBadge?: () => Promise<void> };
  if (count > 0) void nav.setAppBadge?.(count).catch(() => undefined);
  else void nav.clearAppBadge?.().catch(() => undefined);
}

let waitingWorker: ServiceWorker | null = null;
let updateRequested = false;
const updateListeners = new Set<() => void>();
export const updateReady = () => waitingWorker != null;
export const onUpdateReady = (listener: () => void) => { updateListeners.add(listener); return () => { updateListeners.delete(listener); }; };
export function applyUpdate(): void {
  updateRequested = true;
  waitingWorker?.postMessage({ type: "skip-waiting" });
}

/** Registers the service worker; a new release waits until the user chooses to reload. */
export function registerServiceWorker(): void {
  if (!("serviceWorker" in navigator) || !window.isSecureContext || import.meta.env.DEV) return;
  const hadController = Boolean(navigator.serviceWorker.controller);
  let reloading = false;
  navigator.serviceWorker.addEventListener("controllerchange", () => {
    if (!hadController || !updateRequested || reloading) return;
    reloading = true;
    window.location.reload();
  });
  void navigator.serviceWorker.register("/sw.js", { scope: "/" }).then((registration) => {
    syncPushServersToWorker();
    const track = (worker: ServiceWorker | null) => {
      if (!worker) return;
      worker.addEventListener("statechange", () => {
        if (worker.state === "installed" && navigator.serviceWorker.controller) {
          waitingWorker = worker;
          updateListeners.forEach((listener) => listener());
        }
      });
    };
    if (registration.waiting && navigator.serviceWorker.controller) { waitingWorker = registration.waiting; updateListeners.forEach((listener) => listener()); }
    track(registration.installing);
    registration.addEventListener("updatefound", () => track(registration.installing));
    // Check for a new release when the app comes back to the foreground.
    document.addEventListener("visibilitychange", () => { if (document.visibilityState === "visible") void registration.update().catch(() => undefined); });
  }).catch(() => undefined);
  // Notification actions handled in the worker ask the page to navigate.
  navigator.serviceWorker.addEventListener("message", (event) => {
    const data = event.data as { type?: string; url?: string } | null;
    if (data?.type === "navigate" && data.url) {
      window.history.pushState({ depth: 1 }, "", data.url);
      window.dispatchEvent(new PopStateEvent("popstate"));
    }
  });
}
