/**
 * Transport-Agnostic Protocol v1 Envelope Validator & Dual-Standby Smart Router
 *
 * Desktop Bridge keeps BOTH channels open simultaneously by default:
 *   1. `LanServer` listening on `ws://0.0.0.0:18791` + UDP `18792` discovery
 *   2. `RelayClient` connected outbound to WAN `relayUrl` (if configured)
 *
 * The mobile phone freely decides whether to connect via LAN Direct or WAN Relay without
 * requiring any manual mode toggle on the PC!
 */

import { EventEmitter } from 'node:events';
import crypto from 'node:crypto';
import { logger } from './logger.mjs';
import { saveConfig, writeRuntimeStatus } from './claw-config.mjs';

export const PROTOCOL_VERSION = 1;

export function serializeEnvelope(type, payload = {}, reqId = crypto.randomUUID()) {
  return JSON.stringify({
    v: PROTOCOL_VERSION,
    type,
    reqId,
    ts: Date.now(),
    payload,
  });
}

export function parseAndValidateEnvelope(rawText) {
  let msg;
  try {
    msg = JSON.parse(rawText);
  } catch {
    return {
      ok: false,
      reason: 'invalid_json',
      errorFrame: {
        type: 'protocol.error',
        reqId: crypto.randomUUID(),
        payload: {
          code: 'invalid_json',
          message: 'Malformed JSON frame',
        },
      },
    };
  }

  if (!msg || typeof msg !== 'object') {
    return {
      ok: false,
      reason: 'invalid_envelope',
      errorFrame: {
        type: 'protocol.error',
        reqId: crypto.randomUUID(),
        payload: {
          code: 'invalid_envelope',
          message: 'Envelope must be a JSON object',
        },
      },
    };
  }

  const reqId = typeof msg.reqId === 'string' && msg.reqId ? msg.reqId : crypto.randomUUID();

  if (msg.v !== PROTOCOL_VERSION) {
    const errMsg = `Protocol version mismatch: bridge expects v=${PROTOCOL_VERSION}, received v=${String(msg.v)}`;
    return {
      ok: false,
      reason: 'version_mismatch',
      receivedVersion: msg.v ?? null,
      errorFrame: {
        type: 'protocol.error',
        reqId,
        payload: {
          code: 'version_mismatch',
          message: errMsg,
          expectedVersion: PROTOCOL_VERSION,
          receivedVersion: msg.v ?? null,
        },
      },
    };
  }

  const type = typeof msg.type === 'string' ? msg.type.trim() : '';
  if (!type) {
    return {
      ok: false,
      reason: 'missing_type',
      errorFrame: {
        type: 'protocol.error',
        reqId,
        payload: {
          code: 'missing_type',
          message: 'Envelope missing message type',
        },
      },
    };
  }

  const payload = msg.payload && typeof msg.payload === 'object' ? msg.payload : {};
  const ts = typeof msg.ts === 'number' ? msg.ts : Date.now();

  return {
    ok: true,
    envelope: {
      v: PROTOCOL_VERSION,
      type,
      reqId,
      ts,
      payload,
    },
  };
}

/**
 * Dual-Standby Router between `LanServer` (LAN Direct) and `RelayClient` (WAN Relay).
 * Both listeners stay open on the PC so the phone can switch between LAN and WAN at will.
 */
export class BridgeTransportRouter extends EventEmitter {
  constructor({ config, relayClient, lanServer }) {
    super();
    this.config = config;
    this.relayClient = relayClient;
    this.lanServer = lanServer;
    this.peerConnectionMode = '';
    // Tracks which channel the phone most recently used ('lan' | 'wan')
    this.preferredOutbound = 'lan';

    this._bindTransportEvents();
  }

  get mode() {
    const lanOn = Boolean(this.lanServer?.running);
    const wanOn = Boolean(this.relayClient && !this.relayClient.manualPaused && !this.relayClient.lanPaused);
    if (lanOn && wanOn) return 'dual';
    if (lanOn) return 'lan';
    return 'wan';
  }

  get peerOnline() {
    return Boolean(this.lanServer?.peerOnline || this.relayClient?.peerOnline);
  }

  /**
   * Routes outbound frames to whichever channel the phone is currently connected on.
   * If both briefly report online during a phone-side switch, prefers the channel that most
   * recently authenticated / sent a frame; if phone is completely offline and `type === 'event.notify'`,
   * queues on available offline queues (deduplicated by `notifyId` on the phone).
   */
  sendEnvelope(type, payload = {}, reqId = crypto.randomUUID()) {
    const lanOnline = Boolean(this.lanServer?.peerOnline);
    const wanOnline = Boolean(this.relayClient?.peerOnline);

    if (lanOnline && !wanOnline) {
      return this.lanServer.sendEnvelope(type, payload, reqId);
    }
    if (wanOnline && !lanOnline) {
      return this.relayClient.sendEnvelope(type, payload, reqId);
    }
    if (lanOnline && wanOnline) {
      if (this.preferredOutbound === 'wan') {
        return this.relayClient.sendEnvelope(type, payload, reqId);
      }
      return this.lanServer.sendEnvelope(type, payload, reqId);
    }

    // Neither channel currently has the phone online -> allow offline notification buffering
    let buffered = false;
    if (this.lanServer?.running) {
      buffered = this.lanServer.sendEnvelope(type, payload, reqId) || buffered;
    }
    if (this.relayClient && this.relayClient.connected) {
      buffered = this.relayClient.sendEnvelope(type, payload, reqId) || buffered;
    }
    return buffered;
  }

  _recomputePeerMode() {
    if (this.lanServer?.peerOnline && !this.relayClient?.peerOnline) {
      this.peerConnectionMode = 'lan';
      this.preferredOutbound = 'lan';
    } else if (this.relayClient?.peerOnline && !this.lanServer?.peerOnline) {
      this.peerConnectionMode = 'wan';
      this.preferredOutbound = 'wan';
    } else if (!this.lanServer?.peerOnline && !this.relayClient?.peerOnline) {
      this.peerConnectionMode = '';
    }
  }

  _bindTransportEvents() {
    // ── WAN Relay Events (Always active whenever RelayClient is connected) ──
    if (this.relayClient) {
      this.relayClient.on('paired', (payload) => {
        this.preferredOutbound = 'wan';
        this.peerConnectionMode = 'wan';
        this._syncCombinedRuntimeStatus();
        this.emit('paired', { ...payload, transport: 'wan' });
      });

      this.relayClient.on('authenticated', (info) => {
        if (info?.peerOnline) {
          this.preferredOutbound = 'wan';
          if (!this.lanServer?.peerOnline) this.peerConnectionMode = 'wan';
        }
        this._syncCombinedRuntimeStatus();
        this.emit('authenticated', { ...info, transport: 'wan' });
      });

      this.relayClient.on('presence', (payload) => {
        if (payload?.online) {
          this.preferredOutbound = 'wan';
          this.peerConnectionMode = 'wan';
        } else {
          this._recomputePeerMode();
        }
        this._syncCombinedRuntimeStatus();
        this.emit('presence', { online: this.peerOnline, transport: 'wan' });
      });

      this.relayClient.on('disconnected', () => {
        this._recomputePeerMode();
        this._syncCombinedRuntimeStatus();
      });

      this.relayClient.on('message', (envelope) => {
        this.preferredOutbound = 'wan';
        this.peerConnectionMode = 'wan';
        if (envelope.type === 'client.mode') {
          this.peerConnectionMode = String(envelope.payload?.mode || 'wan').toLowerCase();
          this.relayClient.sendEnvelope('ack', { ok: true, mode: this.peerConnectionMode }, envelope.reqId);
          this._syncCombinedRuntimeStatus();
          return;
        }
        this.emit('message', envelope);
      });
    }

    // ── LAN Direct Events (Always active whenever LanServer is listening) ──
    if (this.lanServer) {
      this.lanServer.on('paired', (payload) => {
        this.preferredOutbound = 'lan';
        this.peerConnectionMode = 'lan';
        this._syncCombinedRuntimeStatus();
        this.emit('paired', { ...payload, transport: 'lan' });
      });

      this.lanServer.on('authenticated', (info) => {
        this.preferredOutbound = 'lan';
        this.peerConnectionMode = info?.peerMode || 'lan';
        this._syncCombinedRuntimeStatus();
        this.emit('authenticated', { ...info, transport: 'lan' });
      });

      this.lanServer.on('presence', (payload) => {
        if (payload?.online) {
          this.preferredOutbound = 'lan';
          this.peerConnectionMode = payload.peerMode || 'lan';
        } else {
          this._recomputePeerMode();
        }
        this._syncCombinedRuntimeStatus();
        this.emit('presence', { online: this.peerOnline, transport: 'lan' });
      });

      this.lanServer.on('status.changed', () => {
        this._syncCombinedRuntimeStatus();
      });

      this.lanServer.on('message', (envelope) => {
        this.preferredOutbound = 'lan';
        this.peerConnectionMode = 'lan';
        if (envelope.type === 'client.mode') {
          this.peerConnectionMode = String(envelope.payload?.mode || 'lan').toLowerCase();
          this.lanServer.sendEnvelope('ack', { ok: true, mode: this.peerConnectionMode }, envelope.reqId);
          this._syncCombinedRuntimeStatus();
          return;
        }
        this.emit('message', envelope);
      });
    }
  }

  /**
   * Starts BOTH LAN Direct listener (when `lanEnabled !== false`) AND WAN Relay client simultaneously!
   */
  async startInitial() {
    let lanRes = { ok: true };
    if (this.config.lanEnabled !== false && this.lanServer) {
      lanRes = await this.lanServer.start();
    }
    if (this.relayClient && !this.relayClient.manualPaused) {
      this.relayClient.start();
    }
    this._syncCombinedRuntimeStatus();
    return {
      ok: lanRes.ok,
      mode: this.mode,
      lanRunning: Boolean(this.lanServer?.running),
      error: lanRes.error || null,
    };
  }

  async setLanEnabled(enabled) {
    const want = Boolean(enabled);
    this.config.lanEnabled = want;
    if (!this.config._ephemeral) {
      saveConfig(this.config);
    }
    if (want) {
      const startRes = await this.lanServer.start();
      this._syncCombinedRuntimeStatus();
      logger.info('Transport', `LAN Direct listener enabled (port=${this.lanServer.port}, ok=${startRes.ok})`);
      return {
        ok: startRes.ok,
        lanEnabled: true,
        lanRunning: this.lanServer.running,
        lanPort: this.lanServer.port,
        lanIps: this.lanServer.getLanIps(),
        error: startRes.error || null,
      };
    } else {
      await this.lanServer.stop();
      this._recomputePeerMode();
      this._syncCombinedRuntimeStatus();
      logger.info('Transport', 'LAN Direct listener stopped');
      return {
        ok: true,
        lanEnabled: false,
        lanRunning: false,
      };
    }
  }

  /**
   * Supports `'dual'` (both open), `'lan'` (enable LAN without killing WAN unless requested),
   * or `'wan'` (stop LAN listener & ensure WAN running).
   */
  async setMode(targetMode, { exclusive = false } = {}) {
    const normalized = String(targetMode || '').trim().toLowerCase();
    if (normalized === 'dual') {
      this.config.lanEnabled = true;
      this.config.lastConnectionMode = 'dual';
      if (!this.config._ephemeral) saveConfig(this.config);
      const startRes = await this.lanServer.start();
      this.relayClient?.resumeFromLanMode();
      this._syncCombinedRuntimeStatus();
      return {
        ok: startRes.ok,
        mode: this.mode,
        lanRunning: this.lanServer.running,
        lanPort: this.lanServer.port,
        lanIps: this.lanServer.getLanIps(),
        error: startRes.error || null,
      };
    }

    if (normalized === 'lan') {
      this.config.lanEnabled = true;
      this.config.lastConnectionMode = exclusive ? 'lan' : 'dual';
      if (!this.config._ephemeral) saveConfig(this.config);
      if (exclusive) {
        this.relayClient?.pauseForLanMode();
      }
      const startRes = await this.lanServer.start();
      this._syncCombinedRuntimeStatus();
      logger.info('Transport', `LAN Direct listener started (port=${this.lanServer.port}, ok=${startRes.ok})`);
      return {
        ok: startRes.ok,
        mode: this.mode,
        lanRunning: this.lanServer.running,
        lanPort: this.lanServer.port,
        lanIps: this.lanServer.getLanIps(),
        error: startRes.error || null,
      };
    }

    // normalized === 'wan' -> stop LAN listener, keep/resume WAN Relay
    this.config.lanEnabled = false;
    this.config.lastConnectionMode = 'wan';
    if (!this.config._ephemeral) saveConfig(this.config);
    await this.lanServer.stop();
    this._recomputePeerMode();
    this.relayClient?.resumeFromLanMode();
    this._syncCombinedRuntimeStatus();
    logger.info('Transport', 'Stopped LAN listener; WAN Relay active');
    return {
      ok: true,
      mode: 'wan',
      lanRunning: false,
    };
  }

  async stopAll() {
    await this.lanServer?.stop();
    this.relayClient?.stop();
  }

  _syncCombinedRuntimeStatus() {
    if (this.config._ephemeral) return;
    writeRuntimeStatus({
      pid: process.pid,
      connectionMode: this.mode,
      activePhoneChannel: this.peerConnectionMode || null,
      // LAN Direct state
      lanEnabled: Boolean(this.config.lanEnabled),
      lanRunning: Boolean(this.lanServer?.running),
      lanPort: this.lanServer?.port || this.config.lanPort || 18791,
      lanIps: this.lanServer ? this.lanServer.getLanIps() : [],
      lanBindError: this.lanServer?.bindError || null,
      lanPaired: Boolean(this.lanServer?.paired),
      lanPairedDeviceName: this.lanServer?.pairedDeviceName || '',
      lanPeerOnline: Boolean(this.lanServer?.peerOnline),
      lanLastRttMs: this.lanServer?.lastRttMs ?? null,
      lanPairingCode: this.lanServer?.currentPairingCode || null,
      // WAN Relay state
      relayUrl: this.config.relayUrl,
      deviceId: this.config.deviceId,
      manualPaused: Boolean(this.relayClient?.manualPaused),
      connected: Boolean(this.relayClient?.connected),
      authenticated: Boolean(this.relayClient?.authenticated),
      paired: Boolean(this.relayClient?.paired),
      pairedDeviceName: this.relayClient?.pairedDeviceName || '',
      peerOnline: Boolean(this.relayClient?.peerOnline),
      anyPeerOnline: this.peerOnline,
      lastRttMs: this.relayClient?.lastRttMs ?? null,
      pairingCode: this.relayClient?.currentPairingCode || null,
      pairingExpiresAt: this.relayClient?.pairingExpiresAt || null,
    });
  }
}
