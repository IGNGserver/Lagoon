# 加载、刷新与新建会话

这份文档记录 4.2 之后“为什么不再动不动转圈”的约束。它不是 UI 文档，而是连接层与状态层的预算。

## 1. 三层职责

```text
AllServersOverview   每台已启用设备：一条 $events/SSE，轻量快照（任务/审批/结果）
ServerRuntime        当前可写设备：catalog + 会话时间线 + 工作区
SessionViewCache     最近打开的会话窗口（切回先显示，再后台校验）
```

- 事件流是主实时源；轮询只做“漏帧兜底”，不是刷新手段。
- 当前设备保留完整内容订阅；后台设备只保留控制事件，不打开 `workspace/follow`、`session/follow` 或 `job/list`。
- 页面切换不重新 `connect`；只有连接不存在时才在后台建立连接。

## 2. 读取预算

| 动作 | 频率/上限 | 说明 |
|---|---|---|
| 当前设备安全对账 | 5 分钟 | 只读 catalog + active + pending，不读 shell/父子链/自动审批 |
| 后台设备安全对账 | 5 分钟 | 只读 health + catalog + active + 已知 pending |
| 后台设备内容流 | 0 | `setContentWatching(false)`，回到前台只恢复当前设备 |
| `session/follow` | 最近活跃 12 条 | 当前会话固定；后台不 follow |
| `job/list` | 只在深度读/打开后台面板 | 最多 2 个目录；每个目录最多 3 个会话 |
| 自动审批目录扫描 | 12 个 | 自动审批开启时，每次控制读的上限 |
| 父子链补读 | 8 轮 × 32 | 防止异常会话链放大 |
| 待处理表单目录 | 8 个 | 只读 active / 已等待的目录 |
| Agent/Model/Command | 10 分钟 TTL | 按 `(server, directory)` 缓存 |
| 首轮连接 | 20 秒 | 超时转离线/后台重试，不让首屏无限等待 |
| SSH 前台调用 | 20 秒 | 链路状态异步展示，失败快速转离线 |

## 3. 错误语义

- `RetryableTransport`（relay/RPC 超时、半开连接、临时 5xx）：保留上次数据，标 `STALE`，后台退避重试；不弹全局“加载失败”。
- `Auth` / `Contract` / `Rejected`：显示错误和恢复入口。
- `cached` 只表示“这句会话的内存副本未经本轮校验”，不阻止发送。
- 只有“没有可显示数据”时才显示骨架屏；有缓存时只显示轻量同步状态。

## 4. 新建会话

1. 空白页可直接打开；没有已登记工作区时，DSH 会从 `workspace/follow` 的实时清单里自动选第一个。
2. 连接中发送会进入 `PendingDraft` 队列；连接恢复后自动创建并发送第一条消息。
3. DSH 创建会话时优先传工作区 id；只有工作区子目录/未登记目录才退回 `cwd`，避免产生远端不可见的孤儿会话。
4. 创建成功后先显示会话，不等待完整历史；首条消息失败时保留草稿和附件。

## 5. 诊断

设置 → 诊断文本现在包含：

- `connectTrace` / `openTrace` 的耗时；
- 会话缓存命中情况；
- `LoadMetrics` 的累计计数（`catalog`、`transcript`、`choices`、`ancillary`、`overview-control`）。

回归测试重点：

```sh
ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest
```

- 后台设备不因事件触发全量 HTTP 读；
- 取消/超时一个 Remote RPC 不让整条 mux 断开；
- 重复选中同一会话不重新读网络；
- DSH `session/list` 本地分页不重复游标；
- 显式 `workspaceId` 优先于目录匹配。
