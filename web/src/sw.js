/* Lagoon service worker: offline app shell, Web Push and notification actions. Build: __BUILD__ */
const CACHE = "lagoon-__BUILD__";
const SHELL = ["/", "/manifest.webmanifest", "/icons/icon-192.png", "/icons/favicon.svg"];

self.addEventListener("install", (event) => {
  event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(SHELL)).catch(() => undefined));
});

self.addEventListener("activate", (event) => {
  event.waitUntil((async () => {
    const names = await caches.keys();
    await Promise.all(names.filter((name) => name.startsWith("lagoon-") && name !== CACHE).map((name) => caches.delete(name)));
    await self.clients.claim();
  })());
});

self.addEventListener("message", (event) => {
  if (event.data && event.data.type === "skip-waiting") self.skipWaiting();
});

self.addEventListener("fetch", (event) => {
  const request = event.request;
  if (request.method !== "GET") return;
  const url = new URL(request.url);
  if (url.origin !== self.location.origin || url.pathname.startsWith("/api/") || url.pathname === "/healthz") return;
  // App navigations: the network first (new releases), the cached shell when offline.
  if (request.mode === "navigate") {
    event.respondWith(fetch(request).then((response) => {
      if (response.ok) { const copy = response.clone(); void caches.open(CACHE).then((cache) => cache.put("/", copy)); }
      return response;
    }).catch(async () => (await caches.match("/")) || Response.error()));
    return;
  }
  // Hashed assets never change: cache first.
  if (url.pathname.startsWith("/assets/") || url.pathname.startsWith("/fonts/") || url.pathname.startsWith("/icons/")) {
    event.respondWith(caches.match(request).then((hit) => hit || fetch(request).then((response) => {
      if (response.ok) { const copy = response.clone(); void caches.open(CACHE).then((cache) => cache.put(request, copy)); }
      return response;
    })));
  }
});

self.addEventListener("push", (event) => {
  let data = {};
  try { data = event.data ? event.data.json() : {}; } catch { data = { title: "Lagoon", body: event.data ? event.data.text() : "" }; }
  const actions = data.kind === "permission" && data.requestId
    ? [{ action: "approve", title: "允许一次" }, { action: "reject", title: "拒绝" }]
    : [];
  event.waitUntil((async () => {
    if (typeof data.badge === "number" && self.navigator.setAppBadge) {
      try { if (data.badge > 0) await self.navigator.setAppBadge(data.badge); else await self.navigator.clearAppBadge(); } catch { /* unsupported */ }
    }
    await self.registration.showNotification(data.title || "Lagoon", {
      body: data.body || "",
      tag: data.tag || undefined,
      renotify: Boolean(data.tag),
      icon: "/icons/icon-192.png",
      badge: "/icons/badge-96.png",
      data,
      actions,
      requireInteraction: data.kind === "permission" || data.kind === "question",
    });
  })());
});

async function openUrl(url) {
  const windows = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
  for (const client of windows) {
    if (new URL(client.url).origin === self.location.origin) {
      await client.focus();
      client.postMessage({ type: "navigate", url });
      return;
    }
  }
  await self.clients.openWindow(url);
}

self.addEventListener("notificationclick", (event) => {
  const data = event.notification.data || {};
  event.notification.close();
  if ((event.action === "approve" || event.action === "reject") && data.serverId && data.requestId) {
    event.waitUntil(fetch(`/api/v1/servers/${encodeURIComponent(data.serverId)}/permissions/${encodeURIComponent(data.requestId)}`, {
      method: "POST",
      credentials: "same-origin",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ reply: event.action === "approve" ? "once" : "reject" }),
    }).then((response) => {
      // Signed out, or the request changed: open the conversation instead of failing silently.
      if (!response.ok) return openUrl(data.url || "/");
      return undefined;
    }).catch(() => openUrl(data.url || "/")));
    return;
  }
  event.waitUntil(openUrl(data.url || "/"));
});
