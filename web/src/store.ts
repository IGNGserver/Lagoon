import { create } from "zustand";
import type {
  AuthStatus, ConversationOps, ConversationReset, HubState, Message, PermissionPolicy, RefreshMode, ServerView, UiPrefs,
} from "@/api/types";
import { put } from "@/api/http";
import { applyOps, mergeWindow, prependOlder } from "@/transcript/ops";
import { readLocal, writeLocal } from "@/lib/local";

export interface ConversationState {
  messages: Message[];
  /** Where older history continues; null at the start of the session. */
  cursor: string | null;
  loading: boolean;
  error: string | null;
  loadingOlder: boolean;
  /** This tab paged older history in; the hub's window no longer decides the cursor. */
  pagedOlder: boolean;
}

export interface Toast { id: number; message: string; level: "info" | "error" }

export type ThemeMode = "system" | "light" | "dark";
export type LiveStatus = "connecting" | "open" | "reconnecting";

interface Store {
  auth: AuthStatus | null;
  servers: Record<string, ServerView>;
  order: string[];
  policy: PermissionPolicy;
  refreshMode: RefreshMode;
  ui: UiPrefs;
  loaded: boolean;
  live: LiveStatus;
  conversations: Record<string, ConversationState>;
  toasts: Toast[];
  theme: ThemeMode;
  /** Home filter: null shows every server. */
  homeServer: string | null;
  /** Project filter per server (home title). */
  scopes: Record<string, string | null>;

  setAuth(auth: AuthStatus | null): void;
  applyState(state: HubState): void;
  upsertServer(server: ServerView): void;
  removeServer(serverId: string): void;
  resetConversation(reset: ConversationReset): void;
  applyConversationOps(batch: ConversationOps): void;
  olderLoaded(key: string, older: Message[], cursor: string | null): void;
  setLoadingOlder(key: string, loading: boolean): void;
  setLive(live: LiveStatus): void;
  toast(message: string, level?: Toast["level"]): void;
  dismissToast(id: number): void;
  setTheme(theme: ThemeMode): void;
  setHomeServer(serverId: string | null): void;
  setScope(serverId: string, projectId: string | null): void;
  /** Updates the shared preferences on the hub (optimistically here). */
  updateUi(change: (ui: UiPrefs) => UiPrefs): void;
}

export const conversationKey = (serverId: string, sessionId: string) => `${serverId}\u0000${sessionId}`;

const emptyConversation: ConversationState = { messages: [], cursor: null, loading: true, error: null, loadingOlder: false, pagedOlder: false };

let toastId = 0;

export const useStore = create<Store>((set, get) => ({
  auth: null,
  servers: {},
  order: [],
  policy: { hide: false, autoApprove: false },
  refreshMode: "FAST",
  ui: {},
  loaded: false,
  live: "connecting",
  conversations: {},
  toasts: [],
  theme: readLocal<ThemeMode>("theme", "system"),
  homeServer: readLocal<string | null>("homeServer", null),
  scopes: readLocal<Record<string, string | null>>("scopes", {}),

  setAuth: (auth) => set({ auth }),

  applyState: (state) => set({
    servers: Object.fromEntries(state.servers.map((it) => [it.serverId, it])),
    order: state.servers.map((it) => it.serverId),
    policy: state.policy,
    refreshMode: state.refreshMode,
    ui: state.ui ?? {},
    loaded: true,
  }),

  upsertServer: (server) => set((current) => ({
    servers: { ...current.servers, [server.serverId]: server },
    order: current.order.includes(server.serverId) ? current.order : [...current.order, server.serverId],
  })),

  removeServer: (serverId) => set((current) => {
    const servers = { ...current.servers };
    delete servers[serverId];
    return { servers, order: current.order.filter((it) => it !== serverId), homeServer: current.homeServer === serverId ? null : current.homeServer };
  }),

  resetConversation: (reset) => set((current) => {
    const key = conversationKey(reset.serverId, reset.sessionId);
    const previous = current.conversations[key];
    const messages = mergeWindow(previous?.messages ?? [], reset.messages);
    return {
      conversations: {
        ...current.conversations,
        [key]: {
          messages,
          cursor: previous?.pagedOlder ? previous.cursor : reset.cursor,
          loading: reset.loading,
          error: reset.error,
          loadingOlder: false,
          pagedOlder: previous?.pagedOlder ?? false,
        },
      },
    };
  }),

  applyConversationOps: (batch) => set((current) => {
    const key = conversationKey(batch.serverId, batch.sessionId);
    const previous = current.conversations[key];
    if (!previous) return {};
    return { conversations: { ...current.conversations, [key]: { ...previous, messages: applyOps(previous.messages, batch.ops, batch.order) } } };
  }),

  olderLoaded: (key, older, cursor) => set((current) => {
    const previous = current.conversations[key] ?? emptyConversation;
    return { conversations: { ...current.conversations, [key]: { ...previous, messages: prependOlder(previous.messages, older), cursor, loadingOlder: false, pagedOlder: true } } };
  }),

  setLoadingOlder: (key, loading) => set((current) => {
    const previous = current.conversations[key];
    return previous ? { conversations: { ...current.conversations, [key]: { ...previous, loadingOlder: loading } } } : {};
  }),

  setLive: (live) => set({ live }),

  toast: (message, level = "info") => {
    const id = ++toastId;
    set((current) => ({ toasts: [...current.toasts.slice(-2), { id, message, level }] }));
    window.setTimeout(() => get().dismissToast(id), level === "error" ? 6000 : 3500);
  },

  dismissToast: (id) => set((current) => ({ toasts: current.toasts.filter((it) => it.id !== id) })),

  setTheme: (theme) => { writeLocal("theme", theme === "system" ? null : theme); set({ theme }); },

  setHomeServer: (homeServer) => { writeLocal("homeServer", homeServer); set({ homeServer }); },

  setScope: (serverId, projectId) => set((current) => {
    const scopes = { ...current.scopes, [serverId]: projectId };
    writeLocal("scopes", scopes);
    return { scopes };
  }),

  updateUi: (change) => {
    const ui = change(get().ui);
    set({ ui });
    put("/prefs", { ui }).catch(() => get().toast("偏好未能保存到中枢", "error"));
  },
}));

export function conversationOf(serverId: string, sessionId: string): ConversationState {
  return useStore.getState().conversations[conversationKey(serverId, sessionId)] ?? emptyConversation;
}

export const servers = (state: Pick<Store, "servers" | "order">) => state.order.map((id) => state.servers[id]).filter((it): it is ServerView => it != null);
