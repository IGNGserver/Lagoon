# 官网定位与范围（SITE）

> 本文为后续开发者（含 AI 会话）提供站点上下文。范围或技术决策变化时同步更新本文件。

## 是什么

Lagoon 的产品官网：介绍产品、展示界面、提供下载入口与文档链接，并作为搜索引擎与
AI 工具的可引用入口。站点源码位于产品仓库的 `website/`：

| 事项 | 归属 |
| --- | --- |
| 产品功能、版本、发布资产、产品文档 | 产品仓库 `IGNGserver/opencode-lagoon`（`website/` 之外） |
| 页面、样式、静态资源、SEO、站点文案 | `IGNGserver/opencode-lagoon/website/` |

## 当前状态（2026-10-25）

- 单页官网 `website/site/index.html` + `website/site/404.html`，桌面与移动端经浏览器实测；
- 已部署到 Cloudflare Pages，正式地址 `https://lagoon.igng.net`（默认别名 `https://lagoon-website.pages.dev`）；
- `.github/workflows/website.yml` 在 `main` 和正式发版后自动构建并部署；当前线上版本来自
  0.2.4 稳定版 / 0.3.0-rc.10 预览版；
- 旧的独立仓库 `IGNGserver/lagoon-website` 已归档，代码与历史以产品仓库为准。

## 已定案的决策

| 事项 | 结论 |
| --- | --- |
| 技术栈 | 零构建静态站点：HTML + CSS + 原生 ES modules + 原生 WebGL，无前端依赖；只有 Node 标准库的版本注入脚本 |
| 发布目录 | 源文件 `website/site/`，部署前由 `website/scripts/build-site.mjs` 生成 `website/dist/` |
| 页面清单 | 单页（海面 / 连接 / 会话 / 实时更新 / 能力 / 航道图 / 下载）+ 404 |
| 语言 | 简体中文（`zh-CN`），与产品仓库主文档一致 |
| 视觉方向 | “一次下潜”：见下文 |

## 视觉语言

- **叙事**：页面是一次从海面到 200 m 深处的下潜。右侧深度计随滚动读数，每个章节标注深度（0 / −18 / −46 / −80 / −120 / −160 / −200 m）。
- **背景**：全屏 WebGL 片元着色器（`website/site/assets/js/ocean.js`）。`uDepth` 由滚动驱动：海面是焦散网与斜射光束，越深越暗，
  生物荧光微粒越多；指针在水中搅出涡流。无 WebGL 时退化为 `.ocean-fallback` 渐变；`prefers-reduced-motion` 时只渲染静帧。
- **材质**：液态玻璃——`backdrop-filter` 模糊 + 遮罩渐变描边（折射边）+ 跟随指针的镜面高光（`[data-glass]`）。
- **色彩**：取自应用图标——底色 `#0A1838`、靛蓝 `#4752D6` → 潮青 `#59D2E6`、荧光点 `#52E0C4`；状态色沿用 App（琥珀 `#FFB817`、珊瑚 `#E87575`）。
- **字体**：拉丁字符 Sora、等宽 JetBrains Mono（均为 OFL，自托管于 `website/site/assets/fonts/`，附许可证）；中文走系统字体栈。
- **3D 手机**：纯 CSS 3D（多层 `translateZ` 叠出机身厚度），屏幕内是可动的 HTML 界面重绘；移动端用 `zoom` 等比缩放整机。
- **Blender 资产**：`website/site/assets/img/mark-surface.{webp,jpg}` 与 `og.jpg` 由 Blender 5.2（Cycles）渲染的液态玻璃 Lagoon 标志，
  几何取自产品仓库 `ic_notification.xml` 的矢量路径。

## 页面结构与交互

| 章节 | 内容 | 交互 |
| --- | --- | --- |
| 海面 `#surface` | 标语、下载 / 源码按钮、版本信息 | 3D 手机随指针与滚动倾斜；首页列表状态与状态栏胶囊（`x跑·y完`）循环变化 |
| 连接 `#connect` | 直连、Keystore、断线对账、明文授权 | SVG 节点图：正式版点按切换当前服务器；预览版全部保持连接、点按模拟断线重连 |
| 会话 `#session` | 下达任务、操作轨迹、授权、运行中引导、子会话 | 滚动叙事：左侧步骤驱动吸顶手机里的聊天界面 |
| 实时更新 `#live` | Android 16 Live Updates 与旧系统回退 | 模拟器：增减任务，标题 / 胶囊 / 分段轨道 / 阶段按 App 规则实时变化 |
| 能力 `#depths` | 正式版 v0.2.4 的能力卡片 | 玻璃卡片镜面高光 |
| 航道图 `#roadmap` | 正式版 / 预发布版 / 规划方向 + 验收边界 | — |
| 下载 `#download` | 下载卡、三步上手、FAQ | 复制 `opencode pair` |

模拟器的文案与阶段规则镜像产品源码 `core/TaskSummary.kt`、`core/LiveUpdateContent.kt`（v0.2.4），改动时先核对源码。

## 内容原则

- 只陈述产品仓库当前支持的事实，不编造路线图；版本与平台矩阵以产品仓库 `README.md` 与 Releases 为准；
- 下载链接指向产品仓库 Releases 的稳定直链（安装包命名 `Lagoon-<version>.apk`），不在本仓库托管安装包；
- 产品处于快速迭代期，`Latest` 只认正式版；引用版本时明确区分正式版与 pre-release；
- 预发布版独有的能力（如 v0.2.5-rc 的“全部服务器”模式、手机端授权开关）只能出现在标注为预览的位置；
- 规划方向只写产品仓库明示的内容（目前仅“适配更多 harness”），不写时间承诺；
- 界面图均为依据实际布局的 HTML 重绘，演示数据不得冒充真实用户数据，并保留“界面示意”标注；
- 文案与产品仓库命名保持一致：显示名 `Lagoon`，应用标识 `com.igng.opencode.lagoon`，深链接 `lagoon://`。

## 版本更新清单

产品发布新正式版或预发布版时，版本/日期/APK 名/大小/下载链接由 `website/scripts/build-site.mjs`
自动注入，不再手工改这些字段。仍然需要人工检查的是内容层面的变化：

1. 新正式版吸收的预发布能力，从“预览中”移到“已发布”与“能力”章节，并复核“连接”章节两种模式的说明；
2. 如果页面文案引用了具体版本（例如“依据 v0.2.4 实际布局重绘”），按新版本核对；
3. 跑 `node scripts/build-site.mjs`、`python3 scripts/check-site.py dist` 与浏览器验证。

构建脚本取最新非 prerelease 作为正式版、最新 prerelease 作为预览版；如果某个渠道不存在，
脚本会以非零状态退出，避免发布一个缺版本的页面。

## 浏览器验证

页面改动必须在真实浏览器里看过再合并，至少覆盖：

- 视口：桌面 1440×900、移动 390×844（触屏模拟）；逐章节截图检查布局、溢出（`scrollWidth` 不得大于视口宽度）与文字遮挡；
- 交互：实时更新模拟器各按钮与 Android 版本切换、连接图两种模式、会话滚动叙事 5 步、复制按钮、移动端菜单、FAQ；
- 降级：禁用 WebGL（`--disable-webgl`）与 `prefers-reduced-motion: reduce` 下页面可读、无脚本错误；
- 控制台无错误、无 4xx/5xx 资源请求。

无 GPU 的虚拟机里浏览器常禁用 WebGL，可用 Playwright 的 headless Chromium 加
`--use-gl=angle --use-angle=swiftshader --enable-unsafe-swiftshader` 渲染着色器；Playwright 装在 `$BUILD_SCRATCH_ROOT`，不进仓库。
本地预览：`cd website && node scripts/build-site.mjs && python3 -m http.server 4173 --bind 127.0.0.1 --directory dist`。
