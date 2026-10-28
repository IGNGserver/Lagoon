import {
  Copy, File, Info, Ok, QoderCheck, QoderChevron, QoderError, QoderWarning,
} from "@/components/icons";
import { memo, useState, type ReactNode } from "react";
import type { Attachment } from "@/api/types";
import { highlight, renderMarkdown } from "@/lib/markdown";
import type { TranscriptRow } from "@/transcript/rows";
import { Spinner, cx } from "./ui";

export function copyText(text: string) {
  void navigator.clipboard?.writeText(text).catch(() => undefined);
}

function CopyButton({ text, label = "复制" }: { text: string; label?: string }) {
  const [done, setDone] = useState(false);
  return (
    <button type="button" aria-label={label} title={label}
      onClick={() => { copyText(text); setDone(true); window.setTimeout(() => setDone(false), 1200); }}
      className="inline-flex size-7 items-center justify-center rounded-full text-chat-faint hover:bg-bubble hover:text-chat-text">
      {done ? <Ok size={14} /> : <Copy size={14} />}
    </button>
  );
}

export const Markdown = memo(function Markdown({ text, className }: { text: string; className?: string }) {
  return <div className={cx("markdown", className)} dangerouslySetInnerHTML={{ __html: renderMarkdown(text) }} />;
});

export function ToolStatus({ status }: { status: string }) {
  if (status === "running" || status === "pending") return <Spinner size={15} className="shrink-0 text-chat-text-2" />;
  if (status === "error") return <QoderError size={15} className="shrink-0 text-error" />;
  if (status === "warning") return <QoderWarning size={15} className="shrink-0 text-warning" />;
  if (status === "unverified") return <Info size={15} className="shrink-0 text-chat-faint" />;
  return <QoderCheck size={15} className="shrink-0 text-chat-success" />;
}

export function Attachments({ items }: { items: Attachment[] }) {
  if (items.length === 0) return null;
  return (
    <div className="mt-2 flex flex-wrap gap-2">
      {items.map((item) => item.mime.startsWith("image/") && item.url.startsWith("data:") ? (
        <img key={item.url} src={item.url} alt={item.name || "图片"} className="squircle max-h-40 max-w-[220px] rounded-xl object-cover" />
      ) : (
        <span key={item.url} className="squircle inline-flex max-w-[240px] items-center gap-1.5 rounded-xl bg-code px-2.5 py-1.5 text-[12.5px] text-chat-text-2">
          <File size={14} className="shrink-0" /><span className="truncate">{item.name || item.url.substring(item.url.lastIndexOf("/") + 1)}</span>
        </span>
      ))}
    </div>
  );
}

/** A code block of a tool body; patches are coloured line by line. */
export function CodeBody({ text, title, copy, patch }: { text: string; title?: string; copy?: string | null; patch?: boolean }) {
  const diff = patch ?? /^(---|\+\+\+|@@) /m.test(text);
  return (
    <div className="squircle overflow-hidden rounded-xl bg-code">
      {(title || copy) && (
        <div className="flex items-center justify-between px-3 pt-1.5 text-[12px] text-chat-text-2">
          <span>{title}</span>
          {copy && <CopyButton text={copy} />}
        </div>
      )}
      <pre className="code-block scroll-area max-h-[60vh] overflow-x-auto px-3 py-2 text-chat-text">
        {diff ? text.split("\n").map((line, index) => (
          <span key={index} className={cx("block", line.startsWith("+") && !line.startsWith("+++") ? "diff-add" : line.startsWith("-") && !line.startsWith("---") ? "diff-del" : line.startsWith("@@") ? "diff-hunk" : undefined)}>{line || " "}</span>
        )) : text}
      </pre>
    </div>
  );
}

export function HighlightedCode({ text, language }: { text: string; language?: string }) {
  return <pre className="code-block scroll-area overflow-x-auto rounded-xl bg-code px-3 py-2 text-chat-text"><code dangerouslySetInnerHTML={{ __html: highlight(text, language) }} /></pre>;
}

interface RowProps {
  row: TranscriptRow;
  onOpenGroup: (row: TranscriptRow) => void;
  onOpenChild: (sessionId: string) => void;
}

/** One main-timeline row. Group-like rows open the activity sheet; bodies never render inline here. */
export const TranscriptRowView = memo(function TranscriptRowView({ row, onOpenGroup, onOpenChild }: RowProps) {
  switch (row.kind) {
    case "time":
      return <div className="py-3 text-center text-[12px] text-chat-faint">{row.text}</div>;
    case "user":
      return (
        <div className="flex flex-col items-end py-1.5 pl-10">
          {row.text && (
            <div className={cx("squircle max-w-full whitespace-pre-wrap rounded-[20px] bg-bubble px-4 py-2.5 text-[15.5px] leading-relaxed [overflow-wrap:anywhere]", row.status === "queued" && "opacity-70")}>{row.text}</div>
          )}
          <Attachments items={row.attachments} />
          {row.meta && <div className="mt-1 flex items-center gap-1 px-1 text-[11.5px] text-chat-faint">{row.meta}{row.copyText && <CopyButton text={row.copyText} />}</div>}
        </div>
      );
    case "text":
      return <Markdown text={row.text} className="py-2 text-[15.5px] text-chat-text" />;
    case "process-output":
      return <Markdown text={row.text} className="py-1.5 text-[14.5px] text-chat-text-2" />;
    case "tool-group":
    case "diff-summary":
      return (
        <button type="button" onClick={() => onOpenGroup(row)}
          className="group my-1 flex w-full items-center gap-2 rounded-xl py-1.5 text-left text-[14px] text-chat-text-2 hover:text-chat-text">
          {row.kind === "tool-group" ? <ToolStatus status={row.status} /> : <File size={15} className="shrink-0" />}
          <span className="shrink-0 font-medium">{row.title}</span>
          {(row.subtitle || row.meta) && <span className="min-w-0 truncate text-chat-faint">{row.subtitle || row.meta}</span>}
          <QoderChevron size={15} className="ml-auto shrink-0 opacity-60 group-hover:opacity-100" />
        </button>
      );
    case "tool":
      return (
        <button type="button" onClick={() => onOpenGroup(row)} className="my-1 flex w-full items-center gap-2 rounded-xl py-1.5 text-left text-[14px] text-chat-text-2 hover:text-chat-text">
          <ToolStatus status={row.status} />
          <span className="shrink-0 font-medium">{row.title}</span>
          <span className="min-w-0 truncate font-mono text-[12.5px] text-chat-faint">{row.subtitle}</span>
          <QoderChevron size={15} className="ml-auto shrink-0 opacity-60" />
        </button>
      );
    case "note":
      return (
        <div className={cx("flex items-center justify-center gap-1.5 py-1.5 text-center text-[12.5px]", row.status === "error" ? "text-error" : "text-chat-faint")}>
          {row.status === "running" && <Spinner size={12} />}
          <span>{row.title}{row.text && ` · ${row.text}`}</span>
          {row.target && <button type="button" className="text-primary" onClick={() => onOpenChild(row.target!)}>查看</button>}
        </div>
      );
    case "divider":
      return (
        <div className="flex items-center gap-3 py-3 text-[12px] text-chat-faint">
          <span className="h-px flex-1 bg-divider" />{row.text}<span className="h-px flex-1 bg-divider" />
        </div>
      );
    case "thinking":
      return <div className="py-2 text-[14.5px]"><span className="shimmer-text">{row.text}</span></div>;
    case "error":
      return (
        <div className="squircle my-2 flex gap-2 rounded-2xl bg-error-subtle px-3.5 py-2.5 text-[14px] text-error">
          <QoderWarning size={16} className="mt-0.5 shrink-0" /><span className="[overflow-wrap:anywhere]">{row.text}</span>
        </div>
      );
    case "meta":
      return (
        <div className="flex items-center gap-1 pb-3 pt-0.5 text-[11.5px] text-chat-faint">
          <span>{row.meta}</span>{row.copyText && <CopyButton text={row.copyText} label="复制回答" />}
        </div>
      );
    default:
      return null;
  }
});

/** The activity sheet's rows: items with collapsible bodies (opened on tap), like the phone's detail sheet. */
export function DetailRows({ rows, onOpenChild }: { rows: TranscriptRow[]; onOpenChild: (sessionId: string) => void }) {
  const [open, setOpen] = useState<Set<string>>(() => new Set(rows.filter((it) => it.kind === "diff-file").map((it) => it.key)));
  const toggle = (key: string) => setOpen((current) => {
    const next = new Set(current);
    if (next.has(key)) next.delete(key); else next.add(key);
    return next;
  });
  const items: ReactNode[] = [];
  for (const row of rows) {
    if (row.bodyOf) {
      if (!open.has(row.bodyOf)) continue;
      items.push(row.kind === "reasoning-body"
        ? <div key={row.key} className="whitespace-pre-wrap pb-2 pl-7 text-[13.5px] leading-relaxed text-chat-text-2">{row.text}</div>
        : <div key={row.key} className="pb-2 pl-7"><CodeBody text={row.text} title={row.title} copy={row.copyText} /></div>);
      continue;
    }
    switch (row.kind) {
      case "tool":
      case "reasoning":
      case "diff-file":
        items.push(
          <button key={row.key} type="button" onClick={() => toggle(row.key)} className="flex w-full items-center gap-2 py-2.5 text-left text-[14px]">
            {row.kind === "diff-file" ? <File size={15} className="shrink-0 text-chat-text-2" /> : <ToolStatus status={row.kind === "reasoning" && row.status !== "running" ? "completed" : row.status} />}
            <span className="shrink-0 font-medium">{row.title}</span>
            <span className="min-w-0 flex-1 truncate font-mono text-[12.5px] text-chat-faint">{[row.subtitle, ...row.args].filter(Boolean).join(" ")}</span>
            {row.target && <span role="button" tabIndex={0} className="shrink-0 text-[12.5px] text-primary" onClick={(event) => { event.stopPropagation(); onOpenChild(row.target!); }}>打开子会话</span>}
            <QoderChevron size={15} className={cx("shrink-0 text-chat-faint transition-transform", open.has(row.key) && "rotate-90")} />
          </button>,
        );
        if (row.attachments.length > 0 && open.has(row.key)) items.push(<div key={`${row.key}:files`} className="pl-7"><Attachments items={row.attachments} /></div>);
        break;
      case "process-output":
        items.push(<Markdown key={row.key} text={row.text} className="py-1.5 text-[14px] text-chat-text-2" />);
        break;
      case "note":
        items.push(<div key={row.key} className="py-1.5 text-[13px] text-chat-faint">{row.title}{row.text && ` · ${row.text}`}</div>);
        break;
      case "attachment":
        items.push(<Attachments key={row.key} items={row.attachments} />);
        break;
    }
  }
  return <div className="divide-y divide-divider/60">{items}</div>;
}
