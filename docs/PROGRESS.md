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
- 多 OpenCode channel 目前通过用户保存的具体 registration path 逐个建 binding，不自动合并 channel；无真实远端 SSH daemon/Android 真机验收。

### 阶段 5：Remote native connector 已实现，实际加密互操作已通过定向验证
- 新增 `host-connector/`：固定上游 SHA-256 校验 overlay，将 NativeServicePeer 放在认证 Noise/信任校验之后的每连接 RpcRouter，完整构建 Router、HostPluginRuntime、插件入口；不是 manifest/stub，也不需要新建 Hub。
- Host 只读官方 OpenCode registration，先无凭据验证公开 `/api/info` PID/version，再验证受保护 route；凭据仅 Host 内存。只允许 loopback、OpenAPI method/path、header 白名单，禁止重定向、路径穿越和手机任意 URL/凭据。
- 原生 HTTP/binary/SSE/WS，序号/背压/取消、父连接配额、64 MiB 有界 request/response transfer、上传 commit 一次性消费；未知提交不重发。关闭 pending-open 不复活流；发现替换/入站帧校验回收旧服务代次。
- Android RemoteNativeServices + LazyNativeHttpTransport 已接入 RemoteSession/RemoteBackend/OpenCodeApi；同 Hub/account/Host 一个 Noise session，不以 ACP 或 DSH Typert 冒充原生 OpenCode。
- Remote service locator 单独保存为 remoteServiceId，generation 不持久化为身份。新 Host 服务选择页只读发现、默认可停用保存、显式逐个启用；旧 Host 明确升级要求。
- 验证：Host real loopback 11 tests、patched actual Router/ConnectionController 5 tests；Android carrier 15、lazy admission 7、native binding 7、实际 Kotlin Noise→Node Router/Peer→原生服务 fixture 2（其中 1 互操作、1 旧Host）。大请求实际 Noise 互操作及全量 1173 项回归已通过，具体证据见下方日志记录。

### 阶段 6：统一 UI 与自由组合，源码已接入，验收进行中
- 添加入口改为 Remote Hub/SSH 接入源优先，兼容 HTTP 直连放高级区；管理页显示 source→Host→Harness 和独立开关。
- 组合改为稳定 leaf ID 的多对多引用：一个 Harness 可加入多个组合，新建/编辑不再从其他组合偷走成员；显式 MOVE 仍是移动。
- 详情展示所有组合成员关系，不再用一个下拉框破坏其他组合；原生服务保留准确 carrier 标签和独立操作。
- 未跑 Android 真机布局、可访问性、预测式返回；当前仅源码与 Compose 编译验收。

### 阶段 7：验证与交付仍进行中
- 本地 fake Hub/实际 Noise/真实 Node connector 互操作不等于公网 Hub、真实官方 daemon 或 Android 真机验收。
- DSH Desktop SSH 附着仍缺上游受支持 hook；跨 direct DshApi API-peer 复用及完整功能矩阵未全部验收。没有虚构完成。
- 本批完整回归/独立复核/凭据模式扫描已通过，准备保存本地 checkpoint；剩余整合及最终 PR 待收口；没有部署、发布或合并。

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
- 本机 `opencode --version` 为 1.18.18，与固定 native v2.2.6 基线不同，没有拿它冒充官方 2.x daemon 真环境验收；本机未发现 sshd。当前 `adb devices -l` 仍为空；真机 UI/通知/返回手势未验收。公网 Hub/真实官方 daemon 未冒充本地 fixture。
- r8 `source-lifecycle-first.log`、`source-lifecycle-independent.log`：空 source catalog、SSH 删除边界及父级许可定向通过；`source-lifecycle-signedout.log` 首轮一项失败是夹具 Long.MAX_VALUE 超出协议安全时间戳，修正为合法未来值，不放宽生产校验。
- `source-lifecycle-full.log`：**1225 tests、0 failures、0 errors、6 skipped**，actual Noise 跨语言 2/2 运行通过；debug assemble 成功，123s。APK 21,713,748 bytes，SHA-256 `931ecbd9ea5261733f156164176bdbc03bb8f94adeeda5c9d5d4975e11cd77d8`。新测 CatalogProjection 8、真实 SSH Controller 删除 3、parent-only admission 6、signout/relogin 4、catalog signedOut codec 1。
- 全部构建在 `/home/lvziw/.build/Lagoon/unified-harness-connections/source`，Gradle/build 输出在 `~/.build`。源码工作树无 app/build、node_modules。
- `wt audit`：29 repos，0 missing dirs，3 detached、4 merged worktrees 属其他任务；只读报告，无清理。
