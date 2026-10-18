# 实现结构与验收

- `core/OpenCodeApi.kt`: OpenCode 2.x（`anomalyco/opencode` `packages/protocol`）REST、Basic Auth、`location[directory]` 位置上下文、`/api/event` 全局 SSE。各 2.0.x 版本间变化的路由由 `core/ApiCapabilities.kt` 从实例 `/openapi.json` 解析。所有请求绑定固定 origin，禁止跨源重定向转发凭据；SSE 溢出会转成流错误以触发重连对账。
- `core/HarnessClient.kt`: harness 抽象层。`ServerKind`（`opencode` / `dsh`）随服务器资料持久化，`createHarnessClient` 按类型选实现；控制器、总览与通知层只依赖 `HarnessClient` 接口，不感知具体协议。
- `core/DshApi.kt`: DeepSeek Harness 适配。用启动令牌 `GET /connect?token=…&interactions=answer` 换 `dsh-auth-…` 签名 Cookie；一元调用 `POST /api/<endpoint>`（`client-request` 信封 → `result.{ok,value|error}`），流式数据走 `/api/remote.mux`（`open`/`item`/`end` 帧）：`session/follow` 给出快照与增量事件，`$events` 给出会话增删、运行状态与审批/问答 waterfall（回应走 `$events/result` 结算）。会话事件按 `turn/step/tool/approval` 翻译成控制器认识的事件契约。
- `core/HttpOrigin.kt`: scheme+host+port 的凭据目标标识与回环明文白名单。
- `core/LagoonController.kt`: 所有页面共享的服务端状态、任务归一化和操作入口；异步写操作通过不可变 `OperationContext`（serverId + generation + client + selection revision + snapshot）绑定起点，切换后不再回写。
- `core/Models.kt`: 数据模型、`Session.Message.Info` 解析（user / assistant / shell / 通知类记录 / 隐藏记录）与 `TaskReducer` 任务阶段归约。
- `core/TranscriptProjection.kt`: 打开的会话的实时事件投影，对应官方 `packages/client/src/solid/data.ts` 的事件归约（`session.step.*`、`session.text.*`、`session.tool.*`、inbox、revert、compaction 等）。
- `core/TranscriptRows.kt`: 时间线行投影，对应官方 `packages/session-ui/src/timeline/projection.ts`（轮次分组、shell 独立成轮、通知行、中断分隔、只显示最后一条错误、撤销边界隐藏）。
- `core/TaskSummary.kt`: 全服务器范围的任务计数（运行中 / 未读已完成 / 待回复 / 失败）与统一显示文案，供系统实时更新通知与 App 内任务状态共用。
- `core/ServerStore.kt`: 服务器资料与 Keystore AES-GCM 凭据。
- `core/OfflineCache.kt`: 加密离线缓存（catalog 与消息）。
- `core/KeystoreCipher.kt`: `ServerStore`/`OfflineCache` 共用的 AndroidKeyStore AES-GCM 加解密。
- `core/PairLink.kt`: 官方 `opencode pair` 链接解析（强制 HTTPS）。
- `core/Http.kt` / `core/Diagnostics.kt`: 进程级 OkHttp 连接池/调度器；轻量日志。
- `ui/`: 小米 HyperOS / MIUIX 组件页面与不同消息 Part 的渲染；信息架构主线为 Server → Project/Directory → Session → Conversation，结构对齐 Qoder：没有底部 Dock，「会话」首页是唯一根页面（范围标题、服务器胶囊、当前服务器任务概览、按日期 / 项目 / 状态分组的列表、悬浮新建按钮）；任务概览展示运行中、未读完成、待回复、失败计数及最需关注的任务，状态不新鲜时明确提示；已归档、设置等低频页面从首页 ⋯ 整页推入；Chat 是会话主界面（工具调用为可展开的紧凑轨迹，子任务 / 改动 / 文件在右上角 ⋯）。`MainActivity` 负责页面栈、预见式返回（露出真实的上一页）与深链。
- `system/`: 通知 Channel、Android Live Updates 请求、任务前台服务与通知操作。全服务器任务总览沿用统一口径（`core/TaskSummary.kt`）：`TaskNotifications.buildSummary` 生成单条 ongoing 的 Live Update 通知（`setRequestPromotedOngoing` + `setShortCriticalText`），并由 `LagoonController` 在状态变化时统一发布；`system/LiveUpdateSupport.kt` 只探测标准实时更新的可用状态，供设置页展示（不写任何厂商私有 extras，详见 `docs/LIVE_UPDATES.md`）。
- `docs/task-event-contract.json`: Android `TaskReducer` 遵守的任务阶段归约契约。

## 必须在真实环境检查

1. 设置 OpenCode Server Basic Auth，使用 HTTPS URL 添加服务器；确认认证失败不会保存资料。
2. 从手机发送任务，检查消息、工具折叠、停止、SSE 重连后状态一致；确认系统实时更新显示「运行中 / 未读已完成 / 待回复 / 失败」计数，且打开会话后已完成计数下降。
3. 触发 permission 和 question；分别从 App 与系统通知操作，检查服务器继续执行。
4. 检查子会话、Diff、文件浏览；测试新建、运行中继续发送、斜杠命令、重命名、删除等会话操作。
5. 在 Android 16 设备检查实时更新提升与设置页「任务通知」状态；Android 15 及以下确认普通通知照常显示，首页概览中的计数、优先任务和过期状态提示正确；关闭通知权限后仍可在应用首页查看任务概览。
6. 添加 DeepSeek Harness（`dsh web` 地址 + 启动令牌）：确认令牌错误不保存资料、重启实例后旧令牌失效需重填；发送任务后检查时间线、工具结果、停止（`session/cancel`）、审批与问答回应；确认 DSH 不支持的入口（删除、compact、revert、Diff、文件浏览）给出明确提示而不是静默失败。

契约来源：`anomalyco/opencode` 2.x 源码（`packages/protocol`、`packages/schema`）与官方客户端（`packages/client`、`packages/session-ui`）。接入目标实例时可核对该实例 `/openapi.json`。
