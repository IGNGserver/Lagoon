# 设备模型（设备 / 接入源 / Harness）

本文是 Lagoon 连接域的**架构契约**。改连接、设备、Harness 的模型或页面层级之前，先改本文并在 PR 里说明
改了哪一条；不要只改代码。

`docs/UNIFIED_CONNECTIONS.md` 是 2026-11 的一次重构**工作日志**（阶段、失败、验证记录），不是规范；
两者冲突时以本文为准。界面细节见 [`DEVICE_MANAGEMENT.md`](DEVICE_MANAGEMENT.md)，SSH 字段见
[`SSH_HOSTS.md`](SSH_HOSTS.md)，Codex 的协议边界见 [`CODEX.md`](CODEX.md)。

## 1. 三层身份

```text
接入源（怎么到机器）            设备（用户操作的单位）              Harness（能力开关）
Remote 中枢账号  ─┐
SSH 主机配置     ─┼──►  一台机器，恰好一条接入路径  ──►  DSH / OpenCode / Codex …
HTTP 直连(兼容)  ─┘       HostBinding + ConnectionSource        ServerProfile（leaf）
```

| 层 | 代码 | 由谁持久化 | 用户能做什么 |
|---|---|---|---|
| 接入源 | `ConnectionSource` | 连接图（`sshHostConfigId`/账号目录另存） | 中枢：登录、退出、暂停整个中枢。SSH：不单独开关 |
| **设备** | `HostBinding` + 其 `ConnectionSource` | 连接图 | 启用/停用、查看 Harness、编辑接入配置、移除 |
| **Harness** | `HarnessBinding` / `ServerProfile` | 连接图（开关镜像）+ 资料库（leaf 真源） | 启用/停用、进 Harness 详情、移除 |

不再有第四层，也不把 Harness 当设备：**界面、路由、统计、文案里的"设备"一律指 `HostBinding`**。

## 2. 身份规则

| 标识 | 公式 | 约束 |
|---|---|---|
| `sourceId` | `source-hub-<sha(canonicalOrigin, account)>`、`source-ssh-<sha(configId)>`、`source-direct-<sha(profileId)>` | 名称、地址、端口、进程实例 ID 永不作身份 |
| `deviceId` | `host-<sha(sourceId, externalHostId)>` | 路由、开关、发现缓存只认它 |
| `leafId` | 既有 `ServerProfile.id`；**永不重编号** | 会话、草稿、缓存、通知、分组都挂在它上面 |
| 中枢 DSH leaf | `nameUUID(origin, account, hostId)` | 既有值，不得改公式 |
| 中枢 OpenCode leaf | `nameUUID(origin, account, hostId, serviceId)` | 同上 |
| 中枢 Codex leaf | `nameUUID(origin, account, hostId, "codex")` | 2026-11 起替换随机 UUID；旧资料保留原 id |
| SSH 新 leaf | `nameUUID(configId, protocol.wire, registrationPath)` | 首个 leaf 历史上等于 `configId`，属迁移期巧合，新代码不得依赖 |
| Harness 槽位 | `(protocolId, locator)`，locator 为 `serviceId` 或 SSH 注册路径 | 一个槽位最多一个 leaf；重复添加必须复用 |

`ServerKind` 是兼容值，冻结：新代码判断传输只看 `remoteHostId` / `sshHostId` / 连接图的 `carrierId` 与
`HarnessProtocolId`，**不得再往 `ServerKind` 加值**，也不得因新增 Harness 加传输分支。

## 3. 开关语义

生效值是 `source && host && leaf`，但**界面只暴露设备与 Harness 两级**。

| 层级 | 存储位置 | 打开 | 关闭 |
|---|---|---|---|
| 接入源（中枢） | `ConnectionSource.enabled`（连接图） | 源下设备可连接 | 源下设备一律不可用；设备仍列出并标注"中枢已退出/已暂停" |
| **设备** | `HostBinding.enabled`（连接图） | 允许建立传输、发现、刷新 | **不建立中枢 Noise、不建立 SSH 连接**；关闭已有传输；不发现、不刷新、不后台同步；Harness 开关原样保留 |
| **Harness** | `ServerProfile.enabled` | 建 client、起 monitor、订阅事件、参与通知与首页 | 不拨号、不订阅、不轮询、不推送、不刷新缓存；资料与缓存保留 |

- 关父级**不覆写**子项偏好；关子项不影响兄弟。
- SSH 接入源开关不暴露：一台 SSH 配置就是一台设备，两个开关是同一件事。历史数据里 SSH 源
  `enabled=false` 的，首次读取时把意图下移到设备开关并恢复源开关，避免出现"设备开着却永远连不上"。
- 设备开关只写 `HostBinding.enabled`。**不要把设备级偏好塞进 `ServerProfile`。**

## 4. 流量与性能契约

| 情况 | 传输 | 发现 | 监控/订阅 | 通知 | 缓存刷新 |
|---|---|---|---|---|---|
| 设备停用 | 无 | 无（只读缓存） | 无 | 无 | 无 |
| 设备启用、该 Harness 停用 | 可能（同设备其他 Harness 在用） | 仅用户显式刷新 | 无 | 无 | 无 |
| 设备启用、该 Harness 启用 | 有 | 显式刷新 | 有 | 有 | 有 |
| 设备最后一个 Harness 停用 | 引用归零后关闭 | 仅显式刷新 | 无 | 无 | 无 |

- 同一设备的多条 Harness **共用一条设备链路**：中枢按 `(hub, 账号, hostId)` 共享 Noise，SSH 按配置共享
  一条已认证连接；关掉一个 Harness 不关闭兄弟的链路，关掉最后一个才关闭。
- 发现是**用户显式触发的临时连接**，不常驻、不在打开页面时自动跑。设备停用时点刷新直接拒绝。
- `host -> 中枢` 的上行链路不归本应用管，本应用的契约只覆盖"中枢/SSH -> 手机"。

## 5. 什么算"一台设备"

设备存在 = 已采纳，证据按接入方式：

- 中枢：本机为该 Host 注册过身份（`RemoteIdentitySlots.scoped(origin, account, hostId)`），或已有任意 leaf。
  **账号目录里出现不等于设备存在**——未采纳的 Host 只出现在"添加设备 → 经由中枢"的候选列表里。
- SSH：存在 `SshHostConfig`。
- 直连：存在对应 leaf（1:1）。

推论：

- 设备可以有 **0 个启用的 Harness**（甚至 0 个已添加的 Harness），页面必须能表达这个状态。
- 移除最后一个 Harness **不删除设备**；删除设备才删除其接入配置、身份与全部 leaf。
- 同一台物理机器通过中枢和 SSH 各出现一次是设计如此：**不自动合并、不互相借道**。要合并只能由用户删掉一条。

## 6. 页面与路由

```text
设备（RootPage.DEVICES）
├─ 中枢账号卡片             → 账号管理：登录/退出、可接入主机、暂停中枢
├─ 由中枢管理
│    ● ubuntu-dev   3 个 Harness · 2 启用 · 在线      [设备开关]
│    ○ windows-vm   已停用                            [设备开关]
├─ 由 SSH 管理
│    ● arch-dev     1 个 Harness · 在线               [设备开关]
├─ 直连（兼容）
└─ 设备组

设备详情（device = deviceId）
  启用此设备 [switch] · 接入方式 · 地址 · 指纹 · 上次发现
  Harness 列表（发现结果 + 已添加项，逐个开关）
  连接设置（SSH 表单 / 中枢账号）
  移除设备

Harness 详情（harness = leafId）
  状态与路由、通知、新会话默认值、模型显示、协议专属面板、移除该 Harness
```

添加流程只有一个入口，两个分支产出同一种设备：

```text
添加设备
[ 经由中枢 ] 登录 → 选主机 → 核对指纹 → 完成（不默认勾选任何 Harness）
[ 经由 SSH ] 地址/凭据 → 核对指纹 → 只读发现 → 勾选 Harness（可全不选）→ 完成
[ 高级 · HTTP 直连 ]
```

- 两条分支都落到设备页，由用户在那里决定启用哪些 Harness。
- 设备子树每一级都是 `NavRoute` 上的一层（见 `ui/AppBackStack.kt`），不得退回页面内布尔切换。
- `device` 路由字段只装 `deviceId`，`harness` 只装 `leafId`。恢复旧状态时允许把历史 `profileId` 解析一次。

## 7. 发现

发现是**观测**，不是许可：

- 统一形状 `HarnessObservation(protocol, slot, status, locator, reason)`，中枢与 SSH 都往里填。
- `HarnessDiscoveryStatus` 的 `installed/configured/listening/authenticated/compatible/available` 与新鲜度沿用
  `core/ConnectionGraph.kt` 的定义；只有 `available == YES` 且未过期才算"可用"。
- 结果落在 `DeviceDiscoveryStore`（按 `deviceId`），带 TTL；打开页面先显示缓存，刷新是显式动作。
- 未检测到 / 不支持 / 离线 / 未认证必须区分，不得显示成"可用"或"空列表"。

## 8. 存储落点

| 数据 | 位置 | 权威性 |
|---|---|---|
| 接入源、设备（开关/指纹/采纳）、Harness 绑定 | `ConnectionGraphStore` | 结构真源（由 leaf + 目录投影，journal 原子写） |
| Harness leaf（路由、凭据引用、leaf 开关、协议选项） | `ServerStore` | leaf 真源 |
| SSH 配置与密钥 | `SshHostStore` | 设备接入配置 |
| 中枢账号、主机目录、本机设备身份 | `RemoteCatalogStore`、`RemoteDeviceStore` | 账号与身份真源 |
| 发现结果 | `DeviceDiscoveryStore` | 观测，带时效 |
| 分组 | `DeviceGroupStore` | 只影响展示范围；成员按设备选，内部展开到 leaf |

不要新增第七个存储来放已有职责的数据；要放新数据先说明它属于上表哪一类。

## 9. 迁移与兼容

- 既有 leaf id、会话、草稿、缓存、通知账本、分组不重编号、不清空。
- 连接图不可读/版本更高时**失败关闭**（不连接、不迁移、不覆盖原文件）。
- 禁用永不删除资料；重新启用立即建立一次监控并刷新（即使旧 `autoConnect` 为 false）。
- 旧版直连 DSH 与直接 HTTP 直连保留为兼容入口，不再新建。

## 10. 已被否决的方案（不要重做）

1. 把 Harness 当设备展示、按 Harness 类型分设备页。
2. 给 Codex（或任何单一 Harness）建独立设备页、独立传输层或独立开关层级。
3. 添加 SSH 时先勾 Harness 再连、保存即拨号。
4. 在设备列表平铺"接入源 / 主机 / Harness"三个开关。
5. 用 `profiles.size` 当设备数。
6. 用目录里出现的主机推断"设备存在"。
7. 把发现结果当成连接许可，或用 URL/名称/显示顺序做身份。
8. 用 ACP 或旧间接路径冒充原生 Harness。

## 11. 测试锚点

- `DeviceProjectionTest`：设备识别、0/1/N Harness、两级开关链、计数、采纳规则。
- `DeviceSwitchSemanticsTest`：停用设备不拨号、不发现；停用 Harness 不订阅；子项偏好保留。
- `DeviceDiscoveryStoreTest`：往返、TTL、损坏保护、版本拒写。
- `InformationArchitectureRegressionTest`：设备/ Harness 每一级都是路由。
- `DisabledProfileTest`、`DeviceGroupTest`、`CodexConnectionGraphTest`：既有行为不回归。
