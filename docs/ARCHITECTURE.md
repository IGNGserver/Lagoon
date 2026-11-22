# 实现结构与验收

- `shared/.../core/DeviceModel.kt`: 纯 SSH 两层设备模型真源（`SshDevice`、`DeviceHarness`、`HarnessProtocol`、`HarnessConfig`、`DeviceProjection`）及运行态只读视图 `ServerProfile`，契约见 [`DEVICE_MODEL.md`](DEVICE_MODEL.md)。
- `app/.../core/DeviceStore.kt` + `LegacyToSshMigration.kt`: 单一真源设备存储（SP `devices` + 加密 `ssh-secrets`）与从 `v5.3.x`（SSH + 退场 Remote/直连）平滑升级的幂等迁移。
- `app/.../core/SshKeyStore.kt` + `BiometricKeyVault.kt` + `core/ssh/*`: 全局 SSH 密钥库（软件 Ed25519 / 硬件 Keystore ECDSA / 证书）、JSch 连接池（`SshTransportPool`）、统一 5 种 Harness 运行时（`SshHostManager` / `SshHostLink` / `SshDshBootstrap` / `SshStdioRpcTransport` / `SshVerifiedTunnel` / `SshForwarder`），详见 [`SSH_HOSTS.md`](SSH_HOSTS.md)。
- `shared/.../core/OpenCodeApi.kt`: OpenCode 2.x 原生 REST / SSE / WebSocket（经 `NativeOkHttpTransport` + SSH loopback 转发访问），版本差异由 `ApiCapabilities.kt` 从 `/openapi.json` 解析，契约由 `app/src/test/resources/opencode-2.2.6-contract.json` 回归固定。
- `shared/.../core/DshApi.kt`: DeepSeek Harness Typert RPC Gateway 客户端（经 SSH loopback 转发访问 `dsh web`，含根路径启动令牌换 Cookie、`/api/remote.mux` 多路复用流、`$events` 审批/问答瀑布、目标/调度/插件/工作区/正文搜索/终端），完整契约见 [`dsh-protocol.md`](dsh-protocol.md)。
- `shared/.../core/CodexApi.kt` & `AgyApi.kt`: Codex App Server 与 ACP（Antigravity ACP / OpenCode ACP）协议适配器，经 SSH exec stdio 通信。
- `shared/.../core/HarnessClient.kt`: 协议无关的统一 Harness 接口，控制器、总览与通知层只依赖该接口。
- `app/.../core/DeviceCoordinator.kt` + `SshKeyCoordinator.kt` + `LagoonController.kt`: 设备/发现/分组/密钥管理与对话/总览/通知状态协调；异步写操作经不可变 `OperationContext` 防串写。
- `app/.../core/ServerStore.kt` + `OfflineCache.kt` + `TranscriptStore.kt`: 按稳定 `HarnessId` 隔离的运行态持久化（本地项目、草稿、文件引用、任务账本、未读通知、模型偏好、加密离线缓存与 OpenCode SQLite 原文缓存）。
- `ui/`: 小米 HyperOS / MIUIX 界面，信息架构为 `设备 → Harness → 项目/工作区 → 会话 → 对话`；整页路由与预测式返回由 `AppBackStack.kt` 与 `MainActivity.kt` 驱动。
- `system/`: 任务前台监控（`TaskMonitorService`）、Android 16 Live Updates 与普通通知（`TaskNotifications`）、通知动作（`NotificationActionReceiver`）、15 分钟后台同步（`BackgroundSyncWorker`）与 GitHub Releases 自更新（`AppUpdater`）。

## 必须在真实环境检查

1. 添加一台 SSH 设备（密码与密钥两种方式），核对 `SHA256:` 指纹后进入设备详情自动完成只读发现，确认发现不启动远端服务。
2. 同设备同时启用 DSH、OpenCode Native、Codex、Antigravity、OpenCode ACP 中的多项：确认共享一条 SSH 连接，停用/移除一项不影响兄弟 Harness，修改 DSH 启动命令不打断 OpenCode/Codex。
3. 在手机发送任务、触发权限审批与问答、切换 Wi-Fi / 蜂窝网络恢复、检查系统实时更新与通知点击深链（`lagoon://server/...`）。
4. 从 `v5.3.0-rc.16` 覆盖安装升级：确认既有 SSH 设备、密钥、停用开关、草稿、OpenCode SQLite 缓存完整保留，历史 Remote / 直连资料平滑退场并提示。
5. 设置 → 版本与更新：检查更新、下载校验与覆盖安装。
