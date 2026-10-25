# 工作区、分支与构建区

官网源码已经并入产品仓库的 `website/`，不再有独立的站点仓库。Git 纪律、worktree 路径与生命周期
以仓库根目录 `AGENTS.md` 和设备级规范为准；本文只记录站点相关的差异。

## 目录布局

```text
~/项目/Lagoon/                         # 产品仓库锚点，不用于开发
~/项目/.wt/Lagoon/<task-slug>/         # 任务 worktree，唯一开发位置
  └─ website/                          # 官网源码
      ├─ site/                         # 静态源文件
      ├─ scripts/build-site.mjs        # 注入最新版本并生成 dist/
      ├─ scripts/check-site.py         # 静态检查
      └─ docs/                         # 站点文档
```

所有改动都从 `origin/main` 建 worktree，不在主目录改文件或建提交。

## 创建任务 worktree

```bash
REPO=~/项目/Lagoon
git -C "$REPO" fetch origin --prune
BASE=$(git -C "$REPO" symbolic-ref --quiet --short refs/remotes/origin/HEAD | sed 's|^origin/||')
test "$BASE" = main
PATH="$HOME/.local/bin:$PATH" wt new Lagoon lagoon-site
```

`wt` 不可用时的等价命令：

```bash
git -C "$REPO" worktree add ~/项目/.wt/Lagoon/lagoon-site -b agent/lagoon-site origin/main
```

使用等价命令时也必须确认 worktree 的 `HEAD` 与创建时的 `origin/main` 一致。

## 构建与检查

站点没有 npm 依赖；`build-site.mjs` 只用 Node 标准库。构建与检查可以在本机盘 scratch 里做，
避免把 `dist/` 写回 NAS 项目卷：

```bash
WT=~/项目/.wt/Lagoon/<task-slug>
SCR=${BUILD_SCRATCH_ROOT:-$HOME/.build}/Lagoon/<task-slug>
mkdir -p "$SCR"
rsync -a --delete --exclude dist "$WT/website/" "$SCR/"
cd "$SCR" && GITHUB_TOKEN="$(gh auth token)" node scripts/build-site.mjs
python3 scripts/check-site.py dist
```

- `dist/` 是不可提交的构建产物，已在 `.gitignore` 中忽略；
- 版本/日期/APK 大小由构建脚本从 GitHub Releases 注入；不要手工改 `dist/`；
- 浏览器验证按 [`SITE.md`](SITE.md)「浏览器验证」执行，至少覆盖桌面与移动视口、WebGL 降级、
  `prefers-reduced-motion` 和控制台错误。

## 收尾

```bash
cd ~/项目/.wt/Lagoon/<task-slug>
git status --short
git diff --check
python3 website/scripts/check-site.py website/site   # 源文件结构检查
git push -u origin agent/<task-slug>
PATH="$HOME/.local/bin:$PATH" wt done Lagoon <task-slug>
```

`wt done` 遇到未提交或未推送内容会保护性停止；清理前必须确认没有未提交改动。

## 集成与部署

`main` 受分支保护：必须经 PR，且 CI 通过。站点改动由 `.github/workflows/website.yml` 在合并后
自动构建并部署到 Cloudflare Pages，不再手动上传 artifact。部署与回滚细节见 [`HOSTING.md`](HOSTING.md)。
