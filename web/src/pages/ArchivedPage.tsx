import { UnarchiveIcon } from "@/components/icons";
import { setArchived } from "@/components/SessionDialogs";
import { StatusGlyph } from "@/components/StatusGlyph";
import { Empty, IconButton, PageHeader } from "@/components/ui";
import { byActivity, displayTitle, relativeTime, statusOf } from "@/lib/presentation";
import { back, chatPath, navigate } from "@/lib/router";
import { useShallow } from "zustand/react/shallow";
import { servers as serverList, useStore } from "@/store";

/** Archived root sessions of every server; restorable where the server supports it. */
export function ArchivedPage() {
  const all = useStore(useShallow(serverList));
  const entries = all.flatMap((server) => server.sessions.filter((it) => !it.parentId && it.archived).map((session) => ({ server, session })))
    .sort((a, b) => byActivity(a.session, b.session));
  return (
    <div className="flex h-full min-h-0 flex-col bg-bg">
      <PageHeader title="已归档" onBack={() => back()} className="bg-bg" />
      <div className="scroll-area min-h-0 flex-1 pb-[calc(16px+var(--safe-bottom))]">
        {entries.length === 0 ? <Empty title="没有已归档的会话" detail="只显示中枢最近读取到的会话目录中的归档会话" /> : (
          <div className="squircle mx-3 overflow-hidden rounded-[22px] bg-surface [&>*+*]:border-t [&>*+*]:border-divider">
            {entries.map(({ server, session }) => (
              <div key={`${server.serverId}:${session.id}`} className="flex items-center">
                <button type="button" onClick={() => navigate(chatPath(server.serverId, session.id))} className="flex min-w-0 flex-1 items-center gap-3 px-4 py-3 text-left">
                  <span className="flex size-6 items-center justify-center"><StatusGlyph status={statusOf(server, session.id)} unseen={server.notices.some((it) => it.sessionId === session.id && !it.viewed)} /></span>
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-[16px]">{displayTitle(session)}</span>
                    <span className="block truncate text-[12.5px] text-text-2">{server.profile.name} · {relativeTime(session.updated || session.created)}</span>
                  </span>
                </button>
                {server.capabilities.restore && server.connected && (
                  <IconButton icon={UnarchiveIcon} label="取消归档" className="mr-2" onClick={() => void setArchived(server.serverId, session.id, false)} />
                )}
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
