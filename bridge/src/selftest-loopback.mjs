/**
 * ClawBridge OSS Clean Loopback Smoke Test (Zero external servers, zero real credentials)
 *
 * Starts the OSS Relay on 127.0.0.1 (ephemeral port), connects the OSS Bridge RelayClient
 * using an isolated temp config directory, simulates a mobile client claiming the 6-digit
 * pairing code, and verifies /healthz, authentication, pairing, and heartbeat RTT.
 */

import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';
import WebSocket from 'ws';

const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'clawbridge-oss-selftest-'));
process.env.CLAWBRIDGE_DATA_DIR = path.join(tmpDir, 'bridge-data');

const { PowerMonitor } = await import('./power-monitor.mjs');
const { RelayClient } = await import('./relay-client.mjs');

function getFreePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.listen(0, '127.0.0.1', () => {
      const port = srv.address().port;
      srv.close(() => resolve(port));
    });
    srv.on('error', reject);
  });
}

const port = await getFreePort();
const relayDir = path.resolve(import.meta.dirname, '..', '..', 'relay');
const relayEntry = path.join(relayDir, 'src', 'server.mjs');
const stateFile = path.join(tmpDir, 'relay-state.json');

const relayProc = spawn(process.execPath, [relayEntry], {
  cwd: relayDir,
  env: {
    ...process.env,
    RELAY_HOST: '127.0.0.1',
    RELAY_PORT: String(port),
    RELAY_STATE_FILE: stateFile,
  },
  stdio: ['ignore', 'pipe', 'pipe'],
});

const relayUrl = `ws://127.0.0.1:${port}`;
let bridgeRelay = null;
let phoneWs = null;
let exitCode = 0;

try {
  // 1. Wait for /healthz
  let healthy = false;
  for (let i = 0; i < 30; i++) {
    try {
      const res = await fetch(`http://127.0.0.1:${port}/healthz`);
      if (res.ok) {
        const body = await res.json();
        assert.equal(body.ok, true);
        healthy = true;
        console.log(`[PASS 1/4] Relay /healthz OK on 127.0.0.1:${port}`);
        break;
      }
    } catch { /* retry */ }
    await new Promise((r) => setTimeout(r, 150));
  }
  assert.equal(healthy, true, 'Relay failed to become healthy');

  // 2. Connect Bridge RelayClient and receive 6-digit pairing code
  const pm = new PowerMonitor();
  bridgeRelay = new RelayClient(
    {
      _ephemeral: true,
      relayUrl,
      deviceId: `oss-test-bridge-${crypto.randomUUID().slice(0, 8)}`,
      deviceToken: '',
      lastBoundRelayUrl: relayUrl,
      trustSelfSigned: false,
    },
    pm
  );

  const codePromise = new Promise((res, rej) => {
    const t = setTimeout(() => rej(new Error('Timeout waiting for pair.code')), 10_000);
    bridgeRelay.once('pair.code', (info) => {
      clearTimeout(t);
      res(info);
    });
  });
  bridgeRelay.start();
  const { code } = await codePromise;
  assert.match(code, /^\d{6}$/, 'Pairing code must be 6 digits');
  console.log(`[PASS 2/4] Bridge authenticated & received 6-digit pairing code`);

  // 3. Connect simulated Phone client and claim pairing code
  phoneWs = await new Promise((resolve, reject) => {
    const ws = new WebSocket(relayUrl);
    const q = [];
    const waiters = [];
    ws.on('message', (d) => {
      const m = JSON.parse(d.toString());
      const i = waiters.findIndex((w) => w.pred(m));
      if (i >= 0) {
        const [w] = waiters.splice(i, 1);
        clearTimeout(w.t);
        w.res(m);
      } else q.push(m);
    });
    ws.waitFor = (pred, ms = 10_000) => {
      const i = q.findIndex(pred);
      if (i >= 0) return Promise.resolve(q.splice(i, 1)[0]);
      return new Promise((res, rej) => {
        const t = setTimeout(() => rej(new Error('phone waitFor timeout')), ms);
        waiters.push({ pred, res, t });
      });
    };
    ws.sendReq = (type, payload) => {
      const reqId = crypto.randomUUID();
      ws.send(JSON.stringify({ v: 1, type, reqId, ts: Date.now(), payload }));
      return reqId;
    };
    ws.on('open', () => resolve(ws));
    ws.on('error', reject);
  });

  phoneWs.sendReq('hello', { role: 'phone', deviceId: `oss-test-phone-${crypto.randomUUID().slice(0, 8)}` });
  const helloAck = await phoneWs.waitFor((m) => m.type === 'hello.ack');
  assert.equal(helloAck.payload?.paired, false, 'New phone must start unpaired');

  phoneWs.sendReq('pair.request', { code, deviceName: 'OSS-Loopback-Test-Device' });
  const pairRes = await phoneWs.waitFor((m) => m.type === 'pair.result');
  assert.equal(pairRes.payload?.success, true, 'Pairing must succeed');
  assert.ok(pairRes.payload?.issuedToken || pairRes.payload?.deviceToken, 'Relay must issue phone token after valid pairing code');
  console.log(`[PASS 3/4] Phone paired with Bridge over loopback Relay`);

  // 4. Verify Ping/Pong RTT
  const pingId = phoneWs.sendReq('ping', { clientTs: Date.now() });
  const pong = await phoneWs.waitFor((m) => m.type === 'pong' && m.reqId === pingId);
  assert.equal(pong.payload?.peerOnline, true, 'Bridge peerOnline must be true');
  console.log(`[PASS 4/4] Heartbeat ping/pong verified (peerOnline=true)`);
  console.log('\nALL CLAWBRIDGE-OSS LOOPBACK CHECKS PASSED!');
} catch (err) {
  exitCode = 1;
  console.error('[LOOPBACK TEST FAILED]', err?.stack || err);
} finally {
  try { phoneWs?.close(); } catch {}
  try { bridgeRelay?.stop(); } catch {}
  try { relayProc.kill(); } catch {}
  try { fs.rmSync(tmpDir, { recursive: true, force: true }); } catch {}
  process.exit(exitCode);
}
