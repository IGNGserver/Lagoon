# 托管与部署（Cloudflare Pages）

> 正式站点：<https://lagoon.igng.net/>。站点源码在产品仓库 `opencode-lagoon/website/`，部署由
> `.github/workflows/website.yml` 完成。旧的独立仓库 `IGNGserver/lagoon-website` 已归档，仅作历史。

## 当前配置

| 项 | 值 |
| --- | --- |
| Pages 项目 | `lagoon-website`（Direct Upload） |
| 部署来源 | `IGNGserver/opencode-lagoon` 的 `website/` |
| 部署工作流 | `.github/workflows/website.yml` |
| 生产分支 | `main` |
| 构建命令 | `node scripts/build-site.mjs`（在 `website/` 下，无 npm 依赖） |
| 发布目录 | `website/dist/` |
| 自定义域名 | `https://lagoon.igng.net/`（zone `igng.net`） |
| 默认别名 | `https://lagoon-website.pages.dev/`（保留） |
| DNS | proxied CNAME `lagoon.igng.net` → `lagoon-website.pages.dev` |

`build-site.mjs` 从 GitHub Releases 取最新正式版和预览版，注入版本号、日期、APK 文件名/大小与
下载链接；`check-site.py` 检查本地引用、锚点、外链、元信息和体积预算。两者都在部署前运行。

## 自动部署

`website.yml` 在这些时机运行：

- `push` 到 `main` 且改动涉及 `website/**`：构建并部署；
- `Release` 工作流创建完 GitHub Release 后发送 `repository_dispatch`（`release-published`）：
  重新注入版本与下载链接并部署；
- `pull_request`：只构建 + 检查，不部署；
- `workflow_dispatch`：手动构建与部署。

`release: published` 不能用于这条链路：Release 由 `GITHUB_TOKEN` 创建时，GitHub 不会为它触发新的
工作流；`repository_dispatch` 是例外。发布工作流末尾的 `Trigger website deploy` 步骤负责发送事件。

需要两个 repository secrets：

| Secret | 用途 |
| --- | --- |
| `CLOUDFLARE_API_TOKEN` | 最小权限 token，仅需 Cloudflare Pages:Edit |
| `CLOUDFLARE_ACCOUNT_ID` | Cloudflare 账号 ID |

凭据只放 GitHub Actions Secrets，不写进仓库、文档、日志或前端产物。继续复用现有 Direct Upload
项目，不要新建项目或改成 Git 集成：域名绑定与历史部署都在现有项目上。

## 构建参数

| 项 | 值 |
| --- | --- |
| 运行时 | Node.js ≥ 22（使用内置 `fetch` 与 `fs.cp`） |
| 站点源目录 | `website/site/` |
| 生成目录 | `website/dist/`（不入库） |
| 响应头 | `website/site/_headers` 随产物发布：安全头、CSP 与静态资源缓存策略 |
| 可选环境变量 | `GITHUB_TOKEN`：构建时读发布信息，避开匿名限流 |

站点没有 npm 依赖、没有锁文件、没有前端打包器；`build-site.mjs` 只做文件复制和版本替换。

## 发布与回滚

- 正常发布：合并到 `main` 或发布新 Release，工作流自动部署；
- 回滚：Cloudflare Pages 项目 → Deployments 选择上一个成功部署恢复；
- 当前生产部署（迁移前手动发布）：`5214d2ca-6662-41e0-9ba6-d843e5e6b48c`（2026-10-17）；
  回滚点：`f129e204-4207-4df1-b13a-a3cc3f9f6dbb`；
- 迁移后的首次自动部署 ID 在 PR/部署记录里补记，并以它作为新的回滚点；
- 域名变更：修改 DNS 前记录原值；当前记录是 proxied CNAME `lagoon.igng.net` → `lagoon-website.pages.dev`，
  改动必须同步 Pages 自定义域名与 `check-site.py` 的 `ALLOWED_HOSTS`。

## 搜索收录

- Bing Webmaster Tools：站点 `https://lagoon.igng.net/` 已验证所有权（2026-10-17），首页
  `<meta name="msvalidate.01" content="B411826ACAE5C5CD20EA5574C3F8A6E4">` 不能删除，否则会失去
  所有权记录；`/sitemap.xml`（1 条 URL）与首页已提交。
- Google Search Console：域名资源 `sc-domain:igng.net` 已覆盖；`website/site/sitemap.xml` 只列首页，
  新增页面时必须同步补该文件和 `website/site/robots.txt` 的 Sitemap 行。
- GSC 的 sitemap 提交与 Request Indexing 只能在控制台 UI 做。

## 历史

- 2026-10-15：首版站点上线 Cloudflare Pages，项目 `lagoon-website`。
- 2026-10-17：绑定 `lagoon.igng.net`，手动 Direct Upload 生产部署 `5214d2ca`。
- 2026-10-25：站点代码并入产品仓库 `website/`，改为 GitHub Actions 自动部署；独立仓库归档。
