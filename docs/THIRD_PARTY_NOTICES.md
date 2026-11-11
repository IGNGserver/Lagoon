# 第三方组件与参考实现说明

Lagoon 的 SSH 支持使用以下第三方库：

| 组件 | 版本 | 许可证 | 用途 |
|---|---|---|---|
| `com.github.mwiede:jsch` | 0.2.25 | BSD 3-Clause | SSH 传输、私钥认证、`direct-tcpip` 本地转发 |
| `org.bouncycastle:bcprov-jdk18on` | 1.79 | Bouncy Castle License（MIT 风格） | Android 26–32 的 Ed25519 主机密钥与私钥支持 |

两者的许可证文本随依赖发布；发布 APK 时保留其版权与许可证声明。

SSH 配置项、远端 `dsh web` bootstrap 行为、Host Key 流程与错误语义参考了
[`ccch1mneyyy/dsh-Go`](https://github.com/ccch1mneyyy/dsh-Go)（AGPL-3.0）。Lagoon 未复制其源码；
相关 Kotlin/JSch 实现为独立重写。若未来需要直接复用其代码，必须先确认 Lagoon 采用与 AGPL-3.0
兼容的许可证并履行相应义务。

## 官方协议回归 fixture

测试资源 `dsh-0.2.1-rc.5-contract.json` 与 `dsh-0.2.2-rc.1-session-contract.json` 的方法 JSON schema
由 `scripts/export-dsh-contract.mjs` 从 DeepSeek AI 官方 npm 包生成的 `lib/typert.host.js` 描述符提取，
保留 schema 内容，不来自 Lagoon 自行编造的接口。来源仓库为
[deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness)，许可证为 MIT：
Copyright (c) 2026 DeepSeek。版本、commit、包名、生成文件 SHA-256 与原始 sourceLocation 记录在 fixture 中。
升级必须使用已审阅的固定官方版本并对比契约差异，不可为让测试通过而直接改写 schema。

> MIT License — Copyright (c) 2026 DeepSeek
>
> Permission is hereby granted, free of charge, to any person obtaining a copy
> of this software and associated documentation files (the "Software"), to deal
> in the Software without restriction, including without limitation the rights
> to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
> copies of the Software, and to permit persons to whom the Software is
> furnished to do so, subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in all
> copies or substantial portions of the Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
> IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
> FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
> AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
> LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
> OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
> SOFTWARE.

OpenCode fixture 的来源和版本记录在 `opencode-2.2.6-contract.json` 的 `_source` 字段；
`opencode-2.2.6-native-services-contract.json` 另外保存原始 OpenAPI SHA-256 和 native service 路由摘录；
[anomalyco/opencode](https://github.com/anomalyco/opencode) 使用 MIT 许可证。
