# 连接 Codex

Lagoon 通过 **SSH 设备**连接开发机上的 Codex CLI（`codex app-server`）。Codex 在开发机上以 SSH 登录用户的现有配置和登录态运行；Lagoon 不保存或复制 OpenAI / Codex 登录凭据。

## 准备与添加

1. 在开发机安装 Codex CLI，并以用于 SSH 登录的同一用户完成登录和配置。
2. 确认该用户的非交互式登录环境能找到 `codex`（例如 SSH 登录后检查 `command -v codex` 与 `codex --version`）。
3. 在 Lagoon 添加 SSH 设备、填写地址与凭据并核对主机指纹。
4. 在设备详情页点击「刷新发现」确认已安装 Codex，然后添加并启用 **Codex**。它与同一设备上的其他 Harness 共用一条物理 SSH 会话并各自独立启停。

## 使用与限制

- SSH 路径通过非交互式 POSIX shell 查找并启动 `codex app-server --listen stdio://`。**尚未验证 Windows 原生 SSH**，Windows 请使用 WSL。
- Lagoon 为该 SSH 连接启动独立的 App Server 进程并通过标准输入输出通信；断开连接或关闭进程可能中断正在执行的任务，不会自动附着到桌面应用或其他终端里的 Codex 进程。
- 工作区和线程来自 Codex，与 DSH / OpenCode 会话库互相独立。
- 可用的线程操作、模型、推理强度、图片和审批入口取决于服务端实际支持。对已验证的 Codex 0.167.2 开放永久删除线程；部分桌面功能（如回滚、终端与全部 MCP 交互）尚未开放。
- 停止任务需等待服务端确认最终状态；网络中断后先查看线程历史与状态，不盲目重发。
