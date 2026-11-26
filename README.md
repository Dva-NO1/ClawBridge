# ClawBridge

> **在安卓手机上远程或局域网管理桌面端 Antigravity 与 Codex —— 支持同一 Wi-Fi「内网直连（免服务器 1ms 极速）」与跨网「自建轻量 WSS 中继」双模式一键切换。**

[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](./LICENSE)

ClawBridge 由三部分组成：**自建中继服务（`relay/`，仅外网跨网需要）**、**Windows 桌面桥（`bridge/`）**、**Android 原生客户端（`android/`）**。你可以在同一局域网下免配置 VPS 直接局域网极速直连，也可以通过自建 VPS 中继在 5G 移动网下随时远程查看多会话进展、新建电脑项目、切换任务模型、收发消息与图片/文件、接收锁屏审批与任务完成通知。

> 完整版本更新说明请查阅 **[CHANGELOG.md](./CHANGELOG.md)**。本项目基于开源底座 [AvenalJ/AntigravityMobile](https://github.com/AvenalJ/AntigravityMobile) 深度重构与扩展，详见 [THIRD_PARTY_NOTICES.md](./THIRD_PARTY_NOTICES.md) 与 [LICENSE](./LICENSE)。

---

## 快速下载（推荐，免编译）

如果你只想直接使用而不需要从源码编译，可以直接前往 **[GitHub Releases](https://github.com/Dva-NO1/ClawBridge/releases/latest)** 下载对应安装包：

| 平台 | 安装包文件 | 说明 |
| :--- | :--- | :--- |
| **Android 手机端** | `ClawBridge-v2.0.1-android.apk` | 已签名 Release APK，下载后直接安装（Android 8.0+，支持旧版本无缝覆盖升级） |
| **Windows 电脑端** | `ClawBridge-v2.0.1-windows-setup.exe` | 一键图形安装程序，内置双模式 `ClawBridgeDesktop.exe`、桥源码与完整运行依赖（需本机 Node.js ≥ 22） |
| **校验文件** | `SHA256SUMS.txt` | 官方发布的 SHA-256 哈希校验清单 |

> **提示**：**「内网直连」与「外网中继」两种模式均可完全单独使用，也可混合切换！**
> - **纯内网使用（0 成本免服务器）**：只需 **1 台 Windows 电脑 + 1 部手机** 连同一 Wi-Fi，安装后在两端顶部大开关切至 **「内网直连」** 输入 6 位配对码即可使用全部功能；
> - **跨网远程使用（免买域名，仅需 1 台 VPS）**：只需拥有 **1 台带公网 IPv4 的 VPS + 1 台电脑 + 1 部手机**，在 VPS 执行 `sudo bash deploy.sh` 自动生成自签加密直连地址 `wss://<VPS公网IP>:8443` 填入两端即可（无需买域名/无需配 DNS）。超详细手把手教程见 **[docs/DEVELOPMENT_GUIDE.md](./docs/DEVELOPMENT_GUIDE.md)**。

---

## 核心功能

- **内网直连 / 外网中继双模式一键切换（v2.0.0 重磅新增）**：
  - **内网直连（LAN Direct）**：手机与电脑同一 Wi-Fi 下通过 UDP `18792` 自动发现并直连 `ws://<LAN_IP>:18791`，1ms 局域网延迟，不经过任何外部服务器（断外网照常工作）；独立 6 位内网配对码签发 `lanToken` 存入 Android Keystore，支持记住上次 IP 快连与手动 IP 兜底。
  - **外网中继（WAN Relay）**：基于自有 VPS 轻量 WSS（TLS 1.2/1.3）中继，支持 DoH 防劫持、30 秒心跳保活、断线指数退避与 Windows 休眠唤醒自恢复；内外网配对相互独立，切换模式不丢会话且无需重新配对。
- **双引擎统一接入与手机端新建 Project（v2.0.0 新增）**：同时管理桌面端 **Antigravity** 与 **Codex CLI**；支持在手机端直接自定义文件夹名在电脑创建新 Project（默认位于电脑桌面 `Desktop`，可在手机设置页随时修改默认工作区根目录）。
- **一键黑夜/白天模式与六大板块自由取色（v2.0.0 新增）**：内置 10 套青春高颜值主题预设，支持顶栏一键切换黑夜/白天模式，并提供 2D HSV 画布取色器自定义全局背景、卡片、导航栏、主按钮、强调色与聊天气泡配色。
- **会话任务结束通知与分级 PIN 安全审批**：每个会话任务执行完成自动推送最新回复摘要到手机通知栏（点击直达对话）；低风险命令支持通知栏快捷批准，高风险命令强制在 App 内通过 **4–6 位安全 PIN** 校验后放行（内外网双模式同等生效）。
- **双客户端独立配额看板**：直观的绿色「剩余额度条」，独立区分 **Codex (OpenAI) 5h / 7d 额度** 与 **Antigravity Gemini / 附赠 Claude 5h / 7d 额度**，附重置倒计时与免费重置券显示。
- **高效会话列表、秒开双层缓存与阅读记忆**：顶部即时搜索、`时间 | 项目` 双视图切换、来源 + 状态筛选、置顶会话、上次阅读位置记忆；内存 + 磁盘双层缓存实现历史对话秒开。
- **实时输出速度与花费预估面板**：对话页底部可折叠实时指标栏，展示解码 TPS、TTFT、端到端 TPS、输出 token、上下文用量与按公开单价折算的预估花费。
- **可靠任务换模型与双向传文件**：5 层防竞态与离线目录缓存保障 Antigravity / Codex 模型稳定切换；支持手机发图与传文件到电脑工作区，聊天内顶部提示横幅 3 秒自动消隐。
- **无感静默托盘**：Windows 端编译为零黑窗托盘程序 `ClawBridgeDesktop.exe`，开机自启，点击托盘图标或按 `Ctrl+Alt+C` 随时呼出双模式控制台。

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

## 从零搭建全流程（两种模式均可单独使用）

> 更详细的保姆级图文式步骤与全功能说明书请阅读：**[docs/DEVELOPMENT_GUIDE.md](./docs/DEVELOPMENT_GUIDE.md)**。
> - **如果你只在局域网/同一 Wi-Fi 下使用**：**直接跳过第 1 步**，装好第 2 步电脑端与第 3 步手机端后，在两端顶部点击 **「⚡ 内网直连」** 输入 6 位配对码即可！

### 第 1 步：在自己的 VPS 部署中继服务（仅外网模式需要 · 免域名 IP 直连）

#### 1.1 VPS 最低要求与准备
- **最低配置**：1 vCPU / 512 MB–1 GB RAM / 10 GB 硬盘（中继内存占用仅 ~30 MB）
- **操作系统**：Ubuntu 22.04 / 24.04 或 Debian 12，拥有**独立公网 IPv4 地址**（**不需要购买域名！**）
- **云厂商安全组 / 防火墙放行端口**：
  - **免域名公网 IP 模式（默认推荐）**：放行 **`TCP 8443`**（或你通过 `--port` 指定的端口）及 `TCP 22`（SSH）
  - *(可选) 若使用自己的域名模式*：放行 `TCP 80` 与 `TCP 443`

#### 1.2 一条命令免域名部署（自动识别公网 IPv4 + 自动签发 10 年期自签 TLS 加密证书）

SSH 登录 VPS 后执行：

```bash
git clone https://github.com/Dva-NO1/ClawBridge.git
cd ClawBridge/relay/deploy

# 免域名直接部署！（自动识别 VPS 公网 IPv4 并监听 wss://<VPS_IP>:8443）
sudo bash deploy.sh
```

> 可选参数：
> - `--ip 203.0.113.10 --port 8443`：手动指定公网 IPv4 与监听端口（如 `--port 443`）
> - `--domain relay.yourdomain.com --email you@yourdomain.com`：如果你已有域名，可切换为 Nginx + Let's Encrypt 证书模式
> - `--domain relay.yourdomain.com --proxy caddy`：使用 Caddy 自动签发域名证书

#### 1.3 验证中继就绪

```bash
sudo systemctl status clawbridge-relay
curl -k https://127.0.0.1:8443/healthz
# 预期返回：{"ok":true,"service":"clawbridge-relay","v":1,"tls":true,"uptimeSec":...}
```

---

### 第 2 步：安装并配置 Windows 桌面桥

**前置条件**：Windows 10/11、Node.js ≥ 22、本地已安装 Antigravity 和/或 Codex CLI。

- **方式 A（推荐，直接安装）**：前往 [GitHub Releases](https://github.com/Dva-NO1/ClawBridge/releases/latest) 下载 `ClawBridge-v2.0.1-windows-setup.exe`，双击一键安装到 `%LOCALAPPDATA%\ClawBridge`（自动创建桌面快捷方式与登录自启）。
- **方式 B（从源码安装）**：在 PowerShell 进入仓库根目录执行一键安装脚本（自动安装依赖、生成 `%APPDATA%\clawbridge\config.json`、编译 `ClawBridgeDesktop.exe` 并注册登录自启计划任务）：
   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File .\bridge\scripts\install-bridge.ps1
   ```
2. **选择你的使用模式（二选一或都配）**：
   - **仅用内网直连**：点击控制台顶部 **「⚡ 内网直连 (LAN)」** 即可直接生成 6 位内网配对码（无需填中继地址）；
   - **使用外网中继**：在控制台「外网中继地址」直接填入你的 VPS 公网 **`IP:8443`**（如 `203.0.113.10:8443`，会自动补全 `wss://` 并自动开启自签 TLS 证书适配）→ 点击 **「保存并应用」**。
3. 命令行查看运行状态与当前 6 位配对码（源码安装方式）：
   ```powershell
   node --no-warnings .\bridge\src\index.mjs --show-pair-code
   ```

---

### 第 3 步：安装 Android App

- **方式 A（推荐，直接安装）**：前往 [GitHub Releases](https://github.com/Dva-NO1/ClawBridge/releases/latest) 下载 `ClawBridge-v2.0.1-android.apk` 并直接安装到手机。
- **方式 B（从源码自行构建）**：详见完整构建说明 [BUILD.md](./BUILD.md)。简要命令：

```bash
cd android
# 调试版（可直接安装测试）
./gradlew assembleDebug
# 或配置 keystore.properties 后构建签名正式版
./gradlew assembleRelease
```

安装 APK 到手机后：
1. **选择你的模式**：
   - **内网直连模式**：同一 Wi-Fi 下点击顶部大开关 **「内网直连」**，3 秒内自动发现电脑并提示输入 6 位内网配对码；
   - **外网中继模式**：进入底部 **「设置」** → 在「外网中继服务器」直接填入 VPS 的 **`203.0.113.10:8443`** → 点击 **「保存中继设置并重连」**（自动开启自签 TLS 兼容并跳回首页）。
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

- [CHANGELOG.md](./CHANGELOG.md) — 版本发布与功能/修复更新日志
- [BUILD.md](./BUILD.md) — Android APK / Windows 托盘程序 / 中继服务干净环境构建说明
- [docs/DUAL_MODE_DESIGN.md](./docs/DUAL_MODE_DESIGN.md) — 内网直连 / 外网中继双模式连接架构设计
- [docs/DEVELOPMENT_GUIDE.md](./docs/DEVELOPMENT_GUIDE.md) — 项目里程碑与架构演进开发文档
- [docs/RELAY_MIGRATION.md](./docs/RELAY_MIGRATION.md) — 零硬编码更换中继服务器与真机验收清单
- [docs/SANITIZATION_REPORT.md](./docs/SANITIZATION_REPORT.md) — 开源发布脱敏自查报告
- [design-system/clawbridge/MASTER.md](./design-system/clawbridge/MASTER.md) — Android 客户端深色 UI 设计规范
