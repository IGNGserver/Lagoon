import {
  Add, ArchiveIcon, ChevronForward, Close, Delete, GridView, More, Ok, Pin, Rename, Search, Settings, Unpin,
} from "@/components/icons";
import { useMemo, useState, type ReactNode } from "react";
import { enc, errorText, get, post, serverPath } from "@/api/http";
import type { ServerView, Session } from "@/api/types";
import { ProjectSheet } from "@/components/ProjectSheet";
import { DeleteDialog, RenameDialog, setArchived } from "@/components/SessionDialogs";
import { StatusDot, StatusGlyph } from "@/components/StatusGlyph";
import { Button, Empty, IconButton, Menu, Spinner, cx, type MenuItem } from "@/components/ui";
import { readLocal, writeLocal } from "@/lib/local";
import { useLongPress, useNow } from "@/lib/hooks";
import {
  GROUPING_LABELS, STATUS_LABELS, countLine, displayTitle, entryKey, homeEntries, homeSections, isWaiting, mergeSummaries,
  resolveSessionProject, statusOf, type HomeEntry, type HomeGrouping,
} from "@/lib/presentation";
import { chatPath, navigate, serverEditPath, useRoute } from "@/lib/router";
import { useShallow } from "zustand/react/shallow";
import { servers as serverList, useStore } from "@/store";

/**
 * The single root page, structured like the Android home: a scope title, server capsules, the
 * cross-server task strip and the session list grouped by date / project / status, plus a floating
 * “new session” button. On wide screens it is the left column next to the open conversation.
 */
export function HomePage() {
  const all = useStore(useShallow(serverList));
  const homeServer = useStore((state) => state.homeServer);
  const scopes = useStore((state) => state.scopes);
  const ui = useStore((state) => state.ui);
  const updateUi = useStore((state) => state.updateUi);
  const live = useStore((state) => state.live);
  const loaded = useStore((state) => state.loaded);
  const route = useRoute();
  const now = useNow();
  const [projects, setProjects] = useState(false);
  const [searching, setSearching] = useState(false);
  const [query, setQuery] = useState("");
  const [collapsed, setCollapsed] = useState<string[]>(() => readLocal("collapsed", []));
  const [renaming, setRenaming] = useState<HomeEntry | null>(null);
  const [deleting, setDeleting] = useState<HomeEntry | null>(null);
  const [more, setMore] = useState<Record<string, { sessions: Session[]; next: string | null; loading: boolean }>>({});
  const grouping: HomeGrouping = ui.grouping ?? "DATE";

  const current = homeServer ? all.find((it) => it.serverId === homeServer) ?? null : null;
  const visible = useMemo(() => (current ? [current] : all), [current, all]);
  const scope = current ? scopes[current.serverId] ?? null : null;
  const scopeName = current && scope ? current.projects.find((it) => it.id === scope)?.name : null;
  const title = current ? scopeName ?? "全部会话" : all.length > 1 ? "全部服务器" : "全部会话";

  const withMore = useMemo(() => visible.map((server) => {
    const extra = more[server.serverId]?.sessions ?? [];
    if (extra.length === 0) return server;
    const known = new Set(server.sessions.map((it) => it.id));
    return { ...server, sessions: [...server.sessions, ...extra.filter((it) => !known.has(it.id))] };
  }), [visible, more]);

  const pinned = (entry: HomeEntry) => ui.pinned?.[entry.server.serverId]?.includes(entry.session.id) ?? false;
  const entries = useMemo(() => homeEntries(withMore, scope).filter(({ server, session }) => {
    const text = query.trim().toLowerCase();
    if (!text) return true;
    return displayTitle(session).toLowerCase().includes(text) || session.directory.toLowerCase().includes(text) ||
      (resolveSessionProject(session, server.projects)?.name.toLowerCase().includes(text) ?? false);
  }), [withMore, scope, query]);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const sections = useMemo(() => homeSections(grouping, entries, pinned, now), [grouping, entries, ui.pinned, now]);
  const unread = visible.some((server) => server.notices.some((it) => !it.viewed));

  const toggleSection = (key: string) => {
    const next = collapsed.includes(key) ? collapsed.filter((it) => it !== key) : [...collapsed, key];
    setCollapsed(next);
    writeLocal("collapsed", next);
  };

  async function markAllRead() {
    let total = 0;
    for (const server of visible) {
      const unseen = new Set(server.notices.filter((it) => !it.viewed).map((it) => it.sessionId)).size;
      if (unseen === 0) continue;
      try {
        await post(`${serverPath(server.serverId)}/read`, { server: false });
        total += unseen;
      } catch (error) {
        useStore.getState().toast(errorText(error), "error");
      }
    }
    if (total > 0) useStore.getState().toast(`已将 ${total} 个会话标记为已读`);
  }

  async function syncServerRead() {
    let synced = 0;
    let failed = 0;
    for (const server of visible.filter((it) => it.canSyncRead && it.connected)) {
      try {
        const result = await post<{ synced: number; failed: number }>(`${serverPath(server.serverId)}/read`, { server: true });
        synced += result.synced;
        failed += result.failed;
      } catch {
        failed++;
      }
    }
    useStore.getState().toast(failed === 0 ? `服务端已读已同步 ${synced} 个会话` : `服务端已读：${synced} 个成功，${failed} 个失败`, failed > 0 && synced === 0 ? "error" : "info");
  }

  async function loadMore(server: ServerView) {
    const state = more[server.serverId];
    setMore((it) => ({ ...it, [server.serverId]: { sessions: state?.sessions ?? [], next: state?.next ?? null, loading: true } }));
    try {
      let cursor = state?.next ?? null;
      let sessions = state?.sessions ?? [];
      if (!state) {
        // The live catalog is the newest page; start paging after it.
        const first = await get<{ sessions: Session[]; next: string | null }>(`${serverPath(server.serverId)}/sessions`);
        cursor = first.next;
      }
      if (cursor) {
        const page = await get<{ sessions: Session[]; next: string | null }>(`${serverPath(server.serverId)}/sessions?cursor=${enc(cursor)}`);
        sessions = [...sessions, ...page.sessions];
        cursor = page.next;
      }
      setMore((it) => ({ ...it, [server.serverId]: { sessions, next: cursor, loading: false } }));
    } catch (error) {
      useStore.getState().toast(errorText(error), "error");
      setMore((it) => ({ ...it, [server.serverId]: { sessions: state?.sessions ?? [], next: state?.next ?? null, loading: false } }));
    }
  }

  const newSession = () => {
    const target = current ?? all.find((it) => it.connected) ?? all[0];
    navigate(target ? `/new?server=${enc(target.serverId)}${scope ? `&project=${enc(scope)}` : ""}` : serverEditPath(null));
  };

  const openChat = route.name === "chat" ? `${route.serverId}\u0000${route.sessionId}` : null;
  const serverMore = current && !query && (more[current.serverId]?.next !== null || !more[current.serverId]) && current.sessions.filter((it) => !it.parentId).length >= 100;

  return (
    <div className="relative flex h-full min-h-0 flex-col bg-bg">
      {searching ? (
        <div className="safe-top flex min-h-16 items-center gap-2 px-3 pb-1 pt-2">
          <div className="squircle flex h-11 flex-1 items-center gap-2 rounded-2xl bg-surface-2 px-3">
            <Search size={18} className="text-text-2" />
            <input autoFocus value={query} onChange={(e) => setQuery(e.target.value)} placeholder="搜索会话标题或项目"
              className="min-w-0 flex-1 bg-transparent text-[16px] outline-none placeholder:text-text-3" />
            {query && <button type="button" aria-label="清除" onClick={() => setQuery("")}><Close size={16} className="text-text-2" /></button>}
          </div>
          <Button variant="ghost" onClick={() => { setSearching(false); setQuery(""); }}>取消</Button>
        </div>
      ) : (
        <header className="safe-top flex min-h-16 items-center gap-1 px-3 pb-1 pt-2">
          <button type="button" onClick={() => setProjects(true)} className="flex min-w-0 flex-1 items-center gap-2.5 rounded-full px-2 py-1.5 text-left hover:bg-surface-2">
            <GridView size={24} className="shrink-0" />
            <span className="truncate text-[22px] font-medium">{title}</span>
            <ChevronForward size={20} className="shrink-0 rotate-90" />
          </button>
          {unread && <Button variant="ghost" size="sm" onClick={markAllRead} className="shrink-0"><Ok size={16} />全部已读</Button>}
          <Menu trigger={<IconButton icon={More} label="更多" />} sections={[
            { title: "分组方式", items: (Object.keys(GROUPING_LABELS) as HomeGrouping[]).map((value) => ({
              label: GROUPING_LABELS[value], checked: grouping === value, onSelect: () => updateUi((it) => ({ ...it, grouping: value })),
            })) },
            { title: "已读同步", items: visible.some((it) => it.canSyncRead && it.connected) ? [{ label: "同步服务端已读", onSelect: syncServerRead }] : [] },
            { items: [
              { label: "搜索", icon: Search, onSelect: () => setSearching(true) },
              { label: "已归档", icon: ArchiveIcon, onSelect: () => navigate("/archived") },
              { label: "设置", icon: Settings, onSelect: () => navigate("/settings") },
            ] },
          ]} />
        </header>
      )}

      {!searching && <ServerChips all={all} homeServer={homeServer} />}
      {!searching && <TaskStrip all={all} />}
      {live !== "open" && loaded && <p className="px-6 py-1.5 text-[13px] text-warning">{live === "reconnecting" ? "与中枢的实时连接已断开，正在重连…" : "正在连接中枢…"}</p>}
      {current && !searching && <ConnectionHint server={current} />}

      <div className="scroll-area min-h-0 flex-1 pb-28">
        {!loaded && all.length === 0 ? (
          <div className="flex justify-center py-16"><Spinner size={22} /></div>
        ) : all.length === 0 ? (
          <Empty title="还没有服务器" detail="添加一台运行 OpenCode 或 DeepSeek Harness 的开发机，中枢会替你保持连接。"
            action={<Button onClick={() => navigate(serverEditPath(null))}><Add size={18} />添加服务器</Button>} />
        ) : sections.length === 0 ? (
          <Empty title={query ? "没有符合条件的会话" : visible.some((it) => it.loading) ? "正在读取会话…" : visible.some((it) => it.connected) ? "暂无会话" : "尚未连接"}
            action={!query && visible.some((it) => it.connected) ? <Button variant="secondary" onClick={newSession}>新建会话</Button> : undefined} />
        ) : sections.map((section) => {
          const folded = !query && collapsed.includes(section.key);
          return (
            <div key={section.key}>
              <button type="button" onClick={() => toggleSection(section.key)} className="flex items-center gap-1 px-6 pb-1.5 pt-3 text-[13px] text-text-2">
                {section.title}
                <ChevronForward size={14} className={cx("transition-transform", !folded && "rotate-90")} />
              </button>
              {!folded && section.entries.map((entry) => (
                <SessionRow key={entryKey(entry)} entry={entry} showServer={!current && all.length > 1} pinned={pinned(entry)}
                  active={openChat === entryKey(entry)}
                  onRename={() => setRenaming(entry)} onDelete={() => setDeleting(entry)}
                  onTogglePin={() => updateUi((it) => {
                    const list = it.pinned?.[entry.server.serverId] ?? [];
                    const next = list.includes(entry.session.id) ? list.filter((id) => id !== entry.session.id) : [entry.session.id, ...list];
                    return { ...it, pinned: { ...it.pinned, [entry.server.serverId]: next } };
                  })} />
              ))}
            </div>
          );
        })}
        {current && serverMore && (
          <div className="px-4 py-3">
            <Button variant="secondary" block disabled={more[current.serverId]?.loading} onClick={() => loadMore(current)}>
              {more[current.serverId]?.loading ? "正在加载…" : "加载更多会话"}
            </Button>
          </div>
        )}
      </div>

      {!searching && all.length > 0 && (
        <button type="button" onClick={newSession} aria-label="新建会话"
          className="squircle absolute bottom-[calc(24px+var(--safe-bottom))] right-6 flex size-14 items-center justify-center rounded-full bg-primary text-on-primary shadow-[0_8px_24px_rgba(52,130,255,0.4)] transition-transform active:scale-95">
          <Add size={28} />
        </button>
      )}

      <ProjectSheet open={projects} onOpenChange={setProjects} />
      {renaming && <RenameDialog serverId={renaming.server.serverId} session={renaming.session} onClose={() => setRenaming(null)} />}
      {deleting && <DeleteDialog serverId={deleting.server.serverId} session={deleting.session} onClose={() => setDeleting(null)} />}
    </div>
  );
}

function ServerChips({ all, homeServer }: { all: ServerView[]; homeServer: string | null }) {
  const setHomeServer = useStore((state) => state.setHomeServer);
  const tone = (server: ServerView) => (server.connected && server.streamConnected ? "ok" : server.connected || server.loading ? "busy" : "off");
  const chip = "squircle flex h-10 shrink-0 items-center gap-2 rounded-full px-4 text-[14.5px] transition-colors";
  return (
    <div className="scroll-area flex gap-2 overflow-x-auto px-4 py-2 [scrollbar-width:none]">
      {all.length > 1 && (
        <button type="button" onClick={() => setHomeServer(null)} className={cx(chip, homeServer == null ? "bg-primary-subtle font-medium text-primary" : "bg-surface-2 text-text-2")}>全部</button>
      )}
      {all.map((server) => {
        const selected = homeServer === server.serverId || all.length === 1;
        return (
          <ChipButton key={server.serverId} className={cx(chip, selected ? "bg-primary-subtle font-medium text-primary" : "bg-surface-2 text-text-2")}
            onClick={() => setHomeServer(selected && all.length > 1 ? null : server.serverId)} onEdit={() => navigate(serverEditPath(server.serverId))}>
            <StatusDot tone={tone(server)} />
            <span className="max-w-40 truncate">{server.profile.name}</span>
          </ChipButton>
        );
      })}
      <button type="button" aria-label="添加服务器" onClick={() => navigate(serverEditPath(null))} className={cx(chip, "bg-surface-2 px-3 text-text-2")}>
        <Add size={18} />{all.length === 0 && "添加服务器"}
      </button>
    </div>
  );
}

function ChipButton({ onClick, onEdit, className, children }: { onClick: () => void; onEdit: () => void; className: string; children: ReactNode }) {
  const press = useLongPress(onEdit);
  return <button type="button" onClick={onClick} {...press} className={className} title="长按或右键编辑">{children}</button>;
}

/** The cross-server counts, the in-app twin of the push notifications. */
function TaskStrip({ all }: { all: ServerView[] }) {
  const notifying = all.filter((it) => it.profile.notifications);
  const total = mergeSummaries(notifying.map((it) => it.summary));
  const silent = all.filter((it) => !it.profile.notifications && it.summary.running + it.summary.waiting + it.summary.completed + it.summary.failed > 0);
  const archived = all.reduce((sum, server) => sum + server.sessions.filter((it) => !it.parentId && it.archived)
    .filter((it) => { const status = statusOf(server, it.id); return status !== "NONE" && status !== "UNKNOWN"; }).length, 0);
  const empty = total.running + total.completed + total.waiting + total.failed + total.processing === 0;
  if (empty && archived === 0 && silent.length === 0) return null;
  return (
    <div className="px-6 py-1">
      <p className="text-[12px] text-text-2">全部服务器 · 全部项目</p>
      {!empty && <p className="text-[13.5px] font-medium">{countLine(total)}</p>}
      {archived > 0 && <button type="button" className="py-0.5 text-[12.5px] text-primary" onClick={() => navigate("/archived")}>已归档会话另有 {archived} 个任务 · 查看</button>}
      {silent.length > 0 && <p className="text-[12.5px] text-warning">通知已关闭：{silent.map((it) => it.profile.name).join("、")}（未计入推送）</p>}
    </div>
  );
}

function ConnectionHint({ server }: { server: ServerView }) {
  const [busy, setBusy] = useState(false);
  const retry = async () => {
    setBusy(true);
    try { await post(`${serverPath(server.serverId)}/refresh`); } catch (error) { useStore.getState().toast(errorText(error), "error"); }
    finally { setBusy(false); }
  };
  let text: string | null = null;
  if (!server.monitored) text = "未开启自动连接 · 点按连接";
  else if (!server.connected && !server.loading) text = server.error ? `${server.error} · 点按重试` : "尚未连接 · 点按重试";
  else if (server.cached) text = "离线缓存 · 点按重试";
  else if (server.degraded) text = "部分数据待同步 · 点按重试";
  else if (server.connected && !server.streamConnected) text = "实时连接恢复中…";
  if (!text) return null;
  return (
    <button type="button" disabled={busy} onClick={retry} className="block w-full px-6 py-1.5 text-left text-[13px] text-warning">
      {busy ? "正在重试…" : text}
    </button>
  );
}

function SessionRow({ entry, showServer, pinned, active, onRename, onDelete, onTogglePin }: {
  entry: HomeEntry; showServer: boolean; pinned: boolean; active: boolean; onRename: () => void; onDelete: () => void; onTogglePin: () => void;
}) {
  const { server, session } = entry;
  const [menu, setMenu] = useState(false);
  const status = statusOf(server, session.id);
  const unseen = server.notices.some((it) => it.sessionId === session.id && !it.viewed);
  const highlighted = unseen || isWaiting(status);
  const press = useLongPress(() => setMenu(true));
  const label = status === "WAITING_SERVER" || status === "UNKNOWN" || isWaiting(status) ? STATUS_LABELS[status] : null;
  const background = server.backgroundRunning.includes(session.id);
  const caps = server.capabilities;
  const items: MenuItem[] = [
    { label: pinned ? "取消置顶" : "置顶", icon: pinned ? Unpin : Pin, onSelect: onTogglePin },
    ...(server.connected && caps.rename ? [{ label: "重命名", icon: Rename, onSelect: onRename }] : []),
    ...(server.connected && caps.archive ? [{ label: "归档", icon: ArchiveIcon, onSelect: () => void setArchived(server.serverId, session.id, true) }] : []),
    ...(server.connected && caps.delete ? [{ label: "删除", icon: Delete, danger: true, onSelect: onDelete }] : []),
  ];
  return (
    <div className={cx("row-pad group relative flex items-center", active && "bg-primary-subtle")}>
      <button type="button" {...press} onClick={() => navigate(chatPath(server.serverId, session.id))}
        className="flex min-h-14 min-w-0 flex-1 items-center gap-3 py-3 pl-5 pr-2 text-left hover:bg-surface-2/60">
        <span className="flex size-6 shrink-0 items-center justify-center"><StatusGlyph status={status} unseen={unseen} /></span>
        <span className="min-w-0 flex-1">
          <span className={cx("block truncate text-[16px]", highlighted && "font-medium")}>{displayTitle(session)}</span>
          {showServer && <span className="block truncate text-[12px] text-text-2">{server.profile.name}</span>}
        </span>
        {background && status !== "RUNNING" && <span aria-label="后台还有任务" className="size-2 shrink-0 rounded-full border border-text-3/40" />}
        {label && <span className={cx("shrink-0 text-[12.5px]", isWaiting(status) ? "text-warning" : "text-text-2")}>{label}</span>}
      </button>
      <Menu open={menu} onOpenChange={setMenu} sections={[{ items }]}
        trigger={<IconButton icon={More} label="会话操作" className="mr-3 opacity-0 group-hover:opacity-100 focus:opacity-100 data-[state=open]:opacity-100 [@media(pointer:coarse)]:pointer-events-none" />} />
    </div>
  );
}

