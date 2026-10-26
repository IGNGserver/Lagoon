import { useEffect, useState } from "react";
import { enc, get, serverPath } from "@/api/http";
import type { AgentChoice, CommandChoice, ModelChoice, ModelInfo } from "@/api/types";
import { useStore } from "@/store";

export interface Choices { agents: AgentChoice[]; commands: CommandChoice[]; models: ModelInfo[] }

const cache = new Map<string, { at: number; promise: Promise<Choices> }>();
const empty: Choices = { agents: [], commands: [], models: [] };

/** Agents, slash commands and the model catalog of [serverId] for [directory] (cached for a minute). */
export function useChoices(serverId: string | undefined, directory: string | undefined, enabled = true): Choices {
  const [choices, setChoices] = useState<Choices>(empty);
  useEffect(() => {
    if (!serverId || directory == null || !enabled) return;
    const key = `${serverId}\u0000${directory}`;
    let entry = cache.get(key);
    if (!entry || Date.now() - entry.at > 60_000) {
      const promise = get<Choices>(`${serverPath(serverId)}/choices?directory=${enc(directory)}`).catch(() => empty);
      entry = { at: Date.now(), promise };
      cache.set(key, entry);
    }
    let active = true;
    void entry.promise.then((value) => { if (active) setChoices(value); });
    return () => { active = false; };
  }, [serverId, directory, enabled]);
  return choices;
}

export const modelKey = (model: Pick<ModelChoice, "providerId" | "modelId">) => `${model.providerId}/${model.modelId}`;

/** The models offered in pickers: every model until the user limits the list (ModelVisibility.kt). */
export function useVisibleModels(serverId: string, models: ModelInfo[]): ModelInfo[] {
  const visibility = useStore((state) => state.ui.modelVisibility?.[serverId]);
  if (!visibility) return models.filter((it) => !it.deprecated);
  return models.filter((it) => visibility[it.key] === true);
}
