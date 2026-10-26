import { useState } from "react";
import { del, errorText, post, sessionPath } from "@/api/http";
import type { Session } from "@/api/types";
import { displayTitle } from "@/lib/presentation";
import { useStore } from "@/store";
import { Button, ConfirmDialog, Sheet, Spinner, TextField } from "./ui";

export function RenameDialog({ serverId, session, onClose }: { serverId: string; session: Session; onClose: () => void }) {
  const [title, setTitle] = useState(displayTitle(session));
  const [busy, setBusy] = useState(false);
  const toast = useStore((state) => state.toast);
  async function save() {
    setBusy(true);
    try {
      await post(`${sessionPath(serverId, session.id)}/rename`, { title: title.trim() });
      onClose();
    } catch (error) {
      toast(errorText(error), "error");
    } finally {
      setBusy(false);
    }
  }
  return (
    <Sheet open onOpenChange={(open) => !open && !busy && onClose()} title="重命名会话"
      footer={<div className="flex gap-3"><Button variant="secondary" block onClick={onClose} disabled={busy}>取消</Button>
        <Button block disabled={busy || !title.trim()} onClick={save}>{busy ? <Spinner className="text-on-primary" /> : "保存"}</Button></div>}>
      <TextField label="会话名称" value={title} onChange={(e) => setTitle(e.target.value)} autoFocus onKeyDown={(e) => { if (e.key === "Enter" && title.trim()) void save(); }} />
    </Sheet>
  );
}

export function DeleteDialog({ serverId, session, onClose, onDeleted }: { serverId: string; session: Session; onClose: () => void; onDeleted?: () => void }) {
  const [busy, setBusy] = useState(false);
  const toast = useStore((state) => state.toast);
  return (
    <ConfirmDialog open onOpenChange={(open) => !open && onClose()} title="删除此会话？" danger busy={busy} confirm="永久删除"
      message={`会话消息将从服务器永久删除。\n${displayTitle(session)}`}
      onConfirm={async () => {
        setBusy(true);
        try {
          await del(sessionPath(serverId, session.id));
          onClose();
          onDeleted?.();
        } catch (error) {
          toast(errorText(error), "error");
          setBusy(false);
        }
      }} />
  );
}

export async function setArchived(serverId: string, sessionId: string, archived: boolean): Promise<boolean> {
  try {
    await post(`${sessionPath(serverId, sessionId)}/archive`, { archived });
    useStore.getState().toast(archived ? "已归档" : "已取消归档");
    return true;
  } catch (error) {
    useStore.getState().toast(errorText(error), "error");
    return false;
  }
}
