/**
 * TypeScript port of shared/.../TranscriptRows.kt (itself a port of the official OpenCode session
 * timeline projection). contract/transcript-rows.json pins both versions to the same output: the hub
 * test checks the Kotlin original, src/transcript/rows.test.ts checks this file.
 */
import type { Attachment, Message, MessagePart, Notice } from "@/api/types";

export const PENDING_PREFIX = "pending:";

export interface TranscriptRow {
  key: string;
  kind: string;
  title: string;
  subtitle: string;
  args: string[];
  status: string;
  text: string;
  attachments: Attachment[];
  meta: string;
  copyText: string | null;
  target: string | null;
  detailOf: string | null;
  bodyOf: string | null;
  source: MessagePart | null;
}

interface ToolSection { label: string; text: string; code: boolean; copyText: string | null }

const TIME_GAP_MS = 30 * 60 * 1000;
const MAX_DIFF_FILES = 10;
const HIDDEN_TOOLS = new Set(["todowrite"]);
const EDIT_TOOLS = new Set(["edit", "write", "patch", "apply_patch"]);
const FINISHED_TOOLS = new Set(["completed", "error"]);
const ACTIVE_STATUSES = new Set(["running", "pending"]);
export const SHELL_TOOLS = new Set(["shell", "bash", "execute"]);
export const SUBAGENT_TOOLS = new Set(["subagent", "task"]);
export const CHUNK = 4096;

function row(key: string, kind: string, fields: Partial<TranscriptRow> = {}): TranscriptRow {
  return {
    key, kind, title: "", subtitle: "", args: [], status: "", text: "", attachments: [], meta: "",
    copyText: null, target: null, detailOf: null, bodyOf: null, source: null, ...fields,
  };
}

const isBlank = (value: string | null | undefined) => !value || value.trim().length === 0;

/** MarkdownBlocks.chunks: bounded pieces (4096 characters or 80 lines) that never split a surrogate pair. */
export function chunks(text: string): string[] {
  if (text.length === 0) return [];
  const result: string[] = [];
  let start = 0;
  while (start < text.length) {
    let end = Math.min(start + CHUNK, text.length);
    let line = 0;
    let at = start;
    while (at < end) {
      if (text[at] === "\n" && ++line >= 80) { end = at + 1; break; }
      at++;
    }
    const code = text.charCodeAt(end - 1);
    if (end < text.length && code >= 0xd800 && code <= 0xdbff) end--;
    result.push(text.substring(start, end));
    start = end;
  }
  return result;
}

export function partDisplayable(part: MessagePart): boolean {
  switch (part.type) {
    case "text":
    case "reasoning":
      return !isBlank(part.text);
    case "tool":
      return !isBlank(part.tool) || !isBlank(part.input) || !isBlank(part.output) || !isBlank(part.error);
    case "file":
      return !isBlank(part.path);
    default:
      return false;
  }
}

/** Official visibility: notices and shell turns always render; `hidden` records never do. */
export function messageDisplayable(message: Message): boolean {
  switch (message.role) {
    case "hidden":
      return false;
    case "notice":
    case "shell":
      return true;
    default:
      return !isBlank(message.error) || message.retry != null || message.parts.some(partDisplayable);
  }
}

class Turn {
  readonly entries: Message[] = [];
  constructor(readonly id: string, readonly created: number, readonly user: Message | null = null, readonly shell: Message | null = null) {}
}

export function build(messages: Message[], expanded: Set<string> = new Set(), working = false, revertMessageId: string | null = null): TranscriptRow[] {
  const visible = messages.filter((it) => messageDisplayable(it) && (revertMessageId == null || it.id < revertMessageId));
  const turns: Turn[] = [];
  const users = new Set<string>();
  const leading: Message[] = [];
  let current: Turn | null = null;
  for (const message of visible) {
    switch (message.role) {
      case "notice":
        if (current) current.entries.push(message); else leading.push(message);
        break;
      case "shell":
        current = new Turn(message.id, message.created, null, message);
        turns.push(current);
        break;
      case "user":
        if (!users.has(message.id)) {
          users.add(message.id);
          current = new Turn(message.id, message.created, message);
          turns.push(current);
        }
        break;
      case "assistant": {
        const turn: Turn | null = current;
        if (turn != null && turn.shell == null) turn.entries.push(message);
        else {
          current = new Turn(message.id, message.created);
          current.entries.push(message);
          turns.push(current);
        }
        break;
      }
    }
  }
  const rows: TranscriptRow[] = [];
  for (const message of leading) rows.push(noticeRow(message));
  let previousEnd = 0;
  turns.forEach((turn, index) => {
    if (index === 0 || turn.created - previousEnd >= TIME_GAP_MS) rows.push(row(`time:${turn.id}`, "time", { text: formatTime(turn.created) }));
    const members = [turn.user, turn.shell, ...turn.entries].filter((it): it is Message => it != null);
    previousEnd = Math.max(...members.map((it) => it.completedAt ?? it.created));
    rows.push(...turnRows(turn, `turn:${turn.id}`, expanded, working && index === turns.length - 1));
  });
  return withUniqueKeys(rows);
}

/** Rows of one expandable group, for the detail sheet: the group's items and, opened, their bodies. */
export function details(messages: Message[], key: string, working = false, revertMessageId: string | null = null): TranscriptRow[] {
  if (isBlank(key)) return [];
  const first = build(messages, new Set([key]), working, revertMessageId);
  const children = new Set(first.filter((it) => it.detailOf === key).map((it) => it.key));
  const rows = children.size === 0 ? first : build(messages, new Set([key, ...children]), working, revertMessageId);
  return rows.filter((it) => it.detailOf === key);
}

function partRef(message: Message, index: number, part: MessagePart): string {
  return `${message.id}/${isBlank(part.id) ? `#${index}` : part.id}`;
}

function withUniqueKeys(rows: TranscriptRow[]): TranscriptRow[] {
  const seen = new Map<string, number>();
  return rows.map((it) => {
    const count = (seen.get(it.key) ?? 0) + 1;
    seen.set(it.key, count);
    return count === 1 ? it : { ...it, key: `${it.key}~${count}` };
  });
}

function noticeRow(message: Message): TranscriptRow {
  const notice: Notice = message.notice ?? { label: "", detail: "", items: [], error: false, running: false };
  const detail = [notice.detail, ...notice.items].filter((it) => !isBlank(it)).join("、");
  return row(`notice:${message.id}`, "note", {
    title: notice.label, text: detail, status: notice.error ? "error" : notice.running ? "running" : "", target: notice.target ?? null,
  });
}

function interrupted(message: Message): boolean {
  if (message.finish === "aborted") return true;
  const type = message.errorType?.toLowerCase();
  return type != null && (type.includes("abort") || type.includes("interrupt"));
}

function turnRows(turn: Turn, turnKey: string, expanded: Set<string>, working: boolean): TranscriptRow[] {
  const rows: TranscriptRow[] = [];
  const user = turn.user;
  if (user) {
    const text = user.parts.filter((it) => it.type === "text").map((it) => it.text).join("\n").trim();
    const attachments = distinctBy([
      ...user.parts.filter((it) => it.type === "file").map((it) => ({ url: it.path, mime: it.mime, name: it.title })),
      ...user.parts.flatMap((it) => it.attachments),
    ], (it) => it.url);
    const meta = [
      user.id.startsWith(PENDING_PREFIX) ? "等待连接后发送" : user.queued ? "排队中" : null,
      user.agent, user.model?.label, afterSpace(formatTime(user.created)),
    ].filter((it): it is string => it != null && !isBlank(it)).join(" · ");
    rows.push(row(`user:${user.id}`, "user", {
      text, attachments, meta, copyText: isBlank(text) ? null : text, status: user.queued ? "queued" : "",
    }));
  }
  const shell = turn.shell;
  if (shell) shell.parts.forEach((part, index) => rows.push(...toolRows(partRef(shell, index, part), part, expanded)));

  const assistants = turn.entries.filter((it) => it.role === "assistant");
  const body: TranscriptRow[] = [];
  const entries: [string, MessagePart][] = assistants.flatMap((message) =>
    message.parts.map((part, index) => [partRef(message, index, part), part] as [string, MessagePart])
      .filter(([, part]) => partDisplayable(part) && !(part.type === "tool" && (HIDDEN_TOOLS.has(part.tool) || (part.tool === "question" && !FINISHED_TOOLS.has(part.status))))));
  for (const notice of turn.entries.filter((it) => it.role === "notice")) body.push(noticeRow(notice));
  let lastActivity = -1;
  entries.forEach(([, part], index) => { if (part.type === "tool" || part.type === "reasoning") lastActivity = index; });
  const nonFinal = new Set(assistants.filter((it) => it.finish === "tool-calls" || it.finish === "tool_calls").map((it) => it.id));
  const finalParts = entries.filter(([ref, part], index) =>
    part.type === "text" && index > lastActivity && ![...nonFinal].some((id) => ref.startsWith(`${id}/`)));
  const finalRefs = new Set(finalParts.map(([ref]) => ref));
  const process = entries.filter(([ref]) => !finalRefs.has(ref));
  if (working) body.push(...runningTurnRows(process, expanded));
  else if (process.length > 0) body.push(...toolGroupRows(turnKey, process, expanded));
  if (finalParts.length > 0) {
    const text = finalParts.map(([, part]) => part.text.trim()).join("\n\n");
    body.push(row(`answer:${finalParts[0][0]}`, "text", { text }));
  }
  const compaction = turn.entries.some((it) => it.type === "compaction");
  let dividerShown = false;
  for (const message of turn.entries) {
    if (interrupted(message) && !dividerShown) {
      dividerShown = true;
      if (!compaction) body.push(row(`interrupted:${message.id}`, "divider", { text: "本轮已中断" }));
    }
  }
  rows.push(...body);

  const last = assistants[assistants.length - 1] ?? null;
  if (working && last?.retry != null) rows.push(row(`retry:${last.id}`, "note", { title: "正在重试", text: last.retry, status: "running" }));
  else if (last?.error != null && !interrupted(last)) rows.push(row(`error:${last.id}`, "error", { text: unwrapError(last.error) }));
  if (last) {
    const finished = assistants.every((it) => it.completedAt != null);
    const first = turn.user?.created ?? turn.created;
    const duration = finished && !working ? Math.max(...assistants.map((it) => it.completedAt ?? 0)) - first : 0;
    const meta = [last.agent, last.model?.label, durationText(duration), afterSpace(formatTime(last.completedAt ?? last.created))]
      .filter((it): it is string => it != null && !isBlank(it)).join(" · ");
    const copy = body.filter((it) => it.kind === "text").map((it) => it.text).join("\n\n");
    rows.push(row(`meta:${last.id}`, "meta", { meta, copyText: isBlank(copy) ? null : copy }));
  }
  if (!working) {
    const edits = assistants.flatMap((message) => message.parts.map((part, index) => [partRef(message, index, part), part] as [string, MessagePart]))
      .filter(([, it]) => EDIT_TOOLS.has(it.tool) && (!isBlank(it.patch) || it.files.length > 0 || !isBlank(inputString(it, "path"))));
    if (edits.length > 0) {
      const key = `diff:${assistants[assistants.length - 1].id}`;
      const files = distinct(edits.flatMap(([, edit]) => (edit.files.length > 0 ? edit.files : [inputString(edit, "path")])).filter((it) => !isBlank(it)));
      rows.push(row(key, "diff-summary", { title: `本轮改动 ${files.length} 个文件`, subtitle: files.slice(0, 3).map(baseName).join("、") }));
      if (expanded.has(key)) {
        for (const [ref, edit] of edits.slice(0, MAX_DIFF_FILES)) {
          const path = orElse(orElse(edit.files.join("、"), inputString(edit, "path")), "改动");
          const patch = orElse(edit.patch, edit.output);
          const fileKey = `${key}:${ref}`;
          rows.push(row(fileKey, "diff-file", { title: path, detailOf: key }));
          chunks(patch).forEach((chunk, index) => rows.push(row(`${fileKey}:${index}`, "tool-body", {
            title: index === 0 ? "改动" : "", text: chunk, copyText: index === 0 ? patch : null, detailOf: key, bodyOf: fileKey,
          })));
        }
      }
    }
  }
  if (working && turn.shell == null && !body.some((it) => it.kind !== "meta" && it.kind !== "note")) {
    rows.push(row(`${turnKey}:thinking`, "thinking", { text: "运行中…" }));
  }
  return rows;
}

function runningTurnRows(entries: [string, MessagePart][], expanded: Set<string>): TranscriptRow[] {
  const rows: TranscriptRow[] = [];
  let index = 0;
  while (index < entries.length) {
    const [ref, part] = entries[index];
    if (part.type === "text") {
      if (!isBlank(part.text)) rows.push(row(`process:${ref}`, "process-output", { text: part.text, source: part }));
      index++;
      continue;
    }
    const group: [string, MessagePart][] = [];
    while (index < entries.length && entries[index][1].type !== "text") { group.push(entries[index]); index++; }
    if (group.length === 0) { index++; continue; }
    const tools = group.filter(([, it]) => it.type === "tool").length;
    rows.push(...activityGroupRows(`batch:${group[0][0]}`, group, expanded, tools > 0 ? `调用了 ${tools} 次工具` : "思考中"));
  }
  return rows;
}

function toolGroupRows(turnKey: string, entries: [string, MessagePart][], expanded: Set<string>): TranscriptRow[] {
  return activityGroupRows(`${turnKey}:activities`, entries, expanded, `共处理 ${entries.filter(([, it]) => it.type !== "text").length} 个操作`);
}

function activityGroupRows(key: string, entries: [string, MessagePart][], expanded: Set<string>, title: string): TranscriptRow[] {
  const tools = entries.filter(([, it]) => it.type === "tool").map(([, it]) => it);
  const reasonings = entries.filter(([, it]) => it.type === "reasoning").map(([, it]) => it);
  const status = tools.some((it) => ACTIVE_STATUSES.has(it.status)) || reasonings.some((it) => ACTIVE_STATUSES.has(it.status)) ? "running"
    : tools.some((it) => it.status === "error") ? "error" : "completed";
  const counts = new Map<string, number>();
  for (const tool of tools) counts.set(toolCategory(tool), (counts.get(toolCategory(tool)) ?? 0) + 1);
  const summary = [...[...counts].map(([label, count]) => `${count} 个${label}`), ...(reasonings.length > 0 ? [`${reasonings.length} 个思考`] : [])].join("、");
  const failed = tools.find((it) => it.status === "error" && !isBlank(it.error));
  const preview = failed ? unwrapError(failed.error).slice(0, 140) : tools.length === 1 ? toolInfo(tools[0])[1] : "";
  const single = tools.length === 1 && SUBAGENT_TOOLS.has(tools[0].tool) ? tools[0].target ?? null : null;
  const head = row(key, "tool-group", { title, subtitle: preview, meta: summary, status, target: single });
  if (!expanded.has(key)) return [head];
  const children: TranscriptRow[] = [];
  entries.forEach(([ref, part], position) => {
    const occurrence = entries.slice(0, position).filter(([other]) => other === ref).length;
    const itemKey = `${key}:${ref}${occurrence === 0 ? "" : `~${occurrence + 1}`}`;
    if (part.type === "reasoning") {
      children.push(row(itemKey, "reasoning", { title: "深度思考", status: part.status, detailOf: key, source: part }));
      chunks(part.text.trim()).forEach((chunk, index) => children.push(row(`${itemKey}:body:${index}`, "reasoning-body", { text: chunk, detailOf: key, bodyOf: itemKey })));
    } else if (part.type === "text") {
      children.push(row(itemKey, "process-output", { text: part.text, detailOf: key, source: part }));
    } else if (part.type === "notice") {
      children.push(row(itemKey, "note", { title: part.title, text: part.text, status: part.status, target: part.target ?? null, detailOf: key }));
    } else if (part.type === "file") {
      children.push(row(itemKey, "attachment", { attachments: [{ url: part.path, mime: part.mime, name: part.title }], detailOf: key, source: part }));
    } else {
      const [toolTitle, subtitle] = toolInfo(part);
      children.push(row(itemKey, "tool", {
        title: toolTitle, subtitle, args: toolArgs(part), status: part.status, attachments: part.attachments,
        target: SUBAGENT_TOOLS.has(part.tool) ? part.target ?? null : null, detailOf: key, source: part,
      }));
      for (const section of toolSections(part)) {
        chunks(section.text).forEach((chunk, index) => children.push(row(`${itemKey}:${section.label}:${index}`, "tool-body", {
          title: index === 0 ? section.label : "", text: chunk, copyText: index === 0 ? section.copyText : null, detailOf: key, bodyOf: itemKey,
        })));
      }
    }
  });
  return [head, ...children];
}

function toolCategory(part: MessagePart): string {
  if (SHELL_TOOLS.has(part.tool)) return "Shell";
  if (EDIT_TOOLS.has(part.tool)) return "编辑";
  if (SUBAGENT_TOOLS.has(part.tool)) return "子任务";
  switch (part.tool) {
    case "read": return "读取";
    case "list": return "列表";
    case "glob":
    case "grep": return "搜索";
    case "webfetch": return "网页";
    case "websearch": return "网络搜索";
    case "question": return "提问";
    case "skill": return "技能";
    default: return orElse(part.tool, "工具");
  }
}

function toolRows(ref: string, part: MessagePart, expanded: Set<string>): TranscriptRow[] {
  const key = `tool:${ref}`;
  const [title, subtitle] = toolInfo(part);
  const head = row(key, "tool", {
    title, subtitle, args: toolArgs(part), status: part.status, attachments: part.attachments,
    target: SUBAGENT_TOOLS.has(part.tool) ? part.target ?? null : null, source: part,
  });
  if (!expanded.has(key)) return [head];
  return [head, ...toolSections(part).flatMap((section) => chunks(section.text).map((chunk, index) => row(`${key}:${section.label}:${index}`, "tool-body", {
    title: index === 0 ? section.label : "", text: chunk, copyText: index === 0 ? section.copyText : null, detailOf: key, bodyOf: key,
  })))];
}

export function toolSections(part: MessagePart): ToolSection[] {
  const input = inputObject(part);
  const sections: ToolSection[] = [];
  const section = (label: string, text: string, copyText: string | null = null, code = true) => sections.push({ label, text, copyText, code });
  if (SHELL_TOOLS.has(part.tool)) {
    const cmd = command(part);
    if (!isBlank(cmd)) section("命令", cmd, cmd);
    if (!isBlank(part.output)) section("输出", part.output, part.output);
    if (!isBlank(part.error)) section("错误", part.error, part.error);
  } else if (EDIT_TOOLS.has(part.tool)) {
    const path = inputString(part, "path");
    if (!isBlank(path)) section("文件路径", path, null, false);
    const patch = orElse(orElse(part.patch, str(input, "patchText")), part.output);
    if (part.tool === "write" && !isBlank(str(input, "content"))) section("内容", str(input, "content"));
    if (part.tool === "edit" && isBlank(part.patch)) {
      if (!isBlank(str(input, "oldString"))) section("原内容", str(input, "oldString"));
      if (!isBlank(str(input, "newString"))) section("新内容", str(input, "newString"));
    }
    if (!isBlank(patch)) section("改动", patch, patch);
    if (!isBlank(part.error)) section("错误", part.error, part.error);
  } else {
    if (part.tool === "read") {
      const path = inputString(part, "path");
      if (!isBlank(path)) section("文件路径", path, null, false);
    }
    if (part.tool !== "read" && !isBlank(part.input)) section("输入", part.input, part.input);
    if (!isBlank(part.output)) section(part.tool === "read" ? "内容" : "输出", part.output, part.output);
    if (!isBlank(part.error)) section("错误", part.error, part.error);
    if (sections.length === 0 && !isBlank(part.input) && !isBlank(part.tool)) section("输入", part.input, part.input);
  }
  return sections.filter((it) => !isBlank(it.text));
}

export function toolInfo(part: MessagePart): [string, string] {
  const input = inputObject(part);
  const value = (key: string) => str(input, key);
  const path = () => orElse(orElse(value("path"), value("filePath")), part.path);
  if (SUBAGENT_TOOLS.has(part.tool)) return ["子任务", [value("agent"), orElse(value("description"), part.title)].filter((it) => !isBlank(it)).join(" · ")];
  if (SHELL_TOOLS.has(part.tool)) return ["命令行", command(part).split("\n")[0] ?? ""];
  switch (part.tool) {
    case "read": return ["读取文件", baseName(path())];
    case "list": return ["列出目录", path()];
    case "glob": return ["搜索文件", value("pattern")];
    case "grep": return ["搜索内容", orElse(value("pattern"), part.title)];
    case "webfetch": return ["抓取网页", orElse(value("url"), part.path)];
    case "websearch": return ["网络搜索", orElse(value("query"), part.title)];
    case "edit": return ["编辑文件", baseName(path())];
    case "write": return ["写入文件", baseName(path())];
    case "patch":
    case "apply_patch": {
      const names = part.files.map(baseName);
      return ["应用补丁", (names.length > 0 ? names : isBlank(value("files")) ? [] : [value("files")]).join("、")];
    }
    case "question": return ["提问", part.title];
    case "skill": return ["技能", orElse(value("name"), part.title)];
    default: return [orElse(part.tool, "工具"), orElse(part.title, part.path)];
  }
}

function toolArgs(part: MessagePart): string[] {
  const input = inputObject(part);
  if (!input) return [];
  const args: string[] = [];
  if (part.tool === "read") {
    if ("offset" in input) args.push(`offset=${str(input, "offset")}`);
    if ("limit" in input) args.push(`limit=${str(input, "limit")}`);
  }
  if (part.tool === "grep" && !isBlank(str(input, "include"))) args.push(`include=${str(input, "include")}`);
  return args;
}

/** Strips a JSON error envelope down to its human-readable message (official `unwrapErrorMessage`). */
export function unwrapError(message: string): string {
  const text = message.replace(/^Error: /, "").trim();
  let json = parseObject(text);
  if (!json) {
    const start = text.indexOf("{");
    const end = text.lastIndexOf("}");
    if (start >= 0 && start < end) json = parseObject(text.substring(start, end + 1));
  }
  if (!json) return text;
  const error = typeof json.error === "object" && json.error !== null ? (json.error as Record<string, unknown>) : null;
  const errorText = orElse(error ? orElse(str(error, "message"), str(error, "type")) : "", error ? str(error, "code") : "");
  return orElse(orElse(orElse(errorText, str(json, "message")), str(json, "error")), text);
}

function parseObject(text: string): Record<string, unknown> | null {
  try {
    const value = JSON.parse(text);
    return typeof value === "object" && value !== null && !Array.isArray(value) ? value : null;
  } catch {
    return null;
  }
}

function command(part: MessagePart): string {
  return orElse(orElse(str(inputObject(part), "command"), part.input.trimStart().startsWith("{") ? "" : part.input), part.text);
}

function inputObject(part: MessagePart): Record<string, unknown> | null {
  const text = part.input.trim();
  return text.startsWith("{") ? parseObject(text) : null;
}

function inputString(part: MessagePart, key: string): string {
  const input = inputObject(part);
  const value = input ? orElse(str(input, key), key === "path" ? str(input, "filePath") : "") : "";
  return orElse(value, key === "path" ? part.path : "");
}

/** org.json `optString`: absent and null are empty, other values print as JSON does. */
function str(json: Record<string, unknown> | null, key: string): string {
  if (!json) return "";
  const value = json[key];
  if (value == null) return "";
  if (typeof value === "string") return value === "null" ? "" : value;
  if (typeof value === "object") return JSON.stringify(value);
  return String(value);
}

const orElse = (value: string, fallback: string) => (isBlank(value) ? fallback : value);
const baseName = (path: string) => path.substring(path.lastIndexOf("/") + 1);
const afterSpace = (text: string) => (text.includes(" ") ? text.substring(text.indexOf(" ") + 1) : text);
const distinct = <T>(items: T[]) => [...new Set(items)];
function distinctBy<T>(items: T[], key: (item: T) => string): T[] {
  const seen = new Set<string>();
  return items.filter((it) => (seen.has(key(it)) ? false : (seen.add(key(it)), true)));
}

const pad = (value: number) => String(value).padStart(2, "0");

/** `M月d日 HH:mm` in the local time zone. */
export function formatTime(millis: number): string {
  const date = new Date(millis > 0 ? millis : Date.now());
  return `${date.getMonth() + 1}月${date.getDate()}日 ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function durationText(ms: number): string {
  if (ms <= 0) return "";
  if (ms < 60_000) return `用时 ${Math.floor(ms / 1000)} 秒`;
  return `用时 ${Math.floor(ms / 60_000)} 分 ${Math.floor((ms % 60_000) / 1000)} 秒`;
}
