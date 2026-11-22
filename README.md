# Lagoon

> 官网与下载入口：<https://lagoon.igng.net/>

面向多种 coding agent harness 的原生 Android 移动控制端。Kotlin + Jetpack Compose 界面采用小米 HyperOS / MIUIX 设计规范，引入连续曲率 Squircle 与科技蓝语义调色板。

自纯 SSH 架构重构起，Lagoon **只通过 SSH 连接用户自己的开发机**，已彻底移除 DSH Remote Hub 模式与 HTTP 明文/直连模式（详见 [`docs/REMOTE_EOL.md`](docs/REMOTE_EOL.md) 与 [`docs/DEVICE_MODEL.md`](docs/DEVICE_MODEL.md)）。一台 SSH 设备即一个主机记录，可在同一套加密通道上按需发现并挂载五种原生 Harness：

- **DeepSeek Harness (`dsh`)**：经 SSH 本地转发拉起或复用远端 `dsh web`（`127.0.0.1` 随机端口），走 Typert RPC + mux 事件流；支持启动命令与工作目录按 Harness 独立配置，以及扩展、模型密钥与自定义服务商管理
- **OpenCode 原生服务 (`opencode`)**：经 SSH 读取远端服务注册并通过本地转发附着运行中的 OpenCode 2.x 服务端，支持在设备页或首页一键拉起服务
- **OpenCode ACP (`opencode acp`)**：经 SSH stdio JSON-RPC 直连，实时流式对话、工具轨迹与目录工作区管理
- **Codex (`codex app-server`)**：经 SSH stdio JSON-RPC 直连开发机上的 Codex，支持浏览和继续线程、发送任务、流式输出与审批（见 [`docs/CODEX.md`](docs/CODEX.md)）
- **Antigravity (`agy acp`)**：经 SSH stdio JSON-RPC 直连已登录的 Antigravity，支持会话、模型、审批与只读文件浏览（见 [`docs/AGY.md`](docs/AGY.md)）

Lagoon 目前只维护 Android 客户端。v3 曾提供自托管中枢与网页端，现已停止维护并从本仓库移除；相关说明见 [`docs/HUB_EOL.md`](docs/HUB_EOL.md)。

## 功能

- **两层纯 SSH 设备模型**：`SSH 设备` → `Harness`。支持密码（含 `keyboard-interactive`）、私钥、密钥优先三种认证方式；内置全局 SSH 密钥库（Ed25519 / RSA / ECDSA 软件密钥、AndroidKeyStore 硬件密钥、OpenSSH 证书、生物识别保护、一键部署公钥到 `~/.ssh/authorized_keys`）与严格 TOFU `SHA256:` 主机指纹校验；详见 [`docs/SSH_HOSTS.md`](docs/SSH_HOSTS.md) 与 [`docs/DEVICE_MANAGEMENT.md`](docs/DEVICE_MANAGEMENT.md)
- **统一设备管理与分组**：首页左上角范围面板支持「全部设备」、自定义设备组、单台 SSH 设备与单个 Harness 切换；设备组按 SSH 设备 ID 持久化，新增或移除 Harness 时自动跟随；设备级与 Harness 级开关相互独立，停用后不占连接与流量，草稿、项目与缓存完整保留
- **完整会话体验**：项目与工作区浏览、异步任务（运行中引导与排队）、Agent / Model / 思考强度按 Harness 独立配置与可见性过滤、斜杠命令、文本 / Reasoning / Tool 轨迹折叠、停止与权限/问答处理、子会话、代码改动、文件浏览与搜索、重命名、置顶、分叉、归档与离线缓存
- **全设备任务总览与通知**：聚合运行中、未读完成、待回复与失败计数，支持首页一键跳转最需处理的会话；Android 16+ 使用系统实时更新（Live Updates），Android 15 及以下使用普通通知与首页概览，详见 [`docs/LIVE_UPDATES.md`](docs/LIVE_UPDATES.md)
- **网络安全边界**：`network_security_config.xml` 全局禁止明文外部 HTTP，仅允许 SSH 本地端口转发使用的 `127.0.0.1` / `localhost` 回环通信；所有 SSH 密钥与凭据由 Android Keystore 加密存储
- **应用内检查更新**：设置页按 SemVer 检查 GitHub 正式版或预发布版，校验大小、SHA-256、包名、版本码与签名后交由系统安装器安装

## 本地构建

需要 JDK 17+、Android SDK Platform 36。执行：

```sh
ANDROID_HOME=/path/to/Android/Sdk ./gradlew :app:testDebugUnitTest :app:assembleDebug
```

本仓库把 Gradle build 输出放在 `BUILD_SCRATCH_ROOT`（默认 `~/.build`），每个 checkout 使用独立路径 `<scratch>/Lagoon/<checkout-hash>/app/outputs/apk/debug/app-debug.apk`。

## 上手步骤

1. 在电脑上启用 SSH 服务，并安装所需工具（如 `dsh`、`opencode`、`codex` 或 `agy`）。主机需提供 POSIX shell（Linux / macOS / WSL）。
2. 在 Lagoon「设备 › 添加 SSH 设备」中填写主机地址（支持直接粘贴 `user@host` 或 `ssh -p 端口 user@host`）、用户名与验证方式（推荐在密钥库生成 Ed25519 / 硬件密钥并一键安装公钥）。
3. 核对并固定远端 `SHA256:` 主机指纹，保存后自动进入设备页只读发现可用 Harness，按需打开对应开关即可开始对话。

## 更名与升级迁移

- 应用显示名为 `Lagoon`，`applicationId` 保持为 `com.igng.opencode.lagoon` 且签名不变，可直接覆盖安装。
- 从旧版（v5.3.x）升级到纯 SSH 版本时，所有既有 SSH 设备、全局 SSH 密钥库（含硬件密钥别名）、TOFU 主机指纹、SSH Harness、设备组、草稿、项目与 SQLite 对话历史会自动无损迁移；已下线的 DSH Remote 与 HTTP 直连记录会自动清理，并在首页与设备页显示一次性可关闭提示。
- 深链接支持 `lagoon://`（向下兼容 `opencode-lagoon://`）；`docs/releases/` 下的历史发布说明保持原样。

## 验收边界

Android 构建与单元测试可验证编译、协议解析、存储迁移与状态机逻辑。真实 SSH 主机环境、各 Harness 远端进程生命周期、断网重连与 Android 16 实时更新提升仍需在目标开发机与真机上验收。Android 15+ 的 `dataSync` 前台服务受系统运行时长限制。
