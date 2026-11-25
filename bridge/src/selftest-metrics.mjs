/**
 * Portable unit self-test for ClawBridge MetricsService & PricingBook (zero external deps / zero PII)
 */

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';

const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'clawbridge-oss-metrics-'));
process.env.APPDATA = tmpDir;
process.env.CLAWBRIDGE_DATA_DIR = path.join(tmpDir, 'clawbridge');

const { MetricsService, PricingBook } = await import('./metrics-service.mjs');
const { getPricesFilePath } = await import('./claw-config.mjs');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

try {
  // 1. prices.json auto-generation & corruption resilience
  const pb = new PricingBook();
  pb.ensureAndLoad({ force: true });
  const p = getPricesFilePath();
  assert.ok(fs.existsSync(p), 'prices.json should be auto-generated');
  assert.equal(pb.resolveModel('Gemini 4 Neon (High)').priceDefaulted, false);
  assert.equal(pb.resolveModel('unknown-model-xyz').priceDefaulted, true);

  fs.writeFileSync(p, '{ corrupted json', 'utf-8');
  pb.ensureAndLoad({ force: true });
  assert.equal(pb.corrupted, true);
  assert.equal(pb.computeCost({ inUncached: 1_000_000, outTokens: 100_000 }, 'Gemini 4 Neon').priceDefaulted, true);
  fs.unlinkSync(p);
  pb.ensureAndLoad({ force: true });
  assert.equal(pb.corrupted, false);

  // 2. Live Codex stream throttling (<= 1Hz) + exact tokenUsage calibration
  const fakeCodex = new EventEmitter();
  fakeCodex.cachedThreads = [];
  fakeCodex.activeTurns = new Map();
  fakeCodex.continuationForks = new Map();
  fakeCodex.latestTokenUsage = new Map();

  const ms = new MetricsService({ sessionService: null, codexClient: fakeCodex, enabled: true });
  const pushed = [];
  ms.on('updated', (s) => pushed.push(s));
  ms.start();

  const tid = 'demo-codex-thread-001';
  await ms.setSubscription(tid, 'codex');
  pushed.length = 0;

  fakeCodex.activeTurns.set(tid, 'turn-1');
  fakeCodex.emit('turnLifecycle', { threadId: tid, turnId: 'turn-1', phase: 'started', ts: Date.now() });
  await sleep(160);
  for (let i = 0; i < 22; i++) {
    fakeCodex.emit('streamDelta', { threadId: tid, turnId: 'turn-1', kind: 'agent', delta: `Streaming chunk ${i} 测试输出文本。`, ts: Date.now() });
    await sleep(60);
  }
  fakeCodex.emit('tokenUsage', {
    threadId: tid,
    turnId: 'turn-1',
    tokenUsage: {
      modelContextWindow: 258400,
      last: { inputTokens: 40000, cachedInputTokens: 32000, cacheWriteInputTokens: 0, outputTokens: 512, reasoningOutputTokens: 64, totalTokens: 40512 },
      total: { inputTokens: 40000, cachedInputTokens: 32000, cacheWriteInputTokens: 0, outputTokens: 512, reasoningOutputTokens: 64, totalTokens: 40512 },
    },
    ts: Date.now(),
  });
  await sleep(40);
  fakeCodex.activeTurns.delete(tid);
  fakeCodex.emit('turnLifecycle', { threadId: tid, turnId: 'turn-1', phase: 'completed', ts: Date.now() });
  await sleep(60);

  assert.ok(pushed.length >= 2 && pushed.length <= 5, `Unexpected push count: ${pushed.length}`);
  assert.equal(pushed[0].state, 'generating');
  assert.equal(pushed[pushed.length - 1].state, 'idle');
  assert.equal(pushed[pushed.length - 1].outputTokens, 512);
  assert.equal(pushed[pushed.length - 1].outputTokensEstimated, false);

  // 3. Disabled switch
  const msOff = new MetricsService({ sessionService: null, codexClient: fakeCodex, enabled: false });
  msOff.start();
  const offSnap = await msOff.getMetricsSnapshot({ sessionId: tid, source: 'codex' });
  assert.equal(offSnap.enabled, false);

  msOff.stop();
  ms.stop();
  console.log('PASS: ClawBridge OSS MetricsService self-test succeeded.');
} finally {
  fs.rmSync(tmpDir, { recursive: true, force: true });
}
