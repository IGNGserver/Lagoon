# SSH 设备

Lagoon 通过 SSH 连接 Linux、macOS 或 WSL 开发机，这是本应用唯一的设备连接方式。一台 SSH 设备可按需挂载并独立启停 **DeepSeek Harness (DSH)**、**OpenCode 2.x Native**、**OpenCode ACP**、**Codex** 和 **Antigravity ACP**；设备地址、登录凭据与主机指纹由同一设备上的全部 Harness 共享。

设备与 Harness 的模型、身份与存储契约见 [`DEVICE_MODEL.md`](DEVICE_MODEL.md)。

## 添加与发现

1. 点「+ 添加设备」直达 SSH 设备表单，填写地址、用户名和验证方式（密码 / 密钥 / 密钥优先）。地址支持 `user@host:port` 或 `ssh -p 端口 user@host`；不支持的 SSH 命令选项会明确提示，不会静默忽略。
2. 点「保存并检测」，先核对 SSH 主机密钥的类型和 `SHA256:` 指纹。确认后保存设备、跳入设备详情页并执行一次只读发现。发现不安装、不启动、也不停止任何远端程序。
3. 也可以先离线保存，稍后在设备详情页或首页状态条确认指纹。地址或端口变化后旧指纹不会沿用；指纹或密钥类型变化时拒绝连接，并在重新信任前展示旧值与新值。
4. 在设备详情页按需添加或开关 Harness。打开设备页仅展示缓存结果，「刷新发现」才会再次连接探测。

SSH 密码、私钥、口令与硬件/生物识别密钥均由 Android Keystore 保护；交互式验证（含一次性验证码）仅在前台本次握手中使用，不落盘。

## 5 种 Harness 的 SSH 运行时

同一台设备的全部 Harness 共用 `SshTransportPool` 中的**一条已认证 JSch 物理会话**（开启底层 SSH KeepAlive，支持网络切换自动探活恢复），按传输形态分为两类：

1. **本地隧道型（DSH、OpenCode Native）**：
   - 使用每链路隔离的 `SshForwarder`（本地随机 `127.0.0.1` 端口 + 32 字节随机接入令牌）与隔离的 OkHttp `ConnectionPool`，并在发送凭据前经 `SshVerifiedTunnel` 证明远端 loopback（支持 `127.0.0.1` 与 `[::1]`）监听套接字的进程属主。
   - **DSH**：由 `SshDshBootstrap` 在 `~/.lagoon-ssh-dsh/` 下管理 Lagoon 自己的 `dsh web --host 127.0.0.1 --port 0 --no-open` 实例（使用 `set -o noclobber` 原子启动锁、进程代次与安装指纹 `web.ver` 校验；只读兼容复用可验证的旧 `~/.dsh-app-remote/`，绝不干扰用户已运行的桌面版或其它客户端）。启动命令与默认工作目录配置在 DSH Harness 详情中，修改它们不会断开同机其他 Harness。
   - **OpenCode Native**：附着 `$XDG_STATE_HOME/opencode/service.json`（或显式路径如 `service-local.json`），要求严格属主证明；应用不接管进程，但支持用户在设备详情/Harness 详情/首页状态条显式触发「启动或重启服务」（`opencode service start|restart`）。
2. **标准流型（Codex、Antigravity ACP、OpenCode ACP）**：
   - 共用 `SshStdioRpcTransport` 在复用的 SSH 会话上打开非 PTY exec 通道，按协议收发 JSONL / JSON-RPC 2.0 帧，并通过同一连接池执行 SFTP 只读工作区浏览（ACP）或目录查询（AGY）。
   - 同样暴露实时 `SshLinkStatus`、支持网络切换恢复与显式重试。

## 限制与验证

- 需要 SSH 登录后的 POSIX shell、可用 exec 通道及 loopback 转发。Windows 原生 cmd / PowerShell 暂不支持，请使用 WSL。
- 自动化测试基于临时目录、回环 `sshd`（Apache MINA SSHD）与虚拟凭据验证；真实开发机与 Android 真机仍需按目标环境验收。
