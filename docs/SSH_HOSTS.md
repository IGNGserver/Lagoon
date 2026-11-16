# SSH 设备

Lagoon 使用 SSH 连接 Linux、macOS 或 WSL 电脑。一台 SSH 设备可以分别添加 DeepSeek Harness
(DSH)、OpenCode、Codex 和 Antigravity；设备地址、登录信息与主机指纹由这些 Harness 共用。
SSH 接入源、设备和 Harness 的身份与开关规则见 [DEVICE_MODEL.md](DEVICE_MODEL.md)。

## 添加与发现

1. 填写地址、用户名和验证方式。地址支持 `user@host:port` 或 `ssh -p 端口 user@host`；
   电脑上的 `-i` 文件不能直接用于手机，需要导入或选择密钥。不支持的 SSH 命令选项会提示，
   不会被静默忽略。
2. 点「保存并检测」，核对 SSH 主机密钥的类型和 SHA-256 指纹。之后应用保存设备，登录 SSH，
   只读发现已安装或注册的 Harness。发现不安装、不启动、也不停止远端程序。
3. 在设备页添加需要的 Harness。打开设备页本身只显示已有结果；「刷新发现」才会重新检测。
   部分检测失败、过期和尚未验证的结果会保留相应说明。

也可以离线保存设备，稍后从设备页检测并确认指纹。指纹或类型变化时连接会被拒绝；重新信任前
会显示旧值与新值。地址或端口变化后旧指纹不会沿用。设备页提供对已启用 Harness 的「重试连接」。
SSH 密码、私钥及口令由 Android Keystore 加密保存；交互式验证（包括一次性验证码）按服务器提示
逐项输入，只在前台、本次握手中使用，不会自动填入已保存密码或私钥口令。

SSH 发现会在一条经过指纹验证的连接上分别检查各 Harness；一个检查失败不会伪装成其他程序
未安装。OpenCode 2 的默认注册和开发通道注册是不同槽位：默认文件为
`$XDG_STATE_HOME/opencode/service.json`（未设 XDG 时为 `~/.local/state/opencode/service.json`），
开发通道为同目录的 `service-local.json`。已有默认槽位与已确认的默认路径复用原有记录；其他
显式注册路径保留各自身份。未发现默认服务时可在电脑上执行 `opencode service start` 后刷新。
该命令由 [OpenCode 官方文档](https://github.com/anomalyco/opencode/blob/dev/services/site/src/docs/content/troubleshooting.mdx)说明。

DSH、Codex 和 Antigravity 的发现会检查可执行程序；找到程序不等于已登录或可正常工作，实际
状态以连接后返回的信息为准。对自定义 DSH 启动命令，发现不会运行该命令，也不能因找不到全局
`dsh` 就断定自定义命令不可用。

## DSH 进程与本地连接

Lagoon 的托管状态目录为 `~/.lagoon-ssh-dsh/`，含进程 ID、进程启动时间、启动命令、工作目录、
日志以及启动锁。令牌只用于当前运行代次，错误信息中的 `token=` 会脱敏。对旧版 Lagoon 或
dsh-Go 的 `~/.dsh-app-remote/`，应用只读检查；仅当用户、进程代次、启动命令等信息符合时复用。
旧目录不会被本次启动流程改写。其他客户端不参与 Lagoon 的锁，因此跨应用同时启动仍存在竞争，
不能保证全局互斥；无法确认现有实例时会报错，不能自动停止不明进程或强制重启服务。

需要新实例时，默认运行 `dsh web --host 127.0.0.1 --port 0 --no-open`；用户配置的启动命令也会
追加这些选项。工作目录不存在时直接报错。自定义命令由用户输入并经远端 shell 执行；添加设备或
刷新发现不会执行它。关闭应用不会停止已启动的远端 DSH。

DSH 和 OpenCode 连接前，会对远端 loopback 上实际接收连接的进程、注册信息和进程代次进行
验证，再发送登录令牌、Cookie 或 Basic 凭据。Android 本机的转发端口也有进程内随机接入凭据；
其他应用不能仅凭本机端口访问远端服务。DSH 请求的 `Host` 与远端 loopback 地址保持一致。
服务器上需要 Python 3；macOS 的进程验证还需要 `lsof`。无法验证身份时拒绝发送凭据，并显示
检查权限或环境的提示。Codex 与 Antigravity 使用各自的 SSH stdio 协议。

DSH 审批或消息提交后如果连接中断、结果无法确认，应用保留待办或草稿并提示先检查远端状态，
不会因网络失败自动重复执行；切换 Agent 被拒绝、附件读取失败时也不会悄悄发送不完整的消息。

## 限制和验证

- 需要 SSH 登录后的 POSIX shell、可用的远端 exec 通道及 loopback 访问。Windows 原生 cmd /
  PowerShell 暂不支持此模式，请使用 WSL 或其他已支持的接入方式。
- 远端 OpenCode/DSH 的进程身份检查依赖操作系统允许当前用户读取相关进程与 socket 信息；
  禁用这些能力或缺少验证工具时，需要修复远端环境后重试。
- 用户指定的自定义 DSH 命令和已有第三方进程不会被自动重写或终止。
- 自动化测试使用临时目录、回环 SSH 服务和虚拟凭据。真实主机集成测试默认跳过；其中会终止测试
  专用 DSH 进程的场景还要求显式设置 `LAGOON_SSH_IT_ISOLATED=1` 和隔离启动命令，不能指向
  正在使用的服务。
- Android 真机、实际目标 SSH 服务器、OpenCode/DSH 版本与不同服务器的认证策略仍需按目标环境
  验收；纯 JVM 测试不能替代这些检查。

## 主要代码

| 文件 | 作用 |
|---|---|
| `shared/.../SshHost.kt` | SSH 地址、配置、认证与连接状态 |
| `app/.../core/ssh/SshHostManager.kt` | 主机连接共享、只读发现与生命周期 |
| `app/.../core/ssh/RemoteDshBootstrap.kt` | DSH 托管目录、受限复用和启动 |
| `app/.../core/ssh/SshForwarder.kt` / `SshVerifiedTunnel.kt` | 本地接入验证与远端 socket 身份验证 |
| `app/.../core/ssh/JschSshTransport.kt` | JSch 主机密钥、认证、exec 和转发 |
| `app/.../ui/SshHostForm.kt` / `DeviceManagementScreen.kt` | 配置、指纹确认、检测和设备管理 |
