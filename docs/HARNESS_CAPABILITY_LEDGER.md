# 原生 Harness 功能与验收账本

本账本服务于统一连接重构，不表示列出的目标功能已经实现。**实现、接口测试、真实 Host 互操作、Android 真机**为四个不同验收层级；缺少后两项证据不得标为端到端通过。

## 官方基线

| 产品 | 版本 / revision | 权威来源 |
|---|---|---|
| OpenCode native | v2.2.6 / dc2ffcf8bf26f9e669b6aa22b95f50e12be15e06 | packages/protocol、packages/client、packages/app、packages/session-ui |
| DSH Typert | 0.2.1-rc.5 / b830ec950faba0a46dda564be3e2d854afed94f1 | 官方 npm 生成的 Typert face、Host 与官方 UI |
| DSH Typert 新代际 | 0.2.2-rc.1 / 66491b05e3f05ad35bcdd4f6cd7ed26041d9e3db | 官方 Session/terminal/Gateway 契约；附件完整 ref、control changes/seq |
| ds-harness-remote | bb33f0a6eb676b61f40a9bbf7d365e8a5e93b81b | packages/protocol、plugin 固定 allowlist 与实际 carrier |

官方来源：
- https://github.com/anomalyco/opencode/tree/dc2ffcf8bf26f9e669b6aa22b95f50e12be15e06
- https://github.com/deepseek-ai/deepseek-harness/tree/66491b05e3f05ad35bcdd4f6cd7ed26041d9e3db
- https://github.com/liguobao/ds-harness-remote/tree/bb33f0a6eb676b61f40a9bbf7d365e8a5e93b81b

## 传输原则

- 相同原生服务的 SSH 与 Hub 必须复用业务适配器；能力取“官方协议 ∩ Host 允许 ∩ 所选传输 ∩ 客户端实现 ∩ 就绪状态”，不取不同路径并集。
- 旧 Remote Host 不支持新 connector 时明确升级要求；不得用 OpenCode ACP 冒充 native 完整接入。
- 用户动作可确认失败、确认成功、结果未知。请求 ID 或日志能否支持安全恢复必须按方法定义，不以 socket 重连等同于未执行。
- 当前默认仅 Relay（含独立 Relay attach）；WebRTC/LAN/P2P/TURN 是独立传输能力，未实现不得显示为可用。

## 功能清单

“现状”指初始 24d05d4 的实现，不是最终验收结果。所有目标都需同时检查 UI 可达性；接口存在不代表功能已交付。

| 功能 | OpenCode native 依据 | DSH 依据 | 初始缺口 / 本次要求 |
|---|---|---|---|
| 服务与版本发现 | managed service registration + /api/info + /openapi.json | 精确 profile/运行实例 + Host capability | SSH 仅 DSH；Hub 仅 Typert；建立通用只读发现 |
| 工作区列举/目录选择 | project/location/fs | workspace/follow、directoryPicker/list | 保留目录真实 identity，不按显示名路由 |
| 工作区管理 | project/worktree 官方操作 | workspace create/rename/delete/order | 按官方可远程能力补足，删除确认 |
| 会话列举与分页 | GET /api/session | session/list | 主/子会话、blank、归档边界准确 |
| 服务端搜索 | 官方会话/消息搜索 | session/search | 不把本机未缓存数据当完整搜索结果 |
| 创建/复用 | POST /api/session，支持 client id | session/create、blank reuse | workspace attach 失败可能已创建，不盲重试 |
| 重命名 | PATCH session，兼容旧路由 | session/rename | 等待服务端提交结果 |
| 归档/恢复 | 顶层 archived boolean | workspace/archiveSession、unarchiveSession | DSH 外部取消归档不能被本机旧 true 覆盖 |
| 删除 | 原生 delete 含子会话语义 | 不支持永久删除 | DSH 不能伪造删除成功 |
| 分叉 | session/fork before | session/fork atSeq | 原生能力 UI 可达，指定历史边界可验证 |
| 消息级回退/恢复 | revert stage/clear/commit | 不支持 | DSH 明示无此能力，不冒充 fork 等价 |
| 置顶/已读 | 原生能力/本机偏好分别处理 | workspace pin/unpin；无 viewed RPC | 本机偏好不能伪装服务端事实 |
| 历史页/重连快照 | message cursor，durable wire cache | follow/page，turnWindow、seq cut | 重连无重复/缺口；实时流与冷历史同源归约 |
| 正文/思考/工具 | 官方 event reducer/projection | assistant-stream、durable event | 保留失败状态，不把模型注入内容当用户消息 |
| PTC 子调用 | 对应 tool 展示 | rootCallId/parentCallId/subCallId | live/cold 显示一致，不能猜不存在 turn/step |
| 模型/档位 | model Ref、selection API | selectModel、flat model/selection、projection | 服务端确认，跨客户端同步，catalog 失效重读 |
| Agent/权限模式 | agent 与 permission rule semantics | agentPresets、permissionPresets、commands | 不支持值不伪造本地成功 |
| prompt/插话/排队 | inbox durable admission | prompt queue/steer | 超时对账，不在其他路径重发 |
| 排队撤回/编辑 | delete/patch inbox；编辑先读取原附件 | updateQueue | 附件保留与部分失败可恢复 |
| 斜杠命令 | session command | commands/execute + submittedAttachments | 附件/引用支持或显式拒绝，不能静默丢失 |
| 图片附件 | official media limits | 官方 image limits / durable refs | 按服务限制，不以连接方式改变内容 |
| 非图片附件 | 官方支持类型 | fileUploads receipt / 原生 connector | 初始导入器全局拒绝二进制；需按能力策略 |
| 工作区上传 | 原生文件操作明示 | 独立 workspace.upload | 不能把上传落盘与会话附件等同；进度/取消/限额 |
| 历史附件回读 | media/file 原生协议 | session/attachment | 0.2.2 完整 ref 与 rc.5 ID 契约分开适配 |
| 文件列举/搜索 | fs/list/find/read | workspaceFiles、fileReferences | 路径与 scope 权限在 Host 校验 |
| 二进制/PDF/Office 预览 | 官方读取/媒体 | readBytes、officeToPdf | 范围续读、限额前置；不能单窗截断冒充完整 |
| 逐文件 Diff | session diff | workspaceChanges summary/diff Fetch | 初始 Remote 隐藏；补受限原生 Fetch 承载 |
| 压缩上下文 | compact durable admission | /compact 命令 | 按官方动作，不混淆重发普通 prompt |
| 审批 | request + allowed save scope | owner Peer waterfall | 单次/永久授权严格按协议；他端结算撤卡 |
| 结构化提问 | form 生命周期 | userQuestions waterfall/projection | timed 倒计时/认领/continued/late answer 准确 |
| 后台作业 | shell list/info/output/delete | job list/follow/kill | Remote kill 被 UI 隐藏；输出/终态需保留实际意义 |
| 子代理 | 原生父子 Session | subagents list/prompt/interrupt | one-shot/continuable/external 区分，父地址权限 |
| 交互终端 | 原生 PTY/WS | terminal attach / Remote adapter | 双向流、ack、controller ownership、无输入重放 |
| todo/goal | 以官方是否支持为准 | projections、goals actions | 不强行把 DSH 概念加给 OpenCode；补 edit/activation |
| 定时任务 | 官方 schedule 能力 | schedule catalog/CRUD/history/run | 初始 DSH 只读列表；需实际操作闭环 |
| 插件/设置 | 官方 plugin/config surface | 官方 settings/pluginManager | schema/revision、readonly/restart 状态准确；不提权 |
| 模型服务商/凭据 | 官方 provider/auth routes | settings/credentials/llm | 凭据只写不可读回，Host 本地策略保护 |
| 导出/分享 | 官方 export/share 能力 | 按官方可远程表面 | 与 UI/原生系统动作边界明确，不凭相似名称实现 |
| 多端状态 | SSE 断线对账 | session/control + $events | 不把上轮结果/缓存当实时状态 |
| 聚合与通知 | binding + session ID | binding + session ID | 双路径确认同服务后唯一监控源，独立开关不串兄弟 |

## 第一阶段验证记录

- `OpenCodeContractFixtureTest`：固定 v2.2.6 原始 OpenAPI 摘录。
- `DshPublishedContractTest`：官方 generated schema 请求正反例，以及 rc.5/rc.1 attachment 契约分界。
- `RemotePublishedContractTest`：实际 Noise/Relay 栈 + 官方参数校验，避免“假 Host 同意一切”的假阳性。
- 其它修复以针对性回归 + 原有相关套件共同验证；每阶段日志在 `UNIFIED_CONNECTIONS.md` 记录。
- 当前无 Android 设备连接，不把 JVM/构建视为真机验收。

## 本轮 Remote/层级能力证据

- 固定上游 companion overlay 可构建实际 Router、HostPluginRuntime 与完整插件入口；Host 原生 OpenCode 不依赖 ACP。
- 原生服务 HTTP/binary/SSE/WebSocket 和上传/下载分片经 Node loopback 测试；Android NativeHttpTransport 经 fake-RPC 单测、实际 Kotlin Noise → Node patched Router/Peer → 原生 fixture 集成验证。
- 同 Host 两个服务共享加密连接，关一个服务不关兄弟；关闭、慢消费者、旧代次、pending open cancellation 有专项回归。请求超时不自动改路或重发。
- 单实例 DshApi 的多个 events() 收集器共享唯一业务事件源，独立有界队列和 JSON 载荷副本，旁观者取消不交还前台提问；最后退出后清理并等待再换代。跨调用者另有显式 DshClientRegistry/factory opt-in 与完整 guarded lease；消息读取、前台提问与监视兴趣隔离，最后 release 才关闭 owner，旧凭据/路由/timeout 不合并；裸构造器保持独立。
- 三层开关在连接/通知/深链/组合生效；可重叠组合保留同一 leaf 多处引用。原生服务选择不自动启用。
- 后续原生 UI 补齐：DSH bundle/entry 插件、凭据引用和 provider 设置按协议而非 carrier 显示，使用打开时的确切 route 校验；OpenCode 分叉能力由兼容原生 route 发现，回退/恢复有消息级确认且离线/运行/未知活动状态拒绝。目标编辑另已接入官方 revision/ref 与 Long 预算契约，不自动续跑；调度 catalog/history/delete/CAS update 另按官方五方法接入并保留完整记录/原会话/独立时区；rc.5 无公开 create/run/enable，明确不提供。OpenCode simple PTY 原生文本终端业务另已接入，明确创建/附加/输入/终止，离开不 kill，票据一次性且无输入重放；不是完整 VT。DSH Direct/SSH 的 Session scope、retain、双向 mux、snapshot/output/输入与 resize ACK、控制权与文本 UI 已接入；Remote 已复用实际上游 follow/write/resize 兼容层与 harness.terminal.v1，保留 Host 开关/设备归属/attachmentId 与 provider-ACK 语义，固定旧加密 gateway 不重定向输入；无能力时仍明确拒绝。
- r8 已取得额外真实服务证据：校验官方 v2.2.6 release 二进制的原生直连读取/SSE、真实 JSch→隔离 sshd 的两 binding 共享/关闭隔离、Kotlin Noise→actual Router/Peer→同一官方 daemon 的目录/能力/事件；另有官方 DSH rc.5 全新 WebHost 的 list/workspace/$events。未发模型 prompt、未使用用户现有资料。
- Host Basic 仅在 Linux 内核证明登记 PID 持有确切对端 socket 后发送；macOS/Windows 暂不支持该证据路径，明确不可用。不将这些 JVM/loopback/fake relay 证据冒充公网 Hub、生产 Host、DSH Desktop 附着或 Android 真机；完整原生能力清单仍需逐项验收。

## 尚需分阶段落实的关键门槛

1. 第 1 阶段：已发现语义 bug 的回归与修复全部收口。
2. 第 2 阶段：稳定绑定 ID、确切 credential refs、无网络迁移、parent/child enable 关系和存储恢复。
3. 第 3 阶段：单物理 mux、typed errors、取消/背压、所有权和无写重放。
4. 第 4 阶段：单 SSH 身份多服务、已存在真实实例附着、启动锁与独立恢复。
5. 第 5 阶段：实际可打包 Host connector/固定 Router hook，不能只交 manifest/stub。
6. 第 6 阶段：每个已实现能力均有原生 UI 操作闭环，保留导航/预测式返回。
7. 第 7 阶段：同一官方用例分别经 SSH 与 Hub 真链路执行；真机未具备时如实报告，不宣称全阶段验收完成。
