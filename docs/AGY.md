# Antigravity 连接

Lagoon 支持通过现有 SSH 设备或 DSH Remote Host 接入 Antigravity CLI 的原生 ACP 接口。AGY 是独立的 Harness，同一设备上已有的 OpenCode、DSH 和 Codex 资料、开关、草稿与会话不会被替换。

## 准备与添加

1. 在开发机的服务用户环境中安装支持 `agy acp` 的 Antigravity CLI，并在该用户的终端运行 `agy acp --login` 完成登录。Lagoon 不接收 Google 密码或登录令牌。
2. SSH 方式：在 Lagoon 中配置 SSH 设备并核对 Host Key，刷新该设备的 Harness 发现，然后添加 Antigravity。SSH 发现只说明命令存在、`agy acp --help` 可运行，登录与会话可用性以连接时结果为准。开发机需要 POSIX shell、SFTP 和 Python 3，`agy` 必须在登录 shell 的 PATH 中。AGY 1.5 的会话列表只返回第一个工作目录，Lagoon 会只读查询该用户 AGY 目录数据库以识别其他工作区；数据库或 Python 不可用时不会声称按目录筛选的列表完整。
3. DSH Remote 方式：Host 必须启用 Antigravity 后端，并加载 Lagoon 的 [Host 连接器](../host-connector/README.md)。默认旧版 stream-json 参数会转换为 `acp`；自定义启动参数必须以 `acp` 开头。Host 就绪后刷新设备发现并添加 Antigravity。旧 Host 或未登录的 AGY 不会被当作已就绪的原生 ACP 服务。
4. 在 AGY 连接内添加开发机上已经存在的绝对工作目录，再打开已有会话或创建新会话。项目目录会由 SSH SFTP 或 Remote Host 验证；不会因为在手机上添加项目而创建远端目录。

## 能力与边界

- 可浏览会话和历史，创建并继续会话，发送文本及受支持的图片，查看流式文本、思考与工具输出，并按当前会话提供的选项选择模型和思考强度。模型目录由会话协商，空白草稿在创建会话前不推测可用模型。
- 工具审批使用 AGY 实际提供的选项 ID；支持的结构化提问为单层字符串表单。Remote 只允许单次授权。停止请求发出后，最终状态以 AGY 的轮次结果为准。
- AGY 可能把额度耗尽等提示作为普通文本返回，同时将轮次标为 `end_turn`；Lagoon 保留上游结果并显示提示文字，不从文案推断失败状态。
- 工作区文件浏览和文本预览只读；SSH 路径经 SFTP realpath 校验并限制在工作区内，Remote 由 Host 的会话作用域和能力控制。当前不提供 AGY 文件搜索，Remote 不提供文件引用。
- Remote Host 明确启用 `agent.acp.terminal.v1` 后可在会话中使用 Host 终端；终端附着与输入受 Host 归属检查，断开附着不会自动关闭进程。SSH 直连目前不提供应用内终端。
- SSH 的 `agy acp` 进程跟随 SSH 通道；断线或关闭连接可能中断正在执行的轮次，不保证后台继续执行。Remote Host 可在连接断开后继续执行，但流缓冲有限；重连后的历史以 Host 可读取的 AGY 持久化 transcript 为准，未持久化的内容无法保证恢复。
- AGY ACP 没有可供本客户端验证的全局活动会话清单，其他客户端或旧连接发起的任务状态不会被推测为已结束。当前没有会话重命名、归档、删除、分叉、回退、压缩和斜杠命令接口。
- 固定版本的 Remote Host 每个工作目录最多返回 100 条 AGY 会话且不提供分页；达到上限时 Lagoon 显示不完整列表错误，而不是把截断结果当作完整目录。Remote 历史读取需要 Host 声明 `agent.acp.transfer.v1`。
