import type { ConversationOps, ConversationReset, HubState, ServerView } from "./types";
import { get, put } from "./http";
import { useStore } from "@/store";
import type { AuthStatus } from "./types";

/**
 * The tab's single event stream to the hub. It carries the full state on connect, every server change
 * after it, and the conversations this tab has open. iOS drops it in the background: when the page
 * comes back the stream is rebuilt and the hub resends everything, so nothing needs replaying.
 */
class LiveConnection {
  private source: EventSource | null = null;
  private streamId: string | null = null;
  private watched: { serverId: string; sessionId: string }[] = [];
  private failures = 0;
  private hiddenAt = 0;

  start() {
    if (this.source) return;
    useStore.getState().setLive(this.failures > 0 ? "reconnecting" : "connecting");
    const source = new EventSource("/api/v1/events");
    this.source = source;
    source.addEventListener("hello", (event) => {
      this.streamId = (JSON.parse((event as MessageEvent).data) as { streamId: string }).streamId;
      this.failures = 0;
      useStore.getState().setLive("open");
      void this.sendWatch();
    });
    source.addEventListener("state", (event) => useStore.getState().applyState(JSON.parse((event as MessageEvent).data) as HubState));
    source.addEventListener("server", (event) => useStore.getState().upsertServer(JSON.parse((event as MessageEvent).data) as ServerView));
    source.addEventListener("server.removed", (event) => useStore.getState().removeServer((JSON.parse((event as MessageEvent).data) as { serverId: string }).serverId));
    source.addEventListener("conv.reset", (event) => useStore.getState().resetConversation(JSON.parse((event as MessageEvent).data) as ConversationReset));
    source.addEventListener("conv.ops", (event) => useStore.getState().applyConversationOps(JSON.parse((event as MessageEvent).data) as ConversationOps));
    source.onerror = () => {
      this.streamId = null;
      this.failures++;
      useStore.getState().setLive("reconnecting");
      // EventSource retries on its own; a closed stream (HTTP error such as 401) needs a decision.
      if (source.readyState === EventSource.CLOSED || this.failures % 3 === 0) void this.checkAuth();
    };
  }

  stop() {
    this.source?.close();
    this.source = null;
    this.streamId = null;
  }

  restart() {
    this.stop();
    this.start();
  }

  /** Replaces the conversations this tab has open; the hub sends each one's window. */
  watch(conversations: { serverId: string; sessionId: string }[]) {
    const same = conversations.length === this.watched.length && conversations.every((it, i) => it.serverId === this.watched[i]?.serverId && it.sessionId === this.watched[i]?.sessionId);
    this.watched = conversations;
    if (!same) void this.sendWatch();
  }

  private async sendWatch() {
    const id = this.streamId;
    if (!id) return;
    try {
      await put(`/streams/${encodeURIComponent(id)}/watch`, { conversations: this.watched, visible: document.visibilityState === "visible" });
    } catch {
      // The stream reconnects and sends the watch again with its new id.
    }
  }

  private async checkAuth() {
    try {
      const status = await get<AuthStatus>("/auth/status");
      if (!status.authenticated) {
        this.stop();
        useStore.getState().setAuth(status);
      } else if (this.source?.readyState === EventSource.CLOSED) {
        window.setTimeout(() => this.restart(), Math.min(30_000, 1000 * 2 ** Math.min(this.failures, 5)));
      }
    } catch {
      if (this.source?.readyState === EventSource.CLOSED) window.setTimeout(() => this.restart(), 5000);
    }
  }

  /** Visibility decides whether open conversations count as read, and whether a stale stream is rebuilt. */
  handleVisibility() {
    if (document.visibilityState === "hidden") {
      this.hiddenAt = Date.now();
      void this.sendWatch();
      return;
    }
    const away = Date.now() - this.hiddenAt;
    if (!this.source || this.source.readyState === EventSource.CLOSED || (this.hiddenAt > 0 && away > 30_000)) this.restart();
    else void this.sendWatch();
  }
}

export const live = new LiveConnection();

document.addEventListener("visibilitychange", () => {
  if (useStore.getState().auth?.authenticated) live.handleVisibility();
});
window.addEventListener("pageshow", (event) => {
  if ((event as PageTransitionEvent).persisted && useStore.getState().auth?.authenticated) live.restart();
});
window.addEventListener("online", () => {
  if (useStore.getState().auth?.authenticated) live.restart();
});
