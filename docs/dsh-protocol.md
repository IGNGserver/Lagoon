# DeepSeek Harness 协议适配（dsh web）

本文记录 Lagoon 的 DSH 适配所依赖的 Host 契约。所有条目都在真实实例上核对过：
`@deepseek-ai/dsh@0.2.0-rc.2`（npm `latest`）与源码 `deepseek-ai/deepseek-harness` master
（2026-10 的 `0.2.1-rc.2` 线）。DSH 处于 developer preview，协议可能破坏性变化；本文件
描述当前支持的形状，代码按「方法级探测 + 明确降级」处理漂移。

## 支持范围

- 目标表面是 `dsh web`（`dsh --profile web`）暴露的 Typert RPC Gateway。
- 同一套 RPC 也是官方 Web UI 的客户端协议；CLI/MCP 走 `/connect` 时同样落到这里。
- `dsh sdk` / `dsh acp` / `headless` 是 stdio 表面，能力远小于 Gateway，不在适配范围。
- 其他 surface（`tui`）与 `dsh web` 共用 `$DSH_HOME` 的会话存储，但运行状态、审批
  瀑布只在各自的进程内可见。

## 鉴权

- 启动令牌（launch token）每个进程随机生成，只出现在 `dsh web` 打印的 URL 里。
- **浏览器式交换（主路线）**：`GET /?token=<token>` → `303` + `Set-Cookie: dsh-auth-<hash>=v1.<payload>.<sig>`
  （也见过 `302`）。Cookie 30 天有效（`cookieMaxAgeDays`），签名密钥持久化在
  `$DSH_HOME/.credentials.yaml`，因此 **dsh 重启后仍然有效**；对应「共享浏览器 Peer」，
  `interactions=answer`，可回答审批/问答。
- **API Peer 交换（备选）**：`GET /connect?token=<token>&interactions=answer|none` →
  302 + API Cookie。仅 0.2.1 线起提供（0.2.0-rc.2 上为 404）。该 Cookie 绑定独立 Peer，
  生命周期 WebSocket 关闭或 Host 重启后失效，必须重新 connect。
- 每个请求都要求 `Host` 属于 loopback / 监听绑定 IP / `--trusted-host` 之一（否则 403），
  未认证返回 401。Cookie 按 authority（host:port，TLS 时含 scheme）绑定。

## 传输

- 一元调用：`POST /api/<namespace>/<method>`，信封
  `{type:"client-request", rpcId, method:"<namespace>/<method>", payload:{args:{…}}}`。
  `args` 里是**方法形参名**（`signal` 除外）：常见为 `{request:{…}}`，
  `session/list` 为 `{_request:{}}`，无参方法为 `{}`。响应
  `{type:"server-response", rpcId, result:{ok:true, value}|{ok:false, error:{code,message,details}}}`。
  错误码形如 `gateway/arguments-invalid`、`gateway/lookup-unavailable`、`session/not-found`。
- 流：`/api/remote.mux` WebSocket。客户端发
  `{type:"open", streamId, endpoint, payload:{args:{…}}}`；服务端回
  `{type:"item", streamId, value}` / `{type:"end", streamId}` /
  `{type:"error", streamId, error:{code,message,details}}`；取消发 `{type:"cancel", streamId}`。
- 全局事件流：mux 上 `open` 的 `endpoint` 为 `$events`，`payload:{args:{}}`。首帧
  `{type:"ready", clientId, host:{home}}`；之后 `{type:"emit", event, args}`、
  `{type:"waterfall", event, eventId, agentId, request}`、`{type:"cancel", eventId}`。
- 审批/问答回应：一元 `POST /api/$events/result`，`args` 为
  `{clientId, eventId, outcome}`；`outcome` 为 `{kind:"result", value?}` 或
  `{kind:"rejected", error}`。审批 outcome 词汇：`allowed-once` / `rejected` /
  `cancelled` / `unavailable`；问答结果值是 `{answers:[{id, selected, custom?}]}`。
- 精确路由（非 RPC，同一认证栅栏内）：
  - `GET /api/changes.summary?sessionId=<id>&seq=<n>` → 该 `workspace/changes` 事件宣告的
    轮次改动摘要 `{turn, files:[{path, display, added, deleted, binary?, oversized?}], total, added, deleted}`；
  - `GET /api/changes.diff?sessionId=<id>&seq=<n>&index=<i>` → 单文件对比
    `{kind:"text", hunks:[{oldStart, oldLines, newStart, newLines, lines[]}], coarse}` 或
    `{kind:"binary"|"oversized"}`；
  - `GET /api/file?path=<绝对路径>` → 会话媒体读取（图片预览等）。
  摘要与对比只在该 Host 进程内、该会话存活期间可用；重启后旧轮次没有对比。

## 用到的命名空间（0.2.x 已核对）

| 命名空间 | 方法 | 用途 |
|---|---|---|
| `session` | `list` / `search` / `create` / `rename` / `fork` / `prompt` / `cancel` / `updateQueue` / `selectModel` / `modelCatalog` / `page` / `follow` / `projections` / `control` | 会话生命周期、历史、实时 |
| `workspace` | `follow` / `create` / `rename` / `delete` / `archiveSession` / `unarchiveSession` / `pinSession` / `unpinSession` | 工作区（项目）与会话归档/置顶 |
| `commands` | `list` / `execute` | 斜杠命令（含 `/compact`） |
| `workspaceFiles` | `stat` / `read` / `readBytes` / `list` / `changes` | 工作区文件浏览（需 `workspaceFileScopeId`） |
| `job` | `list` / `follow` / `kill` | 后台作业（后台 shell / 长任务） |
| `terminal` | — | 交互终端（未接入） |
| `userQuestions` | 瀑布 + `userQuestions` 投影 | 结构化提问 |
| `subagent` | — | 子代理（当前用列表行 `parentSessionId` 折算） |
| `$events` | `$events` / `$events/result` | 全局事件、审批/问答瀑布与结算 |

关键请求形状（0.2.0-rc.2 实测）：

- `session/create`：`{request:{cwd?, workspaceId?, sessionId?, agentPreset?}}`（没有 `title`）。
- `session/prompt`：`{request:{requestId, sessionId, mode:"queue"|"steer", content:[…], clientTimeZone?}}`。
  `content` 支持 `{type:"text",text}`、`{type:"image",mediaType,data,name?}`、
  `{type:"file",receiptId}`（receipt 需要先上传）。
- `session/fork`：`{request:{sessionId, atSeq?}}`。
- `workspace/archiveSession` / `unarchiveSession`：`{request:{sessionId, stopActivity?}}`。
- `commands/list`：`{agentId}`；`commands/execute`：`{agentId, line, submittedAttachments:[]}`。
  `agentId` 就是会话 id（Agent 与会话一一对应，lookup 会复用/恢复）。
- `workspaceFiles/list`：`{workspaceFileScopeId, path}` → `{path, entries:[{name,type,size?}], truncated?}`。
- `workspaceFiles/read`：`{workspaceFileScopeId, path, range:{offset?,limit?}}` → `{text,…}`；
  二进制以 `workspace-file/not-text` 失败。
- `job/list`：`{request:{sessionId}}` 流，首帧 `{type:"rows", jobs:[JobView]}`。
- `session/follow`：`{request:{address:{kind:"session",sessionId}, assistantStream:true, …}}`。

## 事件与帧形状

`session/follow` 帧：

- `{type:"snapshot", header, cursor, records, hasMore, projections, assistantStream?}`；
- `{type:"event", event:{type, seq, time, data}}`（`SessionEventEntry`）；
- `{type:"assistant-stream", frame}`：`start` / `chunk` / `end`。`chunk.chunk` 是
  `StreamChunk`：`block-start` / `text-delta{index,text}` / `reasoning-delta{index,text}` /
  `tool-call-delta` / `block-end` / `usage` / `finish`。

会话日志事件（`SessionEventMap`，插件可合并扩展）：

- `turn/start{turn}`、`turn/end{turn, reason}`（`completed` / `aborted` / `blocked` /
  `error{error}` / `max-tokens` / `interrupted` / `forked`）、
  `step/start{turn,step}`、`step/end{turn,step}`；
- `user/message`（`UserMessage`：`{id, role, content, source}`）、
  `assistant/message{turn,step,message,stream,usage?,interrupted?}`、
  `assistant/attempt{turn,step,stream}`、`tool/call{turn,step,callId,name,arguments}`、
  `tool/result{turn,step,message{toolCallId,content,isError?},error?,meta?}`；
- 插件事件：`session/title{title,messageSeqs,source}`、`workspace/changes{turn}`、
  `compaction/start{compactionId,turn}` / `compaction/end{compactionId,turn,error?}`、
  `command/run{commandId,name,args?}` / `command/done{commandId,kind,text?}`、
  `approval/asked{id,toolName,callId?,reason?}` / `approval/decided{id,outcome}`。

全局事件（`$events` 转发名单）：

- `api-session/added(summary)`、`api-session/removed(sessionId)`、
  `api-session/status(sessionId, running)`、`api-session/activity(sessionId, updatedAt)`、
  `api-session/error(sessionId, message)`；
- 瀑布：`approval/request(request:{toolName,callId?,reason?})`、
  `user-questions/request(request:{questions:[{id,question,detail?,options?,multiSelect?}], wait?})`。

投影（`session/list` 行的 `projections.values`、`session/projections`）：

- `inbox`：`{"next-turn":[UserMessage],"next-step":[UserMessage]}`；
- `userQuestions`：`{active:[{callId,questions,state}], settled:[…]}`；
- `agentPreset`、`modelSelection`、`sessionListMetadata{blank,lastPromptAt}`、`permissions` 等。
- 注意：会话工作目录在列表行的顶层 `cwd`，不是投影字段。

## Lagoon 的折算

- 项目 = Workspace（`workspace/follow` 首帧 `value.items`）；会话目录取行内 `cwd`。
- `activeSessions` = 列表行 `running`；后台工作 = `job/list` 的运行作业 + 子会话。
- 时间线由 `turn/step/assistant/tool` 事件折算为控制器认识的 `session.*` 事件；
  实时文本来自 `assistant-stream` 帧（`text-delta`/`reasoning-delta` 折算为
  `session.text.delta` / `session.reasoning.delta`，耐久消息到达时用 `*.ended` 收口）。
- 审批/问答：瀑布事件折算为 `permission.asked` / `form.created`，回应走 `$events/result`；
  其他客户端结算时收到 `cancel` 帧，按本地待办移除并折算为 `permission.replied` / `form.cancelled`。
- 删除会话 = 归档（DSH 没有删除）；回退（revert）在 DSH 里没有对应语义，保持不支持；
  手动压缩走 `/compact` 命令。
- 改动列表取最近若干 `workspace/changes` 事件的摘要并按路径合并（最新轮次优先），
  逐文件对比来自 `/api/changes.diff`。

## 已知限制

- DSH 处于 developer preview，跨版本破坏性变更；适配按当前 0.2.x 形状实现，遇到未知
  错误码/形状时给出明确失败而不是猜测。
- 会话删除、消息级回退、文件搜索（`searchFiles`）没有对应 RPC。
- 改动摘要/对比仅在 Host 进程内、会话存活期间提供；重启后旧轮次不可读。
- 工作区文件访问需要会话身份（`workspaceFileScopeId`）；没有会话的目录无法浏览。
- 文件附件需要先经 `fileUploads` 上传换取 receipt；当前只支持内联图片。
