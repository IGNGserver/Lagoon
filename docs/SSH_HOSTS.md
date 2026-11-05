# SSH 主机

Lagoon 的 SSH 主机通过标准 SSH 连接到一台 POSIX 电脑，在远端启动或复用 `dsh web`，再用本地
端口转发把 DSH 的 loopback HTTP/WebSocket 带回来。配置项、bootstrap 流程和错误语义对齐
dsh-Go，但实现是 Kotlin/JSch 的独立重写（dsh-Go 是 AGPL-3.0，不直接复制其源码）。

## 1. 配置

| 字段 | 说明 |
|---|---|
| `name` | 显示名称；留空使用主机地址 |
| `host` / `port` / `user` | SSH 地址、端口（默认 22）、登录用户 |
| `auth` | `PASSWORD` 或 `PRIVATE_KEY` |
| `password` | 密码认证；只保存在加密 SSH 存储 |
| `privateKey` / `passphrase` | OpenSSH/PEM 私钥；支持导入文件 |
| `launchCommand` | 远端启动命令，默认 `dsh web`；App 追加 `--host 127.0.0.1 --port 0 --no-open` |
| `workingDirectory` | 可选；`~` 由远端 shell 展开，同时作为默认会话目录 |
| `hostKeyType` / `hostKeyFingerprint` | 首次连接确认后固定的主机密钥，SHA-256 `SHA256:…` |
| `autoConnect` | 保存在 `ServerProfile`；默认开启 |

一个 SSH 主机对应一个 `ServerProfile`（`kind = DSH`、`sshHostId != null`）。`url` 是稳定的
`ssh://user@host:port` 展示身份，不参与 HTTP 凭据 origin；SSH 密码/私钥放在独立的
`SshHostStore`，使用 Android Keystore 加密。

## 2. 连接流程

```text
SSH 连接
  → Host Key 校验/首次确认
  → 探测或启动远端 dsh web
  → 本地 127.0.0.1:<ephemeral> 端口转发到远端 127.0.0.1:<dshPort>
  → DSH 登录（Host: 127.0.0.1:<dshPort>）
  → keepalive + 断线/进程重启恢复
```

### Host Key

- 首次连接只做握手读出公钥，不发送认证凭据；弹窗显示类型和 `SHA256:` 指纹，用户与
  `ssh-keygen -lf` 结果核对后才固定。
- 之后指纹变化直接拒绝连接，错误里带“已记住/这次收到”，必须在编辑页显式“重置主机指纹”。
- 修改 host 或 port 会自动丢弃旧 pin；后台重连不会弹窗，未固定指纹的主机只显示“需要确认”。

### 远端 dsh web

状态目录与 dsh-Go 兼容：`~/.dsh-app-remote/`

| 文件 | 用途 |
|---|---|
| `web.pid` | 本 App 启动的 dsh web 进程 |
| `web.st` | 进程启动时间，避免 PID 复用误杀 |
| `web.log` | stdout/stderr，每次启动清空；解析最后一条 `token=` 链接 |
| `web.cmd` | 启动命令；命令变化会重启 |

- 启动命令：`<launchCommand> --host 127.0.0.1 --port 0 --no-open`，`nohup` 后台运行。
- 优先复用已有实例；PID 不属于当前实例时清理 pid 并重新启动。
- 找不到 `dsh` 时搜索 npm/nvm/volta/bun/pnpm/Homebrew 常见路径；仍找不到则提示
  `npm i -g @deepseek-ai/dsh`。
- 日志中的 `token=…` 在错误详情里统一替换为 `token=•••`。

### 本地转发与 DSH 登录

- 本地 `ServerSocket` 绑定 `127.0.0.1:0`；端口在 SSH 重连期间保持不变，DshApi 的 base URL 因此稳定。
- 每个本地 socket 开一条 JSch `direct-tcpip` channel 到远端 dsh 的 loopback 端口。
- DSH 请求带 `Host: 127.0.0.1:<dshPort>`，与远端 dsh 的 loopback Host fence 和 Cookie audience 对齐。
- SSH 断开时销毁现有 channel，但保留本地监听；重连后新连接继续使用同一端口。
- 远端 dsh 进程死亡时，keepalive 会探测其 loopback 端口并触发 bootstrap 重启，产生新的
  DshApi 代次（新 token、新端口）。

## 3. 认证与安全

- Host Key 采用 TOFU + 显式 pin；不使用 `StrictHostKeyChecking=no`。
- SSH 密码/私钥/passphrase 使用 Android Keystore AES/GCM 加密，和服务器凭据分开存储。
- 私钥只在本机加密保存；导入文件上限 64 KiB。
- `dsh web` 始终只监听远端 loopback，不暴露到网络。
- `launchCommand` 是用户明确输入的命令，会按 shell 执行；其余路径/工作目录均做 POSIX 单引号转义。
- 应用禁用备份（`allowBackup=false`）。

## 4. 限制

- 需要远端 POSIX shell（Linux / macOS / WSL）。Windows 原生 OpenSSH 的 cmd/PowerShell 不支持，
  与 dsh-Go 一致；Windows 电脑使用 DSH Remote 或 WSL。
- 不支持 SSH agent、ProxyJump、证书主机密钥、2FA OTP；keyboard-interactive 会用同一密码回答。
- 一台 SSH 主机只管理一个 `dsh web`。同一台机器上的第二个 DSH 实例需要另建 SSH 主机配置。
- 连接/会话期间远端 dsh 会持续运行；关闭 App 不会停止远端进程。
- 首次连接、dsh 不存在、端口转发被禁止、主机密钥变化等错误都需要在真机上按提示处理。

## 5. 测试

常规套件（不依赖网络）覆盖：

- `SshHostTest`：配置 JSON、shell 引号/路径、bootstrap 启停/复用、缺少 dsh、token redaction。
- `SshHostStoreTest`：配置与密钥分离、认证方式切换、pin/reset、删除。
- `SshForwarderIT` / `JschSshTransportIT` / `SshHostLinkIT`：可选的真实主机测试，默认跳过。

真实主机测试：

```sh
LAGOON_SSH_IT_HOST=<ssh-host> \
LAGOON_SSH_IT_USER=<user> \
LAGOON_SSH_IT_KEY=$HOME/.ssh/id_ed25519 \
ANDROID_HOME=$HOME/Android/Sdk \
./gradlew :app:testDebugUnitTest --tests '*IT'
```

本机已验证（2026-11-05，Ubuntu 26 VM + dsh 0.2.1-rc.5）：

- JSch 私钥认证、Ed25519 主机密钥指纹与 `ssh-keygen -lf` 一致；
- `direct-tcpip` 能读取远端 SSH banner；
- bootstrap 启动/复用 dsh web，本地转发 + Host header 登录 DSH，读取 `health` 和会话页；
- 杀掉远端 dsh 后，keepalive 探测到端口消失并重启进程，客户端拿到新代次；
- 未固定指纹返回 `HOST_KEY_UNKNOWN`，错误 pin 返回 `HOST_KEY_CHANGED`，均不会自动信任。

## 6. 代码落点

| 文件 | 内容 |
|---|---|
| `shared/.../SshHost.kt` | 配置/密钥/状态/错误/指纹/脚本工具 |
| `app/.../core/SshHostStore.kt` | 配置 + Keystore 加密密钥 |
| `app/.../core/ssh/JschSshTransport.kt` | JSch 传输、Host Key、exec、direct-tcpip |
| `app/.../core/ssh/RemoteDshBootstrap.kt` | probe/start/stop 与日志解析 |
| `app/.../core/ssh/SshForwarder.kt` | 稳定本地端口转发 |
| `app/.../core/ssh/SshHostLink.kt` | 状态机、keepalive、重连、DshApi 代次 |
| `app/.../core/ssh/SshDshClient.kt` | `HarnessClient` 适配 |
| `app/.../core/ssh/SshHostManager.kt` | 每主机 link 注册表 |
| `app/.../ui/SshHostForm.kt` | 配置、测试、主机指纹弹窗 |
