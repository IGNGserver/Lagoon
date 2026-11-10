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

请求 headers 仅 Accept/Content-Type 与必要协议 header 白名单；Host 独立注入凭据。registration 缺失/损坏/不可读为 partial/issue，不等于权威空目录。不启动、不安装、不终止外部服务。

## 实现与验证边界

Host companion 和 SHA 校验的固定上游 Router/HostPluginRuntime overlay、Android HTTP/SSE/WS/分片 carrier、同 Host 共享 Noise 会话和服务选择 UI 已有实现。构建、测试入口见 [Host 连接器说明](<../host-connector/README.md>)。

本地跨语言 fixture 集成实际经过 Kotlin Noise/Relay → patched upstream RpcRouter → NativeServicePeer，验证会话列表/改名/SSE/2 MiB 上传与大响应分片/兄弟关闭隔离。另有 opt-in 测试实际连到校验后的官方 OpenCode v2.2.6 daemon：Android 原生直连、真实 JSch/sshd 原生附着，以及 Kotlin Noise → actual Router/Peer → 同一官方 daemon 的目录/能力/事件读取均通过；没有发送模型 prompt。Fake Hub/Host relay + 真实 Noise/Router 不等于公网 Hub/生产 Host，JVM 也不等于 Android 真机。

官方 DSH 0.2.1-rc.5 另以全新隔离 WebHost 验证 session/list、workspace/follow、$events 就绪/取消/关闭；不声称附着现有 Desktop。旧 Host 没有声明原生能力时不发送原生调用，显示升级要求，不走 ACP。

服务 ID 是 Host 本地注册路径的稳定 locator；不声称物理机器全局身份、跨路径服务合并或 DSH Desktop 附着。Host 插件必须由用户显式构建/安装/启用；本任务没有部署。
