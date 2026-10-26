import type { ConversationOp, Message } from "@/api/types";

/**
 * Applies one `conv.ops` batch from the hub (see hub Conversation.kt `TranscriptDiff`): survivors keep
 * their place, new messages are appended, and an explicit `order` wins when the hub sent one.
 */
export function applyOps(messages: Message[], ops: ConversationOp[], order?: string[]): Message[] {
  const list = [...messages];
  const indexOf = (id: string) => list.findIndex((it) => it.id === id);
  for (const op of ops) {
    switch (op.op) {
      case "put": {
        const index = indexOf(op.message.id);
        if (index >= 0) list[index] = op.message; else list.push(op.message);
        break;
      }
      case "del": {
        const index = indexOf(op.id);
        if (index >= 0) list.splice(index, 1);
        break;
      }
      case "part": {
        const index = indexOf(op.messageId);
        if (index < 0) break;
        const message = list[index];
        const parts = [...message.parts];
        parts[op.index] = op.part;
        list[index] = { ...message, parts };
        break;
      }
      case "append": {
        const index = indexOf(op.messageId);
        if (index < 0) break;
        const message = list[index];
        const part = message.parts[op.index];
        if (!part) break;
        const parts = [...message.parts];
        parts[op.index] = { ...part, [op.field]: part[op.field] + op.text };
        list[index] = { ...message, parts };
        break;
      }
    }
  }
  if (!order) return list;
  const byId = new Map(list.map((it) => [it.id, it]));
  const ordered = order.map((id) => byId.get(id)).filter((it): it is Message => it != null);
  // Anything the order does not mention (a local optimistic row) keeps its place at the end.
  const listed = new Set(order);
  return [...ordered, ...list.filter((it) => !listed.has(it.id))];
}

/**
 * A fresh window from the hub replaces the newest part of the transcript; older pages this tab loaded
 * itself (they are older than everything in the window) are kept in front of it.
 */
export function mergeWindow(existing: Message[], window: Message[]): Message[] {
  if (window.length === 0) return existing.length === 0 ? window : existing;
  const ids = new Set(window.map((it) => it.id));
  const oldest = window[0].created;
  const older = existing.filter((it) => !ids.has(it.id) && it.created < oldest);
  return [...older, ...window];
}

/** Prepends an older page, dropping anything already loaded. */
export function prependOlder(existing: Message[], older: Message[]): Message[] {
  const ids = new Set(existing.map((it) => it.id));
  return [...older.filter((it) => !ids.has(it.id)), ...existing];
}
