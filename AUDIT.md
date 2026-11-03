# OpenCode Mobile 全库审计

审计日期：2026-09-28（Asia/Shanghai）。本轮仅审计；未修改业务代码，未提交、推送、创建 PR、发版或部署。未使用 Memory 或其他聊天记录。

## 1. 审计对象与结论边界

本地工作目录的 `main` 是 `b8595bcadefc3412f2fe0a33e823493218d051b7`。执行 `git fetch origin --prune` 后发现远端已前进五个提交，最新 `origin/main` 为 **`3e957e6aeb4e4bac7df6e1df399645f0f750cdb8`**。为避免把已修复的问题再次当成现存缺陷，本报告以这个远端提交为主审基线，并检查本地版本至该提交的历史。

所有源码行号均指 **`3e957e6`**，不是本地旧 `main`。可用 `git show 3e957e6:<文件路径>` 对照。没有切换、合并或更新本地主分支，也没有进入其他人的工作区。现有四个附加 worktree 均保留；本次采用 `git archive` 导出到 `/tmp/opencode-audit-20260928-fIJo8c` 的独立审计快照，业务源码保持不变。当前仓库唯一交付文件是本报告。

覆盖内容：该提交全部 47 个受控文件的清单；18 个 Kotlin 生产文件（3,831 行）、Android 入口/资源/存储/网络/界面/通知、OpenCode 插件、companion 服务及测试、构建和发布工作流、依赖声明及 lockfile、架构和版本文档。第三方二进制和 178 个 npm 依赖包没有逐行审计；不把版本落后推断成已知 CVE。未连接真实 OpenCode、Firebase、生产 companion 或用户设备。

**核心结论：主要风险集中在“操作属于哪个服务器/会话、事件是否真正处理成功、用户看到的状态是否可信”三个边界。** 现有加密、认证和 happy-path 测试有效，但无法覆盖跨服务器竞态、通知进程生命周期、事件压力和失败重试。部分近期重构重新破坏了此前已修复的行为；还有数据在解析、缓存和测试中循环，却没有产品消费端。

共列出 **18 个根因级发现：P1 六项、P2 十一项、P3 一项**。没有证据支持 P0 级“无条件远程接管”等结论。这里的 P1 表示应在下一次对外交付前修复，并不表示每项都属于可远程利用的安全漏洞。

证据分类：

- **动态确认**：使用实际生产类/函数、MockWebServer 或本机 loopback HTTP 复现；不是生产环境渗透。
- **静态确认**：读通入口、数据转换与最终调用，存在确定的缺口；没有冒充真机验收。
- **条件性风险**：明确写出所需部署条件或攻击前提。

## 2. 产品与真实调用关系

| 用户意图 | 实际链路 | 权威数据/信任边界 |
|---|---|---|
| 添加服务器、配对 | `ServerForm → PairLinkResolver/testServer → ServerStore → connect` | 用户输入的地址成为凭据发送目标；密码/Cookie 用 Keystore 加密 |
| 查看/操作会话 | `MainActivity → MobileController → OpenCodeApi → V1/V2 REST` | 服务端是权威；Controller 是进程单例，只有一个当前 API 和一份全局可变状态 |
| 实时状态 | `OpenCodeApi.events → callbackFlow → handleEvent → TaskReducer → UI/通知` | SSE 是状态输入；当前无丢事件检测，也没有常驻的周期对账 |
| 离线浏览 | `loadAll/loadSession → OfflineCache → JSON/AES-GCM/SharedPreferences` | 缓存应仅作旧快照；目前成功/失败/空数据和缓存新鲜度混用 |
| 后台任务监控 | `send → TaskMonitorService → 同一 MobileController.state → TaskNotifications` | 服务声称跟踪多个任务，但它没有独立的多服务器连接 |
| 通知按钮 | `PendingIntent → NotificationActionReceiver → 单例 Controller → REST` | 应绑定通知创建时的服务器、目录、会话和请求；当前丢失部分上下文 |
| 关 App 推送 | `OpenCode plugin → /v1/events → mapEvent → FCM → MobileMessagingService → 通知` | 独立的第二套任务状态机；认证成功、状态接受、设备收到是三件不同的事 |
| 发布安装包 | tag/dispatch → `release.yml → assembleDebug → gh release create` | 正式/预发布共用 Debug 产物；Firebase 配置和稳定签名不在交付链里 |

已核实的有效防护：主 API 初始地址有 HTTPS/显式 HTTP 开关；不接受 URL 用户信息与 query；Basic/Cookie 凭据和消息缓存使用 AES-GCM；companion 启动时检查本地 OpenCode 未认证请求被拒绝，注册与事件分别认证并校验 serverKey；通知 Receiver 不导出、PendingIntent 不可变。上述措施不应因其他问题被整体否定。

## 3. 修复优先级概览

| ID | 优先级 | 发现 | 证据 |
|---|---|---|---|
| A01 | P1 | 原始 Cookie 跨主机重定向泄漏；配对的重定向检查发生在发送之后 | 动态 + 静态 |
| A02 | P1 | 异步写操作与文件请求不绑定服务器/会话世代，切换后串写 | 动态 + 静态 |
| A03 | P1 | 通知命令回归：目录丢失、没有后台保活、过早消除通知 | 动态 + 历史 + 静态 |
| A04 | P1 | SSE 队列满时静默丢事件，没有可靠补偿 | 动态 |
| A05 | P1 | 状态/权限读取失败被转换成空值，运行任务可被误判完成 | 动态归约 + 静态调用链 |
| A06 | P1 | 推送先记状态后投递，失败仍成功、重试被吞、并发乱序 | 动态 |
| A07 | P2 | 推送密钥边界、签名覆盖和更新语义不完整 | 动态 + 静态 |
| A08 | P2 | 设备注册缺少可靠持久化、撤销和持续有效期检查 | 动态 + 静态 |
| A09 | P2 | 多服务器监控、通知关闭开关和前台通知生命周期不一致 | 静态 |
| A10 | P2 | 首次离线后的刷新不能恢复 SSE；缓存标志可能永久阻止发送 | 静态 |
| A11 | P2 | 文件引用按钮无效；切标签丢草稿，发送回调可能清掉新增文本 | 静态 + 历史 |
| A12 | P2 | 新建/Fork 成功与导航脱节，依靠旧状态或固定延时 | 静态 |
| A13 | P2 | 两套任务状态机与插件投影不等价，契约测试掩盖差异 | 动态 |
| A14 | P2 | 缓存删除与后台写竞争，已删数据可能复活；没有总容量策略 | 静态时序 |
| A15 | P2 | 请求不可及时取消、读取无界、主线程仍承担大量解析序列化 | 动态 + 静态 |
| A16 | P2 | 权限通知不能支撑知情授权；“当前会话记住”的范围没有协议依据 | 静态；服务端范围待验 |
| A17 | P2 | 发布产物缺少稳定签名与 Firebase 配置，正式版也走 Debug | 构建产物 + 静态 |
| A18 | P3 | 附件/输出路径等生产消费链断裂，遗留参数和死分支未清干净 | 全库引用 + 历史 |

## 4. 发现详情

### A01 — P1：重定向绕过最初的凭据目标边界

**证据**：`core/OpenCodeApi.kt:54-70,82-88,106-118` 用 OkHttp 默认重定向策略，并手工添加 `Cookie`；`push/PushRegistration.kt:31-37,50-71` 同样如此。`core/PairLink.kt:17-25,32-55` 明确允许 HTTP/HTTPS 互相重定向，只在 `execute()` 返回后检查最终 scheme。

**动态确认**：`rawCookieFollowsCrossHostRedirect` 使用实际 `OpenCodeApi`，让 `localhost` 返回 302 到 `127.0.0.1` 的另一 HTTP 服务。目标收到原始 `opencode-session=fixture-cookie`；作为对照，`Authorization` 已被 OkHttp 去掉。不能将此问题笼统表述成 Basic Auth 同样跨主机转发。

`pairRedirectRejectsOnlyAfterSendingToDisallowedHttpHost` 从 resolver 允许的 loopback 地址重定向到它不允许的 HTTP 主机 `127.0.0.2`。虽然最后抛出“非 HTTPS 地址”，目标已经收到 `/auth/connect/fixture-bearer`。此实验使用本机地址，验证的是**检查时机**；HTTPS 初始地址的降级风险来自相同重定向配置，未搭建真实 HTTPS 服务器验证。

**影响与前提**：已信任服务器或其代理返回跨源/降级 Location 时，手工 Cookie 或 URL 中的 bearer token 可能先泄漏，随后才报错。没有证明不控制服务器/代理的人能凭空制造这一重定向。

同一边界的另一个缺口是编辑地址：`ui/Screens.kt:374-385` 在使用新 URL 测试前，直接继承原 profile 的 password/Cookie；没有区分凭据原始 origin。用户改错域名可能把旧凭据发给新主机。

**修复**：统一传输策略，逐跳在发送前校验 scheme/origin；禁止凭据请求跨源/降级自动跳转；Cookie 保留域、路径、Secure 等约束，不能降成无作用域字符串后随请求发送。改变凭据目标时显式重新认证或确认迁移。

**验收**：REST、SSE、注册、配对分别覆盖跨主机、跨端口、HTTPS→HTTP、中间跳转再回 HTTPS；断言被拒绝的目标收到 **0 次请求**，而非仅检查最终抛错。

### A02 — P1：请求结果与命令没有绑定操作起点

**证据**：`core/MobileController.kt:103-122` 通过 `generation` 防止部分连接结果串写，但 `act/withSession`（420-423）没有任务上下文；`createSession`（347-351）、`send`（353-368）、删除/撤销（370-378）及文件操作（401-414）在挂起后继续修改当前 `mutable`，甚至重新读取当前 `api/server`。

**动态确认**：`oldServerFileResponseWritesIntoNewServerState` 运行真实 `readFile`，以延迟 HTTP 响应模拟 A 请求未完成时状态切换到 B。最终 `serverId == b`，`fileText` 却是 A 的文件内容。探针只替换 Controller 的运行 scope/初始依赖以绕开 Android 构造，不替换被测方法和 API 实现。

**其他确定路径**：A 的创建响应可插入 B 的 sessions；A 的发送成功后以 `state.value.server.id` 启动 B 的监控，并在 `loadSession` 中使用新的 API；旧删除结果可清空新选中的 session。文件搜索每次输入并发发起，旧 query 的慢响应也能覆盖新结果。风险包括界面数据跨服务器混杂、后续路由错误；没有声称必然能在不相关服务器执行同名会话命令。

**修复**：为操作捕获不可变 `{serverId, profileRevision, generation, projectId, directory, sessionId, client}`；响应只提交到相同上下文。切换时取消所属 Job，保留服务端已接受的操作结果用于对账，不能将“取消等待”当成“服务端撤销”。搜索使用 latest-wins 的请求序号/取消。

**验收**：发送、创建、删除、文件读取/搜索、权限回复分别在 A→B、项目切换和会话切换中插入延迟；断言既不串状态，也不向新服务器补发旧会话请求。

### A03 — P1：通知动作重构回归了目标、生命周期与反馈

**证据**：`system/NotificationActionReceiver.kt:16-34` 直接投递 Controller 协程，`onReceive` 随即返回并无条件取消通知。`core/MobileController.kt:388-392` 的 `abortSession` 构造 `Session(sessionId, "", "", 0)`，丢弃 Receiver 已读到的 directory；`OpenCodeApi.kt:252-255,73-79` 对 V1 因此省略 directory 参数。

**动态确认**：`abortWithEmptyDirectoryOmitsRoutingContext` 捕获请求为 `/session/s/abort`，没有选中项目的 directory。真实多项目服务上的最终错误取决于服务端默认目录，未在真实服务器验证。

**历史证据**：`e5b7e5f` 的 Receiver 曾保存完整目录，使用 `goAsync()/finish()` 和 8 秒 API 超时；`b80084f` 改成单例 Controller 后删除这些逻辑。注释“controller scope 在 Receiver 生命周期内，因此不需要 goAsync”（Receiver 9-15）不能提供进程存活保证。

还有两个冷启动/陈旧通知问题：

- 若保存的当前 serverId 相同、`autoConnect=false`，Controller 初始化（71）不会创建 API；Receiver（25）也不会 connect，命令因空 API 失败，通知仍消失。
- 如果通知指向已删除 profile，`connect`（104）直接返回；Receiver 不验证连接成功，后续命令可能用当前另一个服务器的 API。

**修复**：通知命令使用独立、受生命周期管理且有总时限的执行器，捕获完整目标而非切换 UI 单例。验证 profile/request 仍存在；仅在服务端成功确认后撤掉待处理通知，失败保留可重试入口。完成后向 UI 发布针对原目标的结果。

**验收**：冷启动、关闭自动连接、App 退后台、旧 profile 已删除、服务器超时、多项目 V1；不仅测 REST 返回，还要做进程回收下的设备验收。

### A04 — P1：SSE 背压失败被静默忽略

**证据**：`core/OpenCodeApi.kt:438-457` 的 `callbackFlow` 回调只调用 `trySend(...)`，不检查失败结果。`MobileController.kt:204-257` 对主线程上的事件进行状态更新与通知处理；没有周期对账，只依赖收到某类事件或断线后刷新。

**动态确认**：`sseOverflowSilentlyLosesPermissionEvent` 一次发送 200 个状态事件和最后一个权限请求，消费者每条延迟 10ms。两次实验仅收到 **65/201、66/201**，末尾权限事件均丢失，Flow 正常结束。接收条数随调度变化，不把 65 当固定阈值；关键是不报错且缺少末尾权限事件。若真实连接保持打开，无法指望立即触发断线补偿。

**影响**：等待授权/完成事件可消失；后续更新还可前移已处理游标，使重连也难以依赖重放补齐。这不是单纯“流式文本少一个字”。

**修复**：显式处理溢出；控制类事件保证交付或标记 desync 并触发全量对账。仅可合并能证明可覆盖的文本增量，不能无差别丢权限/终态；游标表示已可靠处理的位置。

**验收**：压力流、慢 UI、队列溢出、事件 ID 重放、正常关闭与异常断开；权限、问题、删除和终态均必须最终收敛。

### A05 — P1：局部读取失败被伪装成权威空状态

**证据**：`core/MobileController.kt:146-169` 把 status 失败转换为空 map，权限/问题失败转换为空列表，再用 `statuses[id] ?: "idle"` 归约并宣布 `connected=true,error=null`。`Models.kt:214-227` 会把此前 active 的任务变成 COMPLETED。

**动态确认**：`missingStatusBecomesFalseCompletion` 对实际 reducer 证明上述缺省输入将 THINKING 变成 COMPLETED；HTTP 错误→empty map 的上游路径已静态核对。不是声称在生产执行了失败注入。

**影响**：某个端点超时/500 就可能显示“完成”、清空待授权列表、关闭任务监控并重新允许发送；部分项目的 sessions 失败也会被 `loadAll:142-144` 隐藏。查询失败与成功返回空集合应是不同语义。

**修复**：按资源记录 success/error/stale；失败保留最后一次可信状态并标明未知，不能合成 idle。只有明确的权威终态才能触发完成通知和停监控。

**验收**：保持其他接口成功，单独对 status/permission/question/session 列表注入 401、500、超时和解析失败；任务不得从“正在运行/等待”变成“已完成”。

### A06 — P1：companion 的接受、去重和投递顺序设计不可靠

**证据**：`companion/src/server.mjs:115-135` 在 `fetchSession/send` 之前写 `states`；`Promise.allSettled` 的失败被完全忽略，最终统一 200。`companion/opencode-mobile.plugin.js:37-44` 不检查响应状态，也没有重试或持久队列。

**动态确认**：

1. `send` 抛错时事件仍返回 200；重发相同事件再次 200，但 `send` 总计只调用一次，失败状态已被去重。
2. 阻塞第一条事件的标题查询，再提交第二条事件，实际发送顺序成为 **COMPLETED → WAITING_PERMISSION**。
3. states 的 6 小时 TTL 不只是缓存：长时间无变更的 active 状态被另一个会话的事件清理后，后续 idle 因没有 previous 而不产生完成推送（40-61、15-17）。

**影响**：“可靠后台推送”不具备可靠的至少一次投递或顺序收敛。签名正确的旧消息也能覆盖新状态。插件等待 fetch 最长 2.5 秒的开销真实存在，但没有上游插件调度器代码，不能断言每次一定阻塞模型执行。

**修复**：分离已接收、待投递、各设备已确认/已尝试状态；持久 outbox、可重试错误分类、设备无效 token 清理、会话序号。同会话串行归约/投递，客户端拒绝陈旧序号；终态识别不应依赖会丢失的纯内存历史。标题查询可缓存或异步补充。

**验收**：FCM 单设备/全部失败、重启、6 小时任务、并发、标题查询超时、重复事件；区分“服务器已接收”与“设备已送达”，避免作出工具无法证明的送达承诺。

### A07 — P2：推送签名与密钥管理边界不完整

**证据**：`push/PushMessageVerifier.kt:22-47` 与 `companion/src/server.mjs:34-37` 只签 sessionId/serverId/phase/detail/title，用换行拼接；未覆盖 directory、时间/序号。secret 为空直接放行。`core/ServerStore.kt:25-26,62-78` 将 pluginSecret 明文写入普通私有 preferences；仅非空时更新，无法通过清空字段删除。界面入口实际存在（`ui/Screens.kt:459-460,571-583,611-614`），不是死代码。

**动态确认**：改 directory 后原签名仍通过；重复同一签名没有新鲜度限制；`detail="a\nb",title="c"` 与 `detail="a",title="b\nc"` 的签名相同。签名格式本身可保持相同字节，却不唯一绑定字段边界。

**影响/限制**：在既有威胁模型“Firebase 发送权限被泄漏或过宽”下，攻击者持有旧消息时可重放/改未签名字段；空密钥配置没有这层防护。**仅拿到 FCM registration token 不足以证明有发送权限**，源代码注释中的这种暗示不成立。HMAC key 也是事件接收端的全局 pluginSecret，因此不是无敏感性的“验证配置”；但 App 私有存储可读仍需另一个前提，本轮没有发现任意 App 可读取它。

**修复**：版本化、无歧义的 canonical payload，签完整路由/序号/时间/设备标识；明确签名必需或显式兼容模式。用 Keystore 加密独立的推送验证密钥，避免复用插件入站认证密钥；区分保持/替换/删除密钥，支持轮换。

**验收**：空、清空、旋转密钥；重放和乱序；改变任一字段；字段含换行；旧版本迁移。不能只测一个已知 HMAC 向量。

### A08 — P2：注册成功不等于可恢复，设备权限缺少撤销闭环

**证据**：`companion/src/server.mjs:63-77` 捕获磁盘写错误并将 persist promise 转成成功；`95-108` 仍返回 `registered:true`。设备过期只在 `load/save` 检查（47-55、68），事件发送（129）不检查；只有 POST 注册，没有注销路由。Android 删除 profile 只清本地（`MobileController.kt:88-101`），关闭通知也不注销远端记录。

**动态确认**：将 registry 的 `.tmp` 路径设为测试目录模拟写入失败，注册返回 200/true；从**同一个未更新的 registry** 重建 companion 后设备数为 0。将已注册设备更新时间置为 181 天前，在只有事件流量的服务上仍能收到推送。

**影响**：磁盘故障造成虚假成功，重启失联；旧设备的注册在密码/Cookie 撤销之后仍然存在，事件路径不重新验证授权。TTL 不能提供持续撤销保证。关闭/删除本机 profile 会让正常客户端忽略消息，但不等于服务器停止向已登记 token 发送任务标题与目录。

**修复**：落盘成功后才返回 durable registration；I/O 故障返回可重试 5xx；按真实授权主体/设备支持注销、短租约续期和批量撤销，发送前检查到期状态，并清理 FCM 无效 token。限制注册总数/调用速率，避免长期无限 fan-out。

**验收**：磁盘满/权限错误、重启、仅有事件流量、凭据轮换、设备注销/丢失、token 变更；检查服务端真正停止发送，而非只检查 App 不弹窗。

### A09 — P2：多服务器任务监控与通知设置并不一致

**证据**：`system/TaskMonitorService.kt:32-80` 保存多个 `(serverId,sessionId)`，但只观察单例 Controller，54 行跳过所有非当前服务器。切换连接会取消旧 SSE（`MobileController.kt:103-109`）。服务发送通知不检查 `profile.notifications`；`TaskNotifications.show:93-96` 也仅检查系统权限。`send:367` 无条件启动服务。

**确定后果**：A 上任务运行时切到 B，A 留在 tracked 却不再更新；不存在独立后台连接继续监控 A。关闭 App 内通知后，从本机发送任务仍可触发服务的具体任务通知。服务的固定前台 ID 1001 只在 `onStartCommand` 设置；后续更新使用 session hash ID，结束时 `STOP_FOREGROUND_DETACH` 还保留初始“正在连接任务状态”通知（36-44、63-75）。

**修复**：明确产品只监控当前连接还是支持并行多服务器；前者显式中止/转移监控，后者建以 serverId 为键的连接管理器。前台服务保活通知与可关闭的任务通知分开；完成时移除保活通知，保留实际结果通知。

**验收**：两服务器并发任务、切换/删除 profile、任务完成/断线/不存在、禁用通知。前台通知展示和服务存活需要真机验证；本轮仅确认代码路径。

### A10 — P2：离线恢复有数据刷新但没有连接状态机闭环

**证据**：`core/MobileController.kt:117-122` 只有首次 `loadAll` 成功才启动 SSE；失败后 `showOffline` 不安排重试。用户点刷新走 `reload:175-178`，成功也不会 `startStream`。因此可以显示 `connected=true`，却没有实时流。

`loadSession:324-332` 失败命中缓存后置 `cached=true`；之后消息成功读取不把它清掉。`ui/ChatScreen.kt:565` 在 cached=true 时禁用发送。已经有 API 的离线模式也没有统一写操作门禁：菜单 rename/delete/revert、问题/权限按钮等仍可进入 Controller 的 `act`（420-423），不能兑现旧发布说明中的“离线禁用写操作”。

**修复**：连接建立、断开、重连、快照新鲜度、每个资源的缓存状态分开建模；刷新恢复时确保 SSE 已订阅；成功的权威消息覆盖应更新新鲜度。所有写操作统一判断可用性/用户意图，不能只在发送按钮设置 enabled。

**验收**：冷启动离线→服务器恢复→只点刷新；消息暂时失败→再成功；离线进入更多菜单/权限面板。断言流已建立、发送状态恢复、所有写操作有一致反馈。

### A11 — P2：文件引用路径被丢弃，草稿不具备应有的生命周期

**证据**：`ui/ChatScreen.kt:773-778` 的“引用到当前对话”调用 `onInsertRef(state.filePath)`；唯一上层接收方（125-128）丢弃 `ref`，仅切换 tab。Composer 只在 CHAT 分支构建（132-135），草稿是它内部的 `remember(sessionId)`（500-501），切换到文件/待办等标签后即脱离组合。

**确定行为**：文件内容读取、路径传递、按钮和回调都在，但没有任何一步把文件引用写入草稿。用户为了选文件离开对话时，原草稿也会丢失。快捷栏的另一个文件选择器（575-611）确实能插入 `@path`，不能把整个文件浏览/搜索或所有引用功能都判为死代码。

另一个丢稿窗口：发送等待期间输入框仍可编辑（545-550）；成功回调无条件 `draft=""`（561-563），会清掉发送之后新增的文本。

**历史**：`27268af` 新增浏览器引用回调时就只切 tab，没有证据证明这一按钮曾真正完成插入，因此属于**未接通功能链**，不能伪称“已下线旧产品”。

**修复**：草稿按 `(serverId,sessionId)` 提升到稳定状态容器，引用成为修改草稿的明确事件；发送按提交时草稿版本清理，不删除后续编辑。

**验收**：输入草稿→查看文件→引用→返回，原草稿和路径均保留；切 tab、旋转、返回列表再进入；慢发送时继续输入，新增内容保留。

### A12 — P2：创建和 Fork 的完成结果没有驱动导航

**证据**：`ui/Screens.kt:147-150` 调用异步 `createSession` 后立刻读取回调闭包中的旧 `state.sessionId` 来 `onOpen`。无旧会话时不打开详情；有旧会话时先打开旧会话。Sessions 页（280-286、331-335）创建后也未调用 onOpen。`MainActivity.kt:113-150` 进入详情由独立布尔值控制，Controller 更新 sessionId 不能自动导航。

`MobileController.fork:373` 调用异步 reload 后固定等待 300ms 再 `selectSession`，若新 fork 尚未出现在列表，选择直接失败（307-308）。

**修复**：创建/Fork 返回明确的 Session 结果，成功后一次性更新选择并触发导航；不要读取旧 Compose state 或用 delay 猜网络完成时间。

**验收**：无当前会话、有当前会话、慢网络、连续点击、Fork 刷新超过 300ms；展示的详情必须对应本次成功创建的 ID。

### A13 — P2：跨语言“共享契约”没有覆盖真实输入投影

**证据**：`docs/task-event-contract.json:53-56` 的测试命令案例给 Kotlin 的 tool 是 `bash` 且含 `gradle test`，给 companion 却人为写成 tool=`test`。真实插件（`companion/opencode-mobile.plugin.js:28-35`）只传 `part.tool`，不传 input，也没有生成 `test` 分类。`server.mjs:24` 只根据工具名判断 TESTING。

**动态确认**：把带 `bash + gradle test` 的同一个实际事件送进插件再送进 mapEvent，得到 TOOL；现有 fixture 用伪造的 tool=`test` 得到 TESTING，故测试通过却无法证明真实链一致。

V2 也有内部矛盾：`Models.kt:163-174` 从 `name` 读取 tool；`TaskReducer:245-253` 与插件却只读 `tool`。`v2ToolPartNotNormalizedForTaskReducer` 用仓库已支持的 V2 shape 得到 MessagePart.tool=`task`，任务阶段却是 TOOL 而非 SUBAGENT。其他差异包括 companion 不处理 status=`running`、不保留已有工具阶段，以及没有 Android Controller 的 pending-permission 完成抑制（`MobileController:212-214`）。

**修复**：定义真正的 canonical task event，明确 V1/V2 原始输入到它的投影；只传工具类别而非私密 command 内容。契约测试必须从同一原始事件分别通过完整适配器，再比较结果；覆盖状态序列，不能只比较经过人为调整的单事件 fixture。

**验收**：同一 V1/V2 trace 在前台与 FCM 产生一致阶段；含 bash 测试、subagent、waiting 后 idle、连续 busy、终态重复、重启后终态。

### A14 — P2：缓存删除不能与后台写入建立顺序关系

**证据**：`core/OfflineCache.kt:32-40` 在锁内取出并清空 pending 后，在锁外加密并写 preferences；`delete:99-105` 只删除还在 pending 的 key 和当前磁盘 key。已取出的 batch 不受删除影响。

**可执行时序（静态确认）**：writer 取出 A 的消息 → 删除 profile A 并清除缓存 → writer 完成加密，再次写入 A 的消息。加密数据仍可能残留；不等于已经泄漏给别的 App。会话远端删除（`MobileController:372`）也没有对应的单会话缓存删除路径。

此外只限制单条文本长度、单份快照最多 100 消息/300 会话（55-82），没有缓存 key 总量/总字节/过期策略；不断打开不同会话会不断增加一个 SharedPreferences 文件的内容。

**修复**：删除与写入共用串行事务/actor，或使用 server cache generation/tombstone 拒绝旧写入；删除会话时处理其缓存；增加总容量和 LRU/TTL，区分缓存不完整与完整历史。

**验收**：在“batch 出队、加密未完成”处暂停执行后删除 profile，恢复写入，断言 key 不再出现；大量会话与大 tool output 下验证容量边界。

### A15 — P2：资源边界和协程取消仍有缺口

**证据**：`core/OpenCodeApi.kt:106-135` 用阻塞 `execute()`，只有 finally 才在协程取消时 `call.cancel()`，无法中断正在等待的请求。`request().string()`、`requestBytes().bytes()` 全量读入；`dataObjects:141-161` 只防重复 cursor，不限页数/总量。文件二进制先全部下载并 Base64 编码（416-422），UI 随后只显示“不可预览”。

**动态确认**：`cancellingApiDoesNotInterruptNetworkWait` 取消一个延迟 1.3 秒响应的真实请求，取消等待仍超过 0.9 秒，直到网络调用返回。

虽然 HTTP I/O 和缓存加密已移出主线程，但 `obj/arr` 的 JSONObject/JSONArray 构造在 request 返回后执行；Controller scope 在 Main（`MobileController:59`），`OfflineCache.saveMessages:72-82` 的遍历/截断/toString 在入队之前执行。注释“序列化已在 IO”与真实行为不符。全历史加载和非 lazy 的大文本/逐行 diff（`ChatScreen:343-365,785-790`）放大资源消耗。

**修复**：将 Call 与协程 cancellation 即时绑定；网络读取、JSON 映射与缓存序列化一起放到受控后台 dispatcher；限制单响应/文件/页数/总消息量并支持按需分页，二进制先判断类型或限制读取。结合 A04/A05 对超限给出明确状态，不能吞成空数据。

**验收**：取消后尽快关闭 Call、连续搜索不积累旧请求、超大文件有可解释拒绝、唯一 cursor 无限分页能终止。尚未做设备 ANR/峰值内存测量，因此不提供猜测的耗时或内存数值。

### A16 — P2：授权动作缺乏足够上下文与可靠范围说明

**证据**：`system/TaskNotifications.kt:62-84` 的权限通知显示 session.title 与 TaskState.detail，并提供“允许一次/始终允许”，却不展示 `PermissionRequest.action/detail/always`；TaskState.detail 由 reducer 固定成“等待权限确认”（`Models:237`）。用户可以在不知道具体命令、文件模式或规则的情况下授权。

App 内 `ui/ChatScreen.kt:432-434` 将发送协议值 `always` 的按钮标为“当前会话记住”；`OpenCodeApi.kt:364-375` 仅传 reply/remember，没有表达有效期或作用域，客户端没有证据保证所有支持版本都只记住当前会话。`PermissionRequest.always/toolMessageId/toolCallId` 虽然解析出来（`Models:177-188`），没有被用于说明授权规则。`27268af` 还移除了之前的二次确认面板。

**修复**：系统通知至少展示动作与安全摘要；信息不足时只能打开 App 详情，不直接给长期授权。根据已验证的服务端能力展示实际范围；对 always 展示将保存的规则和可撤销入口。服务端权限域必须从目标版本的真实契约验证，本报告不假设一定跨会话持久。

**验收**：包含多个 path pattern、危险 shell、复杂 metadata 和 V2 save/source 的请求；用户确认页信息与实际发送的规则一致。

### A17 — P2：安装包交付链无法兑现稳定升级和可配置后台推送

**证据**：`.github/workflows/release.yml:25-60,88-103` 在全新 ubuntu runner 上构建并发布 Debug APK，正式 release 仅改变发布标识/文案；没有导入固定签名、备份调试 keystore 或注入 Firebase Android 配置。`app/build.gradle.kts:6,15-16` 只有存在 `google-services.json` 时启用配置，版本仍固定 2/0.1.0。

**产物确认**：从审计快照构建的 APK，merged manifest 为 `android:debuggable="true"`；apksigner 显示 `CN=Android Debug`；资源表没有 `google_app_id`。`PushRegistration.available:38` 因缺 FirebaseApp 而不可用。配置 companion URL 和 secret 本身不能让这种 APK 获得 FCM。

**影响/边界**：干净 runner 没有受控签名输入，不能保证不同发布包可覆盖升级；正式分发还暴露调试能力。这是发布工作流的缺口，**没有下载并比较历史已发布 APK 的实际证书**。FCM 是合法的可选构建功能，不等于整条推送业务已下线；问题是发布包与用户可配置能力的边界未在交付策略中定义。

**修复**：正式/预发布都使用稳定、受保护的签名，并明确版本递增规则；正式版禁止 debuggable。提供清晰的带 Firebase 配置构建通道，或将官方包定义成无 FCM 变体并在配置说明中明确必须重新构建，避免 UI 参数暗示能补齐编译期能力。签名问题若要求用户卸载，会连带丢失本地 Keystore 凭据与缓存，应先规划迁移。

**验收**：两次独立 runner 构建证书一致并做保数据覆盖安装；检查 versionCode、debuggable、Firebase 初始化；FCM 真设备投递另行验收。预发布文档已承认调试签名限制，不应把既知限制伪装成新发现，但正式发布通道仍需要阻断。

### A18 — P3：内部仍存活、产品没有出口的数据链

全库搜索排除了以下“有引用所以有功能”的误判。来源与消费证据均位于生产代码，不只依赖编译器 unused 检查。

| 链路 | 内部引用为何仍存在 | 产品出口/历史结论 | 建议 |
|---|---|---|---|
| 工具附件 | `Models:134,140,165,174 → MessagePart.attachments → OfflineCache:79,91 → MessagePart`；单测还断言附件名称 | `ChatScreen:283-302` 不读取 attachments；全库没有打开/下载/显示入口。由 `a67fd0e` 带入，现有历史不能证明曾有可用附件 UI | 若不计划交付，整链删除 DTO 字段、映射、cache schema 和仅验证映射的测试；若保留，应补真正出口和旧缓存迁移 |
| V2 工具输出路径 | `state.outputPaths → MessagePart.files → cache → MessagePart.files` | 唯一 UI 读取 files 是 `patch/diff` 分支（293-297），实际 V2 tool 进入 tool 分支（287-291），因此该分支产出的 files 无消费。V1 patch.files 仍有用 | 按来源/part 类型清理，不能全局删除 files |
| 权限规则与工具来源 | V2 SSE 的 save/source 归一化（`OpenCodeApi:492-496`）→ `toPermission` → always/toolMessageId/toolCallId | UI、通知、reply 都不读取这些字段，授权上下文在中间丢失。属于 A16 的未完成安全交互，不应简单为了减代码删除 | 补授权范围及关联工具展示；不交付时明确停止收集 |
| 问题 header、FileNode.name、FileChange.status | JSON → DTO 字段；部分还有模型单测 | `QuestionPanel` 只读 title/options/multiple/custom；文件 UI 从 path 截取名称；改动 UI 不读 status。`modelsExposeTheFieldsTheUiReads` 这个测试名不等于 UI 真在读 | 逐字段决定消费或删除，测试转向用户行为 |
| 通知专用短超时参数 | `OpenCodeApi.callTimeoutSeconds → client.callTimeout` 仍互相引用 | `e5b7e5f` 有通知 8 秒 caller，`b80084f` 删除 caller；当前生产无非默认参数传入，注释仍说通知会传小值 | 随 A03 恢复真正 caller，或删参数/错误注释，不能把参数存在当防护已生效 |
| V2 Todo/Diff 的“不支持”提示 | `TodoPanel/ChangesPanel` 内仍有 V2 文案 | `ChatScreen:40-45` 已在入口隐藏这两个 tab。V1 整条链仍可达，不能删除其 API/DTO | 清理不可达提示分支或建立显式能力视图 |

**特别说明**：本轮没有找到足够产品历史证据证明“某个完整、曾交付的大模块后来明确下线但整链残留”。确实确认了上表的**无产品消费端链**，以及 A11 从引入时就未接通的假入口。二者都应处理，但不能编造“过去已下线”的故事，也不能把 V1 兼容层、可选 FCM 和 Xiaomi 适配一概删掉。

## 5. 历史修复复核与不应误报的项目

| 历史/当前设计 | 本轮判断 |
|---|---|
| `e5b7e5f` 修正路径编码与部分安全/可靠性问题 | 当前 ID 只编码一次的单测通过，不再把旧双重编码列为漏洞；通知超时/目录修复后来被 `b80084f` 回归，见 A03 |
| `9a3f7d8` 提取连接池、并行请求、缓存后台加密 | 优化实际存在；不是所有工作都移到后台，见 A15。共享连接池不等于共享 HTTP 认证，也不能替代每请求凭据校验 |
| `b80084f` 流式 part upsert 与 HMAC | 均有真实 caller 和界面配置入口；仍存在 A04/A07/A13 的交付、协议和安全边界缺口 |
| `3e957e6` 提取 KeystoreCipher，删除 password 重载及未用字段 | 重复 AES-GCM 已清理，已删除字段不重复报告。A18 是剩下的数据消费链审计，不能仅按引用次数做删除 |
| Lint 的 `StaticFieldLeak` | Controller 以 `applicationContext` 构造（`MobileController:52-53`），不足以认定持有 Activity 导致泄漏；不计入漏洞 |
| `usesCleartextTraffic=true` | 单看 Manifest 不能断言任意 HTTP 自动获准，应用还有 profile 开关。现存问题是重定向未逐跳执行同一策略（A01） |
| Xiaomi extras / Live Updates / optional Firebase | 都有实际调用路径。没有真机验收只是证据缺口，不等于死代码或功能必然无效 |
| 手工拼接 HTTP/JSON/消息展示 | 没有 WebView/HTML 执行或 companion 远程 shell 路径，不能因为展示模型文本就报告 XSS/RCE |
| Gradle/npm/action 版本 | lockfile 178 个依赖条目均有 integrity，resolved 均为 npm registry HTTPS；未做在线漏洞数据库核验，不声称依赖无漏洞，也不把升级提醒算成漏洞 |

结构性技术债应随修复消化，而非另开“大重写”：Controller 同时承担连接、catalog、会话、命令、缓存和通知，导致 A02/A05/A09/A10 共用全局状态；两套 reducer 与投影分散导致 A13。建议分出每服务器连接/快照仓库、带不可变目标的命令执行器、归一化事件模型与 UI 状态层。先锁定行为测试再移动代码；单纯拆文件不算消除边界问题。

另有低成本工程加固项：Gradle distribution 没有 SHA-256 校验字段，Actions 使用主版本 tag 而非 commit SHA，Android 依赖未启用可见的验证元数据；这些是供应链可复现性改进，当前没有篡改证据。构建输出固定到 `/tmp/opencode-mobile-gradle/${project.name}`（根 `build.gradle.kts:7-9`），同仓库多 worktree 并发构建可互相污染，应按 checkout/hash 隔离，而不是只共享依赖缓存。

## 6. 建议修复顺序与交付门槛

### 第一批：保护凭据和命令目标（A01、A02、A03）

先统一凭据传输/重定向策略；为每个异步操作显式绑定 server/client/directory/session；将通知命令从 UI 单例连接切换中解耦。补跨源目标零请求、切服务器竞态、冷启动通知和目录路由测试。完成前不继续扩展多服务器并行任务功能。

### 第二批：让状态与送达可被相信（A04、A05、A06、A10）

建立事件溢出/断线/恢复对账机制；失败保持 unknown/stale，不能变为空闲/完成；companion 引入可靠接收和投递状态、顺序信息及可观察错误。验收标准是故障后最终收敛与不产生虚假完成，不是仅增加重试次数。

### 第三批：关闭后台安全与产品操作断点（A07、A08、A09、A11、A12、A16）

完成密钥轮换和撤销、设备注销及持久化失败反馈；明确监控/通知策略；修复草稿、引用、创建/Fork 导航和权限说明。签名开启后还需覆盖密钥缺失与迁移，避免升级导致所有推送被静默丢弃。

### 第四批：统一协议契约与资源策略（A13、A14、A15）

用真实同源 trace 驱动两端测试；缓存采用有序删除和总量限制；网络支持取消和分页。不要将“删掉字段让测试更少”作为通过方式。

### 第五批：交付与精确清理（A17、A18）

在任何正式发布前将 A17 提升为发布阻断项：稳定签名、不可调试产物、明确 Firebase 变体、覆盖安装保留数据。随后逐链处理 A18；每一项清理同时说明它在产品中被删除还是被补齐，连同映射、缓存与测试一起处理。V1 功能和可选集成必须保留其有效意图。

修复后的最低新增验证面：

| 层级 | 应覆盖的真实失败/交互 |
|---|---|
| Controller | A/B 服务器与多项目切换、延迟响应、命令执行后上下文变更、局部端点失败、缓存恢复 |
| 网络与事件 | 跨源/降级重定向、取消、SSE 溢出/重放/乱序/正常关闭、超大响应 |
| UI | 新建→打开、Fork→打开、草稿切页/旋转、文件引用、慢发送继续输入、权限规则展示 |
| 通知 | 禁用自动连接冷启动、后台执行寿命、失效 profile、V1 目录、多服务器任务与开关 |
| companion | 磁盘故障、FCM 失败、并发/重复、重启/长任务、设备注销/到期、插件真实投影 |
| 发布与设备 | 相邻版本覆盖安装、debuggable 检查、Firebase 配置变体、真实 FCM 与 OEM 展示 |

现有 CI 已有 PR/main 构建与测试（`.github/workflows/ci.yml`），但当前 Android 只有一个 15 项的 JVM 测试类，Node 只有 5 项；没有仓库内 Controller/Compose/通知生命周期仪器测试。应把上表的边界测试纳入 CI 或明确的设备验收门槛。不要仅让静态扫描数量变少。

## 7. 本轮验证、复现证据与限制

### 已执行的验证

| 验证 | 结果与计数依据 |
|---|---|
| 规定的 Android `:app:testDebugUnitTest :app:assembleDebug` | 原有 **15 项**测试通过，APK 构建成功；通过临时 init script 指定构建目录，不改项目源文件 |
| `cd companion && node --test` | **5/5** 通过；直接执行仓库现有测试，未安装/启动 Firebase 服务 |
| `:app:lintDebug` | BUILD SUCCESSFUL；XML 中 **0 Error、27 Warning**，大部分是依赖升级/KTX 建议；未把警告计数等同于漏洞数量 |
| 临时 `AuditRegressionTest` | **9/9** 探针确认预期缺陷；与原测试合跑 **24/24** 通过，并再次构建 APK |
| `node --test audit-companion.mjs` | **7/7** 探针确认预期缺陷；使用 stub send/fetchSession 与 loopback HTTP，不实际发送 FCM |
| APK 检查 | apksigner：Android Debug；merged manifest：debuggable=true；aapt2 资源表无 google_app_id |
| npm lockfile 结构 | lockfile v3；178 个依赖条目均有 integrity，无非 npm registry resolved URL；不等于 CVE 扫描 |
| 源码完整性 | 导出提交中的受控文本/二进制文件与 Git blob 逐一比对，确认审计没有修改业务源码 |

**探针“通过”的含义是成功复现缺陷，不是证明代码安全。** SSE 实验已出现 65/201 和 66/201 两种调度结果，最终权限事件均缺失。不要用普通测试全部绿色覆盖这些反证。

临时证据目录：`/tmp/opencode-audit-20260928-fIJo8c`。它是本机辅助证据，`/tmp` 可能被系统清理，本报告中的源码证据与复现步骤不依赖其永久存在。

关键文件：

- `gradle-validation.log`：最初的规定 Android 验证。
- `audit-isolated-validation.log`：最终独立目录的 24 项测试与 APK 构建。
- `audit-tests/AuditRegressionTest.kt`：9 项 Kotlin 探针。
- `audit-companion.mjs`、`audit-companion.log`：7 项 companion 探针与输出。
- `build-output/app/reports/lint-results-debug.xml`：27 条 Lint 警告。
- `isolated-build/app/test-results/testDebugUnitTest/`：最终 JUnit XML。
- `isolated-build/app/outputs/apk/debug/app-debug.apk`：本次构建产物，未安装、未发布。

原有根构建脚本固定输出路径。首次 init script 在 `projectsEvaluated` 阶段调整目录时，部分 APK task 仍沿用项目默认 `/tmp/opencode-mobile-gradle` 路径；最终改为 app 配置前设置输出路径并重新构建，证据以 **isolated-build** 结果为准。没有把旧目录中的既有 APK 当成当前产物。

最终复现命令（本机临时证据仍在时）：

```sh
cd /tmp/opencode-audit-20260928-fIJo8c
ANDROID_HOME=/home/lvziw/Android/Sdk GRADLE_USER_HOME=/home/lvziw/.gradle \
  ./gradlew --init-script audit-isolated.init.gradle :app:testDebugUnitTest :app:assembleDebug
node --test audit-companion.mjs
(cd companion && node --test)
```

### 16 项探针对照

| 探针 | 验证的生产路径/实际结果 |
|---|---|
| rawCookieFollowsCrossHostRedirect | 目标 Host 改变后仍收到 Cookie；Authorization 被删除 |
| pairRedirectRejectsOnlyAfterSendingToDisallowedHttpHost | resolver 最终拒绝之前，目标已收到 bearer 路径 |
| oldServerFileResponseWritesIntoNewServerState | Controller 当前 serverId=B，文件内容来自 A |
| abortWithEmptyDirectoryOmitsRoutingContext | V1 abort 请求没有 directory 参数 |
| sseOverflowSilentlyLosesPermissionEvent | 201 条只收到部分，最后权限请求缺失 |
| missingStatusBecomesFalseCompletion | 实际 reducer 对上游失败缺省值返回 COMPLETED |
| cancellingApiDoesNotInterruptNetworkWait | cancelAndJoin 仍等待接近 HTTP 延迟 |
| signatureDoesNotBindDirectoryOrFreshness | 改目录/重复签名仍通过，空密钥放行 |
| v2ToolPartNotNormalizedForTaskReducer | 同一 V2 part 的消息 tool=task，任务阶段却是 TOOL |
| delivery failure acknowledged and identical retry permanently deduplicated | 两次事件 200，失败 send 只尝试一次 |
| slow earlier metadata lookup sends stale status after terminal status | COMPLETED 先发，旧 WAITING_PERMISSION 后发 |
| disk persistence failure still returns registered true; same registry restart loses device | 持久化报错却注册成功，同路径重启设备数为 0 |
| plugin-to-reducer actual bash testing phase contradicts shared fixture | 实际插件输出 bash→TOOL，fixture 人工 test→TESTING |
| signature admits ambiguous newline field boundaries and unsigned directory | 不同 detail/title/directory 对应相同签名 |
| six hour expiry can lose completion after unrelated event prunes active state | 被清理的 active 会话后续 idle 没有完成推送 |
| expired registration continues receiving on event-only server | 181 天的设备记录仍获投递 |

### 无临时脚本时的最小复现方法

以下均只使用测试数据，不涉及用户凭据：

1. **Cookie 重定向**：准备两个 MockWebServer；A 的地址使用 localhost，302 到 B 的 127.0.0.1 地址；用 `OpenCodeApi(profileA, ServerCredentials(cookie="opencode-session=fixture"))` 调 health；检查 B 的 Cookie 请求头。比较 Host 头，不能用 MockWebServer 重建的 requestUrl.host 误判目标。
2. **状态串写**：以 A API 调 `MobileController.readFile`，延迟其 HTTP 响应；响应返回前切换到 B，观察 `serverId/fileText` 是否来自不同服务器。无需假造返回处理逻辑。
3. **SSE 丢消息**：health 后订阅 `/global/event`；服务端连续发送 200 条 session.status 和一条 permission.asked；消费者每条延迟 10ms；统计接收类型，最后权限事件不应消失。
4. **FCM 丢失**：创建真实 `createCompanion`，令 `verifyDevice` 测试桩成功、`send` 测试桩抛错；通过 HTTP 注册测试设备，连续两次 POST 同一 permission.asked；断言响应与 send 计数。
5. **注册落盘失败**：预先写 `devices.json={"devices":[]}`，创建名为 `devices.json.tmp` 的目录；注册设备；再以同一路径 load 新 companion。模拟错误后返回成功与设备丢失均可见。
6. **状态机投影**：将 `{type:"tool",tool:"bash",state:{input:{command:"gradle test"}}}` 作为 message.part.updated 送给真实插件；捕获 fetch body 后传入 mapEvent，对照共享 fixture 的 companion 输入。不要把 tool 手动改成 test。

### 尚未验证的边界

没有真实 OpenCode `/doc` 或服务端实现快照，故不推断当前所有服务端版本都遵循仓库命名的 V1/V2；API URL/事件 shape 的真正兼容性仍需目标实例契约测试。没有 Android 真机/模拟器 UI 操作、低内存进程回收、FCM 实际投递、Live Updates 提升、HyperOS 岛展示或历史 APK 覆盖安装证据。强制停止 App 与普通进程回收应分开验收，不能用“关 App”笼统替代。

本报告提供的是代码级和隔离运行级证据，不宣称完成这些运行环境验收。所有建议尚未实施。

## 8. 交付状态

- 本地分支保持 `main`，本地 HEAD 保持 `b8595bc`；主审基线为远端 `3e957e6`。
- 当前目录：`/home/lvziw/项目/opencode-app`；只新增未提交的 `AUDIT.md`。
- 本轮没有开发分支/新 worktree；使用上述 `/tmp` Git 导出快照完成只读审计和隔离复现。
- 原有 worktree 未更改、未清理；审计快照和复现证据暂留，便于复查。
- 未集成、未发 PR、未发布、未部署；业务代码未改。下一步应按第 6 节分批修复并验证，而非把本报告的测试通过解读为问题已经修复。
