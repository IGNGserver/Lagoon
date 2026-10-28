import { ChevronForward, Folder } from "@/components/icons";
import { useState } from "react";
import { errorText, post, serverPath } from "@/api/http";
import type { Session } from "@/api/types";
import { Composer, type ComposerSubmit } from "@/components/Composer";
import { ProjectSheet } from "@/components/ProjectSheet";
import { Button, Empty, Menu, PageHeader } from "@/components/ui";
import { useChoices } from "@/lib/choices";
import { choiceKey, draftKey } from "@/lib/local";
import { draftProject } from "@/lib/presentation";
import { back, chatPath, navigate, serverEditPath } from "@/lib/router";
import { useShallow } from "zustand/react/shallow";
import { servers as serverList, useStore } from "@/store";

/** A blank conversation: the session is created when the first message is sent. */
export function DraftPage({ serverId, projectId }: { serverId?: string; projectId?: string }) {
  const all = useStore(useShallow(serverList));
  const scopes = useStore((state) => state.scopes);
  const loaded = useStore((state) => state.loaded);
  const toast = useStore((state) => state.toast);
  // Until the user picks one, follow the link's server or the first connected one (the hub state may still be arriving).
  const [chosenServer, setChosenServer] = useState<string | undefined>(serverId);
  const server = all.find((it) => it.serverId === chosenServer) ?? all.find((it) => it.connected) ?? all[0] ?? null;
  const [chosenProject, setChosenProject] = useState<string | null>(projectId ?? null);
  const [projects, setProjects] = useState(false);
  const project = server ? server.projects.find((it) => it.id === chosenProject) ?? draftProject(server, scopes[server.serverId] ?? null) : null;
  const choices = useChoices(server?.serverId, project?.directory, !!project && server?.connected === true);

  if (!server) {
    if (!loaded) return <div className="h-full bg-chat" />;
    return (
      <div className="flex h-full flex-col bg-chat">
        <PageHeader title="新会话" onBack={() => back()} className="bg-chat" />
        <Empty title="还没有服务器" action={<Button onClick={() => navigate(serverEditPath(null))}>添加服务器</Button>} />
      </div>
    );
  }

  async function submit(payload: ComposerSubmit): Promise<boolean> {
    if (!server || !project) { toast("先选择一个项目", "error"); return false; }
    try {
      const result = await post<{ session: Session }>(`${serverPath(server.serverId)}/sessions`, { directory: project.directory, title: "", prompt: payload });
      navigate(chatPath(server.serverId, result.session.id), { replace: true });
      return true;
    } catch (error) {
      toast(errorText(error, "新建会话失败"), "error");
      return false;
    }
  }

  return (
    <div className="flex h-full min-h-0 flex-col bg-chat text-chat-text">
      <PageHeader title="新会话" onBack={() => back()} className="bg-chat" subtitle={
        <span className="inline-flex items-center gap-1">
          {all.length > 1 ? (
            <Menu align="start" trigger={<button type="button" className="inline-flex items-center gap-0.5 text-primary">{server.profile.name}<ChevronForward size={13} className="rotate-90" /></button>}
              sections={[{ items: all.map((it) => ({ label: it.profile.name, checked: it.serverId === server.serverId, onSelect: () => { setChosenServer(it.serverId); setChosenProject(null); } })) }]} />
          ) : server.profile.name}
          <span>·</span>
          <button type="button" className="inline-flex items-center gap-0.5 text-primary" onClick={() => setProjects(true)}>{project?.name ?? "选择项目"}<ChevronForward size={13} className="rotate-90" /></button>
        </span>
      } />
      <div className="flex min-h-0 flex-1 flex-col items-center justify-center px-8 text-center">
        <p className="text-[22px] font-medium">要做点什么？</p>
        <p className="mt-2 flex items-center gap-1.5 text-[13.5px] text-chat-text-2">
          <Folder size={14} />{project ? `会在「${project.name}」中新建，发出第一条消息后才会创建会话` : "先点标题下方选择一个项目"}
        </p>
        {!server.connected && <p className="mt-2 text-[13px] text-warning">服务器未连接</p>}
      </div>
      <div className="mx-auto w-full max-w-[860px]">
        <Composer serverId={server.serverId} draftKey={draftKey(server.serverId, null)} choiceKey={choiceKey(server.serverId, null)} choices={choices}
          ready={server.connected && !!project} offlineHint={!server.connected ? "离线 · 草稿保留，恢复连接后可发送" : !project ? "先选择一个项目" : null}
          running={false} references={[]} onRemoveReference={() => undefined} onSubmit={submit} />
      </div>
      <ProjectSheet open={projects} onOpenChange={(open) => {
        setProjects(open);
        if (!open) {
          const state = useStore.getState();
          const home = state.homeServer;
          if (home) { setChosenServer(home); setChosenProject(state.scopes[home] ?? null); }
        }
      }} />
    </div>
  );
}
