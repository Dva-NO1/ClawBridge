# ClawBridge 开源发布脱敏自查报告

本报告记录在生成独立开源仓库 `ClawBridge-OSS/` 过程中执行的所有敏感信息清理、移除与替换项（仅记录文件位置与敏感信息类型，不包含任何真实凭据、IP、域名或个人数据）。

---

## 1. 彻底排除未进入开源仓库的敏感文件与目录

以下包含个人运行态、凭据、签名密钥或历史提交记录的文件/目录均**未复制**进 `ClawBridge-OSS/`，并已加入 `.gitignore`：

| 原位置 | 敏感类型 | 处理方式 |
|---|---|---|
| 原仓库 `.git/` | 包含历史敏感域名、IP、路径的 Git 提交历史 | 不继承历史，在 `ClawBridge-OSS/` 重新执行干净 `git init` |
| `android/keystore/*.jks` | Android 正式版发布签名私钥库 | 彻底排除；新增 `android/keystore.properties.example` 与 `keytool` 自建指南 |
| `android/local.properties` | 本机 Android SDK 绝对路径 | 彻底排除并纳入 `.gitignore` |
| `bridge/src/tunnel.mjs`、`http-server.mjs`、`config.mjs`、`supervisor-service.mjs` | 遗留的 Cloudflare Tunnel (`cloudflared`) / 旧版服务代码（未被主程序引用） | 彻底移除，不进入开源版 |
| `bridge/src/selftest-m*.mjs`、`selftest-v*.mjs`、`selftest-wake.mjs` | 含个人真实会话 UUID、域名与路径的阶段自测脚本 | 彻底移除；替换为无个人数据的 `bridge/src/selftest-loopback.mjs` |
| `dist/*.apk`、`bridge/ClawBridgeDesktop.exe` | 已签名个人编译产物与二进制 | 彻底排除，改在 `BUILD.md` 提供从零构建方式 |
| `data/`、`手机上传/`、`.agents/` | 本地运行数据、个人上传图片与本地工具配置 | 彻底排除 |

---

## 2. 代码、配置与文档中的脱敏替换清单

| 文件路径（相对 `ClawBridge-OSS/`） | 敏感信息类型 | 处理结果 |
|---|---|---|
| `bridge/src/claw-config.mjs` | 个人中继域名、含个人用户名的 Windows 绝对路径 | 默认中继改为占位符 `wss://relay.example.com`（支持 `CLAWBRIDGE_RELAY_URL`）；路径改为动态读取 `%LOCALAPPDATA%` |
| `bridge/src/antigravity-executor.mjs` | 含个人用户名的 `language_server.exe` 绝对路径 | 改为动态读取 `ANTIGRAVITY_LANGUAGE_SERVER_EXE` 或 `%LOCALAPPDATA%` |
| `bridge/src/notification-engine.mjs` | 个人会话 UUID 与本地工作区名称 | 替换为示例 UUID `00000000-0000-4000-8000-000000000001` 与 `SampleWorkspace` |
| `bridge/scripts/start-antigravity-cdp.ps1` | 含个人用户名的 Antigravity 可执行文件绝对路径 | 改为读取 `$env:ANTIGRAVITY_EXE_PATH` 或 `$env:LOCALAPPDATA` |
| `bridge/desktop-gui/ClawBridgeDesktop.cs` | 个人中继域名默认值 | 替换为占位符 `wss://relay.example.com` |
| `bridge/config.example.json` | 新增示例配置 | 仅含占位符域名与环境变量路径，不含 `deviceToken` |
| `relay/deploy/deploy.sh` | 个人中继域名、Cloudflare Tunnel 名称与 `cloudflared` 路由逻辑 | 重写为纯自建 VPS 部署脚本（支持 Nginx + Certbot 自动签发 Let's Encrypt / Caddy / 无反代模式），强制要求用户传入真实 `--domain` |
| `relay/deploy/Caddyfile.example`、`nginx-relay.conf.example`、`.env.example` | 原 `Caddyfile` 中的个人域名 | 统一替换为 `relay.example.com`、`you@example.com` 等占位符 |
| `android/gradle.properties`、`android/app/build.gradle.kts` | 硬编码的 Release 签名密钥路径与默认密钥密码 | 彻底移除真实密码与默认硬编码，改为从 git-ignored `keystore.properties` 或环境变量读取 |
| `android/app/src/main/java/com/clawbridge/mobile/data/SettingsStore.kt` | 个人中继域名默认值 | 改为 `wss://relay.example.com`，并新增 `isRelayConfigured` 检测 |
| `android/app/src/main/java/com/clawbridge/mobile/net/RelaySocketClient.kt` | 首次启动默认占位符处理 | 未配置真实中继前不发起外连，提示用户前往设置页填写自建中继地址 |
| `android/app/src/main/java/com/clawbridge/mobile/ui/ClawBridgeScreens.kt` | 设置页中继输入框 placeholder 中的个人域名 | 改为 `wss://relay.example.com`，并在首页增加首次启动配置中继引导 |
| `android/app/src/test/.../UiScreenshotAuditTest.kt`、`SessionsPageAuditTest.kt` | 个人用户目录路径、个人域名、个人设备/会话 UUID、个人项目与聊天标题 | 全部替换为合成示例数据与相对输出目录 `build/ui_screenshots` |
| `README.md`、`THIRD_PARTY_NOTICES.md`、`docs/RELAY_MIGRATION.md`、`docs/DEVELOPMENT_GUIDE.md` | 个人 VPS 公网 IP、个人域名、个人本地目录与设备型号 | 全部替换为 `YOUR_VPS_IP`、`relay.yourdomain.com` 等通用占位符 |

---

## 3. 全局关键词零命中校验结果

在 `ClawBridge-OSS/` 全目录文本文件及全新 Git 仓库完整提交历史（`git log -p`）中执行正则扫描，以下所有敏感关键词均验证 **0 命中**：

- 个人域名关键词（主域名及子域名）— **0 命中**
- 个人公网 VPS IP 前缀 — **0 命中**
- 本机用户名路径标识 — **0 命中**
- Cloudflare 隧道配置与证书凭据引用 — **0 命中**
- 原签名密钥库密码、真实 token、个人设备/会话 UUID、个人项目目录名 — **0 命中**
