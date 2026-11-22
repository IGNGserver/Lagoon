# 设备管理与分组

> 设备模型、身份、开关与存储契约以 [`DEVICE_MODEL.md`](DEVICE_MODEL.md) 为准。本文描述纯 SSH 下的页面层级、交互与代码落点。

## 1. 页面结构

```text
设备（RootPage.DEVICES）
├─ 顶部统计：N 台设备 · M 个 Harness 已启用
├─ 设备列表（按设备名排序）
│    ● ubuntu-dev   user@192.168.1.10 · 2/3 Harness 在线          [设备开关]
│    ○ mac-mini     user@10.0.0.8 · 密钥待解锁                    [设备开关]
│    ○ arch-dev     user@arch.lan · 已停用                        [设备开关]
├─ SSH 密钥库（全局密钥管理入口）
└─ 设备组（仅引用设备 ID，新增 Harness 自动纳入）
```

- 点击右上角 `+` **一步直达 SSH 设备表单**（不再经过「选择接入方式」中转页）。
- 设备行直接呈现设备级状态（在线 Harness 比例、SSH 失败原因、指纹变更或密钥待解锁），不再只显示模糊的“未连接”。

```text
设备详情（路由一层，id = DeviceId）
  启用此设备 [switch]
  SSH 地址 / 端口 / 用户 / 认证 / 主机指纹（原样展示 SHA256:... + 复制）
  SSH 连接状态（实时显示连接/失败原因，行内提供「解锁密钥 / 核对指纹 / 重试连接」）
  Harness 列表  [刷新发现]
    ● DeepSeek Harness   已启用 · 在线         [switch] >
    ○ OpenCode           已启用 · 服务未运行   [启动服务] [switch] >
    ○ Codex              已发现 v0.x           [添加]
    ○ Antigravity        未检测到              [添加]
    ○ OpenCode ACP       已发现                [添加]
  编辑 SSH 连接 / SSH 密钥
  移除这台设备
```

```text
Harness 详情（路由一层，id = HarnessId）
  启用此 Harness [switch]
  协议 / 所属设备 / 连接状态与重试
  协议专属配置：
    • DSH：启动命令、默认工作目录、主机扩展、模型密钥、自定义模型服务商
    • OpenCode Native：注册文件路径、服务状态、「启动或重启服务」
    • Codex / Antigravity / OpenCode ACP：状态与能力边界
  使用方式：所属设备组、任务通知、新会话默认值（严格绑定当前 harnessId）、模型显示（严格绑定当前 harnessId）
  从此设备移除这个 Harness
```

### 1.1 路由与转场

设备子树的每一级都是 `SessionNavigation` 上的一层（`device` / `harness` / `editor`），并在 `MainActivity` 中拥有独立 `contentKey`（含 `harness` 层）与 `SaveableStateProvider`，确保「首页 → 设备列表 → 设备详情 → Harness 详情 / SSH 表单」每一级都具备一致的滑入动画与预测式返回。首次添加设备保存后统一落到 `RootPage.DEVICES` + `device = deviceId` 并立即在设备详情页触发只读发现。

## 2. 首页范围与新会话目标

- 首页范围面板（`HomeScopeSheet`）按 **全部设备 → 设备组 → 按设备分组的 Harness 列表** 展示：支持直接选中**某一台设备**作为首页聚合范围，也支持展开选中其下的单个 Harness（带 `HarnessBadge`，不再出现同机多 Harness 同名无法区分）。
- 首页范围胶囊、设置页摘要统一严格区分「N 台设备」与「M 个 Harness」。
- 运行时遇到 SSH 主机指纹未确认/变更、密钥需生物识别/口令解锁、或 OpenCode 服务未运行时，首页与对话页状态条直接给出对应操作按钮，原地修复并重连。

## 3. 设备组

- `DeviceGroup` 仅持久化 `deviceIds: List<String>`（`DeviceGroupCodec` v4），运行时由当前设备列表即时展开为有效 `HarnessId`。
- 一台设备可加入多个设备组；设备当前为 0 Harness 时分组依然保留；删除设备才将该设备移出分组。
- 全应用统一使用“设备组”术语。

## 4. 代码落点

| 层 | 文件 |
|---|---|
| 设备/Harness 模型与投影 | `shared/.../core/DeviceModel.kt` |
| 设备持久化与迁移 | `app/.../core/DeviceStore.kt`、`app/.../core/LegacyToSshMigration.kt` |
| Harness 运行态存储 | `app/.../core/ServerStore.kt`（运行态/草稿/项目/任务/通知） |
| 发现与分组存储 | `app/.../core/DeviceDiscoveryStore.kt`、`app/.../core/DeviceGroupStore.kt`、`shared/.../core/DeviceGroup.kt` |
| 设备与密钥协调器 | `app/.../core/DeviceCoordinator.kt`、`app/.../core/SshKeyCoordinator.kt` |
| SSH 运行时 | `app/.../core/ssh/*` |
| 页面与表单 | `app/.../ui/DeviceManagementScreen.kt`、`app/.../ui/SshHostForm.kt`、`app/.../ui/DshHostSettingsDialogs.kt`、`app/.../ui/DeviceGroupsSheet.kt` |
| 路由 | `app/.../ui/AppBackStack.kt`、`app/.../ui/MainActivity.kt` |
