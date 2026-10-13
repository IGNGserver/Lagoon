# 持续服务器连接与手机授权策略

## 三种互不绑定的状态

- 连接：应用进程级 `AllServersOverview` 持有每台已保存服务器的 API 客户端、唯一 SSE 订阅与重连循环。首页筛选或打开对话不会销毁它们。
- 首页：`allServers` / `homeServerId` 只决定 `homeProjection()` 的数据范围与保存状态的键。
- 对话：`serverId` / `sessionId` 确定读写目标；进入详情时立即导航，消息在详情内加载。当前对话复用该服务器的 API 与事件订阅，退出后该服务器仍被监控。

系统实时更新与前台服务只使用 `globalSummary`、`globalSummaryTarget`，身份固定为全部服务器。对话的实时状态替换同服务器快照，而不是重复计入；相同会话 ID 在不同服务器上不会碰撞。

断线独立恢复，事件消费不等待全量 HTTP 对账。事件流是实时、不可重放的，重连后需重读控制状态。Android 强行终止应用、强制停止以及 dataSync 前台服务时限仍是平台约束，不承诺无限后台保活。

## 官方 V2 依据

本次对照版本：`anomalyco/opencode` `1015cb67d08858496518b02f437b8e4a2fc89d61`。

- [`packages/core/src/permission.ts`](https://github.com/anomalyco/opencode/blob/1015cb67d08858496518b02f437b8e4a2fc89d61/packages/core/src/permission.ts)：规则结果是 `allow` 时直接返回，仅 `ask` 创建 pending 请求并发出 `permission.asked`；回复移除请求。
- [`packages/app/src/session/requests/auto-approve.ts`](https://github.com/anomalyco/opencode/blob/1015cb67d08858496518b02f437b8e4a2fc89d61/packages/app/src/session/requests/auto-approve.ts)：桌面自动授权属于客户端策略，发送 `decision: "once"`；连接恢复与启用设置时扫查现有请求，并去重、重试。
- [`packages/app/src/session/requests/model.ts`](https://github.com/anomalyco/opencode/blob/1015cb67d08858496518b02f437b8e4a2fc89d61/packages/app/src/session/requests/model.ts)：自动授权启用时不展示权限 dock。
- [V2 客户端文档](https://opencode.ai/v2/docs/build/client)：订阅不重放、不会自动重连；恢复必须由客户端负责。

因此不能从“工具运行了”猜服务器自动授权，也不能只保留历史 WAITING_PERMISSION 标记。请求完成事件和成功的 pending 读取共同维护真实等待集；已处理请求保留有界 tombstone，防止迟到读取复活旧请求。

## 手机全局设置（默认均关闭）

| 隐藏 | 自动授权 | 行为 |
| --- | --- | --- |
| 关 | 关 | 正常展示真实待授权请求，用户手动回复 |
| 开 | 关 | 不展示授权窗口、列表授权标记或授权通知；不回复请求，真实阻塞显示中性的“等待服务器处理” |
| 关 | 开 | 全部已连接服务器自动允许一次，正常不展示授权窗口；失败重试后提供手动处理入口 |
| 开 | 开 | 自动允许一次，但不展示授权窗口或授权标记 |

这些策略只保存在手机，不修改服务端规则。不将拒绝规则改成允许，不发送 `always`，不自动填写问题或表单。自动授权意味着信任所有已连接服务器提出的权限请求，开启前请确认这些服务器可信。

中性等待有独立 `processing` 计数，不算正在执行、不算待用户回复，也不假装已完成。系统通知仍保持进行中，直到服务器真实恢复或结束。

## 真机验收

1. 两台服务器同时运行任务；在全部列表进入任意详情、返回、切换单服务器、打开设置，另一台的完成与问答通知仍到达，实时更新计数始终汇总两台。
2. 在全部列表搜索并滚动后进入详情，返回仍在原筛选和原位置；从另一服务器通知进入详情也不改变原首页范围。
3. 短暂断网、重启其中一台服务器：自动恢复订阅，对账补齐断线期间的完成、权限与表单变化；另一台不受影响。
4. 电脑启用自动授权，连续产生权限请求：手机不残留授权状态或已处理授权通知。
5. 分别验证两个开关的四种组合，包括尚未打开的对话、子会话和设置开启前积压的请求。
6. 两个客户端同时回复同一个请求：只清除该请求；同会话剩余的权限或问答仍保持等待。
7. 自动授权失败、重试期间关闭开关、删除或修改服务器配置：不继续对旧连接补发新的授权操作，不把失败当成功。
