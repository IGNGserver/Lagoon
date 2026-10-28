import {
  AddFolder, Back, Delete, Folder, GridView, More, Search,
} from "@/components/icons";
import { useEffect, useMemo, useState } from "react";
import { del, enc, errorText, get, post, serverPath } from "@/api/http";
import type { FileNode, ServerView } from "@/api/types";
import { crumbs, parentPath, resolveSessionProject, visibleOnHome } from "@/lib/presentation";
import { useShallow } from "zustand/react/shallow";
import { servers as serverList, useStore } from "@/store";
import { Button, Empty, IconButton, Menu, Sheet, Spinner, Switch, TextField, cx } from "./ui";

/**
 * The home title's sheet: pick the project the list is scoped to (or every session), and register a
 * server directory as a project through a remote directory browser.
 */
export function ProjectSheet({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  const all = useStore(useShallow(serverList));
  const homeServer = useStore((state) => state.homeServer);
  const scopes = useStore((state) => state.scopes);
  const setScope = useStore((state) => state.setScope);
  const setHomeServer = useStore((state) => state.setHomeServer);
  const [browsing, setBrowsing] = useState<ServerView | null>(null);
  const shown = homeServer ? all.filter((it) => it.serverId === homeServer) : all;

  useEffect(() => { if (!open) setBrowsing(null); }, [open]);

  const choose = (serverId: string | null, projectId: string | null) => {
    if (serverId == null) setHomeServer(null);
    else { setHomeServer(serverId); setScope(serverId, projectId); }
    onOpenChange(false);
  };

  return (
    <Sheet open={open} onOpenChange={onOpenChange} title={browsing ? `添加项目 · ${browsing.profile.name}` : "项目"}>
      {browsing ? (
        <DirectoryBrowser server={browsing} onBack={() => setBrowsing(null)} onAdded={(projectId) => choose(browsing.serverId, projectId)} />
      ) : (
        <div className="space-y-5">
          {all.length > 1 && (
            <button type="button" onClick={() => choose(null, null)}
              className={cx("squircle flex w-full items-center gap-3 rounded-2xl px-4 py-3.5 text-left", homeServer == null ? "bg-primary-subtle text-primary" : "bg-surface-2")}>
              <GridView size={20} /> <span className="flex-1 text-[16px]">全部服务器</span>
            </button>
          )}
          {shown.map((server) => {
            const counts = new Map<string, number>();
            server.sessions.filter(visibleOnHome).forEach((session) => {
              const project = resolveSessionProject(session, server.projects);
              if (project) counts.set(project.id, (counts.get(project.id) ?? 0) + 1);
            });
            const scope = homeServer === server.serverId ? scopes[server.serverId] ?? null : undefined;
            return (
              <section key={server.serverId}>
                <h3 className="mb-2 px-1 text-[13px] text-text-2">{server.profile.name}</h3>
                <div className="squircle overflow-hidden rounded-[22px] bg-surface-2 [&>*+*]:border-t [&>*+*]:border-divider">
                  <ProjectRow label="全部会话" count={server.sessions.filter(visibleOnHome).length} selected={scope === null} onClick={() => choose(server.serverId, null)} />
                  {server.projects.map((project) => (
                    <ProjectRow key={project.id} label={project.name} detail={project.directory} count={counts.get(project.id) ?? 0}
                      selected={scope === project.id} onClick={() => choose(server.serverId, project.id)}
                      onRemove={async () => {
                        try { await del(`${serverPath(server.serverId)}/projects/${enc(project.id)}`); }
                        catch (error) { useStore.getState().toast(errorText(error), "error"); }
                      }} />
                  ))}
                  <button type="button" disabled={!server.connected} onClick={() => setBrowsing(server)}
                    className="flex w-full items-center gap-3 px-4 py-3.5 text-left text-primary disabled:opacity-45">
                    <AddFolder size={20} /><span className="text-[15.5px]">添加项目</span>
                  </button>
                </div>
              </section>
            );
          })}
        </div>
      )}
    </Sheet>
  );
}

function ProjectRow({ label, detail, count, selected, onClick, onRemove }: {
  label: string; detail?: string; count: number; selected: boolean; onClick: () => void; onRemove?: () => void;
}) {
  return (
    <div className={cx("flex items-center", selected && "bg-primary-subtle")}>
      <button type="button" onClick={onClick} className="flex min-w-0 flex-1 items-center gap-3 px-4 py-3 text-left">
        <Folder size={20} className={selected ? "text-primary" : "text-text-2"} />
        <span className="min-w-0 flex-1">
          <span className={cx("block truncate text-[15.5px]", selected && "font-medium text-primary")}>{label}</span>
          {detail && <span className="block truncate text-[12.5px] text-text-2">{detail}</span>}
        </span>
        <span className="text-[13px] text-text-2">{count}</span>
      </button>
      {onRemove && (
        <Menu trigger={<IconButton icon={More} label="项目操作" className="mr-1" />}
          sections={[{ items: [{ label: "从列表移除", icon: Delete, danger: true, onSelect: onRemove }] }]} />
      )}
    </div>
  );
}

function DirectoryBrowser({ server, onBack, onAdded }: { server: ServerView; onBack: () => void; onAdded: (projectId: string) => void }) {
  const [path, setPath] = useState<string | null>(null);
  const [entries, setEntries] = useState<FileNode[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [hidden, setHidden] = useState(false);
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<string[]>([]);
  const [adding, setAdding] = useState(false);

  const load = async (target: string | null) => {
    setLoading(true);
    setError(null);
    setQuery("");
    try {
      const data = await get<{ path: string; entries: FileNode[] }>(`${serverPath(server.serverId)}/browse${target ? `?path=${enc(target)}` : ""}`);
      setPath(data.path);
      setEntries(data.entries);
    } catch (failure) {
      setError(errorText(failure, "无法读取目录"));
    } finally {
      setLoading(false);
    }
  };
  useEffect(() => { void load(null); }, [server.serverId]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (!path || query.trim().length < 2) { setResults([]); return; }
    const controller = new AbortController();
    const timer = window.setTimeout(async () => {
      try {
        const data = await get<{ results: string[] }>(`${serverPath(server.serverId)}/browse/search?path=${enc(path)}&query=${enc(query.trim())}`, { signal: controller.signal });
        setResults(data.results);
      } catch {
        // Typing on; the next query replaces this one.
      }
    }, 250);
    return () => { controller.abort(); window.clearTimeout(timer); };
  }, [query, path, server.serverId]);

  const directories = useMemo(() => entries.filter((it) => it.type === "directory")
    .filter((it) => hidden || !baseName(it.path).startsWith(".")), [entries, hidden]);

  async function add() {
    if (!path) return;
    setAdding(true);
    try {
      const data = await post<{ project: { id: string } }>(`${serverPath(server.serverId)}/projects`, { directory: path });
      onAdded(data.project.id);
    } catch (failure) {
      useStore.getState().toast(errorText(failure), "error");
    } finally {
      setAdding(false);
    }
  }

  const absolute = (entry: string) => (entry.startsWith("/") || /^[A-Za-z]:/.test(entry) ? entry : `${(path ?? "").replace(/\/+$/, "")}/${entry}`);

  return (
    <div className="space-y-3">
      <div className="flex items-center gap-1">
        <IconButton icon={Back} label="返回项目列表" onClick={onBack} />
        <div className="scroll-area flex min-w-0 flex-1 items-center gap-0.5 overflow-x-auto whitespace-nowrap text-[13.5px]">
          {path && crumbs(path).map(([label, target], index) => (
            <span key={target} className="flex items-center">
              {index > 1 && <span className="px-0.5 text-text-3">/</span>}
              <button type="button" className="rounded-lg px-1.5 py-1 text-primary hover:bg-primary-subtle" onClick={() => void load(target)}>{label}</button>
            </span>
          ))}
        </div>
      </div>
      <TextField placeholder="在此目录下搜索" value={query} onChange={(e) => setQuery(e.target.value)} />
      <div className="flex items-center justify-between px-1 text-[13.5px] text-text-2">
        <span>显示隐藏目录</span>
        <Switch checked={hidden} onChange={setHidden} label="显示隐藏目录" />
      </div>
      {loading ? (
        <div className="flex justify-center py-10"><Spinner size={22} /></div>
      ) : error ? (
        <Empty title={error} action={<Button variant="secondary" onClick={() => void load(path)}>重试</Button>} />
      ) : (
        <div className="squircle overflow-hidden rounded-[22px] bg-surface-2 [&>*+*]:border-t [&>*+*]:border-divider">
          {path && path !== "/" && !query && (
            <button type="button" className="flex w-full items-center gap-3 px-4 py-3 text-left" onClick={() => void load(parentPath(path))}>
              <Folder size={18} className="text-text-2" /><span className="text-[15px]">..</span>
            </button>
          )}
          {(query.trim().length >= 2 ? results.map((it) => ({ path: it, type: "directory" })) : directories).map((entry) => (
            <button key={entry.path} type="button" className="flex w-full items-center gap-3 px-4 py-3 text-left" onClick={() => void load(absolute(entry.path))}>
              {query ? <Search size={18} className="text-text-2" /> : <Folder size={18} className="text-text-2" />}
              <span className="min-w-0 flex-1 truncate text-[15px]">{query ? entry.path : baseName(entry.path)}</span>
            </button>
          ))}
          {!query && directories.length === 0 && <p className="px-4 py-6 text-center text-[14px] text-text-2">没有子目录</p>}
        </div>
      )}
      <Button block size="lg" disabled={!path || adding} onClick={add}>{adding ? <Spinner className="text-on-primary" /> : "添加此目录为项目"}</Button>
    </div>
  );
}

const baseName = (path: string) => path.replace(/\/+$/, "").substring(path.replace(/\/+$/, "").lastIndexOf("/") + 1) || path;
