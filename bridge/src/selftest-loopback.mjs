/**
 * ClawBridge P2 Dual-Mode Loopback & Security Self-Test (`npm run selftest-loopback`)
 *
 * Verifies:
 *   1. Shared Envelope validator (`parseAndValidateEnvelope`) handles malformed JSON & version mismatch
 *   2. Default WAN mode (`lanEnabled=false`) does NOT open LanServer; dynamic `setMode('lan')` starts LanServer
 *      and `setMode('wan')` stops LanServer cleanly without restarting process
 *   3. UDP discovery (`CLAWBRIDGE_DISCOVER v1`) returns `{ deviceId, deviceName, lanPort, lanEnabled }`
 *      and contains ZERO tokens or pairing codes
 *   4. Unauthenticated business frame sent directly to LanServer is rejected (`unauthorized`) and closed (`4003`)
 *   5. 5 consecutive wrong pairing attempts trigger 5-minute IP lockout (`rate_limited`, close `4029`)
 *   6. Valid 6-digit LAN pairing issues 32-byte hex `lanToken`, independent from WAN `deviceToken`
 *   7. Paired reconnect with `auth { deviceId, lanToken }` succeeds and routes real business frames
 *      (`session.list.get`, `projects.config.get`, `client.mode`, `ping/pong`) through shared `attachBusinessProtocolRouter`
 *   8. Local IPC `/api/status` & `/api/mode` expose full LAN/WAN state with ZERO raw token leakage
 */

import assert from 'node:assert/strict';
import dgram from 'node:dgram';
import crypto from 'node:crypto';
import { EventEmitter } from 'node:events';
import WebSocket from 'ws';
import {
  parseAndValidateEnvelope,
  serializeEnvelope,
  BridgeTransportRouter,
} from './transport-router.mjs';
import { LanServer, listLocalLanIps, DISCOVER_MAGIC_PREFIX } from './lan-server.mjs';
import { RelayClient } from './relay-client.mjs';
import { attachBusinessProtocolRouter } from './index.mjs';
import { startLocalControlServer, getIpcTokenPath } from './local-ipc.mjs';
import fs from 'node:fs';

function waitWsOpen(ws) {
  return new Promise((resolve, reject) => {
    ws.once('open', resolve);
    ws.once('error', reject);
  });
}

function nextFrameOfType(ws, expectedType, timeoutMs = 4000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      cleanup();
      reject(new Error(`Timed out waiting for frame "${expectedType}"`));
    }, timeoutMs);

    const onMsg = (data) => {
      const msg = JSON.parse(data.toString('utf-8'));
      if (!expectedType || msg.type === expectedType) {
        cleanup();
        resolve(msg);
      }
    };
    const onClose = (code, reason) => {
      cleanup();
      reject(new Error(`Socket closed (${code}: ${reason}) while waiting for "${expectedType}"`));
    };
    const cleanup = () => {
      clearTimeout(timer);
      ws.off('message', onMsg);
      ws.off('close', onClose);
    };

    ws.on('message', onMsg);
    ws.on('close', onClose);
  });
}

function waitWsClose(ws, timeoutMs = 4000) {
  return new Promise((resolve, reject) => {
    if (ws.readyState === WebSocket.CLOSED) {
      resolve({ code: 1000, reason: '' });
      return;
    }
    const timer = setTimeout(() => reject(new Error('Timed out waiting for WS close')), timeoutMs);
    ws.once('close', (code, reason) => {
      clearTimeout(timer);
      resolve({ code, reason: reason?.toString('utf-8') || '' });
    });
  });
}

async function main() {
  console.log('=== [1/8] Shared Envelope Validation ===');
  const badJson = parseAndValidateEnvelope('{broken');
  assert.equal(badJson.ok, false);
  assert.equal(badJson.errorFrame.payload.code, 'invalid_json');

  const badVer = parseAndValidateEnvelope(JSON.stringify({ v: 99, type: 'ping', reqId: 'r1', payload: {} }));
  assert.equal(badVer.ok, false);
  assert.equal(badVer.errorFrame.payload.code, 'version_mismatch');

  const goodEnv = parseAndValidateEnvelope(serializeEnvelope('session.list.get', {}, 'req-ok'));
  assert.equal(goodEnv.ok, true);
  assert.equal(goodEnv.envelope.type, 'session.list.get');

  const detectedIps = listLocalLanIps();
  console.log('Detected physical LAN IPv4s (virtual NICs filtered):', detectedIps);

  // Ephemeral test config on high loopback ports so live ClawBridge daemon (18790/18791/18792) is untouched
  const testLanPort = 28791;
  const testUdpPort = 28792;
  const testIpcPort = 28790;

  const testConfig = {
    _ephemeral: true,
    relayUrl: 'wss://127.0.0.1:59999', // intentionally unreachable in WAN test
    deviceId: 'bridge-test-' + crypto.randomUUID().slice(0, 8),
    deviceToken: 'wan_secret_token_' + 'a'.repeat(47),
    lanEnabled: false,
    lanPort: testLanPort,
    lanToken: '',
    lastConnectionMode: 'wan',
    projectsRootDir: process.cwd(),
  };

  // Lightweight stub services to verify shared attachBusinessProtocolRouter
  const fakeQuota = Object.assign(new EventEmitter(), {
    cached: { available: true, planName: 'Ultra', tierCode: 'ULTRA', fetchedAt: Date.now() },
    getQuotaSnapshot: async () => ({ available: true, planName: 'Ultra', tierCode: 'ULTRA', fetchedAt: Date.now() }),
  });
  const fakeCodex = Object.assign(new EventEmitter(), {
    cachedRateLimits: null,
    continuationForks: new Map(),
    getRateLimitsSnapshot: async () => null,
    getModelCatalog: async () => [{ modelId: 'gpt-6.2-sol#high', label: 'GPT-6.2 Sol (High)', source: 'codex' }],
    releaseThreadWriter: async () => {},
  });
  const fakeSessionSvc = Object.assign(new EventEmitter(), {
    cdpStatus: { available: false, note: 'selftest' },
    subscription: null,
    listSessions: () => ({
      sessions: [{ id: 'sess-1', source: 'antigravity', title: 'P2 回环测试会话', project: 'ClawBridge', status: 'done', updatedAt: Date.now() }],
      cdpAvailable: false,
      cdpNote: 'selftest',
      ts: Date.now(),
    }),
    unsubscribeSession: () => {},
  });
  const fakeMetrics = Object.assign(new EventEmitter(), {
    clearSubscription: () => {},
    getMetricsSnapshot: async () => ({ enabled: true, available: false }),
  });
  const fakeNotify = Object.assign(new EventEmitter(), {
    getPendingApprovalsList: () => [],
  });

  const relayClient = new RelayClient(testConfig, null);
  // Pause actual WAN dial attempts in ephemeral unit test
  relayClient.disconnectManually();

  const lanServer = new LanServer(testConfig, { discoveryPort: testUdpPort });
  const router = new BridgeTransportRouter({
    config: testConfig,
    relayClient,
    lanServer,
  });

  attachBusinessProtocolRouter({
    config: testConfig,
    transport: router,
    quotaService: fakeQuota,
    codexClient: fakeCodex,
    sessionService: fakeSessionSvc,
    metricsService: fakeMetrics,
    notificationEngine: fakeNotify,
  });

  const ipcServer = startLocalControlServer({
    relay: relayClient,
    lanServer,
    transportRouter: router,
    quotaService: fakeQuota,
    codexClient: fakeCodex,
    sessionService: fakeSessionSvc,
    notificationEngine: fakeNotify,
    onShutdown: () => {},
    port: testIpcPort,
  });

  try {
    console.log('=== [2/8] Default WAN Mode & Dynamic LAN Start ===');
    assert.equal(lanServer.running, false, 'LanServer must be OFF by default when lanEnabled=false');

    const switchRes = await router.setMode('lan');
    assert.equal(switchRes.ok, true);
    assert.equal(lanServer.running, true);
    assert.ok(lanServer.currentPairingCode && lanServer.currentPairingCode.length === 6, 'Must generate 6-digit LAN pairing code');

    console.log('=== [3/8] UDP Discovery Responder & Zero Token Leakage ===');
    const udpReply = await new Promise((resolve, reject) => {
      const client = dgram.createSocket('udp4');
      const timer = setTimeout(() => {
        client.close();
        reject(new Error('UDP discovery timed out'));
      }, 2500);

      client.on('message', (buf) => {
        clearTimeout(timer);
        const raw = buf.toString('utf-8');
        client.close();
        resolve(raw);
      });

      const probe = Buffer.from(DISCOVER_MAGIC_PREFIX, 'utf-8');
      client.send(probe, testUdpPort, '127.0.0.1');
    });

    console.log('UDP reply:', udpReply);
    const parsedUdp = JSON.parse(udpReply);
    assert.equal(parsedUdp.deviceId, testConfig.deviceId);
    assert.equal(parsedUdp.lanPort, testLanPort);
    assert.equal(parsedUdp.lanEnabled, true);
    assert.equal('lanToken' in parsedUdp, false, 'UDP reply MUST NOT contain lanToken');
    assert.equal('deviceToken' in parsedUdp, false, 'UDP reply MUST NOT contain deviceToken');
    assert.equal('code' in parsedUdp, false, 'UDP reply MUST NOT contain pairing code');

    console.log('=== [4/8] Security Check: Unauthenticated Business Frame Rejected ===');
    {
      const wsRogue = new WebSocket(`ws://127.0.0.1:${testLanPort}`);
      await waitWsOpen(wsRogue);
      const errPromise = nextFrameOfType(wsRogue, 'auth.error');
      const closePromise = waitWsClose(wsRogue);
      wsRogue.send(serializeEnvelope('session.list.get', {}, 'rogue-1'));
      const errFrame = await errPromise;
      const closeInfo = await closePromise;
      assert.equal(errFrame.payload.code, 'unauthorized');
      assert.equal(closeInfo.code, 4003);
    }

    console.log('=== [5/8] Security Check: 5 Wrong Pairing Codes Trigger 5-Min Lockout ===');
    {
      const wsBrute = new WebSocket(`ws://127.0.0.1:${testLanPort}`);
      await waitWsOpen(wsBrute);
      for (let attempt = 1; attempt <= 5; attempt++) {
        const resPromise = nextFrameOfType(wsBrute, 'pair.result');
        wsBrute.send(serializeEnvelope('pair.request', { code: '000000', deviceId: 'attacker-1', deviceName: 'Attacker' }));
        const resFrame = await resPromise;
        assert.equal(resFrame.payload.success, false);
        if (attempt < 5) {
          assert.equal(resFrame.payload.code, 'invalid_pair_code');
        } else {
          assert.equal(resFrame.payload.code, 'rate_limited');
        }
      }
      const closeInfo = await waitWsClose(wsBrute);
      assert.equal(closeInfo.code, 4029);

      // Verify subsequent connection while locked is immediately rejected with 4029
      const wsLocked = new WebSocket(`ws://127.0.0.1:${testLanPort}`);
      const closeLocked = await waitWsClose(wsLocked);
      assert.equal(closeLocked.code, 4029);

      // Clear rate limit map to continue functional pairing test on loopback
      lanServer.ipFailures.clear();
    }

    console.log('=== [6/8] Fresh LAN Pairing Issues 32-Byte Hex lanToken (WAN Token Preserved) ===');
    let issuedLanToken = '';
    const phoneId = 'phone-test-' + crypto.randomUUID().slice(0, 8);
    {
      const validCode = lanServer.currentPairingCode;
      const wsPair = new WebSocket(`ws://127.0.0.1:${testLanPort}`);
      await waitWsOpen(wsPair);
      const pairResPromise = nextFrameOfType(wsPair, 'pair.result');
      const initialListPromise = nextFrameOfType(wsPair, 'session.list');
      wsPair.send(
        serializeEnvelope('pair.request', {
          code: validCode,
          deviceId: phoneId,
          deviceName: 'Xiaomi Loopback Test',
        })
      );
      const pairRes = await pairResPromise;
      assert.equal(pairRes.payload.success, true);
      issuedLanToken = pairRes.payload.lanToken;
      assert.match(issuedLanToken, /^[0-9a-f]{64}$/, 'lanToken must be 32-byte (64-char) hex');
      assert.equal(testConfig.lanToken, issuedLanToken);
      assert.ok(testConfig.deviceToken.startsWith('wan_secret_token_'), 'WAN deviceToken must remain untouched');

      const initialList = await initialListPromise;
      assert.equal(initialList.payload.sessions[0].title, 'P2 回环测试会话');
      wsPair.close();
      await waitWsClose(wsPair);
    }

    console.log('=== [7/8] Paired Reconnect via First-Frame auth + Business Routing + LAN RTT ===');
    {
      // Wrong lanToken must disconnect immediately with 4003
      const wsWrongTok = new WebSocket(`ws://127.0.0.1:${testLanPort}`);
      await waitWsOpen(wsWrongTok);
      const closeWrong = waitWsClose(wsWrongTok);
      wsWrongTok.send(serializeEnvelope('auth', { deviceId: phoneId, lanToken: 'f'.repeat(64) }));
      const wrongCloseInfo = await closeWrong;
      assert.equal(wrongCloseInfo.code, 4003);
      lanServer.ipFailures.clear();

      // Valid lanToken auth + business messages
      const wsAuth = new WebSocket(`ws://127.0.0.1:${testLanPort}`);
      await waitWsOpen(wsAuth);
      const authAckPromise = nextFrameOfType(wsAuth, 'auth.ack');
      wsAuth.send(
        serializeEnvelope('auth', {
          deviceId: phoneId,
          lanToken: issuedLanToken,
          deviceName: 'Xiaomi Loopback Test',
          mode: 'lan',
        })
      );
      const authAck = await authAckPromise;
      assert.equal(authAck.payload.ok, true);

      // Report client.mode & query projects.config.get + ping
      const modeAckPromise = nextFrameOfType(wsAuth, 'ack');
      wsAuth.send(serializeEnvelope('client.mode', { mode: 'lan' }, 'mode-1'));
      const modeAck = await modeAckPromise;
      assert.equal(modeAck.payload.mode, 'lan');

      const cfgPromise = nextFrameOfType(wsAuth, 'projects.config.data');
      wsAuth.send(serializeEnvelope('projects.config.get', {}, 'cfg-1'));
      const cfgMsg = await cfgPromise;
      assert.equal(cfgMsg.payload.ok, true);

      const pingSentTs = Date.now();
      const pongPromise = nextFrameOfType(wsAuth, 'pong');
      wsAuth.send(JSON.stringify({ v: 1, type: 'ping', reqId: 'ping-1', ts: pingSentTs, payload: {} }));
      const pongMsg = await pongPromise;
      const rttMs = Date.now() - pongMsg.payload.clientTs;
      console.log(`LAN loopback RTT: ${rttMs}ms`);
      assert.ok(rttMs < 100, 'LAN RTT should be sub-100ms');

      console.log('=== [8/8] Local IPC /api/status & /api/mode Redaction Audit ===');
      const ipcToken = fs.readFileSync(getIpcTokenPath(), 'utf-8').trim();
      const statusRes = await fetch(`http://127.0.0.1:${testIpcPort}/api/status`, {
        headers: { 'X-ClawBridge-Token': ipcToken },
      });
      assert.equal(statusRes.status, 200);
      const rawStatusJson = await statusRes.text();
      assert.equal(rawStatusJson.includes(issuedLanToken), false, 'IPC response MUST NOT leak lanToken');
      assert.equal(rawStatusJson.includes(testConfig.deviceToken), false, 'IPC response MUST NOT leak WAN deviceToken');

      const statusData = JSON.parse(rawStatusJson);
      assert.equal(statusData.connectionMode, 'lan');
      assert.equal(statusData.lanEnabled, true);
      assert.equal(statusData.lanRunning, true);
      assert.equal(statusData.lanPaired, true);
      assert.equal(statusData.lanPeerOnline, true);
      assert.equal(statusData.peerConnectionMode, 'lan');

      // Switch back to WAN via IPC -> LanServer stops dynamically and closes active LAN socket cleanly
      const closeOnModeSwitch = waitWsClose(wsAuth);
      const modeSwitchRes = await fetch(`http://127.0.0.1:${testIpcPort}/api/mode`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-ClawBridge-Token': ipcToken,
        },
        body: JSON.stringify({ mode: 'wan' }),
      });
      assert.equal(modeSwitchRes.status, 200);
      await closeOnModeSwitch;
      assert.equal(lanServer.running, false, 'LanServer must stop immediately when switched to WAN');
      // Keep relayClient paused in ephemeral test so it doesn't dial dummy port
      relayClient.disconnectManually();
    }

    console.log('ALL 8 P2 DUAL-MODE LOOPBACK & SECURITY CHECKS PASSED!');
  } finally {
    ipcServer.close();
    await router.stopAll();
  }
}

main().catch((err) => {
  console.error('SELFTEST FAILED:', err);
  process.exit(1);
});
