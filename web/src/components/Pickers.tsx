import { Ok, Search } from "@/components/icons";
import { useMemo, useState } from "react";
import type { AgentChoice, ModelChoice, ModelInfo } from "@/api/types";
import { modelKey, useVisibleModels } from "@/lib/choices";
import { useStore } from "@/store";
import { Sheet, cx } from "./ui";

export function AgentPicker({ open, onOpenChange, agents, selected, onSelect }: {
  open: boolean; onOpenChange: (open: boolean) => void; agents: AgentChoice[]; selected: string | null; onSelect: (name: string | null) => void;
}) {
  return (
    <Sheet open={open} onOpenChange={onOpenChange} title="Agent" chat>
      <div className="space-y-1">
        <Option title="默认" detail="使用服务器为会话选择的 Agent" selected={selected == null} onClick={() => { onSelect(null); onOpenChange(false); }} />
        {agents.map((agent) => (
          <Option key={agent.name} title={agent.name} detail={agent.description} selected={selected === agent.name}
            onClick={() => { onSelect(agent.name); onOpenChange(false); }} />
        ))}
        {agents.length === 0 && <p className="py-6 text-center text-[14px] text-chat-text-2">服务器没有提供 Agent 列表</p>}
      </div>
    </Sheet>
  );
}

/** Models grouped by provider, recently used first; the selected model's thinking variants below the list. */
export function ModelPicker({ open, onOpenChange, serverId, models, selected, onSelect }: {
  open: boolean; onOpenChange: (open: boolean) => void; serverId: string; models: ModelInfo[]; selected: ModelChoice | null; onSelect: (model: ModelChoice | null) => void;
}) {
  const [query, setQuery] = useState("");
  const visible = useVisibleModels(serverId, models);
  const recentModels = useStore((state) => state.ui.recentModels?.[serverId]);
  const recent = useMemo(() => recentModels ?? [], [recentModels]);
  const updateUi = useStore((state) => state.updateUi);
  const groups = useMemo(() => {
    const text = query.trim().toLowerCase();
    const filtered = visible.filter((it) => !text || it.choice.label.toLowerCase().includes(text) || it.key.toLowerCase().includes(text) || it.providerName.toLowerCase().includes(text));
    const recentModels = recent.map((key) => filtered.find((it) => it.key === key)).filter((it): it is ModelInfo => it != null).slice(0, 5);
    const byProvider = new Map<string, ModelInfo[]>();
    for (const model of filtered) byProvider.set(model.providerName, [...(byProvider.get(model.providerName) ?? []), model]);
    return [...(recentModels.length > 0 && !text ? [["最近使用", recentModels] as [string, ModelInfo[]]] : []), ...byProvider];
  }, [visible, recent, query]);
  const current = selected ? models.find((it) => it.key === modelKey(selected)) : null;

  const choose = (info: ModelInfo, variant?: string | null) => {
    onSelect({ ...info.choice, variant: variant ?? null });
    updateUi((ui) => ({ ...ui, recentModels: { ...ui.recentModels, [serverId]: [info.key, ...(ui.recentModels?.[serverId] ?? []).filter((it) => it !== info.key)].slice(0, 8) } }));
    if (info.variants.length === 0 || variant !== undefined) onOpenChange(false);
  };

  return (
    <Sheet open={open} onOpenChange={onOpenChange} title="模型" chat wide>
      <div className="squircle sticky top-0 z-10 mb-3 flex h-11 items-center gap-2 rounded-2xl bg-input px-3">
        <Search size={17} className="text-chat-faint" />
        <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="搜索模型" className="min-w-0 flex-1 bg-transparent text-[15px] outline-none placeholder:text-chat-faint" />
      </div>
      {current && current.variants.length > 0 && (
        <div className="mb-4">
          <p className="mb-2 px-1 text-[12.5px] text-chat-text-2">思考强度 · {current.choice.label}</p>
          <div className="flex flex-wrap gap-2">
            {[{ id: "", label: "默认" }, ...current.variants].map((variant) => (
              <button key={variant.id} type="button" onClick={() => choose(current, variant.id || null)}
                className={cx("squircle h-9 rounded-full px-4 text-[14px]", (selected?.variant ?? "") === variant.id ? "bg-chat-text text-chat" : "bg-bubble text-chat-text")}>
                {variant.label}
              </button>
            ))}
          </div>
        </div>
      )}
      <Option title="默认" detail="使用会话当前的模型" selected={selected == null} onClick={() => { onSelect(null); onOpenChange(false); }} />
      {groups.map(([provider, list]) => (
        <section key={provider} className="mt-3">
          <h4 className="mb-1 px-1 text-[12.5px] text-chat-text-2">{provider}</h4>
          {list.map((info) => (
            <Option key={`${provider}:${info.key}`} title={info.choice.label} detail={info.key}
              selected={selected != null && modelKey(selected) === info.key} onClick={() => choose(info)} />
          ))}
        </section>
      ))}
      {visible.length === 0 && <p className="py-6 text-center text-[14px] text-chat-text-2">没有可用的模型{models.length > 0 ? "，请在设置中调整模型可见性" : ""}</p>}
    </Sheet>
  );
}

function Option({ title, detail, selected, onClick }: { title: string; detail?: string; selected: boolean; onClick: () => void }) {
  return (
    <button type="button" onClick={onClick} className={cx("squircle flex w-full items-center gap-3 rounded-2xl px-3 py-2.5 text-left", selected ? "bg-bubble" : "hover:bg-bubble/60")}>
      <span className="min-w-0 flex-1">
        <span className="block truncate text-[15px] text-chat-text">{title}</span>
        {detail && <span className="block truncate text-[12.5px] text-chat-text-2">{detail}</span>}
      </span>
      {selected && <Ok size={17} className="shrink-0 text-chat-text" />}
    </button>
  );
}
