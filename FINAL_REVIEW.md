# AUDIT.md 整改终审

日期：2026-09-29。**结论：不能结束本轮整改，不建议将当前整改分支认定为可交付。**

此次未调用 Memory，未读取其他聊天记录。检查范围是 AUDIT.md、NEEDS_SOL_REVIEW.md、整改提交差异及受影响的调用链；没有机械重审全部未修改模块。

## 1. 审查对象与证据边界

- 主目录 `/home/lvziw/项目/opencode-app` 在 `main@b8595bc`，受控文件的工作区和暂存区 diff 均为空，只有原有未跟踪的 AUDIT.md。
- 真正的整改代码在分支 `fix/audit-remediation`、worktree `/home/lvziw/.local/share/opencode/worktree/e247a1/witty-lagoon`，HEAD 为 `4e65e40a8c69b28ac6865a468cf29bf1f0dfa34a`。该工作区干净，未被本次修改。
- 已 fetch origin 并 prune worktree 记录；`origin/main=db7289d`。审计基线 `3e957e6` 至整改 HEAD 的整体差异有 30 个文件；其中 `db7289d..4e65e40` 是本次主要整改差异，发布准备提交与整改分开判断。
- 在 `/tmp/opencode-final-review-20260929-PvSGZg` 导出该 HEAD，运行原有验证和新增故障探针。以下代码位置均指整改 HEAD；链接指向导出快照。
- 动态证据来自真实生产 Kotlin 类、MockWebServer、真实 companion HTTP handler；Controller 探针通过反射注入内存 preferences、运行 scope/API/初始 state，绕开 Android 构造并停用无关后台刷新，没有替换被测操作方法。它们不是设备端测试。

## 2. 必须修复的发现

### R01 · P1 · 改服务器地址时，旧 Cookie 仍会在正式连接中泄漏（A01 未闭环）

位置：[Screens.kt:400](/tmp/opencode-final-review-20260929-PvSGZg/app/src/main/java/com/igng/opencode/mobile/ui/Screens.kt:400)、[ServerStore.kt:84](/tmp/opencode-final-review-20260929-PvSGZg/app/src/main/java/com/igng/opencode/mobile/core/ServerStore.kt:84)。证据：静态完整调用链。

表单确实在跨 origin 的测试请求中置 `cookie=""`，但测试成功后调用 `saveServer(actualProfile, carryPassword, credentialUsername=...)`，没有传 cookie。`ServerStore.save` 把 null 解释为保留旧值；Controller 随即 connect，新 API 会从存储取回旧 Cookie，发到新域名/端口。触发条件是已有 Cookie 的 profile 改地址，并用新密码通过地址测试。新增的 origin 检查只保护了测试阶段。

另一个回归是同 origin 的 Cookie-only 配对账号：`carryPassword` 为空即报“地址已更改”，导致只修改名字、通知等设置也无法保存。

修复：保存与测试使用同一份已校验凭据；origin 改变时显式删除旧 Cookie，不能用 null 的“保留”语义。允许未改 origin 且已有有效 Cookie 的认证方式。回归测试必须检查保存后的实际连接请求，而不只检查 testServer。

### R02 · P1 · 操作上下文仍不绑定项目、会话和排队时的命令参数（A02）

位置：[MobileController.kt:451](/tmp/opencode-final-review-20260929-PvSGZg/app/src/main/java/com/igng/opencode/mobile/core/MobileController.kt:451)、[send:475](/tmp/opencode-final-review-20260929-PvSGZg/app/src/main/java/com/igng/opencode/mobile/core/MobileController.kt:475)、[deleteSession:498](/tmp/opencode-final-review-20260929-PvSGZg/app/src/main/java/com/igng/opencode/mobile/core/MobileController.kt:498)、[readFile:553](/tmp/opencode-final-review-20260929-PvSGZg/app/src/main/java/com/igng/opencode/mobile/core/MobileController.kt:553)。证据：动态 + 静态。

`OperationContext` 只有 token/serverId/client。以下缺陷已通过生产方法复现：

- A 项目读取文件未完成时切到 B 项目，返回的 A 文件仍写进 B 当前页面。
- 删除会话 A 的请求未完成时选择 B，A 删除成功后仍把当前 sessionId 清空。
- 两个 send 在互斥锁上排队时切服务器，第二个操作保留旧服务器 client，却在取到锁后读取新服务器的 session/agent/model。探针在服务器 A 收到了针对 B 会话 ID 的 prompt；B 收到 0 次请求。实际是否错误执行取决于旧服务器能否解析该 ID，但目标绑定已经失效。

创建结果也仅检查服务器，不检查期间的项目/选择变化。文件搜索用 query 字符串判断新旧，不能区分跨项目同名 query 或 A→B→A 的请求。

修复：点击时一次性捕获 server revision、generation、project、directory、session、文本、agent/model 和目标 client；锁内重新验证捕获的命令能否执行，不重新组合当前 state。提交按资源身份及请求序号检查；删除只在仍选中被删会话时清选择。旧发送完成启动 TaskMonitorService 也应使用原 profile 策略且不得把 UI 切回旧服务器。

### R03 · P1 · 当前发布 V2 的核心请求契约仍不匹配（NEEDS_SOL_REVIEW 第 5 节）

位置：[OpenCodeApi.kt:378](/tmp/opencode-final-review-20260929-PvSGZg/app/src/main/java/com/igng/opencode/mobile/core/OpenCodeApi.kt:378)、[权限和问题:435](/tmp/opencode-final-review-20260929-PvSGZg/app/src/main/java/com/igng/opencode/mobile/core/OpenCodeApi.kt:435)。证据：本次重新下载官方规范 + 实际请求捕获。

[官方 V2 OpenAPI](https://opencode.ai/v2/openapi.json) 要求 prompt 顶层必填 `text`，权限回复必填 `decision`，两者都声明 `additionalProperties:false`。当前实际发出的是 `{prompt:{text}}` 和 `{reply}`。新增 health 回退只让连接检测成功，没有让发送和授权可用。问题交互仍访问规范不存在的 `/api/question/request`，404 又被转换为空列表；当前规范使用 form 接口。

这确认的是“客户端与该已发布规范不兼容”，不是声称已在用户目标服务器运行失败。不同 V2 修订是否仍接受旧形状，需要版本证据。

修复：在连接时按目标实例的 OpenAPI/明确版本选择完整适配器，统一 prompt、permission、form 及事件归一化。不要对非幂等写请求做宽泛“失败就换请求体重发”；必须证明前一次未执行，或使用幂等 ID。目标版本未知时明确提示不支持，不应显示连接成功后再静默丢交互。

### R04 · P1 · outbox 仍不能保证已接受事件可恢复、有序收敛（A06）

位置：[server.mjs:78](/tmp/opencode-final-review-20260929-PvSGZg/companion/src/server.mjs:78)、[落盘:122](/tmp/opencode-final-review-20260929-PvSGZg/companion/src/server.mjs:122)、[重试:162](/tmp/opencode-final-review-20260929-PvSGZg/companion/src/server.mjs:162)、[事件接受:253](/tmp/opencode-final-review-20260929-PvSGZg/companion/src/server.mjs:253)、[启动:341](/tmp/opencode-final-review-20260929-PvSGZg/companion/src/server.mjs:341)。证据：4 个动态探针 + 静态。

- outbox 写失败被 catch 吞掉，enqueue 不等待落盘，HTTP 仍返回 202，内存去重状态已更新。模拟磁盘目标为目录后确认仍接受成功。进程中断可能丢掉唯一事件。
- load 只把 pending 放回数组；start 没有调用 drainOutbox、没有重建定时器。重启后没有新可归约事件时，恢复队列不会重试；探针等待后 send 为 0，显式调用 drain 才发送。
- 重试不按会话/设备版本收敛。等待权限的推送先失败，完成推送成功，旧推送随后重试成功，实际顺序为 `COMPLETED → WAITING_PERMISSION`。签名时间戳只是新鲜度窗口，Android 没有序号去旧。
- states 仍是 6 小时过期的纯内存“去重”缓存，但 mapEvent 依赖它判断完成；7 小时后 idle 不产生完成通知，重启也丢相同历史。

插件依然忽略 HTTP status、没有可靠重试；outbox 达 500 条会静默 shift，六次失败后直接移除且没有死信反馈。增加队列和重试次数不等于原问题解决。

修复：原子持久化接受状态与 outbox 后再返回 202，失败返回可重试错误；启动恢复投递器；按服务器/会话/设备持久化序号，旧投递在新状态确认后失效，客户端也拒绝旧序号；保存足以识别长任务终态的状态；插件提供有界可靠接受重试，容量/重试耗尽必须可观察。

### R05 · P2 · 注销没有完整的认证、持久化与队列撤销闭环（A08）

位置：[MobileController.kt:96](/tmp/opencode-final-review-20260929-PvSGZg/app/src/main/java/com/igng/opencode/mobile/core/MobileController.kt:96)、[unregisterPush:585](/tmp/opencode-final-review-20260929-PvSGZg/app/src/main/java/com/igng/opencode/mobile/core/MobileController.kt:585)、[server.mjs:140](/tmp/opencode-final-review-20260929-PvSGZg/companion/src/server.mjs:140)、[注销接口:237](/tmp/opencode-final-review-20260929-PvSGZg/companion/src/server.mjs:237)。证据：动态 + 静态。

App 先 `store.delete(id)` 删除密码/Cookie，再由注销协程读取 `store.credentials(id)`；因此需要认证的服务收到无凭据请求。关闭通知、移除/更换 companion 配置也没有可靠注销旧注册。

companion 注销只删 devices，不清 outbox；deliver 找不到注册设备时反而继续发送。探针确认注销返回 200、devices 为空后旧队列仍发送成功。注销的落盘 save 也不等待且吞错，重启可能恢复旧注册。load 时剪掉过期设备同样不能阻止其残留 outbox 发送。

修复：删除前捕获 profile+credentials，持久化可重试撤销意图；服务端原子删除注册和对应待投递消息，落盘成功再回执；每次投递都要求注册仍存在且有效，并匹配当前 token/设备身份。

### R06 · P2 · 降级快照没有保持完整状态，缓存命中又被标成新鲜（A05/A10）

位置：[MobileController.kt:213](/tmp/opencode-final-review-20260929-PvSGZg/app/src/main/java/com/igng/opencode/mobile/core/MobileController.kt:213)、[loadSession:419](/tmp/opencode-final-review-20260929-PvSGZg/app/src/main/java/com/igng/opencode/mobile/core/MobileController.kt:419)。证据：动态 + 静态。

两项目中 A 的 sessions 500、B 成功时，A 的旧 sessions 被合并保留，但 tasks 仅遍历本轮成功取得的 sessions，sessionId 检查也只使用这份不完整列表；缓存写入更早，写的是缺 A 的目录快照。探针确认 A 会话仍在列表，运行任务和选择却消失。

loadSession 从缓存兜底后先置 cached=true，随后无条件把同一份旧数据 saveMessages 并置 cached=false，绕过了“必须由成功权威读取清除旧快照标记”的条件。全局 cached/connected 不足以表达 transcript/status/permissions 分别是否可靠。

修复：按资源保存 success/error/stale，合并后的可信会话集合用于 tasks、选择和缓存；缓存回退必须保留来源与旧标记，不能当成成功网络读取。读失败也不应把另一个当前会话的全局缓存标志改掉。

### R07 · P2 · 缓存删除的竞态仍存在（A14）

位置：[OfflineCache.kt:89](/tmp/opencode-final-review-20260929-PvSGZg/app/src/main/java/com/igng/opencode/mobile/core/OfflineCache.kt:89)、[delete:157](/tmp/opencode-final-review-20260929-PvSGZg/app/src/main/java/com/igng/opencode/mobile/core/OfflineCache.kt:157)。证据：静态可执行时序，未宣称真机复现。

序号检查发生在加密之前。writer 检查通过 → delete 增加序号并删键 → writer 加密结束写回，已删数据仍复活。删除 profile 只枚举 index/preferences，不含尚未写入的新 pending/batch key，部分删除甚至不会留下对应 tombstone；pruneSequences 后以 `?:0 <= captured` 判定还可能放行已丢墓碑的旧写。

修复：加密可在锁外，但落盘前必须在与 delete 相同的锁/actor 中再做严格世代检查并原子提交；对整个 server 设删除世代，覆盖尚未落盘的键。增加暂停在加密中途再删除的回归测试。150 个条目的限制有改善，但不是总字节预算。

### R08 · P2 · Fork 的修复变成了直接丢弃返回结果（A12 回归）

位置：[MobileController.kt:505](/tmp/opencode-final-review-20260929-PvSGZg/app/src/main/java/com/igng/opencode/mobile/core/MobileController.kt:505)。证据：动态。

此前的固定 300ms 等待确实删掉了，但现在只调用 forkSession，返回的 Session 被 Unit lambda 丢弃，没有插入列表、刷新或选择。探针确认 HTTP 返回新 fork，仍停在原会话，新 ID 也不在 state.sessions。创建会话的回调改进不能覆盖这条路径。

修复：使用 fork 返回值更新列表和选择，再驱动导航；按 R02 的会话上下文守卫，避免用户离开后抢回页面。

### R09 · P2 · 默认预发布流程必然撞上新增的 debuggable 检查（A17 回归）

位置：[release.yml:60](/tmp/opencode-final-review-20260929-PvSGZg/.github/workflows/release.yml:60)、[产物检查:117](/tmp/opencode-final-review-20260929-PvSGZg/.github/workflows/release.yml:117)。证据：静态 workflow + 本次 APK 检查。

没有签名 secrets 的默认预发布仍选择 assembleDebug，后面却对所有发布无条件拒绝 application-debuggable。本次生成的 APK 确有该标志，所以文档承诺的 fallback 无法发布。

配置 `_KEYSTORE_FILE` 也仅传了路径：干净 ubuntu runner 中没有下载/解码 keystore 的步骤。正式版最终会拒绝 debug 证书是有效门禁，但不构成可执行的稳定签名交付链；预发布稳定升级、版本递增和明确 Firebase 构建变体仍未闭环。

修复：统一发布 release build，提供受保护的 keystore 实际注入步骤，检查全部签名输入并固定证书；不要靠放宽产物检查解决。明确不含 FCM 的官方变体或提供配置注入。保留历史安装数据的覆盖升级必须单独验收。

### R10 · P2 · 新签名被无期限旧协议兼容绕过，重放/设备校验仍缺失（A07）

位置：[PushMessageVerifier.kt:46](/tmp/opencode-final-review-20260929-PvSGZg/app/src/main/java/com/igng/opencode/mobile/push/PushMessageVerifier.kt:46)、[MobileMessagingService:147](/tmp/opencode-final-review-20260929-PvSGZg/app/src/main/java/com/igng/opencode/mobile/push/PushRegistration.kt:147)。证据：动态 + 静态。

新 v2 canonical payload 对字段边界、目录和时间签名有效；但任何非 2 版本都落到 v1，缺少按 profile 固定最低版本和兼容截止点。捕获的旧 v1 签名仍可无限重放并改目录，探针通过。v2 在 24 小时内也可重复通过，没有处理序号/已接受 ID，签名中的 deviceId 没与本机 deviceId 比较。空 secret 仍无条件放行；清空密钥因而不是“撤销推送信任”，而是关闭验证。

该风险沿用审计中的前提：攻击者具有投递能力和/或捕获旧签名；仅有 registration token 不等于能发送 FCM。

修复：明确“未配置/启用/兼容/撤销”状态，默认必需签名；已升级 profile 禁止自动降级；设备身份校验及持久化防重放序号与 R04 配套。推送验证 key 应与插件入站认证 key 分离。密钥存储应有版本化 envelope，不能把所有解密失败的 Base64 密文都当旧明文再次加密。

## 3. 其余影响实际使用的遗漏

- **A03 通知命令仍有目标竞争**：[NotificationActionReceiver.kt:73](/tmp/opencode-final-review-20260929-PvSGZg/app/src/main/java/com/igng/opencode/mobile/system/NotificationActionReceiver.kt:73) 在 IO 线程先检查当前 server，再调用 Controller 的 beginOperation 取 client；Main 线程在两步之间切服务器仍能错配。应始终使用独立的不可变通知目标 client，然后针对原目标同步 UI。goAsync、总超时、完整 directory、成功后撤通知均是有效改善。Push 通知的停止按钮走同一 Receiver，但成功只 cancel 普通通知 ID，showPush 使用另一 ID，旧推送通知仍可能保留。
- **A09 监控仍与产品描述不一致**：[TaskMonitorService.kt:64](/tmp/opencode-final-review-20260929-PvSGZg/app/src/main/java/com/igng/opencode/mobile/system/TaskMonitorService.kt:64) 对非当前服务器直接跳过且永不移除 tracked，通知还声称“正在后台跟踪任务状态”。删除 profile、会话不存在、断线完成也缺退出路径。前台通知 REMOVE 与通知开关检查已改善。
- **A04/A10 对账仍会丢完成语义**：断线把 active phase 替换成 DISCONNECTED，重连遇到 idle 归约成 IDLE，丢失之前正在运行的事实；生产 reducer 探针确认。周期 loadAll 设置 connected=true，也不能单独证明 SSE 已恢复；对账失败仍 runCatching 忽略。应把连接健康与最后可信任务阶段分开。
- **A11 草稿只修到标签切换**：[ChatScreen.kt:55](/tmp/opencode-final-review-20260929-PvSGZg/app/src/main/java/com/igng/opencode/mobile/ui/ChatScreen.kt:55) 的 remember map 仍随返回列表/旋转销毁。发送先 trim，回调却以原草稿对比共享 submitted；带空白的草稿（包括自动插入引用后的空格）发送后不清空，重复发送还能让旧回调清理后一次文本。应提升可保存的 `(serverId,sessionId)` 草稿状态，并按不可变文本版本清理。
- **A13 两端仍不等价**：companion 不处理 status=running，busy 会丢已有工具阶段；plugin 只把 task 当 SUBAGENT，Android 还支持 subagent；waiting 后 idle 也没有 Controller 的 pending request 抑制。TESTING 与 V2 name 的局部修复有效，但共享 fixture 没有代替真实原始 trace 的全链投影测试。
- **A16 权限规则显示仍会丢失**：V2 REST 直接 toPermission，只读取 always/tool，实际 save/source 丢失；SSE 只在部分分支做转换，还读取 source.callID 而非 source.id。探针确认 REST 的规则及 toolCallId 为空。通知即使缺少动作细节仍给“始终允许”，没有能力判定或二次说明。
- **A15 资源限制是改善，仍有静默截断**：40 页/5000 条达到上限后 break，调用方仍把部分列表当完整权威数据；大列表映射、requestObject 和缓存读取还有主线程路径。应返回明确的 partial/cursor 状态，分页 UI 可另行迭代，不能以空值/完整成功掩盖超限。

## 4. 对 NEEDS_SOL_REVIEW 遗留决策的结论

1. **权限域**：本次官方 V2 规范明确存在带 projectID 的已保存权限，以及 GET/DELETE saved 规则接口。应统一 REST/SSE 归一化，显示项目与实际规则，并提供撤销入口或明确可达的服务端管理路径。V1 范围未经验证时隐藏长期授权，仅保留 once/reject；不应把范围未知压缩成一个“始终允许”文案。
2. **多服务器监控**：无需为本轮结项立即做大架构重写。可明确交付“仅当前服务器本地监控”，切换时显式结束旧跟踪、清理旧运行通知并说明原服务器任务继续运行；跨服务器后台通知只有已验证 companion 可承担。若继续承诺同时监控，则必须实现每服务器独立连接和生命周期。当前静默保留 tracked 两者都未实现。
3. **Controller 拆分**：可以延期拆文件，不能延期 R02/R06 的边界修复。先引入完整不可变命令目标、每资源可信快照及可测试的连接状态，再决定类的拆分。新增竞态探针可成为整改回归测试的起点。
4. **SSE 游标**：不依赖未知 replay 语义、通过溢出失败和周期对账恢复是合理方向；但要保持活动任务历史、暴露对账失败，并使权限/终态最终收敛。目标实例 replay/乱序/正常关闭测试是验收门槛，不需要以臆测的 Last-Event-ID 保证替代。
5. **V2 契约**：按明确修订适配，不采用宽泛失败重试写操作。目标实例未提供不妨碍修复已确认的已发布规范差异；也不能声称所有 V2 都兼容。
6. **设备与发布验证**：真实 UI、进程回收、FCM、OEM 和保数据覆盖安装仍需执行。依赖 checksum/Actions 固定 SHA 是工程加固，不证明功能验收通过。文档“依赖验证元数据尚未启用”与仓库已有 3661 行 verification-metadata.xml 相矛盾，应更新。

## 5. AUDIT.md 逐项关闭判断

| 原项 | 当前判断 |
|---|---|
| A01 | 自动重定向边界改善；保存后旧 Cookie 仍跨 origin，不能关闭 |
| A02 | 服务器世代守卫有效但不完整；项目/会话/排队发送复现，不能关闭 |
| A03 | 生命周期、超时、目录与失败保留改善；目标 TOCTOU 和 Push ID 撤销仍缺 |
| A04 | 溢出现在显式失败，新增压力测试确认；对账的完成语义仍缺 |
| A05 | 失败与空集部分区分；部分项目 sessions 失败仍丢状态，不能关闭 |
| A06 | outbox/重试框架存在；持久化、恢复、顺序、长任务均有反例，不能关闭 |
| A07 | v2 签名和加密存储改善；无条件 legacy/空 key 和重放仍缺 |
| A08 | 注册落盘失败正确返回错误；注销认证/队列/持久化仍缺 |
| A09 | 开关和 FGS 通知结束改善；单连接监控策略未落地 |
| A10 | 离线重试和 reload 建流改善；缓存来源/连接与任务阶段仍混用 |
| A11 | 文件引用已接入、标签切换草稿改善；页面生命周期和发送版本仍缺 |
| A12 | 新建回调导航改善；Fork 返回值被丢弃，不能关闭 |
| A13 | 测试命令和 V2 name 部分对齐；未覆盖状态序列的一致性 |
| A14 | 序号和条目上限不是原子删除；旧写仍可复活 |
| A15 | 即时取消、读取限额、部分后台解析有效；截断/分页语义仍缺 |
| A16 | 文案和摘要改善；V2 REST 规则未归一化，长期授权与撤销未闭环 |
| A17 | 正式版拒绝不安全产物有效；默认预发布回归、签名输入链仍缺 |
| A18 | attachments/outputPaths 等无出口链已精确清理，保留 V1 patch；权限来源链随 A16 未完成 |

不能把这些部分修复全部称作绕过：重定向阻断、socket 取消和 SSE 溢出检测都有真实效果。但测试请求与保存凭据脱节、缓存失败后无条件清旧标记、Fork 直接丢结果，确实没有解决相应根因。

## 6. 验证和交付状态

以下结果最终以本目录日志及 JUnit XML 为准：

- 规定的 `ANDROID_HOME=/home/lvziw/Android/Sdk GRADLE_USER_HOME=/home/lvziw/.gradle ./gradlew :app:testDebugUnitTest :app:assembleDebug`：原有 **23 项**通过，APK 构建成功。NEEDS_SOL_REVIEW 中的 21 项是旧计数。
- `cd companion && node --test`：原有 **8/8** 通过。
- 隔离新增 JVM 探针：**11 项**，其中 10 项以成功复现缺陷为通过，1 项确认 SSE 溢出修复有效；与原有测试合计 **34 项**。
- `node --test review-companion.test.mjs`：**5/5 成功复现未修缺陷**。
- `:app:lintDebug`：BUILD SUCCESSFUL，XML 为 **0 Error、30 Warning**；不以 lint 绿色代替竞态和产品验收。
- APK 使用本机 aapt2 检查，确认默认 Debug 产物包含 application-debuggable，与新增无条件发布门禁冲突。
- 当前官方 V2 OpenAPI 快照 SHA-256：`8ab6ec800922fc68890c68134320b4063fb317e0d944252d232083e12d32f4e9`。未把当前公开规范冒充用户目标实例契约。

复现材料：[隔离目录](/tmp/opencode-final-review-20260929-PvSGZg)、[JVM Controller 探针](/tmp/opencode-final-review-20260929-PvSGZg/app/src/test/java/com/igng/opencode/mobile/core/FinalReviewProbeTest.kt)、[API/签名/SSE 探针](/tmp/opencode-final-review-20260929-PvSGZg/app/src/test/java/com/igng/opencode/mobile/core/FinalReviewApiProbeTest.kt)、[Node 探针](/tmp/opencode-final-review-20260929-PvSGZg/review-companion.test.mjs)。原验证日志为 gradle-validation.log；新增测试日志为 review-final-gradle.log、review-probes-gradle.log、review-companion.log；JUnit/Lint 产物在 `/tmp/opencode-mobile-gradle/5e888e33/app`。

本轮交付是终审报告和隔离复现证据，**没有修改生产源码、提交、推送、创建 PR、合并、发布或部署**。现有整改分支/worktree 原样保留；未新建或清理 worktree。报告另存主目录未跟踪文件，AUDIT.md 未改。临时证据目录未清理，但 `/tmp` 不保证长期保存。

尚未执行：真实 OpenCode 实例、Compose/Android 真机、低内存回收、真实 FCM、OEM 展示、相邻 APK 保数据升级。没有这些证据不能称设备验收完成；更关键的是，本轮已经有代码级和隔离运行级的确定反例，因此即使暂不要求设备验收，也不能结束整改。
