# DSH Remote 多 Host 接入与设备分组

> **2026-11-06 更新**：首页的横向服务器胶囊已删除，设备/分组/项目/工作区统一由左上角标题打开的
> 范围面板承载；本文中关于“胶囊”的描述为当时实现。

本文分析 Android 端 DSH 全面改用 [ds-harness-remote](https://github.com/liguobao/ds-harness-remote) 后，为什么当前一次只能连一台 Host，以及如何做到：

1. 同时连接账号下的全部 Host；
2. 把同一台物理设备上的多个 Host（不同 harness 实例）显示成一个设备，而不是多个条目。

结论先行：

- **同时连接全部 Host 可行，且不必修改 ds-harness-remote**。协议允许“不同客户端身份各连一台 Host”；Lagoon 缺的是“一次登录、多 Host 建档”的产品层编排，底层多服务器引擎已经存在。
- **同一个客户端身份同时连多台 Host 不可行**。上游 Hub 的 `peers` 以 `deviceId` 为键，新连接会踢掉旧连接；`connect.request` 还会丢弃该客户端已有的 link。要单身份多连接，必须改自部署 Hub，或等上游支持。
- **设备分组不要用 IP**。Host 自报的 `name`（默认 hostname）与 NAT / 内网穿透无关；默认按 `name + platform` 分组，手动合并/拆分兜底。要更稳，可让 Host 额外上报 `machineId`。
- **以上两条都不要求改中枢**。多设备并行只改 Lagoon；设备分组默认也只改 Lagoon；“完全免手动且可靠”的分组才需要 Host 侧 `machineId`（Host 插件可选升级，不是中枢补丁）。
- **同一台机器上多个 DSH profile 要同时在线，前提是各自拥有独立的 Remote Host 身份**。共享 `$DSH_HOME` 时 Host 身份也共享，Hub 只会保留一个连接。

---

## 1. 现状

### 1.1 Lagoon 的数据模型

- `ServerProfile` 只保存一个 `remoteHostId` / `remoteHostFingerprint`：
  [`Models.kt`](../shared/src/main/kotlin/com/igng/opencode/lagoon/core/Models.kt#L19-L21)。
- `RemoteSession` 绑定一个 `hostId`，`ensure()` 只拉取这一台 Host：
  [`RemoteSession.kt`](../shared/src/main/kotlin/com/igng/opencode/lagoon/core/RemoteSession.kt#L9-L51)。
- `RemoteBackend` 包一个 `RemoteSession`，该 profile 的 `DshApi` 全部调用都走它：
  [`RemoteBackend.kt`](../shared/src/main/kotlin/com/igng/opencode/lagoon/core/RemoteBackend.kt#L13-L17)。
- `remoteBackendFactory` 每个 profile 建一个 `RemoteDeviceManager`，slot 是 `profile-<id>`：
  [`LagoonController.kt`](../app/src/main/java/com/igng/opencode/lagoon/core/LagoonController.kt#L279-L294)。
  因此**每个 profile 会注册一个独立的 Remote client 设备身份**。
- 配对 UI 的 Host 下拉是单选，保存时只写一个 `remoteHostId`：
  [`Screens.kt`](../app/src/main/java/com/igng/opencode/lagoon/ui/Screens.kt#L287-L292)、
  [`Screens.kt`](../app/src/main/java/com/igng/opencode/lagoon/ui/Screens.kt#L453-L454)。

但 `AllServersOverview.start()` 已经按 `autoConnect` 为每个 profile 建立独立 monitor：
[`AllServersOverview.kt`](../shared/src/main/kotlin/com/igng/opencode/lagoon/core/AllServersOverview.kt#L85-L99)。
也就是说，**底层已经能并行连接多个 profile**；现在缺的是从一个 Hub 账号生成多个 Host profile 的入口，以及按物理设备聚合的展示。

### 1.2 Remote 的三层“单连接”

| 层 | 约束 | 影响 |
|---|---|---|
| Hub Server `peers` | `Map<deviceId, Peer>`；新 hello 调 `disconnect(device.deviceId, 'CONNECTION_REPLACED')`（[gateway.ts](https://github.com/liguobao/ds-harness-remote/blob/main/apps/server/src/gateway.ts#L83)） | 同一个客户端身份只能有一个控制 WebSocket |
| Hub Server links | `connect.request` 丢弃该 client 的旧 link（[gateway.ts](https://github.com/liguobao/ds-harness-remote/blob/main/apps/server/src/gateway.ts#L124)） | 同一个客户端身份同时只能有一个 Host link |
| Host `ConnectionController` | 同一 `peerDeviceId` 的新连接会替换旧连接（[connection-controller.ts](https://github.com/liguobao/ds-harness-remote/blob/main/packages/plugin/src/connection-controller.ts#L79)） | 同一个客户端身份对同一台 Host 只能有一条连接；不同 Host 各自独立，不受影响 |
| 上游客户端 | `client-runtime.ts` 明确“一台设备一个 Control 连接”，工具查询会替换 warm UI 连接（[client-runtime.ts](https://github.com/liguobao/ds-harness-remote/blob/main/packages/plugin/src/client-runtime.ts#L492)） | 上游 Web/Android 产品层是单目标 |

关键点：**Host 层的替换是按每台 Host 进程各自的 `active` 表做的**。客户端身份 A 连 Host X、身份 B 连 Host Y 时，两个 Host 各自只看到一条连接，不会互相踢。因此“多客户端身份 → 多 Host 并行”在未改动的 Hub/Host 上都成立。

### 1.3 为什么现在看起来只能连一台

- 一个 profile 只保存一个 Host；
- 一次配对只选一个 Host；
- 每个 profile 一个新 client 身份，但没有“一次登录创建多个 Host profile”的入口；
- 首页服务器胶囊按 profile 展示，N 台 Host 就是 N 个条目。

不是协议只能一台，而是**产品模型把“Hub 账号”和“Host 连接”压成了一个 profile**。

---

## 2. 目标模型

```text
DSH Hub (origin + account)
  ├─ Host A (deviceId, name=MacBook, platform=darwin, harnessVersion=...)
  ├─ Host B (deviceId, name=MacBook, platform=darwin, ...)
  └─ Host C (deviceId, name=Studio,  platform=linux,  ...)

Lagoon 本地：
  RemoteHub
    ├─ HostBinding A -> ServerProfile(remoteHostId=A)
    ├─ HostBinding B -> ServerProfile(remoteHostId=B)
    └─ HostBinding C -> ServerProfile(remoteHostId=C)
  DeviceGroup "MacBook" = [A, B]   // 仅展示/默认目标
  DeviceGroup "Studio"  = [C]
```

- **Hub** 是账号/身份边界；
- **HostBinding** 是连接边界；
- **DeviceGroup** 是展示边界。

底层仍然一台 Host 一个 `ServerProfile` / `RemoteSession` / `DshApi`，复用现有 `AllServersOverview`、缓存、通知与会话路由。DeviceGroup 不参与鉴权，只影响 UI 分组和默认目标。

---

## 3. 方案

### 方案 A：每 Host 一个客户端身份（推荐，兼容官方 Hub）

**做法**

- 一次 Hub 登录，列出全部 Host；用户多选并确认指纹。
- 为每个选中的 Host 注册一个 Remote client 设备身份（不同 slot），保存一个 `ServerProfile`。
- 所有 profile 的 `autoConnect` 打开；`AllServersOverview` 并行建立连接。
- 删除 Host 时同时 revoke 对应 client 设备，避免服务端残留。

**优点**

- 零上游改动，官方托管 Hub 也能用。
- 连接、令牌、密钥按 Host 隔离；一台出问题不影响其他。
- 每台 Host 的 Host 侧只看到一条连接，符合上游现有语义。
- 现有 Lagoon 多服务器引擎直接复用。

**代价**

- Hub 账号的设备列表会出现 N 个 “Lagoon” client 设备（每 Host 一个）。客户端列表接口只返回 Host，所以 Lagoon 自己不显示；但服务端管理页会看到。
- N 个 client 私钥/令牌，需要清理。
- 服务端设备上限 256；N 台 Host 远低于上限，但应用内仍应设上限。
- 添加新 Host 时需要重新输入 Hub 密码（account token 只有 8 小时，不建议长期保存）。

这是满足“同时连接全部设备”的最小可用路径，建议作为默认。

### 方案 B：单客户端身份 + Hub 多连接（自部署/上游）

**做法**

- 修改 `apps/server/src/gateway.ts`：
  - `peers` 改按连接实例键，增加 `byDevice: Map<deviceId, Set<Peer>>`；
  - hello 不再断开同 device 的其他连接，改为按角色限制（client 最多 N 条，host 1 条）；
  - `connect.request` 只清理同一 `(client, host)` 的旧 link，不清理该 client 的其他 Host link；
  - link 存 host/client Peer 引用；close 只移除当前 peer 及其 link；revoke/auth invalidate 关闭该 device 全部连接；
  - `online` 改为“该 device 是否有任意 host peer”；
  - 增加能力探测（`GET /api/v1/capabilities`，或在 `/health` 返回 feature）。
- Lagoon 启动/配对时探测能力：
  - 支持 → 一个 Hub 一个 client 身份，N 条 WebSocket；
  - 不支持 → 回退方案 A。
- Host 不需要改：每台 Host 仍只看到该 client 的一条连接。

**优点**

- Hub 设备列表只多一个 Lagoon client。
- 令牌/密钥只有一份，清理简单。

**代价**

- 必须自部署并维护 Hub 补丁；官方托管 Hub 不可用。
- 单身份是更大的 blast radius：一份凭据或一次 revoke 影响全部 Host。
- 上游升级需要 rebase；必须做能力探测，避免对旧 Server 误开多连接把主连接踢掉。
- 若同一 Host 同时开两条连接，Host 仍会替换；Lagoon 必须保证每 Host 一条。

作为自部署优化项，不作为默认。

### 方案 C：上游协议/能力增强

- 在 `harness.transport.describe` 返回值增加 `machineId`（Host 侧改动，Hub 不存）；Lagoon 缓存后做权威分组。
- 在 Hub 增加 multi-link 能力或协议版本；上游接受后方案 B 可标准化。
- 不建议把 `machineId` 塞进 `HostDeviceDescriptor`：协议 schema 是 `.strict()`，加字段需要版本升级；`describe` 扩展更轻。
- 优点：分组最稳；缺点：要改 Host 插件并分发到每台机器。

### 不推荐

- **SSH/frp 隧道 + 直连 `dsh web`**：绕开 Remote 的 NAT 穿透价值，回到 `dsh-multi-device` 插件的路线；用户明确提到内网穿透，不适合作为主方案。
- **N 个 Hub 账号**：破坏账号边界，Host 授权和设备管理更乱。
- **轮换单连接**：不是“同时”，切换会断流、丢实时事件。

---

## 4. 设备分组

### 4.1 可用标识

`RemoteHost` 目前有：`deviceId`、`name`、`platform`、`membershipId`、`online`、`publicKey`、`clientVersion`、`harnessVersion`、`fingerprint`。

| 字段 | 能否做设备分组 | 说明 |
|---|---|---|
| `name` | **默认可以** | Host 侧默认 `os.hostname()`；`DSH_REMOTE_DEVICE_NAME` 可覆盖。不是 IP，NAT 不影响。 |
| `platform` | 辅助 | `linux` / `darwin` / `win32`，降低同名跨平台误合并。 |
| `harnessVersion` | 辅助展示 | 不唯一。 |
| `deviceId` | **不可以** | 每个 Host 实例一个，正是要合并的维度。 |
| `membershipId` | **不可以** | 服务端按账号生成（`account:<hash>`），同一账号所有设备相同。 |
| `publicKey` / `fingerprint` | 不可以 | 每个 Host 身份一个。 |
| IP / 端口 / URL | **不可以** | NAT、frp、TURN、多网卡都会变；用户已明确排除。 |

### 4.2 分组键优先级

```text
1. 手动指定（用户把 Host 移到某个设备组）
2. machineId（Host 在 describe 上报，哈希后）
3. 结构化 name（如 "MacBook/desktop"、"MacBook · web"）
4. 规范化 name + platform（默认 hostname 分组）
5. hostId 单独成组（兜底）
```

- 规范化：Unicode NFKC、trim、大小写折叠；显示保留原文。
- 不用 IP、端口、URL、lastSeen、deviceId 前缀。
- `machineId` 冲突时手动优先；同名但 fingerprint 不同不静默合并，先提示确认。
- 不想改 Host 时，可用环境变量约定同一台机器的分组，例如
  `DSH_REMOTE_DEVICE_NAME="MacBook/desktop"`、`"MacBook/web"`；Lagoon 可按 `/` 或 ` · `
  拆分出设备前缀和 harness 别名。Host 名称是自报字段，只用于展示，不作为安全边界。

### 4.3 自动、确认与手动

- **自动**：`machineId` 相同 → 直接同组。
- **自动建议**：`name + platform` 相同 → 建议同组；首次出现第二个成员时提示一次“是否归为同一设备”，确认后记住，之后同键新 Host 自动加入。
- **手动**：任何时候可合并/拆分/重命名/改别名；手动结果持久化，优先于自动。
- **同名不同机**：用户拆分一次即可；拆分后记住该 hostId 的 manual 归属。
- **Host 身份重置**：hostId 变化但 name/machineId 不变 → 自动回到原组；旧 hostId 标记失效，TTL 后清理。

### 4.4 同机多 harness 的前提

上游 Host 身份目录是 `$DSH_HOME/remote/servers/<origin-hash>/host`（`IdentityStore` 默认 `$DSH_HOME/remote`）。同一 `$DSH_HOME` 下的多个 DSH profile 共享同一个 Host 身份，Hub 只允许一个控制连接：

- 两个 profile 同时启动 → 后启动的把前一个踢下线；
- 它们不会显示成两个 Host，而是同一个 Host 反复上下线。

要在一台机器上同时跑多个 harness，必须让每个实例有独立 Host 身份：

```sh
DSH_HOME=~/.dsh-web     dsh --profile web
DSH_HOME=~/.dsh-desktop dsh --profile desktop
# 或者不同 OS 用户 / 容器
```

这样 Hub 才会看到两个 host deviceId；Lagoon 再按 `name + platform` 或 machineId 把它们归到同一设备。

如果用户只是想在同一个 Host 内切换 DSH/Codex/ACP 后端，那是 Host 内部 backend，不是本文的 Host 分组；Lagoon 目前只适配 DSH Typert 后端。

### 4.5 数据结构建议

```kotlin
data class RemoteHubKey(val origin: String, val account: String)

data class RemoteHostMeta(
  val hostId: String,
  val name: String,
  val platform: String,
  val harnessVersion: String?,
  val fingerprint: String,
  val online: Boolean,
  val lastSeenAt: Long?,
  val machineId: String? = null
)

data class RemoteDeviceGroup(
  val id: String,                  // 本地稳定 id
  val label: String,               // "MacBook Pro"
  val key: String?,                // "machine:<hash>" / "name:<platform>:<name>" / null=manual
  val hostIds: Set<String>,
  val confirmed: Boolean,
  val aliases: Map<String, String> // hostId -> "desktop"/"web"
)
```

存储：

- `remote-host-catalog`：`origin|account -> Map<hostId, RemoteHostMeta>`；连接/刷新 Host 列表时更新，供离线展示。
- `remote-device-groups`：`origin|account -> List<RemoteDeviceGroup>`；手动归属/确认状态/别名。
- `ServerProfile` 仍是一台 Host 一个；不把组写进 profile，避免两套真相。
- 可选给 `ServerProfile` 增加 `remoteHostName` / `remoteHostPlatform` 缓存字段，但单独 catalog 更干净。

### 4.6 UI

- **首页服务器胶囊**：从“每 profile 一个”改为“每设备组一个”；组内多个 harness 时，胶囊显示设备名 + 在线数，点开选择 harness（或按最近使用直接进入）。
- **设置/服务器页**：设备组 → 展开 Host 列表；每个 Host 显示别名、平台、harness 版本、deviceId 短码、在线状态、指纹；支持“移入其他设备 / 拆分为新设备 / 重命名”。
- **组内 harness 标签**：默认用 `harnessVersion`、`platform`、deviceId 短码区分；允许设置本地别名（desktop/web/…）。如果用户按 `设备/harness` 约定命名，直接显示后半段。
- **会话行**：全部服务器模式下显示 `设备 · harness`，避免同名会话混淆。
- **新建会话**：先选设备组，再选 harness（组内只有一个在线时自动选）。
- **通知**：`TaskSummary` 的 serverName 改为 `设备 · harness`。
- **连接状态**：组级汇总 + 单 Host 明细；一台离线不让整组显示离线。

### 4.7 迁移

- 现有 DSH_REMOTE profile：每台一个 Host；按 `(url, account)` 归入同一个 Hub。
- 首次连接时拉取 `api.host`，写入 catalog；按 name/machineId 自动建议分组。
- 没有密码时不能主动列全部 Host；不要为了迁移要求重新登录。未取得元数据的 profile 先单独显示，连上后自动归组。
- 删除 profile 时 revoke 对应 client 设备；若离线/令牌过期，记录待清理，下次登录提示。
- 旧 profile 的 slot/identity 保留，不重建；避免 Host 侧重新 TOFU。

### 4.8 是否必须手动分组？

不是“必须手动”，但要区分三档：

| 档位 | 是否要改上游 | 效果 |
|---|---|---|
| 默认：`name + platform` 自动建议/确认 | 不要 | 同一台机器通常默认同一个 hostname，常见情况零操作；同名冲突时手动拆分 |
| 约定：`DSH_REMOTE_DEVICE_NAME="设备/harness"` | 不要（用户配置环境变量） | 可自动拆出设备前缀和 harness 别名；跨平台/自定义名称也能正确归组 |
| 权威：Host `machineId` | 要更新 Host 插件（不是中枢） | 完全自动且可靠，改名/改网络都不影响 |

以下情况需要手动合并/拆分：

- 两台不同机器 hostname 相同（例如都叫 `localhost` / `ubuntu`）；
- 用户给同一台机器的各个 harness 起了互不相同、又没有共同前缀的名称；
- 同一物理机的 WSL 与 Windows 两侧 hostname 不同；
- Host 身份重置/迁移，需要把旧成员并到新 Host。

如果“不改 Host、也不要求用户配置名称”仍想 100% 自动，现有 Hub 暴露的字段做不到：`deviceId` 是每 Host 一个，`membershipId` 是账号级，IP 不可用。此时只能“自动建议 + 手动兜底”。

无论哪一档，都**不需要给中枢打补丁**。

### 4.9 跨 harness 分组（OpenCode + DSH）

上面的 hostname / machineId 方案针对的是**多个 Remote DSH Host**。如果一台设备同时有 OpenCode 和 DSH，要求把它们归到同一个设备，问题会变一个数量级：

- DSH Remote 至少还暴露 `Host.name`（默认 hostname）、`platform`、`hostId`；
- OpenCode 直连在 Lagoon 里只有 URL、Basic Auth 和项目目录，没有机器名/机器 ID；
- 两者没有共同键：DSH 的 `deviceId` 每 Host 一个，`membershipId` 是账号级；OpenCode 没有对应字段；
- IP / URL 会被 NAT、frp、反向代理、多网卡破坏；工作区名称又可能在不同设备上重名；
- 用项目绝对路径做启发式（如两边都有 `/home/alice/proj`）只能猜，不能保证同机，也不能保证不同机不撞。

因此，在“Remote 插件不打补丁、不改中枢、不装新插件、不改开发机配置”的前提下，**跨 OpenCode + DSH 的自动设备分组不能可靠实现**。可落地的只有：

1. 用户一次性手动把 OpenCode profile 和 DSH profile 归到同一个设备组；
2. 或接受按 URL/hostname/路径的启发式建议，再由用户确认。

一旦设备组已经确定，组内“会话显示 DSH/OpenCode 徽标、工作区按名称/路径合并、顶栏只显示 A/B 两个 tab”都是客户端展示逻辑，不需要上游改动。

如果要全自动，至少需要给两类 harness 一个共同的机器标识来源，例如：

- OpenCode 侧暴露 hostname/machineId（上游 API 或插件）；
- DSH Remote Host 侧在 `harness.transport.describe` 暴露 machineId（Host 插件增强）；
- 或每台开发机有一个共享的 device agent 同时向两端登记。

这三者都超出了当前约束。

---

## 5. Lagoon 改造清单

### 5.1 数据与存储

- `RemoteHostMeta` / `RemoteDeviceGroup` / `RemoteGrouping`（shared，纯逻辑可测）。
- `ServerStore` 增加 host catalog、group、alias 的读写；Android 实现用 SharedPreferences（不含密钥）。
- `RemoteServerApi` 增加 `revokeSelf(token)`，删除 Host 时调用。

### 5.2 配对

- 新增 `RemoteSetup.discover(account, password)`：一次登录，返回 account token + hosts + fingerprints。
- 新增 `RemoteSetup.enroll(selectedHosts)`：
  - 方案 A：每个 Host 一个 `RemoteDeviceManager` / slot，`register` 循环；复用同一个 account token。
  - 方案 B：探测 Hub 能力；支持则一个 slot 注册一次，N 个 Host 共用。
- UI：多选 + 一次指纹确认；显示将创建的设备组预览（同 name 合并建议）。
- 幂等：`(origin, account, hostId)` 已存在则复用 profile / identity，不重复注册。
- 失败回滚：已注册但未保存的 client 设备尽量 revoke；至少不留下“有身份无 profile”的静默状态。

### 5.3 运行时

- 方案 A：现有 `RemoteBackendFactory` 不变；每个 profile 一个 `RemoteSession`。
- 方案 B：`RemoteBackendFactory` 按 `(origin, account)` 共享 `RemoteDeviceManager` / token；每个 Host 仍是独立 `RemoteSession` / WebSocket。
- `AllServersOverview`：保持每 profile 一个 monitor；增加并发上限（建议 8/16）、启动错峰、后台策略。
- 同一 Host 的连接单例：`testServer`、monitor、controller 不要各开一条同身份连接；用 registry 或临时停 monitor，避免 Host 侧互相替换。
- Token refresh：方案 A 每身份独立；方案 B 共享 manager，已有 slot 锁串行化，注意别每个 session 建独立 manager 实例（锁是进程级，仍安全，但对象冗余）。

### 5.4 UI

- 设备组模型/排序/筛选；组内 harness 选择。
- 服务器胶囊、连接条、通知、新建会话目标、设置页。
- 组级“连接全部 / 仅按需”开关；单 Host `autoConnect`。
- 空态/错误：组内某台离线时显示“2/3 在线”，不要整组报错。

### 5.5 测试

- `RemoteGroupingTest`：同名、同 machineId、手动覆盖、hostId 重置、同名不同 platform、空 name。
- `RemoteMultiHostTest`：假 Hub + 两个 Host，两个 client 身份，验证两条连接、事件隔离、关闭一台不影响另一台。
- `RemoteEnrollmentTest`：一次登录注册 N 个身份；部分失败回滚；重复 hostId 幂等。
- 方案 B：`RemoteGatewayMultiLinkTest`（同 device 两条 socket / 两条 link）、能力探测回退。
- 真机：两台 Host、NAT/frp/TURN、断网重连、删除/重装、通知。

---

## 6. Hub 补丁草案（仅方案 B）

> 目标：同一 client deviceId 允许多条控制 WebSocket；每条 link 仍一对一 Host。

`apps/server/src/gateway.ts` 关键改动：

1. `Peer` 增加 `key`；`peers: Map<key, Peer>`；`byDevice: Map<deviceId, Set<key>>`。
2. hello：
   - 认证后不再 `disconnect(deviceId)`；
   - `client` 允许最多 `MAX_CLIENT_CONNECTIONS`（建议 8），`host` 仍 1；
   - 加入 `byDevice`。
3. `Link` 存 `host: Peer` / `client: Peer`（或 key），不再只存 id。
4. `connect.request`：
   - 从 `byDevice` 找该 host 的在线 peer；
   - 只清理 `(client, host)` 这一对的旧 link；
   - 不清理该 client 的其他 Host link。
5. 帧路由：`peer` 必须等于 `link.host` / `link.client`；`send` 到另一端 peer。
6. close：只移除当前 peer + 它的 link；`disconnect(deviceId)` 用于 revoke/auth invalidate，关闭该 device 全部 peer + link。
7. presence：`byDevice.has(hostId)`。
8. `GET /api/v1/capabilities`：返回 `{ protocol: 1, multiControlConnections: true, maxClientConnections: 8 }`。
9. 测试：两条同身份 socket → 两个 Host link 同时 ready；关闭一条不影响另一条；revoke 关闭全部；旧客户端单连接行为不变。

Lagoon 侧：

- `RemoteServerApi.capabilities(token)` 探测；404/false → 方案 A。
- `RemoteRelay` hello capabilities 增加 `control.multi-link`（仅探测到支持时发送）；`hello.ack` 解析允许该能力。
- 方案 B 的 slot 用 `hub-<hash(origin,account)>`；N 个 `RemoteSession` 共用。

> 注意：不要在 `hello.ack` 无条件增加能力字段。旧客户端可能严格校验能力数组；应按客户端请求回显，或先让 Lagoon 更新解析。

---

## 7. Host machineId 草案（方案 C，分组增强）

在 `harness.transport.describe` 返回值增加：

```json
{ "capabilities": ["..."], "workspaceTypes": [], "machineId": "sha256:..." }
```

Host 侧：

- Linux：`/etc/machine-id` 或 `/var/lib/dbus/machine-id`；
- macOS：`ioreg -rd1 -c IOPlatformExpertDevice` 的 `IOPlatformUUID`；
- Windows：`HKLM\SOFTWARE\Microsoft\Cryptography` 的 `MachineGuid`；
- 兜底：`~/.dsh/remote-machine-id`（首次生成，跨 profile 共享）。
- 发送前哈希：`sha256(serverOrigin + "\0" + raw)`，只发 32 hex；不泄露原始机器 ID，也不跨 Hub 关联。
- 读取失败返回省略字段，不阻塞连接。

Lagoon 侧：

- `RemoteCapabilities` 增加 `machineId: String?`，校验长度/字符；
- `RemoteSession` 连接成功后回调 `RemoteHostCatalog.rememberMachineId(hostId, machineId)`；
- 分组键优先级 machineId > name；
- 旧 Host 没有该字段 → 继续用 name，不报错。

---

## 8. 风险与边界

- **设备数量**：方案 A 每 Host 一个 client 设备；自部署 Server 的上限是 256，官方托管 Hub 的实际上限以服务端为准。如果官方上限低于 Host 数，方案 A 会被卡住，只能走方案 B 或推动上游。应用内建议上限 16/32，并在设置中显示。
- **Hub 管理页**：会看到 N 个 Lagoon client；用 `Lagoon · <hostName>` 命名，删除时 revoke。
- **令牌/密钥**：方案 A 隔离好但数量多；方案 B 一份凭据影响全部，需明确提示。
- **同 Host 重复连接**：test/monitor/controller 必须共用单例，否则 Host 会替换；这是现有 `testServer` 的潜在问题，多 Host 后更明显。
- **共享 `$DSH_HOME`**：多个 harness 不能同时在线；这是 Host 身份/上游设计，不是 Lagoon 能修的。
- **同名设备**：hostname 相同可能不是同一台机器；默认建议 + 手动拆分，避免静默错并。
- **电池/流量**：N 条 WebSocket + N 个轮询；需要上限、错峰、后台策略。
- **上游漂移**：Remote 是 preview；能力探测/降级，不把版本字符串当契约。
- **安全**：分组仅展示，不参与授权；machineId 哈希；不引入 IP 信任。

---

## 9. 建议落地顺序

1. **P1（零上游）**：多选配对 → N 个 profile → 设备组模型/UI → 迁移 → 测试。满足“同时连接全部设备”。
2. **P2（可选）**：Host `machineId`（describe）→ 权威分组；仍兼容旧 Host。
3. **P3（可选，自部署）**：Hub 多连接补丁 + 能力探测 → 单 client 身份；官方 Hub 自动回退 P1。
4. **P4**：上游 PR（multi-link 能力、machineId 约定）。

---

## 10. 五分钟验证方案 A

在写代码之前，可以用现有 UI 验证“多客户端身份 → 多 Host 并行”这个前提：

1. 在 Lagoon 里给 Host A 建一个 DSH Remote profile，保存并连接；
2. 用同一个 Hub 账号、同一个密码，再给 Host B 建一个 DSH Remote profile（会注册第二个 client 身份）；
3. 两个 profile 都打开“自动连接”，首页切到“全部服务器”；
4. 预期：Hub 管理页出现两个 Lagoon client；两台 Host 同时 online；打开/刷新其中一台不影响另一台；
5. 如果 B 上线导致 A 掉线，说明两个 profile 误用了同一个 client 身份或 slot；检查 `remoteSlot` 与 profile id 是否唯一。

这个验证只证明“多身份并行”的协议前提，不覆盖 UI 分组与批量配对。

---

## 11. 需要确认

1. Hub 是自部署还是官方托管？（决定 P3 是否可用）
2. 能否给每台机器上的 Host 插件打补丁/升级？（决定 P2 是否可用）
3. 是否接受 Hub 管理页出现 N 个 Lagoon client？（方案 A 的代价）
4. 先做 P1 的哪一部分：多选配对、设备组 UI，还是两者一起？

---

## 参考

- 落地计划：[`DSH_DEVICE_GROUPING_PLAN.md`](DSH_DEVICE_GROUPING_PLAN.md)
- Lagoon：`Models.kt`、`RemoteSession.kt`、`RemoteBackend.kt`、`AllServersOverview.kt`、`LagoonController.kt`、`Screens.kt`。
- 上游：[`apps/server/src/gateway.ts`](https://github.com/liguobao/ds-harness-remote/blob/main/apps/server/src/gateway.ts)、[`packages/plugin/src/connection-controller.ts`](https://github.com/liguobao/ds-harness-remote/blob/main/packages/plugin/src/connection-controller.ts)、[`packages/plugin/src/client-runtime.ts`](https://github.com/liguobao/ds-harness-remote/blob/main/packages/plugin/src/client-runtime.ts)、[`packages/plugin/src/identity-store.ts`](https://github.com/liguobao/ds-harness-remote/blob/main/packages/plugin/src/identity-store.ts)。
