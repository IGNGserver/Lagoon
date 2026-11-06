# 设备管理与分组（v2）

本文记录 2026-11 的设备管理重构：首页从“每个连接一个服务器胶囊”改成统一的左上角范围面板，
DSH 的添加入口收进一个分页，原有直连 DSH（地址 + 启动令牌）不再作为新连接方式提供。

## 1. 目标信息架构

```text
会话首页
[全部设备 ⌄]                           ← 唯一范围入口，没有横向胶囊行

设备管理
  全部设备                        ← 聚合所有 profile
  DSH Remote Hub                  ← 一个账号，刷新/退出/接入主机
  设备分组
    MacBook  2 个设备 · 1 个 Harness · 2 在线
    + 新建设备组
  未分组
    设备：DSH Remote Host / SSH 主机
    Harness：OpenCode / 旧版直连 DSH
  添加设备
```

- **全部** 是隐式聚合，不是可编辑分组；它始终存在，且包含所有 profile（包括已分组和未分组）。
- 未分组 profile **不再**在首页单独占一个 tab；它们仍出现在「全部」和设备管理页。
- 首页设备条末尾的 `＋` 已删除。添加设备只能从「设备管理」或「设置 › 添加设备」进入。
- 首页标题打开的「选择范围」面板是唯一的设备/分组/项目/工作区入口：全部设备、设备分组、单台连接都在这里；单台设备范围不再出现临时胶囊，回到顶部选「全部设备」即可。

## 2. 添加设备页

顶部两个分页：

| 分页 | 内容 |
|---|---|
| OpenCode | 原有 OpenCode 资料表单：地址/pair 链接、Basic Auth、自动连接、通知 |
| DeepSeek Harness | 一个 DSH Remote Hub + 任意数量 SSH 主机 |

DSH 分页不再提供“直连 DSH”的地址/令牌输入。已有旧版直连 DSH 资料会继续出现在设备管理
的 Harness 区域，可编辑或删除，但新版本不再创建这类资料。

### DSH Remote Hub

- 一次登录一个 Hub 账号；设备管理只展示最近保存/更新的 Hub catalog。
- 登录后批量发现主机，逐台核对 Host 指纹，再为每台主机注册独立 client 身份（沿用
  `RemoteAccountManager` 的多 Host 并行模型）。
- Hub 下的每台 Host 是独立设备/profile；不会退化成 dsh-Go 的“一次只连一台”。
- Host 名称只在“设备”范围内去重：OpenCode / 旧版直连 DSH 属于 Harness，和设备同名不会再让
  设备被保存成 `ubuntu-dev (2)`。旧版本已经写入的这种后缀会在启动时按 Hub 目录自动纠正；
  只有同一分区里确实重名的第二台设备才保留 `(2)`。

### SSH 主机

- 每台 SSH 主机对应一个 DSH profile（`sshHostId`），连接、通知、会话路由都复用现有
  `HarnessClient` 管线。
- 保存数量没有产品上限；默认自动连接，按需在编辑页关闭。
- 具体字段、bootstrap、Host Key、端口转发见 [`SSH_HOSTS.md`](SSH_HOSTS.md)。

## 3. 分组语义

- 分组只保存 `ServerProfile.id`，不复制连接、凭据或会话。
- UI 按 profile 推导角色：
  - `DSH_REMOTE` / `sshHostId != null` → 设备；
  - `OpenCode` / 旧版直连 DSH → Harness。
- 一个 profile 最多属于一个组；空组不持久化；删除 profile 会从组里剪掉。
- 新建/编辑分组时可勾选「同时连接全部成员」，默认开启；关闭后仅有当前活动连接保持监控。
- 分组只影响本机展示，不上传服务端，不改变认证、路由或会话身份。

## 4. 禁用连接

设备管理的每个连接行都有一个启用开关；「设备」和「Harness」两种角色都支持禁用。
禁用是本机偏好，不删除资料、不上传服务端，也不改变远端任务。

- 持久化：`ServerProfile.enabled` 默认 `true`；旧版资料没有该字段时按启用处理。
- 禁用后该 profile 不再被 `AllServersOverview` 拨号，即使它被当前选中、被设备组
  「同时连接全部成员」覆盖或被旧通知/深链引用。当前连接会被关闭，SSH 主机链接也会关闭。
- 首页、设备组、归档、搜索、全局任务行、灵动态/系统通知和「服务端已读」候选都通过
  `ServerProjection` 过滤禁用 profile，因此不会再显示它的对话和计数。
- 禁用时撤下该服务器的结果/待处理通知；本机未读账本、草稿、会话缓存和凭据保留。
  重新启用后未读会回到应用内列表，但第一次快照只作为基线，不会重放旧系统通知。
- 如果禁用的是当前设备：优先切到当前设备组的其它启用成员；单设备模式切到其它启用连接；
  没有可用连接时回到「全部」空首页。关闭的设备组保留成员关系，重新启用后自动回到组内。
- 重新启用会立即建立一次监控并刷新（即使该 profile 的「自动连接」是关闭的），首页先显示
  缓存、再更新实时数据；当前没有可写连接时会自动接回该 profile。
- 全部连接禁用后不再安排 WorkManager 后台同步；设备管理仍保留所有行，便于重新启用。

## 5. 升级迁移

`DeviceGroupStore` 从 v1 升到 v2：

1. 读到 v1 时丢弃旧分组和旧选中项，立即写入 v2 空文档；
2. 本次启动显示一次“设备分组已按新版规则重置”的 toast 和设备管理页 banner；
3. 第二次启动读取 v2，不再清空；
4. profile、凭据、会话缓存、通知账本全部保留。

首页默认进入「全部」。`homeGroupId == null` 即全部；选中显式分组时 `allServers = false`。
旧版的 `allServers` 偏好不再决定首页形态，只作为兼容字段保留。

## 6. 代码落点

| 层 | 文件 |
|---|---|
| 连接资料/启用字段 | `shared/.../Models.kt`、`app/.../core/ServerStore.kt` |
| 自动连接/监控 | `shared/.../AllServersOverview.kt` |
| 分组模型/编解码 | `shared/.../DeviceGroup.kt` |
| 分组存储/迁移 | `app/.../core/DeviceGroupStore.kt` |
| 首页投影/过滤 | `app/.../core/ServerProjection.kt` |
| 首页范围面板 | `app/.../ui/ProjectSheets.kt`、`app/.../ui/SessionsHomeScreen.kt` |
| 设备管理页 | `app/.../ui/DeviceManagementScreen.kt` |
| 分组编辑 | `app/.../ui/DeviceGroupsSheet.kt` |
| 添加设备页 | `app/.../ui/AddDeviceSheet.kt` |
| 控制器 | `app/.../core/LagoonController.kt` |
| 通知/深链入口 | `app/.../system/NotificationActionReceiver.kt`、`app/.../ui/MainActivity.kt` |
| 页面路由 | `app/.../ui/AppBackStack.kt`、`MainActivity.kt` |

## 7. 验证

- `DeviceGroupTest` / `DeviceGroupProjectionTest`：v2 codec、只显示显式分组、未分组 profile、
  全部聚合、角色推导。
- `DeviceGroupStoreTest`：v1 只重置一次、v2 往返、损坏回退。
- `DisabledProfileTest`：`enabled` 旧资料默认值/往返/凭据保留；禁用快照不进入首页和汇总；
  单设备回退、全禁用空首页、设备组只统计启用成员；禁用 profile 即使被 `include` 也不拨号，
  重新启用后恢复连接。
- `ANDROID_HOME=/home/lvziw/Android/Sdk ./gradlew :app:testDebugUnitTest :app:assembleDebug`
  全量通过。
