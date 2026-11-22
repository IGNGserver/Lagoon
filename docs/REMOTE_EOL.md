# DSH Remote 中枢与 HTTP 直连模式已移除

为精简连接架构、消除多模式并存带来的维护与状态不一致负担，Lagoon 已彻底移除 **DSH Remote（中枢/Noise 中继/扫码配对/Host Connector）** 与 **HTTP 直连（OpenCode 直连、`opencode pair`、旧版 DSH 直连）**。

从本版本起，Lagoon **只支持经由 SSH 连接开发机**（Linux / macOS / WSL），在同一台 SSH 设备上统一承载 DeepSeek Harness、OpenCode 2.x Native、OpenCode ACP、Codex 与 Antigravity ACP。

## 升级时的本地数据处理

- **保留**：全部已保存的 SSH 设备、主机指纹（TOFU）、SSH 密钥库（含生物识别与硬件 Keystore 密钥）、SSH 下已挂载的 Harness、其所有会话草稿/离线缓存/SQLite 对话缓存/任务账本/未读通知/模型偏好，以及设备的启用/停用状态与设备组，均在首次启动时自动迁移保留。
- **清理**：历史 DSH Remote 账号目录、设备身份与令牌（`remote-device-identities`）、Remote Host 指纹（`remote-host-pins`）、未完成压缩记录、HTTP 直连资料与凭据、以及旧 `connection-graph` 会在迁移完成后自动清理，并在应用内给出一次性提示。

## 如果开发机上仍在运行 DSH Remote 插件或自建中枢

1. 在开发机停用或卸载 `ds-harness-remote` 插件（以及此前构建的 `host-connector` overlay 产物）。
2. 若曾自建过 DSH Remote Server，停止并清理其服务与凭据数据。
3. 若此前曾将 OpenCode / DSH 端口通过反代或局域网明文暴露给手机直连，现在可改为仅监听 `127.0.0.1` 并关闭多余的公网/反代入口，仅通过 SSH 隧道安全访问。

## 历史参考

- 最后一个包含 DSH Remote 与 HTTP 直连模式的 tag 为 `v5.3.0-rc.16`。
- 历史发布说明（`docs/releases/`）保持原样作为版本归档。
