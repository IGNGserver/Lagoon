# 实现结构与验收

- `core/OpenCodeApi.kt`: OpenCode 2.x（`anomalyco/opencode` `packages/protocol`）REST、Basic Auth、`location[directory]` 位置上下文、`/api/event` 全局 SSE。各 2.0.x 版本间变化的路由由 `core/ApiCapabilities.kt` 从实例 `/openapi.json` 解析。所有请求绑定固定 origin，禁止跨源重定向转发凭据；SSE 溢出会转成流错误以触发重连对账。
- `core/HarnessClient.kt`: harness 抽象层。`ServerKind`（`opencode` / `dsh`）随服务器资料持久化，`createHarnessClient` 按类型选实现；控制器、总览与通知层只依赖 `HarnessClient` 接口，不感知具体协议。
- `core/DshApi.kt`: DeepSeek Harness 适配（`dsh web` 的 Typert RPC Gateway，完整契约见 `docs/dsh-protocol.md`）。启动令牌先走浏览器式根路径交换（长寿命 Cookie，跨进程重启有效，换到后加密落盘复用并滑动续期），根路径不交付 Cookie 时才退回 `GET /connect?token=…&interactions=answer` 的 API Peer；签名密钥模式在本机签发 Cookie（`core/DshAuth.kt`）；一元调用 `POST /api/<namespace>/<method>`（payload 只含一个 `args` 对象，字段是方法形参名），流式数据走 `/api/remote.mux`（`open`/`item`/`end` 帧）：`session/follow` 给出快照、增量事件与实时 assistant 流，`workspace/follow` 维护项目清单与归档集合，`$events` 给出会话增删、运行状态与审批/问答 waterfall（回应走 `$events/result`，其他客户端结算的 `cancel` 帧撤回本地待办）。文件浏览走 `workspaceFiles`，改动列表走 `workspace/changes` 摘要与认证栅栏内的 `/api/changes.summary|diff` 路由，后台作业走 `job/list`，手动压缩走 `/compact` 命令。会话事件按 `turn/step/tool` 翻译成控制器认识的事件契约；DSH 没有删除会话与消息级回退，分别折算为归档与「分叉」提示。
- `core/HttpOrigin.kt`: scheme+host+port 的凭据目标标识与回环明文白名单。
- `core/LagoonController.kt`: 所有页面共享的服务端状态、任务归一化和操作入口；异步写操作通过不可变 `OperationContext`（serverId + generation + client + selection revision + snapshot）绑定起点，切换后不再回写。
- `core/Models.kt`: 数据模型、`Session.Message.Info` 解析（user / assistant / shell / 通知类记录 / 隐藏记录）与 `TaskReducer` 任务阶段归约。
- `core/TranscriptProjection.kt`: 打开的会话的实时事件投影，对应官方 `packages/client/src/solid/data.ts` 的事件归约（`session.step.*`、`session.text.*`、`session.tool.*`、inbox、revert、compaction 等）。
- `core/TranscriptRows.kt`: 时间线行投影，对应官方 `packages/session-ui/src/timeline/projection.ts`（轮次分组、shell 独立成轮、通知行、中断分隔、只显示最后一条错误、撤销边界隐藏）。
- `core/TaskSummary.kt`: 全服务器范围的任务计数（运行中 / 未读已完成 / 待回复 / 失败）与统一显示文案，供系统实时更新通知与 App 内任务状态共用。
- `core/ServerStore.kt`: 服务器资料与 Keystore AES-GCM 凭据。
- `core/OfflineCache.kt`: 加密离线缓存（会话列表，以及 DSH 的消息快照）。
- `core/TranscriptStore.kt` / `core/TranscriptSync.kt`: OpenCode 会话缓存。SQLite 保存服务器原始 JSON（只存已结束的消息、连续一段），打开会话先显示本机内容，再只读最新一页并与缓存核对后追加，对不上即重建；历史先从本机翻页。设计与正确性保证见 `docs/TRANSCRIPT_CACHE.md`。
- `core/KeystoreCipher.kt`: `ServerStore`/`OfflineCache` 共用的 AndroidKeyStore AES-GCM 加解密。
- `core/PairLink.kt`: 官方 `opencode pair` 链接解析（强制 HTTPS）。
- `core/Http.kt` / `core/Diagnostics.kt`: 进程级 OkHttp 连接池/调度器；轻量日志。
- `ui/`: 小米 HyperOS / MIUIX 组件页面与不同消息 Part 的渲染；信息架构主线为 Server → Project/Directory → Session → Conversation，结构对齐 Qoder：没有底部 Dock，「会话」首页是唯一根页面（范围标题、服务器胶囊、当前服务器任务概览、按日期 / 项目 / 状态分组的列表、悬浮新建按钮）；任务概览展示运行中、未读完成、待回复、失败计数及最需关注的任务，状态不新鲜时明确提示；已归档、设置等低频页面从首页 ⋯ 整页推入；Chat 是会话主界面（工具调用为可展开的紧凑轨迹，子任务 / 改动 / 文件在右上角 ⋯）。`MainActivity` 负责页面栈、预见式返回（露出真实的上一页）与深链。
- `system/`: 通知 Channel、Android Live Updates 请求、任务前台服务与通知操作。全服务器任务总览沿用统一口径（`core/TaskSummary.kt`）：`TaskNotifications.buildSummary` 生成单条 ongoing 的 Live Update 通知（`setRequestPromotedOngoing` + `setShortCriticalText`），并由 `LagoonController` 在状态变化时统一发布；`system/LiveUpdateSupport.kt` 只探测标准实时更新的可用状态，供设置页展示（不写任何厂商私有 extras，详见 `docs/LIVE_UPDATES.md`）。
- `core/AppUpdate.kt` / `system/AppUpdater.kt`: GitHub Releases 检查与 APK 自更新。SemVer（含 rc）比较、预发布通道筛选、ETag 缓存、流式下载、大小 / SHA-256 / 包名 / versionCode / 签名证书校验；只有通过校验且位于 `cache/updates/` 的 APK 会通过 FileProvider 交给系统安装器，详见 `docs/APP_UPDATE.md`。
- `docs/task-event-contract.json`: Android `TaskReducer` 遵守的任务阶段归约契约。

## 必须在真实环境检查

1. 设置 OpenCode Server Basic Auth，使用 HTTPS URL 添加服务器；确认认证失败不会保存资料。
2. 从手机发送任务，检查消息、工具折叠、停止、SSE 重连后状态一致；确认系统实时更新显示「运行中 / 未读已完成 / 待回复 / 失败」计数，且打开会话后已完成计数下降。
3. 触发 permission 和 question；分别从 App 与系统通知操作，检查服务器继续执行。
4. 检查子会话、Diff、文件浏览；测试新建、运行中继续发送、斜杠命令、重命名、删除等会话操作。
5. 在 Android 16 设备检查实时更新提升与设置页「任务通知」状态；Android 15 及以下确认普通通知照常显示，首页概览中的计数、优先任务和过期状态提示正确；关闭通知权限后仍可在应用首页查看任务概览。
6. 添加 DeepSeek Harness（`dsh web --host <本机 IP> --no-open`）：粘贴整条启动链接，确认地址与令牌自动拆开、令牌错误不保存资料；强杀 App 并重启 dsh 后仍能直接连上（落盘 Cookie 复用，不需要新令牌）；用签名密钥模式添加同一实例，确认无需启动链接即可连接、改错一个字符会提示重新配对；连接后断网/切换 Wi-Fi 再恢复，事件流与打开的会话自动续上、断线期间被别处回答的审批自动消失；发送任务后检查实时流式文本、工具轨迹、停止（`session/cancel`）、审批与问答回应；检查文件浏览、文件搜索、在没有会话的目录添加项目、图片与文本附件、Agent 预设切换、改动列表、后台任务状态与 `/compact` 命令；确认会话菜单显示「分叉 / 归档」而没有「删除」，审批卡片没有「始终允许」。
7. 设置 → 版本与更新：检查更新、切换预发布通道、下载进度与取消、首次安装的“安装未知应用”授权、系统安装器覆盖安装；断网、签名不一致、`versionCode` 不升时确认会明确拒绝。

契约来源：`anomalyco/opencode` 2.x 源码（`packages/protocol`、`packages/schema`）与官方客户端（`packages/client`、`packages/session-ui`）。接入目标实例时可核对该实例 `/openapi.json`。
