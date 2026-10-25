# Lagoon 官网 · Lagoon Website

Lagoon 的产品官网与下载入口，源码位于产品仓库的 `website/`：

- 产品仓库（唯一事实来源）：<https://github.com/IGNGserver/opencode-lagoon>
- 正式站点：<https://lagoon.igng.net/>（Cloudflare Pages 项目 `lagoon-website`）
- 部署：`.github/workflows/website.yml` 在 `main` 和正式发版后自动构建并上传 `dist/`

站点是零构建静态页面：`site/` 下是源文件，`scripts/build-site.mjs` 从 GitHub Releases 取最新
正式版与预览版，生成带当前版本/下载链接的 `dist/`，再由 Cloudflare Pages 发布。

## 目录结构

| 路径 | 内容 |
| --- | --- |
| `site/` | 站点源文件：`index.html`、`404.html`、`assets/`、`_headers`、`sitemap.xml`、`robots.txt` |
| `scripts/build-site.mjs` | 生成 `dist/`，注入最新正式版/预览版、日期、APK 大小和下载链接 |
| `scripts/check-site.py` | 静态检查：本地引用、锚点、外链、元信息、体积预算 |
| `docs/SITE.md` | 官网定位、视觉语言、页面结构、内容原则、版本更新与浏览器验证 |
| `docs/HOSTING.md` | Cloudflare 托管、自动部署与回滚 |
| `docs/WORKSPACE.md` | 本机 worktree 与验证约定 |

## 开发

```bash
cd website
GITHUB_TOKEN="$(gh auth token)" node scripts/build-site.mjs
python3 scripts/check-site.py dist
python3 -m http.server 4173 --bind 127.0.0.1 --directory dist
```

`GITHUB_TOKEN` 可省略；省略时走匿名 GitHub API，可能受速率限制。站点没有 npm 依赖，构建脚本
只用 Node 标准库。构建产物 `dist/` 不得落在 NAS 项目卷上；本机 scratch 步骤见
[`docs/WORKSPACE.md`](docs/WORKSPACE.md)。

## 许可

[MIT](../LICENSE)

站点内自托管的字体 Sora 与 JetBrains Mono 采用 SIL Open Font License 1.1，许可证随字体置于
[`site/assets/fonts/`](site/assets/fonts/)。
