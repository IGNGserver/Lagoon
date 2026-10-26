/**
 * Wire types of the hub API (docs/HUB_DESIGN.md §7). Field names follow the Kotlin models in
 * shared/src/main/kotlin (see hub Codec.kt), so this file reads like the Android code it mirrors.
 */

export type ServerKind = "opencode" | "dsh";
export type DshAuthMode = "token" | "secret";

export interface ServerProfile {
  id: string;
  name: string;
  url: string;
  username: string;
  autoConnect: boolean;
  notifications: boolean;
  allowCleartext: boolean;
  kind: ServerKind;
  dshAuth: DshAuthMode;
  hasSecret?: boolean;
}

export interface ModelChoice {
  providerId: string;
  modelId: string;
  label: string;
  variant?: string | null;
}

export interface Project {
  id: string;
  directory: string;
  name: string;
  sandboxes: string[];
}

export interface Session {
  id: string;
  directory: string;
  title: string;
  updated: number;
  parentId?: string | null;
  projectId?: string | null;
  created: number;
  archived: boolean;
  agent?: string | null;
  model: ModelChoice | null;
  viewed: number;
  idle: number;
  outcome?: string | null;
  revertMessageId?: string | null;
}

export type TaskPhase =
  | "IDLE" | "THINKING" | "TOOL" | "SUBAGENT" | "TESTING" | "WAITING_PERMISSION" | "WAITING_QUESTION"
  | "WAITING_SERVER" | "COMPLETED" | "FAILED" | "ABORTED" | "DISCONNECTED";

export interface TaskState {
  sessionId: string;
  phase: TaskPhase;
  detail: string;
  since: number;
  finishedAt?: number;
  background: boolean;
  uncertain: boolean;
  pendingInput: boolean;
  active: boolean;
}

export interface SessionNotice {
  id: string;
  sessionId: string;
  time: number;
  error: boolean;
  viewed: boolean;
}

export interface PermissionRequest {
  id: string;
  sessionId: string;
  directory: string;
  action: string;
  detail: string;
  always: string[];
  toolMessageId: string;
  toolCallId: string;
}

export interface QuestionOption {
  label: string;
  description: string;
  value: string;
}

export interface QuestionPrompt {
  title: string;
  options: QuestionOption[];
  multiple: boolean;
  custom: boolean;
  field: string;
}

export interface QuestionRequest {
  id: string;
  sessionId: string;
  directory: string;
  form: boolean;
  questions: QuestionPrompt[];
}

export interface TaskSummaryItem {
  sessionId: string;
  title: string;
  phase: TaskPhase;
  detail: string;
  serverId?: string;
  serverName?: string;
  since: number;
}

export interface TaskSummary {
  running: number;
  completed: number;
  waiting: number;
  failed: number;
  background: number;
  processing: number;
  text?: string;
  countLine: string;
  items: TaskSummaryItem[];
}

export interface Capabilities {
  rename: boolean;
  unrevert: boolean;
  diff: boolean;
  view: boolean;
  archive: boolean;
  restore: boolean;
  delete: boolean;
  fork: boolean;
}

export type SessionStatus =
  | "WAITING_PERMISSION" | "WAITING_QUESTION" | "WAITING_SERVER" | "RUNNING" | "BACKGROUND_RUNNING"
  | "FAILED" | "COMPLETED" | "UNKNOWN" | "NONE";

/** One saved server as the hub watches it. */
export interface ServerView {
  serverId: string;
  profile: ServerProfile;
  hasSecret: boolean;
  monitored: boolean;
  version: string;
  connected: boolean;
  cached: boolean;
  loading: boolean;
  lastReadAt: number;
  error: string | null;
  degraded: boolean;
  streamConnected: boolean;
  stale: boolean;
  canSyncRead: boolean;
  capabilities: Capabilities;
  projects: Project[];
  sessions: Session[];
  parents: Record<string, string>;
  tasks: Record<string, TaskState>;
  notices: SessionNotice[];
  permissions: PermissionRequest[];
  questions: QuestionRequest[];
  backgroundRunning: string[];
  autoApprovalFailures: string[];
  shells: { id: string; sessionId: string; command: string; started: number; directory: string }[];
  summary: TaskSummary;
  statuses: Record<string, SessionStatus>;
}

export interface Attachment {
  url: string;
  mime: string;
  name: string;
}

export interface Notice {
  label: string;
  detail: string;
  items: string[];
  target?: string | null;
  error: boolean;
  running: boolean;
}

export interface MessagePart {
  id: string;
  type: string;
  text: string;
  tool: string;
  title: string;
  status: string;
  input: string;
  output: string;
  path: string;
  error: string;
  patch: string;
  files: string[];
  mime: string;
  attachments: Attachment[];
  target?: string | null;
  background: boolean;
}

export interface Message {
  id: string;
  role: string;
  created: number;
  parts: MessagePart[];
  error?: string | null;
  agent?: string | null;
  model: ModelChoice | null;
  completedAt?: number | null;
  finish?: string | null;
  errorType?: string | null;
  retry?: string | null;
  notice: Notice | null;
  queued: boolean;
  type: string;
}

export interface ModelInfo {
  choice: ModelChoice;
  key: string;
  providerName: string;
  family?: string | null;
  released: number;
  imageInput?: boolean | null;
  deprecated: boolean;
  variants: { id: string; label: string }[];
}

export interface AgentChoice { name: string; description: string }
export interface CommandChoice { name: string; description: string }
export interface FileNode { path: string; type: string }
export interface FileContent { type: string; content: string }
export interface FileChange { path: string; after: string; additions: number; deletions: number; patch: string }

export interface PermissionPolicy { hide: boolean; autoApprove: boolean }
export type RefreshMode = "STANDARD" | "FAST";

export interface HubState {
  servers: ServerView[];
  policy: PermissionPolicy;
  refreshMode: RefreshMode;
  ui: UiPrefs;
}

/** Preferences shared by every device signed in to the hub. */
export interface UiPrefs {
  grouping?: "DATE" | "PROJECT" | "STATUS";
  /** Pinned session ids per server. */
  pinned?: Record<string, string[]>;
  /** Per server: model key → visible; absent means the full catalog is shown. */
  modelVisibility?: Record<string, Record<string, boolean>>;
  recentModels?: Record<string, string[]>;
}

export interface AuthStatus {
  authenticated: boolean;
  setupRequired: boolean;
  passwordFromEnv: boolean;
  version: string;
  secure: boolean;
  tls: { selfSigned: boolean; port: number } | null;
}

export type ConversationOp =
  | { op: "put"; message: Message }
  | { op: "part"; messageId: string; index: number; part: MessagePart }
  | { op: "append"; messageId: string; index: number; field: "text" | "input" | "output"; text: string }
  | { op: "del"; id: string };

export interface ConversationReset {
  serverId: string;
  sessionId: string;
  messages: Message[];
  cursor: string | null;
  loading: boolean;
  error: string | null;
}

export interface ConversationOps {
  serverId: string;
  sessionId: string;
  ops: ConversationOp[];
  order?: string[];
}
