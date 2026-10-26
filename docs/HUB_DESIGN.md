# Lagoon 中枢（Hub）与网页端设计

v3.0.0 新增「Lagoon 中枢」：用户在任意一台机器上用 docker compose 部署一个容器，打开网页（或把它安装成 PWA）即可像 Android 客户端一样连接、监控、控制各台开发机上的 harness（OpenCode 2.x / DeepSeek Harness）。Android 客户端仍然直连开发机，不依赖中枢。

## 1. 为什么中枢必须是后端

浏览器不能直接连开发机上的 harness：

- harness 不为第三方页面开放 CORS；dsh 还要求 `Host` 白名单，Cookie 按 authority 绑定；
- PWA、Service Worker 与推送只在安全上下文（HTTPS）里可用，而局域网 harness 多是 HTTP，HTTPS 页面请求 HTTP 地址会被当作混合内容拦截；
- 凭据（Basic Auth 密码、dsh Cookie/签名密钥）不应该落在每个浏览器里。

因此中枢替浏览器连接 harness：浏览器只和中枢通信，中枢持有凭据、常驻监控、归一化两种协议并推送通知。

## 2. 总体结构

```
浏览器 / PWA（web/，React + TypeScript）
   │ REST（命令） + 每个标签页一条 SSE（状态与会话增量）
   ▼
Lagoon Hub（hub/，Kotlin/JVM，单容器）
   ├─ 静态托管 web 构建产物、manifest、Service Worker
   ├─ 鉴权：单一密码 → HttpOnly 会话 Cookie；失败退避
   ├─ 每台服务器一个常驻监控：AllServersOverview（与 Android 同一份代码）
   ├─ 会话视图：被浏览器打开的会话在中枢侧做 TranscriptProjection，按消息/片段增量下发
   ├─ Web Push：VAPID + RFC 8291 加密，任务完成/失败/待审批时推送
   └─ /data：服务器资料、加密凭据、任务账本、推送订阅、VAPID 与会话密钥
   │ OkHttp / WebSocket（shared/ 中的 OpenCodeApi、DshApi）
   ▼
各开发机：opencode serve / dsh web
```

### 代码复用：`shared/` 源码集

- `shared/src/main/kotlin` 存放与平台无关的 Kotlin 源码（协议适配、数据模型、任务归约、会话投影、全服务器监控）。它**不是独立的 Gradle 模块**，而是同时被 `:app`（Android）与 `:hub`（JVM）作为额外源码目录编译。这样 `internal` 可见性保持不变，Android 端零行为变化。
- 平台差异通过小接口隔离：
  - `Diagnostics` 输出到可替换的 `DiagnosticsSink`（Android 接 `android.util.Log`，中枢接标准输出）；
  - `AllServersOverview` 依赖 `OverviewStore` / `CatalogCache` 接口（Android 由 `ServerStore` / `OfflineCache` 实现，中枢由 `HubStore` 实现）；
  - Base64 统一使用 `java.util.Base64`（Android minSdk 26 起可用）。
- 留在 `:app` 的：Compose UI、通知与实时更新、Keystore、SharedPreferences、SQLite 会话缓存、APK 自更新、`LagoonController`。

### 中枢运行时

- **服务器监控**：每个开启「自动连接」的服务器一个 `AllServersOverview` 监控（`selectedServer` 恒为空，即全部按总览口径归约）。它维护会话目录、任务状态、待审批/待回答、未读结果账本、后台 shell，并在事件流断开时退避重连、按刷新频率对账。中枢没有“当前服务器”的概念，因此不存在 Android 那种前台/后台切换。
- **会话视图（ConversationView）**：浏览器打开一个会话时订阅它。中枢读取最新一页消息（不足一个完整轮次时向前补页）与 inbox，之后对该服务器的事件逐条调用 `TranscriptProjection.apply`；投影要求对账时去抖重读尾页并按 id 合并（`mergeTranscript`）。最后一个订阅者离开 60 秒后释放。
- **增量下发**：每次投影后对比新旧消息列表：新增/结构变化的消息发 `put`，仅单个片段变化的发 `part`，文本只是追加时发 `append`（只带新增后缀），删除发 `del`。同一会话的推送合并为每 100 ms 至多一批。
- **任务账本**：`HubStore` 复刻 Android `ServerStore` 的任务状态 / 父链 / 未读结果语义（30 天、500 条），全部常驻内存，变更后去抖 1 秒原子写盘（临时文件 + rename）。单用户，所有设备共享同一份未读账本：任意设备打开会话即视为已读。

## 3. 鉴权

- 只有一个访问密码，没有用户体系，**允许弱密码**（不做长度/复杂度要求，非空即可）。
- 密码来源：环境变量 `LAGOON_PASSWORD`（设置后每次启动以它为准，网页内不可修改）；未设置时首次访问进入「初始化」，需要输入容器日志里打印的一次性初始化码（防止“谁先打开谁设密码”），之后可在设置里修改。
- 存储：PBKDF2-HMAC-SHA256（21 万次迭代，16 字节盐）。
- 会话：登录成功下发 `lagoon_session` Cookie（随机 32 字节，服务端只存 SHA-256），`HttpOnly`、`SameSite=Strict`、`Path=/`；经 HTTPS 访问时加 `Secure`。有效期 90 天，每次使用滑动续期。设置页可「退出所有设备」。
- 防爆破（因为允许弱密码，这一层不可关闭）：同一来源 IP 连续失败 5 次后每次失败的锁定时间翻倍（1 秒起，上限 15 分钟）；全局 10 分钟内失败超过 30 次时所有失败请求至少间隔 2 秒。
- 写请求校验 `Origin`/`Referer` 与当前站点一致（另有 SameSite=Strict 兜底）。
- 安全提示写入部署文档：中枢掌握所有开发机的 harness 凭据，而 harness 能执行 shell。密码被猜中等同开发机被远程执行。建议只在局域网、VPN（Tailscale 等）或带额外访问控制的反向代理后暴露。
- harness 凭据在 `/data` 中用 AES-256-GCM 加密，密钥是同卷的 `secret.key`。这能防“只泄露状态文件”，防不了整卷被拿走。

## 4. 传输与部署

- 中枢默认监听 HTTP `:8080`（用户明确允许 HTTP）。
- PWA 安装、Service Worker、Web Push 是浏览器强制要求安全上下文的能力：经 `http://局域网IP` 访问时网页功能完整，但不能安装、不能收推送（`localhost` 除外）。提供三种 HTTPS 方式：
  1. **反向代理**（推荐）：Caddy / Nginx / Cloudflare Tunnel / Tailscale Serve 终止 TLS，中枢设置 `LAGOON_TRUST_PROXY=true` 以识别 `X-Forwarded-Proto`；
  2. **自带证书**：`LAGOON_TLS_CERT` / `LAGOON_TLS_KEY`（PEM）后中枢同时监听 HTTPS `:8443`；
  3. **自签名**：`LAGOON_TLS=self-signed` 且 `LAGOON_TLS_HOSTS=192.168.1.10,hub.lan` 时，中枢首次启动用 JDK `keytool` 生成带这些 SAN 的自签名 CA 证书并监听 `:8443`；设置页提供证书下载，用户在手机/电脑上安装并信任后即可安装 PWA、接收推送。
- 推送要求中枢能访问浏览器厂商的推送服务（FCM、`web.push.apple.com`、Mozilla autopush）。
- 容器内的 `localhost` 不是宿主机：连接同一台机器上的 harness 请用 `host.docker.internal`（compose 已加 `extra_hosts: host-gateway`）或宿主机局域网 IP。
- 镜像：`ghcr.io/igngserver/lagoon-hub:<版本>`，amd64 + arm64，基于 Eclipse Temurin 21 JRE。数据卷 `/data`。

## 5. 网页端

- 技术栈：React 19 + TypeScript + Vite；Radix UI 无障碍原语 + Tailwind CSS（shadcn/ui 的组织方式），在其上套用 Lagoon 的 MIUIX / HyperOS 设计 token（科技蓝语义色、大标题、分组卡片、胶囊按钮、连续曲率圆角）；Markdown 用 markdown-it（禁用原始 HTML），代码高亮用 highlight.js 子集；状态用 Zustand。
- 信息架构与 Android 一致：服务器 → 项目/目录 → 会话 → 对话。首页是唯一根页面（服务器胶囊、范围、按日期/项目/状态分组的会话列表、悬浮新建按钮），已归档、设置、服务器管理整页推入；对话页的子任务、改动、文件在右上角菜单。
- 布局：
  - 宽屏（≥ 1024px）：左栏会话列表常驻，右侧对话；子任务/改动/文件以右侧抽屉打开；
  - 窄屏：单页栈，页面左右滑入，支持浏览器/系统返回手势；底部弹层替代菜单。
- 时间线：`TranscriptRows`（Kotlin）移植为 TypeScript。`hub/src/test/resources/contract/transcript-rows.json` 是双端共用的黄金样例：中枢测试断言 Kotlin 版输出与之相同，网页测试断言 TypeScript 版输出与之相同。
- PWA：`display: standalone`、maskable 图标、`apple-touch-icon`、`viewport-fit=cover` 与 safe-area；安卓 Chrome 用 `interactive-widget=resizes-content`，iOS 用 `visualViewport` 处理键盘；Service Worker 缓存应用外壳（index.html 走网络优先），新版本就绪时提示刷新；`navigator.setAppBadge` 显示待处理数量。
- iOS 切到后台会断开连接：页面回到前台（`visibilitychange`）立即重连 SSE，中枢重发完整状态与已订阅会话的快照。

## 6. 推送

- VAPID 密钥首次启动生成并保存在 `/data`；浏览器订阅后把 `PushSubscription` 交给中枢保存（可多设备）。
- 触发点与 Android 通知口径一致：根会话出现未读的完成/失败结果；会话进入等待审批/等待回答（去抖 1.5 秒，期间被处理则不推）。服务器资料关闭「通知」时不推。
- 负载只含标题、正文、跳转路径与（审批时）请求标识，不含消息正文以外的敏感数据；RFC 8291 `aes128gcm` 加密。
- 安卓 Chrome 的审批通知带「允许一次 / 拒绝」按钮，由 Service Worker 携带会话 Cookie 调用中枢；iOS 不支持通知按钮，点击进入会话处理。
- 推送服务返回 404/410 时删除该订阅。

## 7. API 契约

所有接口前缀 `/api/v1`，JSON，UTF-8。错误统一为 `{"error":{"code":"…","message":"中文说明"}}`，HTTP 状态：400 参数错误、401 未登录、403 Origin 不符、404 不存在、409 冲突、429 退避中（带 `Retry-After`）、502 harness 报错（`message` 是 `userFacingError` 文案）。

### 鉴权

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/auth/status` | `{authenticated, setupRequired, passwordFromEnv, version, secure}` |
| POST | `/auth/setup` | `{code, password}` 初始化密码并登录 |
| POST | `/auth/login` | `{password}` |
| POST | `/auth/logout` | 注销本设备 |
| POST | `/auth/password` | `{current, next}` 修改密码（环境变量托管时 409） |
| POST | `/auth/revoke` | 退出所有设备（含自己） |

### 服务器

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/servers` | 资料列表（不含任何凭据，带 `hasSecret`） |
| POST | `/servers` | 新增：`{profile, secret?, link?}`；先测试连接，成功才保存，返回 `{profile, version}` |
| PUT | `/servers/{id}` | 修改，语义同上；`secret` 缺省表示沿用 |
| DELETE | `/servers/{id}` | 删除资料、凭据与账本 |
| POST | `/servers/parse` | `{text}` → 识别 dsh 启动链接 / opencode pair 链接 / 普通地址 |

`profile` 字段与 Android `ServerProfile` 一致：`id name url username autoConnect notifications allowCleartext kind dshAuth`。

### 状态与事件流

- `GET /state`：完整状态 `{servers:[ServerSnapshot], policy, prefs}`。
- `GET /events`（`text/event-stream`）：
  - `hello` `{streamId}`：本条流的标识，用于订阅会话；
  - `server` `ServerSnapshot`：某台服务器的控制面快照变化（每台服务器至多每 250 ms 一次）；
  - `server.removed` `{serverId}`；
  - `conv.reset` `{serverId, sessionId, messages, cursor, complete}`：订阅后或中枢重读后的完整窗口；
  - `conv.ops` `{serverId, sessionId, ops:[…]}`：`{op:"put", message}` / `{op:"part", messageId, part}` / `{op:"append", messageId, partId, field, text}` / `{op:"del", id}`；
  - `toast` `{level, message}`：后台操作失败等提示；
  - 每 25 秒一行注释心跳。
- `PUT /streams/{streamId}/watch`：`{conversations:[{serverId, sessionId}]}` 替换本流订阅的会话集合。

`ServerSnapshot` 字段沿用 `ServerOverviewSnapshot`：`serverId profile version connected cached loading lastReadAt error degraded streamConnected canSyncRead projects sessions parents tasks notices permissions questions backgroundRunning summary autoApprovalFailures`。

### 会话与操作（`/servers/{sid}` 下）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/sessions?cursor=` | 根会话分页（首页“加载更多”） |
| POST | `/sessions` | `{directory, title, prompt?, agent?, model?}` 新建（带 prompt 时立即发送） |
| GET | `/sessions/{id}` | 单个会话 |
| GET | `/sessions/{id}/messages?cursor=` | 更早的一页 `{messages, cursor}` |
| POST | `/sessions/{id}/send` | `{text, agent?, model?, references?, attachments?, delivery?, id?}`；以 `/` 开头且命中命令列表时走命令 |
| POST | `/sessions/{id}/abort` · `/compact` · `/fork` · `/unrevert` | |
| POST | `/sessions/{id}/rename` | `{title}` |
| POST | `/sessions/{id}/archive` | `{archived}` |
| POST | `/sessions/{id}/revert` | `{messageId}` |
| POST | `/sessions/{id}/view` | 标记已读（本地账本 + 服务端 `time.viewed`） |
| DELETE | `/sessions/{id}` | 删除 |
| GET | `/sessions/{id}/children` · `/diff` | 子会话、改动 |
| POST | `/permissions/{rid}` | `{reply: "once" | "always" | "reject"}` |
| POST | `/questions/{rid}` | `{answers: string[][]}`；`/questions/{rid}/reject` 拒绝 |
| GET | `/choices?directory=` | `{agents, commands, models}` |
| GET | `/files?directory=&path=&session=` · `/file?…` · `/search?directory=&query=&session=` | 文件浏览 |
| GET | `/browse?path=` · `/browse/search?path=&query=` | 添加项目用的目录选择器与目录搜索 |
| POST | `/projects` | `{directory}`；`DELETE /projects/{pid}`。DSH 只列出已有工作区，未列出的目录直接按目录登记 |
| POST | `/refresh` | 立即连接（未开启自动连接时）并对账一次 |
| POST | `/read` | 本服务器未读结果全部标记已读，并在支持时同步服务端 `time.viewed` |

### 偏好与推送

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET/PUT | `/prefs` | 权限策略（隐藏/自动允许）、刷新频率、置顶会话、模型可见性与最近模型等 |
| GET | `/push/key` | VAPID 公钥（base64url） |
| POST | `/push/subscriptions` | `{subscription, label}` |
| DELETE | `/push/subscriptions` | `{endpoint}` |
| POST | `/push/test` | 向本设备发一条测试推送 |
| GET | `/tls/cert` | 自签名模式下下载证书 |

## 8. 仓库布局

```
shared/src/main/kotlin/…   与平台无关的 Kotlin 源码，被 :app 与 :hub 共同编译
app/                       Android 客户端
hub/                       中枢（Kotlin/JVM，JDK 内置 HttpServer + 虚拟线程，无 Web 框架依赖）
web/                       网页端（pnpm）
deploy/                    Dockerfile、compose.yaml、示例反向代理配置
```

中枢不引入 Web 框架：JDK 21 的 `com.sun.net.httpserver` 加虚拟线程足以支撑单用户负载，SSE 是长连接分块响应，依赖面与镜像都更小。Web Push 加密、VAPID 签名、密码哈希都只用 JDK 自带密码学实现。

## 9. 验证

- Android：`./gradlew :app:testDebugUnitTest :app:assembleDebug`（行为不变的回归保护）。
- 中枢：`./gradlew :hub:test`——MockWebServer 假 OpenCode/DSH、鉴权与退避、存储原子写、推送加密（RFC 8291 附录测试向量）、会话增量、`TranscriptRows` 黄金样例。
- 网页：`pnpm lint && pnpm typecheck && pnpm test && pnpm build`（Vitest，含 `TranscriptRows` 黄金样例 `contract/transcript-rows.json`）。
- 无 Android SDK 时用 `LAGOON_HUB_ONLY=1` 只配置中枢工程（容器构建与 CI 的中枢任务都这样做）。
- 镜像：CI 构建 amd64/arm64 并在容器内跑健康检查。
- 必须在真实环境验收：iOS 26 Safari 主屏 PWA（安装、推送、键盘、safe-area）、安卓 Chrome PWA（安装、推送与通知按钮）、桌面 Chrome/Safari/Firefox；两种 harness 各跑通发送、停止、审批、问答、文件、改动。
