/**
 * Transport-Agnostic Protocol v1 Envelope Validator & Dual-Mode Router
 *
 * Shared by both WAN `RelayClient` and LAN `LanServer` so Envelope validation,
 * protocol version checks, and business handler routing are 100% identical.
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
 * Unified single-active-mode router between RelayClient (WAN) and LanServer (LAN).
 * Never connects both simultaneously; never performs silent fallback.
 */
export class BridgeTransportRouter extends EventEmitter {
  constructor({ config, relayClient, lanServer }) {
    super();
    this.config = config;
    this.relayClient = relayClient;
    this.lanServer = lanServer;
    this.peerConnectionMode = '';

    this._bindTransportEvents();
  }

  get mode() {
    return this.config.lanEnabled ? 'lan' : 'wan';
  }

  get peerOnline() {
    return this.config.lanEnabled
      ? Boolean(this.lanServer?.peerOnline)
      : Boolean(this.relayClient?.peerOnline);
  }

  sendEnvelope(type, payload = {}, reqId = crypto.randomUUID()) {
    if (this.config.lanEnabled) {
      return this.lanServer ? this.lanServer.sendEnvelope(type, payload, reqId) : false;
    }
    return this.relayClient ? this.relayClient.sendEnvelope(type, payload, reqId) : false;
  }

  _bindTransportEvents() {
    // WAN Relay events (forwarded only when WAN is the active mode)
    if (this.relayClient) {
      this.relayClient.on('paired', (payload) => {
        if (!this.config.lanEnabled) {
          this.peerConnectionMode = 'wan';
          this.emit('paired', { ...payload, transport: 'wan' });
        }
      });

      this.relayClient.on('authenticated', (info) => {
        if (!this.config.lanEnabled) {
          if (info?.peerOnline) this.peerConnectionMode = 'wan';
          this.emit('authenticated', { ...info, transport: 'wan' });
        }
      });

      this.relayClient.on('presence', (payload) => {
        if (!this.config.lanEnabled) {
          if (payload?.online) this.peerConnectionMode = this.peerConnectionMode || 'wan';
          else this.peerConnectionMode = '';
          this.emit('presence', { ...payload, transport: 'wan' });
        }
      });

      this.relayClient.on('message', (envelope) => {
        if (this.config.lanEnabled) return;
        if (envelope.type === 'client.mode') {
          this.peerConnectionMode = String(envelope.payload?.mode || 'wan').toLowerCase();
          this.relayClient.sendEnvelope('ack', { ok: true, mode: this.peerConnectionMode }, envelope.reqId);
          return;
        }
        this.emit('message', envelope);
      });
    }

    // LAN Direct events (forwarded only when LAN is the active mode)
    if (this.lanServer) {
      this.lanServer.on('paired', (payload) => {
        if (this.config.lanEnabled) {
          this.peerConnectionMode = 'lan';
          this._syncCombinedRuntimeStatus();
          this.emit('paired', { ...payload, transport: 'lan' });
        }
      });

      this.lanServer.on('authenticated', (info) => {
        if (this.config.lanEnabled) {
          this.peerConnectionMode = info?.peerMode || 'lan';
          this._syncCombinedRuntimeStatus();
          this.emit('authenticated', { ...info, transport: 'lan' });
        }
      });

      this.lanServer.on('presence', (payload) => {
        if (this.config.lanEnabled) {
          this.peerConnectionMode = payload?.online ? (payload.peerMode || 'lan') : '';
          this._syncCombinedRuntimeStatus();
          this.emit('presence', { ...payload, transport: 'lan' });
        }
      });

      this.lanServer.on('status.changed', () => {
        this._syncCombinedRuntimeStatus();
      });

      this.lanServer.on('message', (envelope) => {
        if (!this.config.lanEnabled) return;
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

  async startInitial() {
    if (this.config.lanEnabled) {
      this.config.lastConnectionMode = 'lan';
      this.relayClient?.pauseForLanMode();
      const res = await this.lanServer.start();
      this._syncCombinedRuntimeStatus();
      return res;
    } else {
      this.config.lastConnectionMode = 'wan';
      await this.lanServer?.stop();
      this.relayClient?.start();
      return { ok: true, mode: 'wan' };
    }
  }

  async setMode(targetMode) {
    const normalized = String(targetMode || '').trim().toLowerCase() === 'lan' ? 'lan' : 'wan';
    if (normalized === 'lan') {
      const wasWanPeerOnline = Boolean(!this.config.lanEnabled && this.relayClient?.peerOnline);
      this.config.lanEnabled = true;
      this.config.lastConnectionMode = 'lan';
      if (!this.config._ephemeral) {
        saveConfig(this.config);
      }
      this.relayClient?.pauseForLanMode();
      if (wasWanPeerOnline) {
        this.peerConnectionMode = '';
        this.emit('presence', { online: false, transport: 'wan' });
      }
      const startRes = await this.lanServer.start();
      this._syncCombinedRuntimeStatus();
      logger.info('Transport', `Switched connection mode -> LAN (port=${this.lanServer.port}, ok=${startRes.ok})`);
      return {
        ok: startRes.ok,
        mode: 'lan',
        lanRunning: this.lanServer.running,
        lanPort: this.lanServer.port,
        lanIps: this.lanServer.getLanIps(),
        error: startRes.error || null,
      };
    } else {
      const wasLanPeerOnline = Boolean(this.config.lanEnabled && this.lanServer?.peerOnline);
      this.config.lanEnabled = false;
      this.config.lastConnectionMode = 'wan';
      if (!this.config._ephemeral) {
        saveConfig(this.config);
      }
      await this.lanServer.stop();
      if (wasLanPeerOnline) {
        this.peerConnectionMode = '';
        this.emit('presence', { online: false, transport: 'lan' });
      }
      this.relayClient?.resumeFromLanMode();
      logger.info('Transport', 'Switched connection mode -> WAN Relay');
      return {
        ok: true,
        mode: 'wan',
        lanRunning: false,
      };
    }
  }

  async stopAll() {
    await this.lanServer?.stop();
    this.relayClient?.stop();
  }

  _syncCombinedRuntimeStatus() {
    if (this.config._ephemeral) return;
    if (!this.config.lanEnabled) return;
    writeRuntimeStatus({
      pid: process.pid,
      connectionMode: 'lan',
      lanEnabled: true,
      lanRunning: Boolean(this.lanServer?.running),
      lanPort: this.lanServer?.port || this.config.lanPort || 18791,
      lanIps: this.lanServer ? this.lanServer.getLanIps() : [],
      lanBindError: this.lanServer?.bindError || null,
      paired: Boolean(this.lanServer?.paired),
      pairedDeviceName: this.lanServer?.pairedDeviceName || '',
      peerOnline: Boolean(this.lanServer?.peerOnline),
      lastRttMs: this.lanServer?.lastRttMs ?? null,
      pairingCode: this.lanServer?.currentPairingCode || null,
      pairingExpiresAt: this.lanServer?.pairingExpiresAt || null,
      relayUrl: this.config.relayUrl,
      deviceId: this.config.deviceId,
    });
  }
}
