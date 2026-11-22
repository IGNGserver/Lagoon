# 设备模型（纯 SSH：设备 → Harness）

本文是 Lagoon 连接域的**架构契约**。改设备、Harness 的模型或页面层级之前，先改本文并在 PR 里说明改了哪一条；不要只改代码。

Lagoon 从今往后**只支持经由 SSH 连接设备**。历史上的 DSH Remote 中枢模式与 HTTP 直连模式已彻底移除（见 [`REMOTE_EOL.md`](REMOTE_EOL.md)）。界面细节见 [`DEVICE_MANAGEMENT.md`](DEVICE_MANAGEMENT.md)，SSH 协议与安全细节见 [`SSH_HOSTS.md`](SSH_HOSTS.md)，Codex 与 Antigravity 边界见 [`CODEX.md`](CODEX.md) 与 [`AGY.md`](AGY.md)。

## 1. 两层单一真源

```text
设备（用户操作的单位，一台 SSH 主机）               Harness（设备上的能力开关）
         SshDevice (id = ssh-<uuid>)   ──►   0..N 个 DeviceHarness (id = 稳定 HarnessId)
           • 地址 / 端口 / 用户 / 认证               • DeepSeek Harness (DSH)
           • 主机指纹 (TOFU SHA-256)                 • OpenCode 2.x Native
           • 设备总开关 (enabled)                    • OpenCode ACP
                                                     • Codex (app-server)
                                                     • Antigravity ACP
```

| 层 | 代码 | 唯一持久化位置 | 用户能做什么 |
|---|---|---|---|
| **设备** | `SshDevice` | `DeviceStore`（`devices` SP + `ssh-secrets` 加密凭据） | 添加/编辑 SSH 配置、启用/停用、核对/重置主机指纹、刷新发现、查看设备级 SSH 状态、移除设备 |
| **Harness** | `DeviceHarness`（内嵌在所属 `SshDevice.harnesses`） | 同上（单文档原子提交） | 添加/移除、启用/停用、编辑协议专属选项、任务通知、新会话默认值、模型显示 |

- **不再有第三层“接入源”或独立的连接图副本**：`ConnectionSource`、`HostBinding`、`HarnessBinding`、`ConnectionGraphStore` 与 `ServerKind` 均已废除。
- **界面、路由、统计、文案里的“设备”永远指 `SshDevice`**；Harness 只是设备下的能力，不得把 Harness 当设备计数或展示。
- `ServerProfile` 退化为由 `SshDevice` + `DeviceHarness` 实时派生的**只读运行时投影**（供监控总览、对话与隧道协议层使用），不再单独落盘。

## 2. 身份与协议规则

| 标识 | 形式与规则 | 约束 |
|---|---|---|
| `DeviceId` | `ssh-<uuid>`（沿用既有 `SshHostConfig.id`） | 名称、IP、端口永不作身份；重命名或改地址不改变 `DeviceId` |
| `HarnessId` | 稳定字符串（既有 SSH leaf 完整保留原 `ServerProfile.id`；新建使用 `UUID.randomUUID()`） | **永不重编号**。会话、草稿、离线缓存、SQLite Transcript、任务账本、未读通知、深链都挂在它上面 |
| `HarnessProtocol` | `DSH` / `OPENCODE_NATIVE` / `OPENCODE_ACP` / `CODEX` / `ANTIGRAVITY_ACP` | 一等公民强类型枚举；未知协议读取失败闭环，绝不静默回退到其他协议 |
| Harness 槽位 | `(protocol, locator)`；仅 `OPENCODE_NATIVE` 有可选 `registrationPath` locator（`null` 表示官方默认 `service.json`） | 一台设备内同槽位最多一个 `DeviceHarness`；重复添加必须幂等复用 |

- OpenCode Native 的默认注册文件（`$XDG_STATE_HOME/opencode/service.json` 或 `~/.local/state/opencode/service.json`）与开发通道（`service-local.json`）是独立槽位，不得合并。
- 协议专属配置强类型挂载在 `DeviceHarness.config`（`HarnessConfig`）上：
  - `HarnessConfig.Dsh(launchCommand, workingDirectory)`：修改 DSH 启动命令或工作目录只重建该 DSH 链路，**不再影响同一设备的物理 SSH 会话或其他 Harness**。
  - `HarnessConfig.OpenCodeNative(registrationPath)`：支持默认通道与显式注册路径。
  - `HarnessConfig.OpenCodeAcp` / `Codex` / `AntigravityAcp`：各自经 SSH exec stdio 通道运行。

## 3. 开关与流量契约

生效条件为 `device.enabled && harness.enabled`。

| 情况 | 物理 SSH 会话 | 只读发现 | 监控/订阅 | 通知 | 缓存刷新 |
|---|---|---|---|---|---|
| 设备停用（`device.enabled = false`） | 无（立即关闭） | 拒绝（只读缓存） | 无 | 无 | 无 |
| 设备启用、该 Harness 停用 | 可能（同机其他 Harness 在用） | 仅用户显式刷新 | 无 | 无 | 无 |
| 设备启用、该 Harness 启用 | 共用本设备唯一 SSH 连接 | 显式刷新 | 有 | 按 `harness.notifications` | 有 |
| 设备最后一个 Harness 停用 | 引用归零后关闭 | 仅用户显式刷新 | 无 | 无 | 无 |

- 关设备**不覆写**子 Harness 开关；关单个 Harness 不影响兄弟 Harness。
- 一台设备上的所有 Harness（DSH / OpenCode Native / OpenCode ACP / Codex / Antigravity）**共享同一条已认证物理 SSH 会话**（`SshTransportPool`），并统一具备状态流、SSH 保活（keepalive）与切网恢复能力。
- 设备可以拥有 **0 个 Harness**；移除最后一个 Harness 保留设备本身；只有显式「移除这台设备」才删除 SSH 配置、凭据与全部 Harness。

## 4. 存储落点（单一真源）

| 数据 | 存储位置 | 权威性 |
|---|---|---|
| 设备配置与挂载的 Harness | `DeviceStore`（SP `devices`，version 2，单次原子 `commit()`） | 设备与 Harness 唯一结构真源 |
| SSH 主机内联凭据（密码/旧私钥） | `DeviceStore`（SP `ssh-secrets`，Keystore AES-GCM） | 主机凭据真源 |
| 全局 SSH 密钥库 | `SshKeyStore`（SP `ssh-keys` + `ssh-key-secrets` + AndroidKeyStore 生物识别/硬件密钥） | 密钥库真源（`keyId` 永不变） |
| 发现观测缓存 | `DeviceDiscoveryStore`（SP `device-discovery`，version 2，按 `DeviceId` 索引） | 带 TTL 的只读观测，非连接许可 |
| 设备组 | `DeviceGroupStore`（SP `device-groups`，version 4，仅存 `deviceIds`） | 本地展示范围，运行时展开为 Harness |
| 按 Harness 隔离的运行态/用户偏好 | `HarnessStateStore`（SP `servers` + `secrets`） | 本地项目、草稿、文件引用、任务账本、未读通知、模型偏好、全局设置 |
| 离线目录/消息缓存 | `OfflineCache`（SP `offline_cache`）与 `TranscriptStore`（SQLite `transcripts.db`） | 按稳定 `HarnessId` 索引 |

## 5. 升级与迁移（v5.3.x → 纯 SSH）

首次启动执行幂等迁移 `LegacyToSshMigration`：
1. 若旧 `servers`/`secrets` 存在中断的 `configuration-save-journal-v1`，先容错完成恢复，避免升级卡死。
2. 读取旧 `ssh-hosts`、旧 `connection-graph` 中的 SSH `HostBinding.enabled`（以及未归一化的 SSH `ConnectionSource.enabled`）、旧 `servers` 中的 SSH leaves，合并原子写入新 `DeviceStore`：
   - 保留全部 SSH `DeviceId`、`HarnessId`、`keyId`、TOFU 主机指纹、停用开关与草稿/缓存/通知账本。
   - 保持 `TranscriptStore` 的 `server.identity` 兼容，不清空已有 OpenCode SQLite 缓存。
   - 把旧 `SshHostConfig.launchCommand`/`workingDirectory` 迁入该设备下 DSH Harness 的 `HarnessConfig.Dsh`。
3. 把旧 `DeviceGroup` 的 `host-<sha256>` / `memberIds` 确定性映射为新 `DeviceId` 并升级为 v4（仅存存活 SSH 设备）。
4. 清理退场的 Remote Hub 与 HTTP 直连资料、旧 `remote-*` 存储、`connection-graph`、`ssh-hosts` 与 `lagoon-remote-device-identities` Keystore 别名，并在首页/设备页展示一次性退场说明。

## 6. 已被否决的方案（不要重做）

1. 再引入独立于 `DeviceStore` 的第二份连接图或双写日志。
2. 把 Harness 当设备展示或计数（如用 `profiles.size` 冒充设备数）。
3. 把 DSH 专属 `launchCommand`/`workingDirectory` 塞进 SSH 物理连接池 key。
4. 把发现结果当成连接许可，或在打开设备页时静默自动拨号/启动远端服务。
5. 用 `ServerKind` 这类冻结兼容枚举把 ACP 协议伪装成 Codex。
