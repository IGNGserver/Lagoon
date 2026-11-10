# 统一连接机制重构任务跟踪

## 实际交付状态（2026-11-10）

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
- **尚未完成**：图成为统一运行路由真源；source/host 开关在全部连接/通知/组合范围实际生效；跨 store 事务验收。

### 阶段 3：实际原生传输与 mux，已有实质交付
- OpenCodeApi 的 HTTP/二进制/SSE 已经通过 NativeHttpTransport；public constructors 兼容，close 释放本实例 transport，不关闭共享 dispatcher/pool。
- NativeOkHttpTransport 已实现 HTTP/SSE/**WebSocket text/binary**，generation 取消、终态 close、在途 body-read 取消、3xx 拒绝、固定 origin、响应字节预算、RFC token headers、明确 encoded session ID 路径策略。
- Concrete NativeOkHttpTransportTest 16 项；旧的 WS unsupported 测试已替换，不再声称 stock carrier 缺少 WS。
- DSH direct/SSH PhysicalMuxOwner 一个实例一个 physical socket；保留 idle socket 至 owner close，避免关闭 API-peer lifecycle 后复用失效 cookie；cookie acquisition/socket callback/send 都有 attempt fence。
- 11 项 mux 回归覆盖 typed errors、cancel-all/reopen、end/reopen、onClosed fan-out、旧代次/旧 401 回调、cookie exchange 期间 close、坏载荷/慢消费者/回调异常隔离。
- **尚未完成**：跨独立 direct DshApi 实例共用 API-peer owner；真实 API-peer fallback 互操作。RemoteBackend 仍用原独立 encrypted carrier。

### 阶段 4：SSH 多 Harness 已接入，仍非全部完成
- SshTransportPool 已被 SshHostManager 实际使用：同一 saved source/config/pin/credential revision 复用物理 SSH；每个 binding 有独立 runtime link/forwarder/client/refcount，最后一个引用释放才关连接。
- OpenCode SSH 实际路径：只读 registration → 受信 loopback forwarder → /api/info PID/version 校验 → protected project auth → OpenAPI discovery → 同一个原生 OpenCodeApi。
- 只读 SshHarnessProbe 使用一个有界 exec；默认 XDG/HOME official registration，16 KiB 读取预算；无 Python、文件缺失/损坏/不可读/部分结果明确区分。DSH legacy 标记只作为标记，不冒充桌面 profile runtime。
- OpenCode password 仅 runtime 内存，redacted toString，无 graph codec；服务 generation id 不作为 durable binding 身份。
- SSH 表单已有 DSH/OpenCode 独立选择、只读发现、OpenCode registration path。可全部停用；禁用/删除单 Harness 不关闭兄弟、不删除共享 SSH 凭据；显式删除主机才删全部 leaves。
- 保存 host route 同步更新 siblings，但不改兄弟 enabled/notifications/drafts/cache。未变 source 只失效目标 binding；source 配置变更才重建全部运行通道。仅保存会把新 leaves 停用，避免偷偷 dial。
- OpenCode attach-only，从不 install/start/kill；DSH 保留明确选择的旧 managed web bootstrap，普通 close 不杀远端进程。
- Tests：discovery 13、pool/session ownership 4、真实 socket-backed tunnel adapter 2；另有 graph/store/factory 路由测试。
- **尚未完成**：真正桌面 DSH profile/runtime 自动发现附着、多 OpenCode channel 列表选择、共享主机重连统一状态。没有真实远端 SSH daemon/Android 真机验收。

### 阶段 5–7：未交付
- Remote native connector 仍需固定 upstream overlay + companion package + Android carrier 的构建/端到端测试，不能以 ACP 或插件草案替代原生支持。
- 全局中枢入口/层级管理/组合交互需统一；目前只有 SSH 接入入口和 leaf 独立开关已扩展。
- 真实 Host/Hub/SSH/Android 环境验收、完整能力对照、独立审查、凭据扫描、提交/PR 待完成。

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
- 全部构建在 `/home/lvziw/.build/Lagoon/unified-harness-connections/source`，Gradle/build 输出在 `~/.build`。源码工作树无 app/build、node_modules。
- `wt audit`：29 repos，0 missing dirs，3 detached、4 merged worktrees 属其他任务；只读报告，无清理。
