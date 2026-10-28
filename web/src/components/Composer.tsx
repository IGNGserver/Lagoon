import {
  AgentIcon, AttachmentIcon, ChevronForward, Close, File, Folder, Image as MiuixImage, QoderPlus, QoderSend, QoderStop,
} from "@/components/icons";
import { useEffect, useRef, useState, type ClipboardEvent, type KeyboardEvent } from "react";
import type { ModelChoice } from "@/api/types";
import { importFile, type PendingAttachment } from "@/lib/attachments";
import type { Choices } from "@/lib/choices";
import { readLocal, writeLocal } from "@/lib/local";
import { useStore } from "@/store";
import { AgentPicker, ModelPicker } from "./Pickers";
import { AutoTextarea, Menu, Spinner, cx } from "./ui";

export interface ComposerSubmit {
  text: string;
  agent: string | null;
  model: ModelChoice | null;
  references: { path: string }[];
  attachments: { name: string; mime: string; uri: string }[];
}

interface Choice { agent: string | null; model: ModelChoice | null }

/**
 * The Qoder-style composer: a pill at rest that grows into a card with ＋ / Agent / model / send while
 * focused or holding a draft. The draft, the agent/model choice and picked files stay per conversation.
 * A prompt typed while the agent works steers the running turn; with an empty draft the button stops it.
 */
export function Composer({ serverId, draftKey, choiceKey, choices, ready, offlineHint, running, defaultAgent, defaultModel, references, onRemoveReference,
  onServerFile, onSubmit, onStop, placeholder = "描述你的任务…" }: {
  serverId: string;
  draftKey: string;
  choiceKey: string;
  choices: Choices;
  ready: boolean;
  offlineHint?: string | null;
  running: boolean;
  defaultAgent?: string | null;
  defaultModel?: ModelChoice | null;
  references: string[];
  onRemoveReference: (path: string) => void;
  onServerFile?: () => void;
  onSubmit: (payload: ComposerSubmit) => Promise<boolean>;
  onStop?: () => Promise<void>;
  placeholder?: string;
}) {
  const toast = useStore((state) => state.toast);
  const [text, setText] = useState(() => readLocal(draftKey, ""));
  const [choice, setChoice] = useState<Choice>(() => readLocal<Choice>(choiceKey, { agent: null, model: null }));
  const [attachments, setAttachments] = useState<PendingAttachment[]>([]);
  const [focused, setFocused] = useState(false);
  const [sending, setSending] = useState(false);
  const [stopping, setStopping] = useState(false);
  const [agentPicker, setAgentPicker] = useState(false);
  const [modelPicker, setModelPicker] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);
  const imageInput = useRef<HTMLInputElement>(null);
  const textarea = useRef<HTMLTextAreaElement>(null);

  // Another conversation: load its draft and choice.
  useEffect(() => {
    setText(readLocal(draftKey, ""));
    setAttachments([]);
  }, [draftKey]);
  useEffect(() => { setChoice(readLocal<Choice>(choiceKey, { agent: null, model: null })); }, [choiceKey]);
  useEffect(() => {
    const timer = window.setTimeout(() => writeLocal(draftKey, text), 300);
    return () => window.clearTimeout(timer);
  }, [text, draftKey]);

  const updateChoice = (next: Choice) => { setChoice(next); writeLocal(choiceKey, next.agent || next.model ? next : null); };

  const hasContent = text.trim().length > 0 || attachments.length > 0 || references.length > 0;
  const expanded = focused || text.length > 0 || attachments.length > 0 || references.length > 0;
  const canSend = ready && !sending && hasContent;
  const agentLabel = choice.agent ?? defaultAgent ?? "Agent";
  const model = choice.model ?? defaultModel ?? null;
  const modelInfo = model ? choices.models.find((it) => it.choice.providerId === model.providerId && it.choice.modelId === model.modelId) : null;
  const imageWarning = attachments.some((it) => it.image) && modelInfo?.imageInput === false;
  const hint = offlineHint ?? (sending ? "正在发送…" : imageWarning ? "当前模型不支持图片，发送前请换一个模型" : null);

  const slash = text.startsWith("/") && !/\s/.test(text) ? choices.commands.filter((it) => it.name.toLowerCase().startsWith(text.slice(1).toLowerCase())).slice(0, 8) : [];

  async function add(files: FileList | File[]) {
    let pending = attachments.reduce((sum, it) => sum + it.size, 0);
    const next: PendingAttachment[] = [];
    for (const file of Array.from(files)) {
      const result = await importFile(file, pending);
      if (typeof result === "string") toast(result, "error");
      else { next.push(result); pending += result.size; }
    }
    if (next.length > 0) setAttachments((current) => [...current, ...next]);
  }

  async function send() {
    if (!canSend) return;
    setSending(true);
    const draft = text;
    const ok = await onSubmit({
      text: draft.trim(),
      agent: choice.agent,
      model: choice.model,
      references: references.map((path) => ({ path })),
      attachments: attachments.map(({ name, mime, uri }) => ({ name, mime, uri })),
    });
    setSending(false);
    if (ok) {
      setText((current) => (current === draft ? "" : current));
      writeLocal(draftKey, null);
      setAttachments([]);
    }
  }

  const onKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key !== "Enter" || event.nativeEvent.isComposing) return;
    // Phones insert a newline; keyboards send with Enter (Shift+Enter for a newline).
    if (window.matchMedia("(pointer: coarse)").matches || event.shiftKey) return;
    event.preventDefault();
    void send();
  };

  const onPaste = (event: ClipboardEvent<HTMLTextAreaElement>) => {
    const files = Array.from(event.clipboardData.files);
    if (files.length > 0) { event.preventDefault(); void add(files); }
  };

  const attachButton = (
    <Menu align="start" trigger={<button type="button" aria-label="添加图片或文件" disabled={sending}
      className="inline-flex size-10 shrink-0 items-center justify-center rounded-full text-chat-text hover:bg-bubble disabled:opacity-40"><QoderPlus size={22} /></button>}
      sections={[{ items: [
        { label: "图片", icon: MiuixImage, onSelect: () => imageInput.current?.click() },
        { label: "本机文件", icon: AttachmentIcon, onSelect: () => fileInput.current?.click() },
        ...(onServerFile ? [{ label: "服务器文件", icon: Folder, onSelect: onServerFile }] : []),
      ] }]} />
  );
  const sendButton = running && !hasContent && onStop ? (
    <button type="button" aria-label="停止" disabled={stopping} onClick={async () => { setStopping(true); await onStop(); setStopping(false); }}
      className="inline-flex size-10 shrink-0 items-center justify-center rounded-full bg-code text-chat-text">
      {stopping ? <Spinner size={16} /> : <QoderStop size={14} />}
    </button>
  ) : (
    <button type="button" aria-label="发送" disabled={!canSend} onClick={send}
      className={cx("inline-flex size-10 shrink-0 items-center justify-center rounded-full transition-colors", canSend ? "bg-chat-text text-chat" : "bg-code text-chat-faint")}>
      {sending ? <Spinner size={16} /> : <QoderSend size={20} />}
    </button>
  );

  return (
    <div className="safe-bottom shrink-0 px-3 pb-2 pt-1">
      {slash.length > 0 && (
        <div className="squircle mb-2 overflow-hidden rounded-2xl bg-sheet py-1 shadow-sm ring-1 ring-divider">
          {slash.map((command) => (
            <button key={command.name} type="button" onClick={() => { setText(`/${command.name} `); textarea.current?.focus(); }}
              className="flex w-full items-baseline gap-2 px-4 py-2 text-left hover:bg-bubble">
              <span className="font-mono text-[14px] text-chat-text">/{command.name}</span>
              <span className="truncate text-[12.5px] text-chat-text-2">{command.description}</span>
            </button>
          ))}
        </div>
      )}
      <div className={cx("squircle bg-input p-1.5 transition-[border-radius]", expanded ? "rounded-[24px]" : "rounded-[28px]")}>
        {hint && <p className={cx("px-3 pt-1 text-[12.5px]", sending ? "text-chat-text-2" : "text-warning")}>{hint}</p>}
        {(attachments.length > 0 || references.length > 0) && (
          <div className="scroll-area flex gap-2 overflow-x-auto px-1.5 pb-1 pt-1.5">
            {attachments.map((item) => item.image ? (
              <span key={item.id} className="relative shrink-0">
                <img src={item.uri} alt={item.name} className="squircle size-14 rounded-xl object-cover" />
                <RemoveBadge onClick={() => setAttachments((current) => current.filter((it) => it.id !== item.id))} />
              </span>
            ) : (
              <Chip key={item.id} name={item.name} detail={`${Math.ceil(item.size / 1024)} KB`} onRemove={() => setAttachments((current) => current.filter((it) => it.id !== item.id))} />
            ))}
            {references.map((path) => <Chip key={path} name={path.substring(path.lastIndexOf("/") + 1)} detail="服务器文件" onRemove={() => onRemoveReference(path)} />)}
          </div>
        )}
        <div className="flex items-end gap-1">
          {!expanded && attachButton}
          <AutoTextarea ref={textarea} value={text} onChange={(e) => setText(e.target.value)} onKeyDown={onKeyDown} onPaste={onPaste}
            onFocus={() => setFocused(true)} onBlur={() => setFocused(false)} placeholder={placeholder} maxRows={8}
            enterKeyHint="send"
            className={cx("min-h-10 py-2 text-[16px] leading-[24px] text-chat-text placeholder:text-chat-faint", expanded ? "px-3" : "px-1")} />
          {!expanded && sendButton}
        </div>
        {expanded && (
          <div className="flex items-center gap-1 pt-0.5">
            {attachButton}
            <div className="flex min-w-0 flex-1 items-center gap-1.5">
              <Pill onClick={() => setAgentPicker(true)}><AgentIcon size={15} className="shrink-0" /><span className="truncate">{agentLabel}</span></Pill>
              <Pill onClick={() => setModelPicker(true)} className="min-w-0"><span className="truncate">{model?.label ?? "模型"}</span>
                {model?.variant && <span className="shrink-0 text-chat-faint">· {modelInfo?.variants.find((it) => it.id === model.variant)?.label ?? model.variant}</span>}</Pill>
            </div>
            {sendButton}
          </div>
        )}
      </div>
      <input ref={imageInput} type="file" accept="image/*" multiple hidden onChange={(e) => { if (e.target.files) void add(e.target.files); e.target.value = ""; }} />
      <input ref={fileInput} type="file" multiple hidden onChange={(e) => { if (e.target.files) void add(e.target.files); e.target.value = ""; }} />
      <AgentPicker open={agentPicker} onOpenChange={setAgentPicker} agents={choices.agents} selected={choice.agent} onSelect={(agent) => updateChoice({ ...choice, agent })} />
      <ModelPicker open={modelPicker} onOpenChange={setModelPicker} serverId={serverId} models={choices.models} selected={choice.model ?? null}
        onSelect={(selected) => updateChoice({ ...choice, model: selected })} />
    </div>
  );
}

function Pill({ children, onClick, className }: { children: React.ReactNode; onClick: () => void; className?: string }) {
  return (
    <button type="button" onClick={onClick} className={cx("squircle inline-flex h-8 max-w-[45%] items-center gap-1 rounded-full bg-bubble px-3 text-[13px] text-chat-text", className)}>
      {children}<ChevronForward size={13} className="shrink-0 rotate-90 text-chat-faint" />
    </button>
  );
}

function Chip({ name, detail, onRemove }: { name: string; detail: string; onRemove: () => void }) {
  return (
    <span className="squircle relative inline-flex h-14 shrink-0 items-center gap-2 rounded-xl bg-bubble px-3 pr-6">
      <File size={18} className="text-chat-text-2" />
      <span className="flex flex-col">
        <span className="max-w-36 truncate text-[13px] text-chat-text">{name}</span>
        <span className="text-[11.5px] text-chat-text-2">{detail}</span>
      </span>
      <RemoveBadge onClick={onRemove} />
    </span>
  );
}

function RemoveBadge({ onClick }: { onClick: () => void }) {
  return (
    <button type="button" aria-label="移除" onClick={onClick} className="absolute -right-1 -top-1 inline-flex size-5 items-center justify-center rounded-full bg-chat-text text-chat">
      <Close size={12} />
    </button>
  );
}
