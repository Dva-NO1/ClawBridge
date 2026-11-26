# ClawBridge 双模式连接（内网直连 / 外网中继）P1 调研与设计要点

> 阶段：**P1 只读调研产出**（未修改 `bridge/`、`android/`、`relay/` 任何现有代码）  
> 目标：在不破坏现行外网中继（WAN Relay）与任何业务功能的前提下，新增零依赖、强鉴权、低延迟的局域网直连模式（LAN Direct），并在桌面端与手机端提供醒目大尺寸手动切换开关。

---

## 一、现行链路只读梳理（文件 / 字段 / 行号级证据）

### 1.1 桥端外网连接层：`bridge/src/relay-client.mjs` + `claw-config.mjs`

1. **连接与 TLS 策略（`relay-client.mjs` L227–275）**
   - 主动连接 `config.relayUrl`（默认 `wss://relay.example.com`），强制要求 `wss://`（仅 `127.0.0.1` / `localhost` 允许 `ws://`）。
   - 使用 `resilientLookup`（DoH 防 DNS 劫持）与 `rejectUnauthorized: !config.trustSelfSigned`。
2. **鉴权与配对生命周期（`relay-client.mjs` L265–271, L350–453, L496–507）**
   - **首帧握手**：连接建立后发送 `hello` 帧：`{ role: "bridge", deviceId, deviceToken, deviceName: os.hostname(), appVersion: "1.0.0" }`。
   - **鉴权确认（`hello.ack`）**：若中继首次签发 `issuedToken`（32 字节随机 hex，中继侧仅存 SHA-256 哈希），桥端将其持久化至 `%APPDATA%\clawbridge\config.json` 的 `deviceToken` 字段；若 `paired === false`，立即发送 `pair.code.create` 申请 6 位一次性配对码。
   - **令牌失效自愈（`hello.error`）**：当 `code === "invalid_token"` 时自动轮换 `deviceId` 并清空 `deviceToken` 重新注册。
   - **6 位配对码轮换（`pair.code.created` / `pair.result` / `pair.unpaired`）**：配对码有效期 10 分钟，落盘 `%APPDATA%\clawbridge\pairing-code.json`，到期前 5 秒由 `pairRenewTimer` 自动续期；配对成功后记录 `pairedDeviceName` 并触发 `'paired'` 事件。
3. **心跳与休眠恢复（`relay-client.mjs` L23–27, L52–58, L176–201, L473–547）**
   - 每 `20s` 发送协议层 `ws.ping()` + 业务层 `ping` Envelope，设 `8s` `pongDeadlineTimer` 超时断线自愈；收到 `pong` 计算 `lastRttMs = Date.now() - payload.clientTs`。
   - 断线重连采用 `1s → 60s` 指数退避（带 `0.85~1.15` 抖动）；`PowerMonitor` 监听系统唤醒事件后立即调用 `forceReconnectNow()` 重置退避在 `300ms` 后重连。
4. **Envelope 校验与业务分发路径（`relay-client.mjs` L212–225, L307–471 → `index.mjs` L195–843）**
   - **Envelope 统一结构**：`{ v: 1, type, reqId, ts, payload }`。
   - **校验逻辑**：`_handleFrame` 解析 JSON 后校验 `msg.v === 1`；版本不符立即回发 `protocol.error { code: "version_mismatch", expectedVersion: 1, receivedVersion }`。
   - **业务分发**：剥离连接控制帧（`pong` / `hello.*` / `pair.*` / `presence` / `protocol.error`）后，通过 `this.emit('message', { type, reqId, ts, payload })` 送入 `bridge/src/index.mjs` L388 的 `switch (type)` 路由（涵盖 `session.*`、`model.*`、`chat.send`、`quick.command`、`approval.*`、`quota.get`、`metrics.get`、`artifact.get`、`file.download`、`upload.*`、`project.create`、`projects.config.*`）。
5. **`claw-config.mjs` 与本机 `%APPDATA%\clawbridge\config.json` 现有字段**
   - 原子写入（`.tmp` + `fs.renameSync`）；`relayUrl` 变更时自动清空旧中继 `deviceToken`。
   - **本机现行 `config.json` 14 个字段**：
     `relayUrl`、`deviceId`、`deviceToken`、`lastBoundRelayUrl`、`trustSelfSigned`、`cdpPort`、`antigravityExePath`、`targetAntigravityVersion`、`quotaRefreshIntervalSec`、`metricsEnabled`、`projectsRootDir`、`assistantSessionId`、`personaPath`、`schedules`。
   - **日志脱敏（`logger.mjs` L22–30）**：已过滤 `deviceToken|issuedToken|...` 与 64 位 hex 字符串；P2 将显式把 `lanToken` 纳入脱敏正则。

---

### 1.2 Android 端连接层与配对存储

1. **连接与状态管理（`android/.../net/RelaySocketClient.kt`）**
   - `ClawBridgeApp` 全局持有单例 `socketClient: RelaySocketClient`，向上游 `MonitorRepository`、`NotificationCenter`、`RelayForegroundService`、`ClawBridgeMainApp` 暴露：
     - `uiState: StateFlow<ConnectionUiState>`（含 `socketStatus`、`relayUrl`、`paired`、`desktopOnline`、`latencyMs`、`networkTypeLabel`、错误与配对状态）
     - `incomingFrames: SharedFlow<RelayFrame>`
     - `sendEnvelope(type, payload, reqId)` / `reconnectNow()` / `sendPingNow()` / `pulseFromDoze()` / `submitPairingCode(code)` / `unpairAndReset()`
   - 重连与探活：`1s → 30s` 指数退避 + `ConnectivityManager.NetworkCallback` 网络切换即时重连 + `25s` 业务 `ping` + 锁屏 Doze 下 `KeepalivePulseReceiver` 每 `3.5` 分钟唤醒探活。
2. **现行配对信息存储位置（`android/.../data/SettingsStore.kt`）**
   - 存储在 Jetpack `Preferences DataStore`（`clawbridge_settings`）：`relay_url`、`device_id`、`device_token`（中继凭证）、`paired_bridge_id`、`trust_self_signed`、`pin_hash` / `pin_salt` 等。
3. **调研发现的两个 Android 系统级关键约束（必须在 P4 处理）**
   - **约束 1（Android 9+ 明文流量拦截）**：`AndroidManifest.xml` L24 当前配置了 `android:usesCleartextTraffic="false"`。在 Android 14/15 (`targetSdk = 35`) 下，系统网络层会直接阻断 `ws://192.168.1.100:18791` 的局域网 WebSocket 连接。P4 需配置网络安全策略允许局域网连接，同时在 `RelayTransport` 代码层继续强制拦截非回环的明文外网 `ws://`（外网中继仍严格要求 `wss://`）。
   - **约束 2（代理与 DNS 旁路）**：现行 `RelaySocketClient` 挂载了 `DoHDns` 且未禁用系统 HTTP 代理；内网 `LanTransport` 直连局域网 IP 时必须配置 `Proxy.NO_PROXY` 与 `Dns.SYSTEM`，避免手机开启 VPN/代理客户端时把 `10.x` / `192.168.x` 流量误送入远端代理导致连不上电脑。

---

### 1.3 桌面端 GUI 与本地 IPC 接口梳理（`local-ipc.mjs` & `ClawBridgeDesktop.cs`）

- **通信方式**：`127.0.0.1:18790` HTTP JSON，强制校验请求头 `X-ClawBridge-Token`（读取 `%APPDATA%\clawbridge\ipc.token`）；C# WPF 桌面端每 `1200ms` 轮询一次 `GET /api/status`（已设 `req.Proxy = null`）。
- **现行全部 11 个 IPC 端点清单**：

| 方法与路径 | 功能说明 |
| :--- | :--- |
| `GET /api/status` | 查询守护进程状态、中继连接/鉴权/配对状态、6 位配对码与过期时间、RTT、CDP 状态、AGY/Codex 配额、最近 50 条脱敏日志 |
| `POST /api/connect` | 手动恢复连接中继（`relay.connectManually()`） |
| `POST /api/disconnect` | 手动暂停断开中继（`relay.disconnectManually()`） |
| `POST /api/reconnect` | 立即强制重连中继（重置退避） |
| `POST /api/new-pair-code` | 向中继申请新 6 位一次性配对码 |
| `POST /api/unpair` | 解除当前中继手机绑定并重新申请配对码 |
| `POST /api/relay-config` | 动态修改 `relayUrl` / `trustSelfSigned` 并重连 |
| `POST /api/refresh-quota` | 立即刷新 Google 配额与本机 CDP 状态 |
| `POST /api/test-notify` | 发送锁屏与离线队列联调测试通知 |
| `POST /api/launch-antigravity-cdp` | 以 `--remote-debugging-port=9222` 重启 Antigravity |
| `POST /api/shutdown` | 彻底退出桥守护进程 |

---

## 二、《双模式设计要点》架构方案

### 2.1 业务消息处理与传输层的耦合点及最小改动抽离方案

#### （1）当前耦合点定位
- **Bridge 端**：业务模块（`index.mjs` 路由与主动推送、`notification-engine.mjs` 通知与审批响应）仅依赖传输对象的 4 个接口契约：
  1. `peerOnline: boolean`（判断对端手机当前是否在线可推送）
  2. `sendEnvelope(type, payload, reqId): boolean`（向对端发送 Protocol v1 JSON 帧）
  3. 事件 `'message'`（接收已通过 Envelope `v === 1` 校验与鉴权的业务请求帧 `{ type, reqId, ts, payload }`）
  4. 事件 `'presence'` / `'paired'` / `'authenticated'`（触发上线初始全量快照推送 `pushInitialStateToPhone()` 与离线资源释放）
- **Android 端**：`MonitorRepository`、`NotificationCenter`、`RelayForegroundService`、`ClawBridgeMainApp` 仅依赖 `socketClient` 的 `uiState`、`incomingFrames`、`sendEnvelope(...)` 及配对/重连控制方法，业务解析逻辑（会话、模型、文件分片、指标、PIN 审批）与底层是中继还是局域网完全正交。

#### （2）Bridge 端抽离设计（最小改动面）
1. **抽离共享协议校验函数 `validateIncomingEnvelope(rawText)`（新建 `bridge/src/transport-router.mjs`）**：
   - 统一完成 JSON 解析、`msg.v === 1` 协议版本强校验（不匹配统一生成 `protocol.error { code: 'version_mismatch' }`），保证 `RelayClient` 与 `LanServer` 的 Envelope 校验行为一字不差。
2. **引入传输门面 `BridgeTransportRouter`（`EventEmitter`）**：
   - 聚合 `RelayClient`（外网）与 `LanServer`（内网），向 `index.mjs` 和 `NotificationEngine` 暴露与原 `relay` **100% 相同的接口**（`peerOnline`、`sendEnvelope`、`'message'`、`'presence'`、`'paired'`）。
   - **严格单路排他（不做双路同时连接）**：
     - 当 `config.lanEnabled === false`（默认，外网中继模式）：`LanServer` 停止监听（释放 TCP `18791` 与 UDP `18792`），`RelayClient` 按现行逻辑工作，行为零变化；
     - 当 `config.lanEnabled === true`（内网直连模式）：`RelayClient` 挂起断开连接（不清除 `deviceId` / `deviceToken` 配对凭证，切回外网时秒级自动重连无需重配），启动 `LanServer` 接管收发。
   - **新增轻量模式同步帧 `client.mode`**：手机连接建立后上报 `{ mode: "lan" | "wan" }`，桥端记录 `peerConnectionMode` 供 IPC 状态展示，旧端忽略无副作用。

#### （3）Android 端抽离设计（`ConnectionTransport` 接口）
1. 新增 `interface ConnectionTransport`：定义 `mode: ConnectionMode` (`WAN` / `LAN`)、`uiState: StateFlow<ConnectionUiState>`、`incomingFrames: SharedFlow<RelayFrame>`、`start()`、`stop()`、`reconnectNow()`、`sendPingNow()`、`pulseFromDoze()`、`submitPairingCode(code)`、`unpairAndReset()`、`sendEnvelope(type, payload, reqId)`。
2. 现行中继逻辑原样封装为 `RelayTransport`；新增 `LanTransport` 实现内网 UDP 发现、已知 IP 优先直连、手动 IP 兜底、内网鉴权与内网配对。
3. `RelaySocketClient` 保留原类名作为**双模式传输调度器（Facade）**，内部根据持久化的 `connectionMode` 仅启动对应单一 `ConnectionTransport`（切换模式时先 `stop()` 旧传输再 `start()` 新传输）。上游 `MonitorRepository`、`NotificationCenter`、会话与指标 UI 无需改动任何业务代码。

---

### 2.2 内网服务端口与局域网发现协议选型结论

1. **端口选型（本机实测均空闲无冲突）**
   - **本地 GUI 控制 IPC**：TCP `127.0.0.1:18790`（现行保持不变）
   - **内网 WebSocket 直连服务（`LanServer`）**：TCP **`0.0.0.0:18791`**（由 `config.lanPort` 配置，默认 `18791`）
   - **内网局域网自动发现（UDP Responder）**：UDP **`0.0.0.0:18792`**（固定发现端口 `18792`，仅在 `lanEnabled === true` 时绑定响应）

2. **发现协议选型：UDP 广播请求 + 单播 JSON 响应（零第三方依赖）**
   - **弃用 mDNS 的原因**：Windows 多网卡环境（本机实测同时存在 `以太网 192.168.1.100`、`vEthernet 172.20.192.1`、`vpn_tun 172.18.0.1`、`Tailscale 100.91.107.121`）下，mDNS 组播极易绑定到虚拟网卡且需额外依赖。
   - **UDP 发现交互规范**：
     - 手机端向当前 Wi-Fi 子网广播地址（如 `10.0.0.255:18792`）及受限广播地址（`255.255.255.255:18792`）发送 UTF-8 报文：`CLAWBRIDGE_DISCOVER v1`（单次超时 `3s`，最多重试 `2` 次；若存在上次成功连接的已知局域网 IP，优先尝试快速直连该 IP，失败再走广播发现以自动适应 DHCP 换 IP）。
     - 桥端 UDP `18792` 收到合法探测包后，向来源 `rinfo.address:rinfo.port` 单播回复：
       ```json
       {
         "v": 1,
         "service": "clawbridge-lan",
         "deviceId": "00000000-0000-4000-8000-000000000002",
         "deviceName": "DESKTOP-XXXX",
         "lanPort": 18791,
         "lanEnabled": true
       }
       ```
     - **安全铁律**：UDP 回复仅含上述公开路由字段，严禁包含 `lanToken`、`deviceToken` 或配对码。
   - **本机物理局域网 IP 智能筛选**：
     - 过滤 `vEthernet`、`WSL`、`singbox`、`tun`、`tap`、`clash`、`tailscale`、`vmware`、`vbox`、`docker` 等虚拟/隧道网卡及 `198.18.0.0/15`、`100.64.0.0/10`、`169.254.0.0/16` 地址段，优先提取真实物理网卡 IPv4（如本机 `以太网: 192.168.1.100`）展示在桌面端面板上供用户核对或手动输入兜底。

---

### 2.3 内网独立配对、鉴权与安全机制设计

1. **存储隔离（与外网中继配对完全独立、互不影响）**
   - **桥端 `%APPDATA%\clawbridge\config.json` 新增字段**：
     - `lanEnabled`: `false`（默认关闭）
     - `lanPort`: `18791`
     - `lanToken`: `""`（存已配对手机 `lanToken` 的 SHA-256 哈希或独立校验值；配合 `lan-pairing.json` 存储已配对 `phoneDeviceId`、`deviceName`、`pairedAt` 及当前未配对时的 6 位临时配对码与 `expiresAt`）
     - `lastConnectionMode`: `"wan"`（`"wan"` | `"lan"`，仅用于状态记录展示）
   - **手机端安全存储**：
     - `lanToken` 采用 **Android Keystore (`AES/GCM/NoPadding` 256-bit 硬件密钥)** 加密存储（封装 `LanCredentialStore`），绝不明文落盘、绝不入日志；
     - 外网中继原有的 `deviceToken` / `pairedBridgeId` / `relayUrl` 原封不动保留，来回切换内网/外网均**无需重新配对**。
2. **内网握手与限速防爆破**
   - **未配对首次绑定**：桌面端开启内网模式且无有效绑定时，生成独立 6 位数字内网配对码（10 分钟有效，到期自动轮换，存 `lan-pairing.json`）；手机连接 `ws://<ip>:<lanPort>` 后发送 `lan.pair.request { code, deviceId, deviceName }` → 校验通过后桥端生成随机 32 字节 hex `lanToken`，返回 `lan.pair.result { success: true, lanToken, bridgeDeviceId, bridgeDeviceName }`，随即进入已鉴权态并推送初始会话/配额快照。
   - **已配对日常重连**：手机连接 `ws://<ip>:<lanPort>` 后必须在 `8s` 内首帧发送 `auth { deviceId, lanToken, deviceName, mode: "lan" }`（兼容标准 Envelope `{ v: 1, type: "auth", payload: { ... } }`）；常量时间比对（`crypto.timingSafeEqual`）通过后返回 `auth.ack { ok: true, bridgeDeviceId, bridgeDeviceName }` 并推送初始快照；**未发送 `auth` 直接发业务帧、超时或 `lanToken` 校验失败均立即返回错误并断开连接（Close `4001`/`4003`）**。
   - **防暴力破解限速**：按客户端来源 IP 统计配对/鉴权失败次数，同一 IP 连续失败 **5 次** 立即锁定 **5 分钟**（期间拒绝配对尝试并返回剩余锁定秒数提示）。
   - **PIN 审批不降级**：`NotificationEngine.handleApprovalRespond` 位于传输层之上，高风险命令的强制 PIN 校验在内网直连与外网中继下完全一致生效。

---

### 2.4 新增本地 IPC 接口规划（供 P3 桌面端 GUI 调用）

在保留现有 11 个 IPC 端点兼容性的基础上，`local-ipc.mjs` 扩展如下：
1. `GET /api/status` 新增字段（不含任何明文 token）：
   - `connectionMode`: `"lan"` | `"wan"`
   - `lanEnabled`: `boolean`
   - `lanRunning`: `boolean`
   - `lanPort`: `number`
   - `lanIps`: `string[]`（过滤虚拟网卡后的本机局域网 IPv4 列表，如 `["192.168.1.100"]`）
   - `lanBindError`: `string | null`（端口占用或绑定异常中文原因）
   - `lanFirewallHint`: `boolean`（首次开启或无入站连接时的防火墙提示辅助位）
   - `lanPaired`: `boolean`
   - `lanPairedDeviceName`: `string`
   - `lanPeerOnline`: `boolean`
   - `lanLastRttMs`: `number | null`
   - `lanPairingCode`: `string`（6 位内网配对码，已配对时为空）
   - `lanPairingExpiresAt`: `number`
   - `peerConnectionMode`: `"lan"` | `"wan"` | `""`（手机上报的对端当前模式）
2. `POST /api/mode`：请求体 `{ mode: "lan" | "wan" }` —— 动态启停 `LanServer` 与切换 `RelayClient`，更新 `config.lanEnabled` 与 `config.lastConnectionMode`，不重启桥进程。
3. `POST /api/lan/new-pair-code`：刷新生成新的 6 位内网配对码。
4. `POST /api/lan/unpair`：解除当前内网配对并立即生成新 6 位内网配对码（不影响外网中继配对）。
