# DSH Remote 接入基线与进度

本文件记录 Android 端的开发基线，不表示新接入已经可用。现有 DSH 直连在新方案完成验收前保持原状。

## 固定参考

- Lagoon 基线：`3ba98eb6f2b263d2125d56d0ee5a653f62681edc`。
- [ds-harness-remote](https://github.com/liguobao/ds-harness-remote/tree/88e1808585faee417a1663a2a3b8a265f716dfe6)：commit `88e1808585faee417a1663a2a3b8a265f716dfe6`，根包 `0.6.0`，Remote Protocol v1。
- 上游声明的 DSH 开发基线：`0.2.1-rc.5`。具体部署仍需按能力发现与实际版本验证。
- 上游仓库内的 Server 是单账号最小自部署实现；托管 Server 的 OAuth、WebRTC/TURN、设备管理等能力不能假设在自部署 Server 上存在。

## 边界

Android 注册独立的 Remote Client 设备，直接作为 Remote Noise 安全通道的终点。DSH Server 协调设备和连接，Host 插件提供经加密通道访问的业务接口。不能只实现 Server REST 就声称已接入会话。

Lagoon 中枢与网页端已停止维护并从仓库移除，不再注册 Remote Client，也不提供浏览器入口；本文件只描述 Android 客户端。

目标层次：账号与设备 → Control、Noise、传输与重连 → Typert Harness → Codex、ACP、Bot 等按上游能力声明分别实现。不同 Host、backend 和会话需要隔离身份及状态；Unknown/unsupported 不得静默映射到其他后端。旧启动令牌、签名密钥与 Cookie 无法转换为新设备凭据；迁移不能删除归属不明的草稿与缓存。

## 当前实现状态

| 项目 | 状态 | 说明 |
| --- | --- | --- |
| Server 健康、账号密码登录、注册、刷新、Host 列表与授权详情 | 开发中 | `RemoteServerApi`；MockWebServer 测试及本机自部署 Server REST 冒烟，已接入 Android 配置流程；不含 OAuth。 |
| 安全消息分片 | 开发中 | `RemoteSecureFragments`；本地单元测试，尚未与真实 Host 联调。 |
| Host pin 校验 | 开发中 | Android 独立持久化并显示，要求人工确认上游 Host 指纹；尚无真 Host 验收。 |
| 设备私钥、原子 token 保存 | 开发中 | Android 加密持久化；注册/刷新记录不确定状态避免重放，已接入连接生命周期。 |
| Noise、Control WebSocket、Relay | 开发中 | JVM 测试通过上游 Noise 固定向量及模拟 Host 的加密往返；控制通道 Relay 尚未与真实 Host 联调，独立 Relay 端点不支持；Android 仅编译，尚无真机验证。 |
| WebRTC/TURN | 未实现 | 不能假定自部署 Server 支持。 |
| RPC 与 capability | 开发中 | `RemoteConnection` 已接入 `DshApi` 投影，严格按能力选择 Typert；不支持历史 `harness.api.*` 回退。 |
| stream、transfer | 开发中 | 已实现基础流、512 KiB transfer 及大流帧接收。只读调用在 `RESPONSE_TOO_LARGE` 时只改走一次分块；写入、权限和提问不重放。尚无真实 Host 验证。 |
| DSH、Codex、ACP、Bot 等业务 | 开发中 | DSH Typert allowlist 通过现有投影接入；Codex/ACP/Bot 仅保留能力探测，不伪装为已支持。 |
| Android 集成、旧资料迁移、移除旧入口 | 开发中 | Android 配置与生命周期已接入。旧直连资料只记录“不能转换成 Remote 设备凭据”，令牌、Cookie、草稿和缓存都留在原资料；未达到移除条件。 |
| 自部署 Server REST 联调 | 开发中 | `RemoteServerIntegrationTest` 对本机上游 Server 验证了登录、客户端注册、Host 列表、刷新及旧 refresh token 被拒；没有可用 Remote Host，端到端业务联调仍未执行。 |
| 真机 / 真实 Host 联调 | 未执行 | 单元测试或编译通过不等于互操作完成。 |

## 本地验证示例

协议单测需要先将工作树复制到本机 scratch，禁止在项目卷安装依赖或生成构建产物：

```sh
ANDROID_HOME="$HOME/Android/Sdk" GRADLE_USER_HOME="$HOME/.build/gradle" BUILD_SCRATCH_ROOT="$HOME/.build" \
  ./gradlew :app:testDebugUnitTest --tests 'com.igng.opencode.lagoon.core.Remote*Test'
ANDROID_HOME="$HOME/Android/Sdk" GRADLE_USER_HOME="$HOME/.build/gradle" BUILD_SCRATCH_ROOT="$HOME/.build" \
  ./gradlew :app:compileDebugKotlin
```

这些只验证当前代码片段，不代替仓库 `AGENTS.md` 的完整 Validation 或 Remote 的真实互操作验收。

多 Host 并行接入与“同一物理设备多 harness 分组”的设计见
[`DSH_REMOTE_MULTI_HOST.md`](DSH_REMOTE_MULTI_HOST.md)。
