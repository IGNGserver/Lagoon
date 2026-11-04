# DSH Remote 分阶段实现记录

> 对应设计：[`DSH_GO_REMOTE_DESIGN.md`](DSH_GO_REMOTE_DESIGN.md)。
> 基线：`cd45f72`；本次实现没有真实 Remote Host 可用，所有网络行为仍需真机/真实 Host 验收。

## 状态总览

| 阶段 | 状态 | 本次落地 |
| --- | --- | --- |
| P0 账号与接入 | 基本完成 | OAuth/QR API、主机目录、账号管理器、登录/多选/指纹核对 UI、远程主机管理、添加/编辑服务器分流、typed failure |
| P1 连接韧性 | 部分完成 | 已保存 Remote profile 复用 monitor 连接做测试；服务器编辑页连接详情与加密 RPC 往返测量；同 Host 全链路单例、完整失败动作仍未完成 |
| P2 远程任务 | 部分完成 | 工作区上传（发送时）、排队消息编辑/插话/撤回、`job/follow` 输出；`job/kill` 受上游 allowlist 阻塞；主机模型/密钥设置未完成 |
| P3 主机扩展 | 部分完成 | DSH 子代理续聊入口已接入；Office/PDF、插件开关、定时任务/目标未做 |
| P4 传输升级 | 未开始 | WebRTC LAN/P2P/TURN 评估 |

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

- `testServer` 对当前已连接的 DSH Remote profile 复用活动连接做 health，避免同一 Host 被第二条连接替换。
- DSH Remote 服务器编辑页显示连接方式、地址、状态、路径（Relay）、加密、服务版本、主机指纹。
- “网络与连接”对话框支持一次加密 RPC 往返测量（`harness.transport.describe`），明确标注不是网络层 RTT。

未完成：

- 进程级 `(origin, account, hostId)` 连接注册表；monitor/controller/test 仍可能各开连接。
- 网络详情中的连接耗时、最近观测；RPC 往返已有，但只是单次估算。
- 失败动作按钮（重新登录/重新信任/重新接管）与 ConnectionStrip 阶段细分。

## P2 已落地/未完成

已落地：

- `RemoteSession.uploadWorkspaceFile`：顶层 `workspace.upload.open/chunk/commit/abort`，不走 Gateway；校验 1 GiB 协议上限、`chunkBytes`、ack、最终路径；失败 abort，不自动重放 commit。
- `DshApi.send`：Remote 下非图片附件上传到当前会话工作区，消息正文写 `已上传文件：./name`；图片仍走 image 块。
- `HarnessClient.updateQueue` / `DshApi.updateQueue` / 控制器 `updateQueued`；聊天里排队中的消息可编辑、立即插话、撤回。
- `HarnessClient.followJobOutput` / `DshApi` 用 `job/follow` 流式输出；控制器和现有后台命令 sheet 已接入。

未完成/阻塞：

- `job/kill` 不在 Remote allowlist，停止按钮不显示；等上游。
- 上传进度/取消 UI、20 MiB 软提示、跨断线恢复。
- 远程主机模型服务商/密钥设置（P2 剩余）。
- 主机设置页、插件开关、Office 预览、定时任务/目标。

## 验证

已运行：

```sh
ANDROID_HOME=/home/lvziw/Android/Sdk BUILD_SCRATCH_ROOT=/home/lvziw/.build-lagoon-remote \
  ./gradlew :app:compileDebugKotlin :app:assembleDebug

ANDROID_HOME=/home/lvziw/Android/Sdk BUILD_SCRATCH_ROOT=/home/lvziw/.build-lagoon-remote \
  ./gradlew :app:testDebugUnitTest --tests 'com.igng.opencode.lagoon.core.Remote*Test'
```

结果：编译与 debug APK 构建通过；Remote 定向单测通过。未执行真实 Host 联调，也未在真机验证。

新增单测：

- `RemoteOAuthTest`：provider 解析、QR 轮询、account me、跨源拒绝、revoke。
- `RemoteCatalogTest`：目录编解码、重复 Host 拒绝。
- `RemoteAccountManagerTest`：邮箱登录、发现、Host-stable 设备 slot、pin、目录保存。
- `RemoteFailureTest`：状态码到失败类型、指纹失败、离线可重试。
- `RemoteCapabilitiesTest`：`workspace.upload.v1` 门控。

## 下一步

1. P1：连接注册表、失败动作、网络详情测量。
2. P2：上传进度/取消、主机模型设置。
3. P3：子代理续聊补 continuable 判定/刷新，再做 Office 预览、插件开关、自动化/目标。
4. P4：WebRTC 评估；在完成前不承诺大文件/低延迟路径。
