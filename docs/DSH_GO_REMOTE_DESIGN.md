# DSH Go 远程能力借鉴与 Lagoon 落位设计

> **2026-11-05 更新**：SSH 主机已作为 DSH 的一等连接方式落地，行为/配置对齐 dsh-Go 的
> `SshHostConfig` + `RemoteDshBootstrap` + `SshHostLink`，实现为 Kotlin/JSch 独立重写；
> 直连 DSH（地址 + 令牌）已从新建设备入口移除。设计与限制见
> [`SSH_HOSTS.md`](SSH_HOSTS.md)，设备管理见 [`DEVICE_MANAGEMENT.md`](DEVICE_MANAGEMENT.md)。
> 本文正文中“明确不做 SSH 主机”“SSH 作为远程主路径不借鉴”等结论已被此次更新取代，保留原文
> 仅用于记录当时的取舍。
>
> **2026-11-06 更新**：首页的横向服务器胶囊已删除，设备/分组/项目/工作区统一由左上角标题打开的
> 范围面板承载。本文中“首页胶囊/服务器胶囊是唯一入口”的措辞仅指当时实现，切换职责已收进左上角。
>
> 分析对象：`ccch1mneyyy/dsh-Go`（GitHub 约 9.1k Star，2026-11-04 抓取的 `9ac085f`）与
> `liguobao/ds-harness-remote`（Remote Protocol v1，0.6.x Host 插件）。
> Lagoon 基线：`cd45f72`。
>
> 本文只讨论 **DSH Remote 这条线**，不把 DSH Go 的本机 Linux 运行时、手机操控、桌宠、分享、
> SSH 主机等产品能力混进来。多 Host 并行、设备分组、Hub 补丁的底层设计见仓库已有的
> [`DSH_REMOTE_MULTI_HOST.md`](DSH_REMOTE_MULTI_HOST.md)，本文采用其结论，不重复推演。
>
> 代码与协议结论来自源码阅读和协议文档；没有在真机或真实 Host 上重新验收。文中“可用/不可用”
> 指当前上游协议与 dsh-go 的实测口径，落地前仍需按 `docs/DSH_REMOTE_INTEGRATION.md` 的验收边界复核。

---

## 0. 一页结论

DSH Go 真正值得 Lagoon 学的，不是某个单独的页面，而是把 Remote 从“一组 RPC”做成了完整的
**账号 → 主机 → 加密连接 → 失败恢复** 生命周期：

1. **账号是账号，主机是主机**。登录一次，列出账号下的电脑；连接哪台是下一步，不是登录表单的一部分。
2. **连接状态是可解释的**。离线、登录失效、指纹变化、版本不兼容、被别的设备接管，各有各的文案和动作。
3. **主机能力决定界面**。支持工作区上传才显示上传，支持后台任务才显示输出；不支持的入口不摆出来。
4. **远程任务要有闭环**。后台命令能看实时输出（停止还受上游 allowlist 限制）；排队消息能撤回/改成插话；主机密钥能重新核对。

Lagoon 的 DSH Remote 底层并不弱：Noise、Host pin、token 轮换、stream/transfer 都已经有了；
缺的是上面这层“产品化”。同时，Lagoon 已有的多 Host 并行监控是 DSH Go 没有的能力，**不要为了
照搬 dsh-go 而退化成单设备单主机**。

### 建议优先级

| 优先级 | 能力 | Lagoon 落点 | 价值 |
| --- | --- | --- | --- |
| P0 | DSH Remote 浏览器登录（OAuth/QR）+ 自建服务器邮箱登录 | 设置 › 服务器 › DSH Remote；添加服务器流程 | 首次接入从“填账号密码 + 手抄指纹”变成“浏览器登录 + 点主机” |
| P0 | 主机目录、一次登录多选 Host、批量建档 | 远程主机页 + 添加服务器流程 | 保留多 Host 并行，同时不重复登录、不重复注册设备 |
| P0 | 指纹信任弹窗、密钥变化阻断/重新信任 | 选中主机后、首次连接前 | 把安全确认从“手输 12 位十六进制”变成可核对的仪式 |
| P0 | 连接失败分类、可执行动作、避免无效重连 | ConnectionStrip、服务器编辑页、远程主机页 | 用户知道“为什么连不上、下一步点哪” |
| P1 | 网络与连接详情（Relay、耗时、RTT、版本） | 远程主机详情 / ConnectionStrip “详情” | 排障时不再只有一句“连接失败” |
| P2 | 远程工作区文件上传 | 聊天输入框 ＋ › 手机文件 | PDF/压缩包/代码文件不再只能发图片和文本 |
| P2 | 后台命令实时输出（停止待上游） | 聊天 ⋯ › 子会话与后台 › 后台命令 | 现有入口不再点了没反应 |
| P2 | 排队消息撤回 / 改为插话 | 聊天里的“排队中”消息 | 发错了不用等它自己执行 |
| P2 | 主机设置（模型服务商/密钥，后续插件开关） | 设置 › 服务器 › 主机 › 此主机设置 | 远程 DSH 没配模型时，不必回到电脑 |
| P3 | 可继续子代理的续聊 | 子会话底部状态栏 | 一次性子代理仍然只读，可继续的才给输入；需产品确认 |
| P3 | Office/PDF、二进制文件预览 | 聊天“文件”面板 | 远程文件不只文本可看 |
| P3 | 定时任务、目标、插件开关 | 主机设置 / 会话输入区 | 有价值，但不属于远程首批；插件只做官方 allowlist 开关 |
| P4 | WebRTC LAN/P2P/TURN | 连接详情 | 大文件与低延迟，但成本是另一个项目 |

**明确不做/暂不做**：SSH 主机、本机运行时、手机操控、桌宠、分享链接、插件市场、远程网页版、
终端、远程 diff、远程会话导出。原因见 §5.13。

> 定时任务、长期目标是独立产品能力，本文只给落位，不纳入阶段估算；P3 阶段实际只包含插件、Office 预览、子代理续聊。

### 功能落位速查

| 功能 | 放在哪 | 具体入口 |
| --- | --- | --- |
| DSH Remote 登录 | 设置 / 添加服务器 | 设置 › 服务器 › DSH Remote；首页 ＋ › 添加服务器 |
| 主机目录 / 多选接入 | 远程主机页 | 设置 › 服务器 › DSH Remote › 接入主机（从账号选择） |
| 指纹信任 / 密钥变化 | 选中主机后的核对页 / 弹窗 | 接入主机流程；主机详情 › 重新验证 |
| 连接状态 / 失败恢复 | 当前页面的状态条 | 首页服务器胶囊下方的 ConnectionStrip；远程主机页“正在使用”卡 |
| 网络与连接详情 | Bottom sheet | 远程主机页 › 正在使用 › 详情；服务器编辑页 › 连接 |
| 工作区文件上传 | 聊天输入框 | 聊天 › ＋ › 手机文件 |
| 后台命令输出 | 聊天的后台面板 | 聊天 › ⋯ › 子会话与后台 › 后台命令 |
| 排队消息操作 | 消息本身 | 聊天 › 排队中消息 › 操作 |
| 子代理续聊 | 子会话底部 | 子会话 › 继续（仅可继续的子代理） |
| 主机设置（模型/密钥） | 服务器详情 | 设置 › 服务器 › [远程主机] › 此主机设置 |
| 定时任务 | 主机设置 | 此主机设置 › 自动化 |
| 长期目标 | 会话输入区 | 输入框上方目标条（独立能力，非首批） |
| 插件开关 | 主机设置 | 此主机设置 › 插件 |
| 网络路径升级（WebRTC） | 连接详情 | 未来在路径行增加 LAN/P2P/TURN |

---

## 1. Lagoon 现在是什么样

### 1.1 代码链路

```
ServerProfile(DSH_REMOTE, remoteHostId, remoteHostFingerprint)
  └─ LagoonController.remoteBackendFactory
       └─ RemoteDeviceManager(slot = profile-<id>)
            └─ RemoteSession(hostId)
                 ├─ RemoteServerApi  /api/v1/*（登录、注册、刷新、Host 列表、Host 详情）
                 ├─ RemoteRelay      /ws/v1/connect（Control WebSocket + Noise IK + relay 帧）
                 ├─ RemoteGateway    harness.remote.call / stream.open / transfer.*
                 └─ RemoteBackend → DshApi（把远程调用投影成现有 HarnessClient）
```

关键点：

- 每台 Host 一个 `ServerProfile`，每台一个 `RemoteSession`，每台一个设备身份（`profile-<id>`）。
- `AllServersOverview` 会按 `autoConnect` 为每个 profile 建独立 monitor，所以多 Host 并行是现成的。
- `RemoteRelay` 当前只走 Control WebSocket 上的 relay，不支持独立 Relay 节点、WebRTC/TURN。
- `DshApi.capabilities()` 对 Remote 只把 `diff` 关掉，其他能力仍是 DSH 直连的全集；具体功能是否可用
  依赖 Host allowlist 和调用时返回。
- `RemoteSetup` 的配对是“登录账号 → 注册设备 → 拉 Host 详情 → 人工输入 12 位指纹 → 保存”。

### 1.2 现在的用户路径

```
首页 ＋ → 添加服务器 → 服务类型选 DSH Remote
  → 填 Hub 地址、账号、密码
  → 登录并发现 Host
  → 下拉选一台 Host
  → 在电脑上找到指纹，手抄 12 位十六进制填进去
  → 保存并连接
```

问题不在“步骤多”，而在**每一步都在让用户做本应由产品完成的判断**：

- Hub 地址、账号、密码、Host、指纹全挤在一个通用服务器表单里；
- 登录成功后的 Host 列表是临时的，离开表单就没了；
- 指纹没有展示、复制、对照的界面，只能手输；
- 加第二台 Host 要重复账号密码，并再注册一个设备；
- 连上以后看不到主机版本、在线状态、指纹状态、网络路径；
- 出错时通常只有一句中文 `IOException`，没有“该点哪里”。

### 1.3 已有优势，不能丢

- **多服务器引擎**：OpenCode、DSH 直连、DSH Remote 共用 `HarnessClient`，任务总览、通知、会话路由都复用。
- **多 Host 并行**：每台 Host 独立设备身份，`AllServersOverview` 可同时监控；这是 dsh-go 单设备模型没有的。
- **安全底子**：Noise IK、Host pin、设备私钥/令牌加密落盘、refresh token 轮换、分帧/上限校验。
- **会话体验**：时间线、工具折叠、审批/问答、子会话、后台活动、队列展示、任务通知都已成熟。
- **视觉体系**：首页 Qoder 任务列表、聊天 Qoder 主题、设置 MIUIX 卡片，已经形成稳定语言。

所以这次不是“重做 Remote”，而是**在现有链路上补产品层**。

---

## 2. DSH Go 做对了什么

### 2.1 三层模型

DSH Go 把 Remote 拆成三层，UI 也按这三层组织：

```
RemoteAccount（origin + account + 本机设备身份）
  ├─ listHosts()：账号下的电脑，带 online / platform / harnessVersion / fingerprint / trust
  ├─ connectHost(host)：一次只连一台（它的产品选择）
  └─ RemoteHostLink：连接状态机（connecting / connected / reconnecting / failed）
       └─ RemoteTunnelCarrier → DshClient（和本机引擎同一套 API）
```

- **账号层**负责登录、设备注册、Host 目录、pin。
- **连接层**负责 Relay/WebRTC、Noise、重连、失败分类。
- **业务层**复用同一个 `DshClient`，所以聊天、审批、模型、文件不需要为远程写第二套。

这正好对应 Lagoon 缺的三块：账号页、连接状态、能力门控。

### 2.2 值得学的交互

| DSH Go 的做法 | 为什么好 | Lagoon 可借鉴的部分 |
| --- | --- | --- |
| 远程主机是独立设置页，不是添加服务器表单的一部分 | 登录、主机列表、退出、网络详情有地方放 | 新增“远程主机”页，服务器表单只负责“选择已发现的主机（可多选）” |
| 浏览器登录 + 轮询，不要求填密码 | 托管 Hub 的 GitHub/知乎登录是主路径 | OAuth/QR 登录，Custom Tabs，回到 App 自动完成 |
| 自建服务器用邮箱密码，作为次级入口 | 不干扰主路径 | 登录 sheet 底部“自建服务器” |
| 主机列表显示在线、平台、Harness 版本、指纹信任 | 用户能判断“这是哪台、能不能连” | 主机行副标题 + 信任徽标 |
| 首次连接弹指纹确认；密钥变化红色阻断 | 安全确认是仪式，不是手抄 | 指纹弹窗/核对页，展示、复制、对照 |
| 活动主机卡带状态、路径、失败原因和一个修复动作 | 排障入口就在眼前 | 远程主机页顶部“正在使用”卡 + ConnectionStrip |
| 网络详情显示路径、连接耗时、RTT、回落原因 | 弱网/慢的时候能解释 | Relay-only 版本显示“Relay 中继”、耗时、RPC 往返 |
| 失败码映射成中文和动作 | 不同错误不能都叫“连接失败” | 建立 `RemoteFailure` 分类表 |
| 连接步骤可见（连接服务器 → 验证指纹 → 启动/隧道 → 登录） | 长连接有进度感 | ConnectionStrip 增加 Remote 阶段 |
| 能力驱动 UI（上传、后台任务、终端） | 不摆死路 | `RemoteCapabilities` 增加功能开关 |

### 2.3 远程能力清单（按 Lagoon 可用性分层）

图例：✅ 远程可用 · ⚠️ 有条件可用 · ❌ 远程不可用/不适用。

| 能力 | dsh-go | 上游方法 | Lagoon 现状 | 结论 |
| --- | --- | --- | --- | --- |
| OAuth / QR 浏览器登录 | ✅ | `GET /api/v1/auth/oauth/providers`、`POST .../qr/start`、`GET .../qr/{id}` | ❌ 只有邮箱密码 | **P0 借鉴** |
| 自建服务器邮箱登录 | ✅ | `POST /api/v1/auth/login` | ✅ 已有，但埋在表单里 | 保留，挪到登录 sheet |
| Host 目录 / 在线状态 / 版本 | ✅ | `GET /api/v1/devices`、`GET /api/v1/devices/{id}`、`.../presence` | ❌ 只在配对时临时拉 | **P0 借鉴** |
| 主机指纹信任 / 密钥变化 | ✅ | Host descriptor + pin | ⚠️ 有 pin，但手输指纹 | **P0 改造** |
| 一个账号多台 Host | ✅ 列表；一次只连一台 | 同上 | ✅ 每 Host 一个 profile，可并行 | 保留 Lagoon 的多 Host 模型 |
| Relay 连接 | ✅ | `/ws/v1/connect` + Noise | ✅ 已有 | 保持 |
| LAN / P2P / TURN | ✅ | WebRTC signaling + ICE | ❌ 只有 Relay | P4 评估，不首批 |
| 网络路径 / RTT / 回落原因 | ✅ | WebRTC stats / 连接元数据 | ❌ | P1 做 Relay 版本 |
| 工作区文件上传 | ✅ | `workspace.upload.open/chunk/commit/abort` | ❌ 只支持图片/文本内联 | **P2 借鉴** |
| 后台任务列表 | ✅ | `job/list` | ⚠️ 只列运行中，且只用于标记 | 已有基础 |
| 后台任务实时输出 | ✅ | `job/follow` | ❌ 只有 OpenCode 的 shell 输出 | **P2 借鉴** |
| 停止后台任务 | ❌ Remote allowlist 无 `job/kill` | `job/kill`（不在 allowlist） | ❌ | 阻塞：等上游加入 allowlist |
| 排队消息编辑/撤回/插话 | ✅ | `session/updateQueue` | ⚠️ 只显示“排队中” | P2，小改动高收益 |
| 子代理续聊 | ✅ | `subagents/list`、`subagents/prompt`、`subagents/interruptByParent` | ⚠️ 子会话只读 | P3，按 `continuable` 开放；需产品确认 |
| 主机设置 / 模型服务商 | ✅ | `settings/*`、`llm/*`、`credentials/*` | ❌ | P2，先做模型 |
| 插件开关 | ✅ | `pluginManager/list*`、`set*Enabled` | ❌ | P3 |
| 定时任务 | ✅ | `schedule/*` | ❌ | P3，非远程专属 |
| 长期目标 | ✅ | `goals/*` | ❌ | P3，非远程专属 |
| Office 转 PDF / 二进制预览 | ✅ | `officeToPdf/render`、`workspaceFiles/readBytes` | ⚠️ 只有文本 | P3 |
| 终端 | ❌（Remote 无 uplink） | `terminal/attach` 需要双向流 | ❌ | 暂不做 |
| 逐文件 diff | ❌（依赖 HTTP `/api/changes.*`） | 无 RPC | ❌ Remote 已关闭 | 不承诺 |
| 会话日志导出 | ❌（依赖 HTTP） | 无 RPC | ❌ | 不承诺 |
| 远程网页版 | ❌ | 无 HTTP 通道 | ❌ | 不承诺 |
| SSH 主机 | ✅ | 自有 SSH + 本地转发 | ❌ | 不借鉴，独立项目 |
| 本机运行时 / 手机操控 / 桌宠 / 分享 | ✅ | 自有 | ❌ | 不相关 |

### 2.4 不能照搬的三件事

1. **单设备单主机**。dsh-go 为了消费级体验，一个账号一台设备、一次连一台；Lagoon 的多 Host 并行
   需要“每 Host 一个 client 身份”。这是产品模型差异，不是谁对谁错。照搬会把 Lagoon 已有的
   “全部服务器”总览做没。
2. **SSH 作为远程主路径**。dsh-go 同时支持 SSH 和 Relay；Lagoon 已有 Relay，再引入 SSH 会变成
   第二个远程产品（跳板、启动 dsh web、端口转发、私钥管理），不应混在这次设计里。
3. **把 dsh-go 的代码搬进 Kotlin**。dsh-go 是 AGPL-3.0；在确认 Lagoon 的授权与 AGPL-3.0 兼容之前，
   不要把它的代码复制进来。只借鉴交互与协议事实；协议上游 `ds-harness-remote` 是 MIT，可按其规范重实现。

---

## 3. 关键取舍：保留多 Host 并行

仓库已有的 [`DSH_REMOTE_MULTI_HOST.md`](DSH_REMOTE_MULTI_HOST.md) 已经证明：

- 一个账号下可以注册多个 client 设备身份，每个身份连一台 Host，互不踢线；
- `AllServersOverview` 已经能按 profile 并行监控；
- 真正的限制是“一个 client 身份只能连一台 Host”，不是“一个账号只能连一台”。

因此本文采用它的方案 A：

- **一个 DSH Remote 账号，一次登录，多选 Host，批量注册 N 个设备身份，生成 N 个 profile；**
- 每个 profile 一条独立连接；首页/通知/任务总览在 P0 继续按服务器展示，设备组落地后按组聚合；
- Hub 管理页会看到 N 个 `Lagoon · <hostName>` 设备，这是并行监控的代价，用命名和清理入口降低困惑；
- 同一 Host 只允许一条连接，monitor、test、controller 共用一个连接注册表，避免互相替换。

dsh-go 的“一次只连一台”提示不搬。相反，Lagoon 应该在接入主机时明确：

> 每台主机都会在本机注册一个独立设备身份，用于同时保持连接。Hub 的设备列表里会看到对应的
> `Lagoon · 主机名`，删除主机时可一并撤销。

代价与边界：

- 设备数上限（自部署 256，托管以服务端为准；应用内建议 16/32）；
- N 条 WebSocket + N 个轮询的耗电/流量，需要并发上限和启动错峰；
- Hub 设备列表变长，需要命名、批量撤销、残留清理；
- 如果未来上游支持“单身份多连接”，再切到单设备模型；在此之前不改。

---

## 4. 目标信息架构

### 4.1 导航图

```
首页
├─ 服务器胶囊（全部 / 服务器或设备组）    ← 日常切换；聚合粒度按多 Host 设计
│   ├─ 点按：切换当前服务器
│   ├─ 长按：编辑服务器
│   └─ ＋：添加服务器
├─ ⋯ → 设置
│   ├─ 服务器
│   │   ├─ 服务器列表
│   │   ├─ 添加服务器
│   │   ├─ DSH Remote 账号  ← 新增
│   │   │   └─ 远程主机页
│   │   │       ├─ 账号卡
│   │   │       ├─ 正在使用卡
│   │   │       ├─ 主机列表（已添加/未添加、在线、指纹）
│   │   │       ├─ 刷新主机 / 接入主机
│   │   │       ├─ 本机设备（高级，清理）
│   │   │       └─ 退出登录
│   │   └─ 模型显示
│   └─ ...
聊天
├─ 顶栏 ⋯
│   ├─ 子会话与后台 → 后台命令 → 输出（新增）
│   ├─ 文件 / 改动
│   └─ ...
├─ 输入框 ＋
│   ├─ 图片
│   ├─ 手机文件（远程 → 工作区上传；新增）
│   └─ 服务器文件
└─ 排队中消息 → 操作（新增）
```

原则：

- **频繁操作留在首页/聊天**：切换服务器、看连接状态、发消息、看后台输出。
- **低频管理放设置**：账号、主机目录、指纹、设备清理、退出登录。
- **不为远程单开一级根页面**：Lagoon 只有一个根页面；远程主机页是设置里的整页推入。
- **不新增第二个切换入口**：首页服务器胶囊仍是唯一入口；设备组聚合只是同一个入口的粒度变化（见 `DSH_REMOTE_MULTI_HOST.md`），远程页只管理，不抢切换职责。

### 4.2 术语

| 词 | 含义 | 用在哪 |
| --- | --- | --- |
| DSH Remote | 李国宝的 remote 中枢/协议，以及账号服务 | 账号、登录、服务名 |
| 远程主机 | 账号下的一台电脑/服务器（Host） | 远程主机页、主机行 |
| 服务器 | Lagoon 本地保存的连接目标（profile） | 首页胶囊、设置服务器列表 |
| 主机指纹 | Host 身份公钥的 12 位 SHA-256 短指纹 | 信任弹窗、主机详情 |
| 设备身份 | 本机在 DSH Remote 账号里注册的 client device | 高级/清理，不暴露给普通用户 |

一句话：**远程主机是“别人的电脑”，服务器是“本机保存的连接”。** 页面上不要混用。

### 4.3 入口清单

| 用户意图 | 入口 | 形态 |
| --- | --- | --- |
| 第一次连电脑 | 首页 ＋ → 添加服务器 → DSH Remote | 整页/大 sheet 的登录 + 选主机 |
| 再添加一台电脑 | 设置 › 服务器 › DSH Remote → 接入主机 | 远程主机页内 |
| 日常切换 | 首页服务器胶囊 | 现有交互 |
| 看为什么连不上 | ConnectionStrip / 服务器编辑页 / 远程主机页 | 状态条 + 一个修复动作 |
| 看主机详情 | 远程主机页 › 主机行 › 详情 | 整页或 sheet |
| 看网络路径/延迟 | 连接详情 | Bottom sheet |
| 退出账号 | 远程主机页 › ⋯ | 确认对话框 |
| 发文件给远程任务 | 聊天输入框 ＋ → 手机文件 | 上传进度 + 路径 chip |
| 看后台命令输出 | 聊天 ⋯ → 子会话与后台 → 后台命令 | 现有 sheet 扩展 |
| 改排队消息 | 聊天里的“排队中”消息 | 操作 sheet |

### 4.4 反模式（明确不这样放）

| 不要放在 | 原因 | 应该放 |
| --- | --- | --- |
| 添加服务器表单里的账号/密码/Host/指纹 | 把低频账号管理与一次性服务器配置混在一起；Host 列表离开表单就丢 | 远程主机页 + 登录 sheet |
| 首页新增第二个“主机切换”入口 | 服务器胶囊已经是切换器，两个入口会让人不知道哪个是当前 | 沿用服务器胶囊 |
| 聊天 ⋯ 里放主机设置/模型服务商 | 主机级设置会影响所有会话，不是当前会话的资源 | 设置 › 服务器 › 此主机设置 |
| 设置里放上传/后台输出/排队操作 | 这些是任务级动作，离开会话上下文就没有意义 | 聊天输入框 / 后台面板 / 消息本身 |
| 把远程页做成主要切换入口 | 管理页应保持低频；日常切换在首页胶囊，远程页的“使用”只是管理动作 | 首页胶囊；远程页只保留“使用/切换”作为管理动作 |
| 远程主机页里放“在手机上创建主机” | 手机会误导用户填 IP，变成 SSH/直连；Host 只能由电脑注册 | 刷新主机 + 电脑端安装说明 |

---

## 5. 详细设计

### 5.1 DSH Remote 账号与登录

**入口**

- 设置 › 服务器 › DSH Remote（未登录时显示“连接 DSH Remote”）。
- 添加服务器 → 服务类型 DSH Remote。
- 已登录时，服务器编辑页只显示账号摘要和“管理远程主机”，不再显示账号密码输入框。

**登录 sheet**

```
┌ 登录 DSH Remote ─────────────────────┐
│ 连接安装了远程插件的电脑               │
│ 全程端到端加密，不需要配置网络         │
│                                       │
│ [ GitHub 登录 ]                       │
│ [ 知乎登录 ]                          │
│                                       │
│ 在浏览器里完成登录，回到 Lagoon 即可   │
│                                       │
│ 自建服务器 · 邮箱登录                  │
│                                       │
│ 说明：主机需要安装 ds-harness-remote   │
│ 插件并登录同一账号                     │
└───────────────────────────────────────┘
```

**OAuth / QR 流程**

1. `GET /api/v1/auth/oauth/providers`。上游约定 `startUrl` 必有、`qrStartUrl` 可选；
   本版只对带 `qrStartUrl` 的 provider 做免密登录，其余先标注“暂不支持”，避免打开浏览器后回不来。
   每个入口路径必须是同源、以 `/api/v1/auth/` 开头，且拒绝控制字符、空白、反斜杠、`#`、
   `.`/`..` 段和 `%` 编码段（与上游 `_isAuthEntryPath` 一致）。
2. `POST <qrStartUrl>` → `{qrId, scanUrl, expiresIn}`；`scanUrl` 必须与 Server 同源，
   拒绝控制字符；不接受任意外部 URL。
3. 用 **Custom Tabs**（或系统浏览器 `ACTION_VIEW`）打开 `scanUrl`，不要用 WebView（Google 登录会拒绝 WebView）。
4. 每 2 秒 `GET /api/v1/auth/oauth/qr/{qrId}`，处理 `pending` / `expired` / `complete`。
5. `complete` 后用返回的 account token 调 `GET /api/v1/auth/me`，再进入主机选择/注册。
6. account token **只放在内存**，完成设备注册后丢弃；设备 token 进 Keystore。
7. 取消、超时、进程回后台都要停轮询；重新打开时从“未登录”开始，不复用旧 qrId。

**自建服务器**

- 登录 sheet 底部“自建服务器 · 邮箱登录”打开二级表单：服务器地址、邮箱、密码。
- 地址必须是 HTTPS，或用户明确允许的局域网 HTTP。
- 复用现有 `RemoteServerApi.login`；不新造协议。
- 登录成功后同样进入主机选择。

**加载/错误状态**

| 状态 | 文案 | 动作 |
| --- | --- | --- |
| 加载 provider | 正在读取登录方式… | 取消 |
| 等待浏览器 | 在浏览器里完成登录… | 取消 / 复制登录链接 |
| 浏览器打不开 | 没能打开浏览器 | 复制链接 / 重试 |
| 登录超时 | 登录超时，请重新发起 | 重新登录 |
| 服务端不支持 OAuth | 这个服务器不支持浏览器登录 | 自建服务器邮箱登录 |
| 网络失败 | 没能连接登录服务，检查网络 | 重试 |

**不做**

- 不内嵌 WebView 登录；
- 不保存账号密码；
- 不把 account token 长期落盘；
- 不把 Host 名称当可信文本直接拼接。

### 5.2 主机目录与多选接入

**一次登录、批量建档**

```
登录成功
  → GET /api/v1/devices（Host 列表，带 online/platform/harnessVersion）
  → 用户多选（已添加的显示“已添加”，不重复建）
  → 对选中的 Host 逐个 GET /api/v1/devices/{id} 取 identityKey，本地计算 12 位指纹（SHA-256 前 6 字节）
  → 指纹核对页（可一次核对多台）
  → 逐个注册 client 设备（同一 account token）
  → 生成 ServerProfile（每台一个）
  → 连接第一台；其余按 autoConnect 并行监控
```

**选择页**

```
┌ 添加远程主机 ─────────────────────────┐
│ 账号 account@example.com               │
│ 2 台在线 · 1 台离线                    │
│                                       │
│ ☑ 家里的 Mac        macOS · 在线       │
│    Harness 0.2.1-rc.5 · 未添加         │
│ ☑ 公司 Linux        Linux · 在线       │
│    已添加，将在首页切换                 │
│ ☐ 旧笔记本          Linux · 离线        │
│                                       │
│ [ 下一步：核对指纹 ]                   │
└───────────────────────────────────────┘
```

- 已添加的 Host 不可重复勾选；点击行可切到对应服务器。
- 离线 Host 允许添加，但标记“连接时再确认在线”；指纹由 descriptor 的 identityKey 本地计算（同 `RemoteSetup.fingerprint`），不依赖服务端展示值。
- 上限建议 16 台；超过时提示分批。
- 失败时不要静默留下“注册了设备但没有 profile”：能撤销就撤销，不能撤销就记录待清理。

**批量注册**

- 每个 Host 一个 `RemoteDeviceManager`，slot 建议 `host-<hostId>`（比 `profile-<id>` 更稳定，删除再添加可复用身份）。
- 设备名：`Lagoon · <hostName>`；同名时加平台或短 ID；截断到服务端 128 字节限制，去掉控制字符。
- 复用同一个 account token 顺序注册，避免并发触发服务端限流。
- 任一台失败：已完成的其他 Host 可以保留，UI 明确列出“成功/失败”；失败项可重试，不要整批回滚已连接的 Host。

**Host 名与服务器名**

- Host 名来自账号侧，可能变化；服务器名是本机别名，不自动跟随。远程页显示 Host 当前名，首页胶囊显示本机别名。
- 首次接入默认用 Host 名作服务器名；用户改名后不再自动覆盖。

**添加主机 ≠ 在手机上创建主机**

主机是电脑上的远程插件注册的；手机只能发现和接入。空态要写清楚：

> 还没有主机。在电脑上安装并登录 ds-harness-remote 插件，然后点“刷新主机”。

不要做一个“添加主机”按钮却让用户填 IP——那会误导成 SSH/直连。

### 5.3 指纹信任

**首次信任（单台）**

`SuperDialog`，不可点外部关闭：

```
信任这台主机？

「家里的 Mac」首次连接。请在电脑的远程插件状态页核对指纹，
一致后再继续。

  ABCD EF01 2345          [复制]

之后指纹一旦变化，Lagoon 会拒绝连接。

[取消]                 [信任并连接]
```

**多台核对（批量）**

整页或大 sheet，每台一行，展开显示指纹；底部勾选“我已在这台主机上核对以上指纹”，
再启用“信任并添加”。比连续弹 N 个对话框更清楚，也避免用户机械点“确定”。

**指纹变化**

```
身份指纹已变化

「家里的 Mac」这次提供的身份和上次不一致。
可能是主机重装了远程插件，也可能是有人冒充。

上次：1111 2222 3333
这次：AAAA BBBB CCCC

[ ] 我已在这台主机上核对，确认是重装/更换

[取消]                 [重新信任]（危险色）
```

- 默认阻断连接，**不自动 forget pin**。
- 只有勾选确认后才能重新 pin；取消则保持旧 pin，连接失败。
- 错误提示里不要显示完整公钥或原始异常。

**指纹格式**

- 统一格式化为 `ABCD EF01 2345`（3 组 4 位），等宽字体。
- 复制时带空格；存储仍用 12 位十六进制。
- Host 名称是不可信输入：截断、转义，不参与鉴权。

### 5.4 远程主机管理页

**定位**：账号级管理页，不是服务器编辑页。整页推入，顶部 `PageTopBar("远程主机")`。

```
┌ 远程主机 ─────────────────────────────┐
│ [头像] account@example.com             │
│        GitHub · dsh.r2049.cn   刷新  ⋯ │
├ 正在使用 ─────────────────────────────┤
│ ● 家里的 Mac          已连接           │
│   Relay 中继 · 端到端加密   [详情]     │
│   主机指纹已确认                       │
│   [重新连接]（失败时才出现）           │
├ 主机 ────────────────────────────────┤
│ ● 公司 Linux    在线 · 已添加 · 已确认 │
│ ○ 旧笔记本      离线 · 2 小时前        │
│ + 刷新主机                             │
├ 高级 ────────────────────────────────┤
│ 本机设备 · 2 个                  ›     │
│ 退出 DSH Remote                  ›     │
└───────────────────────────────────────┘
```

**账号卡**

- 显示 provider（GitHub/知乎/密码）、account、server origin。
- 刷新按钮只刷新 Host 列表，不重新登录。
- `⋯`：退出登录、复制服务器地址（可选）。
- 未登录时替换为登录卡；不要显示空的主机列表。

**正在使用卡**

- 只在当前服务器是 DSH Remote 且已连接/重连时出现；否则显示“当前未使用远程主机”。
- 状态：已连接 / 重连中 / 失败。
- 副标题：`Relay 中继 · 端到端加密`；失败时显示原因 + 一个动作。
- `详情` 打开网络与连接 sheet。

**主机行**

- 左侧 7dp 状态点：在线/离线/错误。
- 标题：Host 名称；副标题：`平台 · Harness 版本`。
- 右侧：`接入` / `已添加` / `切换` / 当前勾选；指纹未确认时显示“待确认”。
- 点击：
  - 未添加 → 单台接入流程（核对指纹 → 创建 profile；离线也可接入，连接时再确认在线）。
  - 已添加未选中 → 切换服务器。
  - 当前 → 打开主机详情。
- 离线行不可切换，但未添加的离线行仍可接入；已添加的可以打开详情/重试；不要假装在线。

**空态/错误**

- 空：说明 + “刷新主机” + “在电脑上安装远程插件”的帮助文案。
- 列表刷新失败：保留上次列表，顶部一条错误 + “重试”，不要清空。
- 账号失效：整页顶部登录卡，主机列表灰掉；保留服务器资料与缓存。

**自动连接**

- 沿用每台服务器已有的 `autoConnect` 开关（服务器编辑页），远程页不新增全局开关；
  多 Host 并行时，用户可以为每台 Host 单独决定是否后台保持连接。
- 如果要做批量控制，放在“本机设备”同级的高级区，不要放在主界面。

**高级：本机设备**

- 显示“本机在此账号注册了 N 个设备，用于同时连接 N 台主机”。
- 提供“清理失效设备”：只清理本机记录中确认删除的 Host 对应设备；不要一键撤销所有，除非用户明确退出账号。
- 这是解释多设备成本的唯一位置，不要放在主界面吓人。

**退出登录**

- 确认文案：断开所有远程主机，保留本机服务器资料和聊天缓存；可随时重新登录。
- 行为：尽力 `DELETE /api/v1/devices/self` 撤销 N 个设备；失败记录待清理；清设备令牌；保留 pin。
- 退出后服务器胶囊仍在，但连接状态显示“需要登录 DSH Remote”；点按跳远程页。
- 把所有远程 profile 标记为“需要登录”并暂停自动连接/监控，避免后台反复打服务端。
- 不自动删除 profile、草稿、缓存。

### 5.5 首页切换与连接状态

**服务器胶囊仍是唯一入口**：P0 先保持一台 Host 一个 profile、一个胶囊；设备分组是后续独立设计，按 [`DSH_REMOTE_MULTI_HOST.md`](DSH_REMOTE_MULTI_HOST.md) 演进为“设备组 + 组内 harness”。本文不新增第二套切换器，远程页和账号层不依赖胶囊形态。

- 当前远程服务器：实心状态点 + ConnectionStrip。
- 非当前远程服务器：由 `AllServersOverview` 维护；多 Host 并行时正常显示。
- 远程连接失败：胶囊点变警告色；点按后 ConnectionStrip 给出原因和动作。
- 远程主机离线时，任务沿用现有离线 TTL：不继续转圈、不计入运行中，行内回到安静圆点（没有独立的“未知”状态）；恢复连接后对账。
- 不要为远程新增“主机切换”底部 sheet；胶囊已经是切换器。

**ConnectionStrip 扩展**

现有 `ConnectionStrip` 已经能显示阶段、耗时、重试和“立即重试”。Remote 只需要补充：

| 阶段 | 文案示例 |
| --- | --- |
| 账号/设备 | 正在验证 DSH Remote 账号… |
| 读取 Host | 正在读取主机… |
| Relay | 正在建立加密中继… |
| 身份 | 正在验证主机身份… |
| 能力 | 正在协商主机能力… |
| 已连接 | 已连接 · Relay 中继 · 端到端加密 |
| 重连 | 重新连接中 · 第 2 次 · 12 秒后重试 |
| 失败 | 主机现在不在线 / 身份指纹已变化 / 登录已失效 |

- 连接成功后不常驻；点服务器胶囊长按进入编辑页，或从远程主机页看详情。
- 失败时一个动作，不要给三个平行按钮。

**同 Host 单连接**

- `AllServersOverview`、`LagoonController`、`testServer` 不能各自开一条同 Host 连接；
  Host 会用新连接替换旧连接，表现为“随机掉线”。
- 新增进程级 `RemoteConnectionRegistry`：键 `(origin, account, hostId)`，返回共享 `RemoteSession`；
  测试连接时如果是同一 profile，复用现有连接做 health，不要另开。
- 如果 `CONNECTION_REPLACED` 真发生（另一台设备用了同一设备身份，或旧进程残留），
  停止自动重连，显示“连接已被替换” + “重新连接”。

### 5.6 网络与连接详情

Remote 目前只有 Relay，不要伪造 LAN/P2P/TURN。详情 sheet：

```
网络与连接

主机              家里的 Mac
连接方式          DSH Remote
状态              已连接
当前路径          Relay 中继
加密              Noise IK · 端到端加密
连接耗时          1.8 秒
往返延迟          86 毫秒（加密 RPC，非网络层 RTT）
最近观测          14:32
主机版本          DSH 0.2.1-rc.5
远程插件（若返回） 0.6.2
主机指纹          ABCD EF01 2345

当前版本固定使用 Relay；LAN / P2P / TURN 尚未启用。

[重新测量]
```

- 连接耗时：从 `RemoteSession.ensure()` 开始到 `harness.transport.describe` 成功。
- RTT：发一次轻量 `harness.transport.describe` 计时，标注“加密 RPC 往返”；不要写成网络 RTT。
- 路径：只有 Relay 就写 Relay；未来 WebRTC 再加 LAN/P2P/TURN 标签。
- 不显示候选地址、token、ticket、密文、原始异常。
- 未连接时显示“连接后可测量”；测量失败给“稍后重试”，不要显示 0。

**主机详情**

从主机行或服务器编辑页进入，整页或大 sheet：

```
主机详情

家里的 Mac
macOS · Harness 0.2.1-rc.5 · 远程插件 0.6.2
在线 · 最近在线 14:32

身份
指纹 ABCD EF01 2345 · 已确认
[重新验证指纹]

此主机支持
工作区上传 ✓   后台任务 ✓   模型设置 ✓
插件开关 ✓     终端 —       逐文件 diff —

[在首页打开]  [刷新]  [移除此服务器]
```

- 进入不主动开新连接；只读已缓存的能力/版本；需要刷新时复用连接注册表。
- “移除此服务器”与服务器编辑页的删除是同一动作；给出“不影响主机数据”的说明。
- 指纹变化时，详情顶部红色提示 + “重新核对”，不要只藏在弹窗里。
- 能力列表用“✓ / —”，不要显示内部 capability 字符串；高级诊断才展示原始列表。

### 5.7 添加/编辑服务器

**添加远程服务器**

- 服务类型选 DSH Remote 后，不再显示通用 URL/用户名/密码字段。
- 显示登录状态 + 主机选择入口：
  - 未登录：登录卡（OAuth/自建）。
  - 已登录：直接进入主机选择（可多选），或显示最近主机列表。
- 选中主机后进入指纹核对；完成后创建 profile 并返回首页。
- 如果用户只选一台，仍然是“登录 → 选一台 → 核对指纹 → 保存”，但不再手输指纹。
- 在线主机保存前做一次健康检查（复用连接注册表，避免重复连接）；离线主机跳过，标记“待连接”。

**编辑远程服务器**

```
服务器名称        [家里的 Mac]
远程账号          account@example.com · GitHub
主机              家里的 Mac · macOS · Harness 0.2.1
指纹              已确认（ABCD EF01 2345）  ›
连接              已连接 · Relay 中继         ›
通知              [开]
[从此设备移除]
```

- 不显示账号密码、Host 下拉、指纹输入框。
- `指纹`/`连接` 进入主机详情或网络 sheet。
- “从此设备移除”只删本地 profile 和对应设备身份（尽力 revoke），不影响 Host 上的 DSH 和会话。

**多 Host 批量添加后的返回**

- 首页 toast：“已添加 2 台主机，正在连接…”
- 如果用户当前停在“全部服务器”，不要强行切到某一台；让总览自然出现。
- 失败项保留在远程主机页，标“添加失败”，可重试。

### 5.8 聊天：远程工作区文件上传

**入口**：输入框 ＋ → 手机文件。图片仍走现有 image 内联；文本文件仍可内联；**任意文件**走工作区上传。

**流程**

1. 用户选文件。
2. 如果当前 Host 是 Remote、已有当前会话、且 `workspace.upload.v1` 可用：
   - 只检查协议绝对上限 1 GiB；不按 20 MiB 或当前路径硬拦截（Host 才是权威）。
   - 在输入框上方显示上传条：文件名、大小、进度、取消。
   - `workspace.upload.open` → 按 Host 返回的 `chunkBytes`（1–64 KiB）分块 → `commit`。
   - 成功后把 `./<finalName>` 插入草稿，并显示“已上传”chip。
3. 如果能力不可用：
   - 不静默失败；提示“此主机的远程插件不支持上传文件，可发送图片或文本”。
4. 如果 Host 拒绝大小/路径：
   - 显示 Host 的 `UPLOAD_*` / `WORKSPACE_UPLOAD_*` 语义化提示（例如“主机拒绝了这个大小；
     Relay 路径通常限制 20 MiB”），不把 Host 策略写成客户端硬上限。
5. 如果没有当前会话或工作区不可写：
   - Host 返回 `UPLOAD_WORKSPACE_UNAVAILABLE` / `UPLOAD_WORKSPACE_CHANGED` 时，提示先创建/打开
     该会话，不静默回退到内联，也不写到任意目录。

**细节**

- 上传以**当前会话**为作用域（`sessionId`）；不是按 Host 全局目录，也不是按手机文件系统。
- 文件名安全：不覆盖、不穿越、不带路径；以 Host 返回的最终名为准（可能自动加 `(1)`）。
- 不自动重放 `commit`：超时/断线意味着结果未知，文件可能已落盘；提示用户检查，重新上传用新 uploadId。
- 取消：停止等待并 abort；失败 best-effort，不阻塞 UI。
- 从文件 URI 分块读取，不把整个文件读进内存；一次只处理一个上传，其余排队。
- 上传内容、绝对路径、token 不写日志。
- 发送时只把路径作为普通文本；不要伪造 attachment receipt。
- 模型不支持图片时，仍然可以上传文件并引用路径；不要强行内联二进制。
- 界面可以在 Relay 路径下提示“Relay 通常限制 20 MiB”，但只作为说明，不作为本地拦截；Host 返回拒绝时以 Host 为准。

**核心改造**

- `workspace.upload.*` 是**绕过 Typert 网关的独立顶层 RPC**，不是 `harness.remote.call` 的
  endpoint。dsh-go 的注释明确写了 "independent top-level RPCs, not Gateway endpoints"，并直接走
  `session.rpc.call`。Lagoon 不能只改 `RemoteGateway.validEndpoint`；那会把
  `{endpoint,payload}` 包进网关，Host 不会按上传处理器执行。
- 正确路径：在 `RemoteConnection` 上增加直连上传方法，调用 `RemoteRpc.call` 的带点号方法名
  （`RemoteRpc` 的方法正则已允许 `workspace.upload.open` 这类名字）；参数是
  `{sessionId, uploadId, name, totalBytes}` / `{sessionId, uploadId, offset, data}` /
  `{sessionId, uploadId}`，没有 `args` 外壳。
- `RemoteCapabilities` 增加 `supportsWorkspaceUpload`；`HarnessClient`/`DshApi` 增加可选上传接口；
  OpenCode/DSH 直连不需要实现。UI 只依赖能力，不依赖版本字符串。

### 5.9 聊天：后台命令实时输出（停止待上游）

现有“子会话与后台”面板已经能列出运行中的 `ShellJob`，但 DSH 点“输出”没有数据；OpenCode 才走
`/api/shell/{id}/output`。Remote 应使用 `job/follow`。

**交互**

```
聊天 ⋯ → 子会话与后台 → 后台命令
  → 点一条命令 → 现有输出 sheet
      标题：命令首行
      状态：运行中 / 已结束 · 退出码 N / 已停止 / 暂时无法读取输出
      正文：等宽输出，自动跟随底部
```

- `job/list` 取运行中 + 最近结束；当前实现只取运行中，需要扩展状态和保留时间。
- `job/follow` 流式追加；断线后按最后序号/`from` 续读，不能重复整段。
- 输出只保留最近 64 KB 或 1000 行，超出提示“仅显示最近…”。
- 流断开：sheet 内显示“连接断开，重连后继续”，不要直接关闭。

**停止是阻塞项，不是“按能力显示”**

- dsh-go 的 `harnessRemoteAllowlist` 只有 `job/list`、`job/follow`，**没有 `job/kill`**；
  `jobs_page.dart` 用 `allowsEndpoint('job/kill')` 隐藏停止按钮。这是静态 allowlist 拒绝，
  不是 Host 配置差异，也不能靠 capability 探测打开。
- 因此 Lagoon 的 P2 只做“实时输出”，不显示停止按钮；不要先放一个必然失败的停止入口。
- 等上游把 `job/kill` 加入 allowlist（或提供等价能力）后，再按 `job/kill` 约定补停止：
  先确认、显示“正在停止…”，收到状态帧后才写“已停止”。

**放置理由**：后台命令是任务级的东西，属于当前会话；放在聊天 ⋯ 的子会话/后台面板最符合现有
信息架构，不新增首页入口。

### 5.10 聊天：排队消息操作

Lagoon 已经能显示 `queued` 的 inbox 消息，但不能操作。上游 `session/updateQueue` 支持：

- `remove`：撤回；
- `steer`：立即插话；
- `edit`：改文本。

**交互**

- “排队中”pill 可点，或长按排队消息，打开操作 sheet：
  - 编辑（弹输入框，保存后仍排队）
  - 立即插话
  - 撤回
- 操作后乐观更新，失败回滚并提示；不要静默丢消息。
- 只对 `queued` 消息显示；正在执行的消息不显示这些动作。
- 撤回后是否回到输入框：建议回到输入框（带原文），避免用户丢失内容；如果用户选择“删除”，则只撤回。

**放置理由**：这是消息级操作，放在消息气泡/操作 sheet；不要放设置，也不要放主机页。

### 5.11 聊天：可继续子代理（P3）

Lagoon 现在把子会话当只读：有状态栏、没有输入框。这个默认对一次性子代理是对的，但 DSH 有
`subagents/prompt` 和 `subagents/interruptByParent`，dsh-go 也允许“可继续的子代理点开接着聊”。

**设计**

- 子会话底部状态栏增加“继续”按钮，仅在 Host 报告该子代理可继续时显示。
- 点击打开一个轻量输入 sheet（或切到可输入的聊天态），发送走 `subagents/prompt`，不是新根会话。
- 文案明确：“继续此子代理 · 结果会回到父会话”。
- 一次性子代理保持只读；没有能力时不显示按钮，不要用“输入后报错”探测。
- 中断：`subagents/interruptByParent`，只在子代理运行中显示。
- 放置理由：子会话的身份和父子关系在会话页，不应该跳回主机页或设置。

**待确认**：这是否符合 Lagoon “子会话只读”的产品原则；如果团队坚持只读，则本条降为不做。

### 5.12 主机设置（P2）

远程 DSH 连上后，如果电脑上没配模型，Lagoon 只能看到空模型目录。dsh-go 把主机设置做成原生页；
Lagoon 至少要有“模型服务商/密钥”的入口，否则远程接入是半成品。

**范围建议**

- P2 第一版只做“模型服务商”：
  - `llm/listProviders` / `llm/listConfigurableProviders` / `llm/discoverModels`
  - `credentials/describe` / `set` / `unset`
  - `settings/update` 相关模型默认项
- 入口：设置 › 服务器 › [远程主机] › 此主机设置。
- 不在聊天 ⋯ 里放主机级设置；聊天 ⋯ 只放会话资源。
- 表单风格沿用 MIUIX 设置卡；密钥只显示“已设置/未设置”，不回显。
- 复杂 schema（引擎参数、插件配置）放 P3，不要第一版全量渲染。

**插件开关（P3）**

- `pluginManager/listBundles` / `listPlugins` / `setBundleEnabled` / `setPluginEnabled`。
- 只做官方 allowlist 的开关；不提供安装/卸载/任意插件方法。
- 入口：此主机设置 › 插件；不要在聊天里。

**文件预览（P3）**

- 文本/图片已有；二进制走 `workspaceFiles/readBytes`，Office 走 `officeToPdf/render` 转 PDF 预览。
- 入口：聊天 ⋯ › 文件面板；不要为预览单独开首页入口。
- Remote 没有 HTTP 文件路由；只能走上述 RPC，失败时显示“此文件暂不支持预览”，不要回退到下载整个目录。
- 大文件先读窗口，不要一次拉全量；具体上限用 Host 返回值，不硬编码。

**定时任务/目标（P3）**

- 定时任务：`schedule/list|catalog|history|update|delete`，主机级页面，放在“此主机设置 › 自动化”。
- 目标：`goals/*`，会话级，放在输入框上方（类似 dsh-go 的目标条），但这是独立产品能力，
  不要塞进这次 Remote 首批。
- 这两个不是 Remote 专属；先确认 Lagoon 是否要做，再谈落位。

### 5.13 不做 / 暂不做

| 能力 | 原因 |
| --- | --- |
| SSH 主机 | 第二个远程产品：跳板、启动 dsh web、端口转发、私钥/口令管理；与 DSH Remote 重复 |
| 本机 DSH 运行时 | Lagoon 是控制端，不是移动端引擎 |
| 手机操控 / 桌宠 / 分享链接 / 插件市场 | dsh-go 的产品特色，不是 Remote 能力 |
| 远程网页版 | Remote 没有 HTTP 通道；不要放一个必然打不开的入口 |
| 终端 | 上游 `terminal/attach` 需要双向流；Remote carrier 不支持 uplink，当前无法可靠实现 |
| 逐文件 diff | 依赖 `/api/changes.diff` HTTP 路由；Remote 没有 HTTP，Lagoon 已把 diff 能力关掉 |
| 会话日志导出 | 依赖 HTTP `/api/session.export`；Remote 不可用 |
| WebRTC LAN/P2P/TURN | 成本高（原生依赖、ICE/TURN、信令、选路、回落），且自部署 Hub 未必支持；P4 单独评估 |

---

## 6. 状态、错误与数据

### 6.1 Remote 失败分类

现在 `RemoteSession` 抛出的多是 `IOException("Remote Host 离线")` 一类字符串；UI 只能显示，
不能决定“是否重试、是否重新登录、是否重新信任”。建议引入 typed failure：

```kotlin
sealed class RemoteFailure {
  data object HostOffline : RemoteFailure()
  data object AuthRequired : RemoteFailure()
  data object DeviceRevoked : RemoteFailure()
  data class TrustRequired(val hostId: String) : RemoteFailure()
  data class TrustChanged(val hostId: String, val saved: String, val received: String) : RemoteFailure()
  data object ConnectionReplaced : RemoteFailure()
  data object Incompatible : RemoteFailure()
  data class Rejected(val code: String?) : RemoteFailure()
  data class Network(val retryable: Boolean) : RemoteFailure()
}
```

| 失败 | UI 文案 | 主操作 | 自动重连 |
| --- | --- | --- | --- |
| `HOST_OFFLINE` | 主机现在不在线 | 刷新 / 重试 | 是，退避 |
| `ACCOUNT_AUTH_REQUIRED` / `TOKEN_EXPIRED`（终态） | DSH Remote 登录已失效 | 重新登录 | 否 |
| `DEVICE_REVOKED` | 本机设备已被移除 | 重新登录并注册 | 否 |
| `DEVICE_NOT_FOUND` | 账号下找不到这台主机 | 刷新主机列表 | 否 |
| `PEER_IDENTITY_MISMATCH` | 主机身份指纹已变化 | 查看并重新信任 | 否 |
| `HOST_NOT_TRUSTED` | 尚未确认主机指纹 | 核对指纹 | 否 |
| `CONNECTION_REPLACED` | 这台主机已在另一台设备上使用 | 重新连接 | 否 |
| `HARNESS_VERSION_INCOMPATIBLE` | 主机 DSH/远程插件版本不兼容 | 查看更新方法 | 否 |
| `CONNECTION_REJECTED` | 主机拒绝了这台手机 | 查看主机详情 / 重试 | 否 |
| `MEMBERSHIP_REQUIRED` | 主机不在这个账号下 | 刷新主机列表 | 否 |
| `RATE_LIMITED` | 请求太频繁 | 稍后重试 | 是，长退避 |
| `CONNECTION_FAILED` / `RPC_TIMEOUT` | 重新连接中… | 立即重试 | 是，1s→30s 抖动 |

实现要点：

- 不要在 `ensure()` 里把所有异常都包成 `IOException`；保留 code、status、retryable。
- 终端失败要“粘住”：停止自动重连，等用户动作；否则会反复打服务端。
- `RemoteRelay` 解析 `connect.rejected` / `error` 的 payload，不要只抛“DSH Hub 拒绝连接”。
- UI 只认 typed failure，不解析字符串。

### 6.2 数据模型

在现有 `ServerProfile` 之上增加账号/Host 目录，不改变“一台 Host 一个 profile”：

```kotlin
data class RemoteHubKey(val origin: String, val account: String)

data class RemoteHub(
  val key: RemoteHubKey,
  val provider: String?,          // github / zhihu / password
  val deviceLabel: String         // 仅诊断/清理页显示；不同步 Host 目录
)

data class RemoteHostMeta(
  val hostId: String,
  val name: String,
  val platform: String,
  val online: Boolean,
  val lastSeenAt: Long?,
  val harnessVersion: String?,
  val pluginVersion: String?,
  val fingerprint: String?,       // 本地由 identityKey 计算，不信任服务端展示值
  val trust: TrustState,
  val added: Boolean,
  val machineId: String? = null   // 可选：Host 在 describe 上报，仅用于设备分组建议
)

data class RemoteDeviceGroup(
  val id: String,
  val label: String,
  val key: String?,               // machineId / name+platform；手动组为 null
  val hostIds: Set<String>,       // 视图层；权限仍按 hostId/设备身份
  val confirmed: Boolean,
  val aliases: Map<String, String>
)

enum class TrustState { UNKNOWN, PINNED, CHANGED }
```

存储（与 [`DSH_REMOTE_MULTI_HOST.md`](DSH_REMOTE_MULTI_HOST.md) 对齐，避免两套目录）：

- `remote-hubs`：`origin|account -> RemoteHub`，只存账号/设备元数据，**不内嵌 Host 列表**。
- `remote-host-catalog`：`origin|account -> Map<hostId, RemoteHostMeta>`，Host 目录的**唯一真相源**；
  UI 的账号页、服务器编辑页、设备组都从它读取。
- `remote-device-groups`：`origin|account -> List<RemoteDeviceGroup>`，仅展示/分组，不参与鉴权；
  合并/拆分/别名以它为准，`machineId` 只是自动建议。
- `remote-devices`：已有 `RemoteDeviceStore`，每 Host 一个 slot；新增 `host-<hostId>` 作为新 slot。
- `remote-pins`：已有 `RemotePeerTrustStore`，继续按 `origin + account + hostId`。
- `ServerProfile` 增加可选的 `remoteHostName` / `remoteAccountId` 缓存字段；
  `remoteHostId` / `remoteHostFingerprint` 保持不变。
- 不把 Host 目录写进 profile；目录是账号级，profile 是连接级。

### 6.3 迁移

- 现有 DSH_REMOTE profile：保持原 slot/身份，不重建，避免 Host 重新 TOFU。
- 首次打开远程主机页：
  - 按 `(url, account)` 聚合现有 profile；
  - 用 `api.host` 补 `remoteHostName` / `platform` / 版本；
  - 已有 pin 的显示“已确认”；没有的显示“待确认”，下次连接走信任弹窗；
  - 不要求重新登录，除非用户要添加新 Host 或设备令牌失效。
- 新增 Host 用 `host-<hostId>` slot；如果同 Host 已有旧 profile，复用旧 slot，不重复注册。
- 删除 profile：尽力 revoke 对应设备；失败记录待清理；不删除 Host 数据。
- 退出账号：撤销本机所有设备（best effort）；保留 profile、缓存、pin；服务器显示“需要登录”。

### 6.4 安全不变量

- Host 名称、平台、版本都当作不可信展示字段；截断、转义，不参与鉴权。
- 指纹只在显式确认后 pin；密钥变化不自动覆盖。
- account token 只在内存；设备 token 只进 Keystore；密码不落盘。
- OAuth 的 provider 入口必须是同源 `/api/v1/auth/` 路径，拒绝控制字符、空白、反斜杠、`#`、`.`/`..` 段和 `%` 编码段；`scanUrl` 必须同源且无控制字符；禁止任意外部跳转。
- 不使用 WebView 登录；Custom Tabs / 系统浏览器不注入 JS。
- 日志/诊断不包含 token、ticket、密文、文件内容、完整路径；RTT 失败不暴露候选地址。
- 上传不覆盖已有文件；commit 超时不自动重试；abort 不等于撤销已提交文件。
- `workspace.upload` 只写当前会话工作区根目录；以 Host 返回的 `./name` 为准，不自行拼目标目录。

### 6.5 能力门控

`RemoteCapabilities` 增加：

```kotlin
val sessionFormat: Int?
val supportsWorkspaceUpload: Boolean   // workspace.upload.v1
val supportsTransfer: Boolean          // harness.remote.transfer.v1
val supportsTerminal: Boolean          // harness.terminal.v1 + uplink（当前 false）
val supportsOfficePreview: Boolean     // officeToPdf/render 可用（可探测）
```

UI 规则：

- 没有 `workspace.upload.v1`：隐藏“上传到工作区”，保留图片/文本。
- 没有 `job/follow`：后台命令只显示状态，不显示“输出”。
- Remote allowlist 没有 `job/kill`：不显示“停止”入口；等上游加入后再做。
- `harness.remote.v3` 缺失：拒绝连接，提示更新 Host；不要按版本号猜。
- 未知/不支持：明确说明，不静默降级到其他后端。

---

## 7. 视觉与文案

### 7.1 MIUIX 映射

| DSH Go 的形态 | Lagoon 的形态 |
| --- | --- |
| 远程主机 Settings 列表 | `PageTopBar` + `SmallTitle` + `Card` + 自定义主机行 |
| 活动主机卡 | `Card`（primary subtle 底），状态点 + 两行文字 + 右侧动作 |
| 主机行 | `Card` 内 56–64dp 行，7dp 状态点，标题/副标题，右侧勾选/chevron |
| 指纹弹窗 | `SuperDialog` + 代码风格指纹块 + 复制 |
| 网络详情 | `SuperBottomSheet`，0.78 高，键值行 |
| 登录 pending | 行内 spinner + “在浏览器里完成登录…” + 取消 |
| 错误 footer | `footnote2` 错误色 + 一个 `TextButton` 动作 |
| 上传进度 | 输入框上方窄条，文件名 + 进度 + 取消 |

不要引入：

- 玻璃拟态、渐变 hero、巨大插画；
- 新的底部 Dock；
- 与 Qoder 聊天主题冲突的卡片；
- 多个同权重主按钮。

### 7.2 关键线框

**添加流程**

```
[添加服务器] → 服务类型 DSH Remote
   ↓
[登录 DSH Remote]  GitHub / 知乎 / 自建
   ↓（浏览器）
[选择主机]  ☑ Mac  ☑ Linux  ☐ 离线旧机
   ↓
[核对指纹]  ABCD EF01 2345  ☑ 我已核对
   ↓
[添加并连接] → 首页胶囊出现 2 台
```

**后台命令输出**

```
┌ 后台命令 ─────────────────────────────┐
│ pnpm test                    运行中   │
│ 已运行 00:42                           │
│ ┌───────────────────────────────────┐ │
│ │ > vitest run                      │ │
│ │ ✓ 128 passed                      │ │
│ │ ...                               │ │
│ └───────────────────────────────────┘ │
│ 仅显示最近 64 KB                       │
└───────────────────────────────────────┘
```

**上传**

```
┌ 输入框上方 ───────────────────────────┐
│ report.pdf  2.4 MB  ▓▓▓▓▓░░ 68%  取消 │
└───────────────────────────────────────┘
发送时草稿自动带：已上传文件：./report.pdf
```

### 7.3 文案

| 场景 | 文案 |
| --- | --- |
| 登录标题 | 登录 DSH Remote |
| 登录说明 | 连接安装了远程插件的电脑；全程端到端加密，不需要配置网络 |
| 等待浏览器 | 在浏览器里完成登录… |
| 主机空态 | 还没有主机。在电脑上安装并登录 ds-harness-remote 插件，然后点“刷新主机” |
| 首次信任 | 信任这台主机？ |
| 指纹说明 | 请在电脑的远程插件状态页核对指纹，一致后再继续 |
| 指纹变化 | 身份指纹已变化。可能是主机重装了远程插件，也可能是有人冒充 |
| 已连接 | 已连接 · Relay 中继 · 端到端加密 |
| 主机离线 | 主机现在不在线 |
| 登录失效 | DSH Remote 登录已失效 |
| 版本不兼容 | 主机上的 DSH 或远程插件需要更新 |
| 连接被替换 | 这台主机已在另一台设备上使用 |
| 上传不支持 | 此主机的远程插件不支持上传文件，可发送图片或文本 |
| 上传被拒 | 主机拒绝了这个文件（大小/路径）；Relay 路径通常限制 20 MiB，以主机返回为准 |
| 排队操作 | 编辑 / 立即插话 / 撤回 |
| 子代理继续 | 继续此子代理 · 结果会回到父会话 |

语气：短句、陈述、给动作；不用感叹号；不把技术细节（Noise、Noise IK、ticket）放在主文案。

---

## 8. 迭代计划与验收

### 8.1 阶段

| 阶段 | 范围 | 规模 | 依赖 |
| --- | --- | --- | --- |
| P0 账号与接入 | OAuth/QR、自建登录、Host 目录、多选批量注册、指纹核对、远程主机页、添加/编辑服务器改造 | M–L（1.5–2 周，含测试） | 上游 OAuth 端点；现有 `RemoteServerApi` |
| P1 连接韧性 | typed failure、连接阶段、同 Host 单连接注册表、重连/接管、网络详情 | M（约 1 周） | P0 的 failure/registry |
| P2 远程任务与主机设置 | 工作区上传、`job/follow`、排队消息操作、能力门控；模型服务商/密钥 | M–L（1.5–2 周） | 上游 allowlist；`RemoteConnection` 直连顶层上传；Host 设置/凭据接口 |
| P3 主机扩展 | 插件开关、Office 预览、子代理续聊 | L（2–4 周） | 官方 allowlist；产品确认 |
| P4 传输升级 | WebRTC LAN/P2P/TURN、大文件 | XL（独立项目） | 原生 WebRTC、ICE/TURN、信令 |

多 Host 设备分组、Hub 补丁按 [`DSH_REMOTE_MULTI_HOST.md`](DSH_REMOTE_MULTI_HOST.md) 的节奏走，
不阻塞 P0/P1。Host 目录以 `remote-host-catalog` 为唯一真相源；设备组只是它的视图，不再存一份 Host 列表。

如果希望缩短首个 PR：P0a 先做 OAuth + 单台接入 + 信任弹窗；P0b 再做多选批量。账号/目录的数据结构一次设计好，避免返工。

### 8.2 验收

**P0**

- 托管 Hub：GitHub（或服务端实际提供的 provider）登录成功后回到 App，列出 Host。
- 自建 Hub：邮箱密码登录；HTTP 地址在未授权时拒绝。
- 一次登录勾选 2 台 Host，注册 2 个设备；Hub 管理页看到 2 个 `Lagoon · 主机名`；两台同时 online。
- 指纹首次确认后保存；拒绝则不创建 profile。
- 密钥变化时连接被阻断；勾选确认后重新信任并恢复。
- 添加已存在的 Host 不重复注册设备。
- 退出登录撤销本机所有设备；profile/缓存/草稿保留。

**P1**

- Host 离线、登录失效、设备被撤销、指纹变化、版本不兼容、连接被替换，各自显示正确文案和动作。
- 终端失败不再自动重连；手动重试可恢复。
- monitor/test/controller 不会同时开同 Host 连接；不会出现随机掉线。
- 网络详情显示 Relay、连接耗时、RPC 往返；未连接时不可测量。
- 断网/切 Wi-Fi 后自动重连，状态条能解释当前阶段。

**P2**

- 远程上传一个 Host 接受的文件成功，草稿出现 `./name`；同名自动改名；客户端只按 1 GiB 协议绝对上限预检，不按 20 MiB 硬拦截；Host 拒绝时显示具体原因。
- 上传中断不自动重放 commit；失败可取消/重试。
- 后台命令输出实时追加；Remote 不显示停止按钮（`job/kill` 不在 allowlist），不出现“点了报错”。
- 排队消息可撤回、编辑、立即插话；失败回滚。
- 不支持的功能不显示入口，不出现“点了报错”。
- 远程主机可配置模型服务商/密钥，模型目录刷新。

**P3**

- 插件开关只作用于 allowlist；安装/卸载不提供。
- 可继续子代理能续聊；一次性子代理仍只读。
- Office/PDF 预览可用；不支持时给出明确提示。

### 8.3 测试

- 单元：OAuth provider 解析、同源校验、QR 状态机、指纹格式化/比较、failure 映射、能力门控、上传分块 ack、queue action、job 帧。
- 集成：MockWebServer 模拟 REST；假 Host 模拟 Noise/RPC/stream；多 Host 两条连接隔离；同 Host 单连接注册表。
- 真机/真实 Host：托管 OAuth（如有账号）、自建 Hub、两台 Host 并行、离线/重连、指纹变化、一个实际允许大小的上传（Relay 常见 ≤20 MiB，LAN/P2P 可更大）、后台命令、排队消息。
- 明确记录“未验证”的项；单元测试不等于互操作完成。

---

## 9. 风险与开放问题

### 9.1 风险

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| Remote 是 preview，协议漂移 | 端点/字段变化 | 能力探测、显式失败、按 allowlist 实现 |
| OAuth 端点主要见于托管 Hub，自建 Server 可能未配置 | 自建 Hub 无浏览器登录 | 保留邮箱密码；provider 为空时降级 |
| 多 Host 多设备导致 Hub 设备列表变长 | 用户困惑、设备上限 | `Lagoon · 主机名` 命名、删除时 revoke、高级清理 |
| account token 只有短期有效 | 后续加 Host 要重新登录 | OAuth 登录成本低；主机列表刷新用 device token，不需要重登 |
| 同 Host 多连接互相替换 | 随机掉线 | 连接注册表 + 测试连接复用 |
| Remote allowlist 不含 `job/kill` | 停止后台任务暂不可用 | P2 只做输出，不显示停止；等上游加入 allowlist |
| Host/路径大小限制（Relay 常 20 MiB，LAN/P2P 可达 1 GiB） | 大文件在 Relay 下不可用 | 客户端不硬编码；Host 拒绝时解释；WebRTC 后放宽 |
| WebRTC 成本高 | 包体、复杂度、测试 | P4 独立评估，不阻塞 |
| AGPL 代码混入 | 授权风险 | 只看行为与协议，不复制 dsh-go 代码 |

### 9.2 需要拍板

1. 多 Host 并行是否继续作为 Lagoon 的默认能力？（本文按“是”设计；如果是“否”，账号层可以简化成 dsh-go 的单设备模型。）
2. 托管 Hub 的 OAuth 是否是首发必达？自建 Hub 用户是否接受邮箱密码？
3. 后续添加 Host 时，接受“重新走一次浏览器登录”，还是短期保存 account token？
4. 主机设置 P2 只做模型，还是一开始就做插件/引擎参数？
5. 子代理续聊是否违背“子会话只读”的现有原则？
6. 是否把“设备分组/首页按设备聚合”与本文同批做？建议分开。
7. WebRTC 是否在下一个大版本的目标里？如果不是，网络详情只做 Relay 版本。

---

## 附录 A：端点与 Lagoon 使用情况

| 端点/方法 | 用途 | Lagoon 现在 | 本文建议 |
| --- | --- | --- | --- |
| `GET /api/v1/auth/oauth/providers` | 登录方式 | 未用 | P0 |
| `POST <qrStartUrl>` | 发起 QR/浏览器登录 | 未用 | P0 |
| `GET /api/v1/auth/oauth/qr/{id}` | 轮询登录 | 未用 | P0 |
| `GET /api/v1/auth/me` | 账号名 | 未用 | P0 |
| `POST /api/v1/auth/login` | 自建/邮箱登录 | 已用 | 保留，挪到登录 sheet |
| `POST /api/v1/devices/register` | 注册 client 设备 | 已用（每 profile 一个） | 一次登录批量注册 |
| `GET /api/v1/devices` | Host 目录 | 已用（配对时） | P0 常驻目录 |
| `GET /api/v1/devices/{id}` | Host 详情/公钥/在线 | 已用 | P0 懒加载 + 指纹 |
| `GET /api/v1/devices/{id}/presence` | 在线状态 | 未用 | P0/P1 |
| `DELETE /api/v1/devices/self` | 撤销本机设备 | 未用 | P0 退出/删除 |
| `harness.transport.describe` | 能力/版本 | 已用 | 扩展 capability |
| `workspace.upload.open/chunk/commit/abort` | 工作区上传（顶层 RPC，不经 Gateway） | 未用 | P2 |
| `job/list` | 后台任务 | 已用（仅运行中） | 扩展状态 |
| `job/follow` | 输出流 | 未用 | P2 |
| `job/kill` | 停止 | 未用 | 阻塞：不在 Remote allowlist，等上游 |
| `session/updateQueue` | 排队编辑/撤回/插话 | 未用 | P2 |
| `subagents/list` / `prompt` / `interruptByParent` | 子代理 | 未用 | P3 |
| `settings/describe` / `update` | 引擎设置 | 未用 | P3（模型默认项随 P2） |
| `llm/*` / `credentials/*` | 模型服务商/密钥 | 未用 | P2 |
| `pluginManager/*` | 插件开关 | 未用 | P3 |
| `officeToPdf/render` | Office 预览 | 未用 | P3 |
| `schedule/*` | 定时任务 | 未用 | P3 |
| `goals/*` | 长期目标 | 未用 | P3 |
| `terminal/*` | 终端 | 未用 | 不做（Remote 无 uplink） |
| `/api/changes.*` | 逐文件 diff | 仅直连 | 不做（Remote 无 HTTP） |
| `/api/session.export` | 会话导出 | 未把 DSH 导出做成入口 | 不做（Remote 无 HTTP） |

## 附录 B：参考

- Lagoon 现有接入基线：[`DSH_REMOTE_INTEGRATION.md`](DSH_REMOTE_INTEGRATION.md)
- Lagoon 多 Host 设计：[`DSH_REMOTE_MULTI_HOST.md`](DSH_REMOTE_MULTI_HOST.md)
- Lagoon 协议适配：[`dsh-protocol.md`](dsh-protocol.md)
- dsh-go 远程包说明：[`packages/dsh_remote/README.md`](https://github.com/ccch1mneyyy/dsh-Go/blob/main/packages/dsh_remote/README.md)
- dsh-go 远程页：[`RemoteScreen`](https://github.com/ccch1mneyyy/dsh-Go/blob/main/app/lib/ui/remote/remote_screen.dart)
- dsh-go 连接状态：[`link_status.dart`](https://github.com/ccch1mneyyy/dsh-Go/blob/main/app/lib/remote/link_status.dart)
- dsh-go 网络详情：[`network_details_sheet.dart`](https://github.com/ccch1mneyyy/dsh-Go/blob/main/app/lib/ui/remote/network_details_sheet.dart)
- ds-harness-remote 协议：[`docs/protocol.md`](https://github.com/liguobao/ds-harness-remote/blob/479b94341c0d0de63846f21d0fcca26997d158d1/docs/protocol.md)
- ds-harness-remote 工作区上传：[`protocol.md#workspace-upload`](https://github.com/liguobao/ds-harness-remote/blob/479b94341c0d0de63846f21d0fcca26997d158d1/docs/protocol.md#workspace-upload)

---

## 一句话总结

**把 dsh-go 的“账号 → 主机 → 连接 → 恢复”产品闭环借过来，但保留 Lagoon 的多 Host 并行；
先在设置里把账号和主机管理做对，再在聊天里补齐上传、后台输出和排队操作，最后才谈主机设置和 WebRTC。**
