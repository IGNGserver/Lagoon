# 部署 Lagoon 中枢

Lagoon 中枢是一个容器：它替浏览器连接你的开发机（OpenCode 2.x / DeepSeek Harness），常驻监控任务状态，并提供网页端（可安装为 PWA，支持推送）。设计说明见 [docs/HUB_DESIGN.md](../docs/HUB_DESIGN.md)。

## 快速开始

1. 准备一台装有 Docker 的机器（NAS、服务器、任意一台常开的电脑都可以），它需要能访问你的开发机。
2. 下载 [compose.yaml](compose.yaml)（发布页的 `lagoon-hub-compose.yaml` 已固定到对应版本），在同目录执行：

   ```sh
   LAGOON_PASSWORD='你的密码' docker compose up -d
   ```

   不设置 `LAGOON_PASSWORD` 也可以：首次打开网页时需要输入容器日志里的初始化码再设置密码：

   ```sh
   docker compose logs lagoon-hub | grep 初始化码
   ```

3. 浏览器打开 `http://<这台机器的 IP>:25246`，登录后「添加服务器」：
   - OpenCode：填开发机上 `opencode serve` 的地址与 Basic Auth 密码，或粘贴 `opencode pair` 的配对链接；
   - DeepSeek Harness：在开发机运行 `dsh web --host <开发机局域网 IP> --no-open`，把打印的整条启动链接粘贴进地址栏，地址和令牌会自动拆开。

   中枢在容器里，`localhost` 指容器自己。harness 和中枢在同一台机器上时请填 `host.docker.internal`（compose 已配置）。

## 环境变量

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `LAGOON_PASSWORD` | 空 | 访问密码，允许弱密码。设置后以它为准，网页内不能修改；修改后所有设备需重新登录。 |
| `LAGOON_PORT` | `25246` | HTTP 端口 |
| `LAGOON_TRUST_PROXY` | `false` | 在反向代理后面时设为 `true`，以识别 `X-Forwarded-Proto` / `X-Forwarded-For` |
| `LAGOON_TLS` | 空 | 设为 `self-signed` 时自建本地证书颁发机构并监听 HTTPS |
| `LAGOON_TLS_HOSTS` | 空 | 自签名证书包含的 IP / 域名，逗号分隔，例如 `192.168.1.10,hub.lan` |
| `LAGOON_TLS_CERT` / `LAGOON_TLS_KEY` | 空 | 自带证书（PEM；私钥须为 PKCS#8，即 `BEGIN PRIVATE KEY`） |
| `LAGOON_TLS_PORT` | `8443` | HTTPS 端口 |
| `LAGOON_PUSH` | `true` | 是否启用 Web Push |
| `LAGOON_PUSH_SUBJECT` | 项目地址 | VAPID 联系方式（`mailto:` 或 `https:`） |

数据都在 `/data` 卷：服务器资料、加密后的凭据、任务账本、推送订阅与密钥。备份就是备份这个卷；迁移时整卷搬走。

## HTTPS、PWA 与推送

中枢允许 HTTP 访问，网页功能完整。但浏览器只在安全上下文（HTTPS 或 `localhost`）里允许**安装为应用、Service Worker 和推送**。任选一种方式提供 HTTPS：

**A. 反向代理（推荐）**：Caddy / Nginx / Cloudflare Tunnel / Tailscale Serve 终止 TLS，并设置 `LAGOON_TRUST_PROXY: "true"`。Caddy 示例见 [Caddyfile.example](Caddyfile.example)。代理不要缓冲 `/api/v1/events`（Nginx：`proxy_buffering off;`）。

**B. 自签名**：

```yaml
environment:
  LAGOON_TLS: self-signed
  LAGOON_TLS_HOSTS: 192.168.1.10
ports:
  - "8443:8443"
```

首次启动会生成一个本地证书颁发机构。登录后在「设置 → HTTPS 证书」下载根证书，在每台设备上安装并信任：

- iPhone / iPad：Safari 下载后，设置 → 通用 → VPN 与设备管理 安装描述文件，再到 通用 → 关于本机 → 证书信任设置 开启完全信任；
- Android：设置 → 安全 → 加密与凭据 → 安装证书 → CA 证书；
- 电脑：导入系统钥匙串 / 证书管理器的「受信任的根证书」。

然后用 `https://192.168.1.10:8443` 打开。`LAGOON_TLS_HOSTS` 改变时服务器证书会自动重签，根证书不变。

**C. 自带证书**：挂载证书目录并设置 `LAGOON_TLS_CERT` / `LAGOON_TLS_KEY`。

安装与推送：

- Android Chrome / 桌面 Chrome、Edge：地址栏的安装按钮，或「设置 → 安装应用」；
- iPhone / iPad：Safari「分享 → 添加到主屏幕」，**从主屏幕图标打开后**才能在设置里开启推送；
- 推送由中枢发往浏览器厂商的推送服务（FCM、Apple、Mozilla），中枢需要能访问外网；
- 每台设备在「设置 → 推送通知」单独开启，可发送测试推送确认。

## 安全

- 中枢保存了所有开发机的 harness 凭据，而 harness 能执行 shell：**密码被猜中等于开发机被远程执行**。密码允许很弱，因此登录有不可关闭的退避：同一来源连续失败 5 次后锁定时间逐次翻倍（最长 15 分钟），全局 10 分钟内失败超过 30 次时所有失败请求至少间隔 2 秒。
- 建议只在局域网、VPN（Tailscale 等）或带访问控制的反向代理后暴露中枢，不要直接映射到公网。
- 凭据在 `/data` 中用 AES-256-GCM 加密，密钥 `secret.key` 在同一个卷里：它防的是单独泄露的状态文件，防不了整卷被拿走。
- 「设置 → 退出所有设备」立即作废所有浏览器会话。
- 「设置 → 客户端接入」签发的接入密钥能导出全部服务器**连同凭据**（给 Android 客户端一键导入用），和访问密码一样敏感：只发给自己的设备，不用了就吊销。它不受「退出所有设备」影响。导出内容用密钥加密，经 HTTP 访问的中枢也不会在网络上暴露凭据。
- 中枢里的服务器地址若只在容器里可用（`host.docker.internal`、容器名），在服务器编辑页填写「客户端访问地址」，导出给其他设备时用它。

## 升级

```sh
docker compose pull && docker compose up -d
```

数据卷格式向后兼容。网页会在新版本就绪时提示刷新。

## 自行构建

```sh
docker build -f deploy/Dockerfile -t lagoon-hub .
```

在仓库根目录执行；构建不需要 Android SDK。
