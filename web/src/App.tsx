import { Close, Messages, Refresh } from "@/components/icons";
import { useEffect, useState } from "react";
import { get, onUnauthorized } from "@/api/http";
import { live } from "@/api/live";
import type { AuthStatus } from "@/api/types";
import { BrandMark } from "@/components/BrandMark";
import { Button, Spinner, cx } from "@/components/ui";
import { useTheme, useVisualViewportHeight, useWide } from "@/lib/hooks";
import { applyUpdate, onUpdateReady, setBadge, updateReady } from "@/lib/pwa";
import { useRoute, type Route } from "@/lib/router";
import { ArchivedPage } from "@/pages/ArchivedPage";
import { ChatPage } from "@/pages/ChatPage";
import { DraftPage } from "@/pages/DraftPage";
import { HomePage } from "@/pages/HomePage";
import { LoginPage } from "@/pages/LoginPage";
import { ServerEditorPage } from "@/pages/ServerEditorPage";
import { SettingsPage } from "@/pages/SettingsPage";
import { useShallow } from "zustand/react/shallow";
import { servers as serverList, useStore } from "@/store";

export function App() {
  useTheme();
  useVisualViewportHeight();
  const auth = useStore((state) => state.auth);
  const setAuth = useStore((state) => state.setAuth);
  const [failed, setFailed] = useState(false);

  const check = () => {
    setFailed(false);
    get<AuthStatus>("/auth/status").then(setAuth).catch(() => setFailed(true));
  };
  useEffect(check, []); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => onUnauthorized(() => {
    live.stop();
    const current = useStore.getState().auth;
    if (current?.authenticated) setAuth({ ...current, authenticated: false });
  }), [setAuth]);

  if (!auth) {
    return (
      <main className="flex h-full flex-col items-center justify-center gap-5 bg-bg">
        <BrandMark size={56} />
        {failed ? <><p className="text-text-2">无法连接到中枢</p><Button variant="secondary" onClick={check}>重试</Button></> : <Spinner size={22} />}
      </main>
    );
  }
  if (!auth.authenticated) return <LoginPage status={auth} onSignedIn={check} />;
  return <Shell />;
}

function Shell() {
  const route = useRoute();
  const wide = useWide();
  useEffect(() => {
    live.start();
    return () => live.stop();
  }, []);
  useBadge();

  const page = renderRoute(route, wide);
  return (
    <div className="flex h-[var(--app-height,100dvh)] w-full overflow-hidden bg-bg">
      {wide ? (
        <>
          <aside className="safe-x h-full w-[360px] shrink-0 border-r border-divider xl:w-[400px]"><HomePage /></aside>
          <main className="h-full min-w-0 flex-1">{page ?? <Placeholder />}</main>
        </>
      ) : (
        <main key={routeKey(route)} className={cx("safe-x h-full w-full", route.name !== "home" && "page-in")}>{page ?? <HomePage />}</main>
      )}
      <Toasts />
      <UpdatePrompt />
    </div>
  );
}

function renderRoute(route: Route, wide: boolean) {
  switch (route.name) {
    case "chat": return <ChatPage key={`${route.serverId}:${route.sessionId}`} serverId={route.serverId} sessionId={route.sessionId} />;
    case "new": return <DraftPage key={`${route.serverId}:${route.projectId}`} serverId={route.serverId} projectId={route.projectId} />;
    case "archived": return <ArchivedPage />;
    case "settings": return <SettingsPage />;
    case "server": return <ServerEditorPage key={route.id ?? "new"} id={route.id} />;
    case "home": return wide ? null : null;
    default: return null;
  }
}

const routeKey = (route: Route) => route.name === "chat" ? `chat:${route.serverId}:${route.sessionId}` : route.name;

function Placeholder() {
  return (
    <div className="flex h-full flex-col items-center justify-center gap-3 bg-chat text-chat-faint">
      <Messages size={40} />
      <p className="text-[15px]">选择一个会话，或新建会话</p>
    </div>
  );
}

/** App icon badge: everything that needs the user across servers with notifications on. */
function useBadge() {
  const all = useStore(useShallow(serverList));
  const count = all.filter((it) => it.profile.notifications).reduce((sum, it) => sum + it.summary.waiting + it.summary.completed + it.summary.failed, 0);
  useEffect(() => { setBadge(count); }, [count]);
  useEffect(() => {
    document.title = count > 0 ? `(${count}) Lagoon` : "Lagoon";
  }, [count]);
}

function Toasts() {
  const toasts = useStore((state) => state.toasts);
  const dismiss = useStore((state) => state.dismissToast);
  return (
    <div className="pointer-events-none fixed inset-x-0 bottom-[calc(96px+var(--safe-bottom))] z-[60] flex flex-col items-center gap-2 px-4">
      {toasts.map((toast) => (
        <div key={toast.id} role="status" className={cx("squircle fade-in pointer-events-auto flex max-w-[min(92vw,480px)] items-start gap-2 rounded-2xl px-4 py-3 text-[14px] shadow-lg",
          toast.level === "error" ? "bg-error text-white dark:text-black" : "bg-text text-bg")}>
          <span className="flex-1 [overflow-wrap:anywhere]">{toast.message}</span>
          <button type="button" aria-label="关闭" onClick={() => dismiss(toast.id)} className="opacity-70"><Close size={16} /></button>
        </div>
      ))}
    </div>
  );
}

function UpdatePrompt() {
  const [ready, setReady] = useState(updateReady());
  useEffect(() => onUpdateReady(() => setReady(updateReady())), []);
  if (!ready) return null;
  return (
    <div className="squircle fade-in fixed left-1/2 top-[calc(12px+var(--safe-top))] z-[60] flex -translate-x-1/2 items-center gap-3 rounded-full bg-surface px-4 py-2 text-[14px] shadow-lg ring-1 ring-divider">
      <Refresh size={16} className="text-primary" />新版本已就绪
      <Button size="sm" onClick={applyUpdate}>刷新</Button>
    </div>
  );
}
