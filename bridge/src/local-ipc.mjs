/**
 * Local Loopback Control Server for ClawBridgeDesktop.exe GUI
 *
 * Strictly listens on 127.0.0.1:18790 and authenticates requests using a random
 * per-user secret written to %APPDATA%\clawbridge\ipc.token.
 * Never exposes deviceToken or OAuth tokens over any response!
 */

import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { spawn } from 'node:child_process';
import { getAppDataDir, getRecentLogs, logger } from './logger.mjs';

export const LOCAL_IPC_PORT = 18790;

export function getIpcTokenPath() {
  return path.join(getAppDataDir(), 'ipc.token');
}

function loadOrCreateIpcToken() {
  const p = getIpcTokenPath();
  fs.mkdirSync(path.dirname(p), { recursive: true });
  try {
    if (fs.existsSync(p)) {
      const existing = fs.readFileSync(p, 'utf-8').trim();
      if (existing.length >= 32) return existing;
    }
  } catch { /* ignore */ }
  const fresh = crypto.randomBytes(24).toString('hex');
  fs.writeFileSync(p, fresh, { encoding: 'utf-8', mode: 0o600 });
  return fresh;
}

function readJsonBody(req) {
  return new Promise((resolve) => {
    let raw = '';
    req.on('data', (c) => {
      if (raw.length < 65536) raw += c;
    });
    req.on('end', () => {
      try {
        resolve(raw ? JSON.parse(raw) : {});
      } catch {
        resolve({});
      }
    });
    req.on('error', () => resolve({}));
  });
}

export function startLocalControlServer({ relay, quotaService, codexClient, sessionService, notificationEngine, onShutdown }) {
  const ipcToken = loadOrCreateIpcToken();

  const server = http.createServer(async (req, res) => {
    const send = (code, obj) => {
      res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify(obj));
    };

    // Enforce local IPC token for all mutating & status endpoints
    const provided = req.headers['x-clawbridge-token'];
    if (provided !== ipcToken) {
      return send(403, { ok: false, error: 'forbidden' });
    }

    const url = (req.url || '/').split('?')[0];

    try {
      if (req.method === 'GET' && url === '/api/status') {
        const q = quotaService.cached || null;
        return send(200, {
          ok: true,
          pid: process.pid,
          uptimeSec: Math.floor(process.uptime()),
          manualPaused: relay.manualPaused,
          running: relay.running,
          connected: relay.connected,
          authenticated: relay.authenticated,
          paired: relay.paired,
          pairedDeviceName: relay.pairedDeviceName || '',
          peerOnline: relay.peerOnline,
          lastRttMs: relay.lastRttMs,
          relayUrl: relay.config.relayUrl,
          trustSelfSigned: Boolean(relay.config.trustSelfSigned),
          deviceIdShort: (relay.config.deviceId || '').slice(0, 12),
          pairingCode: relay.currentPairingCode || '',
          pairingExpiresAt: relay.pairingExpiresAt || 0,
          cdpAvailable: Boolean(sessionService.cdpStatus?.available),
          cdpNote: sessionService.cdpStatus?.note || '',
          quota: q
            ? {
                planName: q.planName,
                tierCode: q.tierCode,
                fetchedAt: q.fetchedAt,
                gemini5hRem: q.gemini?.window5h?.remainingPercent ?? null,
                gemini7dRem: q.gemini?.window7d?.remainingPercent ?? null,
                claude5hRem: q.claudeGpt?.window5h?.remainingPercent ?? null,
                claude7dRem: q.claudeGpt?.window7d?.remainingPercent ?? null,
              }
            : null,
          codexQuota: codexClient?.cachedRateLimits
            ? {
                planName: codexClient.cachedRateLimits.planName,
                window5hRem: codexClient.cachedRateLimits.window5h?.remainingPercent ?? null,
                window7dRem: codexClient.cachedRateLimits.window7d?.remainingPercent ?? null,
              }
            : null,
          logs: getRecentLogs().slice(-50),
        });
      }

      if (req.method === 'POST' && url === '/api/connect') {
        relay.connectManually();
        return send(200, { ok: true });
      }

      if (req.method === 'POST' && url === '/api/disconnect') {
        relay.disconnectManually();
        return send(200, { ok: true });
      }

      if (req.method === 'POST' && url === '/api/reconnect') {
        relay.connectManually();
        relay.forceReconnectNow('desktop_ui_reconnect');
        return send(200, { ok: true });
      }

      if (req.method === 'POST' && url === '/api/new-pair-code') {
        if (relay.manualPaused || !relay.connected) {
          relay.connectManually();
        }
        const ok = relay.requestNewPairingCode();
        return send(200, { ok });
      }

      if (req.method === 'POST' && url === '/api/unpair') {
        const ok = relay.unpairAndRequestNewCode();
        return send(200, { ok });
      }

      if (req.method === 'POST' && url === '/api/relay-config') {
        const body = await readJsonBody(req);
        if (body.relayUrl) {
          relay.updateRelayConfigLive(body.relayUrl, body.trustSelfSigned);
        }
        return send(200, { ok: true });
      }

      if (req.method === 'POST' && url === '/api/refresh-quota') {
        await quotaService.refreshNow().catch(() => {});
        await sessionService._checkCdpStatus().catch(() => {});
        return send(200, { ok: true });
      }

      if (req.method === 'POST' && url === '/api/test-notify') {
        const body = await readJsonBody(req);
        const kind = String(body.kind || 'task_done');
        if (notificationEngine) {
          notificationEngine.triggerTestNotification(kind);
        }
        return send(200, { ok: true, kind });
      }

      if (req.method === 'POST' && url === '/api/launch-antigravity-cdp') {
        const psScript = path.resolve(import.meta.dirname, '..', 'scripts', 'start-antigravity-cdp.ps1');
        const child = spawn(
          'powershell.exe',
          ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', psScript, '-ForceRestart'],
          { detached: true, stdio: 'ignore', windowsHide: true }
        );
        child.unref();
        setTimeout(() => sessionService._checkCdpStatus().catch(() => {}), 4000).unref?.();
        return send(200, { ok: true });
      }

      if (req.method === 'POST' && url === '/api/shutdown') {
        send(200, { ok: true });
        setTimeout(() => onShutdown('DESKTOP_UI_EXIT'), 150);
        return;
      }

      return send(404, { ok: false, error: 'not_found' });
    } catch (err) {
      return send(500, { ok: false, error: err.message });
    }
  });

  server.on('error', (err) => {
    if (err.code === 'EADDRINUSE') {
      logger.warn('LocalIPC', `Another ClawBridge daemon is already listening on 127.0.0.1:${LOCAL_IPC_PORT}; exiting duplicate instance`);
      process.exit(0);
    } else {
      logger.error('LocalIPC', `Control server error: ${err.message}`);
    }
  });

  server.listen(LOCAL_IPC_PORT, '127.0.0.1', () => {
    logger.info('LocalIPC', `Desktop GUI control server ready on http://127.0.0.1:${LOCAL_IPC_PORT}`);
  });

  return server;
}
