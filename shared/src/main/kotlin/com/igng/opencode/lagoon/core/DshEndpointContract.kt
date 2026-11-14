package com.igng.opencode.lagoon.core

/**
 * 远程主机（`ds-harness-remote` 插件）对 `harness.remote.call` / `harness.remote.stream.open`
 * 的**端点白名单**与**可转发事件表**快照。
 *
 * 这三个集合是 `harness.remote.*` 的对外契约，不是从版本号推断出来的：
 * - [REMOTE_ALLOWLIST] 对应 `packages/plugin/src/harness-remote-bridge.ts` 的
 *   `HARNESS_REMOTE_ALLOWLIST`。不在表里的端点会被 `assertAllowed()` 直接拒绝
 *   （`METHOD_NOT_ALLOWED`），所以"本机直连能用"不等于"远程能用"。
 * - [FORWARDED_EVENTS] 对应 `dsh-api-remotes` 的 `API_REMOTE_FORWARDED_EVENTS`：
 *   `$events` 只会转发这些名字，其余事件远程永远收不到。
 * - [CONSUMED_EVENTS] 是 [FORWARDED_EVENTS] 中本应用真正消费的子集；其余条目是
 *   **有意不消费**（对应面板在打开时按需重读），列在 [IGNORED_EVENTS] 里避免被误认为遗漏。
 *
 * 升级 DSH 或远程插件时对照刷新这三个集合；[DshEndpointContractTest] 会守住一致性。
 */
internal object DshEndpointContract {
  /**
   * 远程插件 0.6.3 的白名单（100 条）。端点用 `namespace/method` 形式。
   *
   * `$events` 与 `$events/result` 是网关内部的伪端点，同样列在这里。
   */
  /** Separate upstream terminal policy checks Host enablement and trusted device ownership. */
  val REMOTE_TERMINAL_ENDPOINTS = setOf("terminal/environment", "terminal/shells", "terminal/list",
    "terminal/create", "terminal/retain", "terminal/follow", "terminal/write", "terminal/resize", "terminal/rename", "terminal/close")

  val REMOTE_ALLOWLIST: Set<String> = setOf(
    "\$events",
    "\$events/result",
    "agentPresets/list",
    "agentPresets/read",
    "agentPresets/select",
    "claudeCodeMods/pressBand",
    "claudeCodeMods/watchBand",
    "commands/execute",
    "commands/list",
    "credentials/describe",
    "credentials/set",
    "credentials/unset",
    "directoryPicker/list",
    "fileReferences/list",
    "fileUploads/upload",
    "goals/clear",
    "goals/complete",
    "goals/create",
    "goals/edit",
    "goals/pause",
    "goals/resume",
    "job/follow",
    "job/kill",
    "job/list",
    "llm/discoverModels",
    "llm/listConfigurableProviders",
    "llm/listProviders",
    "messageFeedback/delete",
    "messageFeedback/list",
    "messageFeedback/put",
    "officeToPdf/generation",
    "officeToPdf/render",
    "permissionPresets/catalog",
    "pluginInventory/list",
    "pluginManager/listBundles",
    "pluginManager/listPlugins",
    "pluginManager/listVersions",
    "pluginManager/readBundleDetail",
    "pluginManager/setBundleEnabled",
    "pluginManager/setPluginEnabled",
    "pluginManager/updateBundle",
    "remoteAccess/follow",
    "schedule/catalog",
    "schedule/create",
    "schedule/delete",
    "schedule/deleteInactive",
    "schedule/deliveryLimits",
    "schedule/edit",
    "schedule/history",
    "schedule/list",
    "schedule/run",
    "schedule/update",
    "session/attachment",
    "session/cancel",
    "session/canOpenWorkspacePath",
    "session/control",
    "session/create",
    "session/events",
    "session/follow",
    "session/fork",
    "session/initializeDefaultModel",
    "session/list",
    "session/modelCatalog",
    "session/openWorkspacePath",
    "session/page",
    "session/prompt",
    "session/projections",
    "session/rename",
    "session/search",
    "session/selectModel",
    "session/updateQueue",
    "session/workspacePathApplications",
    "sessionReferenceResolver/candidates",
    "settings/describe",
    "settings/mutate",
    "settings/replace",
    "settings/update",
    "skills/list",
    "subagents/interruptByParent",
    "subagents/list",
    "subagents/prompt",
    "toolResults/watch",
    "userQuestions/answer",
    "userQuestions/attachWait",
    "workspace/archiveSession",
    "workspace/create",
    "workspace/delete",
    "workspace/follow",
    "workspace/initializeDefault",
    "workspace/insertBefore",
    "workspace/insertSessionBefore",
    "workspace/pinSession",
    "workspace/rename",
    "workspace/unarchiveSession",
    "workspace/unpinSession",
    "workspaceFiles/changes",
    "workspaceFiles/list",
    "workspaceFiles/read",
    "workspaceFiles/readAll",
    "workspaceFiles/readBytes",
    "workspaceFiles/readRelated",
    "workspaceFiles/stat"
  )

  /**
   * `$events` 会转发的 Host 事件（`API_REMOTE_FORWARDED_EVENTS`）。未列出的名字远程永远收不到，
   * 不要为它们写本地处理器——那是死代码。
   */
  val FORWARDED_EVENTS: Set<String> = setOf(
    "agent-preset/selected",
    "api-session/activity",
    "api-session/added",
    "api-session/error",
    "api-session/removed",
    "api-session/status",
    "approval/request",
    "commands/change",
    "cordis/dynamic-package",
    "cordis/dynamic-retract",
    "cordis/inspect-query",
    "cordis/inspect-query-resolved",
    "cordis/request-run",
    "cordis/request-run-resolved",
    "credentials/record-updated",
    "credentials/reference-updated",
    "deepseek-account/model-sign-in-required",
    "deepseek-account/session-expired",
    "goal/activation-changed",
    "llm/adapters-updated",
    "permission-presets/catalog-changed",
    "plugin-manager/changed",
    "plugin-manager/install-log",
    "plugin-manager/install-state",
    "plugin-market/changed",
    "schedule/changed",
    "settings/document-updated",
    "user-questions/request"
  )

  /** [FORWARDED_EVENTS] 中本应用真正消费的条目（`handleEmit`/`handleWaterfall`）。 */
  val CONSUMED_EVENTS: Set<String> = setOf(
    "agent-preset/selected",
    "api-session/activity",
    "api-session/added",
    "api-session/error",
    "api-session/removed",
    "api-session/status",
    "approval/request",
    "goal/activation-changed",
    "user-questions/request"
  )

  /**
   * 有意不消费的转发事件。它们描述的都是"面板打开时才需要"的状态，本应用在每次打开对话框时
   * 按需重读（`settings/describe`、`credentials/describe`、`pluginManager` 各方法、`schedule/list`、
   * `commands/list`、`agentPresets/list`），因此不需要实时订阅。
   */
  val IGNORED_EVENTS: Set<String> = FORWARDED_EVENTS - CONSUMED_EVENTS

  /** 该端点是否允许在 `harness.remote.call` 上使用。 */
  fun isRemoteEndpoint(endpoint: String): Boolean = endpoint in REMOTE_ALLOWLIST || endpoint in REMOTE_TERMINAL_ENDPOINTS
}
