/**
 * ClawBridge WSS Relay Client
 *
 * Features:
 *   - Protocol v=1 strict envelope validation (explicit error on version mismatch)
 *   - TLS connection to configurable relayUrl
 *   - Exponential backoff reconnect (1s -> 60s cap)
 *   - Instant reconnect on hibernation wake-up (PowerMonitor) + 20s ping / 8s pong health probe
 *   - Automatic 6-digit pairing code request & rotation when unpaired
 */

import { EventEmitter } from 'node:events';
import crypto from 'node:crypto';
import os from 'node:os';
import WebSocket from 'ws';
import fs from 'node:fs';
import { logger } from './logger.mjs';
import { resilientLookup } from './dns-resolver.mjs';
import {
  getPairingFilePath,
  isIpv4RelayUrl,
  isPlaceholderRelayUrl,
  normalizeRelayUrlInput,
  saveConfig,
  writePairingCodeInfo,
  writeRuntimeStatus,
} from './claw-config.mjs';
import { PROTOCOL_VERSION, parseAndValidateEnvelope, serializeEnvelope } from './transport-router.mjs';

export { PROTOCOL_VERSION };

const PING_INTERVAL_MS = 20_000;
const PONG_TIMEOUT_MS = 8_000;
const BACKOFF_INITIAL_MS = 1_000;
const BACKOFF_MAX_MS = 60_000;

export class RelayClient extends EventEmitter {
  constructor(config, powerMonitor) {
    super();
    this.config = config;
    this.powerMonitor = powerMonitor;

    this.ws = null;
    this.running = false;
    this.manualPaused = false;
    this.lanPaused = false;
    this.connected = false;
    this.authenticated = false;
    this.paired = false;
    this.peerOnline = false;
    this.pairedDeviceName = this._loadSavedPairedName();
    this.lastRttMs = null;
    this.currentPairingCode = null;
    this.pairingExpiresAt = 0;

    this.backoffMs = BACKOFF_INITIAL_MS;
    this.reconnectTimer = null;
    this.pingTimer = null;
    this.pongDeadlineTimer = null;
    this.pairRenewTimer = null;

    if (this.powerMonitor) {
      this.powerMonitor.on('resume', ({ source }) => {
        if (!this.manualPaused && !this.lanPaused) {
          this.forceReconnectNow(`wake:${source}`);
        }
      });
    }
  }

  _loadSavedPairedName() {
    try {
      const p = getPairingFilePath();
      if (fs.existsSync(p)) {
        const d = JSON.parse(fs.readFileSync(p, 'utf-8'));
        if (d?.paired && d?.deviceName) return String(d.deviceName);
      }
    } catch { /* ignore */ }
    return '';
  }

  start() {
    this.lanPaused = false;
    this.manualPaused = false;
    if (this.running) {
      if (!this.ws && !this.reconnectTimer) this._connect();
      return;
    }
    this.running = true;
    this.backoffMs = BACKOFF_INITIAL_MS;
    this._connect();
  }

  pauseForLanMode() {
    this.lanPaused = true;
    this.running = false;
    this._clearTimers();
    if (this.ws) {
      const old = this.ws;
      this.ws = null;
      try {
        old.close(1000, 'switched_to_lan');
      } catch { /* ignore */ }
    }
    this.connected = false;
    this.authenticated = false;
    this.peerOnline = false;
  }

  resumeFromLanMode() {
    this.lanPaused = false;
    if (!this.manualPaused) {
      this.start();
    } else {
      this._syncStatus();
    }
  }

  connectManually() {
    logger.info('RelayClient', 'Manual CONNECT triggered from Desktop UI');
    this.manualPaused = false;
    this.running = true;
    this.backoffMs = BACKOFF_INITIAL_MS;
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    if (!this.ws) {
      this._connect();
    }
    this._syncStatus();
  }

  disconnectManually() {
    logger.info('RelayClient', 'Manual DISCONNECT triggered from Desktop UI');
    this.manualPaused = true;
    this.running = false;
    this._clearTimers();
    if (this.ws) {
      const old = this.ws;
      this.ws = null;
      try {
        old.close(1000, 'manual_disconnect');
      } catch { /* ignore */ }
    }
    this.connected = false;
    this.authenticated = false;
    this.peerOnline = false;
    this._syncStatus();
    this.emit('disconnected', { code: 1000, reason: 'manual_disconnect' });
  }

  updateRelayConfigLive(newRelayUrl, trustSelfSigned) {
    const normalized = normalizeRelayUrlInput(newRelayUrl);
    if (!normalized) return;
    const urlChanged = normalized !== this.config.relayUrl;
    this.config.relayUrl = normalized;
    if (typeof trustSelfSigned === 'boolean') {
      this.config.trustSelfSigned = trustSelfSigned;
    } else if (isIpv4RelayUrl(normalized)) {
      // Auto-enable self-signed TLS compatibility when user configures a bare VPS IPv4 address
      this.config.trustSelfSigned = true;
    }
    if (urlChanged) {
      logger.info('RelayClient', `Relay URL updated via Desktop UI -> ${normalized}; resetting token for re-pairing`);
      this.config.deviceId = crypto.randomUUID();
      this.config.deviceToken = '';
      this.config.lastBoundRelayUrl = normalized;
      this.paired = false;
      this.pairedDeviceName = '';
      this.currentPairingCode = null;
    }
    if (!this.config._ephemeral) {
      saveConfig(this.config);
    }
    if (!this.lanPaused && !this.config.lanEnabled) {
      this.manualPaused = false;
      this.running = true;
      this.forceReconnectNow('relay_config_changed');
    }
  }

  unpairAndRequestNewCode() {
    if (this.ws && this.ws.readyState === WebSocket.OPEN && this.authenticated) {
      this.sendEnvelope('pair.unpair', {});
      this.paired = false;
      this.peerOnline = false;
      this.pairedDeviceName = '';
      setTimeout(() => {
        if (this.authenticated && !this.paired) {
          this.requestNewPairingCode();
        }
      }, 300);
      return true;
    }
    return false;
  }

  stop() {
    this.running = false;
    this._clearTimers();
    if (this.ws) {
      try {
        this.ws.close(1000, 'bridge_shutdown');
      } catch { /* ignore */ }
      this.ws = null;
    }
    this.connected = false;
    this.authenticated = false;
    this._syncStatus();
  }

  /**
   * Immediately tear down any stale socket, reset backoff to 1s, and reconnect.
   * Invoked on hibernation wake-up so Bridge recovers well within 1 minute.
   */
  forceReconnectNow(reason = 'manual') {
    if (!this.running) return;
    logger.info('RelayClient', `Forcing immediate reconnect (${reason})`);
    this.backoffMs = BACKOFF_INITIAL_MS;
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this._clearSocketTimers();
    if (this.ws) {
      const old = this.ws;
      this.ws = null;
      try {
        old.terminate();
      } catch { /* ignore */ }
    }
    this.connected = false;
    this.authenticated = false;
    this._syncStatus();
    // Small 300ms settle delay on wake so OS network stack can rebind routes, then 1s->2s->4s retry
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      this._connect();
    }, 300);
    this.reconnectTimer.unref?.();
  }

  requestNewPairingCode() {
    if (!this.authenticated || !this.ws || this.ws.readyState !== WebSocket.OPEN) {
      logger.warn('RelayClient', 'Cannot request pairing code while disconnected');
      return false;
    }
    this.sendEnvelope('pair.code.create', {});
    return true;
  }

  sendEnvelope(type, payload = {}, reqId = crypto.randomUUID()) {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      return false;
    }
    this.ws.send(serializeEnvelope(type, payload, reqId));
    return true;
  }

  _connect() {
    if (!this.running || this.ws) return;

    const url = normalizeRelayUrlInput(this.config.relayUrl);
    if (isPlaceholderRelayUrl(url)) {
      this.connected = false;
      this.authenticated = false;
      this.peerOnline = false;
      this._syncStatus();
      return;
    }
    if (!url || (!url.startsWith('wss://') && !url.startsWith('ws://'))) {
      logger.error('RelayClient', `Invalid relayUrl "${url}" (must start with wss://)`);
      this._scheduleReconnect();
      return;
    }

    if (url.startsWith('ws://') && !url.includes('127.0.0.1') && !url.includes('localhost')) {
      logger.error('RelayClient', 'Security policy blocks plaintext ws:// to non-loopback hosts; use wss://');
      this._scheduleReconnect();
      return;
    }

    logger.info('RelayClient', `Connecting to ${url} ...`);
    let ws;
    try {
      ws = new WebSocket(url, {
        handshakeTimeout: 10_000,
        rejectUnauthorized: !this.config.trustSelfSigned,
        lookup: resilientLookup,
      });
    } catch (err) {
      logger.error('RelayClient', `WebSocket init failed: ${err.message}`);
      this._scheduleReconnect();
      return;
    }

    this.ws = ws;

    ws.on('open', () => {
      if (this.ws !== ws) return;
      this.connected = true;
      this.backoffMs = BACKOFF_INITIAL_MS;
      logger.info('RelayClient', 'WSS socket open; sending hello');

      this.sendEnvelope('hello', {
        role: 'bridge',
        deviceId: this.config.deviceId,
        deviceToken: this.config.deviceToken || '',
        deviceName: os.hostname() || 'RenderLaptop',
        appVersion: '1.0.0',
      });

      this._startPingLoop();
      this._syncStatus();
    });

    ws.on('pong', () => {
      if (this.ws !== ws) return;
      this._clearPongDeadline();
    });

    ws.on('message', (data, isBinary) => {
      if (this.ws !== ws || isBinary) return;
      this._handleFrame(data.toString('utf-8'));
    });

    ws.on('close', (code, reasonBuf) => {
      if (this.ws !== ws) return;
      const reason = reasonBuf ? reasonBuf.toString('utf-8') : '';
      logger.warn('RelayClient', `Disconnected from relay (code=${code}, reason=${reason || 'none'})`);
      this.ws = null;
      this.connected = false;
      this.authenticated = false;
      this.peerOnline = false;
      this._clearSocketTimers();
      this._syncStatus();
      this.emit('disconnected', { code, reason });
      this._scheduleReconnect();
    });

    ws.on('error', (err) => {
      if (this.ws !== ws) return;
      logger.warn('RelayClient', `Socket error: ${err.message}`);
    });
  }

  _handleFrame(rawText) {
    const parsed = parseAndValidateEnvelope(rawText);
    if (!parsed.ok) {
      if (parsed.reason === 'version_mismatch') {
        logger.error('RelayClient', parsed.errorFrame.payload.message);
        this.sendEnvelope(
          parsed.errorFrame.type,
          parsed.errorFrame.payload,
          parsed.errorFrame.reqId
        );
        this.emit('protocol.error', {
          expectedVersion: PROTOCOL_VERSION,
          receivedVersion: parsed.receivedVersion,
        });
      } else {
        logger.warn('RelayClient', `Ignored invalid frame (${parsed.reason})`);
      }
      return;
    }

    const { type, reqId, ts, payload } = parsed.envelope;

    if (type === 'pong') {
      this._clearPongDeadline();
      if (typeof payload.clientTs === 'number') {
        this.lastRttMs = Math.max(0, Date.now() - payload.clientTs);
      }
      if (typeof payload.peerOnline === 'boolean') {
        this.peerOnline = payload.peerOnline;
      }
      this._syncStatus();
      return;
    }

    if (type === 'hello.ack') {
      this.authenticated = true;
      this.paired = Boolean(payload.paired);
      this.peerOnline = Boolean(payload.peerOnline);

      if (typeof payload.issuedToken === 'string' && payload.issuedToken) {
        this.config.deviceToken = payload.issuedToken;
        this.config.lastBoundRelayUrl = this.config.relayUrl;
        if (!this.config._ephemeral) {
          saveConfig(this.config);
        }
        logger.info('RelayClient', 'Saved newly issued deviceToken to local config (redacted)');
      }

      logger.info('RelayClient', 'Authenticated with relay', {
        paired: this.paired,
        peerOnline: this.peerOnline,
      });

      if (!this.paired) {
        this.requestNewPairingCode();
      } else {
        this.currentPairingCode = null;
        this.pairingExpiresAt = 0;
      }

      this._syncStatus();
      this.emit('authenticated', { paired: this.paired, peerOnline: this.peerOnline });
      return;
    }

    if (type === 'hello.error') {
      logger.error('RelayClient', `Hello rejected: ${payload.code} - ${payload.message}`);
      // If relay wiped state or user pointed to a fresh relay where deviceId had another token, rotate deviceId & re-enroll
      if (payload.code === 'invalid_token') {
        logger.warn('RelayClient', 'Token rejected by relay; rotating deviceId for clean re-pairing');
        this.config.deviceId = crypto.randomUUID();
        this.config.deviceToken = '';
        if (!this.config._ephemeral) {
          saveConfig(this.config);
        }
      }
      return;
    }

    if (type === 'pair.code.created') {
      this.currentPairingCode = payload.code;
      this.pairingExpiresAt = payload.expiresAt;
      if (!this.config._ephemeral) {
        writePairingCodeInfo({
          code: payload.code,
          expiresAt: payload.expiresAt,
          expiresAtIso: new Date(payload.expiresAt).toISOString(),
          relayUrl: this.config.relayUrl,
          paired: false,
        });
      }
      logger.info(
        'Pairing',
        `=== 6位配对码: ${payload.code} (10分钟内有效，至 ${new Date(payload.expiresAt).toLocaleTimeString()}) ===`
      );
      this._schedulePairCodeRenewal(payload.expiresAt);
      this._syncStatus();
      this.emit('pair.code', { code: payload.code, expiresAt: payload.expiresAt });
      return;
    }

    if (type === 'pair.result') {
      if (payload.success) {
        this.paired = true;
        this.peerOnline = true;
        this.pairedDeviceName = payload.deviceName || payload.phoneDeviceId || '已绑定手机';
        this.currentPairingCode = null;
        this.pairingExpiresAt = 0;
        if (this.pairRenewTimer) {
          clearTimeout(this.pairRenewTimer);
          this.pairRenewTimer = null;
        }
        if (!this.config._ephemeral) {
          writePairingCodeInfo({
            code: null,
            paired: true,
            pairId: payload.pairId,
            phoneDeviceId: payload.phoneDeviceId,
            deviceName: payload.deviceName,
            pairedAt: new Date().toISOString(),
            relayUrl: this.config.relayUrl,
          });
        }
        logger.info('Pairing', `Successfully paired with phone "${payload.deviceName || payload.phoneDeviceId}"!`);
        this._syncStatus();
        this.emit('paired', payload);
      }
      return;
    }

    if (type === 'pair.unpaired') {
      this.paired = false;
      this.peerOnline = false;
      logger.warn('Pairing', `Pair unbound (${payload.reason || 'unpaired'}); requesting fresh pairing code`);
      this.requestNewPairingCode();
      this._syncStatus();
      return;
    }

    if (type === 'presence') {
      this.peerOnline = Boolean(payload.online);
      logger.info('Presence', `Phone is now ${this.peerOnline ? 'ONLINE' : 'OFFLINE'}`);
      this._syncStatus();
      this.emit('presence', payload);
      return;
    }

    if (type === 'protocol.error') {
      logger.error('RelayClient', `Relay reported protocol.error: ${payload.code} - ${payload.message}`);
      this.emit('protocol.error', payload);
      return;
    }

    // Dispatch business messages (session.*, quota.*, chat.send, approval.*, quick.command)
    this.emit('message', { type, reqId, ts, payload });
  }

  _startPingLoop() {
    this._clearSocketTimers();
    this.pingTimer = setInterval(() => {
      if (!this.ws || this.ws.readyState !== WebSocket.OPEN) return;
      this._clearPongDeadline();
      this.pongDeadlineTimer = setTimeout(() => {
        logger.warn('RelayClient', `Heartbeat pong timeout (${PONG_TIMEOUT_MS}ms); terminating dead socket to recover`);
        if (this.ws) {
          try {
            this.ws.terminate();
          } catch { /* ignore */ }
        }
      }, PONG_TIMEOUT_MS);
      this.pongDeadlineTimer.unref?.();

      try {
        this.ws.ping();
      } catch { /* ignore */ }
      this.sendEnvelope('ping', {});
    }, PING_INTERVAL_MS);
    this.pingTimer.unref?.();
  }

  _schedulePairCodeRenewal(expiresAt) {
    if (this.pairRenewTimer) clearTimeout(this.pairRenewTimer);
    const waitMs = Math.max(5_000, expiresAt - Date.now() - 5_000);
    this.pairRenewTimer = setTimeout(() => {
      this.pairRenewTimer = null;
      if (this.running && this.authenticated && !this.paired) {
        logger.info('Pairing', 'Previous pairing code expired while still unpaired; generating a fresh 6-digit code');
        this.requestNewPairingCode();
      }
    }, waitMs);
    this.pairRenewTimer.unref?.();
  }

  _clearPongDeadline() {
    if (this.pongDeadlineTimer) {
      clearTimeout(this.pongDeadlineTimer);
      this.pongDeadlineTimer = null;
    }
  }

  _clearSocketTimers() {
    if (this.pingTimer) {
      clearInterval(this.pingTimer);
      this.pingTimer = null;
    }
    this._clearPongDeadline();
    if (this.pairRenewTimer) {
      clearTimeout(this.pairRenewTimer);
      this.pairRenewTimer = null;
    }
  }

  _clearTimers() {
    this._clearSocketTimers();
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
  }

  _scheduleReconnect() {
    if (!this.running || this.reconnectTimer) return;
    const jitter = 0.85 + Math.random() * 0.3;
    const delayMs = Math.round(Math.min(BACKOFF_MAX_MS, this.backoffMs) * jitter);
    this.backoffMs = Math.min(BACKOFF_MAX_MS, this.backoffMs * 2);
    logger.info('RelayClient', `Reconnecting in ${delayMs}ms...`);
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      this._connect();
    }, delayMs);
    this.reconnectTimer.unref?.();
  }

  _syncStatus() {
    if (this.config._ephemeral || this.lanPaused) return;
    writeRuntimeStatus({
      pid: process.pid,
      connectionMode: 'wan',
      lanEnabled: false,
      relayUrl: this.config.relayUrl,
      deviceId: this.config.deviceId,
      manualPaused: this.manualPaused,
      connected: this.connected,
      authenticated: this.authenticated,
      paired: this.paired,
      pairedDeviceName: this.pairedDeviceName,
      peerOnline: this.peerOnline,
      lastRttMs: this.lastRttMs,
      pairingCode: this.currentPairingCode,
      pairingExpiresAt: this.pairingExpiresAt || null,
    });
  }
}
