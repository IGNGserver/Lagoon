# Remote native service protocol v1（实现已验证，部署/真机仍待验收）

承载：现有 ds-harness-remote Noise IK authenticated business channel，固定上游 bb33f0a6eb676b61f40a9bbf7d365e8a5e93b81b。Hub 无业务明文；每个 authenticated connection 独立 NativeServicePeer，断线 closeAll。能力 `harness.services.v1` 不代表 ACP。

## 方法

- `harness.services.describe` `{}` → `{version:1,completeness:"complete"|"partial",services:[{id,name,protocol:"opencode-native",generation,available,capabilities:["http","binary","sse","websocket"]}],issues?:[{code}]}`。
- `harness.services.call` `{serviceId,generation,method,path,headers?,body?,maxResponseBytes}` → `{status,headers,body}` 或 `{status,headers,transferId,totalBytes}`。body 为标准 base64；path 是原生相对路径。maxResponseBytes 1..64MiB，inline body <=512KiB；超过走 transfer。调用只执行一次，不因大响应而重复执行。
- `harness.services.stream.open` `{serviceId,generation,streamId,kind:"sse"|"websocket",path,headers?}` → `{streamId}`。streamId 由客户端生成并在 RPC 前注册。
- `harness.services.stream.send` `{streamId,type:"text"|"bytes"|"close",data?,code?,reason?}` → `{accepted:true}`；bytes data 为标准 base64。每帧 <=512KiB，WebSocket 原生无输入重放。
- `harness.services.stream.close` `{streamId}` → `{closed:true}`，幂等。
- `harness.services.transfer.open` `{serviceId,generation,method,path,headers?,maxResponseBytes,totalBytes}` → `{transferId,chunkBytes:524288}`；大请求预留有界缓冲，不执行业务。
- `harness.services.transfer.chunk` `{transferId,index,data}` → `{accepted:true}`；标准 base64、严格顺序和完整长度。
- `harness.services.transfer.commit` `{transferId}` → 与 `call` 相同的响应。先消费上传 handle 再执行一次；超时未知不再次提交，也不改为 inline 重发。
- `harness.services.transfer.read` `{transferId,index}` → `{index,totalChunks,data}`；chunk <=512KiB，标准 base64，顺序读、校验总长度、最多64MiB。只读已执行 call 的缓存响应，不重执行 mutation。
- `harness.services.transfer.close` `{transferId}` → `{closed:true}`。

所有 stream/transfer handle 绑定当前 authenticated connection，不接受其他连接的 handle。每连接最多16 streams、4 transfers，总缓存<=64MiB，idle TTL 60s。closeAll 取消 HTTP/stream 并销毁所有缓冲，不停止外部进程。

## 事件

`harness.services.frame` data `{streamId,seq,type,...}`，seq 从0连续递增（包含 open/terminal）。
- `open`
- `event` `{id?,event?,data}`（SSE 原始文本）
- `text` `{data}`
- `bytes` `{data}` 标准 base64
- `closing`/`closed` `{code?,reason?}`
- `error` `{code,message}`，message 为安全静态文本

每流有界队列，消费过慢只终止该流；禁止静默丢帧/重排。先到首帧后到 stream.open RPC 响应有效。

## 信任与路由

Host 从本机官方 OpenCode registration 读取 URL/Basic password，验证 loopback root HTTP、正整数PID、版本、受保护路由认证和 `/api/info` PID/version。真实 v2.2.6 发布二进制的外层认证会保护 `/api/info`，不能仅按 OpenAPI 的 security=[] 当作公开端点。

任何携凭据 HTTP/WS 都先在新 TCP socket 上发送有界匿名身份请求（激活 deferred accept），再用 Linux `/proc/<pid>/fd` 的 socket inode 和 `/proc/net/tcp[6]` 已建立四元组确认登记 PID 拥有该 socket 的服务端；只有证明成功才在**同一条 socket**发送认证。公开 200 可伪造 PID/version，也不得绕过此证明。匿名响应必须有唯一有界 Content-Length、不能重定向/关闭连接/夹带额外字节；从连接到归属证明总计 5s 墙钟超时。校验失败不发送凭据，不自动重发业务写操作。

当前 OS 归属实现仅 Linux；macOS/Windows 或 `/proc` 权限/命名空间不能证明时返回 `IDENTITY_UNVERIFIED`，不能降级为信任 loopback 或注册文本。服务 locator 使用本地 registration path 的 hash（不是跨机器身份声明）；generation 由 registration tuple 派生，变更时旧请求 STALE_SERVICE，不重试。

URL/端口/Authorization/Cookie 不能由 Remote 请求提供；响应剔除 Set-Cookie 等敏感 headers。仅相对 OpenCode 路由和 Host 读取的 OpenAPI method/path 匹配；显式允许 `/api/info`、`/openapi.json`、`/api/event`。禁止绝对URL、authority、userinfo、fragment、遍历、反斜杠、未列出 method/path、重定向。服务 ID 非手机 URL。

请求 headers 仅 Accept/Content-Type 与必要协议 header 白名单；`x-opencode-ticket: 1` 仅允许用于已列入 OpenAPI 的 `POST /api/pty/{id}/connect-token`，其他值/方法/路径或 SSE/WS header 均拒绝。票据只作一次性附着 URL 参数，不记录到持久日志；Host 独立注入凭据。registration 缺失/损坏/不可读为 partial/issue，不等于权威空目录。不启动、不安装、不终止外部服务。

## 受限 DSH 改动读取

`harness.changes.v1` 是独立 capability，不由 Typert wire 版本推断。`harness.changes.read` 只接受
`{kind:"summary"|"diff", sessionId, seq, index?}`；diff 必须有0–4999的索引，summary 禁止索引，
seq 必须为正安全整数。URL、路径、方法、认证、peer、额外字段都不属于 wire 输入。

每个可信 Remote 连接持有一个 DshChangesPeer。它把固定 GET 请求交给官方
`Connection.createSharedFetchHandler('/api').fetch(request, sameOwnerPeer)`，保留 owner 认证栅栏；
没有 HTTP/端口扫描或网络 fallback。只有该本地服务和未结束 owner peer 同时可用才声明能力。
最多4个在途读取、10秒绝对期限、2MiB原始JSON响应，拒绝重定向/错误MIME/无效UTF-8和对象以外响应；
关闭只取消本连接读取，不关闭 Host 或 owner service。404保留为过期，不伪造空列表。

Android 对一次多文件读取固定当前加密连接并前后核对代次；解析官方摘要和 diff，共享 Direct/SSH/Remote
业务逻辑。当前历史窗口最近8个公告、最多60个合并文件，文件或摘要部分/超限明确报错；单文件补丁1MiB、
总补丁4MiB，不把截断当完整。初始follow快照后的原生 `workspace/changes` 公告单独有界保留，避免刷新沿用旧窗口。
CI 同时覆盖受限参数/权限/字节预算/取消、实际patched Router与Noise链路。另有显式 opt-in 测试实际调用
固定官方 Connection 认证和 deliverables 路由实现，但数据服务是受控夹具，不冒充真实模型轮次/文件修改。

## DSH 原生工作区登记管理

工作区管理使用已有 Typert `workspace/create`、`rename`、`delete`、`insertBefore` 和 `follow`，
不增加任意远程文件操作。create 仅登记已有目录，Host 返回规范路径与 opaque workspaceId；delete 仅移除
共享登记，保留文件和 Sessions。界面与本机项目保存/移除明确分开，只有显式本机保存才导入目录记录。

管理读取要求完整且严格的 baseline，不将缺失/坏帧当空目录；提交前重新核对目标及排序锚点的完整快照。
官方接口没有 CAS/revision，读后到写前仍有不可消除的 Host 并发窗口，不承诺原子锁。写入禁用认证401重放、
HTTP连接恢复，并使用一次性请求体阻止状态码 Retry-After 自动重发；未知结果先只读刷新，再由用户重新确认。
Controller 前后核对原 Host 路由和当前客户端，拒绝旧窗口写向编辑后的同 ID 地址，late ack 不改新 Host 状态。
写入失效后只有完整 baseline 才重建目录，孤立增量不能冒充完整清单。

## DSH 服务器正文搜索

`session/search` 只发送 `args.request.query`，去首尾空白后最多500个UTF-16单元，不接受 NUL，
按字面搜索 Host 当前可见的消息内容，不接受目录、分页游标或正则开关。搜索不会恢复 Agent 或打开消息流。
官方返回最多20个不同Session及240个Unicode码点的摘要；结果不完整、类型错误或超限是错误而非空列表。
`hasMore` 在远端访问过滤前计算，所以空列表加 true 仍表示有未展示结果，客户端不会伪造下一页。

独立界面标明搜索整个原 Host 可见范围，不受本机项目筛选约束；原首页筛选明确只处理本机已加载标题/项目。
查询、摘要仅在内存展示，不写到本机配置、草稿或日志。控制器绑定原客户端、路由、连接代次和最新查询；
明确点击后重新读取授权会话身份与目录，已移除/失权/旧查询/换路由结果均不能作为导航目标。
OpenCode 未按 DSH 方法假报支持，需按其自身官方可远程契约另核对。

## 原生历史边界分叉

`ForkBoundary.DshEvent` 发送官方 `session/fork` 的 `atSeq`，复制包含指定原始耐久事件的精确前缀；
省略边界时为最新已完成轮次前缀。`ForkBoundary.OpenCodeBefore` 发送 `before` 原生消息 ID，
复制严格位于这条消息之前的投影历史；省略则为全部历史。OpenCode 边界能力只由实例自己的兼容
OpenAPI（可选字符串 before，接受空对象）声明，不由通用 fork 或版本基线推断。

依据固定 [OpenCode 协议](https://github.com/anomalyco/opencode/blob/dc2ffcf8bf26f9e669b6aa22b95f50e12be15e06/packages/protocol/src/groups/session.ts)
及 [原生 handler](https://github.com/anomalyco/opencode/blob/dc2ffcf8bf26f9e669b6aa22b95f50e12be15e06/packages/server/src/handlers/session.ts)，
DSH 另以固定 rc.5 generated schema 固化请求与结果。消息行使用原生 ID/严格正安全整数事件 seq，
不使用行号或助手合并步骤的推测序号；queued、注入内容、未知序号和离线快照不能作为操作边界。

原生 HTTP 写入使用关闭连接恢复的一次性请求体，阻止连接/状态自动重发；DSH fork 也禁用401换 Cookie 重放。
Remote mutation 不因响应过大切换路径重发。坏/丢失 ack 不假造创建成功；运行期未知状态按原 Host/Session
锁定，重连或切换视图不清除。只读核对同时包含最近根会话与源子分支，列表可能不完整，不能证明是否曾创建；
用户人工确认后才解除锁定、重新确认新的动作。此运行期锁定不是跨进程持久事务或幂等性保证。

## 实现与验证边界

Host companion 和 SHA 校验的固定上游 Router/HostPluginRuntime overlay、Android HTTP/SSE/WS/分片 carrier、同 Host 共享 Noise 会话和服务选择 UI 已有实现。构建、测试入口见 [Host 连接器说明](<../host-connector/README.md>)。

本地跨语言 fixture 集成实际经过 Kotlin Noise/Relay → patched upstream RpcRouter → NativeServicePeer，验证会话列表/改名/SSE/2 MiB 上传与大响应分片/兄弟关闭隔离。另有 opt-in 测试实际连到校验后的官方 OpenCode v2.2.6 daemon：Android 原生直连、真实 JSch/sshd 原生附着，以及 Kotlin Noise → actual Router/Peer → 同一官方 daemon 的目录/能力/事件读取均通过；没有发送模型 prompt。Fake Hub/Host relay + 真实 Noise/Router 不等于公网 Hub/生产 Host，JVM 也不等于 Android 真机。

OpenCode simple PTY 协议另按固定提交 `dc2ffcf8bf26f9e669b6aa22b95f50e12be15e06` 的 [wire protocol](https://github.com/anomalyco/opencode/blob/dc2ffcf8bf26f9e669b6aa22b95f50e12be15e06/packages/core/src/pty/protocol.ts)、[连接 handler](https://github.com/anomalyco/opencode/blob/dc2ffcf8bf26f9e669b6aa22b95f50e12be15e06/packages/server/src/handlers/pty.ts) 和 [domain](https://github.com/anomalyco/opencode/blob/dc2ffcf8bf26f9e669b6aa22b95f50e12be15e06/packages/core/src/pty.ts) 验证：原始 UTF-8 text、binary 0x00 + JSON cursor 的回放界线、UTF-16 输出计数，不移用 persistent-pty 帧或虚构 ACK。原生文本终端明确创建/附着/输入/resize/DELETE，附着 close 只 detach；隔离官方 v2.2.6 的 Direct 与 Noise 路径已实际验证，真实 SSH 验收在专项测试中执行。UI 是有界 ANSI 纯文本投影而非完整 VT/TUI。

官方 DSH 0.2.1-rc.5 另以全新隔离 WebHost 验证 session/list、workspace/follow、$events 就绪/取消/关闭；不声称附着现有 Desktop。DSH Direct/SSH 的 LogicalSocket 另仅对 terminal/attach 开放有界 item.value 上行，保留原流/物理代次，失败不缓冲或重放。Session scope 与 OpenCode location scope 分开；先 retain 确认后 attach，snapshot/output 与 mixed write/resize 的 ACK/拒绝/窗口预算均按固定官方 generated contract，输入权由 Host 状态授予，附加立即接管须用户确认。隔离官方 WebHost 的 Direct 与真实 pinned SSH 用例均验证 ACK、resize、两附着控制转移和明确终止，无模型调用。Remote 终端另使用固定上游的 `harness.terminal.v1` 与 TerminalPolicy/TerminalCarrierCompat：设备身份由可信 peer 提供，Host 开关与设备持有记录继续约束目录、retain、附着、输入与终止。Android 将 native attach 映射为带唯一 attachmentId 的 terminal/follow，将顺序输入映射为 terminal/write/resize RPC，仅 provider ACK 引发的成功结果才生成本机 ACK；没有通用流上行或直接 gateway 绕过。输入权从 controllerId 精确匹配，固定当前加密 gateway、限制队列、未知交付和超时关闭不重发，不从 v3 推断能力。实际 Kotlin Noise→上游 Router/Policy/Compat→隔离官方 DSH 的终端创建/输入/resize/detach保留/终止均已执行通过。旧 Host 没有声明原生能力时不发送原生调用，显示升级要求，不走 ACP。

服务 ID 是 Host 本地注册路径的稳定 locator；不声称物理机器全局身份、跨路径服务合并或 DSH Desktop 附着。Host 插件必须由用户显式构建/安装/启用；本任务没有部署。
