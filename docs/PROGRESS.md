# 统一连接机制重构任务跟踪

## 实际交付状态（2026-11-11）

目标仍在进行；没有把组件单测通过等同于七阶段完成。没有部署、发布或合并。

### 阶段 1：官方契约与准确性，部分完成
- 固定 OpenCode v2.2.6、DSH 0.2.1-rc.5 / 0.2.2-rc.1 fixtures 与提取脚本，真实 Noise/Relay 测试使用官方 schema 正反例。
- 已接入：DSH model/projection、执行结果 epoch/seq、archive authoritative set、workspace attach partial success、command attachments、typed errors、响应预算；Remote identity scope、分页、不完整目录、损坏存储保护、SSH URL/auth 错误。
- PTC 已接入 DshApi 实时 translate、snapshot/page 历史投影和 TranscriptProjection；rootCallId/parentCallId/subCallId 锚定真实 assistant message，跨页保留祖先，按 durable seq/工具插入顺序归约，普通 native 工具不依赖 PTC 缓存容量。
- 提问已接入 DshQuestionCoordinator、DshApi、Controller 前后台/可见会话与 Compose 卡片：前台认领/首帧 remainingMs、焦点暂停、编辑保持、慢慢回答、ASK_TIMED_OUT、后台 next handoff、continued 投影/inbox 恢复与排队隐藏。提交失败只读对账，不自动改路或重放。
- PTC adapter tests 12；Question coordinator 17；Question actual DshApi gateway integration 9。重要 race 覆盖包括同 client queued-reply discard 再答、旧提交与新 event redelivery 并发、cancel 先于 durable continued、directory cold recovery、collector-cancel-first graceful next drain。
- 当前 integration targeted tests 全通过；最新全量见 `runtime-r5-checkpoint-2.log`。尚无 Android 真机 UI 交互证据。

### 阶段 2：连接图与安全迁移，部分完成
- ConnectionSource → HostBinding → HarnessBinding、严格 codec、credential slot refs、稳定 legacy leaf ID、effectiveEnabled 与 capability/generation/delivery 模型。
- ConnectionGraphStore journal/backup/future/corrupt protection；Controller 已加载/保存 companion graph。迁移使用严格 ServerStore reader，未知协议/损坏资料禁止覆写。
- SSH native OpenCode locator 作为 sshServicePath 可持久化，不保存临时 forwarder URL、PID 或进程 generation。
- source/host/leaf 开关已实际接入：父级 gate 独立持久化，ServerStore.profiles 仅做 runtime overlay，raw reader/writers 不改子项 enabled；新 sibling 即使尚未写入图也继承已停用父级。
- Overview/Controller 的连接、重连、通知动作、深链、首页/组合/全局摘要均使用 effectiveEnabled；详情页已有三层开关。旧 UI/通知对象不能绕过刚提交的父级 gate；写入主记录成功但 journal cleanup 失败也按磁盘许可收紧运行状态。
- 损坏/未来版图为 runtime fail-closed，不清空 raw 资料/凭据；graph reader 不自动恢复或迁移。组合全暂停后保留选中的 group ID，不跳转组外连接。
- r7：ConnectionRouteSnapshot 从 exact binding→Host→source 链生成不含 secret 的不可变执行路由；对照 raw 全量路由元组（完整 Direct base path/认证、SSH config/path/user、Hub account/Host/fingerprint/service），不一致/图缺 binding/损坏/未来版均拒绝，不按新 raw 或旧 graph 静默回退。
- Overview 的建连及缓存复用、通知租约、Controller 操作/完成提交已接入 runtimeRoute；实际工厂按已准入 carrier→native protocol 分派。raw enabled 和显示偏好不被旧 graph leaf 偏好覆盖；旧同-ID客户端在改路/改认证时回收。Remote account 固定来自准入源，不让 credential username 改变信任 scope。
- r8：ConnectionCatalogProjection 合并独立 SSH 配置和 Hub 账号/主机目录，无 Harness 时也保留 source/Host；不创建假 leaf。删除最后一个 SSH Harness 不再删除主机配置/共享凭据，只有显式删除接入源才移除。
- Hub 登记只保存确认的主机，不默认创建 DSH 或自动连接；DSH/OpenCode 在同一主机服务页独立选择。无子 Harness 的只读发现仍读取 committed graph 父级许可，错误/缺图不自动修复。
- 退出登录保留 signedOut 目录及原父级/子项偏好，撤销凭据前先持久化本地状态并暂停源；重新登录不偷偷开启父级。signedOut 的发现与后台新连接明确拒绝。
- **尚未完成**：完整跨 store 故障注入矩阵、完整原生能力交互矩阵和真机 UI 验收。

### 阶段 3：实际原生传输与 mux，已有实质交付
- OpenCodeApi 的 HTTP/二进制/SSE 已经通过 NativeHttpTransport；public constructors 兼容，close 释放本实例 transport，不关闭共享 dispatcher/pool。
- NativeOkHttpTransport 已实现 HTTP/SSE/**WebSocket text/binary**，generation 取消、终态 close、在途 body-read 取消、3xx 拒绝、固定 origin、响应字节预算、RFC token headers、明确 encoded session ID 路径策略。
- Concrete NativeOkHttpTransportTest 16 项；旧的 WS unsupported 测试已替换，不再声称 stock carrier 缺少 WS。
- DSH direct/SSH PhysicalMuxOwner 一个实例一个 physical socket；保留 idle socket 至 owner close，避免关闭 API-peer lifecycle 后复用失效 cookie；cookie acquisition/socket callback/send 都有 attempt fence。
- 11 项 mux 回归覆盖 typed errors、cancel-all/reopen、end/reopen、onClosed fan-out、旧代次/旧 401 回调、cookie exchange 期间 close、坏载荷/慢消费者/回调异常隔离。
- r7 修复应用 saved direct DSH 诊断另建 physical mux：相同路由/认证借用 Overview owner，未改内容/问答展示，不关闭 borrowed client；编辑 draft 不用旧资料代测，保存后先退役旧 owner 再连接。注意 durable browser cookie 允许多物理 socket，并非必然 409；409 限制是相同 v2 API-peer cookie。
- Cookie 回调按 owner token+路由+username/password 输入隔离；同URL替换认证后迟到的旧回调不能覆盖新凭据，owner 自身 cookie 轮换仍复用。
- **尚未完成**：公共库独立 DshApi 实例的全局复用（冷 events 多 collector 仍有 ownership 风险，不以裸 refcount 共享掩盖）；真实官方 API-peer fallback 互操作。RemoteBackend 共用原加密会话。

### 阶段 4：SSH 多 Harness 已接入，仍非全部完成
- SshTransportPool 已被 SshHostManager 实际使用：同一 saved source/config/pin/credential revision 复用物理 SSH；每个 binding 有独立 runtime link/forwarder/client/refcount，最后一个引用释放才关连接。
- OpenCode SSH 实际路径：只读 registration → 受信 loopback forwarder → /api/info PID/version 校验 → protected project auth → OpenAPI discovery → 同一个原生 OpenCodeApi。
- 只读 SshHarnessProbe 使用一个有界 exec；默认 XDG/HOME official registration，16 KiB 读取预算；无 Python、文件缺失/损坏/不可读/部分结果明确区分。DSH legacy 标记只作为标记，不冒充桌面 profile runtime。
- OpenCode password 仅 runtime 内存，redacted toString，无 graph codec；服务 generation id 不作为 durable binding 身份。
- SSH 表单已有 DSH/OpenCode 独立选择、只读发现、OpenCode registration path。可全部停用；禁用/删除单 Harness 不关闭兄弟、不删除共享 SSH 凭据；显式删除主机才删全部 leaves。
- 保存 host route 同步更新 siblings，但不改兄弟 enabled/notifications/drafts/cache。未变 source 只失效目标 binding；source 配置变更才重建全部运行通道。仅保存会把新 leaves 停用，避免偷偷 dial。
- OpenCode attach-only，从不 install/start/kill；DSH 保留明确选择的旧 managed web bootstrap，普通 close 不杀远端进程。
- Tests：discovery 13、pool/session ownership 4、真实 socket-backed tunnel adapter 2；另有 graph/store/factory 路由测试。
- **DSH Desktop 原生附着为上游 blocker**：本机安装的官方 `@deepseek-ai/dsh@0.2.1-rc.5` CLI 明确拒绝 `--profile desktop`；Electron Desktop 使用 `file://` + IPC，而 Typert web/WS mux 仅由 WebHost 挂载；`/connect` launch token 只存在该 Web process 内存，官方包没有 Desktop profile 注册文件、daemon HTTP 入口或可供 SSH 转发的 IPC bridge。不得通过启动第二个 `dsh web`、猜端口或读写共享 profile state 冒充附着当前 Desktop Agent。正确下一步是先取得官方 Electron Desktop 提供的受支持 Host/SSH attach hook 与身份/lifecycle contract；未取得前，DSH SSH 仅是兼容的新建 WebHost，不宣称 Desktop 运行时复用。
- 多 OpenCode channel 目前通过用户保存的具体 registration path 逐个建 binding，不自动合并 channel。r8 已在隔离 loopback 真 sshd + 官方 OpenCode v2.2.6 验证两 bindings 共享物理 JSch、原生读取/SSE、关闭一个不影响另一个；不是公网 SSH 或 Android 真机验收。
- 真 SSH 验收暴露并修复 JSch 0.2.25 DirectTCPIP.connect 在已设 input 时异步返回：必须等待 open confirmation 后才向 forwarder 暴露 isOpen，否则 opening 被当 closed，截断 HTTP。保留 opt-in 实际 SSH 回归。

### 阶段 5：Remote native connector 已实现，实际加密互操作已通过定向验证
- 新增 `host-connector/`：固定上游 SHA-256 校验 overlay，将 NativeServicePeer 放在认证 Noise/信任校验之后的每连接 RpcRouter，完整构建 Router、HostPluginRuntime、插件入口；不是 manifest/stub，也不需要新建 Hub。
- Host 只读官方 OpenCode registration；先匿名 `/api/info`，但真实 v2.2.6 发布二进制即使 OpenAPI 声明 security=[] 也受外层认证保护。每个携凭据 HTTP/WS socket 均先进行有界匿名激活，再校验 Linux `/proc/<pid>/fd` inode + 已建立连接四元组，确认登记 PID 持有对端后在同一 socket 发送认证，公开 200 也不绕过归属证明。
- 连接/匿名激活/归属验证总计 5s 墙钟上限；不重放业务 mutation。不支持/无权限取得内核归属证据时 `IDENTITY_UNVERIFIED` fail-closed（当前 macOS/Windows 原生 Host 不可用，不能假称支持）。凭据仅 Host 内存；只允许 loopback、OpenAPI method/path、header 白名单，禁止重定向、遍历和手机任意 URL/凭据。
- 原生 HTTP/binary/SSE/WS，序号/背压/取消、父连接配额、64 MiB 有界 request/response transfer、上传 commit 一次性消费；未知提交不重发。关闭 pending-open 不复活流；发现替换/入站帧校验回收旧服务代次。
- Android RemoteNativeServices + LazyNativeHttpTransport 已接入 RemoteSession/RemoteBackend/OpenCodeApi；同 Hub/account/Host 一个 Noise session，不以 ACP 或 DSH Typert 冒充原生 OpenCode。
- Remote service locator 单独保存为 remoteServiceId，generation 不持久化为身份。新 Host 服务选择页只读发现、默认可停用保存、显式逐个启用；旧 Host 明确升级要求。
- 验证：Host real loopback 11 tests、patched actual Router/ConnectionController 5 tests；Android carrier 15、lazy admission 7、native binding 7、实际 Kotlin Noise→Node Router/Peer→原生服务 fixture 2（其中 1 互操作、1 旧Host）。大请求实际 Noise 互操作及全量 1173 项回归已通过，具体证据见下方日志记录。

### 阶段 6：统一 UI 与自由组合，源码已接入，验收进行中
- 添加入口改为 Remote Hub/SSH 接入源优先，兼容 HTTP 直连放高级区；管理页显示 source→Host→Harness 和独立开关。
- 组合改为稳定 leaf ID 的多对多引用：一个 Harness 可加入多个组合，新建/编辑不再从其他组合偷走成员；显式 MOVE 仍是移动。
- 详情展示所有组合成员关系，不再用一个下拉框破坏其他组合；原生服务保留准确 carrier 标签和独立操作。
- r8 后续：DSH 插件/凭据/服务商管理按原生协议开放给 Direct/SSH/Remote，Hub 管理按 remoteHostId 单独显示；插件包与 Loader entry 均可读取/修改 Host 允许的开关，呈现只读原因/phase/可能重启，不把未确认 false 当成功。
- 设置弹窗按完整 profile 失效；Controller settingsClient 在读写前双重校验打开时的 exact saved route，不按 ID 悄悄转向编辑后的 Host。凭据引用先读 writable 状态，值不回显。
- OpenCode 从实际文档发现兼容 POST /fork 与 messageID /revert/stage，提供具体已落盘人类消息回退确认与暂存边界恢复入口；DSH 不冒充回退。离线缓存、任务活跃、降级未知状态、排队消息、父级禁用均拒绝；确认绑定原路由/会话/边界，回退期间不发送消息且保留草稿。
- r10：目标编辑已按官方 `goals/edit` 的 agentId/ref/request 接入 Direct/SSH/Remote，保存只修改 objective/cap，不改变阶段或自动续跑状态。Long cap 覆盖正 JavaScript-safe integer，缺少轮数信息显示未知；仅明确 stopped 状态且预算充足时提供独立恢复操作。
- 编辑与清除确认捕获 exact route/session/goal revision；旧版本仅重读状态，不自动重发编辑，失败保留输入；完整 GoalView 响应直接提交并按版本挡住旧投影。create ref-only ack 不推断 armed，保留确认前收到的同目标激活事件；同 revision 激活变化、迟到空读取与清除 tombstone 均有缓存/Controller 防回滚门禁。
- r11：官方调度 catalog/list/history/delete/update 已接入三种 DSH carrier。完整六类 ScheduleRecord 作为封闭 CAS 快照，原会话、状态、lastDelivery 单独保存，不混入 expected；时区/计划目标原样保留，修改定时须明确勾选，after 仅明确改为 at。
- UI 有活跃/非活跃目录、投递回执分页（不冒充 Agent 运行完成）、保留/修剪提示、删除确认、分阶段编辑与冲突后显式重新读取；已改字段保留，未改字段随服务器更新，避免回写他端编辑。写操作前后按原路由/会话/完整记录隔离；未知结果只读核对，不自动重发，HTTP401 也不重放调度 mutation。
- rc.5 generated 仅公开上述5种调度方法，没有公开 create/run/enable，不能将内部服务方法或 Agent tool 冒充客户端 API。
- r12：OpenCode simple PTY 的原生业务模型/目录/明确创建/单次票据附着/输入/尺寸与标题更新/明确终止接入 Direct/SSH/Remote native service；能力仅从兼容路由和票据 header 声明发现，baseline 不假定支持。纯文本 ANSI 投影、OSC/clipboard 控制序列抑制，不宣称完整 VT/TUI。
- 终端以 location 归属，不冒充 Session 资源。UI 默认只读，明确附加/输入确认；离开仅 detach，只有确认终止才 DELETE。原路由/会话/目录捕获并按输入逐次许可；原始 UTF-8 无 ACK，游标按 UTF-16，输入不自动重放/重连。Host 仅在原生 POST /api/pty/{id}/connect-token 放行 x-opencode-ticket:1。
- r13：DSH Direct/SSH Session 归属终端已接入，不能从目录推断 Session；与 OpenCode location scope 明确区分。同目录会话不会串用终端，PID 未提供则显示未知，实际 cwd 单独展示。
- retain 确认后才 attach，首帧 snapshot/后续 output/ACK 按官方序号验证；write/resize 共用序号和 UTF-8/编码帧预算，ACK 仅代表 provider 接收，不冒充执行完成。附加明确提示立即夺取输入控制，旧附着只读，新附着退出不自动恢复旧控制；断线/未确认输入不重放，关闭页面只 cancel 附着与 hold。
- UI 还保留本机输入确认，拒绝 ACK 即使序号前进也恢复草稿并提示核对；显式分离同步记录未确认状态，避免收集器调度丢失提示。Direct/SSH 官方终端实测已通过；未跑 Android 真机布局、可访问性、预测式返回。
- r14：Remote 复用固定上游 `harness.terminal.v1` 的 Host 开关/设备归属/attachmentId 策略；native attach 转为 `terminal/follow`，write/resize 走上游兼容 RPC，只有 provider ACK 使 RPC 成功后才生成客户端 ACK。仅从 controllerId 判断输入权，不能信任自报 writable 或按 harness.remote.v3 推断。
- 终端固定到当前加密 gateway，旧代次不能把输入或 mutation 转到新连接；有界32项/256KiB单发送队列、10s超时、取消丢弃待发输入、未知结果不确认/重放。闭合 Gateway 拒绝新调用，普通只读流仍无上行。Host 设备策略继续过滤其他终端、拒绝其他设备和关闭开关后的调用，不增加绕过通道。

### 阶段 7：验证与交付仍进行中
- 本地 fake Hub/实际 Noise/真实 Node connector 互操作不等于公网 Hub、真实官方 daemon 或 Android 真机验收。
- r15 单实例 DshApi 事件改为一个 business owner 和独立有界订阅队列：旁观者取消不停止兄弟流或交还前台提问，最后订阅者才清理，旧代次退出/交还后再启动新源；慢消费者明确报错而不静默丢事件，JSONObject 载荷逐订阅复制。
- r16 显式 `DshClientRegistry.acquire` / factory `dshRegistry` opt-in 入口提供跨调用者共享：完整96项接口转发均由 lease 门禁校验，关闭仅取消本 lease 操作/Flow/终端附着，最后一个释放才关闭 owner 并等提问交还；继任 owner 的业务调用先等原 owner 退出，不重放未知 mutation。原始构造器与默认 acquisition 保持独立，不做隐式全局池。
- 共享键按确切绑定/路由/鉴权/timeout/凭据摘要区分，映射 key 不存明文；Cookie 回调逐 lease fencing。监视兴趣合并、后台 false 不关闭前台，消息读取通过调用者上下文引用计数、不隐式设置全局焦点；仅当前显式前台 token 可操作提问。终端 StateFlow/发送/尺寸/关闭同样有门禁，附着返回边界取消会回收句柄而不杀远端进程。
- DSH Desktop SSH 附着仍缺上游受支持 hook；完整功能矩阵未全部验收。显式共享入口不会将裸构造的任意 DshApi 自动合并，也不能绕过保存的 route admission。
- 已推送分支并建立 [Draft PR #193](https://github.com/IGNGserver/Lagoon/pull/193)，官方服务验收 checkpoint 的 CI 已通过；PR 保持 draft，继续收口剩余能力，不表示全部目标完成。没有部署、发布或合并。

## 验证记录
- `ssh-integration-r4.log`：先修复之前中断留下的 SSH owner 编译错误，ownership/model/legacy store/mux 定向通过。
- `ssh-native-r4.log`：同步到了正在编辑的 mux 中间快照，编译失败；不当成环境问题，等待 READY 后重新同步。
- `ssh-native-r4-2.log`：新 tunnel test 缺少 RemoteExecResult import，已修复。
- `ssh-native-r4-3.log`：稳定快照定向测试全部通过（16s）。
- `full-ssh-r4.log`：1065 tests、0 failures、0 errors、6 skipped，112s。
- `ssh-snapshot-r4-final.log`：同批全量 tests + debug assemble 通过。
- `ssh-r4-verified.log`：1067 tests、0 failures、0 errors、6 skipped；`:app:assembleDebug` 成功。
- 独立审查发现 SSE EOF 代次等待、半开池连接复用、注册路径选择回退、无选择测试启动 DSH 四个问题，均已修复并只读复核。增加原连接两次 SSE EOF、新代次超时、半开连接替换测试。
- `checkpoint-r4.log`：当前 checkpoint 源码 **1070 tests、0 failures、0 errors、6 skipped**；`:app:assembleDebug` 成功，111s。APK 21,556,934 bytes，SHA-256 `4db87ea62e61b121f01c2c7ac9330d9b1cd5e593c688f0074d0403c640bb84c6`。
- 基础批次 93 files 已保存本地提交 `e1ceb83`；`git diff --check` 通过；常见 token/private-key pattern 扫描无命中（不等同于完整安全审计）。未推送/部署/发版；最终 PR 仍待目标完成。
- Runtime r5：先后修复新增测试的 Kotlin import/JSON 括号/inner fixture、PTC native 缓存 admission、缓存祖先回放、混合工具排序与 lifetime saturation；保留原失败日志，不把其解释为环境失败。
- 问答审查修复：queued reply 撤回后同客户端可重答、Host cancel 先于 durable pending 的读取竞态、cold idle-session directory 恢复、新投递在旧 submit 期间的认领、关闭时 next RPC 排空、SSH 排空期间新 RPC tunnel、投影读期间 dirty trigger 补读。
- `runtime-r5-review-2.log`：161 项定向回归全通过。`runtime-r5-final-targeted.log`：PTC/Question+SSH 目标通过；之后加 collector teardown/dirty-read fixtures。
- `runtime-r5-lifecycle.log`：旧 question test 使用 take(1) 关闭事件监听后继续提交，按新 ownership 语义被拒；已改为在提交期间保留 collector，增加 collector-first teardown 专项测试。
- `runtime-r5-checkpoint-2.log`：**1108 tests、0 failures、0 errors、6 skipped**；debug assemble 成功，112s；已保存本地提交 `03f4de1`。
- Hierarchy r5：新增纯 runtime projection、严格主图只读 overlay、Overview/home/通知 effective gate，实际 Controller source/host setter 的前置失败/提交后清理失败/旧页面许可/重开偏好测试。Controller 旧 Unsafe fixture 必须有真实保存 profile，已补齐而不绕开生产许可。
- `hierarchy-r5-final.log`：106 定向测试通过；`hierarchy-r5-checkpoint.log`：**1134 tests、0 failures、0 errors、6 skipped**；debug assemble 成功，116s，APK 21,612,457 bytes，SHA-256 `15a9019a8d971f11c4d64a1fcb0633ba10aeddcb8ddb974807abad448866c848`。
- Remote native r6：先后修复 Host public info 凭据探测顺序、pending stream 取消前置预留、旧代次入站流回收、上传 commit 一次性消费/finish-close 内存计费；11 项真实 Node peer 测试通过。完整 fixed-upstream Router/Host runtime/plugin 构建成功，actual Router/ConnectionController 5 项通过。
- `native-android-integration.log`：新测试旧假设失败（协程异常栈恢复会复制异常；异步取消完成需等待；MockWebServer server-side WS 不能使用 client cancel）。保留日志，修正夹具后 `native-android-integration-2.log` 定向通过。
- `integration-checkpoint-1.log`：1165 tests 中 1 旧 profile scope fixture 缺少 remoteServiceId；`integration-checkpoint-2.log` 同步到组合测试中间快照缺 helper/import，不能作为通过。fixture 已按完整 native locator 更新、组合测试已稳定。
- `integration-checkpoint-3.log`：1173 tests 中 1 大上传互操作失败；定位 FakeRemoteHost 收到客户端 DSHF 片段却直接当 JSON，加入真实有界解片器（control/data relay 均适用），不缩减上传量。
- `integration-upload-repaired.log`：实际 Noise + native upload/stream/binding/组合/旧Remote 定向通过。`integration-checkpoint-final.log`：**1173 tests、0 failures、0 errors、6 skipped**（包含实际 Noise cross-language tests 2/2，0 skipped）；`:app:assembleDebug` 成功，112s。APK 21,689,086 bytes，SHA-256 `f932587dc377ad94355aef173de88d364cb8a454830f4939fa2a1b67aa853c3c`。
- `connector-full-build-4.log`：完整插件 bundle 4,023,665 bytes，SHA-256 `fb0e0c9a9746e6c00fcbe4fed92bb59cb91b2129a1ba4917774ce58a2e94afef`；Host upstream archive SHA-256 与4个关键源文件 SHA 校验，工具依赖已锁定。没有安装或部署到用户 Host。
- 最终独立 review 发现 metadata route 的 SSE/WS 仍可能带 Basic；已在流入口拒绝 `/api/info`/`openapi.json`、低层公开 info 统一不带认证，负例确认无任何网络请求。`native-host-security-final.log` 11/11、`connector-router-security-final.log` 5/5，`native-security-encrypted-final.log` 最新 Host artifact + Android 真 Noise 回归通过。
- `connector-full-build-5.log`：最终安全修复插件 bundle 4,023,814 bytes，SHA-256 `1470c8ce20237815a7c5f9c9ec6d35bda47ce4b1442853153f83fb5b610ce0dd`。构建复制上游 MIT LICENSE 到源码副本和输出目录；没有重许可或删除作者声明。
- r7 `route-admission-targeted-2.log`：strict route/diagnostic/父级许可等定向通过；初次 full 暴露 CancellationSafety 要求新 catch 必须重抛取消，以及 Overview 旧测试只换内存 profile、不保存路由的假设，均已修正而非放宽生产准入。
- r7 独立复核修复 DSH Remote legacy kind alias 的原生协议归一化；addProject/deleteSession 的迟到结果在持久化副作用前校验 route，避免同 leaf ID 换地址后污染项目、草稿/缓存；问答通知取消也按原连接守卫。
- `route-admission-final.log`：**1203 tests、0 failures、0 errors、6 skipped**，其中 actual Noise 跨语言 2/2，0 skipped；debug assemble 成功，117s。APK 21,697,632 bytes，SHA-256 `1c5c5f445bc52cc04ecd123d5a81848872729d761d0b83f32e98f2ade4119407`。
- 新增 route admission 11、carrier-first factory 7、saved diagnostic ownership 6、Cookie/auth owner fence 4、迟到持久化副作用 2 项回归。凭据模式扫描无命中（不是完整安全审计）。
- 早期环境检查仅发现 PATH 中 OpenCode 1.18.18、未发现 sshd，未拿它冒充 v2.2.6。r8 后续从官方 GitHub release 下载固定 v2.2.6 并校验 SHA-256；明确绝对路径找到 `/usr/sbin/sshd`，在隔离 HOME/随机 loopback 端口启动独立测试实例，不替换全局工具或用户服务。Android 真机/公网 Hub 仍未验收。
- r8 `source-lifecycle-first.log`、`source-lifecycle-independent.log`：空 source catalog、SSH 删除边界及父级许可定向通过；`source-lifecycle-signedout.log` 首轮一项失败是夹具 Long.MAX_VALUE 超出协议安全时间戳，修正为合法未来值，不放宽生产校验。
- `source-lifecycle-full.log`：**1225 tests、0 failures、0 errors、6 skipped**，actual Noise 跨语言 2/2 运行通过；debug assemble 成功，123s。APK 21,713,748 bytes，SHA-256 `931ecbd9ea5261733f156164176bdbc03bb8f94adeeda5c9d5d4975e11cd77d8`。新测 CatalogProjection 8、真实 SSH Controller 删除 3、parent-only admission 6、signout/relogin 4、catalog signedOut codec 1。
- r8 真实官方验收：GitHub OpenCode v2.2.6 Linux x64 baseline 压缩包 121,793,402 bytes，官方 SHA256SUMS 校验为 `08bb21d6326ce740d91cce7ebe88a6536933f6e1e30353b91a06b01f25da306c`；全新 HOME/XDG/随机 loopback 端口，未替换系统工具，未发模型 prompt。
- 官方 /api/info 的外层认证与 OpenAPI security=[] 有差异；初版探测误判，已用同 socket Linux PID/inode/tuple 归属证明修复。审查再修复伪造公开200可绕过认证探测、slow-drip 占用验证槽：所有凭据 socket 统一校验，整体5s截止。`official-native-host-final.log` 16/16（15 peer regression +1真实daemon）；`official-upstream-router.log` actual Router/ConnectionController 5/5。
- `official-daemon-acceptance.log` 官方 OpenCode 与隔离官方 DSH rc.5 直连只读通过；`official-ssh-noise-acceptance.log` SSH 首轮失败，发现 JSch 异步通道误关闭，不当作环境问题。修复后 `official-ssh-confirmed-acceptance.log` 所有真实 SSH/DSH/Direct/Noise +相关回归通过；RemoteNativeConversationTest 3/3 包含实际 Router→官方daemon，未跳过。
- `official-connector-full-build.log` 完整固定上游插件 bundle 4,029,731 bytes，SHA-256 `4b8a2d59ebfc75163a06b7b32f058fed649aa346b6ccd67b55bb4ef62ba6ee71`。未安装到用户 Host 或部署。
- `official-acceptance-full.log`：**1229 tests、0 failures、0 errors、6 skipped**；三个 opt-in 官方 Direct/DSH/SSH 测试及实际 Noise3项均运行无跳过；Debug assemble 成功113s。APK 21,713,748 bytes，SHA-256 `e1f52694152156d36e0762b602430938e859fa7b810b92c9ab34341e8b6cac78`。测试后停止仅本任务拥有的三个临时 daemon。
- 源码复核发现 DSH 设置 UI carrier 条件、OpenCode revert/restore 缺入口和 fork 能力遗漏；后续已接入并进行回归，不因 read/event 验收通过就声称完整功能完成。
- `native-action-ui-first.log`：初版时间线/能力/Controller 定向通过；`native-action-ui-scoped.log` 两项失败分别是旧 baseline fork=false 断言和 events 首帧假设，已按当前原生契约修正并显式 close owner。`native-action-ui-guarded.log` 是 Flow filter import 尚未同步的编译失败，补齐后 `native-action-ui-guarded-2.log` 定向通过55s。独立复核补上 degraded 任务状态未知时禁止时间线 mutation 的门禁。
- `native-action-ui-final.log`：**1252 tests、0 failures、0 errors、10 skipped**，Debug assemble 成功113s；本次真实 daemon 已停，因此四个官方 opt-in 用例如实 skip，fixture Noise2项仍实际运行通过。新增能力发现7、timeline policy6、六carrier设置入口3、Controller6、native revert route1项。APK 21,735,293 bytes，SHA-256 `20b00047e0c1c7d6db4d371bb20ac2726cb845f32ea04fdf1804ba60d91aa7b0`。不拿本次 skip 冒充重复真实验收；真实服务证据保留在前述独立日志。
- r10 `goal-edit-first.log` 编译/目标/API/Controller/UI 定向通过84s；`goal-edit-reviewed.log` 增加 read epoch、同 revision 激活与 create ack 的时序修复后通过55s；`goal-edit-encrypted.log` 实际 Noise 目标编辑、Controller 清除后迟到投影、原路由创建许可回归通过18s。没有发送目标编辑之外的 resume，也没有自动重放 mutation。
- 独立复核发现并修复三处：Controller 旧 composer 投影复活已清除目标、create ack 清掉先到的同目标 activation、旧 direct get 覆盖同 revision 新 activation。额外保留 clear tombstone 防后续旧投影复活，数字 revision 不截断。`goal-edit-contract-tombstone.log` 官方导出 schema + tombstone/加密/Controller 通过20s。
- 从官方 rc.5 generated Typert 导出7项目标方法请求/响应契约，保留版本/源 SHA；create ref-only 与 edit full GoalView 分开测试，不按手写 fake 假定相同结果。
- `goal-edit-final.log`：**1289 tests、0 failures、0 errors、10 skipped**；Debug assemble 成功120s。新增 GoalEditApi15、GoalMutationPolicy6、GoalEditorPolicy6、Controller7、官方goal契约2、实际加密目标编辑1项；fixture Noise2项运行，已停止 daemon 的4个 opt-in 如实跳过。APK 21,766,757 bytes，SHA-256 `03557554bf9a45d95901e32f29de6bb7c4d7372989c02007d03516856b33e174`。
- 调度契约核对：官方 rc.5 generated RPC 仅 catalog/delete/history/list/update；没有公开 create/run/enable。r11 已实现支持的删除/完整记录 CAS 更新与历史，不通过调用 Agent tool 或猜 endpoint 冒充额外接口。
- r11 `schedule-first.log` 生产 UI/RPC 编译通过，但夹具错误使用 Android JSONObject 不存在的 similar，改为字段递归比较；`schedule-scoped.log` 定向通过62s。独立复核发现并修复 CAS 刷新把未编辑旧字段回写覆盖他端新值，以及旧会话写确认使新会话目录永久 loading，新增 dirty-field 和重叠会话读取回归。
- `schedule-reviewed.log` 官方 schema、Controller/dirty-field、实际 Noise 调度目录/历史/更新/删除等定向通过21s；写操作 401 不重放，冲突/未知只读核对。固定导出5种官方请求契约，结果 schema 无法表达的重叠 union 保留 resultSchemaError，不手造成功 schema。
- `schedule-full.log`：**1325 tests、0 failures、0 errors、10 skipped**；Debug assemble 成功122s。新增 ScheduleApi12、ScheduleMutationPolicy4、ScheduleEditorPolicy7、Controller10、官方调度契约2、实际加密调度1项。APK 21,842,052 bytes，SHA-256 `61cd28a5625e4686ba0b349cb13fc74219e9d080b79017e608b326e8bde62e4a`；本轮不启动真实 daemon，4个 live opt-in 保持 skip，fixture Noise 实际运行。
- `schedule-timing-rebase-final.log`：按 time/zone/weekdays 等字段分别保留实际编辑意图，避免仅改时间后把他端新时区覆盖；新增1回归。**1326 tests、0 failures、0 errors、10 skipped**，Debug assemble 成功117s。APK 21,842,299 bytes，SHA-256 `516bf2b2f16be324e36fc3c99e6badce07ec0cb7e496b69a4367a27a0e486e54`。
- r12 固定官方源码验证 simple PTY 原始 text / binary0+cursor / UTF-16 / detach 协议；`official-pty-protocol.log` 隔离官方 v2.2.6 上 /bin/cat 的创建、输入回显、metadata、detach 后仍运行、resize、204 删除均通过。
- `terminal-first.log` Android 原生直连 PTY 实测及初版 UI/门禁通过16s；`terminal-native-encrypted.log` actual Noise→patched Router→官方 PTY 与回归通过12s。独立复核发现终端 stateLock 与 carrier callback gate 反向嵌套可死锁，已将 send/cancel 移至状态锁外，新增可重复触发旧竞态的双线程回归；`terminal-reviewed.log` 包含真实 PTY 的定向通过11s。
- `pty-connector-full-build.log` 固定上游完整 Host plugin bundle 4,029,973 bytes，SHA-256 `701a39fa59835b3b9274cbd9476b2065c892b18b48ce2d2c1e646a285208d64a`；`pty-host-router-final.log` peer16 + actual Router5 共21项通过。x-opencode-ticket仅精确POST票据路由允许，其他方法/路径/值与流头拒绝。
- `terminal-full.log` 全量仅失败旧 ChatSheet 枚举逐字断言；改为仍核对 ACTIVITY 枚举成员并保留半屏高度/入口/返回语义断言。`terminal-final.log`：**1352 tests、0 failures、0 errors、7 skipped**，Debug assemble 成功119s；官方 Direct PTY、真实 SSH PTY、actual Noise PTY 均实际运行未跳过。新增 adapter12、文本投影6、位置/路由门禁2、Controller4、Remote header1、官方 PTY1项。APK 21,915,479 bytes，SHA-256 `e288128d2cd7d77089790928a6764fc25a93686a778a1fd51a804e8e8ec1c343`。
- PTY 测试仅在全新 HOME/XDG/固定 scratch 工作目录创建默认 shell 或 /bin/cat，发送固定 printf 探针，无模型调用或用户项目命令；目录 PTY 列表核对为空后停止本任务拥有的随机 loopback OpenCode/sshd，不终止用户现有服务。不是公网/Android 真机或完整 VT 验收。
- r13 固定导出官方 rc.5 terminal 的8方法 named args，generated 源 SHA-256 `3377602ada84e2de6ec192b200e7ba02ee5051fb05cf4a014b2504b63e1ace10`；adapter fake 的每个调用也用这些 schema 校验。Direct/SSH 的 item.value、retain/snapshot、UTF-8/编码预算、连续输出/ACK、拒绝/未知结果、关闭不杀进程有14个业务状态机和2个 mux 上行回归。
- `dsh-terminal-first.log` 首轮 scope/mux/schema 通过16s；`dsh-terminal-official.log` 官方 Direct 终端通过，但4项旧 Controller fake 因 Kotlin 委托没有重载新 scope 方法而失败/超时；修正 fake 而不放松门禁。`dsh-terminal-frozen.log` 冻结后的 adapter/schema/Controller/Remote gate/官方服务通过19s；`dsh-terminal-ssh.log` 真实 pinned JSch/sshd 上相同 ACK/控制权用例通过4s。
- 独立复核未发现新增明确高风险项；额外修复 ACK 与超时竞争（按过期序号在结束锁内重核对）、拒绝 ACK 序号前进却被 UI 当成功、分离 pending 时收集器交接与快速 ACK 先到 UI 不更新等边界。增加3个回执状态纯逻辑测试，不把接收确认冒充执行完成。
- `dsh-terminal-full.log`：1380项0失败/0错误/11跳过，Debug126s。`dsh-terminal-final.log`：**1380 tests、0 failures、0 errors、11 skipped**，Debug assemble 成功121s；官方 DSH Direct 与真实 pinned SSH 终端2项均实际运行0跳过，fixture Noise3项运行，先前停止的 OpenCode/DSH只读 opt-in 不冒充重跑。APK 21,945,880 bytes，SHA-256 `e8bd4f2ebb3f34a73c8a67b16a4f88d63a7bc092d91eb3a9407d3e267c31d78e`。
- DSH 终端验收用全新 scratch HOME/DSH_HOME、随机 loopback WebHost/sshd，新建测试会话与 shell，仅固定 printf 探针，无模型 prompt；每个终端显式关闭并读回空列表后停止本任务服务。没有操作用户 port3080/SSH配置/现有会话。未提供的 Remote 终端能力仍保留明确拒绝与0 terminal/*发送回归。
- r14 构建固定上游完整插件且仅扩展 verification-entry exports；运行时插件未改行为，4,029,973 bytes / SHA-256 `701a39fa59835b3b9274cbd9476b2065c892b18b48ce2d2c1e646a285208d64a`。验证入口实际包含 Router、HarnessRemoteBridge、TerminalPolicy、TerminalCarrierCompat；`remote-terminal-host-all.log` 原生16+Router5+终端6共27项通过。
- `remote-terminal-first.log` 首轮 Kotlin 转换与能力门禁通过15s。初轮 Node 1项因未等待异步 snapshot 失败、加密用例先遇旧暂存能力脚本再遇测试桥仅准 harness.services.* 而拒绝 harness.remote.frame，已分别修正事件等待、重同步与精确两个 Remote 事件名；未放宽生产策略。`remote-terminal-event-fixed.log` 实际 Noise→Router/Policy/Compat 的延迟 provider ACK、设备过滤、开关拒绝、detach不kill通过8s。独立复核0个新增明确高风险生产问题，指出上述测试桥事件白名单缺陷。
- `remote-terminal-official.log`：实际 Kotlin Noise→固定上游 Router/设备策略/Compat→隔离官方 rc.5 WebHost 的终端创建、输入/resize ACK、分离保留与明确终止通过3s。Node 测试适配器只负责本地官方 HTTP/mux，不启动服务、不重试或绕过设备策略，token 仅 scratch 私有文件读入；无模型 prompt/用户项目命令。
- `remote-terminal-final.log`：**1395 tests、0 failures、0 errors、13 skipped**，Debug assemble 成功119s。新增 RemoteSocket12、实际加密 provider2、capability1项，官方 encrypted DSH 1项实际执行；原先停止的 Direct/SSH/OpenCode opt-in 如实跳过。APK 21,961,103 bytes，SHA-256 `33800dc5c4ea7ecd4f604a3e6ac84390a8f47760de5adfe98ec42ff9d420b520`。所有子进程关闭、测试终端明确删除且清单为空后停止隔离 WebHost；不是公网 Host/Android 真机验收。
- r15 单实例事件 owner：`events()` 不再每个收集器独立创建业务流并覆盖 eventChannel；统一源保留提问交还生命周期，订阅者独立256项队列/JSONObject副本，取消旁观者不清理兄弟，慢消费者明确失败而非静默丢事件。最终退订的清理串行 join 后才允许新源，awaitClosed 等待事件源退出。
- `event-owner-first.log` 提问/mux基线通过7s。新增测试初次因 Source.flow 与 flow builder 同名递归推断编译失败，改为明确类型/全限定构造；另一次测试在普通 ready 回调调用 suspend currentCoroutineContext，改为提前捕获 Job。`event-owner-compiled.log` hub/native subscribers/question/mux/全部DshApi定向通过41s；`event-owner-question.log` 旁观者取消不交还前台提问通过6s。
- 独立源码复核0个新增明确高风险项。追加 physical disconnect 清除ready、Unconfined回调取消不破坏 fanout 集合、载荷深复制与 ready前最后退订也必须取消逻辑生产者等回归；旧事件源在首次ready等待被取消时现由finally统一回收，保留共享物理API Peer。
- `event-owner-cancel-final.log`：**1411 tests、0 failures、0 errors、14 skipped**，Debug assemble 成功120s。新增事件订阅8、有界hub7、提问旁观者1项；fixture Noise/Router 测试实际执行，已停止的官方daemon opt-in 如实跳过，本轮不启动真实服务。APK 21,970,612 bytes，SHA-256 `092445cdb50b2bc476da22a276a835c25e659a3fac552f062700558aa562c07a`。
- 跨实例共享仍待 guarded lease 与前台/监视归属 seam：messagesPage 当前会隐式开启 contentWatching 并设置 activeSessionId，不能把裸 DshApi 放入全局池或仅靠引用计数包装。当前单实例广播不冒充跨实例 owner 修复。
- r16 实现 explicit DshClientRegistry / factory opt-in、完整 guarded 92方法+4属性、Cookie回调/Flow/终端StateFlow门禁；调用者兴趣和前台 token 隔离。真实 MockWebServer/OkHttp 验证两独立lease仅一次认证/一个mux/$events，后台消息读取不夺焦点，control-only读取不启动workspace内容；后台lease不能操作或清掉实际前台提问认领。
- 首轮 `client-lease-first.log` 生产编译通过，测试替身 Function0/Function3 分支推断冲突，已明确 Any；`client-lease-registry.log` fake用了错误 getIsClosed JVM名使屏障不达，修正为 isClosed 并给等待加期限。`client-lease-registry-fixed.log` 冷Flow内部尚未进入owner就close造成预期活动取消测试失败，新增明确收集屏障，生产门禁未放宽。
- `client-lease-guarded.log` 门禁/兴趣/registry/事件/提问定向通过4s。真实mux夹具 Dispatcher import冲突和session address错误字段已修正。新增终端句柄回归发现 `use` 解析成 Closeable.use 导致发送即detach，改为显式 this@Access.use 并幂等close；`client-lease-terminal-fixed.log` 真实mux与终端清理通过3s。独立源码复核0个新增明确高风险项。
- `client-lease-full.log`：1438项0失败/14跳过 Debug124s；追加失败的旧owner清理不能作为继任权限的fail-closed回归，`client-lease-handoff.log` 通过4s。`client-lease-final.log`：**1439 tests、0 failures、0 errors、14 skipped**，Debug assemble 成功120s；新增 Guarded7、Interests8、Registry10、真实mux2、提问lease1项。APK 22,023,492 bytes，SHA-256 `b39bb202f62c12997685325467a8abc8fe9df6e955b08f7cffc14d198d76b820`。本轮不启动真实服务；fixture Noise/Router运行，已停止的官方daemon opt-in 如实跳过。
- 原始构造器不隐式池化，显式 direct registry 的不同绑定/路由/凭据/timeout 不合并；最后release才关闭owner，有界交还未确认时保留继任失败门禁。应用默认 acquisition 暂不改为全局共享，调用者可通过已提供的 opt-in 接入。
- 全部构建在 `/home/lvziw/.build/Lagoon/unified-harness-connections/source`，Gradle/build 输出在 `~/.build`。源码工作树无 app/build、node_modules。
- `wt audit`：29 repos，0 missing dirs，3 detached、4 merged worktrees 属其他任务；只读报告，无清理。
