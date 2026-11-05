# DSH Remote WebRTC 可行性评估（P4）

> 结论：**当前不具备在 Lagoon 客户端单独实现 WebRTC 的条件**。可以先做能力探测与回退设计，但不能在没有 Host 侧信令和真实网络环境验收的情况下上线 LAN/P2P/TURN。

## 现状

- Lagoon 的 DSH Remote 固定走 Host 的 Relay：`/api/v1/remote/connect` + Control WebSocket，传输帧由 Relay 转发。
- 协议里没有 ICE/STUN/TURN 候选、SDP、DTLS 指纹或 WebRTC 信令端点；客户端无法自行把 Relay 连接“升级”为 P2P。
- Host 端是 `dsh-remote` 插件（`ds-harness-remote`），需要同时增加：
  1. 能力标记（例如 `remote.transport.webrtc.v1`）；
  2. 信令 RPC / 帧（ICE 候选、offer/answer、DTLS 指纹）；
  3. TURN 凭据或内置 TURN 服务；
  4. 与现有 Noise IK 设备身份绑定的鉴权。

## 安全要求

- 不能把 WebRTC 的 DTLS 指纹当作新的信任根；必须把 DTLS 证书/指纹绑定到已 pin 的 Host Noise 身份，或由 Noise 通道对 SDP/指纹做完整性保护。
- 必须防止降级：Relay 失败时不能静默切到未验证的 P2P；也不能让 Relay 路径与 P2P 路径的 Host 身份不一致。
- TURN 凭据要短期、按设备/会话签发，不能写入日志或持久化明文。
- 继续保持“Host 是权威、客户端只读/受控写入”的边界。

## 客户端成本

- Android 需要引入 WebRTC 原生依赖（体积、ABI、功耗、后台策略）。
- 需要新的连接状态机：Relay → ICE 候选 → P2P/TURN → 回退 Relay；与现有 `ConnectionStatus`/`RemoteConnection` 生命周期、重连、前后台切换、通知都要重新验证。
- 需要网络切换（Wi-Fi/蜂窝）、对称 NAT、企业代理、IPv6-only 等真机矩阵。
- 当前没有真实 Host、没有第二台网络设备、没有 TURN 服务，无法做有意义的验收。

## 建议路线

1. **Host 先行**：在 `ds-harness-remote` 增加可选 WebRTC 传输与信令，保留 Relay 为默认/回退；发布能力标记。
2. **协议评审**：确定 SDP/ICE 与 Noise 身份的绑定方式，补滥用/降级测试。
3. **客户端实验开关**：仅对显式开启的实验用户显示；默认 Relay，失败自动回退并明确提示。
4. **验收矩阵**：同 LAN、不同 NAT、TURN-only、Relay-only、网络切换、Host 重启、指纹变化。
5. 全部通过后再考虑默认开启。

## 本次结论

P4 只完成评估，不实现 WebRTC。阻塞条件不是 Lagoon 单方面能解决的：**Host 侧没有 WebRTC 信令/传输能力，且当前没有真实 Host/网络环境做验收**。在 Host 提供能力前，Lagoon 继续使用 Relay，并在网络详情里明确标注“当前路径 Relay 中继”。
