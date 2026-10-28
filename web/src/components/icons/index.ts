import type { ComponentType } from "react";
import { MiuixIcons, type IconProps } from "./MiuixIcons";

export * from "./MiuixIcons";
export * from "./QoderIcons";

export type IconComponent = ComponentType<IconProps>;

// Semantic Android aliases aligned with Lagoon Android client
export const ArchiveIcon = MiuixIcons.Download;
export const UnarchiveIcon = MiuixIcons.Import;
export const DiffIcon = MiuixIcons.Merge;
export const SubtaskIcon = MiuixIcons.MindMap;
export const ForkIcon = MiuixIcons.Copy;
export const AgentIcon = MiuixIcons.ContactsCircle;
export const AttachmentIcon = MiuixIcons.Link;

export default MiuixIcons;
