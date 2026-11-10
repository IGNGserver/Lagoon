# 统一连接机制重构

## 任务与基线

- 用户于 2026-11-09 确认按目标模式依次完成全部七阶段。
- 任务分支：`agent/unified-harness-connections`。
- 初始基线：`origin/main` `24d05d4196fda9437a83d2a1e1ff1cc5bf0754b4`。
- 不部署、不发布；最终提交 PR，协作仓库等待人审查。
- 工作区超限已得到用户对本任务新增一个 worktree 的明确授权；不清理其他任务。

## 不变量

1. 接入源（Hub/SSH/兼容直连）、可信 Host、Harness 原生协议与 route-bound 绑定分离。
2. 老 `ServerProfile.id` 保留为稳定绑定 ID；会话/草稿/缓存/通知/分组不重新编号或清空。
3. 连接开关分层：source && host && harness；关父级不覆写子项偏好；关子项不影响兄弟。
4. SSH 与 Hub 共用同一个 Harness 原生业务适配器，传输层独立；不把 ACP 子集伪装成 OpenCode native。
5. 名称、IP、路径、临时端口和进程随机实例 ID 不作永久服务身份；跨路径合并必须可信证明或人确认。
6. mutation 绑定路径/代次；超时结果未知不盲重放，不切路再发；对账优先。
7. 发现只读，安装/启动/终止独立且显式；不杀不归本应用管理的进程；禁用不停止远端任务。
8. 不把读取失败/部分目录当成权威空列表；不支持、未知、损坏/错误区分。
9. 源码仅工作区；依赖与构建一律 `~/.build`。测试结果和真机互操作分开记录。

## 参考契约

- OpenCode v2.2.6：`dc2ffcf8bf26f9e669b6aa22b95f50e12be15e06`，原生 REST/SSE/WS 与官方 client。
- DSH 0.2.1-rc.5：已安装官方 npm 实现；0.2.2-rc.1：`66491b05e3f05ad35bcdd4f6cd7ed26041d9e3db`。
- ds-harness-remote：`bb33f0a6eb676b61f40a9bbf7d365e8a5e93b81b`；Host/Client 协议为权威，不依赖本仓库历史设计中已过时限制。

## 阶段与验收

### 1. 官方契约与运行时准确性（投影/问题已接入，仍在验证）

- 修复 DSH 定时/continued 提问、模型事件、PTC live/history、每轮结局、外部取消归档、命令附件、部分成功创建和 typed stream errors。
- 修复注册部分失败建档、Hub/account identity scope、目录不完整、SSH auth 错误误判、bootstrap endpoint 校验。
- 补官方契约 fixture 与针对性回归。物理 mux 所有权由第 3 阶段重构。
- 验收：针对性单测真实通过；明确已支持版本和未跑真机边界。

### 2. 新连接域与迁移（模型/存储已落地，统一路由未完成）

- ConnectionSource → HostBinding → HarnessBinding、观测目录与本机选择分离。
- 幂等/中断可恢复/版本化迁移与确切 credential slot refs；保留 ID、disabled、分组/草稿等。
- 原有资料通过兼容投影运行，unknown 协议不默认为 OpenCode。

### 3. 传输和生命周期（HTTP/SSE/WS 和实例级 mux 已落地，跨实例验收未完成）

- NativeHttpTransport（HTTP/SSE/WS/binary），DshTransport（unary/stream/duplex），真实物理 mux。
- Source/Host 连接 owner、子服务 leases、generation/error/backpressure/取消统一。
- 同一目标 test/monitor/controller/notification 不互踢；无写重放。

### 4. SSH 多 Harness（原生 OpenCode/legacy DSH 接线已落地，profile 附着未完成）

- 通用 SSH 会话 + 只读发现 + DSH/OpenCode 独立服务 runtime/forwarder。
- 官方 OpenCode service registration + authenticated health；真实 DSH profile/进程附着。
- 启动锁、进程所有权、独立状态命名空间与故障隔离；明确 POSIX/Windows 支持边界。

### 5. Remote native 多 Harness（连接器/原生 carrier/实际 Noise fixture 已实现）

- 可构建交付的 Host native connector，版本化 manifest/serviceId/原生承载。
- 保持 Hub opaque、现有 Noise 信任边界、同 Host 一条共享通道、多 Host 独立身份兼容。
- 旧 Host 明确降级；不以 ACP 代替完整原生 OpenCode。

### 6. UI 与自由组合（源优先层级/重叠组合已接入，真机待验收）

- 添加入口中枢/SSH（直连兼容高级区）；源/Host/服务详情和各级开关。
- 每台设备多 Harness、组合只引用绑定、范围选择/新会话/通知/深链准确。
- 沿用整页路由及预测式返回，不回退为页面内布尔切换。

### 7. 集成与交付（待开始）

- 全量 unit + debug assemble，安全/取消/迁移/多后端矩阵。
- 真实环境在授权与可用范围内验证，未验证清晰记录，不伪造设备验收。
- 独立审查、文档、凭据扫描、提交并创建 PR；不得擅自合并、部署或发布。

## 当前执行记录

当前阶段状态以 [PROGRESS.md](PROGRESS.md) 为准；下方早期日志保留用于解释失败与修复，不代表最终树。

2026-11-10 本轮实质交付：
- OpenCode 原生 HTTP/SSE/WebSocket/binary carrier，取消/固定 origin/重定向/预算 concrete tests。
- DSH 实例级 mux attempt fence、保留 idle lifecycle socket、过载/坏帧/旧回调隔离（11 tests）。
- SSH 官方注册只读发现与 PID/version/protected-route 验证；同 source 一个共享 SSH pool，native OpenCode/legacy DSH 独立 runtime links。
- SSH 表单独立选择、全停用、只读发现、OpenCode 注册路径；删除/禁用 leaf 不再销毁兄弟连接和主机凭据。
- 独立审查修复 SSE EOF 重订阅、新代次等待递归、半开 SSH entry 淘汰、编辑服务路径回退、无 Harness 测试误启动 DSH。
- PTC/Question 已接入运行时和真实 adapter 测试；仍需复核该阶段提交的 teardown/dirty refresh 最后补丁及完整最终日志。
- Remote native connector 与固定上游完整插件构建、Android 原生 carrier、Host 服务选择和实际 Noise fixture 已接入；管理入口改为源优先并支持重叠组合，最终验证见进度记录。
- 尚未完成：图成为所有运行场景的唯一真源、真实桌面 DSH profile 附着、完整原生能力真环境矩阵、真实设备交互验收和最终 PR。

- 工作区已创建且 HEAD 与 origin/main 基线相同。
- 本机盘可用约 96 GiB（2026-11-09）；构建区 `~/.build`。
- 并行子任务：DSH 语义修复；注册/SSH 准确性修复；Host connector 可实现性只读研究。
- 已执行（本机 scratch mirror，不在源码工作区构建）：
  - 基线 `OpenCodeContractFixtureTest`、`RemoteCapabilitiesTest`：通过，约 1m02s。
  - 新增 `DshPublishedContractTest`、`ApiCapabilitiesTest`、`NotificationClientLeaseTest`：通过。
  - 新增 `RemotePublishedContractTest`：真实 Noise/Relay 栈上的官方 schema 正反例通过；与前述测试合跑约 6s。
- 提取官方 rc.5 的 22 个方法 schema，以及 rc.1 的 8 个 session schema，保留官方 package/source hash 和版本。
- 当前结论仅覆盖已同步测试快照，不代表并行进行中的其余修复已验证，不代表真实主机/真机互操作通过。
- 测试目录：`/home/lvziw/.build/Lagoon/unified-harness-connections/source`；共享 Gradle 缓存 `~/.build/gradle`；日志在任务 scratch 的 `logs/`。
- `source-integrity.log` 快照：51 项测试通过（Remote API 分页/认证、凭据刷新、跨账号资料作用域、名称、存储损坏保护、官方 schema/加密栈、通知租约），约 9s；尚未包含子任务后续编辑。
- Android `adb devices -l` 当前无连接设备；不能声称真机验收已执行。
- 首次 `enrollment-ssh.log`：17 测试中 1 个失败，暴露启动链接正则截断 `&x=y` 导致额外查询未被验证。已改为捕获整条 URL 再严格检查原始 query，并加重复/编码参数负例；复跑中，不能沿用旧通过结论。
- `enrollment-ssh-2.log`：在 DSH 子任务修改途中同步，编译失败（`archivedSessions` 声明被误替换）。已要求子任务恢复并交付稳定快照后再测试；不把该次失败解释为环境问题。
- `enrollment-ssh-3.log`：修复后稳定快照 25 项通过，约 7s，包含跨作用域身份/legacy slot、显式 trust CAS、SSH parser/JSch 错误分类、官方 OpenCode native route fixture、批量注册失败和目录失败、停止命令延迟返回的设备隔离。
- `dsh-semantics.log`：23 项通过，约 5s，包含 DSH 新增语义回归、独立执行 epoch/seq tracker、原有 cold projection 和真实 adapter 的 idle 结局回读；另一个 DSH 子任务初版有未接入字段/未完成逻辑，主代理已接管并修正，不能按子任务初版宣称全完成。
- `dsh-expanded.log`：120 项通过，约 39s，包含全部既有 `DshApiTest` 80 项、投影/后台作业、新官方契约/加密测试、模型/归档/部分创建/命令附件/0.2.2 图片引用和执行 tracker；该快照之后的 PTC/问答及降级收紧仍需再跑。
- 全量 `stage1-full.log` 首轮：988 项测试中仅 `CancellationSafetyTest` 失败，原因是新增 `setProfileEnabled` 保存异常 catch 未显式重抛 `CancellationException`；已修复并重新触发完整测试/assemble，未沿用首轮失败结论。

### Host connector 集成研究（供阶段 5 使用）

- 固定 Remote 上游没有通用 service/register RPC hook；`RpcRouter` 是编译期固定方法集合。
- `plugin.route.call` 只允许 `api/bot` JSON POST，不能借它透传任意原生服务。
- 可信扩展点是 `RpcRouterFactory(context, send)`：Noise/membership/trust 完成后创建每连接 router，断线已有 close 生命周期。
- 建议可复现交付：小型固定上游 source-hash 校验 patch + Lagoon Host native-service package；向 Host runtime/每连接 router 显式注入版本化 registry，能力合并、按连接关闭、固定 method/stream/transfer allowlist；不新建 Noise 身份、不修改 Hub 业务、不暴露 arbitrary URL/shell。
- 补丁应用与打包只在 scratch/CI，最终需有真实 patched router 集成测试和可安装产物；仅写 companion 插件但没有 Router hook 不算实现。
- 精确补丁范围：`service.ts` HostPluginRuntime → `RpcRouterFactory(context, send)` → `rpc-router.ts` 固定新增 `harness.services.describe/call/stream.open/stream.close/transfer.*`，注入 registry；每条已认证连接绑定 `connectionId/peerDeviceId/signal`，unregister/断线关闭服务 session。
- companion 的 OpenCode native endpoint 只读本地 official registration、验证 `/api/info` PID/version，Host 内部保存 URL/Basic 凭据，wire 只传 opaque serviceId；HTTP 仅相对官方路径且拒绝跨源/重定向/任意 URL，SSE/WS/binary 经共享流/transfer。
- 打包建议：源码 patch+SHA manifest 和独立 native-services package 留在本仓库，取固定 upstream commit 在 scratch 构建 patched Remote tarball+companion tarball；不发布 npm、不安装生产桌面 profile。DSH terminal 复用现有受限 carrier，不透传任意 Fetch。
