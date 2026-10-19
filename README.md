# Lagoon

面向多种 coding agent harness 的原生 Android 移动控制端。Kotlin + Jetpack Compose 界面采用小米 HyperOS / MIUIX 设计规范，引入连续曲率 Squircle、科技蓝语义调色板。App 首发支持用户自己的 OpenCode 2.x Server，现已适配 DeepSeek Harness（`dsh web` 实例），直连端点，无需第三方中转服务；未来将持续扩展适配更多 harness。

## 功能

- 首发支持 OpenCode 2.x 服务端（`/api/*` 接口，以 `/api/info` 识别版本；1.x 已不再支持）；多服务器连接、Basic Auth/官方 pair 链接、Android Keystore 保护的密码与会话 Cookie、离线缓存、SSE 自动重连
- 支持 DeepSeek Harness：粘贴启动链接或填写签名密钥即可配对，Cookie 落盘复用、跨 dsh 重启不掉线，走 Typert RPC（一元调用 + mux 事件流），时间线、任务状态、审批与问答和其它 harness 共用同一套界面与通知；详见下文「DeepSeek Harness 支持」
- 项目与会话、异步任务（运行中可继续发送以引导当前轮次）、Agent/Model、斜杠命令（走服务端 `/command`）、文本/Reasoning/Tool 消息、停止与权限/表单处理；时间线按官方客户端规则投影，系统指令、合成输入、技能等给模型看的记录只显示一行通知
- 子会话、改动、文件浏览与搜索、重命名、删除；随版本变化的接口（重命名、恢复撤销、改动、已读同步）按实例 `/openapi.json` 选择
- 全服务器任务总览：实时显示运行中、未读完成、待回复与失败计数，并可从首页直接打开最需关注的任务；Android 16+ 使用系统实时更新（Live Updates），Android 15 及以下使用普通任务通知与应用内首页概览，详见 `docs/LIVE_UPDATES.md`
- 本地前台监控与运行中、待处理、完成通知；任务总览仅统计未读结果，用户打开会话后即视为已读

## 本地构建

需要 JDK 17+、Android SDK Platform 36。执行：

```sh
ANDROID_HOME=/path/to/Android/Sdk ./gradlew :app:assembleDebug :app:testDebugUnitTest
```

本仓库把 Gradle build 输出放在 `/tmp/lagoon-gradle/`，每个 checkout 使用独立路径 `/tmp/lagoon-gradle/<checkout-hash>/app/outputs/apk/debug/app-debug.apk`。

在 App 中添加可访问的 OpenCode Server URL。服务端需启用 Basic Auth；也可以直接粘贴 `opencode pair` 输出的官方链接，App 会访问一次链接、保存返回的会话 Cookie，再用该 Cookie 连接 API。建议通过 HTTPS 或可信 VPN 访问。使用局域网 HTTP 时，必须在该服务器资料中明确开启明文连接，授权会随资料保存。

添加 DeepSeek Harness 时，「服务类型」选择 DeepSeek Harness。默认只监听 loopback，手机访问需要 `dsh web --host <本机 IP> --no-open`（或经反向代理/TLS，并加 `--trusted-host`）。有两种配对方式：

- **启动令牌（默认）**：直接粘贴 `dsh web` 打印的整条启动链接，地址与令牌会自动拆开。令牌只用于换取该实例的浏览器 Cookie；Cookie 由 Android Keystore 加密保存在本机，App 重启、dsh 重启后都直接复用，dsh 未重启时还会自动续期。Cookie 默认 30 天绝对有效期，到期且 dsh 已重启过时才需要用新链接重新配对；想更久可在 `~/.dsh/profiles/web/cordis.patch.yml` 调大 `cookieMaxAgeDays`（见 `docs/dsh-protocol.md`）。
- **签名密钥（不看电脑配对）**：填写 `~/.dsh/.credentials.yaml` 中 `client-connection/browser-session` 记录的 `secret`。它是该 dsh 的长期凭据，手机用它在本机签发 Cookie，dsh 重启、Cookie 过期都不需要重新配对；删除该记录并重启 dsh 即可吊销全部 Cookie。只在 HTTPS 或可信局域网中使用。

### DeepSeek Harness 支持

DSH 的 Client↔Host 协议与 OpenCode 完全不同，Lagoon 用 `HarnessClient` 接口屏蔽差异，由 `DshApi` 做协议翻译（完整契约见 `docs/dsh-protocol.md`）：

- 连接：启动令牌先走浏览器式根路径交换（`GET /?token=…`，长寿命 Cookie，跨进程重启有效），只有根路径不交付 Cookie 时才退回 `GET /connect?token=…&interactions=answer` 的 API Peer（其 Cookie 只放内存）；换到的浏览器 Cookie 落盘复用、签发超过半天自动续期，被服务端拒绝时清掉并提示重新配对；签名密钥模式在本机签发 Cookie。一元调用 `POST /api/<namespace>/<method>`（payload 只含一个 `args` 对象，字段是方法形参名），流式数据走 `/api/remote.mux` WebSocket（`open`/`item`/`end` 帧，20 秒心跳），全局事件与审批/问答瀑布走 `$events`；事件流重连后按 Host 的重新投递对账待办，单条会话流断开会退避重连并补投断线期间的事件。
- 已支持：会话列表/创建/重命名/归档/分叉、发送（steer 打断当前轮次、queue 排队）、停止、模型目录与选择、Agent 预设（`agentPresets`）、审批与问答（waterfall 折算成同一套提示，回应走 `$events/result`；其他客户端结算后本机自动撤回）、子会话、时间线（用户消息、assistant 文本/Reasoning 的实时流式、工具调用与结果、压缩与命令记录）、文件浏览（`workspaceFiles`；没有会话的目录用 `directoryPicker` 浏览子目录）、文件搜索（`fileReferences`，与 `@` 提及同源）、图片与文本附件、改动列表与逐文件对比、后台任务状态、`/compact` 等斜杠命令。
- 差异：DSH 没有删除会话与消息级回退，界面上不提供「删除」，改为「归档」，并在会话菜单提供「分叉」；审批只有「允许一次 / 拒绝」；改动摘要只在 dsh 进程内、会话存活期间提供（重启后旧轮次没有对比）；引用的服务器文件以 `@路径` 文本随消息发送。

### 当前连接与授权

本地前台服务只监控当前服务器。切换服务器会明确停止旧服务器的本地监控，远端任务继续。离线缓存与局部失败数据均标记为过期，不能用于发送操作。

消息按官方客户端方式发送：客户端生成消息 id，仅在连接中断时用同一 id 重试一次。待处理权限按会话读取（`/api/session/{id}/permission`），表单按会话所在目录读取，不会因服务器工作目录不同而漏掉。“始终允许”只在服务端给出保存范围时提供，设置页可查看和撤销已保存权限。

## 更名与迁移

项目统一更名为 **“Lagoon”**（应用显示名为 Lagoon，定位从 OpenCode 专用客户端升级为面向多 Harness 的通用移动控制端）。
- 显示名与工程名：应用显示名为 `Lagoon`，`rootProject.name = "Lagoon"`，发布工作流名为 `发布 Lagoon Release`，安装包产物名为 `Lagoon-<version>.apk`，Gradle 临时输出目录调整为 `/tmp/lagoon-gradle/`。
- 应用标识与升级：`applicationId` 保持为 `com.igng.opencode.lagoon` 并使用固定生产签名，与既有安装版本签名完全一致，支持直接平滑覆盖安装升级。
- 深链接：支持 `lagoon://` 路由（同时兼容接听 `opencode-lagoon://` 历史深链接）。
- 安全存储：`KeystoreCipher` 别名升级为 `lagoon-*`，并自动兼容读取旧有的 `opencode-lagoon-*` 凭据。
- CI / CD：发布工作流优先读取 `LAGOON_*` Secrets，并向下兼容读取 `OPENCODE_LAGOON_*` Secrets。
- `docs/releases/` 下的历史发布说明保持原样，它们记录的是当时实际发布的名称。

## 验收边界

Android 构建与单元测试只能证明代码可编译和有限的 API/状态逻辑。真实服务端版本、SSE 断线恢复、Android 16 Live Updates 提升都需要在目标服务器和设备上验收。Android 15+ 的 dataSync 前台服务有运行时长限制。
