# 发布规范

## 默认规则

- 用户没有明确指定 release 类型时，一律使用 pre-release。
- 正式 release 必须由用户明确指定 `release` 或“正式版”。
- 发布入口统一使用 `.github/workflows/release.yml`，不直接在本地创建 GitHub Release。
- 每一个 tag 都必须新增 `docs/releases/<tag>.md`，内容用中文说明本次更新、验证结果和已知限制。工作流会在缺少文件时直接失败。
- 版本号使用 `vMAJOR.MINOR.PATCH`；预发布版本在末尾追加 `-alpha.N`、`-beta.N` 或 `-rc.N`，例如 `v0.1.0-rc.1`。

## GitHub Release 与 tag 的关系

- git tag 和 GitHub Release 是两样东西：推 tag 只会出现在 Tags 页面，只有工作流跑到「发布 GitHub Release」步骤才会在 Releases 页面出现条目。
- 因此「Releases 页面找不到某个版本」只有两种原因：这一步没跑到（构建或测试失败、超时、被取消），或者根本没这个 tag。
- GitHub 的 “Latest” 标记、仓库首页的 Releases 卡片和 `releases/latest` 接口**只认正式版**，pre-release 永远不会被标成 Latest；这是 GitHub 的行为，不是发布失败。需要在首页可见时，用 `release` 类型发布该版本。

## 发布失败后的恢复

- 发布步骤是幂等的：Release 已存在时更新说明并覆盖同名 APK 产物，因此对同一个 tag 重跑工作流是安全的。
- 恢复方式：Actions → `发布 Lagoon Release` → Run workflow，`tag` 填缺失的版本号（例如 `v0.1.0-rc.14`），`release_type` 按规范选择。
- 工作流和 CI 都带 `timeout-minutes`（job 30 分钟、companion 步骤 10 分钟），卡住会以失败结束而不是占着 runner 几小时；失败时「发布结果提示」步骤会在日志里指出这个 tag 还没有 Release。

## 稳定签名与产物

- 预发布和正式版都必须使用同一套受保护的稳定签名，均构建非 debuggable 的 Release APK；CI 不允许调试证书或临时证书回退。
- 仓库 Secrets：`OPENCODE_LAGOON_KEYSTORE_BASE64`、`OPENCODE_LAGOON_KEYSTORE_PASSWORD`、`OPENCODE_LAGOON_KEY_ALIAS`、`OPENCODE_LAGOON_KEY_PASSWORD`、`OPENCODE_LAGOON_SIGNING_CERT_SHA256`。最后一项是签名证书 SHA-256 指纹。工作流将 keystore 写入 runner 临时目录，构建后用 apksigner 验证实际证书。
- 版本名来自 tag；版本码为 `1000 + GITHUB_RUN_NUMBER`。正常发布须按运行顺序完成，不得将旧 tag 重新构建为较新升级版本；工作流更名/计数重置前需迁移版本码基数。
- 本地未配置 keystore 的 assembleRelease 仅用于编译验证，可能使用 debug 签名，不能作为发布产物。历史 debug 签名安装包无法用新稳定证书直接覆盖，应在目标设备验证迁移并事先保存服务器配置。
- 应用内更新要求下载的 APK 与已安装应用签名一致：发布构建必须继续使用同一固定证书。debug 包或不同来源的 APK 会由客户端在调起系统安装器前拒绝覆盖，详见 `docs/APP_UPDATE.md`。
- keystore、口令均不得进入仓库。CI 在成功或失败后清理注入文件。
- 当前签名证书指纹、主备份位置和恢复步骤见 [`docs/ANDROID_SIGNING.md`](ANDROID_SIGNING.md)；生成 keystore 摘要见 [`docs/ANDROID_SIGNING_MANIFEST.txt`](ANDROID_SIGNING_MANIFEST.txt)。

## 发布流程

1. 完成功能和验证，更新 `docs/releases/<tag>.md`。
2. 提交变更并推送分支。
3. 创建并推送版本 tag，或手动运行 `发布 Lagoon Release` 工作流。
4. 工作流重新执行 Android 单元测试和 Release APK 构建。
5. 工作流把中文说明和 APK 一起发布到 GitHub Release。tag 触发默认是 pre-release；手动运行时只有选择 `release` 才会发布正式版。
6. 第 4、5 步没跑到（失败、超时、被取消）时，这个版本只会出现在 Tags 页面；按上面「发布失败后的恢复」重跑同一个 tag 即可补上 Release。

## Release 说明与产物命名

- GitHub Release 的正文由工作流自动组装，顺序为：版本标题、发布类型说明、推送变体、`## 本次更新`、`## 下载`、`## 自动验证`。
- `## 本次更新` 的正文取自 `docs/releases/<tag>.md`（去掉文件开头的 `## 本次更新` 标题）。
- `## 下载` 由工作流生成，包含 Android 安装包的直链：`https://github.com/<owner>/<repo>/releases/download/<tag>/Lagoon-<version>.apk`。
- Android 安装包的上传文件名固定为 `Lagoon-<version>.apk`（`<version>` 为去掉 `v` 前缀的 tag，例如 `v0.1.0-rc.5` → `Lagoon-0.1.0-rc.5.apk`），不再是固定的 `app-release.apk`。Gradle 仍按默认路径产出 `app-release.apk`，工作流在校验签名后复制并重命名后上传。
- 因为直链里写死了文件名和 tag，改名或改版本号会破坏旧链接；如需变更命名规则，应作为独立迁移处理。

## 说明模板

```markdown
## 本次更新

- 用中文列出用户可感知的功能或修复。

## 验证

- 列出实际执行的验证命令和结果。

## 已知限制

- 列出尚未在真实设备、真实 OpenCode Server 或推送服务上验证的部分。
```

工作流会把 `## 本次更新` 及之后的内容原样拼进 Release 正文，并额外追加「下载」直链和「自动验证」小节，因此说明文件不需要自己写安装包名称或下载链接。
