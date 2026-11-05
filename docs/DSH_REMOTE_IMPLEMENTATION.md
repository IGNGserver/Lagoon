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
  - `DELETE /api/v1/devices/self`
  - 入口路径限定同源 `/api/v1/auth/`，拒绝控制字符、`.`/`..`、`%` 段；`scanUrl` 同源。
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
- `RemoteAccountManagerTest`：邮箱登录、发现、Host-stable 设备 slot、pin、目录保存。
- `RemoteFailureTest`：状态码到失败类型、指纹失败、离线可重试。
- `RemoteCapabilitiesTest`：`workspace.upload.v1` 门控。
- `DshApiTest`：插件 bundle 解析、`credentials/describe/set/unset` 通道、`goals/get/create/pause` 会话级 RPC、`workspaceFiles/readBytes`/`officeToPdf/render` 预览、子代理 `subagent.mode` 解析、`schedule/list`、`settings/mutate` 自定义模型路由。

## 下一步

1. P1：同 Host 连接注册表、失败动作按钮、连接耗时。
2. P2：上传进度/取消、完整模型服务商/自定义路由表单。
3. P3：子代理续聊补 continuable 判定/刷新；Office/PDF/图片首屏预览和目标条已接入；定时任务未做。
4. P4：评估已完成，见 [`DSH_REMOTE_WEBRTC_EVAL.md`](DSH_REMOTE_WEBRTC_EVAL.md)；Host 提供能力前继续 Relay。
