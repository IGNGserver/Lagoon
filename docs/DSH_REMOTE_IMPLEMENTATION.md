# DSH Remote 分阶段实现记录

> 对应设计：[`DSH_GO_REMOTE_DESIGN.md`](DSH_GO_REMOTE_DESIGN.md)。
> 基线：`cd45f72`；本次实现没有真实 Remote Host 可用，所有网络行为仍需真机/真实 Host 验收。

## 状态总览

| 阶段 | 状态 | 本次落地 |
| --- | --- | --- |
| P0 账号与接入 | 基本完成 | OAuth/QR API、主机目录、账号管理器、登录/多选/指纹核对 UI、远程主机管理、添加/编辑服务器分流、typed failure |
| P1 连接韧性 | 基本完成 | 已保存 Remote profile 复用 monitor 连接做测试；连接详情含 Relay/加密/版本/指纹、RPC 往返、最近联络/当前阶段；typed failure 提供恢复按钮；同 Host 由 addRemoteProfiles 去重、monitor/controller 共享 client |
| P2 远程任务 | 基本完成（完整表单除外） | 工作区上传、排队消息操作、`job/follow`、Host 凭据引用/密钥、自定义 pi-ai 模型路由；`job/kill` 受上游 allowlist 阻塞；通用 schemastery 表单未做 |
| P3 主机扩展 | 基本完成（定时任务只读） | DSH 子代理续聊（按 continuable 投影）、官方插件 bundle 开关、会话目标条、Office→PDF/PDF/图片首屏预览、定时任务只读列表已接入；创建/编辑/删除未做 |
| P4 传输升级 | 评估完成，未实现 | 结论与阻塞见 [`DSH_REMOTE_WEBRTC_EVAL.md`](DSH_REMOTE_WEBRTC_EVAL.md)：Host 无 WebRTC 信令，当前固定 Relay |

## P0 已落地

- `RemoteServerApi`：
  - `GET /api/v1/auth/oauth/providers`
  - `POST <qrStartUrl>` / `GET /api/v1/auth/oauth/qr/{id}`
  - `GET /api/v1/auth/me`（Bearer account token）
  - `GET /api/v1/account/devices`（Bearer account token，登录后的 Host 发现）
  - `DELETE /api/v1/devices/self`
  - 入口路径限定同源 `/api/v1/auth/`，拒绝控制字符、`.`/`..`、`%` 段；`scanUrl` 同源。
  - **账号 token 与设备 token 不能混用**：`/api/v1/devices`、`/api/v1/devices/{id}` 要
    已注册 Client 的 device token；登录后只有 account token，必须走
    `/api/v1/account/devices`，否则会得到 `401 AUTH_INVALID`，表现成“密码错误”。
    `/api/v1/account/devices` 同时返回 Host 的 `identityKey`，发现阶段不需要再逐台取详情；
    响应里也会包含 Client，按 `role=host` 过滤。
- `RemoteCatalog` / `RemoteCatalogCodec` / `AndroidRemoteCatalogStore`：
  - 账号级 Host 目录，不存 token/公钥；每 Host 记录展示名称、平台、在线、版本、指纹、trust。
  - `remote-host-catalog` 是唯一 Host 目录来源；设备组只是后续视图层。
- `RemoteAccountManager`：
  - OAuth/邮箱登录、Host 发现、每 Host 一个 `host-<hostId>` 设备身份、批量 enroll、pin、刷新、退出时撤销设备。
  - account token 只在管理器内存中，enroll 后丢弃。
- UI：
  - 设置 › 服务器 ›「DSH Remote 主机」→ `RemoteHostsSheet`（账号、主机、刷新、退出）。
  - `RemoteEnrollSheet`：浏览器登录 / 自建邮箱登录、多选 Host、逐台指纹核对、接入。
  - 添加/编辑服务器选择 DSH Remote 时走独立入口，不再要求手输指纹。
- `RemoteFailureException`：离线、登录失效、设备撤销、Host 不存在、指纹待确认/变化、被替换、版本不兼容、限流、网络失败；`RemoteSession` 已开始抛 typed failure。

## P1 已落地/未完成

已落地：

- `testServer` 对已保存的 DSH Remote profile 复用 monitor 的 client 做 health，避免同一 Host 被第二条连接替换。
- DSH Remote 服务器编辑页显示连接方式、地址、状态、路径（Relay）、加密、服务版本、主机指纹。
- “网络与连接”对话框支持一次加密 RPC 往返测量（`harness.transport.describe`），明确标注不是网络层 RTT；同时显示最近联络和当前连接阶段。
- `addRemoteProfiles` 按 `(origin, hostId)` 去重；已保存 profile 的测试连接复用 monitor client，正常流程不会为同一 Host 再开第二条 Remote 连接。
- 连接失败按 `RemoteFailureException.kind` 显示恢复动作：重新登录、核对指纹、刷新主机、重新连接；按钮打开 Remote 登录/接主机 sheet。

未完成：

- 进程级 `(origin, account, hostId)` 连接注册表；monitor/controller/test 仍可能各开连接。
- 网络详情中的连接耗时、最近观测；RPC 往返已有，但只是单次估算。
- 失败动作的“重新接管”分支与 ConnectionStrip 阶段细分；当前登录/指纹/刷新主机已有入口。

## P2 已落地/未完成

已落地：

- `RemoteSession.uploadWorkspaceFile`：顶层 `workspace.upload.open/chunk/commit/abort`，不走 Gateway；校验 1 GiB 协议上限、`chunkBytes`、ack、最终路径；失败 abort，不自动重放 commit。
- `DshApi.send`：Remote 下非图片附件上传到当前会话工作区，消息正文写 `已上传文件：./name`；图片仍走 image 块。
- `HarnessClient.updateQueue` / `DshApi.updateQueue` / 控制器 `updateQueued`；聊天里排队中的消息可编辑、立即插话、撤回。
- `HarnessClient.followJobOutput` / `DshApi` 用 `job/follow` 流式输出；控制器和现有后台命令 sheet 已接入。
- 主机模型密钥：`credentials/describe/set/unset`，设置页可填写任意 Host 凭据引用（默认 `DEEPSEEK_API_KEY`），不回显值。
- 自定义模型服务商：`settings/describe` 找到 `llm-pi-ai` revision，`settings/mutate` 写入 `providers.<route>`（api/baseURL/apiKeyEnv/models），再写密钥；支持一个模型 ID 的最小路由。

未完成/阻塞：

- `job/kill` 不在 Remote allowlist，停止按钮不显示；等上游。
- 上传进度/取消 UI、20 MiB 软提示、跨断线恢复。
- 完整模型服务商表单（多模型、headers/compat/重试策略、编辑已有路由）未做；当前是单模型最小路由 + 通用凭据。

## P3 已落地/限制

已落地：

- DSH 子代理续聊：子会话底部“继续”入口，调用 `subagents/prompt`；已从 Session 的 `subagent.mode` 投影识别 `continuable`，一次性子代理不显示输入入口。
- 插件 bundle 开关：`pluginManager/listBundles` / `setBundleEnabled`，设置页“主机扩展”列出可远程开关的官方 bundle；安装/卸载/插件配置不支持。
- Office/PDF/图片预览：`officeToPdf/render` 与 `workspaceFiles/readBytes` 的 data 由插件规范化为 base64 JSON；PDF/Office 只渲染第 1 页，图片按 8 MiB 上限。
- 会话目标：`goals/get/create/pause/resume/complete/clear` 已接入聊天底部目标条，显示轮次/阻塞原因。
- 定时任务：`schedule/list` 只读列表已接入会话菜单；创建仍走官方 `schedule_create` 工具，编辑/删除/历史未做。

未做/限制：

- 子代理 mode 缺失/未知时仍按只读处理；跨父会话恢复未做。
- Office/PDF 多页、文本选择、下载未做；目标编辑、定时任务编辑/删除/历史未做。

## 验证

已运行：

```sh
ANDROID_HOME=/home/lvziw/Android/Sdk BUILD_SCRATCH_ROOT=/home/lvziw/.build-lagoon-remote \
  ./gradlew :app:compileDebugKotlin :app:assembleDebug

ANDROID_HOME=/home/lvziw/Android/Sdk BUILD_SCRATCH_ROOT=/home/lvziw/.build-lagoon-remote \
  ./gradlew :app:testDebugUnitTest
```

结果：完整 `:app:testDebugUnitTest` **612 项通过、0 失败、1 跳过**（隔离构建目录），debug APK 构建通过。未执行真实 Host 联调，也未在真机验证；这是当前环境限制，不是代码未完成项。

新增单测：

- `RemoteOAuthTest`：provider 解析、QR 轮询、account me、跨源拒绝、revoke。
- `RemoteCatalogTest`：目录编解码、重复 Host 拒绝。
- `RemoteAccountManagerTest`：邮箱登录后走账号设备目录、过滤 Client、Host-stable 设备 slot、刷新仍走设备 token、pin、目录保存。
- `RemoteFailureTest`：状态码到失败类型、指纹失败、离线可重试。
- `RemoteCapabilitiesTest`：`workspace.upload.v1` 门控。
- `DshApiTest`：插件 bundle 解析、`credentials/describe/set/unset` 通道、`goals/get/create/pause` 会话级 RPC、`workspaceFiles/readBytes`/`officeToPdf/render` 预览、子代理 `subagent.mode` 解析、`schedule/list`、`settings/mutate` 自定义模型路由。

## 下一步

1. P1：同 Host 连接注册表、失败动作按钮、连接耗时。
2. P2：上传进度/取消、完整模型服务商/自定义路由表单。
3. P3：子代理续聊补 continuable 判定/刷新；Office/PDF/图片首屏预览和目标条已接入；定时任务未做。
4. P4：评估已完成，见 [`DSH_REMOTE_WEBRTC_EVAL.md`](DSH_REMOTE_WEBRTC_EVAL.md)；Host 提供能力前继续 Relay。

## P0 修复：Remote 会话读取不再静默空白（2026-11-05）

真机症状：首页有会话，但每条会话内容都是空白。排查发现链路本身可用（用假 Host 跑通了
Noise + relay + `harness.remote.stream.open` + 1.5 MB 分片快照 + `session/follow` 解码），
真正的问题是**失败被折成空数据**、**凭据刷新可能永久卡死**、以及**缺字段导致的可诊断性为零**。

已修复：

- `DshApi.fetchFirstFrame` 失败不再返回 null：抛带端点与上游 code 的 `ApiException`，
  `projects()` 也不会把“读不到工作区”当成“没有工作区”。聊天页因此能看到真实原因。
- `session/follow` 首帧必须是 `snapshot`（否则重试一次并报 `stream/not-snapshot`）；
  首屏快照补 `assistantStream:true / maxMessages:500 / turnWindow{50,2}`，与官方客户端窗口一致，
  正在跑的轮次也能看到已产出的内容。
- 空会话的 `cursor` 为 `-1` 或 `0` 时同样写入分页切点，翻页不再永久为空。
- 子代理会话使用 `kind:"subagent" + parentSessionId + childSessionId + mode` 地址
  （Host 对 `kind:"session"` 会报 `session/agent-busy`）。
- 凭据刷新引入 `transitionAt`：超过 90s 的 `REFRESHING` 视为进程被杀/断网并自动重试，
  失败保留旧令牌，只有 401/403 才要求重新登录；注册失败恢复原记录。
- 流事件不再抛异常：坏帧只失败所属流（先送完已缓冲的帧），未知消息类型/控制帧忽略并计数。
- 连接与能力协商失败带上层原因（不再只有“没能建立加密中继”）。
- transfer 响应上限 16 MiB → 64 MiB，并给出明确超限文案。
- 新增 `FakeRemoteHost` 端到端测试（Noise + 控制帧 + 加密 relay + DSHF 分片），覆盖
  小快照、1.5 MB 分片快照、非 snapshot 首帧、流失败四种情形。

排查入口：`adb logcat -s DshApi` 会打印每次快照的 `records/messages/cursor/hasMore`。

仍未做：独立 Relay 数据通道（中枢若在 `connect.accepted` 里下发 `relayEndpoint/relayTicket`，
当前会明确报“暂不支持独立 Relay 端点”）、进程级单连接注册表、流 seq 缺口自动补洞。

## P1 收尾（第四阶段）

- **独立 Relay 数据通道**：中枢若在 `connect.accepted` 里下发 `relayEndpoint/relayTicket/relayEpoch`，
  客户端按参考实现 `relay.attach → relay.ready → transport.selected → secure.handshake` 附加数据通道
  （15s 超时，`relay.detach`/断开都会明确失败）；就绪后业务密文只走该通道，控制通道保留 ping 与握手。
- **进程级单连接注册表**：`RemoteConnectionRegistry` 按 `(Hub, 账号, hostId)` 共享 `RemoteSession` 并引用计数，
  首页监控、控制器打开的会话与「测试连接」不再各开一条隧道互相顶掉；最后一个持有者释放时才真正关闭。
- **实时缺口补齐**：`session/follow` 的实时事件出现 seq 缺口时，用 `session/page`（`beforeSeq=current`,
  `throughSeq=current-1`）补齐后再投影当前事件；缺口超过 2000 条或补齐不完整时显式触发快照重读。
- **代际参数表**：`workspace/follow` 的参数形状按代际不同（0.2.1 线 `{args:{}}`，新代际 `{args:{request:{}}}`），
  首次被 `gateway/arguments-invalid` 拒绝后自动换形状并记住，不再把代际写死。
- 主动关闭连接会让流正常结束，不再向消费者抛“连接已关闭”。
- 新增测试：独立数据通道端到端、连接注册表引用计数、缺口补齐、`workspace/follow` 参数回退。

## 连接生命周期、单 Follow 与连接医生（本轮）

目标是让“连接失败”和“会话没有内容”在数据模型上就不可能混为一谈。

### 可观测状态

`RemoteSession` 现在暴露 `StateFlow<RemoteConnectionStatus>`：

- 阶段：`IDLE / CREDENTIALS / CONNECTING / HANDSHAKING / READY / FAILED / CLOSED`；
- 每次新建隧道 `generation + 1`，并记录 `startedAt / readyAt`；
- 记录中枢 `serverVersion`、Host 插件版本、DSH 版本、`transport`、是否走独立 Relay 数据通道、
  协商出的 capability 集合；
- 失败折叠成 `RemoteFailureSnapshot(kind, message, retryable, code)`，不再只有一句 IOException。

`hello` 现在上报 `clientVersion`（App 版本），中枢的设备列表不再永远停留在注册时的版本；
`hello.ack` 的 capability 按“已提供能力的子集”校验，既接受旧中枢省略字段，也拒绝中枢选择
客户端没广告的能力。

### 一个会话一条长期 follow

`session/follow` 从“打开取快照 → 取消”改成每个会话一条 **Follow Generation**：

- 首帧必须是 `snapshot`，否则报 `stream/not-snapshot` 并重开；
- 快照直接服务 `messagesPage(cursor=null)`，流继续留着做实时；
- 快照之后、`events()` 挂上之前的增量进入有界缓冲（512 条），事件流建立时补送；
- 重连后的新快照只补投 `seq > lastSeq` 的记录，避免整页重复；
- 保留 `session/page` 的 seq 缺口补齐（上限 2000 条），缺口过大交给下一次快照整体校正；
- 子代理地址、`assistantStream`、`maxMessages/turnWindow` 与官方客户端窗口一致。

打开动作由 `followOpenLock` 串行化，消息页和 `$events` 不会再各开一条。

### 每台 Host 的流预算

`RemoteGateway` 的逻辑流上限是**每台 Host 共用**的，首页监控、会话页和后台作业都算在一起。
旧的 16 条上限过于贴近实际占用：12 条 follow 加 `$events`/`workspace/follow` 已到 14 条，
首页刷新再并发读 8 个目录的 `job/list` 就会越界，表现为打开某些会话时报
`Remote 流数量超过上限`。

现在的配额与兜底：

- `$events` 1 条 + `workspace/follow` 1 条；
- `session/follow` 严格不超过 `WATCH_LIMIT`（12）条；按最近活跃淘汰，当前打开的会话固定保留；
- `job/follow` 1 条；`job/list` 用 `Semaphore(2)` 限流，且取消时同步从网关移除；
- `RemoteGateway` 的硬上限提升到 32，仅作兜底；
- 首页只为运行中的会话开 follow，空闲会话由列表轮询维护；
- follow 意外断开时只重连仍在 watch 集合里的会话；
- 连接医生增加“流预算”一行（占用/上限、按 endpoint 的计数、最近流错误）。

### 内容与状态解耦

- `events()` 不再因为 `$events` 失败而结束：只把 `eventsReady` 置 false、按 1s→30s 退避重连，
  并发出 `server.connection` 事件；总览据此显示“实时同步恢复中”，而不是整页变空。
- `$events` 不可用时，每 15s 读一次 `session/list.running`，只对变化补发 `session.status`；
  审批/问答会显示实时通道断开，但“某会话正在跑”不会跟着消失。
- `workspace/follow` 的实时流和一元基线共用同一个参数代际探测结果；被
  `gateway/arguments-invalid` 拒绝后切换形状并重试，不再只有基线会回退。
- 控制器解析“当前 client”：监控重建关闭旧 client 后，按需从总览重新取租约，避免对着已关闭的
  传输发请求。

### 连接医生

`HarnessClient` 上不暴露诊断类型；`DshApi` 实现内部 `RemoteDoctorSource`，由控制器读取。
“网络与连接”对话框新增：

- 中枢/账号/主机/客户端版本；
- 连接阶段、generation、路径（控制通道 Relay / 独立数据通道）；
- 中枢、插件、DSH 版本与协商能力；
- 凭据状态（正常/刷新中/待重试/已过期，access/refresh 剩余时间）；
- 最近快照 `records/messages/cursor/hasMore`；
- 每条 follow 的 `lastSeq/最后帧/重连次数/错误`；
- `$events` 的 ready/generation/clientId/最后事件/重连次数；
- 逻辑流预算：当前占用/硬上限、按 endpoint 的计数、最近一次流错误；
- 最近错误与上游 code，可一键复制。报告只含计数和错误码，不含消息正文或令牌。

### 验证

- 新增 `RemoteConnectionDesignTest`：状态机 READY/generation、单会话一条 follow、`$events` 失败
  不结束事件流、90s 后刷新状态自愈、诊断文本不含令牌。
- 新增流预算回归：首页只为运行中会话开 follow、20 个会话仍限制在 `WATCH_LIMIT`、后台流占满时
  核心事件流仍可建立、`closeNow` 同步归还槽位、网关超过硬上限时拒绝。
- `RemoteRelayTest` 新增 hello `clientVersion` 与 capability 子集协商用例。
- 可重复的真实链路：`scripts/remote-e2e.sh`（自建 Hub + 隔离 `dsh web` Host + `RemoteLiveProbeTest`）。
