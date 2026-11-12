# 设备管理与分组

> 连接模型（接入源 → 设备 → Harness、身份、开关、流量契约）以 [`DEVICE_MODEL.md`](DEVICE_MODEL.md) 为准。
> 本文只讲界面行为与代码落点。

## 1. 页面结构

```text
设备
├─ 接入源
│    Remote Hub 账号卡片        → 账号管理（登录/退出、可接入主机、暂停中枢）
│    添加设备                   → 添加入口
├─ 由中枢管理
│    ● ubuntu-dev   3 个 Harness · 2 启用 · 在线      [设备开关]
├─ 由 SSH 管理
│    ● arch-dev     1 个 Harness · 在线               [设备开关]
├─ 直连（兼容）
└─ 设备组
```

- 一个设备一行；接入方式只是它的属性，不再按 OpenCode / DSH / Codex 分成不同种类的"设备"。
- 列表只显示**已采纳**的设备：中枢账号目录里出现但从未接入的主机不算设备，只出现在添加入口的候选里。
- 顶部的"N 台设备 · M 个 Harness 已启用"按 `HostBinding` 与 leaf 分别计数。

```text
设备详情（路由一层，id = deviceId）
  启用此设备 [switch]            ← 唯一的设备级开关
  接入方式 / 地址 / 指纹
  Harness  [刷新发现]
    DeepSeek Harness  已启用 [switch] >
    OpenCode          已停用 [switch]
    Codex             未检测到 / [添加]
  设备设置（编辑 SSH 连接 / 中枢与账号）
  移除这台设备
```

```text
Harness 详情（路由一层，id = leafId）
  启用此 Harness [switch]
  协议 / 所属设备 / 连接地址
  组合成员、任务通知、新会话默认值、模型显示
  连接与高级功能（DSH 主机扩展、模型密钥、服务商；Remote 网络与诊断）
  从此设备移除这个 Harness
```

### 1.1 设备子树的每一级都是路由

设备子树的每一级都记在 `SessionNavigation` 上（`device` / `harness` / `editors`，见 `ui/AppBackStack.kt`），
由 `MainActivity` 的根 `AnimatedContent` 统一渲染，动效规格只有 `HyperMotion.pageTransform` 一份：

- 进入：新页整幅从右侧推入，底层退 28% 并缩到 0.94；返回方向相反，底层不变透明。
- 每一级返回只退一层：SSH 表单 → 添加入口 → 设备详情 → Harness 详情 → 设备列表 → 会话首页。
- 预测式返回预览垫在下面的是真实的上一级。
- 不要再用 `detailId` / `showingAddDevice` 这类页面内布尔量切换整页：根过渡只认 `NavRoute` 的变化。
- `device` 只装 `HostBinding.id`，`harness` 只装 `ServerProfile.id`。恢复旧状态时允许把历史 profile id 解析一次。

## 2. 添加设备

顶部只有一个入口，页内分两个接入分支，产物都是同一种设备：

| 分支 | 流程 | 结果 |
|---|---|---|
| 经由中枢 | 登录 → 选主机（可多选）→ 核对指纹 | 每台勾选的主机是一台设备；**不默认启用任何 Harness** |
| 经由 SSH | 地址 / 用户名 / 凭据 → 核对主机指纹 | 一台设备；保存时**不预选 Harness、不连接、不启动远端服务** |
| 高级 · 兼容 HTTP 直连 | 地址或配对链接 | 1:1 的设备，直接进它的设备页 |

- 设备可以先存在而没有任何 Harness；那种状态不建立任何连接。
- 添加入口里的「已保存的 SSH 设备」点进去就是那台设备的页面，不是第二个表单。
- 具体 SSH 字段、bootstrap、Host Key、端口转发见 [`SSH_HOSTS.md`](SSH_HOSTS.md)。

## 3. 发现与启用 Harness

- 打开设备页**不会**自动连接：先显示上次的发现结果（带时间与过期标记），刷新是显式动作。
- 刷新会做一次只读发现：中枢读 Host 服务目录；SSH 读注册文件/命令。不安装、不启动、不终止进程。
- 设备停用时刷新直接拒绝，页面只显示缓存；这也是"停用后不再占用连接与流量"的一部分。
- 未挂载的 Harness 显示「添加」；添加即启用。已挂载的显示开关：关掉只是停用，不删除资料、会话、
  草稿或缓存；删除只在 Harness 详情的「从此设备移除这个 Harness」里发生。
- SSH 的只读发现只能证明"装了/注册了"，认证与版本在连接时验证；中枢则由 Host 明确声明的可用性决定。
  这些差异必须显示为原因，而不是假装"可用"或假装"空列表"。

## 4. 开关语义

| 层级 | 位置 | 说明 |
|---|---|---|
| 接入源（中枢） | 账号管理 | 「暂停此中枢」影响源下所有设备；退出登录同样会让设备不可用但保留 |
| 设备 | 设备页 / 列表行 | 关掉后不建中枢 Noise、不建 SSH、不发现、不刷新、不后台同步；Harness 开关原样保留 |
| Harness | 设备页 / Harness 详情 | 关掉后不拨号、不订阅、不轮询、不推送通知、不刷新缓存；资料与未读账本保留 |

- SSH **不再暴露接入源开关**：一台 SSH 配置就是一台设备，设备开关就是唯一的门禁。
- 关父级不覆写子项偏好；重新启用会立即建立一次监控并刷新（即使旧 `autoConnect` 为 false）。
- 全部连接禁用后不再安排 WorkManager 后台同步；设备管理仍保留所有行，便于重新启用。

## 5. 设备组

- 成员按**设备**选（`DeviceGroup.deviceIds`），保存时展开成该设备当前的 leaf 集合（`memberIds`）。
- 之后在组内设备上新启用 Harness，会自动进入分组（控制器在挂载/移除后重新展开）。
- 旧分组只有 leaf id：编辑器按"成员里有这台设备的任一 Harness"回填勾选，保存后升级为按设备。
- 一个设备可以属于多个组；空组不持久化；删除 Harness 会从组里剪掉，删除设备则整台退出分组。
- 分组只影响本机展示与首页范围，不上传服务端，也不改变认证、路由或会话身份。

## 6. 升级迁移

- `DeviceGroupCodec` v3 增加 `deviceIds`；v2 文档仍可读（只有 leaf 成员），v1 仍按旧规则重置一次。
- 连接图不可读/版本更高时失败关闭：不连接、不迁移、不覆盖原文件；设备列表退回 leaf 投影。
- 旧版直连 DSH 与直接 HTTP 直连保留为兼容入口，不再新建。

## 7. 代码落点

| 层 | 文件 |
|---|---|
| 设备/接入源/Harness 模型 | `docs/DEVICE_MODEL.md`、`shared/.../core/DeviceModel.kt` |
| 连接资料与启用字段 | `shared/.../models`（`ServerProfile`）、`app/.../core/ServerStore.kt` |
| 设备投影与发现缓存 | `shared/.../core/DeviceModel.kt`、`app/.../core/DeviceDiscoveryStore.kt` |
| 自动连接/监控 | `shared/.../AllServersOverview.kt` |
| 分组模型/编解码 | `shared/.../DeviceGroup.kt` |
| 分组存储/迁移 | `app/.../core/DeviceGroupStore.kt` |
| 设备列表 / 设备详情 / Harness 详情 | `app/.../ui/DeviceManagementScreen.kt` |
| 添加设备 / SSH 表单 / 兼容直连 | `app/.../ui/DeviceEditorScreen.kt`、`app/.../ui/SshHostForm.kt` |
| 分组编辑 | `app/.../ui/DeviceGroupsSheet.kt` |
| 控制器 | `app/.../core/LagoonController.kt` |
| 页面路由 | `app/.../ui/AppBackStack.kt`、`MainActivity.kt` |

## 8. 验证

- `DeviceProjectionTest`：一台设备多个 Harness、0 Harness、采纳规则、两级开关、发现槽位与过期。
- `DeviceSwitchSemanticsTest`：设备停用拒绝全部 leaf 且不改写子项偏好、单条 Harness 停用不影响兄弟、图不可读时失败关闭。
- `DeviceDiscoveryStoreTest`：往返、过期仍可读、损坏/未来版本拒绝覆盖、单设备清除。
- `DeviceGroupTest` / `DeviceGroupStoreTest`：v3 设备成员、v2 兼容、v1 只重置一次。
- `InformationArchitectureRegressionTest`：设备/ Harness 每一级都是路由，配置各归各位。
- `ANDROID_HOME=/home/lvziw/Android/Sdk ./gradlew :app:testDebugUnitTest :app:assembleDebug`
  全量通过。
