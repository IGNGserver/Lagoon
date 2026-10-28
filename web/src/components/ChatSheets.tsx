import { Back, File, Folder, Search } from "@/components/icons";
import { useEffect, useRef, useState } from "react";
import { enc, errorText, get, serverPath, sessionPath } from "@/api/http";
import type { FileChange, FileContent, FileNode, Session, TaskState } from "@/api/types";
import { languageOf } from "@/lib/markdown";
import { displayTitle } from "@/lib/presentation";
import { CodeBody, HighlightedCode } from "./Transcript";
import { Button, Empty, IconButton, Sheet, Spinner, cx } from "./ui";

function useLoad<T>(load: (() => Promise<T>) | null, deps: unknown[]): { data: T | null; error: string | null; loading: boolean; reload: () => void } {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [tick, setTick] = useState(0);
  useEffect(() => {
    if (!load) return;
    let active = true;
    setLoading(true);
    setError(null);
    load().then((value) => { if (active) setData(value); }).catch((failure) => { if (active) setError(errorText(failure, "读取失败")); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [tick, ...deps]); // eslint-disable-line react-hooks/exhaustive-deps
  return { data, error, loading, reload: () => setTick((it) => it + 1) };
}

function Status({ loading, error, empty, reload, emptyText }: { loading: boolean; error: string | null; empty: boolean; reload: () => void; emptyText: string }) {
  if (loading) return <div className="flex justify-center py-12"><Spinner size={22} /></div>;
  if (error) return <Empty title={error} action={<Button variant="secondary" onClick={reload}>重试</Button>} />;
  if (empty) return <Empty title={emptyText} />;
  return null;
}

export function ChangesSheet({ open, onOpenChange, serverId, sessionId }: { open: boolean; onOpenChange: (open: boolean) => void; serverId: string; sessionId: string }) {
  const { data, error, loading, reload } = useLoad(open ? () => get<{ changes: FileChange[] }>(`${sessionPath(serverId, sessionId)}/diff`).then((it) => it.changes) : null, [open, serverId, sessionId]);
  const [expanded, setExpanded] = useState<string | null>(null);
  return (
    <Sheet open={open} onOpenChange={onOpenChange} title="代码改动" chat wide>
      <Status loading={loading} error={error} empty={!!data && data.length === 0} reload={reload} emptyText="暂无改动记录" />
      {data?.map((change, index) => (
        <div key={`${index}:${change.path}`} className="border-b border-divider/60 py-1">
          <button type="button" className="flex w-full items-center gap-2 py-2.5 text-left" onClick={() => setExpanded(expanded === change.path ? null : change.path)}>
            <File size={16} className="shrink-0 text-chat-text-2" />
            <span className="min-w-0 flex-1 truncate font-mono text-[13px]">{change.path}</span>
            <span className="shrink-0 text-[12.5px] text-chat-success">+{change.additions}</span>
            <span className="shrink-0 text-[12.5px] text-error">−{change.deletions}</span>
          </button>
          {expanded === change.path && <div className="pb-3"><CodeBody text={change.patch || change.after} copy={change.patch || change.after} patch={!!change.patch} /></div>}
        </div>
      ))}
    </Sheet>
  );
}

type ShellView = { id: string; sessionId: string; command: string; started: number; directory: string };

export function ChildrenSheet({ open, onOpenChange, serverId, sessionId, tasks, shells = [], onOpen, onShell }: {
  open: boolean; onOpenChange: (open: boolean) => void; serverId: string; sessionId: string; tasks: Record<string, TaskState>;
  shells?: ShellView[]; onOpen: (id: string) => void; onShell?: (shell: ShellView) => void;
}) {
  const { data, error, loading, reload } = useLoad(open ? () => get<{ sessions: Session[] }>(`${sessionPath(serverId, sessionId)}/children`).then((it) => it.sessions) : null, [open, serverId, sessionId]);
  const empty = !!data && data.length === 0 && shells.length === 0;
  return (
    <Sheet open={open} onOpenChange={onOpenChange} title="子会话与后台" chat>
      <Status loading={loading} error={error} empty={empty} reload={reload} emptyText="暂无子会话或后台任务" />
      {shells.length > 0 && <div className="mb-3 space-y-2">
        <div className="px-1 text-[12.5px] text-text-2">后台命令</div>
        {shells.map((shell) => (
          <button key={shell.id} type="button" onClick={() => onShell?.(shell)}
            className="squircle flex w-full items-center gap-3 rounded-2xl bg-bubble px-4 py-3 text-left">
            <span className="min-w-0 flex-1 truncate font-mono text-[13px]">{shell.command.split("\n")[0]}</span>
            <span className="flex shrink-0 items-center gap-1.5 text-[12.5px] text-primary"><Spinner size={12} />运行中</span>
          </button>
        ))}
      </div>}
      {!!data?.length && <div className="px-1 pb-2 text-[12.5px] text-text-2">子会话</div>}
      <div className="space-y-2">
        {data?.map((child) => {
          const task = tasks[child.id];
          const label = task?.active ? "运行中" : child.outcome === "failed" ? "失败" : child.outcome === "interrupted" ? "已中断" : child.outcome === "succeeded" || child.idle > 0 ? "已完成" : "状态待确认";
          return (
            <button key={child.id} type="button" onClick={() => { onOpenChange(false); onOpen(child.id); }}
              className="squircle flex w-full items-center gap-3 rounded-2xl bg-bubble px-4 py-3 text-left">
              <span className="min-w-0 flex-1">
                <span className="block truncate text-[15px]">{displayTitle(child)}</span>
                <span className="block truncate text-[12px] text-text-2">{label}{child.agent ? ` · ${child.agent}` : ""}</span>
              </span>
              {task?.active && <Spinner size={12} />}
            </button>
          );
        })}
      </div>
    </Sheet>
  );
}

/** Live tail of one background shell, polled through the hub's byte-cursor output route. */
export function ShellOutputSheet({ open, onOpenChange, serverId, shell }: {
  open: boolean; onOpenChange: (open: boolean) => void; serverId: string; shell: ShellView | null;
}) {
  const [text, setText] = useState("");
  const [status, setStatus] = useState("running");
  const [error, setError] = useState<string | null>(null);
  const [omitted, setOmitted] = useState(false);
  const scroller = useRef<HTMLDivElement>(null);
  const shellId = shell?.id;
  const shellDirectory = shell?.directory;

  useEffect(() => {
    if (!open || !shellId || !shellDirectory) return;
    let alive = true;
    let cursor: number | undefined;
    let timer: number | undefined;
    setText(""); setStatus("running"); setError(null); setOmitted(false);
    const url = (cursor: number | undefined, limit: number) =>
      `${serverPath(serverId)}/shells/${enc(shellId)}/output?directory=${enc(shellDirectory)}${cursor === undefined ? "" : `&cursor=${cursor}`}&limit=${limit}`;
    const load = async () => {
      try {
        if (cursor === undefined) {
          const head = await get<{ size: number }>(`${serverPath(serverId)}/shells/${enc(shellId)}/output?directory=${enc(shellDirectory)}&cursor=9223372036854775807&limit=1`);
          cursor = Math.max(0, head.size - 65536);
          setOmitted(cursor > 0);
        }
        const page = await get<{ output: string; cursor: number; size: number; status: string; truncated: boolean; exit?: number | null }>(url(cursor, 65536));
        if (!alive) return;
        cursor = page.cursor;
        setText((previous) => (previous + page.output).slice(-65536));
        setStatus(page.status);
        setError(null);
        if (page.truncated) setOmitted(true);
        requestAnimationFrame(() => { if (scroller.current) scroller.current.scrollTop = scroller.current.scrollHeight; });
        if (page.cursor < page.size) timer = window.setTimeout(load, 0);
        else if (page.status === "running") timer = window.setTimeout(load, 1000);
      } catch (failure) {
        if (alive) setError(errorText(failure, "读取后台输出失败"));
      }
    };
    void load();
    return () => { alive = false; if (timer) window.clearTimeout(timer); };
  }, [open, serverId, shellId, shellDirectory]);

  async function stop() {
    if (!shell) return;
    try { await fetch(`${serverPath(serverId)}/shells/${enc(shell.id)}?directory=${enc(shell.directory)}`, { method: "DELETE", credentials: "same-origin" }); }
    catch (failure) { setError(errorText(failure, "停止命令失败")); }
  }

  return (
    <Sheet open={open} onOpenChange={onOpenChange} title={shell?.command.split("\n")[0] || "后台命令"} chat wide>
      <div className="mb-2 flex items-center gap-2 text-[12.5px] text-text-2">
        <span className="flex-1">{status === "running" ? "运行中" : status === "timeout" ? "已超时" : status === "killed" ? "已停止" : "已结束"}</span>
        {status === "running" && <Button size="sm" variant="ghost" onClick={() => void stop()}>停止</Button>}
      </div>
      {error && <div className="mb-2 text-[12.5px] text-error">{error}</div>}
      {omitted && <div className="mb-2 text-[12px] text-text-2">仅显示最近 64KB 输出</div>}
      <div ref={scroller} className="max-h-[60vh] overflow-auto rounded-xl bg-code p-3 font-mono text-[12px] leading-5 whitespace-pre-wrap">
        {text || (error ? "" : "正在读取输出…")}
      </div>
    </Sheet>
  );
}

/** Browse and search the project's files; a file can be read here or referenced in the next message. */
export function FilesSheet({ open, onOpenChange, serverId, directory, sessionId, onReference }: {
  open: boolean; onOpenChange: (open: boolean) => void; serverId: string; directory: string; sessionId?: string; onReference?: (path: string) => void;
}) {
  const [path, setPath] = useState(".");
  const [file, setFile] = useState<string | null>(null);
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<string[] | null>(null);
  const session = sessionId ? `&session=${enc(sessionId)}` : "";
  const base = `${serverPath(serverId)}`;
  const listing = useLoad(open && !file ? () => get<{ files: FileNode[] }>(`${base}/files?directory=${enc(directory)}&path=${enc(path)}${session}`).then((it) => it.files) : null, [open, file, path, directory]);
  const content = useLoad(open && file ? () => get<FileContent>(`${base}/file?directory=${enc(directory)}&path=${enc(file)}${session}`) : null, [open, file, directory]);

  useEffect(() => { if (!open) { setPath("."); setFile(null); setQuery(""); setResults(null); } }, [open]);
  useEffect(() => {
    if (query.trim().length < 2) { setResults(null); return; }
    const controller = new AbortController();
    const timer = window.setTimeout(() => {
      get<{ results: string[] }>(`${base}/search?directory=${enc(directory)}&query=${enc(query.trim())}${session}`, { signal: controller.signal })
        .then((it) => setResults(it.results)).catch(() => undefined);
    }, 250);
    return () => { controller.abort(); window.clearTimeout(timer); };
  }, [query, base, directory, session]);

  const segments = path === "." ? [] : path.split("/").filter(Boolean);
  const nodes = [...(listing.data ?? [])].sort((a, b) => (a.type === "directory" ? 0 : 1) - (b.type === "directory" ? 0 : 1) || a.path.localeCompare(b.path));
  const name = (value: string) => value.replace(/\/+$/, "").substring(value.replace(/\/+$/, "").lastIndexOf("/") + 1) || value;

  return (
    <Sheet open={open} onOpenChange={onOpenChange} title="文件" chat wide
      footer={file && onReference ? <Button block onClick={() => { onReference(file); onOpenChange(false); }}>引用到消息</Button> : undefined}>
      {file ? (
        <div>
          <div className="mb-2 flex items-center gap-1">
            <IconButton icon={Back} label="返回列表" tone="chat" onClick={() => setFile(null)} />
            <span className="min-w-0 flex-1 truncate font-mono text-[13px] text-chat-text-2">{file}</span>
          </div>
          <Status loading={content.loading} error={content.error} empty={false} reload={content.reload} emptyText="" />
          {content.data && (content.data.type === "binary"
            ? <Empty title="当前无法预览此二进制文件" />
            : <HighlightedCode text={content.data.content} language={languageOf(file)} />)}
        </div>
      ) : (
        <div>
          <div className="squircle mb-3 flex h-11 items-center gap-2 rounded-2xl bg-input px-3">
            <Search size={17} className="text-chat-faint" />
            <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="搜索工程文件" className="min-w-0 flex-1 bg-transparent text-[15px] outline-none placeholder:text-chat-faint" />
          </div>
          {results ? (
            results.length === 0 ? <Empty title="没有匹配的文件" /> : results.map((result) => (
              <Row key={result} icon={<File size={17} />} label={result} onClick={() => setFile(result)} />
            ))
          ) : (
            <>
              <div className="mb-2 flex flex-wrap items-center gap-0.5 text-[13px]">
                <button type="button" className="rounded-lg px-1.5 py-1 text-primary" onClick={() => setPath(".")}>项目</button>
                {segments.map((segment, index) => (
                  <span key={index} className="flex items-center">
                    <span className="text-chat-faint">/</span>
                    <button type="button" className="rounded-lg px-1.5 py-1 text-primary" onClick={() => setPath(segments.slice(0, index + 1).join("/"))}>{segment}</button>
                  </span>
                ))}
              </div>
              <Status loading={listing.loading} error={listing.error} empty={!!listing.data && listing.data.length === 0} reload={listing.reload} emptyText="此目录没有文件" />
              {!listing.loading && nodes.map((node) => (
                <Row key={node.path} icon={node.type === "directory" ? <Folder size={17} /> : <File size={17} />} label={name(node.path)}
                  onClick={() => (node.type === "directory" ? setPath(node.path) : setFile(node.path))} />
              ))}
            </>
          )}
        </div>
      )}
    </Sheet>
  );
}

function Row({ icon, label, onClick }: { icon: React.ReactNode; label: string; onClick: () => void }) {
  return (
    <button type="button" onClick={onClick} className={cx("flex w-full items-center gap-3 rounded-xl px-2 py-2.5 text-left hover:bg-bubble/60")}>
      <span className="shrink-0 text-chat-text-2">{icon}</span>
      <span className="min-w-0 flex-1 truncate text-[14.5px]">{label}</span>
    </button>
  );
}
