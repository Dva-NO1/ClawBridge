# ClawBridge

> **在安卓手机上无需 VPN / 内网穿透，通过自建轻量 WSS 中继远程管理桌面端 Antigravity 与 Codex。**

[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](./LICENSE)

ClawBridge 由三部分组成：**自建中继服务（`relay/`）**、**Windows 桌面桥（`bridge/`）**、**Android 原生客户端（`android/`）**。你可以在 5G / Wi-Fi 任意网络下实时查看多会话进展、监控双端模型配额、切换任务模型、收发消息与图片/文件、接收锁屏审批与完成推送。

> 本项目基于开源底座 [AvenalJ/AntigravityMobile](https://github.com/AvenalJ/AntigravityMobile) 深度重构与扩展，详见 [THIRD_PARTY_NOTICES.md](./THIRD_PARTY_NOTICES.md) 与 [LICENSE](./LICENSE)。

---

## 快速下载（推荐，免编译）

如果你只想直接使用而不需要从源码编译，可以直接前往 **[GitHub Releases](https://github.com/xiaojiangxiaojin-prog/ClawBridge/releases/latest)** 下载对应安装包：

| 平台 | 安装包文件 | 说明 |
| :--- | :--- | :--- |
| **Android 手机端** | `ClawBridge-v1.0.0-android.apk` | 已签名 Release APK，下载后直接安装（Android 8.0+） |
| **Windows 电脑端** | `ClawBridge-v1.0.0-windows-setup.exe` | 一键图形安装程序，内置 `ClawBridgeDesktop.exe`、桥源码与完整运行依赖（需本机 Node.js ≥ 22） |
| **校验文件** | `SHA256SUMS.txt` | 官方发布的 SHA-256 哈希校验清单 |

> **提示**：开源版不包含任何公共中继服务器。安装好手机 APK 与电脑 EXE 后，仍需按下方第 1 步在自己的 VPS 上一键部署中继，并在两端填入你自己的 `wss://relay.yourdomain.com`。

---

## 核心功能

- **零 VPN 公网直连**：基于自有 VPS 上的轻量 WSS（TLS 1.2/1.3）中继，手机与桌面桥均主动外连，支持 DoH 防劫持、30 秒心跳保活、断线指数退避与 Windows 休眠唤醒自恢复。
- **双引擎统一接入**：同时管理桌面端 **Antigravity**（Hub ConnectRPC + Transcript 实时镜像 + 可选 CDP 9222 兜底）与 **Codex CLI**（`codex app-server` JSON-RPC 2.0 + 无损 `thread/fork` 自动续接）。
- **双客户端独立配额看板**：直观的绿色「剩余额度条」，独立区分 **Codex (OpenAI) 5h / 7d 额度** 与 **Antigravity Gemini / 附赠 Claude 5h / 7d 额度**，附重置倒计时与免费重置券显示。
- **高效会话列表与阅读记忆**：顶部即时搜索（标题/项目/模型/预览/ID）、`时间 | 项目` 双视图切换、来源 + 状态双维筛选、置顶会话、上次阅读位置记忆与长列表流畅滚动。
- **手机端任务换模型与双向传文件**：会话内实时切换 Antigravity / Codex 模型；支持手机发图片（多模态直传）与传文件到电脑工作区，聊天内出现的文件/安装包路径自动生成一键下载按钮。
- **分级审批与安全保护**：低风险命令可在锁屏通知一键批准；高风险命令（删除/格式化/安装/脚本执行等）由桌面桥硬编码拦截，强制在 App 内通过 **4–6 位安全 PIN** 校验后方可放行。
- **无感静默托盘**：Windows 端编译为零黑窗托盘程序 `ClawBridgeDesktop.exe`，开机自启，点击托盘图标或按 `Ctrl+Alt+C` 随时呼出控制台。

---

## 系统架构

```text
┌─────────────────────────────────────────────────────────────────────────┐
│ Android 原生 App (Kotlin + Jetpack Compose, minSdk 26)                  │
│  · 前台服务常驻保活 · DoH DNS · 双端剩余配额 · 紧凑会话列表 · PIN 审批  │
└───────────────────────────────────┬─────────────────────────────────────┘
                                    │ WSS (TLS 1.2/1.3, 30s Ping/Pong)
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ 用户自建 VPS 中继服务 (Node.js + ws + Nginx/Certbot, relay/)             │
│  · 仅做 3 件事：设备鉴权(SHA-256)、6位一次性配对绑定、帧透传与离线缓冲   │
│  · 不存聊天历史、不碰任何 OAuth / API 凭证、不依赖任何第三方托管服务器   │
└───────────────────────────────────▲─────────────────────────────────────┘
                                    │ WSS 主动外连 (断线退避 + 休眠唤醒自愈)
┌───────────────────────────────────┴─────────────────────────────────────┐
│ Windows 桌面桥 (Node.js + WPF 托盘宿主, bridge/)                         │
│  ├─ Antigravity 通道：Hub ConnectRPC + SQLite/Transcript 镜像 + CDP     │
│  ├─ Codex 通道：codex app-server (JSON-RPC 2.0) + thread/fork 续接       │
│  └─ 本地安全层：OAuth Token 仅本机读取配额，高风险命令桥端硬编码拦截     │
└─────────────────────────────────────────────────────────────────────────┘
```

### 仓库目录结构

```text
ClawBridge-OSS/
├── relay/                        # 自建 WSS 中继服务 (Node.js)
│   ├── src/server.mjs            # 中继主程序
│   ├── src/selftest.mjs          # 中继协议单元测试
│   ├── deploy/deploy.sh          # Ubuntu/Debian 一键部署脚本 (systemd + Nginx + Certbot)
│   ├── deploy/clawbridge-relay.service
│   ├── deploy/nginx-relay.conf.example
│   ├── deploy/Caddyfile.example
│   └── .env.example
├── bridge/                       # Windows 桌面桥与静默托盘控制台
│   ├── src/                      # 15 个核心桥模块 + selftest-loopback.mjs
│   ├── desktop-gui/              # C# WPF 托盘控制台 + Windows 安装程序源码
│   ├── scripts/                  # 安装自启 / 编译托盘 / 打包 setup.exe / 启动 CDP 脚本
│   └── config.example.json
├── android/                      # Android 客户端 (Kotlin + Jetpack Compose)
│   ├── app/src/main/             # 客户端源码
│   ├── app/src/test/             # Robolectric Compose 单元测试与 UI 审计测试
│   └── keystore.properties.example
├── docs/                         # 开发流程、架构文档、中继迁移与脱敏报告
├── design-system/clawbridge/     # 统一深色设计规范 (MASTER.md)
├── BUILD.md                      # 干净环境全端构建指南
├── LICENSE                       # MIT License
└── THIRD_PARTY_NOTICES.md        # 第三方开源声明
```

---

## 从零搭建全流程

### 第 1 步：在自己的 VPS 部署中继服务

#### 1.1 VPS 最低要求与准备
- **最低配置**：1 vCPU / 512 MB–1 GB RAM / 10 GB 硬盘（中继内存占用仅 ~30 MB）
- **操作系统**：Ubuntu 22.04 / 24.04 或 Debian 12（干净环境即可）
- **安全组 / 防火墙放行端口**：
  - `TCP 80`（Let's Encrypt HTTP-01 证书签发与自动跳转 HTTPS）
  - `TCP 443`（公网 `wss://` 加密连接）
  - `TCP 22`（SSH 运维）
  - *注意：内部中继端口 `8787` 仅监听 `127.0.0.1`，**不要**对外网放行！*
- **域名解析**：在你的域名 DNS 服务商添加一条 **`A` 记录**，将子域名（如 `relay.yourdomain.com`）指向你的 VPS 公网 IP（`YOUR_VPS_IP`），等待 DNS 生效（可用 `ping relay.yourdomain.com` 确认）。

#### 1.2 一键部署（自动安装 Node.js + systemd + Nginx + Let's Encrypt 证书）

将本仓库上传或 `git clone` 到 VPS 后执行：

```bash
cd ClawBridge-OSS/relay/deploy
sudo bash deploy.sh --domain relay.yourdomain.com --email you@yourdomain.com
```

> 可选参数：
> - `--port 8787`：自定义内部监听端口（默认 `8787`）
> - `--proxy caddy`：改用 Caddy 自动签发 HTTPS 证书
> - `--proxy none`：仅部署 Node 中继与 systemd，自行配置反向代理（可参考 [nginx-relay.conf.example](./relay/deploy/nginx-relay.conf.example)）

#### 1.3 验证中继就绪

```bash
sudo systemctl status clawbridge-relay
curl https://relay.yourdomain.com/healthz
# 预期返回：{"ok":true,"service":"clawbridge-relay","v":1,"uptimeSec":...}
```

---

### 第 2 步：安装并配置 Windows 桌面桥

**前置条件**：Windows 10/11、Node.js ≥ 22、本地已安装 Antigravity 和/或 Codex CLI。

- **方式 A（推荐，直接安装）**：前往 [GitHub Releases](https://github.com/xiaojiangxiaojin-prog/ClawBridge/releases/latest) 下载 `ClawBridge-v1.0.0-windows-setup.exe`，双击一键安装到 `%LOCALAPPDATA%\ClawBridge`（自动创建桌面快捷方式与登录自启）。
- **方式 B（从源码安装）**：在 PowerShell 进入仓库根目录执行一键安装脚本（自动安装依赖、生成 `%APPDATA%\clawbridge\config.json`、编译 `ClawBridgeDesktop.exe` 并注册登录自启计划任务）：
   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File .\bridge\scripts\install-bridge.ps1
   ```
2. **首次配置你的中继地址**（二选一）：
   - **图形界面**：启动桌面快捷方式 `ClawBridge 桌面控制台`（或点击系统托盘图标），在「中继服务器地址」填入 `wss://relay.yourdomain.com` → 点击 **「保存并应用」**；
   - **手动编辑**：参考 [bridge/config.example.json](./bridge/config.example.json) 编辑 `%APPDATA%\clawbridge\config.json`，将 `relayUrl` 改为 `wss://relay.yourdomain.com` 后重启桥服务。
3. 命令行查看运行状态与当前 6 位配对码（源码安装方式）：
   ```powershell
   node --no-warnings .\bridge\src\index.mjs --show-pair-code
   ```

---

### 第 3 步：安装 Android App

- **方式 A（推荐，直接安装）**：前往 [GitHub Releases](https://github.com/xiaojiangxiaojin-prog/ClawBridge/releases/latest) 下载 `ClawBridge-v1.0.0-android.apk` 并直接安装到手机。
- **方式 B（从源码自行构建）**：详见完整构建说明 [BUILD.md](./BUILD.md)。简要命令：

```bash
cd android
# 调试版（可直接安装测试）
./gradlew assembleDebug
# 或配置 keystore.properties 后构建签名正式版
./gradlew assembleRelease
```

安装 APK 到手机后：
1. **首次启动配置中继**：首页会提示「请先配置中继地址」，点击 **「中继设置」**（或底部「设置」Tab）→ 填入你自己的 `wss://relay.yourdomain.com` → 点击 **「保存中继设置并重连」**；
2. **建议开启后台保活**（国产 ROM 如 HyperOS / MIUI / ColorOS）：在设置页「后台保活配置」中依次开启「忽略电池优化（无限制）」、「后台自启动」与「锁屏/悬浮通知」。

---

### 第 4 步：6 位配对码绑定联调

1. 在电脑端桌面控制台（托盘单击呼出）或终端 `--show-pair-code` 查看 **6 位一次性配对码**（10 分钟内有效，用完即废）；
2. 在手机 App 首页输入该 6 位数字 → 点击 **「立即配对」**；
3. 顶部状态变为 **「已直连 · 桌面在线」** 即完成绑定，后续打开 App 自动秒连，无需重复配对。

---

## 日常使用手册

- **首页**：查看连接延迟、完整中继地址、待确认审批事项，以及 Codex / Antigravity 双客户端 5h & 7d 绿色剩余额度条。
- **会话列表**：顶部输入关键词即时过滤（标题/项目/模型/预览/编号）；右上分段切换 **`时间 | 项目`** 视图；支持来源与状态筛选；**长按任意会话行**可置顶或取消置顶。
- **聊天页**：点进会话自动定位到上次阅读位置；顶栏可实时切换模型；底栏支持快捷指令、发图片、传文件；消息中的文件/安装包路径可直接点击下载到手机。
- **审批与 PIN**：在设置页配置 4–6 位安全 PIN；所有高风险命令必须通过 PIN 校验才会下发桌面端执行。
- **更换中继服务器**：无需改代码或重编 APK，详见 [docs/RELAY_MIGRATION.md](./docs/RELAY_MIGRATION.md)。

---

## 常见问题 (FAQ)

1. **首次打开 App 显示「请先配置中继地址」？**
   开源版默认地址为占位符 `wss://relay.example.com`，不会自动连接任何公共服务器。请先完成第 1 步在自己的 VPS 部署中继，再到 App 设置页与桌面桥填写你自己的 `wss://relay.yourdomain.com`。
2. **手机给 Codex 发消息时提示被电脑端占用？**
   Codex 的 `app-server` 使用系统级单写入者文件锁。当电脑端 Codex 客户端锁住某条会话时，ClawBridge 会自动调用官方 `thread/fork` 无损继承上下文创建续接会话并在原窗口无缝展示回复；对于未被桌面端锁定的会话则直接原地写入。
3. **息屏或切后台后收不到推送？**
   请在 App「设置 → 后台保活配置」中将省电策略设为「无限制」并开启自启动与锁屏通知权限；即使手机完全断网，中继也会 FIFO 离线暂存最多 200 条通知，重连后按序补发。
4. **如何确认开源版能否在本地跑通？**
   无需公网 VPS，直接运行回环自测即可验证中继与桌面桥完整握手配对：
   ```bash
   node bridge/src/selftest-loopback.mjs
   ```

---

## 文档导航

- [BUILD.md](./BUILD.md) — Android APK / Windows 托盘程序 / 中继服务干净环境构建说明
- [docs/DEVELOPMENT_GUIDE.md](./docs/DEVELOPMENT_GUIDE.md) — 项目里程碑与架构演进开发文档
- [docs/RELAY_MIGRATION.md](./docs/RELAY_MIGRATION.md) — 零硬编码更换中继服务器与真机验收清单
- [docs/SANITIZATION_REPORT.md](./docs/SANITIZATION_REPORT.md) — 开源发布脱敏自查报告
- [design-system/clawbridge/MASTER.md](./design-system/clawbridge/MASTER.md) — Android 客户端深色 UI 设计规范
