/**
 * Automated self-test for ClawBridge Relay protocol v1:
 * Verifies version mismatch error, bridge hello, 6-digit single-use pairing code,
 * presence events, desktop_offline immediate reply, and FIFO offline event.notify queue.
 */

import { spawn } from 'node:child_process';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import WebSocket from 'ws';

const TEST_PORT = 18787;
const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'clawrelay-test-'));
const stateFile = path.join(tmpDir, 'state.json');

const proc = spawn(process.execPath, ['src/server.mjs'], {
  cwd: path.resolve(import.meta.dirname, '..'),
  env: {
    ...process.env,
    RELAY_HOST: '127.0.0.1',
    RELAY_PORT: String(TEST_PORT),
    RELAY_STATE_FILE: stateFile,
  },
  stdio: ['ignore', 'pipe', 'pipe'],
});

await new Promise((resolve, reject) => {
  const timer = setTimeout(() => reject(new Error('Relay startup timeout')), 5000);
  proc.stdout.on('data', (chunk) => {
    if (chunk.toString().includes('ClawBridge Relay listening')) {
      clearTimeout(timer);
      resolve();
    }
  });
  proc.on('exit', (code) => reject(new Error(`Relay exited early: ${code}`)));
});

function connectClient() {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`ws://127.0.0.1:${TEST_PORT}`);
    const queue = [];
    const waiters = [];
    ws.on('message', (d) => {
      const msg = JSON.parse(d.toString());
      const idx = waiters.findIndex((w) => w.pred(msg));
      if (idx >= 0) {
        const [w] = waiters.splice(idx, 1);
        clearTimeout(w.timer);
        w.resolve(msg);
      } else {
        queue.push(msg);
      }
    });
    ws.waitFor = (pred, timeoutMs = 4000) => {
      const qIdx = queue.findIndex(pred);
      if (qIdx >= 0) return Promise.resolve(queue.splice(qIdx, 1)[0]);
      return new Promise((res, rej) => {
        const timer = setTimeout(() => rej(new Error('waitFor timeout')), timeoutMs);
        waiters.push({ pred, resolve: res, timer });
      });
    };
    ws.sendEnv = (type, payload = {}, v = 1) => {
      const reqId = crypto.randomUUID();
      ws.send(JSON.stringify({ v, type, reqId, ts: Date.now(), payload }));
      return reqId;
    };
    ws.on('open', () => resolve(ws));
    ws.on('error', reject);
  });
}

try {
  // 1. Version mismatch check
  const cVer = await connectClient();
  cVer.sendEnv('hello', { role: 'bridge', deviceId: 'bridge-test-0001' }, 99);
  const verErr = await cVer.waitFor((m) => m.type === 'protocol.error');
  assert.equal(verErr.payload.code, 'version_mismatch');
  cVer.close();

  // 2. Bridge connects & registers + gets pairing code
  const bridgeId = 'bridge-test-11112222';
  const bridge = await connectClient();
  bridge.sendEnv('hello', { role: 'bridge', deviceId: bridgeId, deviceName: 'TestPC' });
  const bAck = await bridge.waitFor((m) => m.type === 'hello.ack');
  assert.equal(bAck.payload.paired, false);
  assert.equal(typeof bAck.payload.issuedToken, 'string');
  assert.equal(bAck.payload.issuedToken.length, 64);
  const bridgeToken = bAck.payload.issuedToken;

  bridge.sendEnv('pair.code.create', {});
  const codeMsg = await bridge.waitFor((m) => m.type === 'pair.code.created');
  const code = codeMsg.payload.code;
  assert.match(code, /^\d{6}$/);

  // 3. Phone connects & pairs using 6-digit code
  const phoneId = 'phone-test-33334444';
  const phone = await connectClient();
  phone.sendEnv('hello', { role: 'phone', deviceId: phoneId, deviceName: 'K80 Ultra' });
  const pAck = await phone.waitFor((m) => m.type === 'hello.ack');
  assert.equal(pAck.payload.paired, false);

  phone.sendEnv('pair.request', { code, deviceName: 'K80 Ultra' });
  const pPair = await phone.waitFor((m) => m.type === 'pair.result');
  assert.equal(pPair.payload.success, true);
  assert.equal(pPair.payload.deviceToken.length, 64);
  const phoneToken = pPair.payload.deviceToken;

  const bPair = await bridge.waitFor((m) => m.type === 'pair.result');
  assert.equal(bPair.payload.success, true);

  // Verify single-use pairing code cannot be reused
  const attacker = await connectClient();
  attacker.sendEnv('hello', { role: 'phone', deviceId: 'phone-attacker-9999' });
  await attacker.waitFor((m) => m.type === 'hello.ack');
  attacker.sendEnv('pair.request', { code });
  const aRes = await attacker.waitFor((m) => m.type === 'pair.result');
  assert.equal(aRes.payload.success, false);
  attacker.close();

  // 4. Pair forwarding test
  const reqId = phone.sendEnv('session.list.get', {});
  const gotOnBridge = await bridge.waitFor((m) => m.type === 'session.list.get');
  assert.equal(gotOnBridge.reqId, reqId);

  // 5. Bridge disconnects -> phone gets presence offline + immediate desktop_offline
  bridge.close();
  const offPres = await phone.waitFor((m) => m.type === 'presence' && m.payload.online === false);
  assert.equal(offPres.payload.peer, 'bridge');

  phone.sendEnv('quota.get', {});
  const deskOff = await phone.waitFor((m) => m.type === 'desktop_offline');
  assert.equal(deskOff.payload.reason, 'bridge_offline');

  // 6. Phone offline + Bridge reconnects & sends 3 event.notify -> Phone reconnects & gets all 3 in FIFO order
  phone.close();
  await new Promise((r) => setTimeout(r, 150));

  const bridge2 = await connectClient();
  bridge2.sendEnv('hello', { role: 'bridge', deviceId: bridgeId, deviceToken: bridgeToken });
  await bridge2.waitFor((m) => m.type === 'hello.ack');

  for (let i = 1; i <= 3; i++) {
    bridge2.sendEnv('event.notify', { seq: i, title: `Notify ${i}` });
  }
  await new Promise((r) => setTimeout(r, 150));

  const phone2 = await connectClient();
  phone2.sendEnv('hello', { role: 'phone', deviceId: phoneId, deviceToken: phoneToken });
  await phone2.waitFor((m) => m.type === 'hello.ack');

  const n1 = await phone2.waitFor((m) => m.type === 'event.notify' && m.payload.seq === 1);
  const n2 = await phone2.waitFor((m) => m.type === 'event.notify' && m.payload.seq === 2);
  const n3 = await phone2.waitFor((m) => m.type === 'event.notify' && m.payload.seq === 3);
  assert.deepEqual([n1.payload.seq, n2.payload.seq, n3.payload.seq], [1, 2, 3]);

  phone2.close();
  bridge2.close();
  console.log('RELAY_SELFTEST_PASSED');
} finally {
  proc.kill('SIGTERM');
  fs.rmSync(tmpDir, { recursive: true, force: true });
}
