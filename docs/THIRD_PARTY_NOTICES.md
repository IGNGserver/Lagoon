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
