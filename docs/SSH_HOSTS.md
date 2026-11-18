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

DSH 是允许多实例的服务（每个 `dsh web` 进程有独立的启动令牌和端口），所以这台电脑上**已经**
在运行的 `dsh web`（桌面版、命令行、另一台手机）不会阻止 Lagoon 连接，也不会被 Lagoon 停止。
Lagoon 只管理自己记录的那一份：状态目录为 `~/.lagoon-ssh-dsh/`，含进程 ID、进程启动时间、
启动命令、工作目录、日志和启动锁。令牌只用于当前运行代次，错误信息中的 `token=` 会脱敏。
对旧版 Lagoon 或 dsh-Go 的 `~/.dsh-app-remote/`，应用只读检查，仅在用户、进程代次、启动命令
都符合时复用；无法确认时直接忽略该记录并启动自己的实例，绝不向它发信号。

记录过期按「过期」处理而不是「冲突」：进程已退出、启动时间或 boot id 不符、日志里没有启动
链接、启动命令改过、目录权限被放宽，都会重新启动一份；只有 `~/.lagoon-ssh-dsh/` 本身是符号
链接或属于别的用户时才拒绝（此时请在电脑上处理该路径）。应用自己的一代进程如果还能证明身份
（PID + 启动时间），会先正常结束再启动新的，避免留下孤儿进程。启动锁是 `~/.lagoon-ssh-dsh/
.bootstrap.lock` 目录，内含持有者 PID：等待方在持有者进程消失时立即接管，因此不需要
`flock`、`python3` 等额外解释器。

需要新实例时，默认运行 `dsh web --host 127.0.0.1 --port 0 --no-open`；用户配置的启动命令也会
追加这些选项。首次启动会初始化 `~/.dsh/profiles/web`（安装依赖），因此启动等待上限为 180 秒。
工作目录不存在时直接报错。自定义命令由用户输入并经远端 shell 执行；添加设备或刷新发现不会
执行它。关闭应用不会停止已启动的远端 DSH。

DSH 和 OpenCode 连接前，会对远端 loopback 上实际接收连接的进程、注册信息和进程代次进行
验证，再发送登录令牌、Cookie 或 Basic 凭据。该证明每代连接只做一次：远端有 `python3`
（macOS 还需 `lsof`）时由辅助程序核对 socket 属主；缺少这些工具时降级为「启动记录校验」
（PID、启动时间、boot id 与本次启动相符），并在诊断里提示降级，不会因此拒绝连接。
判定为其它进程占用时才拒绝并提示检查权限或环境。Android 本机的转发端口另有进程内随机接入
凭据，其他应用不能仅凭本机端口访问远端服务。DSH 请求的 `Host` 与远端 loopback 地址保持
一致；每个 SSH 绑定使用独立连接池，避免不同隧道复用同一 loopback 端口的连接。
Codex 与 Antigravity 使用各自的 SSH stdio 协议。

DSH 审批或消息提交后如果连接中断、结果无法确认，应用保留待办或草稿并提示先检查远端状态，
不会因网络失败自动重复执行；切换 Agent 被拒绝、附件读取失败时也不会悄悄发送不完整的消息。

## 限制和验证

- 需要 SSH 登录后的 POSIX shell、可用的远端 exec 通道及 loopback 访问。Windows 原生 cmd /
  PowerShell 暂不支持此模式，请使用 WSL 或其他已支持的接入方式。
- 远端 OpenCode/DSH 的进程身份检查依赖操作系统允许当前用户读取相关进程与 socket 信息。
  缺少 `python3`/`lsof` 时按上面的降级规则连接；只有辅助程序明确判定 socket 属于其它进程时
  才要求修复远端环境后重试。
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
