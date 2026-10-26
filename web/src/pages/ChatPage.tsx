import { Archive, ArchiveRestore, ArrowDown, Copy, FileDiff, Folder, GitFork, ListTree, MoreHorizontal, Pencil, Pin, PinOff, Trash2, Undo2 } from "lucide-react";
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { enc, errorText, get, post, sessionPath } from "@/api/http";
import { live } from "@/api/live";
import type { Message, Session } from "@/api/types";
import { ChangesSheet, ChildrenSheet, FilesSheet } from "@/components/ChatSheets";
import { Composer, type ComposerSubmit } from "@/components/Composer";
import { PermissionCard, QuestionCard } from "@/components/Requests";
import { DeleteDialog, RenameDialog, setArchived } from "@/components/SessionDialogs";
import { DetailRows, TranscriptRowView } from "@/components/Transcript";
import { Button, Empty, IconButton, Menu, PageHeader, Sheet, Spinner, cx, type MenuItem } from "@/components/ui";
import { useChoices } from "@/lib/choices";
import { choiceKey, draftKey } from "@/lib/local";
import { STATUS_LABELS, displayTitle, resolveSessionProject, rootOf, statusOf } from "@/lib/presentation";
import { back, chatPath, navigate } from "@/lib/router";
import { build, details, type TranscriptRow } from "@/transcript/rows";
import { conversationKey, useStore } from "@/store";

/** One conversation: header, timeline, pending requests and the composer (ChatScreen.kt on the phone). */
export function ChatPage({ serverId, sessionId }: { serverId: string; sessionId: string }) {
  const server = useStore((state) => state.servers[serverId]);
  const loaded = useStore((state) => state.loaded);
  const conversation = useStore((state) => state.conversations[conversationKey(serverId, sessionId)]);
  const olderLoaded = useStore((state) => state.olderLoaded);
  const setLoadingOlder = useStore((state) => state.setLoadingOlder);
  const pinnedIds = useStore((state) => state.ui.pinned?.[serverId]);
  const updateUi = useStore((state) => state.updateUi);
  const toast = useStore((state) => state.toast);
  const [fetched, setFetched] = useState<Session | null>(null);
  const [missing, setMissing] = useState(false);
  const [sheet, setSheet] = useState<"files" | "changes" | "children" | null>(null);
  const [group, setGroup] = useState<TranscriptRow | null>(null);
  const [renaming, setRenaming] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [references, setReferences] = useState<string[]>([]);

  const session = server?.sessions.find((it) => it.id === sessionId) ?? fetched;

  // Sessions outside the live catalog (children, older history) are read once.
  useEffect(() => {
    setFetched(null);
    setMissing(false);
    setReferences([]);
  }, [serverId, sessionId]);
  useEffect(() => {
    if (!server || server.sessions.some((it) => it.id === sessionId) || fetched || missing) return;
    get<{ session: Session }>(sessionPath(serverId, sessionId)).then((it) => setFetched(it.session)).catch(() => setMissing(true));
  }, [server, serverId, sessionId, fetched, missing]);

  useEffect(() => {
    live.watch([{ serverId, sessionId }]);
    return () => live.watch([]);
  }, [serverId, sessionId]);

  // Close any result notification for this conversation once it is on screen.
  useEffect(() => {
    void navigator.serviceWorker?.getRegistration().then((registration) => registration?.getNotifications({ tag: `${serverId}:${sessionId}` }))
      .then((notifications) => notifications?.forEach((it) => it.close())).catch(() => undefined);
  }, [serverId, sessionId, server?.notices]);

  const task = server?.tasks[sessionId];
  const working = task?.active === true;
  const messages = conversation?.messages ?? NO_MESSAGES;
  const rows = useMemo(() => build(messages, new Set(), working, session?.revertMessageId ?? null), [messages, working, session?.revertMessageId]);
  const choices = useChoices(serverId, session?.directory, !!session && server?.connected === true);
  const root = server && session ? rootOf(server, session.id) : sessionId;
  const permissions = server?.permissions.filter((it) => server && rootOf(server, it.sessionId) === root) ?? [];
  const questions = server?.questions.filter((it) => server && rootOf(server, it.sessionId) === root) ?? [];
  const latest = [...messages].reverse();
  const defaultAgent = latest.find((it) => it.agent)?.agent ?? session?.agent ?? null;
  const defaultModel = latest.find((it) => it.model)?.model ?? session?.model ?? null;

  const openChild = useCallback((child: string) => navigate(chatPath(serverId, child)), [serverId]);
  const openGroup = useCallback((row: TranscriptRow) => setGroup(row), []);

  async function loadOlder() {
    const key = conversationKey(serverId, sessionId);
    const cursor = conversation?.cursor;
    if (!cursor || conversation?.loadingOlder) return;
    setLoadingOlder(key, true);
    try {
      const page = await get<{ messages: Message[]; cursor: string | null }>(`${sessionPath(serverId, sessionId)}/messages?cursor=${enc(cursor)}`);
      olderLoaded(key, page.messages, page.cursor);
    } catch (error) {
      setLoadingOlder(key, false);
      toast(errorText(error, "读取更早的消息失败"), "error");
    }
  }

  async function submit(payload: ComposerSubmit): Promise<boolean> {
    try {
      await post(`${sessionPath(serverId, sessionId)}/send`, { ...payload, references: payload.references });
      setReferences([]);
      return true;
    } catch (error) {
      toast(errorText(error, "发送失败"), "error");
      return false;
    }
  }

  async function stop() {
    try { await post(`${sessionPath(serverId, sessionId)}/abort`); } catch (error) { toast(errorText(error), "error"); }
  }

  if (!server) {
    return loaded ? <Missing text="服务器不存在或已移除" /> : <div className="flex h-full items-center justify-center bg-chat"><Spinner size={22} /></div>;
  }
  if (!session) {
    return missing ? <Missing text="会话尚未加载或已关闭" /> : <div className="flex h-full items-center justify-center bg-chat"><Spinner size={22} /></div>;
  }

  const caps = server.capabilities;
  const pinned = pinnedIds?.includes(session.id) ?? false;
  const status = session.parentId ? null : statusOf(server, session.id);
  const project = resolveSessionProject(session, server.projects);
  const menu: { items: MenuItem[] }[] = [
    { items: [
      { label: "子任务", icon: ListTree, onSelect: () => setSheet("children") },
      ...(caps.diff ? [{ label: "改动", icon: FileDiff, onSelect: () => setSheet("changes") }] : []),
      { label: "文件", icon: Folder, onSelect: () => setSheet("files") },
    ] },
    { items: [
      { label: pinned ? "取消置顶" : "置顶", icon: pinned ? PinOff : Pin, onSelect: () => updateUi((ui) => {
        const list = ui.pinned?.[serverId] ?? [];
        return { ...ui, pinned: { ...ui.pinned, [serverId]: pinned ? list.filter((it) => it !== session.id) : [session.id, ...list] } };
      }) },
      ...(caps.rename ? [{ label: "重命名", icon: Pencil, onSelect: () => setRenaming(true) }] : []),
      ...(caps.fork ? [{ label: "分叉", icon: GitFork, onSelect: async () => {
        try {
          const result = await post<{ session: Session }>(`${sessionPath(serverId, session.id)}/fork`);
          navigate(chatPath(serverId, result.session.id));
        } catch (error) { toast(errorText(error), "error"); }
      } }] : []),
      ...(caps.archive && !session.archived ? [{ label: "归档", icon: Archive, onSelect: async () => { if (await setArchived(serverId, session.id, true)) back(); } }] : []),
      ...(caps.archive && caps.restore && session.archived ? [{ label: "取消归档", icon: ArchiveRestore, onSelect: () => void setArchived(serverId, session.id, false) }] : []),
      { label: "复制会话链接", icon: Copy, onSelect: () => { void navigator.clipboard?.writeText(window.location.href); toast("已复制"); } },
      ...(caps.delete ? [{ label: "删除", icon: Trash2, danger: true, onSelect: () => setDeleting(true) }] : []),
    ] },
  ];

  const offlineHint = !server.connected ? "离线 · 草稿保留，恢复连接后可发送" : server.cached ? "消息未同步，刷新后可发送" : null;
  const subtitle = [
    status && status !== "NONE" ? STATUS_LABELS[status] : working ? "运行中" : null,
    server.profile.name,
    project?.name,
  ].filter(Boolean).join(" · ");

  return (
    <div className="flex h-full min-h-0 flex-col bg-chat text-chat-text">
      <PageHeader title={displayTitle(session)} subtitle={subtitle} onBack={() => back()} className="bg-chat"
        actions={<Menu trigger={<IconButton icon={MoreHorizontal} label="会话操作" tone="chat" />} sections={menu} />} />
      {session.revertMessageId && caps.unrevert && (
        <div className="flex items-center gap-2 bg-warning-subtle px-4 py-2 text-[13px] text-warning">
          <Undo2 size={15} /><span className="flex-1">已撤销部分消息</span>
          <Button size="sm" variant="ghost" onClick={async () => {
            try { await post(`${sessionPath(serverId, session.id)}/unrevert`); } catch (error) { toast(errorText(error), "error"); }
          }}>恢复撤销</Button>
        </div>
      )}
      <Timeline key={`${serverId}:${sessionId}`} rows={rows} loading={conversation?.loading ?? true} error={conversation?.error ?? null}
        hasOlder={!!conversation?.cursor} loadingOlder={conversation?.loadingOlder ?? false} onLoadOlder={loadOlder}
        onOpenGroup={openGroup} onOpenChild={openChild}
        footer={(permissions.length > 0 || questions.length > 0) && (
          <div className="space-y-3 py-3">
            {permissions.map((request) => <PermissionCard key={request.id} serverId={serverId} request={request} />)}
            {questions.map((request) => <QuestionCard key={request.id} serverId={serverId} request={request} />)}
          </div>
        )} />
      <div className="mx-auto w-full max-w-[860px]">
        <Composer serverId={serverId} draftKey={draftKey(serverId, sessionId)} choiceKey={choiceKey(serverId, sessionId)} choices={choices}
          ready={server.connected && !server.cached} offlineHint={offlineHint} running={working} defaultAgent={defaultAgent} defaultModel={defaultModel}
          references={references} onRemoveReference={(path) => setReferences((current) => current.filter((it) => it !== path))}
          onServerFile={() => setSheet("files")} onSubmit={submit} onStop={stop} />
      </div>

      <Sheet open={group != null} onOpenChange={(open) => !open && setGroup(null)} title={group?.title || "操作记录"} chat wide>
        {group && <ActivityDetails messages={messages} group={group} working={working} revert={session.revertMessageId ?? null} onOpenChild={(id) => { setGroup(null); openChild(id); }} />}
      </Sheet>
      <FilesSheet open={sheet === "files"} onOpenChange={(open) => !open && setSheet(null)} serverId={serverId} directory={session.directory} sessionId={session.id}
        onReference={(path) => setReferences((current) => (current.includes(path) ? current : [...current, path]))} />
      <ChangesSheet open={sheet === "changes"} onOpenChange={(open) => !open && setSheet(null)} serverId={serverId} sessionId={session.id} />
      <ChildrenSheet open={sheet === "children"} onOpenChange={(open) => !open && setSheet(null)} serverId={serverId} sessionId={session.id} tasks={server.tasks} onOpen={openChild} />
      {renaming && <RenameDialog serverId={serverId} session={session} onClose={() => setRenaming(false)} />}
      {deleting && <DeleteDialog serverId={serverId} session={session} onClose={() => setDeleting(false)} onDeleted={() => back()} />}
    </div>
  );
}

const NO_MESSAGES: Message[] = [];

function ActivityDetails({ messages, group, working, revert, onOpenChild }: { messages: Message[]; group: TranscriptRow; working: boolean; revert: string | null; onOpenChild: (id: string) => void }) {
  const rows = useMemo(() => details(messages, group.key, working, revert), [messages, group.key, working, revert]);
  if (rows.length === 0) return <Empty title={group.status === "running" ? "等待服务器输出…" : "此操作没有正文"} />;
  return <DetailRows rows={rows} onOpenChild={onOpenChild} />;
}

/**
 * The scrolling timeline. It follows new output while the reader is at the bottom, keeps the reader's
 * place when older history is prepended, and loads older pages as the top comes into view.
 */
function Timeline({ rows, loading, error, hasOlder, loadingOlder, onLoadOlder, onOpenGroup, onOpenChild, footer }: {
  rows: TranscriptRow[]; loading: boolean; error: string | null; hasOlder: boolean; loadingOlder: boolean; onLoadOlder: () => void;
  onOpenGroup: (row: TranscriptRow) => void; onOpenChild: (id: string) => void; footer: React.ReactNode;
}) {
  const scroller = useRef<HTMLDivElement>(null);
  const top = useRef<HTMLDivElement>(null);
  const stick = useRef(true);
  const [atBottom, setAtBottom] = useState(true);
  const anchor = useRef<{ height: number; top: number } | null>(null);
  const firstKey = rows[0]?.key;

  // Older rows were prepended: keep what the reader was looking at in place.
  useLayoutEffect(() => {
    const element = scroller.current;
    if (!element) return;
    if (anchor.current) {
      element.scrollTop = anchor.current.top + (element.scrollHeight - anchor.current.height);
      anchor.current = null;
    } else if (stick.current) {
      element.scrollTop = element.scrollHeight;
    }
  }, [rows, footer, firstKey]);

  useEffect(() => {
    const element = scroller.current;
    if (!element) return;
    const observer = new ResizeObserver(() => { if (stick.current) element.scrollTop = element.scrollHeight; });
    observer.observe(element);
    if (element.firstElementChild) observer.observe(element.firstElementChild);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    const element = top.current;
    if (!element || !hasOlder) return;
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((it) => it.isIntersecting) && !loadingOlder && scroller.current) {
        anchor.current = { height: scroller.current.scrollHeight, top: scroller.current.scrollTop };
        onLoadOlder();
      }
    }, { root: scroller.current, rootMargin: "400px 0px 0px 0px" });
    observer.observe(element);
    return () => observer.disconnect();
  }, [hasOlder, loadingOlder, onLoadOlder]);

  const onScroll = () => {
    const element = scroller.current;
    if (!element) return;
    const bottom = element.scrollHeight - element.scrollTop - element.clientHeight < 80;
    stick.current = bottom;
    if (bottom !== atBottom) setAtBottom(bottom);
  };

  return (
    <div className="relative min-h-0 flex-1">
      <div ref={scroller} onScroll={onScroll} className="scroll-area absolute inset-0">
        <div className="mx-auto w-full max-w-[860px] px-4 pb-4">
          <div ref={top} />
          {hasOlder && <div className="flex justify-center py-3">{loadingOlder ? <Spinner size={18} /> : <span className="text-[12px] text-chat-faint">上拉加载更早的消息</span>}</div>}
          {loading && rows.length === 0 ? (
            <div className="flex justify-center py-16"><Spinner size={22} /></div>
          ) : error && rows.length === 0 ? (
            <Empty title={error} />
          ) : rows.length === 0 ? (
            <Empty title="还没有消息" detail="在下方描述你的任务开始这个会话" />
          ) : rows.map((row) => <TranscriptRowView key={row.key} row={row} onOpenGroup={onOpenGroup} onOpenChild={onOpenChild} />)}
          {error && rows.length > 0 && <p className="py-2 text-center text-[12.5px] text-warning">保留上次数据 · {error}</p>}
          {footer}
        </div>
      </div>
      {!atBottom && (
        <button type="button" aria-label="回到底部" onClick={() => { const element = scroller.current; if (element) { stick.current = true; element.scrollTo({ top: element.scrollHeight, behavior: "smooth" }); } }}
          className={cx("absolute bottom-3 left-1/2 inline-flex size-10 -translate-x-1/2 items-center justify-center rounded-full bg-sheet text-chat-text shadow-md ring-1 ring-divider")}>
          <ArrowDown size={18} />
        </button>
      )}
    </div>
  );
}

function Missing({ text }: { text: string }) {
  return (
    <div className="flex h-full flex-col bg-chat">
      <PageHeader title="会话" onBack={() => back()} className="bg-chat" />
      <Empty title={text} action={<Button variant="secondary" onClick={() => navigate("/", { replace: true })}>返回列表</Button>} />
    </div>
  );
}
