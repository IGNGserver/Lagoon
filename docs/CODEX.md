# 连接 Codex

Lagoon 可以通过 **SSH** 或 **DSH Remote** 连接开发机上的 Codex。Codex 在线程所在的开发机上运行，使用开发机现有的 Codex 配置和登录状态；Lagoon 不需要保存或复制 Codex 的登录凭据。

这两种连接方式的功能和断线行为不同。添加前请先阅读下方限制；实际可用功能还取决于开发机上的 Codex 版本、Remote Host 版本及权限配置。

## SSH 连接

1. 在开发机安装 Codex CLI，并在需要时以将用于 SSH 登录的同一用户完成登录和配置。
2. 确认该用户的非交互式登录环境能够找到 `codex` 命令。例如，从另一台机器使用该用户 SSH 登录后检查 `command -v codex` 和 `codex --version`。
3. 在 Lagoon 添加 SSH 设备，填写地址、用户名及 SSH 凭据，核对并确认主机指纹。
4. 保存后打开这台设备的页面，点「刷新发现」确认 Codex 已安装。只读发现会检查命令和版本，不能证明账户已登录或服务已可用。
5. 在设备页启用 **Codex**：它与同一设备上的其他 Harness 各自启停，未启用的 Harness 不会占用连接与流量。启用后可进 Harness 详情单独测试 Codex 接入。

SSH 路径通过非交互式 shell 查找并启动 `codex app-server`。只在交互式 shell 初始化文件中设置的 PATH、版本管理器或桌面应用内置的 Codex，可能不会被发现。当前发现与启动使用 POSIX shell，**尚未验证 Windows 原生 SSH**。

Lagoon 为这条 SSH 连接启动自己的 App Server 进程，并通过标准输入输出与它通信。断开连接或关闭该进程可能中断正在执行的任务；不要将它视为能保证断线后继续运行的持久服务。它也不会自动附着到其他终端或桌面应用中已经运行的 Codex 进程。已保存的线程历史和另一个进程中的实时任务是两回事。

## DSH Remote 连接

1. 先配置 DSH Remote 中枢并将开发机注册为可信 Host。
2. 在开发机的 Remote Host 插件中启用 Codex（上游配置项 `codex.enabled`），确保 Host 进程能找到或已配置 Codex 可执行文件，并使用正确用户的 Codex 登录和配置。具体插件配置方式请参考 [ds-harness-remote 文档](https://github.com/liguobao/ds-harness-remote/blob/bb33f0a6eb676b61f40a9bbf7d365e8a5e93b81b/docs/protocol.md#codex-app-server-domain)。
3. 在 Lagoon 的 Remote Host 服务列表刷新，找到 **Codex · App Server**，然后加入并启用。

Host 只有在启用 Codex、成功初始化 App Server 并完成账户状态探测后，才会声明 Codex 能力。若显示“未声明”，请检查 Host 插件版本、启用状态、可执行文件和 Host 日志；安装了 Codex 本身并不足以证明 Remote 通道可用。Host 的具体登录与服务配置应在开发机处理，不要把账户令牌粘贴到 Lagoon。

Remote Host 按自己授权的项目根目录或已知线程工作目录限制访问范围。它的 Codex 通道不是任意 App Server 接口的透明代理：例如永久删除线程、回滚、后台终端和任意配置修改不在当前通道白名单内。图片使用 Host 声明的大消息传输能力；通用文件附件不能按图片处理。

Remote 的命令和文件修改审批只提供 Host 支持的单次接受、拒绝或取消，不能当作整个会话的永久授权。连接断开时，Host 会拒绝该连接尚未处理的审批。Host 重启后，Lagoon 需要重新发现服务并读取历史；不要重复发送结果未知的操作。

## 使用与限制

- 工作区和线程来自 Codex；查看历史不会把它们写入 DSH 的会话库。
- 可用的线程操作、模型、推理强度、图片和审批入口取决于当前连接及服务端实际支持。SSH 仅对已验证的 Codex 0.167.2 开放永久删除线程；其他版本和 Remote 不显示删除入口。部分 Codex 或桌面应用功能尚未在 Lagoon 开放，尤其不要假定回滚、终端和所有 MCP 交互都可用。
- 停止任务需要等待服务端确认最终状态；网络中断时，发送或停止操作可能处于结果未知状态。恢复连接后先查看线程历史与当前状态，不要直接重发。
- SSH 与 Remote 分别保存为独立连接。通过两条路径看到相似的线程，并不证明它们连接到同一个运行中的 App Server，也不会自动合并通知或切换路径。
- Codex 协作事件会显示服务端实际提供的信息；目前不提供完整子代理会话树或从事件跳转子代理会话。Remote Host 会限制可列出的线程类型。
- 仅查看已保存历史与继续线程是不同操作。继续线程可能加载工作区配置和 MCP 服务；如果远端环境或模型提供方发生变化，继续操作可能失败，但历史仍可能可读。

## 验证状态

开发期间已使用本机 Codex CLI 的隔离配置验证 App Server 的初始化、账户状态读取与线程列表协议；这不等于完成真实 SSH、已登录账户、Remote 中枢、Android 真机或断线续跑验收。暂未确认完整的 Codex、Remote Host 和操作系统版本兼容矩阵；实际使用前应先在自己的开发机上测试连接、历史、审批和断线恢复。
