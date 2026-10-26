import { ChevronLeft, File, Folder, Search } from "lucide-react";
import { useEffect, useState } from "react";
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

export function ChildrenSheet({ open, onOpenChange, serverId, sessionId, tasks, onOpen }: {
  open: boolean; onOpenChange: (open: boolean) => void; serverId: string; sessionId: string; tasks: Record<string, TaskState>; onOpen: (id: string) => void;
}) {
  const { data, error, loading, reload } = useLoad(open ? () => get<{ sessions: Session[] }>(`${sessionPath(serverId, sessionId)}/children`).then((it) => it.sessions) : null, [open, serverId, sessionId]);
  return (
    <Sheet open={open} onOpenChange={onOpenChange} title="子任务" chat>
      <Status loading={loading} error={error} empty={!!data && data.length === 0} reload={reload} emptyText="暂无子任务" />
      <div className="space-y-2">
        {data?.map((child) => (
          <button key={child.id} type="button" onClick={() => { onOpenChange(false); onOpen(child.id); }}
            className="squircle flex w-full items-center gap-3 rounded-2xl bg-bubble px-4 py-3 text-left">
            <span className="min-w-0 flex-1 truncate text-[15px]">{displayTitle(child)}</span>
            {tasks[child.id]?.active && <span className="flex shrink-0 items-center gap-1.5 text-[12.5px] text-primary"><Spinner size={12} />运行中</span>}
          </button>
        ))}
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
            <IconButton icon={ChevronLeft} label="返回列表" tone="chat" onClick={() => setFile(null)} />
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
