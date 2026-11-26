/**
 * ClawBridge Relay Server (Single-Process Node.js + ws)
 *
 * Responsibilities (strictly 3 things):
 *   1. Authenticate devices (UUID + 32-byte random deviceToken, stored ONLY as SHA-256 hash)
 *   2. Forward messages strictly within bound (phone <-> bridge) pairs
 *   3. Buffer `event.notify` messages when phone is offline (FIFO, cap 200 per pair)
 *
 * Does NOT parse business payloads, does NOT store chat history, does NOT expose open HTTP registration.
 */

import http from 'node:http';
import https from 'node:https';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { WebSocketServer, WebSocket } from 'ws';

const PROTOCOL_VERSION = 1;
const HOST = process.env.RELAY_HOST || '127.0.0.1';
const PORT = parseInt(process.env.RELAY_PORT || '8787', 10);
const STATE_FILE = process.env.RELAY_STATE_FILE || path.resolve(process.cwd(), 'data', 'state.json');
const TLS_CERT_FILE = (process.env.RELAY_TLS_CERT || '').trim();
const TLS_KEY_FILE = (process.env.RELAY_TLS_KEY || '').trim();

const HEARTBEAT_PING_MS = 30_000;
const HEARTBEAT_TIMEOUT_MS = 90_000;
const AUTH_HELLO_TIMEOUT_MS = 10_000;
const PAIR_CODE_TTL_MS = 10 * 60 * 1000; // 10 minutes
const OFFLINE_NOTIFY_LIMIT = 200;
const MAX_CONN_PER_MIN_PER_IP = 20;
const MAX_PAIR_FAILURES_PER_IP = 8;
const PAIR_LOCKOUT_MS = 10 * 60 * 1000;
const MAX_UNPAIRED_BRIDGES = 10;
const MAX_FRAME_BYTES = 16 * 1024 * 1024; // 16 MB for image/file artifact chunks

// ─── Sanitized Logger (never logs deviceToken or raw payload secrets) ────────

function redact(str) {
  if (typeof str !== 'string') return '';
  return str
    .replace(/"(deviceToken|issuedToken|token)"\s*:\s*"[^"]*"/gi, '"$1":"[REDACTED]"')
    .replace(/\b[a-f0-9]{64}\b/gi, '[REDACTED_HEX64]');
}

function log(level, msg, meta = null) {
  const ts = new Date().toISOString();
  const extra = meta ? ' ' + redact(JSON.stringify(meta)) : '';
  process.stdout.write(`[${ts}] [${level}] ${redact(msg)}${extra}\n`);
}

// ─── Persistent Pairing State (SHA-256 token hashes + pair map + offline queue)

function defaultState() {
  return {
    version: 1,
    // deviceId -> { role: 'bridge'|'phone', tokenHash: string, createdAt: number, lastSeenAt: number, name?: string }
    devices: {},
    // pairId -> { pairId, bridgeId, phoneId, pairedAt, offlineQueue: Array<string> }
    pairs: {},
  };
}

function loadState() {
  try {
    if (fs.existsSync(STATE_FILE)) {
      const raw = JSON.parse(fs.readFileSync(STATE_FILE, 'utf-8'));
      return {
        version: 1,
        devices: raw.devices || {},
        pairs: raw.pairs || {},
      };
    }
  } catch (err) {
    log('ERROR', `Failed to load state file, starting clean: ${err.message}`);
  }
  return defaultState();
}

const state = loadState();
let saveTimer = null;

function saveStateNow() {
  try {
    const dir = path.dirname(STATE_FILE);
    fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
    const tmp = `${STATE_FILE}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(state, null, 2), { mode: 0o600 });
    fs.renameSync(tmp, STATE_FILE);
  } catch (err) {
    log('ERROR', `Failed to persist state: ${err.message}`);
  }
}

function scheduleSave() {
  if (saveTimer) return;
  saveTimer = setTimeout(() => {
    saveTimer = null;
    saveStateNow();
  }, 300);
  saveTimer.unref?.();
}

// ─── Crypto Helpers ──────────────────────────────────────────────────────────

function sha256Hex(input) {
  return crypto.createHash('sha256').update(String(input), 'utf-8').digest('hex');
}

function safeEqualHex(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string' || a.length !== b.length) return false;
  try {
    return crypto.timingSafeEqual(Buffer.from(a, 'hex'), Buffer.from(b, 'hex'));
  } catch {
    return false;
  }
}

function generateDeviceToken() {
  return crypto.randomBytes(32).toString('hex'); // 32 random bytes = 64 hex chars
}

function generatePairingCode(activeCodes) {
  for (let i = 0; i < 20; i++) {
    const num = crypto.randomInt(100000, 1000000);
    const code = String(num);
    if (!activeCodes.has(code)) return code;
  }
  return String(crypto.randomInt(100000, 1000000));
}

function isValidUuidLike(id) {
  return typeof id === 'string' && /^[a-zA-Z0-9_-]{8,64}$/.test(id);
}

// ─── Runtime Maps ────────────────────────────────────────────────────────────

// code -> { code, bridgeId, expiresAt, used }
const pairingCodes = new Map();
// deviceId -> active WebSocket context
const activeSockets = new Map();
// ip -> Array<timestampMs>
const ipConnectHistory = new Map();
// ip -> { count, lockedUntil }
const ipPairFailures = new Map();

function isLoopback(addr) {
  return addr === '127.0.0.1' || addr === '::1' || addr === '::ffff:127.0.0.1';
}

function extractClientIp(req) {
  const remote = req.socket?.remoteAddress || 'unknown';
  if (isLoopback(remote)) {
    const cfIp = req.headers['cf-connecting-ip'];
    if (typeof cfIp === 'string' && cfIp.trim()) return cfIp.trim();
    const xff = req.headers['x-forwarded-for'];
    if (typeof xff === 'string' && xff.trim()) return xff.split(',')[0].trim();
    const realIp = req.headers['x-real-ip'];
    if (typeof realIp === 'string' && realIp.trim()) return realIp.trim();
  }
  return remote;
}

function checkRateLimit(ip) {
  const now = Date.now();
  const windowStart = now - 60_000;
  const list = (ipConnectHistory.get(ip) || []).filter((t) => t > windowStart);
  if (list.length >= MAX_CONN_PER_MIN_PER_IP) {
    ipConnectHistory.set(ip, list);
    return false;
  }
  list.push(now);
  ipConnectHistory.set(ip, list);
  return true;
}

function findPairByDevice(deviceId) {
  for (const pair of Object.values(state.pairs)) {
    if (pair.bridgeId === deviceId || pair.phoneId === deviceId) {
      return pair;
    }
  }
  return null;
}

function removePairsForDevice(deviceId) {
  for (const [pairId, pair] of Object.entries(state.pairs)) {
    if (pair.bridgeId === deviceId || pair.phoneId === deviceId) {
      delete state.pairs[pairId];
    }
  }
}

function makeEnvelope(type, payload = {}, reqId = crypto.randomUUID()) {
  return JSON.stringify({
    v: PROTOCOL_VERSION,
    type,
    reqId,
    ts: Date.now(),
    payload,
  });
}

function sendJson(ws, type, payload = {}, reqId = crypto.randomUUID()) {
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(makeEnvelope(type, payload, reqId));
  }
}

function sendRaw(ws, rawText) {
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(rawText);
  }
}

function notifyPeerPresence(deviceId, role, online) {
  const pair = findPairByDevice(deviceId);
  if (!pair) return;
  const peerId = role === 'bridge' ? pair.phoneId : pair.bridgeId;
  const peerCtx = activeSockets.get(peerId);
  if (peerCtx && peerCtx.ws.readyState === WebSocket.OPEN) {
    sendJson(peerCtx.ws, 'presence', {
      peer: role,
      peerDeviceId: deviceId,
      online,
      ts: Date.now(),
    });
  }
}

function pruneUnpairedBridgesIfNeeded() {
  const pairedBridgeIds = new Set(Object.values(state.pairs).map((p) => p.bridgeId));
  const unpaired = Object.entries(state.devices)
    .filter(([, d]) => d.role === 'bridge' && !pairedBridgeIds.has(d.deviceId))
    .sort((a, b) => (a[1].lastSeenAt || 0) - (b[1].lastSeenAt || 0));

  while (unpaired.length >= MAX_UNPAIRED_BRIDGES) {
    const [oldestId] = unpaired.shift();
    if (!activeSockets.has(oldestId)) {
      delete state.devices[oldestId];
    }
  }
}

// ─── HTTP / Native HTTPS + WebSocket Server ──────────────────────────────────

function handleHttpRequest(req, res) {
  if (req.method === 'GET' && (req.url === '/healthz' || req.url === '/')) {
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(
      JSON.stringify({
        ok: true,
        service: 'clawbridge-relay',
        v: PROTOCOL_VERSION,
        tls: Boolean(TLS_CERT_FILE && TLS_KEY_FILE),
        uptimeSec: Math.floor(process.uptime()),
      })
    );
    return;
  }
  res.writeHead(404, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify({ ok: false, error: 'not_found' }));
}

const useNativeTls = Boolean(TLS_CERT_FILE && TLS_KEY_FILE);
const server = useNativeTls
  ? https.createServer(
      {
        cert: fs.readFileSync(TLS_CERT_FILE),
        key: fs.readFileSync(TLS_KEY_FILE),
        minVersion: 'TLSv1.2',
      },
      handleHttpRequest
    )
  : http.createServer(handleHttpRequest);

const wss = new WebSocketServer({
  noServer: true,
  maxPayload: MAX_FRAME_BYTES,
});

server.on('upgrade', (req, socket, head) => {
  const ip = extractClientIp(req);
  if (!checkRateLimit(ip)) {
    log('WARN', 'Connection rate limit exceeded', { ip });
    socket.write('HTTP/1.1 429 Too Many Requests\r\nConnection: close\r\n\r\n');
    socket.destroy();
    return;
  }

  wss.handleUpgrade(req, socket, head, (ws) => {
    wss.emit('connection', ws, req, ip);
  });
});

wss.on('connection', (ws, _req, ip) => {
  const ctx = {
    ws,
    ip,
    authenticated: false,
    pendingPairOnly: false,
    role: null,
    deviceId: null,
    lastSeenAt: Date.now(),
    helloTimer: null,
  };

  ctx.helloTimer = setTimeout(() => {
    if (!ctx.authenticated && !ctx.pendingPairOnly) {
      sendJson(ws, 'hello.error', { code: 'auth_timeout', message: 'hello handshake timed out' });
      ws.close(4001, 'auth_timeout');
    }
  }, AUTH_HELLO_TIMEOUT_MS);
  ctx.helloTimer.unref?.();

  ws.on('pong', () => {
    ctx.lastSeenAt = Date.now();
  });

  ws.on('message', (data, isBinary) => {
    ctx.lastSeenAt = Date.now();
    if (isBinary) {
      sendJson(ws, 'protocol.error', {
        code: 'binary_not_supported',
        message: 'Binary frames are disabled; protocol v1 uses UTF-8 JSON envelopes',
      });
      return;
    }

    const rawText = data.toString('utf-8');
    let msg;
    try {
      msg = JSON.parse(rawText);
    } catch {
      sendJson(ws, 'protocol.error', { code: 'invalid_json', message: 'Malformed JSON frame' });
      return;
    }

    if (!msg || typeof msg !== 'object') {
      sendJson(ws, 'protocol.error', { code: 'invalid_envelope', message: 'Envelope must be an object' });
      return;
    }

    const reqId = typeof msg.reqId === 'string' && msg.reqId ? msg.reqId : crypto.randomUUID();

    // Hard requirement: explicit version mismatch error on both ends, never silent
    if (msg.v !== PROTOCOL_VERSION) {
      sendJson(
        ws,
        'protocol.error',
        {
          code: 'version_mismatch',
          message: `Protocol version mismatch: server requires v=${PROTOCOL_VERSION}, received v=${String(msg.v)}`,
          expectedVersion: PROTOCOL_VERSION,
          receivedVersion: msg.v ?? null,
        },
        reqId
      );
      return;
    }

    const type = msg.type;
    const payload = msg.payload && typeof msg.payload === 'object' ? msg.payload : {};

    if (type === 'ping') {
      const pair = ctx.deviceId ? findPairByDevice(ctx.deviceId) : null;
      let peerOnline = false;
      if (pair && ctx.role) {
        const peerId = ctx.role === 'bridge' ? pair.phoneId : pair.bridgeId;
        peerOnline = activeSockets.has(peerId);
      }
      sendJson(ws, 'pong', { clientTs: msg.ts || Date.now(), serverTs: Date.now(), peerOnline }, reqId);
      return;
    }

    if (type === 'hello') {
      handleHello(ctx, payload, reqId);
      return;
    }

    if (!ctx.authenticated && !ctx.pendingPairOnly) {
      sendJson(ws, 'hello.error', { code: 'unauthorized', message: 'Send hello first' }, reqId);
      ws.close(4003, 'unauthorized');
      return;
    }

    if (type === 'pair.code.create') {
      handlePairCodeCreate(ctx, reqId);
      return;
    }

    if (type === 'pair.request') {
      handlePairRequest(ctx, payload, reqId);
      return;
    }

    if (type === 'pair.unpair') {
      handlePairUnpair(ctx, reqId);
      return;
    }

    if (ctx.pendingPairOnly || !ctx.authenticated) {
      sendJson(
        ws,
        'pair.result',
        { success: false, code: 'not_paired', message: 'Device is not paired yet; submit pairing code first' },
        reqId
      );
      return;
    }

    // Strict pair-only forwarding
    handleForward(ctx, type, reqId, rawText);
  });

  ws.on('close', () => {
    if (ctx.helloTimer) clearTimeout(ctx.helloTimer);
    if (ctx.deviceId && activeSockets.get(ctx.deviceId) === ctx) {
      activeSockets.delete(ctx.deviceId);
      log('INFO', 'Device disconnected', { role: ctx.role, deviceId: ctx.deviceId });
      if (ctx.authenticated) {
        notifyPeerPresence(ctx.deviceId, ctx.role, false);
      }
    }
  });

  ws.on('error', (err) => {
    log('WARN', `Socket error: ${err.message}`, { deviceId: ctx.deviceId, ip: ctx.ip });
  });
});

// ─── Handlers ────────────────────────────────────────────────────────────────

function bindActiveSocket(ctx) {
  const prev = activeSockets.get(ctx.deviceId);
  if (prev && prev !== ctx) {
    try {
      prev.ws.close(4000, 'replaced_by_new_session');
    } catch { /* ignore */ }
  }
  activeSockets.set(ctx.deviceId, ctx);
}

function handleHello(ctx, payload, reqId) {
  const role = payload.role;
  const deviceId = payload.deviceId;
  const deviceToken = typeof payload.deviceToken === 'string' ? payload.deviceToken.trim() : '';
  const deviceName = typeof payload.deviceName === 'string' ? payload.deviceName.slice(0, 64) : '';

  if ((role !== 'bridge' && role !== 'phone') || !isValidUuidLike(deviceId)) {
    sendJson(ctx.ws, 'hello.error', { code: 'invalid_hello', message: 'Invalid role or deviceId' }, reqId);
    ctx.ws.close(4002, 'invalid_hello');
    return;
  }

  if (ctx.helloTimer) {
    clearTimeout(ctx.helloTimer);
    ctx.helloTimer = null;
  }

  const stored = state.devices[deviceId];

  if (role === 'bridge') {
    let issuedToken = null;
    if (stored) {
      if (stored.role !== 'bridge') {
        sendJson(ctx.ws, 'hello.error', { code: 'role_conflict', message: 'deviceId registered to another role' }, reqId);
        ctx.ws.close(4003, 'role_conflict');
        return;
      }
      const incomingHash = deviceToken ? sha256Hex(deviceToken) : '';
      if (!incomingHash || !safeEqualHex(incomingHash, stored.tokenHash)) {
        sendJson(ctx.ws, 'hello.error', { code: 'invalid_token', message: 'Invalid bridge deviceToken' }, reqId);
        ctx.ws.close(4003, 'invalid_token');
        return;
      }
      stored.lastSeenAt = Date.now();
      if (deviceName) stored.name = deviceName;
    } else {
      pruneUnpairedBridgesIfNeeded();
      issuedToken = generateDeviceToken();
      state.devices[deviceId] = {
        deviceId,
        role: 'bridge',
        tokenHash: sha256Hex(issuedToken),
        name: deviceName || 'ClawBridge Desktop',
        createdAt: Date.now(),
        lastSeenAt: Date.now(),
      };
      log('INFO', 'Registered new bridge device', { deviceId });
    }

    ctx.role = 'bridge';
    ctx.deviceId = deviceId;
    ctx.authenticated = true;
    ctx.pendingPairOnly = false;
    bindActiveSocket(ctx);
    scheduleSave();

    const pair = findPairByDevice(deviceId);
    const peerOnline = pair ? activeSockets.has(pair.phoneId) : false;

    const ackPayload = {
      role: 'bridge',
      deviceId,
      paired: Boolean(pair),
      pairId: pair?.pairId || null,
      peerDeviceId: pair?.phoneId || null,
      peerOnline,
    };
    if (issuedToken) {
      ackPayload.issuedToken = issuedToken;
    }

    sendJson(ctx.ws, 'hello.ack', ackPayload, reqId);
    log('INFO', 'Bridge online', { deviceId, paired: Boolean(pair), peerOnline });

    if (pair) {
      notifyPeerPresence(deviceId, 'bridge', true);
      sendJson(ctx.ws, 'presence', {
        peer: 'phone',
        peerDeviceId: pair.phoneId,
        online: peerOnline,
        ts: Date.now(),
      });
    }
    return;
  }

  // role === 'phone'
  const pair = findPairByDevice(deviceId);
  const incomingHash = deviceToken ? sha256Hex(deviceToken) : '';
  const validPairedPhone =
    stored &&
    stored.role === 'phone' &&
    incomingHash &&
    safeEqualHex(incomingHash, stored.tokenHash) &&
    Boolean(pair);

  ctx.role = 'phone';
  ctx.deviceId = deviceId;

  if (!validPairedPhone) {
    // Unpaired phone: ONLY allowed to send `pair.request` (no open registration!)
    ctx.authenticated = false;
    ctx.pendingPairOnly = true;
    sendJson(
      ctx.ws,
      'hello.ack',
      {
        role: 'phone',
        deviceId,
        paired: false,
        peerOnline: false,
      },
      reqId
    );
    log('INFO', 'Phone connected in pending-pair mode', { deviceId, ip: ctx.ip });
    return;
  }

  // Authenticated paired phone
  stored.lastSeenAt = Date.now();
  if (deviceName) stored.name = deviceName;
  ctx.authenticated = true;
  ctx.pendingPairOnly = false;
  bindActiveSocket(ctx);

  const bridgeOnline = activeSockets.has(pair.bridgeId);
  sendJson(
    ctx.ws,
    'hello.ack',
    {
      role: 'phone',
      deviceId,
      paired: true,
      pairId: pair.pairId,
      peerDeviceId: pair.bridgeId,
      peerOnline: bridgeOnline,
    },
    reqId
  );
  log('INFO', 'Phone online', { deviceId, pairId: pair.pairId, bridgeOnline, queued: pair.offlineQueue?.length || 0 });

  // Notify both sides of presence
  notifyPeerPresence(deviceId, 'phone', true);
  sendJson(ctx.ws, 'presence', {
    peer: 'bridge',
    peerDeviceId: pair.bridgeId,
    online: bridgeOnline,
    ts: Date.now(),
  });

  // Flush FIFO offline notifications in exact arrival order
  if (Array.isArray(pair.offlineQueue) && pair.offlineQueue.length > 0) {
    const queued = pair.offlineQueue.splice(0, pair.offlineQueue.length);
    for (const rawNotify of queued) {
      sendRaw(ctx.ws, rawNotify);
    }
    log('INFO', 'Flushed offline notifications to phone', { deviceId, count: queued.length });
  }
  scheduleSave();
}

function handlePairCodeCreate(ctx, reqId) {
  if (ctx.role !== 'bridge' || !ctx.authenticated) {
    sendJson(ctx.ws, 'protocol.error', { code: 'forbidden', message: 'Only authenticated bridge can create pairing code' }, reqId);
    return;
  }

  // Invalidate any previous unused codes for this bridge
  for (const [oldCode, entry] of pairingCodes.entries()) {
    if (entry.bridgeId === ctx.deviceId) {
      pairingCodes.delete(oldCode);
    }
  }

  const code = generatePairingCode(pairingCodes);
  const expiresAt = Date.now() + PAIR_CODE_TTL_MS;
  pairingCodes.set(code, {
    code,
    bridgeId: ctx.deviceId,
    expiresAt,
    used: false,
  });

  log('INFO', 'Created 10-minute single-use pairing code for bridge', { bridgeId: ctx.deviceId, expiresAt });
  sendJson(ctx.ws, 'pair.code.created', { code, expiresAt, ttlMs: PAIR_CODE_TTL_MS }, reqId);
}

function recordPairFailure(ip) {
  const now = Date.now();
  const cur = ipPairFailures.get(ip) || { count: 0, lockedUntil: 0 };
  cur.count += 1;
  if (cur.count >= MAX_PAIR_FAILURES_PER_IP) {
    cur.lockedUntil = now + PAIR_LOCKOUT_MS;
    cur.count = 0;
  }
  ipPairFailures.set(ip, cur);
}

function handlePairRequest(ctx, payload, reqId) {
  if (ctx.role !== 'phone') {
    sendJson(ctx.ws, 'pair.result', { success: false, code: 'invalid_role', message: 'Only phone can submit pair.request' }, reqId);
    return;
  }

  const lock = ipPairFailures.get(ctx.ip);
  if (lock && lock.lockedUntil > Date.now()) {
    sendJson(
      ctx.ws,
      'pair.result',
      { success: false, code: 'too_many_attempts', message: '配对尝试过多，请 10 分钟后再试' },
      reqId
    );
    return;
  }

  const code = String(payload.code || '').trim();
  const deviceName = typeof payload.deviceName === 'string' ? payload.deviceName.slice(0, 64) : 'Android Phone';
  if (!/^\d{6}$/.test(code)) {
    recordPairFailure(ctx.ip);
    sendJson(ctx.ws, 'pair.result', { success: false, code: 'invalid_code_format', message: '配对码须为 6 位数字' }, reqId);
    return;
  }

  const entry = pairingCodes.get(code);
  if (!entry || entry.used || Date.now() > entry.expiresAt) {
    if (entry && Date.now() > entry.expiresAt) pairingCodes.delete(code);
    recordPairFailure(ctx.ip);
    sendJson(ctx.ws, 'pair.result', { success: false, code: 'code_expired_or_invalid', message: '配对码无效或已过期' }, reqId);
    return;
  }

  // Single-use: consume immediately
  entry.used = true;
  pairingCodes.delete(code);
  ipPairFailures.delete(ctx.ip);

  const bridgeId = entry.bridgeId;
  const phoneId = ctx.deviceId;

  // Displace any existing pair involving either bridgeId or phoneId
  for (const [oldPairId, oldPair] of Object.entries(state.pairs)) {
    if (oldPair.bridgeId === bridgeId || oldPair.phoneId === phoneId) {
      if (oldPair.phoneId !== phoneId) {
        const oldPhoneCtx = activeSockets.get(oldPair.phoneId);
        if (oldPhoneCtx) {
          sendJson(oldPhoneCtx.ws, 'pair.unpaired', { reason: 'replaced_by_new_pair' });
          oldPhoneCtx.ws.close(4004, 'unpaired');
        }
      }
      delete state.pairs[oldPairId];
    }
  }

  // Issue fresh 32-byte random deviceToken for phone
  const issuedPhoneToken = generateDeviceToken();
  state.devices[phoneId] = {
    deviceId: phoneId,
    role: 'phone',
    tokenHash: sha256Hex(issuedPhoneToken),
    name: deviceName,
    createdAt: Date.now(),
    lastSeenAt: Date.now(),
  };

  const pairId = crypto.randomUUID();
  state.pairs[pairId] = {
    pairId,
    bridgeId,
    phoneId,
    pairedAt: Date.now(),
    offlineQueue: [],
  };

  ctx.authenticated = true;
  ctx.pendingPairOnly = false;
  bindActiveSocket(ctx);
  scheduleSave();

  const bridgeCtx = activeSockets.get(bridgeId);
  const bridgeOnline = Boolean(bridgeCtx && bridgeCtx.ws.readyState === WebSocket.OPEN);

  log('INFO', 'Paired phone with bridge', { pairId, bridgeId, phoneId, bridgeOnline });

  // Reply to phone (includes phone's own issued deviceToken)
  sendJson(
    ctx.ws,
    'pair.result',
    {
      success: true,
      pairId,
      bridgeDeviceId: bridgeId,
      deviceToken: issuedPhoneToken,
      bridgeOnline,
    },
    reqId
  );

  // Notify bridge (never include phone's token)
  if (bridgeOnline) {
    sendJson(
      bridgeCtx.ws,
      'pair.result',
      {
        success: true,
        pairId,
        phoneDeviceId: phoneId,
        deviceName,
        phoneOnline: true,
      },
      reqId
    );
    sendJson(bridgeCtx.ws, 'presence', {
      peer: 'phone',
      peerDeviceId: phoneId,
      online: true,
      ts: Date.now(),
    });
  }

  sendJson(ctx.ws, 'presence', {
    peer: 'bridge',
    peerDeviceId: bridgeId,
    online: bridgeOnline,
    ts: Date.now(),
  });
}

function handlePairUnpair(ctx, reqId) {
  const pair = findPairByDevice(ctx.deviceId);
  if (!pair) {
    sendJson(ctx.ws, 'pair.unpaired', { ok: true }, reqId);
    return;
  }
  const peerId = ctx.role === 'bridge' ? pair.phoneId : pair.bridgeId;
  removePairsForDevice(ctx.deviceId);
  scheduleSave();

  sendJson(ctx.ws, 'pair.unpaired', { ok: true }, reqId);
  const peerCtx = activeSockets.get(peerId);
  if (peerCtx) {
    sendJson(peerCtx.ws, 'pair.unpaired', { reason: 'peer_unpaired' });
    if (peerCtx.role === 'phone') {
      peerCtx.authenticated = false;
      peerCtx.pendingPairOnly = true;
    }
  }
  if (ctx.role === 'phone') {
    ctx.authenticated = false;
    ctx.pendingPairOnly = true;
  }
  log('INFO', 'Pair removed by request', { byRole: ctx.role, deviceId: ctx.deviceId });
}

function handleForward(ctx, type, reqId, rawText) {
  const pair = findPairByDevice(ctx.deviceId);
  if (!pair) {
    sendJson(ctx.ws, 'protocol.error', { code: 'not_paired', message: 'Device is not bound to an active pair' }, reqId);
    return;
  }

  const peerId = ctx.role === 'bridge' ? pair.phoneId : pair.bridgeId;
  const peerCtx = activeSockets.get(peerId);
  const peerOnline = Boolean(peerCtx && peerCtx.ws.readyState === WebSocket.OPEN);

  if (ctx.role === 'phone') {
    if (!peerOnline) {
      // Spec 3.1: when bridge is offline, phone requests immediately get `desktop_offline`
      sendJson(
        ctx.ws,
        'desktop_offline',
        {
          reason: 'bridge_offline',
          requestType: type,
          message: '桌面桥当前不在线',
        },
        reqId
      );
      return;
    }
    sendRaw(peerCtx.ws, rawText);
    return;
  }

  // ctx.role === 'bridge'
  if (peerOnline) {
    sendRaw(peerCtx.ws, rawText);
    return;
  }

  // Spec 3.1: bridge online & phone offline -> buffer `event.notify` FIFO up to 200
  if (type === 'event.notify') {
    if (!Array.isArray(pair.offlineQueue)) pair.offlineQueue = [];
    if (pair.offlineQueue.length >= OFFLINE_NOTIFY_LIMIT) {
      pair.offlineQueue.shift();
    }
    pair.offlineQueue.push(rawText);
    scheduleSave();
    log('INFO', 'Buffered offline event.notify', {
      pairId: pair.pairId,
      queued: pair.offlineQueue.length,
    });
  }
}

// ─── Periodic Heartbeat & Expiry Sweeper ─────────────────────────────────────

const sweepTimer = setInterval(() => {
  const now = Date.now();

  // Sweep expired pairing codes
  for (const [code, entry] of pairingCodes.entries()) {
    if (entry.used || now > entry.expiresAt) {
      pairingCodes.delete(code);
    }
  }

  // Sweep IP rate-limit history
  for (const [ip, list] of ipConnectHistory.entries()) {
    const recent = list.filter((t) => t > now - 60_000);
    if (recent.length === 0) ipConnectHistory.delete(ip);
    else ipConnectHistory.set(ip, recent);
  }

  // Heartbeat check (30s ping, 90s dead cutoff)
  for (const ws of wss.clients) {
    let socketCtx = null;
    for (const c of activeSockets.values()) {
      if (c.ws === ws) {
        socketCtx = c;
        break;
      }
    }
    if (socketCtx && now - socketCtx.lastSeenAt > HEARTBEAT_TIMEOUT_MS) {
      log('WARN', 'Terminating stale connection (>90s no heartbeat)', {
        role: socketCtx.role,
        deviceId: socketCtx.deviceId,
      });
      ws.terminate();
      continue;
    }
    if (ws.readyState === WebSocket.OPEN) {
      try {
        ws.ping();
      } catch { /* ignore */ }
    }
  }
}, HEARTBEAT_PING_MS);
sweepTimer.unref?.();

function shutdown(signal) {
  log('INFO', `Received ${signal}, saving state and shutting down`);
  saveStateNow();
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 2000).unref?.();
}

process.on('SIGINT', () => shutdown('SIGINT'));
process.on('SIGTERM', () => shutdown('SIGTERM'));

server.listen(PORT, HOST, () => {
  const scheme = useNativeTls ? 'https' : 'http';
  log('INFO', `ClawBridge Relay listening on ${scheme}://${HOST}:${PORT} (protocol v=${PROTOCOL_VERSION}, nativeTls=${useNativeTls})`);
});
