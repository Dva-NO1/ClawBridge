/**
 * ClawBridge LAN Direct Server (`LanServer`)
 *
 * Responsibilities (P2 Spec):
 *   - Listens on TCP 0.0.0.0:`config.lanPort` (default 18791) via `ws` ONLY when `config.lanEnabled === true`
 *   - Listens on UDP 0.0.0.0:18792 for `CLAWBRIDGE_DISCOVER v1` broadcasts and unicast-replies
 *     `{ deviceId, deviceName, lanPort, lanEnabled }` (NEVER leaks tokens or pairing codes)
 *   - Enforces independent LAN pairing (`lan-pairing.json` 6-digit 10-min rotating code -> issues 32-byte hex `lanToken`)
 *   - Enforces first-frame `auth { deviceId, lanToken }` on paired reconnects; disconnects immediately on failure
 *   - Rate-limits brute-force attempts per IP: 5 consecutive failures -> 5-minute lockout
 */

import http from 'node:http';
import dgram from 'node:dgram';
import os from 'node:os';
import crypto from 'node:crypto';
import { EventEmitter } from 'node:events';
import { WebSocketServer, WebSocket } from 'ws';
import { logger } from './logger.mjs';
import {
  saveConfig,
  readLanPairingInfo,
  writeLanPairingInfo,
} from './claw-config.mjs';
import {
  parseAndValidateEnvelope,
  serializeEnvelope,
} from './transport-router.mjs';

export const DEFAULT_LAN_PORT = 18791;
export const LAN_DISCOVERY_UDP_PORT = 18792;
export const DISCOVER_MAGIC_PREFIX = 'CLAWBRIDGE_DISCOVER v1';

const AUTH_TIMEOUT_MS = 10_000;
const PAIR_CODE_TTL_MS = 10 * 60 * 1000; // 10 minutes
const PING_INTERVAL_MS = 20_000;
const PONG_TIMEOUT_MS = 8_000;
const MAX_FRAME_BYTES = 16 * 1024 * 1024;
const MAX_FAILURES_PER_IP = 5;
const LOCKOUT_DURATION_MS = 5 * 60 * 1000; // 5 minutes
const OFFLINE_NOTIFY_CAP = 60;

const VIRTUAL_ADAPTER_NAME_RE =
  /vethernet|wsl|hyper-v|singbox|clash|mihomo|tun|tap|wintun|tailscale|zerotier|vmware|virtualbox|vbox|docker|loopback|teredo|isatap|bluetooth/i;

function normalizeClientIp(rawAddr) {
  const s = String(rawAddr || '').trim();
  if (s.startsWith('::ffff:')) return s.slice(7);
  return s || 'unknown';
}

function isCgnatOrBenchmarkIpv4(ip) {
  const parts = ip.split('.').map(Number);
  if (parts.length !== 4 || parts.some((n) => Number.isNaN(n))) return false;
  // 100.64.0.0/10 (Tailscale / Carrier NAT)
  if (parts[0] === 100 && parts[1] >= 64 && parts[1] <= 127) return true;
  // 198.18.0.0/15 (FakeIP / TUN benchmark range)
  if (parts[0] === 198 && (parts[1] === 18 || parts[1] === 19)) return true;
  // 169.254.0.0/16 (Link-local)
  if (parts[0] === 169 && parts[1] === 254) return true;
  return false;
}

function ipv4PriorityScore(ifaceName, ip) {
  let score = 0;
  if (/wlan|wi-?fi|wireless|无线/i.test(ifaceName)) score += 40;
  else if (/ethernet|以太网|lan/i.test(ifaceName)) score += 35;

  if (ip.startsWith('192.168.')) score += 30;
  else if (ip.startsWith('10.')) score += 25;
  else if (/^172\.(1[6-9]|2\d|3[0-1])\./.test(ip)) score += 20;
  return score;
}

/**
 * Returns physical LAN IPv4 addresses sorted by likelihood of Wi-Fi / Ethernet reachability,
 * filtering out Hyper-V vEthernet, singbox/Clash TUN, Tailscale, Docker, and VMware virtual adapters.
 */
export function listLocalLanIps() {
  const ifaces = os.networkInterfaces();
  const primary = [];
  const fallback = [];

  for (const [name, addrs] of Object.entries(ifaces)) {
    if (!Array.isArray(addrs)) continue;
    const isVirtualName = VIRTUAL_ADAPTER_NAME_RE.test(name);
    for (const addr of addrs) {
      if (!addr || addr.internal) continue;
      const fam = typeof addr.family === 'string' ? addr.family : addr.family === 4 ? 'IPv4' : '';
      if (fam !== 'IPv4') continue;
      const ip = String(addr.address || '').trim();
      if (!ip || ip.startsWith('127.')) continue;
      if (isCgnatOrBenchmarkIpv4(ip)) continue;

      const entry = { ip, name, score: ipv4PriorityScore(name, ip) };
      if (isVirtualName) {
        fallback.push(entry);
      } else {
        primary.push(entry);
      }
    }
  }

  const chosen = primary.length > 0 ? primary : fallback;
  chosen.sort((a, b) => b.score - a.score);
  return [...new Set(chosen.map((x) => x.ip))];
}

function safeEqualToken(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string' || !a || a.length !== b.length) {
    return false;
  }
  try {
    return crypto.timingSafeEqual(Buffer.from(a, 'utf-8'), Buffer.from(b, 'utf-8'));
  } catch {
    return false;
  }
}

export class LanServer extends EventEmitter {
  constructor(config, { discoveryPort = LAN_DISCOVERY_UDP_PORT } = {}) {
    super();
    this.config = config;
    this.discoveryPort = discoveryPort;

    this.httpServer = null;
    this.wss = null;
    this.udpSocket = null;
    this.running = false;
    this.bindError = '';
    this.udpBindError = '';

    // Pairing & Auth state (independent from WAN Relay!)
    const savedPair = this.config._ephemeral ? null : readLanPairingInfo();
    this.paired = Boolean(this.config.lanToken && (savedPair?.paired ?? true));
    this.pairedPhoneId = savedPair?.phoneDeviceId || '';
    this.pairedDeviceName = savedPair?.deviceName || (this.paired ? '已配对手机' : '');
    this.currentPairingCode = null;
    this.pairingExpiresAt = 0;
    this.pairRotateTimer = null;

    // Active authenticated phone connection
    this.activeClient = null; // { ws, ip, deviceId, deviceName, authenticated, pingTimer, pongTimer }
    this.peerOnline = false;
    this.connectedClientIp = '';
    this.lastRttMs = null;

    // Brute-force protection: ip -> { count, lockedUntil }
    this.ipFailures = new Map();
    // Short offline queue while LAN service is up
    this.offlineNotifyQueue = [];
  }

  get port() {
    const p = Number(this.config.lanPort);
    return p > 0 && p <= 65535 ? p : DEFAULT_LAN_PORT;
  }

  getLanIps() {
    return listLocalLanIps();
  }

  async start() {
    if (this.running) {
      this._ensurePairingCodeIfNeeded();
      return { ok: true, port: this.port };
    }

    this.bindError = '';
    this.udpBindError = '';
    const listenPort = this.port;

    try {
      await this._startWebSocketServer(listenPort);
    } catch (err) {
      this.running = false;
      const friendly =
        err.code === 'EADDRINUSE'
          ? `内网端口 ${listenPort} 已被其他程序占用，请关闭占用进程或修改 config.json 中的 lanPort`
          : err.code === 'EACCES'
            ? `内网端口 ${listenPort} 绑定权限不足，请检查系统端口限制或 Windows 防火墙设置`
            : `内网服务启动失败 (${err.code || 'ERROR'}): ${err.message}`;
      this.bindError = friendly;
      logger.error('LanServer', friendly);
      this.emit('status.changed');
      return { ok: false, error: friendly, code: err.code || 'BIND_FAILED' };
    }

    await this._startUdpDiscoveryResponder();
    this.running = true;
    this._ensurePairingCodeIfNeeded();
    logger.info(
      'LanServer',
      `LAN Direct server listening on ws://0.0.0.0:${this.port} (LAN IPs: ${this.getLanIps().join(', ') || 'none'}, UDP discovery: ${this.udpSocket ? this.discoveryPort : 'unavailable'})`
    );
    this.emit('status.changed');
    return { ok: true, port: this.port };
  }

  async stop() {
    this._clearPairRotateTimer();
    this.currentPairingCode = null;
    this.pairingExpiresAt = 0;

    if (this.activeClient) {
      this._teardownClient(this.activeClient, 1001, 'lan_server_stopped');
      this.activeClient = null;
    }
    this.peerOnline = false;
    this.connectedClientIp = '';
    this.lastRttMs = null;

    if (this.udpSocket) {
      const u = this.udpSocket;
      this.udpSocket = null;
      await new Promise((resolve) => {
        try {
          u.close(() => resolve());
        } catch {
          resolve();
        }
      });
    }

    if (this.wss) {
      for (const client of this.wss.clients) {
        try {
          client.close(1001, 'lan_server_stopped');
        } catch { /* ignore */ }
      }
      const w = this.wss;
      this.wss = null;
      await new Promise((resolve) => {
        try {
          w.close(() => resolve());
        } catch {
          resolve();
        }
      });
    }

    if (this.httpServer) {
      const s = this.httpServer;
      this.httpServer = null;
      await new Promise((resolve) => {
        try {
          s.close(() => resolve());
        } catch {
          resolve();
        }
      });
    }

    const wasRunning = this.running;
    this.running = false;
    if (wasRunning) {
      logger.info('LanServer', 'LAN Direct server stopped');
      this.emit('status.changed');
    }
  }

  sendEnvelope(type, payload = {}, reqId = crypto.randomUUID()) {
    const raw = serializeEnvelope(type, payload, reqId);
    if (this.activeClient?.authenticated && this.activeClient.ws?.readyState === WebSocket.OPEN) {
      this.activeClient.ws.send(raw);
      return true;
    }
    if (type === 'event.notify' && this.running && this.paired) {
      this.offlineNotifyQueue.push(raw);
      if (this.offlineNotifyQueue.length > OFFLINE_NOTIFY_CAP) {
        this.offlineNotifyQueue.shift();
      }
    }
    return false;
  }

  regeneratePairingCode({ forceUnpair = false } = {}) {
    if (forceUnpair) {
      this._clearPairingCredentials();
    }
    if (!this.running) {
      return null;
    }
    const code = String(crypto.randomInt(100000, 1000000));
    const expiresAt = Date.now() + PAIR_CODE_TTL_MS;
    this.currentPairingCode = code;
    this.pairingExpiresAt = expiresAt;

    if (!this.config._ephemeral) {
      writeLanPairingInfo({
        code,
        expiresAt,
        expiresAtIso: new Date(expiresAt).toISOString(),
        paired: this.paired,
        phoneDeviceId: this.pairedPhoneId || null,
        deviceName: this.pairedDeviceName || null,
      });
    }

    logger.info(
      'LanPairing',
      `=== 内网 6 位配对码: ${code} (10 分钟有效，至 ${new Date(expiresAt).toLocaleTimeString()}) ===`
    );
    this._schedulePairRotate(expiresAt);
    this.emit('status.changed');
    return { code, expiresAt };
  }

  unpairAndRequestNewCode() {
    if (this.activeClient?.ws?.readyState === WebSocket.OPEN) {
      try {
        this.activeClient.ws.send(serializeEnvelope('pair.unpaired', { reason: 'desktop_unpaired' }));
        this.activeClient.ws.close(4004, 'unpaired');
      } catch { /* ignore */ }
    }
    this.activeClient = null;
    this.peerOnline = false;
    this.connectedClientIp = '';
    this._clearPairingCredentials();
    if (this.running) {
      this.regeneratePairingCode({ forceUnpair: false });
    }
    this.emit('presence', { online: false, peerMode: '' });
    this.emit('status.changed');
    return true;
  }

  _clearPairingCredentials() {
    this.paired = false;
    this.pairedPhoneId = '';
    this.pairedDeviceName = '';
    this.config.lanToken = '';
    if (!this.config._ephemeral) {
      saveConfig(this.config);
      writeLanPairingInfo({
        code: null,
        expiresAt: 0,
        paired: false,
        phoneDeviceId: null,
        deviceName: null,
      });
    }
  }

  _ensurePairingCodeIfNeeded() {
    if (!this.running) return;
    if (this.paired && this.config.lanToken) {
      this.currentPairingCode = null;
      this.pairingExpiresAt = 0;
      this._clearPairRotateTimer();
      return;
    }
    if (!this.currentPairingCode || Date.now() >= this.pairingExpiresAt - 3_000) {
      this.regeneratePairingCode({ forceUnpair: false });
    }
  }

  _schedulePairRotate(expiresAt) {
    this._clearPairRotateTimer();
    const waitMs = Math.max(5_000, expiresAt - Date.now() - 5_000);
    this.pairRotateTimer = setTimeout(() => {
      this.pairRotateTimer = null;
      if (this.running && !this.paired) {
        logger.info('LanPairing', 'LAN pairing code expired while unpaired; rotating fresh 6-digit code');
        this.regeneratePairingCode({ forceUnpair: false });
      }
    }, waitMs);
    this.pairRotateTimer.unref?.();
  }

  _clearPairRotateTimer() {
    if (this.pairRotateTimer) {
      clearTimeout(this.pairRotateTimer);
      this.pairRotateTimer = null;
    }
  }

  _startWebSocketServer(listenPort) {
    return new Promise((resolve, reject) => {
      const server = http.createServer((req, res) => {
        if (req.method === 'GET' && (req.url === '/healthz' || req.url === '/')) {
          res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
          res.end(
            JSON.stringify({
              ok: true,
              service: 'clawbridge-lan',
              deviceName: os.hostname() || 'ClawBridgePC',
              lanPort: listenPort,
              lanEnabled: true,
            })
          );
          return;
        }
        res.writeHead(404, { 'Content-Type': 'application/json; charset=utf-8' });
        res.end(JSON.stringify({ ok: false, error: 'not_found' }));
      });

      const wss = new WebSocketServer({
        server,
        maxPayload: MAX_FRAME_BYTES,
      });

      const onBindError = (err) => {
        server.removeListener('listening', onListening);
        try { wss.close(); } catch { /* ignore */ }
        try { server.close(); } catch { /* ignore */ }
        reject(err);
      };

      const onListening = () => {
        server.removeListener('error', onBindError);
        this.httpServer = server;
        this.wss = wss;

        server.on('error', (err) => {
          logger.error('LanServer', `Runtime HTTP/WS error: ${err.message}`);
        });

        wss.on('connection', (ws, req) => {
          this._handleIncomingConnection(ws, req);
        });

        resolve();
      };

      server.once('error', onBindError);
      server.once('listening', onListening);
      server.listen(listenPort, '0.0.0.0');
    });
  }

  _startUdpDiscoveryResponder() {
    if (!this.discoveryPort || this.discoveryPort <= 0) return Promise.resolve();
    return new Promise((resolve) => {
      const sock = dgram.createSocket({ type: 'udp4', reuseAddr: true });

      sock.on('error', (err) => {
        this.udpBindError = `UDP 发现端口 ${this.discoveryPort} 绑定受限 (${err.code || err.message})，仍可通过手动输入 IP 直连`;
        logger.warn('LanDiscovery', this.udpBindError);
        try { sock.close(); } catch { /* ignore */ }
        if (this.udpSocket === sock) this.udpSocket = null;
        resolve();
      });

      sock.on('message', (msgBuf, rinfo) => {
        if (!this.running || !this.config.lanEnabled) return;
        const text = msgBuf.toString('utf-8').trim();
        if (!text.startsWith(DISCOVER_MAGIC_PREFIX)) return;

        // Strictly discovery-only public fields — NEVER include any token or pairing code!
        const replyPayload = JSON.stringify({
          deviceId: this.config.deviceId,
          deviceName: os.hostname() || 'ClawBridge-PC',
          lanPort: this.port,
          lanEnabled: true,
        });
        sock.send(Buffer.from(replyPayload, 'utf-8'), rinfo.port, rinfo.address, (err) => {
          if (err) {
            logger.warn('LanDiscovery', `UDP reply warning to ${rinfo.address}:${rinfo.port}: ${err.message}`);
          }
        });
      });

      sock.bind(this.discoveryPort, '0.0.0.0', () => {
        try { sock.setBroadcast(true); } catch { /* ignore */ }
        this.udpSocket = sock;
        resolve();
      });
    });
  }

  _getIpLockRemainingMs(ip) {
    const rec = this.ipFailures.get(ip);
    if (!rec) return 0;
    if (rec.lockedUntil && Date.now() < rec.lockedUntil) {
      return rec.lockedUntil - Date.now();
    }
    if (rec.lockedUntil && Date.now() >= rec.lockedUntil) {
      this.ipFailures.delete(ip);
    }
    return 0;
  }

  _recordIpFailure(ip) {
    const now = Date.now();
    const cur = this.ipFailures.get(ip) || { count: 0, lockedUntil: 0 };
    if (cur.lockedUntil && now >= cur.lockedUntil) {
      cur.count = 0;
      cur.lockedUntil = 0;
    }
    cur.count += 1;
    if (cur.count >= MAX_FAILURES_PER_IP) {
      cur.lockedUntil = now + LOCKOUT_DURATION_MS;
      this.ipFailures.set(ip, cur);
      logger.warn('LanSecurity', `IP ${ip} locked for 5 minutes after ${cur.count} failed auth/pair attempts`);
      return { locked: true, remainingSec: Math.ceil(LOCKOUT_DURATION_MS / 1000) };
    }
    this.ipFailures.set(ip, cur);
    return { locked: false, attemptsLeft: MAX_FAILURES_PER_IP - cur.count };
  }

  _clearIpFailures(ip) {
    this.ipFailures.delete(ip);
  }

  _handleIncomingConnection(ws, req) {
    const ip = normalizeClientIp(req?.socket?.remoteAddress);
    const lockRemMs = this._getIpLockRemainingMs(ip);
    if (lockRemMs > 0) {
      const remSec = Math.ceil(lockRemMs / 1000);
      try {
        ws.send(
          serializeEnvelope('auth.error', {
            ok: false,
            code: 'rate_limited',
            message: `连续验证失败次数过多，请等待 ${remSec} 秒后再试`,
            retryAfterSec: remSec,
          })
        );
        ws.close(4029, 'rate_limited');
      } catch { /* ignore */ }
      return;
    }

    const ctx = {
      ws,
      ip,
      authenticated: false,
      deviceId: '',
      deviceName: '',
      peerMode: 'lan',
      authTimer: null,
      pingTimer: null,
      pongTimer: null,
    };

    ctx.authTimer = setTimeout(() => {
      if (!ctx.authenticated) {
        try {
          ws.send(serializeEnvelope('auth.error', { ok: false, code: 'auth_timeout', message: '内网鉴权超时' }));
          ws.close(4001, 'auth_timeout');
        } catch { /* ignore */ }
      }
    }, AUTH_TIMEOUT_MS);
    ctx.authTimer.unref?.();

    ws.on('pong', (buf) => {
      if (ctx.pongTimer) {
        clearTimeout(ctx.pongTimer);
        ctx.pongTimer = null;
      }
      const sentTs = Number(buf?.toString('utf-8') || 0);
      if (sentTs > 0) {
        this.lastRttMs = Math.max(1, Date.now() - sentTs);
        this.emit('status.changed');
      }
    });

    ws.on('message', (data, isBinary) => {
      if (isBinary) {
        try {
          ws.send(
            serializeEnvelope('protocol.error', {
              code: 'binary_not_supported',
              message: 'Binary frames are disabled; use JSON envelopes',
            })
          );
          if (!ctx.authenticated) ws.close(4002, 'binary_not_supported');
        } catch { /* ignore */ }
        return;
      }

      this._handleClientFrame(ctx, data.toString('utf-8'));
    });

    ws.on('close', () => {
      this._clearClientTimers(ctx);
      if (this.activeClient === ctx) {
        this.activeClient = null;
        this.peerOnline = false;
        this.connectedClientIp = '';
        logger.info('LanServer', `LAN phone disconnected (${ip})`);
        this.emit('presence', { online: false, peerMode: '' });
        this.emit('status.changed');
      }
    });

    ws.on('error', (err) => {
      logger.warn('LanServer', `Client socket error (${ip}): ${err.message}`);
    });
  }

  _handleClientFrame(ctx, rawText) {
    const lockRemMs = this._getIpLockRemainingMs(ctx.ip);
    if (lockRemMs > 0) {
      const remSec = Math.ceil(lockRemMs / 1000);
      try {
        ctx.ws.send(
          serializeEnvelope('auth.error', {
            ok: false,
            code: 'rate_limited',
            message: `该 IP 已被临时锁定，请 ${remSec} 秒后再试`,
            retryAfterSec: remSec,
          })
        );
        ctx.ws.close(4029, 'rate_limited');
      } catch { /* ignore */ }
      return;
    }

    const parsed = parseAndValidateEnvelope(rawText);
    if (!parsed.ok) {
      try {
        ctx.ws.send(
          serializeEnvelope(parsed.errorFrame.type, parsed.errorFrame.payload, parsed.errorFrame.reqId)
        );
        if (!ctx.authenticated) {
          ctx.ws.close(4002, parsed.reason);
        }
      } catch { /* ignore */ }
      return;
    }

    const { envelope } = parsed;
    const { type, reqId, ts, payload } = envelope;

    // ─── Unauthenticated Gate: First frame MUST be `auth` or `pair.request` ───
    if (!ctx.authenticated) {
      if (type === 'auth') {
        this._handleAuthFrame(ctx, payload, reqId);
        return;
      }
      if (type === 'pair.request') {
        this._handlePairRequestFrame(ctx, payload, reqId);
        return;
      }

      // Reject & immediately disconnect any unauthenticated business or ping frame!
      logger.warn('LanSecurity', `Rejected unauthenticated frame "${type}" from ${ctx.ip}; closing socket`);
      try {
        ctx.ws.send(
          serializeEnvelope(
            'auth.error',
            {
              ok: false,
              code: 'unauthorized',
              message: '未鉴权连接：首帧必须发送 auth 或 pair.request',
            },
            reqId
          )
        );
        ctx.ws.close(4003, 'unauthorized');
      } catch { /* ignore */ }
      return;
    }

    // ─── Authenticated Control & Business Frames ──────────────────────────────
    if (type === 'ping') {
      try {
        ctx.ws.send(
          serializeEnvelope(
            'pong',
            {
              clientTs: ts || Date.now(),
              serverTs: Date.now(),
              peerOnline: true,
            },
            reqId
          )
        );
      } catch { /* ignore */ }
      return;
    }

    if (type === 'pair.unpair') {
      this.unpairAndRequestNewCode();
      return;
    }

    this.emit('message', envelope);
  }

  _handleAuthFrame(ctx, payload, reqId) {
    const deviceId = String(payload?.deviceId || '').trim();
    const providedToken = String(payload?.lanToken || '').trim();
    const deviceName = String(payload?.deviceName || '').trim();
    const peerMode = String(payload?.mode || 'lan').trim().toLowerCase();

    const expectedToken = String(this.config.lanToken || '').trim();
    const idMatches = !this.pairedPhoneId || this.pairedPhoneId === deviceId;

    if (!deviceId || !expectedToken || !safeEqualToken(providedToken, expectedToken) || !idMatches) {
      const fail = this._recordIpFailure(ctx.ip);
      const msg = fail.locked
        ? `连续验证失败 ${MAX_FAILURES_PER_IP} 次，已锁定 5 分钟`
        : '内网凭证无效或已解除绑定，请重新输入桌面端 6 位内网配对码';
      try {
        ctx.ws.send(
          serializeEnvelope(
            'auth.error',
            {
              ok: false,
              code: fail.locked ? 'rate_limited' : 'invalid_lan_token',
              message: msg,
            },
            reqId
          )
        );
        ctx.ws.close(fail.locked ? 4029 : 4003, fail.locked ? 'rate_limited' : 'auth_failed');
      } catch { /* ignore */ }
      return;
    }

    this._clearIpFailures(ctx.ip);
    this._promoteAuthenticatedClient(ctx, {
      deviceId,
      deviceName: deviceName || this.pairedDeviceName || '已配对手机',
      peerMode,
    });

    ctx.ws.send(
      serializeEnvelope(
        'auth.ack',
        {
          ok: true,
          paired: true,
          peerOnline: true,
          bridgeDeviceId: this.config.deviceId,
          bridgeDeviceName: os.hostname() || 'ClawBridge-PC',
        },
        reqId
      )
    );

    logger.info('LanServer', `Authenticated LAN phone "${ctx.deviceName}" (${ctx.ip})`);
    this._flushOfflineNotifications(ctx.ws);
    this.emit('authenticated', { paired: true, peerOnline: true, peerMode });
    this.emit('presence', { online: true, peerMode });
    this.emit('status.changed');
  }

  _handlePairRequestFrame(ctx, payload, reqId) {
    const inputCode = String(payload?.code || '').trim();
    const phoneId = String(payload?.deviceId || '').trim() || crypto.randomUUID();
    const deviceName = String(payload?.deviceName || '局域网手机').trim();

    const codeValid =
      Boolean(this.currentPairingCode) &&
      Date.now() < this.pairingExpiresAt &&
      inputCode.length === 6 &&
      inputCode === this.currentPairingCode;

    if (!codeValid) {
      const fail = this._recordIpFailure(ctx.ip);
      const errMsg = fail.locked
        ? `配对码连续输错 ${MAX_FAILURES_PER_IP} 次，已锁定 5 分钟后再试`
        : `内网配对码不正确或已过期（剩余可试 ${fail.attemptsLeft} 次）`;
      try {
        ctx.ws.send(
          serializeEnvelope(
            'pair.result',
            {
              success: false,
              code: fail.locked ? 'rate_limited' : 'invalid_pair_code',
              message: errMsg,
            },
            reqId
          )
        );
        if (fail.locked) {
          ctx.ws.close(4029, 'rate_limited');
        }
      } catch { /* ignore */ }
      return;
    }

    this._clearIpFailures(ctx.ip);
    this._clearPairRotateTimer();

    // Issue fresh independent 32-byte hex lanToken
    const issuedLanToken = crypto.randomBytes(32).toString('hex');
    this.config.lanToken = issuedLanToken;
    this.paired = true;
    this.pairedPhoneId = phoneId;
    this.pairedDeviceName = deviceName;
    this.currentPairingCode = null;
    this.pairingExpiresAt = 0;

    if (!this.config._ephemeral) {
      saveConfig(this.config);
      writeLanPairingInfo({
        code: null,
        expiresAt: 0,
        paired: true,
        phoneDeviceId: phoneId,
        deviceName,
        pairedAt: new Date().toISOString(),
      });
    }

    this._promoteAuthenticatedClient(ctx, {
      deviceId: phoneId,
      deviceName,
      peerMode: 'lan',
    });

    ctx.ws.send(
      serializeEnvelope(
        'pair.result',
        {
          success: true,
          lanToken: issuedLanToken,
          bridgeDeviceId: this.config.deviceId,
          bridgeDeviceName: os.hostname() || 'ClawBridge-PC',
          bridgeOnline: true,
        },
        reqId
      )
    );

    logger.info('LanPairing', `Successfully paired LAN phone "${deviceName}" (${ctx.ip})`);
    this._flushOfflineNotifications(ctx.ws);
    this.emit('paired', { phoneDeviceId: phoneId, deviceName });
    this.emit('presence', { online: true, peerMode: 'lan' });
    this.emit('status.changed');
  }

  _promoteAuthenticatedClient(ctx, { deviceId, deviceName, peerMode }) {
    if (ctx.authTimer) {
      clearTimeout(ctx.authTimer);
      ctx.authTimer = null;
    }
    if (this.activeClient && this.activeClient !== ctx) {
      this._teardownClient(this.activeClient, 4000, 'replaced_by_newer_session');
    }
    ctx.authenticated = true;
    ctx.deviceId = deviceId;
    ctx.deviceName = deviceName;
    ctx.peerMode = peerMode || 'lan';

    this.activeClient = ctx;
    this.peerOnline = true;
    this.connectedClientIp = ctx.ip;
    this.pairedDeviceName = deviceName;
    this._startClientHeartbeat(ctx);
  }

  _startClientHeartbeat(ctx) {
    this._clearClientTimers(ctx);
    // Measure initial LAN RTT right after auth
    setTimeout(() => {
      if (this.activeClient === ctx && ctx.ws.readyState === WebSocket.OPEN) {
        try { ctx.ws.ping(Buffer.from(String(Date.now()))); } catch { /* ignore */ }
      }
    }, 250).unref?.();

    ctx.pingTimer = setInterval(() => {
      if (ctx.ws.readyState !== WebSocket.OPEN) return;
      if (ctx.pongTimer) clearTimeout(ctx.pongTimer);
      ctx.pongTimer = setTimeout(() => {
        logger.warn('LanServer', `LAN client pong timeout (${ctx.ip}); terminating dead socket`);
        try { ctx.ws.terminate(); } catch { /* ignore */ }
      }, PONG_TIMEOUT_MS);
      ctx.pongTimer.unref?.();

      try {
        ctx.ws.ping(Buffer.from(String(Date.now())));
      } catch { /* ignore */ }
    }, PING_INTERVAL_MS);
    ctx.pingTimer.unref?.();
  }

  _flushOfflineNotifications(ws) {
    if (!this.offlineNotifyQueue.length) return;
    const queued = this.offlineNotifyQueue.splice(0);
    for (const frame of queued) {
      if (ws.readyState === WebSocket.OPEN) {
        ws.send(frame);
      }
    }
  }

  _teardownClient(ctx, code, reason) {
    this._clearClientTimers(ctx);
    try {
      ctx.ws.close(code, reason);
    } catch { /* ignore */ }
  }

  _clearClientTimers(ctx) {
    if (ctx.authTimer) {
      clearTimeout(ctx.authTimer);
      ctx.authTimer = null;
    }
    if (ctx.pingTimer) {
      clearInterval(ctx.pingTimer);
      ctx.pingTimer = null;
    }
    if (ctx.pongTimer) {
      clearTimeout(ctx.pongTimer);
      ctx.pongTimer = null;
    }
  }
}
