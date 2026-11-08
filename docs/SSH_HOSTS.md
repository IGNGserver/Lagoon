# SSH 主机

Lagoon 的 SSH 主机通过标准 SSH 连接到一台 POSIX 电脑，在远端启动或复用 `dsh web`，再用本地
端口转发把 DSH 的 loopback HTTP/WebSocket 带回来。配置项、bootstrap 流程和错误语义对齐
dsh-Go，但实现是 Kotlin/JSch 的独立重写（dsh-Go 是 AGPL-3.0，不直接复制其源码）。

## 1. 配置

第一屏只保留三项：地址、用户名、密码。其余字段收在「更多选项」里，已保存主机的非默认值会自动
展开该区域，折叠行摘要也会标出 `端口 2222`、`私钥登录` 之类的差异，避免自定义配置被藏住。

| 字段 | 位置 | 说明 |
|---|---|---|
| `host` | 第一屏 | SSH 地址；可直接粘贴 `user@host:port` 或整行 `ssh -p 22 user@host`，App 拆分到用户名与端口 |
| `user` | 第一屏 | 登录用户；地址里带 `user@` 时自动填入 |
| `password` | 第一屏 | 密码认证；只保存在加密 SSH 存储 |
| `port` | 更多 | 默认 22；地址里带 `:port` 时自动填入 |
| `auth` | 更多 | `PASSWORD`（默认）或 `PRIVATE_KEY`；选择私钥后凭据行切换为私钥块 |
| `privateKey` / `passphrase` | 更多 | OpenSSH/PEM 私钥；支持导入文件与显示/隐藏，私钥密码可选 |
| `launchCommand` | 更多 | 远端启动命令，默认 `dsh web`；App 经 `sh -c` 追加 `--host 127.0.0.1 --port 0 --no-open` |
| `workingDirectory` | 更多 | 可选；`~` 由远端 shell 展开。目录不存在则启动失败。没有明确项目目录时，新建会话使用它 |
| `name` | 更多 | 显示名称；留空使用主机地址 |
| `hostKeyType` / `hostKeyFingerprint` | 更多 | 首次连接确认后固定的主机密钥类型和 SHA-256 `SHA256:…` 指纹，两者都要一致；只在此处重置 |
| `notifications` / `groupId` / `enabled` | 设备详情 | 设备层偏好；SSH 表单只透传，不再重复提供 |

保存有两个出口：主按钮「连接」先读取并固定主机指纹，再保存并连接；「仅保存，不连接」不访问主机
（离线也能保存），指纹留到第一次真正连接时确认。启用的设备会保持连接，没有单独的自动连接开关。

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
- 之后指纹或密钥类型变化直接拒绝连接，错误里带“已记住/这次收到”，必须在编辑页显式“重置主机指纹”。
- 指纹保存失败会返回错误，不会假装已经固定。配置文件损坏时拒绝继续写入，避免一次保存覆盖其余主机。
- 修改 host 或 port 会自动丢弃旧 pin；后台重连不会弹窗，未固定指纹的主机只显示“需要确认”。
- 用「仅保存，不连接」存下的主机没有 pin，第一次真正连接时才走同一条确认流程。

### 远端 dsh web

状态目录与 dsh-Go 兼容：`~/.dsh-app-remote/`

| 文件 | 用途 |
|---|---|
| `web.pid` | 本 App 启动的 dsh web 进程 |
| `web.st` | 进程启动时间，避免 PID 复用误杀 |
| `web.log` | stdout/stderr，每次启动清空；解析最后一条 `token=` 链接 |
| `web.cmd` | 启动命令；命令变化会重启 |

- 启动命令经 `sh -c` 执行：包装 shell 把命令行放进后台，把这个后台 pid 写入 `web.pid`，然后 `wait` 陪着它，避免 shell 退出时把 dsh 收掉。输出写入 `web.log`。命令按 POSIX 单引号转义，换行会被拒绝。`nohup` 包在外层。
- 优先复用本 App 自己启动的实例；PID 不属于当前实例时清理 pid 并重新启动。
- 探测到别的 `dsh web` 已经在跑时直接失败，不再启动第二个实例去抢同一个 profile。
  探测只认**当前用户**（`pgrep -u "$(id -u)" -f '[d]sh web'`）：共享主机/跳板机上别人的 `dsh web`
  不该让这台设备的连接失败。
- 启动链接必须解析出 `http://127.0.0.1:<端口>`。如果远端 profile 配了 `publicUrl`（打印出来的不是
  loopback 地址），启动会以「远端 dsh web 没有监听 loopback 地址」失败并给出这行日志：SSH 隧道只能
  接 loopback，需要在远端去掉 `--public-url` / `publicUrl` 配置。
- 找不到 `dsh` 时搜索 npm/nvm/volta/bun/pnpm/Homebrew 常见路径；仍找不到则提示
  `npm i -g @deepseek-ai/dsh`。
- 日志中的 `token=…` 在错误详情里统一替换为 `token=•••`。

### 本地转发与 DSH 登录

- 本地 `ServerSocket` 绑定 `127.0.0.1:0`；端口在 SSH 重连期间保持不变，DshApi 的 base URL 因此稳定。
- 每个本地 socket 开一条 JSch `direct-tcpip` channel 到远端 dsh 的 loopback 端口。
- DSH 请求带 `Host: 127.0.0.1:<dshPort>`，与远端 dsh 的 loopback Host fence 和 Cookie audience 对齐。
- SSH 断开时销毁现有 channel，但保留本地监听；重连后新连接继续使用同一端口。
- 远端 dsh 进程死亡时，keepalive 对 loopback 发一次 `GET /`。只有收到 `HTTP/` 响应才算还活着；
  端口还在听但没有 HTTP 响应会触发 bootstrap 重启，产生新的 DshApi 代次（新 token、新端口）。
- 隧道里的 Cookie 不写入服务器凭据。远端进程换代后用新 token 重新登录；新 token 被拒时保留上一张
  还能用的 Cookie，而不是提示用户去粘贴启动链接。
- 事件流在连接离开「已连接」或 DshApi 被替换时结束当前代次，并绑定下一代，不会停在旧 WebSocket 上。
- 本地转发在 SSH channel 关闭时立刻关掉对应 socket，不再先空等数秒。

## 3. 认证与安全

- Host Key 采用 TOFU + 显式 pin；不使用 `StrictHostKeyChecking=no`。
- SSH 密码/私钥/passphrase 使用 Android Keystore AES/GCM 加密，和服务器凭据分开存储。
- 私钥只在本机加密保存；导入文件上限 64 KiB。
- `dsh web` 始终只监听远端 loopback，不暴露到网络。
- `launchCommand` 是用户明确输入的命令行，经 `sh -c` 加 `eval` 执行并做 POSIX 单引号转义；工作目录同样转义，`cd` 失败则整次启动失败。
- 应用禁用备份（`allowBackup=false`）。

## 4. 限制

- 需要远端 POSIX shell（Linux / macOS / WSL）。Windows 原生 OpenSSH 的 cmd/PowerShell 不支持，
  与 dsh-Go 一致；Windows 电脑使用 DSH Remote 或 WSL。
- 不支持 SSH agent、ProxyJump、证书主机密钥、硬件密钥、2FA OTP。keyboard-interactive 会把密码或私钥口令填进每一个提示。
- 一台 SSH 主机只管理一个 `dsh web`。同一台机器上的第二个 DSH 实例需要另建 SSH 主机配置，且不能和已有 `dsh web` 同时占用默认 profile。
- 连接/会话期间远端 dsh 会持续运行；关闭 App 不会停止远端进程。这和 DSH Remote 不同：Remote 断开的是中继，不会在远端留下常驻 web。
- 没有 Remote 的账号目录、主机发现和连接医生。失败停在 `SshLinkStatus`；主机密钥和认证错误不会在网络恢复时自动重试。
- 编辑页的「测试连接」用表单草稿单独拨号，不复用已经连上的旧配置。保存后先丢掉监控客户端，再强制关闭旧 link。
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
| `shared/.../SshHost.kt` | 配置/密钥/状态/错误/指纹/脚本工具；`SshAddress` 解析 `user@host:port` |
| `app/.../core/SshHostStore.kt` | 配置 + Keystore 加密密钥 |
| `app/.../core/ssh/JschSshTransport.kt` | JSch 传输、Host Key、exec、direct-tcpip |
| `app/.../core/ssh/RemoteDshBootstrap.kt` | probe/start/stop 与日志解析 |
| `app/.../core/ssh/SshForwarder.kt` | 稳定本地端口转发 |
| `app/.../core/ssh/SshHostLink.kt` | 状态机、keepalive、重连、DshApi 代次 |
| `app/.../core/ssh/SshDshClient.kt` | `HarnessClient` 适配 |
| `app/.../core/ssh/SshHostManager.kt` | 每主机 link 注册表 |
| `app/.../ui/SshHostForm.kt` | 三字段表单 + 「更多选项」、仅测试连接、主机指纹弹窗 |
