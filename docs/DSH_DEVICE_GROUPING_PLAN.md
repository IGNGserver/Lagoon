# DSH 多设备接入与手动设备分组 · 开发方案

> **2026-11-05 更新**：分组模型已升级为 v2「全部 + 显式分组」，旧 v1 分组在升级时清空一次；
> 首页不再为未分组 profile 显示单例 tab，设备管理与添加设备页见
> [`DEVICE_MANAGEMENT.md`](DEVICE_MANAGEMENT.md)。SSH 主机见 [`SSH_HOSTS.md`](SSH_HOSTS.md)。
> 本文 M0–M4 保留为当时的实现记录，其中“未分组 profile 仍是 tab”“顶栏保留加号”等描述已过时。

本文是 [`DSH_REMOTE_MULTI_HOST.md`](DSH_REMOTE_MULTI_HOST.md) 的落地计划。目标是在**不修改 ds-harness-remote Hub、不给 Host 插件打补丁、不要求开发机改配置**的前提下：

1. 同时连接账号下的全部设备；
2. 由用户**手动**把多个 `ServerProfile`（OpenCode / DSH Remote）归为一个“设备”；
3. 顶栏按设备显示，设备内展示该设备全部 harness 的会话，并带 DSH/OpenCode 标记；
4. 设备内不同 harness 的同名工作区自动合并展示。

分组只属于本机展示层，不上传服务器、不改变任何连接/凭据/会话身份。

## 当前实现状态

- M0–M4 已在本工作树落地：`DeviceGroup`/`DeviceWorkspace`、本机分组存储、三态 `homeProjection`、`DeviceChips`、设备管理 sheet、harness 徽标、同名工作区合并、组内工作区范围与新建会话选择；设备编辑器另提供“同时连接全部成员”开关（逐 profile 写 autoConnect）。
- M5 的 Host 目录/批量注册/revoke 已由工作树中的 `RemoteCatalog` / `RemoteAccountManager` 提供；设备组只负责把新增 profile 纳入手动分组。
- 有设备组时顶栏默认只显示设备 chip；“全部”移到 ⋯ → “全部设备”，选中全局模式时才出现。无设备组时保持原来的“全部 + 服务器”布局。
- 已通过 `ANDROID_HOME=/home/lvziw/Android/Sdk ./gradlew :app:testDebugUnitTest :app:assembleDebug`；真机上的分组/工作区交互仍需按第 9 节验收。
- 未做自动分组、未改 Remote Hub/Host 插件、未新增服务端字段。

## 里程碑一览

| 阶段 | 内容 | 估算 |
|---|---|---|
| M0 | 设备组/工作区模型、持久化、纯函数测试 | 2–3 人日 |
| M1 | 三态首页投影、顶栏设备 chip、选中设备 | 3–4 人日 |
| M2 | 设备管理页：建组、改名、加减成员、删除 | 2–3 人日 |
| M3 | 组内会话/状态聚合、harness 徽标、连接复用 | 3–4 人日 |
| M4 | 同名工作区合并、范围过滤、新建会话选 harness | 4–5 人日 |
| M5 | DSH Remote 一次登录批量添加 Host（复用/完善在途实现） | 1–4 人日 |
| M6 | 硬化、真机验收、文档 | 3–5 人日 |

合计约 **20–28 人日**；只做 M0–M3 的 tab MVP 约 **8–12 人日**；包含 M4 工作区合并的目标版本约 **12–17 人日**。

---

## 1. 范围与非目标

### 1.1 范围

- 新增本机 `DeviceGroup`（设备组）模型与持久化；
- 顶栏从“每 profile 一个 chip”改为“每设备组一个 chip”；
- 设备组内聚合会话、工作区、状态、未读与新建会话目标；
- 会话行/对话页显示 harness 徽标；
- 设备组内按规范化名称合并工作区；
- DSH Remote 批量添加 Host（一次登录、多选、每 Host 一个 client 身份）；
- 现有无分组用户完全保持原行为。

### 1.2 非目标

- 不做自动设备分组（hostname / machineId / IP / 路径启发式都不做）；
- 不修改 Remote Hub 或 Host 插件；
- 不新增插件、不要求 `DSH_HOME`/环境变量改动；
- 不把设备组同步到服务器或其他手机；
- 不在设备组内合并不同 harness 的模型目录、终端、文件上传等运行环境（只聚合会话/工作区视图）；
- 不改变“全部”全局聚合模式的语义。

---

## 2. 用户流程

### 2.1 创建设备组

```text
首页 ⋯ / 设置 → 设备 → 新建设备
  → 输入名称（例如 MacBook）
  → 多选已有服务器资料：
       MacBook 的 OpenCode
       MacBook 的 DSH Remote
  → 保存
```

保存后：

- 顶栏原来的两个 chip 合并成一个 `MacBook`；
- 未归组的 profile 仍各自显示为单独 chip；
- 现有“全部”chip 保留为全局聚合入口。

### 2.2 选择设备与 harness

```text
顶栏：全部 | MacBook | Studio
点 MacBook：
  - 首页聚合 MacBook 组内全部 profile 的会话；
  - 每条会话显示 DSH / OpenCode 徽标；
  - 组内工作区按名称合并；
  - “新建会话”先选 harness（组内只有一个在线时自动选）。
```

点某条会话时按该会话自己的 `serverId` 打开，不会串到同组另一台 harness。

### 2.3 编辑设备组

- 重命名；
- 添加/移除成员；
- 删除设备组：只删除分组关系，不删除任何服务器资料；
- 把一个 profile 从 A 移到 B；
- 删除服务器资料时自动从所有组移除。

### 2.4 同时在线

- 设备组只是展示层，不改变连接层；
- 组内每个 profile 仍独立连接、独立重连、独立 token；
- 组创建/加成员时提供“同时连接全部成员”（默认勾选，不静默覆盖成员已有的 `autoConnect` 开关）；
- DSH Remote 批量添加时，每个 Host 注册自己的 client 身份（不共享身份，不需要 Hub 补丁）。

---

## 3. 数据模型与持久化

### 3.1 设备组

```kotlin
data class DeviceGroup(
  val id: String,                    // 本机 UUID
  val name: String,                  // 用户输入，允许重名
  val memberIds: List<String>,       // ServerProfile.id，有序
  val updatedAt: Long = 0
)
```

约束：

- 一个 `ServerProfile.id` 最多属于一个组；
- 空组不持久化（删除最后一个成员即删除组）；
- 成员 profile 被删除时，下一次加载自动剪掉；
- 组名只用于展示，不参与路由/鉴权。

### 3.2 状态

`LagoonState` 新增：

```kotlin
val deviceGroups: List<DeviceGroup> = emptyList()
val homeGroupId: String? = null     // 当前首页设备组；null = 旧行为
```

派生：

```kotlin
val homeGroup: DeviceGroup? get() = deviceGroups.firstOrNull { it.id == homeGroupId }
val homeGroupProfiles: List<ServerProfile> get() = homeGroup?.memberIds?.mapNotNull { id -> profiles.firstOrNull { it.id == id } }.orEmpty()
val multiServerView: Boolean get() = allServers || homeGroup != null
```

- `serverId` 仍是“当前可写连接”的 profile，不改成组 ID；
- `homeGroupId` 只控制首页投影；
- `allServers=true` 时忽略 `homeGroupId`，保持全局聚合；
- 无分组时 `homeGroupId=null`，现有逻辑不变。

### 3.3 持久化

新增 `DeviceGroupStore`（Android SharedPreferences，独立文件 `device-groups`）：

```json
{
  "version": 1,
  "groups": [
    {
      "id": "…",
      "name": "MacBook",
      "members": ["profile-a-opencode", "profile-a-dsh"],
      "updatedAt": 1730000000000
    }
  ],
  "selected": "…"
}
```

要求：

- 原子写入（`commit()`），失败给出用户可见错误；
- 解码失败不崩溃：记录诊断并回退空列表；
- 重复成员、跨组重复、未知 profile id 在加载时清理；
- 不存任何密钥/Token。

---

## 4. 投影架构

### 4.1 设备组首页投影

`homeProjection()` 目前处理单服务器与 `allServers`。新增第三态：

```text
单服务器：homeGroupId = null, allServers = false
设备组：  homeGroupId != null, allServers = false
全部：    allServers = true（忽略 homeGroupId）
```

设备组投影复用 `allServers` 的聚合逻辑，但只取组内成员的快照：

```kotlin
val snapshots = serverSnapshots()
  .filterKeys { it in group.memberIds }
```

聚合内容：

- `sessions`：组内全部会话，保留 `serverId`；
- `projects`：组内全部项目，保留 `serverId`；
- `tasks/notices/permissions/questions/backgroundRunning`：按组聚合；
- `allSessionStatuses` / `allUnseenSessions`：只含组内成员；
- `connected/loading/streamConnected`：组内任一成员；状态点区分“全部在线/部分在线/全部离线”；
- `serverId`：保留当前可写 profile，不置空；
- `scopeProjectId`：组切换时清空，避免指向其他组的工作区。

建议把 `allServers` 与 `homeGroupId` 的聚合抽成同一个 `aggregate(snapshots, selectedProfile)`，避免两套逻辑漂移。

### 4.2 工作区按名称合并

新增纯投影：

```kotlin
data class DeviceWorkspace(
  val key: String,                 // "group:<groupId>:<normalizedName>"
  val name: String,
  val members: List<Project>,      // 每个成员保留 serverId / directory
  val sessions: List<Session>
)
```

规则：

- 名称规范化：Unicode NFKC → trim → 连续空白折叠 → 小写（`Locale.ROOT`）；
- 名称为空时回退到目录 basename；
- 同一设备组内，规范化名称相同 → 合并为一个 `DeviceWorkspace`；
- 不同目录但同名也合并（用户明确要“按名称”）；详情里展示各成员路径；
- 会话先通过 `resolveSessionProject(session, projects)` 找到所属项目，再映射到 workspace key；
- 找不到项目的会话进入该组的“未归入项目”；
- 只在设备组视图启用名称合并；`allServers` 全局模式仍按 server-scoped project 分组，避免把不同设备的同名项目误并。

### 4.3 过滤与新建目标

- 新增 `scopeWorkspaceKey: String?`（与 `scopeProjectId` 分开，避免污染单服务器语义）；
- `HomeScope.roots/counts/byActivity` 增加 group-aware 重载，按 `workspaceOf(session)` 过滤；
- “新建会话”：
  - 先确定目标 `DeviceWorkspace`；
  - 若组内多个成员，弹出 harness 选择（同一 workspace 的多个 `Project` 作为候选）；
  - 默认使用该 workspace 最近使用的 profile，否则第一个在线成员；
  - 目标目录取所选 `Project.directory`；
  - 组内只有一个成员时跳过选择，保持旧体验。

---

## 5. UI 改造

### 5.1 顶栏

`ServerChips` 改为 `DeviceChips`：

- 数据源：显式设备组 + 未归组 profile 的隐式单例组；
- 排序：显式组按最近活动/用户顺序；未归组 profile 按现有顺序；
- 标签：组名或 profile.name；
- 状态点：
  - 全部成员在线：Success；
  - 部分在线/正在连接：Warning；
  - 全部离线：灰色；
- 点击：`controller.selectDevice(groupId)`；
- 长按：编辑设备组（单例组则编辑服务器资料）；
- `+`：添加服务器；菜单增加“新建设备”。

无任何分组时，渲染结果与今天完全一致，避免回归。

### 5.2 设备首页

`HomeTopBar` 标题：

- `allServers`：全部服务器；
- 设备组：组名；
- 单服务器：全部会话/项目名（现状）。

组内不默认按 harness 过滤；所有成员会话混排，靠徽标区分。搜索范围包含组内全部会话。

### 5.3 会话徽标

新增 `HarnessBadge`：

- `OPENCODE` → `OpenCode` / `OC`；
- `DSH` / `DSH_REMOTE` → `DSH`（Remote 可加小字 `Remote`）；
- 同一组内有两个 DSH 时，徽标旁显示 profile 别名（如 `DSH · desktop`）。

显示位置：

- 首页会话行；
- 已归档行；
- 对话页副标题；
- 通知标题（可选，建议做）。

2026-11-06 补充：首页 / 已归档行在徽标之后增加 `设备名 · 所属工作区`，单服务器只显示工作区名；
工作区解析与去重规则见 [`docs/HOME_REDESIGN.md`](HOME_REDESIGN.md)。标签只做展示，不参与筛选、
项目计数或新建目标。

### 5.4 工作区分组

- 首页“分组方式 → 项目”在设备组内使用 `DeviceWorkspace`；
- 工作区列表/范围选择器显示合并后的名称、成员数、成员路径；
- 选择某工作区 → 只显示该工作区会话；
- 新建会话 → 选择 workspace + harness；
- 保留“日期/状态”分组方式，不受影响。

### 5.5 设备管理页

新增 `DeviceGroupsScreen`（从设置和顶栏菜单进入）：

- 设备组列表：名称、成员数、在线数；
- 新建设备：名称 + 多选 profile；
- 编辑：重命名、添加/移除成员、移动成员；
- 删除：仅解除分组，成员回到未分组；
- 未分组 profile 区块；
- 空态说明“设备分组只影响本机显示，不会修改远端”。

---

## 6. 多设备同时连接

### 6.1 当前已具备

- `AllServersOverview` 按 profile 建独立 monitor；
- 每个 profile 独立 `RemoteSession` / 连接 / 重连；
- `autoConnect` 已控制是否持续监控。

### 6.2 本方案需要补的

1. **批量添加 DSH Remote Host**（否则每台 Host 都要重新登录一次）：
   - 当前工作树已有未提交的 `RemoteAccountManager` / `RemoteCatalog` 实现：登录/QR、Host 目录、每 Host 独立 slot 注册、指纹核对、revoke 基本齐全；
   - 本方案优先复用它，只补：多选 Host UI、创建 profile 的幂等/回滚、与设备组创建衔接、测试；
   - 如果该在途实现最终未合入，再按 `RemoteSetup.discover/enroll` 落地：一次登录返回 account token + Host 列表，每个 Host 独立 `RemoteDeviceManager` slot，复用同一个 account token；
   - 幂等：`(origin, account, hostId)` 已存在则复用 profile/identity；
   - 失败回滚：注册中途失败时，尽量 revoke 已创建但未落盘的 client 设备。
2. **组级连接开关**：
   - 新建设备/加成员时提供“同时连接全部成员”（默认勾选，可在保存前取消）；
   - 组菜单提供“同时连接全部成员/仅按需连接”，内部仍是逐 profile 设置，不覆盖用户已手动关闭的开关。
3. **避免同一 profile 重复连接**：
   - `testServer` 与 monitor 目前可能各开一条同身份连接，Host 会替换旧连接；
   - 增加连接注册表/复用：编辑已保存的 DSH Remote profile 时优先复用 `allOverview.client(profile.id)`，或在测试期间临时停 monitor；
   - 这是多 Host 后更明显的边缘问题。
4. **并发上限**：
   - 建议默认最多同时监控 16 个 profile；
   - 超出时按最近使用/手动置顶排序，其余显示“未连接”；
   - 启动错峰（每 profile 间隔 200–500ms），避免 N 个 REST/WS 同时打 Hub。

---

## 7. 代码落点

### 7.1 shared（平台无关，纯逻辑可测）

| 文件 | 改动 |
|---|---|
| `shared/.../DeviceGroup.kt`（新增） | `DeviceGroup`、`DeviceWorkspace`、`normalizedWorkspaceName`、组内工作区投影 |
| `shared/.../SessionPresentation.kt` | 增加按 workspace 解析的 helper；不改现有 `resolveSessionProject` 语义 |
| `shared/.../RemoteCatalog.kt`（已有/在途） | `RemoteHostMeta` / `RemoteCatalog` 直接复用；**不要重复定义** |
| `app/.../core/RemoteAccountManager.kt`（已有/在途） | 登录、Host 发现、批量注册、revoke；本方案只补 UI/组衔接/测试 |
| `shared/.../RemoteSetup.kt` | 仅当 `RemoteAccountManager` 未合入时补 `discover` / `enroll` |
| `shared/.../RemoteServerApi.kt` | 已有 `revokeSelf` / `oauthProviders`；勿重复实现 |

### 7.2 app/core

| 文件 | 改动 |
|---|---|
| `app/.../core/DeviceGroupStore.kt`（新增） | SharedPreferences 持久化、版本、损坏回退、成员剪枝 |
| `app/.../core/LagoonController.kt` | `deviceGroups/homeGroupId` 状态；`selectDevice`；组 CRUD；删除 profile 时清成员；连接最近使用成员；test/monitor 连接复用。已有在途的 `remoteSlot(profile)` / `addRemoteProfiles` 直接复用/完善 |
| `app/.../core/ServerProjection.kt` | 三态 `homeProjection`；`multiServerView`；`homeReadSnapshots`/`homeUnseenSessions` 组内过滤；`deviceWorkspaces` |
| `app/.../core/HomeSections.kt` | 设备组“项目”分组使用 `DeviceWorkspace` |
| `app/.../core/HomeScope.kt` | 组内范围过滤、计数、新建目标 |
| `app/.../core/ServerStore.kt` | profile 删除后组数据清理（由 controller 调用即可，store 不必感知） |

### 7.3 app/ui

| 文件 | 改动 |
|---|---|
| `app/.../ui/SessionsHomeScreen.kt` | `ServerChips` → `DeviceChips`；组标题/状态点；会话徽标；工作区分区 |
| `app/.../ui/MainActivity.kt` | 设备组选择、新建会话的 workspace/harness 选择、返回恢复组、组管理入口 |
| `app/.../ui/DeviceGroupsScreen.kt`（新增） | 设备组列表/新建/编辑/删除/移动成员 |
| `app/.../ui/DeviceGroupSheets.kt`（新增） | 建组、选成员、移动成员、重命名 |
| `app/.../ui/Screens.kt` | DSH Remote 多选 Host、指纹批量确认 |
| `app/.../ui/ProjectSheets.kt` | 组内工作区范围选择 |
| `app/.../ui/LagoonComponents.kt` 或新 `HarnessBadge.kt` | DSH/OpenCode 徽标 |
| `app/.../ui/ChatScreen.kt` / `ArchivedScreen.kt` | 副标题/行徽标 |
| `app/.../ui/ConnectionStrip.kt` | 组级连接摘要（可选） |
| `app/.../system/TaskNotifications.kt` / `LiveUpdateContent.kt` | 通知里的“设备 · harness”标签（可选） |

### 7.4 关键接口

```kotlin
class DeviceGroupStore(prefs: SharedPreferences) {
  fun load(): DeviceGroupState                 // groups + selectedGroupId
  fun save(state: DeviceGroupState): Boolean
}

data class DeviceGroupState(
  val groups: List<DeviceGroup>,
  val selectedGroupId: String?
)

// LagoonController
fun selectDevice(groupId: String?, profileId: String? = null)
fun createDevice(name: String, memberIds: List<String>)
fun updateDevice(id: String, name: String, memberIds: List<String>)
fun deleteDevice(id: String)
fun moveProfile(profileId: String, groupId: String?)

// 纯投影
fun LagoonState.deviceWorkspaces(): List<DeviceWorkspace>
fun LagoonState.homeProjection(): LagoonState   // 新增 homeGroupId 分支
```

### 7.5 依赖顺序

```text
M0 DeviceGroup/Store
  ├─ M1 三态 homeProjection + DeviceChips
  │    └─ M3 组内聚合 + 徽标
  │         └─ M4 工作区按名称合并
  └─ M2 设备管理页
M5 批量添加 Host（可并行，依赖 RemoteSetup）
M6 硬化/测试/文档
```

---

## 8. 分阶段任务

以下按 PR 拆分，便于 review 和回滚。估算按 1 名熟悉该代码库的 Android 开发者计算。

### M0 · 基础模型与存储（2–3 人日）

- 新增 `DeviceGroup`、`DeviceWorkspace`、名称规范化纯函数；
- 新增 `DeviceGroupStore`（SharedPreferences，版本 1）；
- `ServerStore`/`LagoonController` 删除 profile 时清理组成员；
- 单元测试：编解码、损坏回退、跨组重复、未知 profile、空组。

验收：无 UI 变化；单测通过。

### M1 · 顶栏设备 chip 与选择（3–4 人日）

- `LagoonState` 增加 `deviceGroups/homeGroupId`；
- `homeProjection()` 支持设备组三态；
- `ServerChips` → `DeviceChips`；
- `controller.selectDevice(groupId)`：设置 `homeGroupId`，连接最近使用的成员；
- 持久化当前选中的设备组；
- 无分组时行为与今天一致。

验收：手工造 2 个组，顶栏只显示 A/B（“全部”仍保留）；切换组首页会话范围正确；无组用户无回归。

### M2 · 设备管理页（2–3 人日）

- 新建设备（名称 + 多选 profile）；
- 重命名、加/移成员、删除组；
- 未分组区块；
- 长按 chip 进入编辑；
- 删除服务器资料时同步更新 UI。

验收：A 组可装入 OpenCode + DSH；成员可在组间移动；删除组不删服务器。

### M3 · 组内聚合与徽标（3–4 人日）

- `homeProjection` 聚合组内 sessions/projects/tasks/notices/statuses；
- `multiServerView` 替换 UI 中需要“多服务器语义”的 `allServers` 判断；
- `homeReadSnapshots()`/`homeUnseenSessions()` 在设备组模式下过滤到组内成员；
- 会话行/归档/对话副标题增加 `HarnessBadge`；
- 组状态点（全部/部分/离线）；
- 修复 `testServer` 重复连接（见 6.2）。

验收：A 组内 OpenCode 与 DSH 会话同屏、徽标正确、状态独立；一台离线不影响另一台。

### M4 · 工作区按名称合并（4–5 人日）

- `DeviceWorkspace` 投影；
- `HomeSections`/`HomeScope` group-aware 重载；
- 工作区范围选择器/“项目”分组；
- 新建会话的 workspace + harness 选择；
- 同名不同目录的展示与新建目标处理。

验收：A 组两个 harness 都有 `Lagoon` 工作区 → 首页只显示一个 `Lagoon` 分区，会话带各自徽标；新建会话能选 harness 和目标目录。

### M5 · DSH 批量添加 Host（3–4 人日；在途实现合入后 1–2 人日）

- 优先复用 `RemoteAccountManager` / `RemoteCatalog`（当前工作树未提交实现）；
- 多选 Host UI、指纹一次确认、已添加标记；
- 每 Host 独立 client 身份，不共享 slot；
- 失败回滚、删除 profile 时 revoke；
- 组创建时可直接把新 Host 加入指定设备（可选）。

验收：一次登录添加 3 台 DSH Host；三台同时在线；若服务端管理页展示 client 设备，应看到 3 个 Lagoon client；删除其中一台只影响该台。

### M6 · 硬化、测试、文档（3–5 人日）

- 组投影/工作区/批量的边界测试；
- 性能：组内 8–16 个 profile 的首页滚动与事件更新；
- 真机验收矩阵；
- 文档更新与 release note。

总估算：**20–28 人日**（约 4–6 周，1 人）；若在途的 `RemoteAccountManager` 直接合入，M5 降为 1–2 人日，合计约 **18–26 人日**。如果先不做 M5、M4，只做“设备 tab + 手动分组 + 徽标”，MVP 约 **8–12 人日**。

---

## 9. 测试计划

### 8.1 单元测试

- `DeviceGroupStoreTest`：编解码、损坏、重复成员、跨组重复、未知 profile、空组。
- `DeviceGroupProjectionTest`：组内 sessions/projects/status 聚合；选中 profile 的 live state 覆盖快照；部分离线；空组。
- `DeviceWorkspaceTest`：名称规范化（大小写/空白/全半角）、同名不同目录、空名称、未归入项目、跨组不合并。
- `HomeSectionsGroupTest`：项目分组使用 workspace；
- `HomeScopeGroupTest`：范围过滤、新建目标；
- `DeviceChipsTest`（纯函数）：显式组 + 未分组单例、排序、状态点。

### 8.2 集成/回归

- `AllServersOverview` 多 profile 并行监控不受分组影响；
- `RemoteMultiHostTest`：两个 client 身份连两个 Host，互不踢；
- `testServer` 与 monitor 不重复连接同一 Host；
- 现有 `HomeScopeTest`、`HomeSectionsTest`、`ServerProjectionTest`、`AllServersAggregationTest` 全绿；
- 无分组旧路径回归。

### 8.3 真机验收

1. A 设备：OpenCode + DSH Remote；B 设备：DSH Remote；
2. 创建 A/B 两个设备组；
3. 顶栏只有 A/B（“全部”若保留则另计）；
4. A 内两类会话同屏、徽标正确；
5. 两个 harness 都有同名工作区时合并为一个分区；
6. 新建会话能选择 harness，落到正确 profile/directory；
7. 断网/重启一台 Host，另一台不受影响，组状态点显示部分在线；
8. 删除组 → 成员回到未分组；删除服务器 → 组内成员消失；
9. 冷启动/旋转/进程重启后分组与选中设备保持；
10. 多组 8+ profile 时首页滚动和实时更新无明显卡顿。

### 8.4 仓库验证

按 `AGENTS.md`：

```sh
ANDROID_HOME=/home/lvziw/Android/Sdk ./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Remote 相关单测可先跑：

```sh
ANDROID_HOME=/home/lvziw/Android/Sdk ./gradlew :app:testDebugUnitTest --tests 'com.igng.opencode.lagoon.core.Remote*Test'
```

---

## 10. 风险与边界

| 风险 | 处理 |
|---|---|
| 设备组投影与 `allServers` 逻辑漂移 | 抽出同一个 aggregate 函数；两态共用测试 |
| `allServers` 判断散落在 UI | 增加 `multiServerView`，逐个替换需要“多服务器语义”的位置；保留“全部模式”文案单独判断 |
| 同名工作区不同目录被合并 | 用户明确要求按名称；详情展示成员路径；新建会话必须选 harness/目录；后续可加“按路径区分”开关 |
| `Session.projectId` 跨服务器冲突 | workspace 投影始终保留 `serverId`；key 带 groupId；不跨组复用 |
| 组内多 profile 重复连接同一 Host | 连接注册表/复用 `allOverview.client`；避免 test 与 monitor 各开一条 |
| N 个 profile 耗电/耗流量 | 并发上限、启动错峰、组级按需连接、后台策略沿用现有服务 |
| 批量注册中途失败 | 先注册后落盘；失败 revoke 已创建设备；至少不留下“有身份无 profile” |
| 删除 profile 留下组成员 | `deleteServer` 同步清理组；加载时再剪枝 |
| 分组数据损坏 | 不崩溃、回退空组、诊断日志；不影响服务器资料 |
| “全部”chip 与用户预期不一致 | 默认保留全局“全部”；若要求严格只有 A/B，把它收进菜单（见待确认） |
| 置顶/折叠 section 目前按单服务器 key 保存 | 组模式要按 profile 作用域保存 scoped key；MVP 可暂时隐藏置顶动作，不能让 A 组的 key 误作用到 B 组 |
| “全部已读/服务端已读”按 `homeReadSnapshots()` 取范围 | 设备组模式要过滤到组内成员，否则会清到其他设备的未读 |
| `scopeProjectId` 与新的 `scopeWorkspaceKey` 语义混用 | 新字段独立；切组/切工作区时同时清空两者，避免旧范围泄漏 |
| 返回键/导航仍以 profile 为 `navigator.server` | `homeGroupId` 独立保存；从会话返回时恢复原设备组，不要退回未分组首页 |
| 组内同一 profile 被两个 chip 同时选中 | 组模型保证一个 profile 只属于一个组；UI 只渲染显式组 + 未分组单例，避免重复项 |

---

## 11. 发布与回滚

- 无分组用户走旧路径，功能可安全上线；
- 建议按 M0→M1→M2→M3→M4→M5 分 PR 合并，每个 PR 保持可编译、可回滚；
- 分组数据独立 SharedPreferences，回滚版本最多丢失分组关系，不影响服务器资料/凭据；
- 批量添加 Host 的回滚要 revoke 已注册 client 设备，避免服务端残留；
- 发布前更新 `docs/releases/<tag>.md`（按 `docs/RELEASE_POLICY.md`）。

---

## 12. 待确认

1. **“全部”chip 是否保留**：建议保留为全局聚合；如果要求顶栏严格只有 A/B，可把它移到 ⋯ 菜单。
2. **工作区合并键**：按你确认的“名称”合并；同名不同目录也合并。是否需要在工作区详情里强制选 harness/目录，建议需要。
3. **批量添加 DSH Host 是否进第一版**：不做也能用（逐台添加），但“同时连接全部设备”的体验会差很多。
4. **组级连接策略**：默认“加入组即 autoConnect”，是否允许组级“仅按需连接”。
5. **分组顺序**：按最近活动自动排序，还是允许手动排序；首版建议自动排序。
