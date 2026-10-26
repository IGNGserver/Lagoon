/** Ports of the Android presentation helpers (SessionPresentation.kt, HomeScope.kt, HomeSections.kt). */
import type { Project, ServerView, Session, SessionStatus, TaskSummary } from "@/api/types";

const placeholderTitle = /^(New session|Child session) - \d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/;

/** Official title rule: only `session.title`; generated placeholders read 新会话 / 子会话. */
export function displayTitle(session: Pick<Session, "title" | "parentId">): string {
  const value = session.title;
  if (value.trim()) {
    const match = placeholderTitle.exec(value);
    if (!match) return value;
    return match[1] === "Child session" ? "子会话" : "新会话";
  }
  return session.parentId ? "子会话" : "新会话";
}

export const activityAt = (session: Session) => (session.updated > 0 ? session.updated : session.created);
export const byActivity = (a: Session, b: Session) => activityAt(b) - activityAt(a) || a.id.localeCompare(b.id);
export const visibleOnHome = (session: Session) => !session.parentId && !session.archived;

export function normalizedDirectory(directory: string): string {
  let path = directory.trim().replaceAll("\\", "/").replace(/\/+$/, "");
  if (!path) path = directory.startsWith("/") ? "/" : "";
  return /^[A-Za-z]:\//.test(path) ? path.toLowerCase() : path;
}

const projectDirectories = (project: Project) => [project.directory, ...project.sandboxes].filter((it) => it.trim());

/** Server identities and execution directories are separate: a worktree is still the same project. */
export function resolveSessionProject(session: Session, projects: Project[]): Project | null {
  if (session.projectId) {
    const byId = projects.find((it) => it.id === session.projectId);
    if (byId) return byId;
  }
  const directory = normalizedDirectory(session.directory);
  const exact = projects.find((project) => projectDirectories(project).some((it) => normalizedDirectory(it) === directory));
  if (exact) return exact;
  let best: Project | null = null;
  let length = -1;
  for (const project of projects) {
    for (const dir of projectDirectories(project)) {
      const root = normalizedDirectory(dir);
      if (root && (directory === root || directory.startsWith(root === "/" ? root : `${root}/`)) && root.length > length) {
        best = project;
        length = root.length;
      }
    }
  }
  return best;
}

export const STATUS_LABELS: Record<SessionStatus, string> = {
  WAITING_PERMISSION: "等待授权", WAITING_QUESTION: "等待回答", WAITING_SERVER: "等待服务器处理", RUNNING: "运行中",
  BACKGROUND_RUNNING: "后台运行中", FAILED: "失败", COMPLETED: "已完成", UNKNOWN: "状态待确认", NONE: "",
};

export const statusOf = (server: ServerView, sessionId: string): SessionStatus => server.statuses[sessionId] ?? "NONE";

export const isWaiting = (status: SessionStatus) => status === "WAITING_PERMISSION" || status === "WAITING_QUESTION";

/** A home row: the session and the server it lives on. */
export interface HomeEntry { server: ServerView; session: Session }
export interface HomeSection { key: string; title: string; entries: HomeEntry[] }
export type HomeGrouping = "DATE" | "PROJECT" | "STATUS";
export const GROUPING_LABELS: Record<HomeGrouping, string> = { DATE: "日期", PROJECT: "项目", STATUS: "状态" };

export const entryKey = (entry: HomeEntry) => `${entry.server.serverId}\u0000${entry.session.id}`;

/** Root sessions of the home list in activity order, optionally scoped to one project. */
export function homeEntries(servers: ServerView[], scope: string | null): HomeEntry[] {
  return servers.flatMap((server) => server.sessions.filter(visibleOnHome)
    .filter((session) => scope == null || resolveSessionProject(session, server.projects)?.id === scope)
    .map((session) => ({ server, session })))
    .sort((a, b) => byActivity(a.session, b.session));
}

function startOfDay(time: number): number {
  const date = new Date(time);
  date.setHours(0, 0, 0, 0);
  return date.getTime();
}

/** Grouping never reorders sessions; pinned ones are lifted into a leading 置顶 section. */
export function homeSections(grouping: HomeGrouping, entries: HomeEntry[], pinned: (entry: HomeEntry) => boolean, now = Date.now()): HomeSection[] {
  const top = entries.filter(pinned);
  const rest = entries.filter((it) => !pinned(it));
  const sections: HomeSection[] = [];
  const push = (key: string, title: string, entry: HomeEntry) => {
    let section = sections.find((it) => it.key === key);
    if (!section) { section = { key, title, entries: [] }; sections.push(section); }
    section.entries.push(entry);
  };
  const today = startOfDay(now);
  const day = 24 * 60 * 60 * 1000;
  for (const entry of rest) {
    if (grouping === "DATE") {
      const at = startOfDay(activityAt(entry.session));
      if (at >= today) push("date:today", "今天", entry);
      else if (at >= today - day) push("date:yesterday", "昨天", entry);
      else if (at > today - 7 * day) push("date:week", "近 7 天", entry);
      else if (at > today - 30 * day) push("date:month", "近 30 天", entry);
      else push("date:earlier", "更早", entry);
    } else if (grouping === "PROJECT") {
      const project = resolveSessionProject(entry.session, entry.server.projects);
      push(project ? `project:${entry.server.serverId}:${project.id}` : "project:unassigned", project?.name ?? "未归入项目", entry);
    } else {
      const status = statusOf(entry.server, entry.session.id);
      const bucket = status === "WAITING_PERMISSION" || status === "WAITING_QUESTION" || status === "WAITING_SERVER" ? 0
        : status === "RUNNING" || status === "BACKGROUND_RUNNING" ? 1 : status === "FAILED" || status === "COMPLETED" ? 2 : 3;
      push(`status:${bucket}`, ["需要处理", "运行中", "未读结果", "其他"][bucket], entry);
    }
  }
  if (grouping === "STATUS") sections.sort((a, b) => a.key.localeCompare(b.key));
  return [...(top.length > 0 ? [{ key: "pinned", title: "置顶", entries: top }] : []), ...sections];
}

/** Where a new session starts: the scoped project, otherwise the project of the latest root session. */
export function draftProject(server: ServerView, scope: string | null): Project | null {
  if (scope) {
    const scoped = server.projects.find((it) => it.id === scope);
    if (scoped) return scoped;
  }
  for (const session of [...server.sessions].filter((it) => !it.parentId).sort(byActivity)) {
    const project = resolveSessionProject(session, server.projects);
    if (project) return project;
  }
  return server.projects[0] ?? null;
}

/** The root of [sessionId]'s family on [server]. */
export function rootOf(server: ServerView, sessionId: string): string {
  let current = sessionId;
  for (let i = 0; i < 32; i++) {
    const parent = server.parents[current] ?? server.sessions.find((it) => it.id === current)?.parentId;
    if (!parent) return current;
    current = parent;
  }
  return current;
}

/** Sessions whose root is [rootId] (including it), for requests that belong to a conversation. */
export function familyOf(server: ServerView, rootId: string, sessionId: string): boolean {
  return rootOf(server, sessionId) === rootId;
}

export function mergeSummaries(summaries: TaskSummary[]): { running: number; completed: number; waiting: number; failed: number; processing: number } {
  return summaries.reduce((total, it) => ({
    running: total.running + it.running, completed: total.completed + it.completed, waiting: total.waiting + it.waiting,
    failed: total.failed + it.failed, processing: total.processing + it.processing,
  }), { running: 0, completed: 0, waiting: 0, failed: 0, processing: 0 });
}

export function countLine(summary: { running: number; completed: number; waiting: number; failed: number; processing: number }): string {
  return [`${summary.running} 运行中`, `${summary.completed} 未读完成`, summary.waiting > 0 ? `${summary.waiting} 待回复` : null,
    summary.processing > 0 ? `${summary.processing} 处理中` : null, summary.failed > 0 ? `${summary.failed} 失败` : null]
    .filter(Boolean).join(" · ");
}

/** `/home/me/repo` → `~/repo` when the home directory is known. */
export function displayPath(path: string, home?: string | null): string {
  const root = home?.replace(/\/+$/, "");
  if (!root || root.length <= 1) return path;
  if (path === root) return "~";
  return path.startsWith(`${root}/`) ? `~${path.substring(root.length)}` : path;
}

export function crumbs(path: string): [string, string][] {
  const parts = path.trim().replace(/\/+$/, "").split("/").filter(Boolean);
  return [["/", "/"], ...parts.map((name, index) => [name, `/${parts.slice(0, index + 1).join("/")}`] as [string, string])];
}

export const parentPath = (path: string) => path.replace(/\/+$/, "").replace(/\/[^/]*$/, "") || "/";

export function relativeTime(time: number, now = Date.now()): string {
  const diff = Math.max(0, now - time);
  if (diff < 60_000) return "刚刚";
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`;
  const date = new Date(time);
  return `${date.getMonth() + 1}月${date.getDate()}日`;
}
