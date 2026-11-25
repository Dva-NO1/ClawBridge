# ClawBridge 开发流程与架构演进文档

本文档记录了 ClawBridge 从零起步到完整可用的里程碑演进、关键协议设计与核心排障结论，帮助开发者理解整个系统是如何一步步搭建起来的。

---

## 1. 里程碑演进路线

### M1：公网 WSS 中继与配对连通
- **目标**：在不依赖 VPN / 内网穿透的前提下，实现安卓手机与 Windows 桌面桥在 5G / Wi-Fi 下的稳定双向连通。
- **实现**：
  - `relay/src/server.mjs`：单进程 Node.js + `ws` 实现最小职责中继（设备鉴权、6 位 10 分钟一次性配对码、绑定对内帧转发、离线通知 FIFO 暂存 200 条）；所有 `deviceToken` 仅以 SHA-256 哈希落盘。
  - `bridge/src/relay-client.mjs` + `power-monitor.mjs`：主动外连中继，支持 1s→60s 指数退避重连与 Windows 休眠唤醒自恢复。
  - `android/`：Kotlin + Jetpack Compose 重建客户端，内置前台保活服务与 DoH DNS 解析。

### M2：双端模型配额与统一会话流
- **目标**：在手机首页展示实时模型配额，并统一浏览 Antigravity 与 Codex 会话、子代理及产物。
- **实现**：
  - `bridge/src/google-quota.mjs`：本机读取 OAuth 凭证（绝不离开本机）每 5 分钟拉取 5h/7d 配额并落盘缓存。
  - `bridge/src/codex-client.mjs`：封装 `codex app-server` JSON-RPC 2.0，独立拉取 OpenAI `ChatGPT` 5h/7d 额度与免费重置券数量，避免与 Antigravity 内置附赠池混淆。
  - `bridge/src/session-service.mjs`：聚合 Antigravity（SQLite + `transcript.jsonl`）与 Codex（`app-server` + `~/.codex/sessions`），提供会话快照、增量流式推送与分片文件下载。

### M3：分级通知与高风险 PIN 审批
- **目标**：息屏/断网不漏重要事件，高风险命令强制二次确认。
- **实现**：
  - `bridge/src/notification-engine.mjs`：统一监控 `approval.pending`、`task.done`、`task.error` 三类事件；高风险正则（`rm/del/format/install/powershell/git reset --hard` 等）在桥端硬编码判定，客户端不可降级。
  - Android 通知栏支持低风险一键批准，高风险强制跳转 App 校验 4–6 位安全 PIN 后放行。

### M4：双向交互增强与原生投递修复
- **目标**：手机端像原生聊天软件一样指挥桌面会话，记忆上次阅读位置。
- **关键工程排障**：
  - 发现 Antigravity 2.34.0 的 `agentapi` 在无环境变量注入时会失败；改为动态扫描 `~/.gemini/antigravity/daemon/hub_*.json`（带存活 PID 校验），直接走 Hub ConnectRPC (`SendUserCascadeMessage`) 原生投递，同时保留 CDP 9222 作为兜底并透传完整错误与退出码。
  - 会话详情页按会话 ID 持久化上次阅读锚点，重进会话自动回到上次看到的位置。

### M5：任务级实时换模型、双向文件传输与静默托盘
- **模型切换**：通过 Antigravity `GetCascadeModelConfigData` 与 Codex `model/list` 动态同步可用模型及推理档位，支持单会话即时切换。
- **手机 ↔ 电脑文件/图片互通**：实现 `upload.chunk` / `upload.complete`（256 KB 分片、最大 64 MB）上传至工作区并支持多模态图片直传；聊天消息内联识别文件路径与 APK 交付物，提供一键下载到手机。
- **Windows 静默托盘**：采用 C# WPF (`/target:winexe`) 编译 `ClawBridgeDesktop.exe`，后台零黑窗拉起 `node.exe`，本地 IPC 强制 `Proxy = null` 绕过系统代理，`Ctrl+Alt+C` 随时呼出。

### v1.6 – v1.7：UI 规范统一与 Codex 单写入者锁治理
- **全 App 深色视觉重构 (`v1.6.0`)**：基于统一设计规范 [MASTER.md](../design-system/clawbridge/MASTER.md) 统一全页面背景层级、排版与柔和状态色，修复窄屏重叠与中继地址截断，并通过 Robolectric Compose 360dp 实机像素渲染自查。
- **高密度可搜索会话列表 (`v1.7.0`)**：重构 56+ 长列表为紧凑行布局，支持顶部即时搜索、时间/项目双视图、状态筛选、编号标题语义化与重复副标题去重。
- **Codex 单写入者锁治理 (`v1.7.1`–`v1.7.2`)**：
  - 查明 Codex `0.167.0` 通过 `%USERPROFILE%\.codex\thread-writer-locks\<id>.lock` 实施系统级独占锁，桌面客户端切会话后仍可能持续持有锁；
  - 桥端实现单线程串行 writer 复用、空闲/切换/断线自动释放 OS 锁（防泄漏）；当遇到外部桌面端独占锁定时，自动调用官方 `thread/fork` 无损继承上下文续接并在原会话流无缝合并回复。
- **直觉式剩余额度条 (`v1.7.3`)**：将配额进度条重做为电池/油表式绿色剩余额度条，一眼看清剩余可用比例。

### v1.1.0（开源版更新）：实时输出速度与花费预估面板 + 会话秒开缓存
- **实时指标采集引擎 (`bridge/src/metrics-service.mjs`)**：仅针对 App 当前订阅会话采集，生成中最多 `1Hz` 节流推送，状态切换与 `metrics.get` 立即补帧；支持在 `%APPDATA%\clawbridge\config.json` 通过 `"metricsEnabled": false` 关闭。
- **双端数据源与诚实口径**：Antigravity 复用 Hub ConnectRPC `GetCascadeTrajectoryGeneratorMetadata`（毫秒级精确 TTFT/TPS/token/缓存/上下文）+ `transcript.jsonl` 兜底 + 可选 CDP 3 秒滑动窗口估算（标 `≈`）；Codex 接入 `app-server` 的 `thread/tokenUsage/updated`、`item/reasoning/*Delta` 与 rollout 精确 usage。
- **外部化可编辑单价表 (`%APPDATA%\clawbridge\prices.json`)**：首次启动自动生成，支持自行增改并热加载，JSON 损坏安全回退默认单价。
- **会话秒开双层缓存 (`SessionDetailCache.kt` + 桥端 `transcript_full.jsonl` 整文件缓存)**：修复点击历史对话等待加载慢的问题，L1 内存 LRU + L2 磁盘缓存实现 0ms 秒开、后台静默同步最新消息。

---

## 2. 协议帧规范速览 (Protocol v1)

所有 WebSocket 帧采用统一的 JSON Envelope：

```json
{
  "v": 1,
  "type": "chat.send",
  "reqId": "uuid-v4",
  "ts": 1730000000000,
  "payload": {}
}
```

| 类别 | 帧类型 | 方向 | 说明 |
|---|---|---|---|
| 鉴权与配对 | `hello` / `hello.ack` / `pair.code.create` / `pair.request` / `pair.result` | 双端 ↔ 中继 | 设备身份校验与 6 位一次性配对 |
| 心跳与在线 | `ping` / `pong` / `presence` | 双端 ↔ 中继 | 30s 探活与对端在线状态同步 |
| 配额与模型 | `quota.get` / `quota.data` / `model.list.get` / `session.model.set` | App ↔ Bridge | 双端配额与任务模型切换 |
| 实时指标 | `metrics.get` / `metrics.data` | App ↔ Bridge | 订阅会话速度、TTFT、token、上下文与预估花费（1Hz 节流） |
| 会话与对话 | `session.list.get` / `session.open` / `session.stream` / `chat.send` / `chat.ack` | App ↔ Bridge | 会话镜像、实时流与消息投递 |
| 审批与通知 | `event.notify` / `approval.pending` / `approval.respond` / `approval.ack` | App ↔ Bridge | 分级推送与高风险 PIN 校验 |
| 文件与产物 | `artifact.get` / `file.download` / `artifact.chunk` / `upload.chunk` / `upload.complete` | App ↔ Bridge | 双向分片文件与图片传输 |
