/**
 * Isolated Codex App-Server Protocol Adapter (Locked to codex-cli 0.167.0)
 *
 * Spec 3.2 & 8.2:
 *   - Spawns `codex app-server` (JSON-RPC 2.0 over stdio)
 *   - Enforces a single writer per thread inside Bridge (reuses loaded thread, serializes
 *     per-thread sends, releases idle writers cleanly so OS `.lock` files never leak)
 *   - Retries transient `already has an active writer` conflicts with full forensic logging
 *     and returns a clear user-facing explanation when an external process (e.g. Codex Desktop)
 *     holds the Windows OS thread-writer lock.
 */

import { spawn, execFile } from 'node:child_process';
import fs from 'node:fs';
import readline from 'node:readline';
import path from 'node:path';
import { EventEmitter } from 'node:events';
import { promisify } from 'node:util';
import { logger } from './logger.mjs';

const execFileAsync = promisify(execFile);

export const LOCKED_CODEX_CLI_VERSION = '0.167.0';

const ACTIVE_WRITER_RE = /already has an active writer|thread-store conflict/i;
const ACTIVE_WRITER_RETRY_DELAYS_MS = [900];
const IDLE_RELEASE_DELAY_MS = 15_000;

export const FRIENDLY_ACTIVE_WRITER_MESSAGE =
  '该 Codex 会话正被电脑端 Codex 客户端独占锁定，且自动续接失败，请关闭电脑 Codex 客户端后再重试';

export class CodexActiveWriterError extends Error {
  constructor({ threadId, rawError, rpcCode = null, attempts = 1 }) {
    super(FRIENDLY_ACTIVE_WRITER_MESSAGE);
    this.name = 'CodexActiveWriterError';
    this.code = 'codex_active_writer';
    this.threadId = threadId;
    this.rawError = String(rawError || '');
    this.rpcCode = rpcCode;
    this.attempts = attempts;
    this.userMessage = FRIENDLY_ACTIVE_WRITER_MESSAGE;
  }
}

function resolveDirectCodexBinary() {
  if (process.platform !== 'win32') return null;
  const appData = process.env.APPDATA || '';
  if (!appData) return null;
  const candidate = path.join(
    appData,
    'npm',
    'node_modules',
    '@openai',
    'codex',
    'node_modules',
    '@openai',
    'codex-win32-x64',
    'vendor',
    'x86_64-pc-windows-msvc',
    'bin',
    'codex.exe'
  );
  return fs.existsSync(candidate) ? candidate : null;
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

export class CodexAppServerClient extends EventEmitter {
  constructor() {
    super();
    this.proc = null;
    this.rl = null;
    this.ready = false;
    this.stopped = false;
    this.nextRpcId = 1;
    // id -> { resolve, reject, timer, method }
    this.pendingCalls = new Map();
    // approvalKey -> { rpcId, method, threadId }
    this.pendingServerApprovals = new Map();
    this.restartTimer = null;
    this.cachedThreads = [];
    this.cachedRateLimits = null;
    this.cachedModels = [];

    // Single-writer lifecycle tracking
    this.loadedThreads = new Set(); // threadIds currently loaded in our single app-server
    this.activeTurns = new Map(); // threadId -> turnId
    this.threadSendChains = new Map(); // threadId -> Promise (serializes writes per thread)
    this.idleReleaseTimers = new Map(); // threadId -> Timeout
    // Seamless continuation forks for threads exclusively OS-locked by Codex Desktop:
    // originalLockedThreadId -> { forkThreadId, forkPath, createdAt }
    this.continuationForks = new Map();
    // Live streaming assistant reply buffers: threadId -> { text, streaming, ts }
    this.liveStreams = new Map();
    this.recentStderr = [];
    this.readyPromise = null;
    this._resolveReady = null;
    this.recycling = false;
    this.lastExitCode = null;
  }

  async start() {
    this.stopped = false;
    await this._logCliVersion();
    this._spawnServer();
  }

  stop() {
    this.stopped = true;
    this._clearAllIdleTimers();
    this._teardownProcess('stop');
  }

  _clearAllIdleTimers() {
    for (const timer of this.idleReleaseTimers.values()) {
      clearTimeout(timer);
    }
    this.idleReleaseTimers.clear();
  }

  _clearIdleTimer(threadId) {
    const t = this.idleReleaseTimers.get(threadId);
    if (t) {
      clearTimeout(t);
      this.idleReleaseTimers.delete(threadId);
    }
  }

  _teardownProcess(reason = 'stop') {
    this.ready = false;
    this.loadedThreads.clear();
    this.activeTurns.clear();
    this.pendingServerApprovals.clear();
    if (this.restartTimer) {
      clearTimeout(this.restartTimer);
      this.restartTimer = null;
    }
    for (const [, p] of this.pendingCalls) {
      clearTimeout(p.timer);
      p.reject(new Error(`Codex app-server stopped (${reason})`));
    }
    this.pendingCalls.clear();
    if (this.rl) {
      try { this.rl.close(); } catch { /* ignore */ }
      this.rl = null;
    }
    if (this.proc) {
      const child = this.proc;
      this.proc = null;
      try { child.stdin?.end(); } catch { /* ignore */ }
      try { child.kill(); } catch { /* ignore */ }
    }
  }

  async _logCliVersion() {
    try {
      const directBin = resolveDirectCodexBinary();
      const { stdout } = directBin
        ? await execFileAsync(directBin, ['--version'], { timeout: 5000, windowsHide: true })
        : await execFileAsync('cmd.exe', ['/c', 'codex', '--version'], { timeout: 5000, windowsHide: true });
      const ver = stdout.trim();
      if (ver.includes(LOCKED_CODEX_CLI_VERSION)) {
        logger.info('Codex', `Detected ${ver} (matches locked target ${LOCKED_CODEX_CLI_VERSION})`);
      } else {
        logger.warn('Codex', `Detected "${ver}" (locked adapter target is ${LOCKED_CODEX_CLI_VERSION})`);
      }
    } catch (err) {
      logger.warn('Codex', `codex CLI version check skipped: ${err.message}`);
    }
  }

  _spawnServer() {
    if (this.stopped || this.proc) return;

    this.readyPromise = new Promise((resolve) => {
      this._resolveReady = resolve;
    });

    try {
      const directBin = resolveDirectCodexBinary();
      const child = directBin
        ? spawn(directBin, ['app-server'], { stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true })
        : spawn('cmd.exe', ['/c', 'codex', 'app-server'], { stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true });

      this.proc = child;
      this.ready = false;
      this.lastExitCode = null;

      this.rl = readline.createInterface({ input: child.stdout });
      this.rl.on('line', (line) => this._handleStdoutLine(line));

      child.stderr.on('data', (chunk) => {
        const raw = chunk.toString('utf-8').trim();
        if (!raw) return;
        for (const line of raw.split(/\r?\n/)) {
          const clean = line.replace(/\x1B\[[0-9;]*m/g, '').trim();
          if (!clean || clean.includes('preferred_auth_method')) continue;
          this.recentStderr.push(`[${new Date().toISOString()}] ${clean}`);
          if (this.recentStderr.length > 20) this.recentStderr.shift();
          logger.warn('CodexStdErr', clean.slice(0, 400));
        }
      });

      child.on('exit', (code, signal) => {
        this.lastExitCode = code;
        const wasCurrent = this.proc === child;
        if (wasCurrent) {
          this.proc = null;
          this.ready = false;
          this.loadedThreads.clear();
          this.activeTurns.clear();
          for (const [, p] of this.pendingCalls) {
            clearTimeout(p.timer);
            p.reject(new Error(`codex app-server exited unexpectedly (code=${code}, signal=${signal || 'none'})`));
          }
          this.pendingCalls.clear();
        }
        if (!this.recycling) {
          logger.warn('Codex', `codex app-server exited (code=${code}, signal=${signal || 'none'})`);
        }
        if (!this.stopped && !this.recycling && wasCurrent) {
          this.restartTimer = setTimeout(() => {
            this.restartTimer = null;
            this._spawnServer();
          }, 4000);
          this.restartTimer.unref?.();
        }
      });

      this._handshake().catch((err) => {
        logger.warn('Codex', `Initial handshake failed: ${err.message}`);
      });
    } catch (err) {
      logger.error('Codex', `Failed to spawn codex app-server: ${err.message}`);
    }
  }

  async _waitUntilReady(timeoutMs = 10_000) {
    if (this.ready) return true;
    if (!this.proc && !this.stopped) {
      this._spawnServer();
    }
    if (!this.readyPromise) return this.ready;
    await Promise.race([
      this.readyPromise,
      sleep(timeoutMs),
    ]);
    return this.ready;
  }

  async _handshake() {
    const res = await this.callRpc(
      'initialize',
      {
        clientInfo: {
          name: 'clawbridge-desktop',
          version: '1.7.1',
        },
      },
      8000
    );
    this._writeRaw({ jsonrpc: '2.0', method: 'initialized', params: {} });
    this.ready = true;
    if (this._resolveReady) {
      this._resolveReady(true);
      this._resolveReady = null;
    }
    logger.info('Codex', `app-server initialized (${res?.userAgent || 'ok'})`);
    await this.refreshThreadsAndLimits().catch(() => {});
  }

  _writeRaw(obj) {
    if (!this.proc || !this.proc.stdin.writable) return false;
    this.proc.stdin.write(JSON.stringify(obj) + '\n');
    return true;
  }

  callRpc(method, params = {}, timeoutMs = 10_000) {
    return new Promise((resolve, reject) => {
      if (!this.proc || !this.proc.stdin.writable) {
        return reject(new Error('codex app-server is not running'));
      }
      const id = this.nextRpcId++;
      const timer = setTimeout(() => {
        if (this.pendingCalls.has(id)) {
          this.pendingCalls.delete(id);
          reject(new Error(`Codex RPC timeout: ${method}`));
        }
      }, timeoutMs);
      timer.unref?.();

      this.pendingCalls.set(id, { resolve, reject, timer, method });
      this._writeRaw({ jsonrpc: '2.0', id, method, params });
    });
  }

  _handleStdoutLine(line) {
    if (!line || !line.trim()) return;
    let msg;
    try {
      msg = JSON.parse(line);
    } catch {
      return;
    }

    // 1. Response to our outgoing ClientRequest
    if (msg.id !== undefined && msg.method === undefined) {
      const pending = this.pendingCalls.get(msg.id);
      if (pending) {
        this.pendingCalls.delete(msg.id);
        clearTimeout(pending.timer);
        if (msg.error) {
          const err = new Error(msg.error.message || JSON.stringify(msg.error));
          err.rpcCode = msg.error.code ?? null;
          err.rpcData = msg.error.data ?? null;
          err.rpcMethod = pending.method;
          pending.reject(err);
        } else {
          pending.resolve(msg.result);
        }
      }
      return;
    }

    // 2. Incoming ServerRequest from Codex requiring client response (Approvals!)
    if (msg.id !== undefined && typeof msg.method === 'string') {
      this._handleServerRequest(msg.id, msg.method, msg.params || {});
      return;
    }

    // 3. Server Notifications (streaming deltas, turn lifecycle)
    if (typeof msg.method === 'string') {
      this._handleNotification(msg.method, msg.params || {});
    }
  }

  _handleServerRequest(rpcId, method, params) {
    if (
      method === 'item/commandExecution/requestApproval' ||
      method === 'item/fileChange/requestApproval' ||
      method === 'execCommandApproval' ||
      method === 'applyPatchApproval'
    ) {
      const threadId = params.threadId || params.conversationId || '';
      const cmdText =
        params.command ||
        (Array.isArray(params.command) ? params.command.join(' ') : '') ||
        params.reason ||
        (method.includes('fileChange') || method.includes('Patch') ? 'Codex 请求修改工作区文件' : 'Codex 请求执行命令');
      const cwd = params.cwd || '';

      const approvalKey = `codex-rpc-${rpcId}-${Date.now()}`;
      this.pendingServerApprovals.set(approvalKey, { rpcId, method, threadId });

      logger.info('Codex', `Intercepted Codex approval request (${method}) on thread ${threadId.slice(0, 8)}`);
      this.emit('approval.request', {
        approvalKey,
        threadId,
        cwd,
        method,
        commandPreview: String(cmdText).slice(0, 300),
      });
      return;
    }

    // Unsupported server request -> return clean JSON-RPC error so Codex never hangs
    this._writeRaw({
      jsonrpc: '2.0',
      id: rpcId,
      error: { code: -32601, message: `Unsupported client callback: ${method}` },
    });
  }

  respondToApproval(approvalKey, approved) {
    const entry = this.pendingServerApprovals.get(approvalKey);
    if (!entry) return false;
    this.pendingServerApprovals.delete(approvalKey);

    const { rpcId, method } = entry;
    let decision;
    if (method === 'execCommandApproval' || method === 'applyPatchApproval') {
      decision = approved ? 'approved' : 'denied';
    } else {
      decision = approved ? 'accept' : 'decline';
    }

    logger.info('Codex', `Sending Codex approval response (method=${method}, decision=${decision})`);
    return this._writeRaw({
      jsonrpc: '2.0',
      id: rpcId,
      result: { decision },
    });
  }

  _handleNotification(method, params) {
    const tid = params?.threadId || params?.turn?.threadId || '';
    if (method === 'turn/started' && tid) {
      this._clearIdleTimer(tid);
      this.activeTurns.set(tid, params?.turn?.id || params?.turnId || 'active');
      this.liveStreams.set(tid, { text: '', streaming: true, ts: Date.now() });
    } else if (method === 'item/agentMessage/delta' && tid && typeof params?.delta === 'string') {
      const cur = this.liveStreams.get(tid) || { text: '', streaming: true, ts: Date.now() };
      cur.text += params.delta;
      cur.streaming = true;
      cur.ts = Date.now();
      this.liveStreams.set(tid, cur);
    } else if (method === 'item/completed' && tid && params?.item?.type === 'agentMessage' && params?.item?.text) {
      this.liveStreams.set(tid, { text: String(params.item.text), streaming: false, ts: Date.now() });
    } else if (method === 'turn/completed' && tid) {
      this.activeTurns.delete(tid);
      const cur = this.liveStreams.get(tid);
      if (cur) {
        cur.streaming = false;
        cur.ts = Date.now();
      }
      this._scheduleIdleRelease(tid, 'turn_completed');
    } else if (method === 'thread/closed' && tid) {
      this.loadedThreads.delete(tid);
      this.activeTurns.delete(tid);
      this._clearIdleTimer(tid);
    }

    if (
      method === 'turn/started' ||
      method === 'turn/completed' ||
      method === 'item/completed' ||
      method === 'item/agentMessage/delta'
    ) {
      this.emit('activity', { method, params });
    }
  }

  _scheduleIdleRelease(threadId, reason = 'idle') {
    if (!threadId) return;
    this._clearIdleTimer(threadId);
    const timer = setTimeout(() => {
      this.idleReleaseTimers.delete(threadId);
      this.releaseThreadWriter(threadId, reason).catch(() => {});
    }, IDLE_RELEASE_DELAY_MS);
    timer.unref?.();
    this.idleReleaseTimers.set(threadId, timer);
  }

  _hasPendingApprovalForThread(threadId) {
    for (const v of this.pendingServerApprovals.values()) {
      if (!threadId || v.threadId === threadId) return true;
    }
    return false;
  }

  /**
   * Releases our writer handle on a Codex thread when a turn completes, the user switches
   * away from the session, or the phone disconnects.
   *
   * Note on codex-cli 0.167.0: `thread/unsubscribe` returns `{ status: 'unsubscribed' }`,
   * yet the process can still retain the thread in `thread/loaded/list` (keeping its Windows
   * `.codex/thread-writer-locks/<id>.lock` handle open). When Bridge has zero active turns,
   * zero pending sends, and zero pending approvals, recycling the idle app-server cleanly
   * guarantees all OS file locks are released back to Codex Desktop without leaking.
   */
  async releaseThreadWriter(threadId, reason = 'manual') {
    if (!this.ready || this.stopped) return false;
    const mappedForkId = threadId ? this.continuationForks.get(threadId)?.forkThreadId : null;
    const targetIds = threadId ? [threadId, ...(mappedForkId ? [mappedForkId] : [])] : null;
    if (targetIds) {
      for (const tid of targetIds) {
        this._clearIdleTimer(tid);
        if (this.activeTurns.has(tid) || this._hasPendingApprovalForThread(tid) || this.threadSendChains.has(tid)) {
          continue;
        }
        if (this.loadedThreads.has(tid)) {
          try {
            await this.callRpc('thread/unsubscribe', { threadId: tid }, 5000);
            logger.info('Codex', `Unsubscribed writer for thread ${tid.slice(0, 8)} (reason=${reason})`);
          } catch (err) {
            logger.warn('Codex', `thread/unsubscribe warning for ${tid.slice(0, 8)}: ${err.message}`);
          }
          this.loadedThreads.delete(tid);
        }
      }
    } else {
      // Release all idle loaded threads (e.g. phone went offline)
      for (const tid of [...this.loadedThreads]) {
        if (!this.activeTurns.has(tid) && !this._hasPendingApprovalForThread(tid) && !this.threadSendChains.has(tid)) {
          this._clearIdleTimer(tid);
          await this.callRpc('thread/unsubscribe', { threadId: tid }, 5000).catch(() => {});
          this.loadedThreads.delete(tid);
        }
      }
    }

    // If completely idle across all threads, verify whether codex 0.167.0 still holds OS locks
    if (
      this.loadedThreads.size === 0 &&
      this.activeTurns.size === 0 &&
      this.pendingServerApprovals.size === 0 &&
      this.threadSendChains.size === 0 &&
      !this.recycling
    ) {
      try {
        const loaded = await this.callRpc('thread/loaded/list', {}, 4000);
        const lingering = Array.isArray(loaded?.data) ? loaded.data : [];
        if (lingering.length > 0) {
          await this._recycleIdleServer(`release_os_locks:${reason}:${lingering.length}`);
        }
      } catch {
        // Ignore transient check errors
      }
    }
    return true;
  }

  async _recycleIdleServer(reason) {
    if (this.stopped || this.recycling) return;
    if (this.activeTurns.size > 0 || this.pendingServerApprovals.size > 0 || this.threadSendChains.size > 0) {
      return;
    }
    this.recycling = true;
    logger.info('Codex', `Recycling idle codex app-server to release OS writer locks (reason=${reason})`);
    try {
      this._teardownProcess(`recycle:${reason}`);
      await sleep(150);
      if (!this.stopped) {
        this._spawnServer();
        await this._waitUntilReady(8000);
      }
    } finally {
      this.recycling = false;
    }
  }

  async refreshThreadsAndLimits() {
    if (!this.ready) return;
    const calls = [
      this.callRpc('thread/list', { limit: 40 }),
      this.callRpc('account/rateLimits/read', {}),
    ];
    if (this.cachedModels.length === 0) {
      calls.push(this.callRpc('model/list', {}));
    }
    const [threadsRes, limitsRes, modelsRes] = await Promise.allSettled(calls);

    if (modelsRes?.status === 'fulfilled' && Array.isArray(modelsRes.value?.data)) {
      const expanded = [];
      for (const m of modelsRes.value.data) {
        if (m.hidden || !m.model) continue;
        const baseName = m.displayName || m.model;
        const efforts = Array.isArray(m.supportedReasoningEfforts)
          ? m.supportedReasoningEfforts.map((e) => e.reasoningEffort).filter((e) => ['low', 'medium', 'high', 'xhigh'].includes(e))
          : [];
        if (efforts.length > 0) {
          for (const eff of efforts) {
            const effCap = eff === 'xhigh' ? 'XHigh' : eff.charAt(0).toUpperCase() + eff.slice(1);
            expanded.push({
              modelId: `${m.model}#${eff}`,
              label: `${baseName} (${effCap})`,
              source: 'codex',
              tag: m.isDefault && eff === m.defaultReasoningEffort ? 'Default' : '',
              description: m.description || '',
              remainingPercent: null,
              resetTimeIso: null,
            });
          }
        } else {
          expanded.push({
            modelId: m.model,
            label: baseName,
            source: 'codex',
            tag: m.isDefault ? 'Default' : '',
            description: m.description || '',
            remainingPercent: null,
            resetTimeIso: null,
          });
        }
      }
      if (expanded.length > 0) {
        this.cachedModels = expanded;
      }
    }

    if (threadsRes.status === 'fulfilled' && Array.isArray(threadsRes.value?.data)) {
      this.cachedThreads = threadsRes.value.data.map((t) => {
        const cwd = t.cwd || '';
        const project = cwd ? path.basename(cwd.replace(/[\\/]+$/, '')) : 'Codex';
        const stType = t.status?.type || 'notLoaded';
        const status = stType === 'running' || stType === 'active' || this.activeTurns.has(t.id) ? 'running' : 'done';
        const updatedMs = t.updatedAt ? Number(t.updatedAt) * 1000 : Date.now();
        return {
          id: t.id,
          source: 'codex',
          title: (t.name || '').trim() || `Codex 会话 ${String(t.id).slice(0, 8)}`,
          project,
          workspacePath: cwd.replace(/\\/g, '/'),
          preview: t.model ? `[${t.model}] ${t.name || ''}`.trim() : t.name || 'Codex 会话',
          status,
          stepCount: 0,
          subagentCount: 0,
          runningSubagentCount: 0,
          updatedAt: updatedMs,
          currentModelId: t.model || '',
          currentModelLabel: t.model || '',
          _filePath: t.path || '',
        };
      });
    }

    if (limitsRes.status === 'fulfilled' && limitsRes.value?.rateLimits) {
      const rl = limitsRes.value.rateLimits;
      const used5h = Math.max(0, Math.min(100, Math.round(Number(rl.primary?.usedPercent ?? 0))));
      const used7d = Math.max(0, Math.min(100, Math.round(Number(rl.secondary?.usedPercent ?? 0))));
      const rawPlan = String(rl.planType || 'plus').trim();
      const planLabel = `ChatGPT ${rawPlan.charAt(0).toUpperCase() + rawPlan.slice(1)}`;
      const resetCreditsCount = Number(limitsRes.value?.rateLimitResetCredits?.availableCount ?? 0);

      const prevSig = this.cachedRateLimits
        ? `${this.cachedRateLimits.window5h?.usedPercent}:${this.cachedRateLimits.window7d?.usedPercent}`
        : '';

      this.cachedRateLimits = {
        available: true,
        planType: rawPlan,
        planName: planLabel,
        fetchedAt: Date.now(),
        resetCreditsCount,
        window5h: {
          bucketId: 'codex_openai_5h',
          window: '5h',
          usedPercent: used5h,
          remainingPercent: 100 - used5h,
          remainingFraction: Number(((100 - used5h) / 100).toFixed(4)),
          resetAt: rl.primary?.resetsAt ? Number(rl.primary.resetsAt) * 1000 : null,
        },
        window7d: {
          bucketId: 'codex_openai_7d',
          window: '7d',
          usedPercent: used7d,
          remainingPercent: 100 - used7d,
          remainingFraction: Number(((100 - used7d) / 100).toFixed(4)),
          resetAt: rl.secondary?.resetsAt ? Number(rl.secondary.resetsAt) * 1000 : null,
        },
      };

      const nextSig = `${used5h}:${used7d}`;
      if (nextSig !== prevSig) {
        this.emit('rateLimits.updated', this.cachedRateLimits);
      }
    }
  }

  async getRateLimitsSnapshot({ forceRefresh = false } = {}) {
    if ((forceRefresh || !this.cachedRateLimits) && this.ready) {
      await this.refreshThreadsAndLimits().catch(() => {});
    }
    return this.cachedRateLimits;
  }

  async getModelCatalog() {
    if (this.cachedModels.length === 0 && this.ready) {
      await this.refreshThreadsAndLimits().catch(() => {});
    }
    return this.cachedModels;
  }

  async _forkLockedThread(originalThreadId) {
    const forkRes = await this.callRpc('thread/fork', { threadId: originalThreadId, excludeTurns: true }, 12_000);
    const newThreadId = forkRes?.thread?.id || '';
    if (!newThreadId) {
      throw new Error(`thread/fork returned empty thread id for ${originalThreadId}`);
    }
    const forkPath = forkRes?.thread?.path || '';
    const origMeta = this.cachedThreads.find((t) => t.id === originalThreadId);
    const origTitle = (origMeta?.title || '').trim();
    const forkName =
      origTitle && !origTitle.startsWith('Codex 会话 ')
        ? origTitle.includes('手机续接')
          ? origTitle
          : `${origTitle} (手机续接)`
        : `Codex 续接 ${originalThreadId.slice(0, 8)}`;
    await this.callRpc('thread/name/set', { threadId: newThreadId, name: forkName }, 5000).catch(() => {});

    this.loadedThreads.add(newThreadId);
    this.continuationForks.set(originalThreadId, {
      forkThreadId: newThreadId,
      forkPath,
      createdAt: Date.now(),
    });
    logger.info(
      'CodexWriter',
      `Created seamless continuation fork ${newThreadId.slice(0, 8)} for externally locked thread ${originalThreadId.slice(0, 8)} (name="${forkName}")`
    );
    this.refreshThreadsAndLimits().catch(() => {});
    return {
      reused: false,
      effectiveThreadId: newThreadId,
      forkedFrom: originalThreadId,
      forkPath,
    };
  }

  async _ensureSingleWriterLoaded(threadId, { allowForkOnExternalLock = true } = {}) {
    // 0. If we previously created a continuation fork for this locked thread, reuse that single fork!
    const existingFork = this.continuationForks.get(threadId);
    if (existingFork?.forkThreadId) {
      const forkId = existingFork.forkThreadId;
      if (this.loadedThreads.has(forkId)) {
        return { reused: true, effectiveThreadId: forkId, forkedFrom: threadId, forkPath: existingFork.forkPath };
      }
      try {
        await this.callRpc('thread/resume', { threadId: forkId, excludeTurns: true }, 12_000);
        this.loadedThreads.add(forkId);
        return { reused: false, effectiveThreadId: forkId, forkedFrom: threadId, forkPath: existingFork.forkPath };
      } catch (forkResumeErr) {
        logger.warn('CodexWriter', `Existing continuation fork ${forkId.slice(0, 8)} unavailable (${forkResumeErr.message}); retrying original thread`);
        this.continuationForks.delete(threadId);
      }
    }

    // 1. Check if our single app-server already holds this thread writer
    if (this.loadedThreads.has(threadId)) {
      return { reused: true, effectiveThreadId: threadId, forkedFrom: null };
    }

    // 2. Sync with live `thread/loaded/list` in case it was already loaded earlier
    try {
      const loaded = await this.callRpc('thread/loaded/list', {}, 4000);
      if (Array.isArray(loaded?.data) && loaded.data.includes(threadId)) {
        this.loadedThreads.add(threadId);
        return { reused: true, effectiveThreadId: threadId, forkedFrom: null };
      }
    } catch { /* proceed to resume */ }

    const maxAttempts = 1 + ACTIVE_WRITER_RETRY_DELAYS_MS.length;
    let lastErr = null;

    for (let attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        await this.callRpc('thread/resume', { threadId, excludeTurns: true }, 12_000);
        this.loadedThreads.add(threadId);
        return { reused: false, attempts: attempt, effectiveThreadId: threadId, forkedFrom: null };
      } catch (resumeErr) {
        const msg = String(resumeErr?.message || '');
        if (/no rollout found/i.test(msg)) {
          this.loadedThreads.add(threadId);
          return { reused: true, attempts: attempt, effectiveThreadId: threadId, forkedFrom: null };
        }

        lastErr = resumeErr;
        if (ACTIVE_WRITER_RE.test(msg)) {
          const retryDelay = ACTIVE_WRITER_RETRY_DELAYS_MS[attempt - 1];
          logger.warn(
            'CodexWriter',
            `Active writer conflict on thread ${threadId} (attempt ${attempt}/${maxAttempts}, rpcCode=${resumeErr.rpcCode ?? 'n/a'}): ${msg}`,
            {
              threadId,
              method: 'thread/resume',
              attempt,
              maxAttempts,
              nextRetryMs: retryDelay ?? 0,
              recentStderr: this.recentStderr.slice(-3),
            }
          );
          if (retryDelay !== undefined) {
            await sleep(retryDelay);
            continue;
          }

          // External writer (e.g. OpenAI Codex Desktop App) holds the OS lock ->
          // seamlessly fork a writable continuation preserving 100% history, cwd & model!
          if (allowForkOnExternalLock) {
            try {
              return await this._forkLockedThread(threadId);
            } catch (forkErr) {
              logger.error(
                'CodexWriter',
                `Auto-continuation fork failed for locked thread ${threadId}: ${forkErr.message}`,
                { threadId, originalError: msg, recentStderr: this.recentStderr.slice(-5) }
              );
            }
          }

          logger.error(
            'CodexWriter',
            `Exhausted ${maxAttempts} attempts for thread ${threadId} due to external active writer lock. Raw error: ${msg}`,
            {
              threadId,
              rpcCode: resumeErr.rpcCode ?? null,
              lastExitCode: this.lastExitCode,
              recentStderr: this.recentStderr.slice(-5),
            }
          );
          throw new CodexActiveWriterError({
            threadId,
            rawError: msg,
            rpcCode: resumeErr.rpcCode ?? null,
            attempts: attempt,
          });
        }

        logger.error(
          'CodexWriter',
          `thread/resume failed for thread ${threadId} (rpcCode=${resumeErr.rpcCode ?? 'n/a'}): ${msg}`,
          { threadId, recentStderr: this.recentStderr.slice(-3) }
        );
        throw resumeErr;
      }
    }

    throw lastErr || new Error(`Failed to acquire writer for thread ${threadId}`);
  }

  /**
   * Serialized, single-writer message delivery for a Codex thread:
   *  - Reuses the already-loaded writer for consecutive messages on the same thread
   *  - Never spawns a second writer per thread
   *  - When an external process (Codex Desktop App) holds a persistent OS `.lock` on the thread,
   *    automatically creates & reuses a single lossless `thread/fork` continuation so mobile control works immediately!
   */
  async sendMessageToThread(threadId, text, { modelSpec = '', imagePaths = [], allowForkOnExternalLock = true } = {}) {
    const cleanThreadId = String(threadId || '').trim();
    if (!cleanThreadId) {
      throw new Error('缺少 Codex 会话 threadId');
    }

    const readyOk = await this._waitUntilReady(10_000);
    if (!readyOk) {
      throw new Error('Codex app-server 尚未就绪');
    }

    const prevChain = this.threadSendChains.get(cleanThreadId) || Promise.resolve();
    const currentTask = prevChain
      .catch(() => {})
      .then(() =>
        this._sendMessageSerialized(cleanThreadId, text, { modelSpec, imagePaths, allowForkOnExternalLock })
      );

    this.threadSendChains.set(cleanThreadId, currentTask);
    try {
      return await currentTask;
    } finally {
      if (this.threadSendChains.get(cleanThreadId) === currentTask) {
        this.threadSendChains.delete(cleanThreadId);
      }
    }
  }

  async _sendMessageSerialized(threadId, text, { modelSpec = '', imagePaths = [], allowForkOnExternalLock = true } = {}) {
    this._clearIdleTimer(threadId);
    const readyOk = await this._waitUntilReady(10_000);
    if (!readyOk) {
      throw new Error('Codex app-server 尚未就绪');
    }

    const loadInfo = await this._ensureSingleWriterLoaded(threadId, { allowForkOnExternalLock });
    const targetThreadId = loadInfo.effectiveThreadId || threadId;
    this._clearIdleTimer(targetThreadId);

    const inputItems = [];
    for (const imgPath of imagePaths || []) {
      if (imgPath) {
        inputItems.push({
          type: 'localImage',
          path: String(imgPath),
        });
      }
    }
    if (String(text || '').trim()) {
      inputItems.push({
        type: 'text',
        text: String(text),
        text_elements: [],
      });
    }

    const turnParams = {
      threadId: targetThreadId,
      input: inputItems,
    };
    const cleanSpec = String(modelSpec || '').trim();
    if (cleanSpec) {
      const [baseModel, effort] = cleanSpec.split('#');
      if (baseModel) turnParams.model = baseModel;
      if (effort) turnParams.effort = effort;
    }

    try {
      const turnRes = await this.callRpc('turn/start', turnParams, 15_000);
      const turnId = turnRes?.turn?.id || turnRes?.turnId || 'active';
      this.activeTurns.set(targetThreadId, turnId);
      logger.info(
        'Codex',
        `Started new turn on Codex thread ${targetThreadId.slice(0, 8)} (requested=${threadId.slice(0, 8)}, forked=${Boolean(loadInfo.forkedFrom)}, model=${cleanSpec || 'keep'}, writerReused=${Boolean(loadInfo.reused)})`
      );
      return {
        ...turnRes,
        effectiveThreadId: targetThreadId,
        forkedFrom: loadInfo.forkedFrom || null,
      };
    } catch (turnErr) {
      const msg = String(turnErr?.message || '');
      logger.error(
        'CodexWriter',
        `turn/start failed on thread ${targetThreadId} (rpcCode=${turnErr.rpcCode ?? 'n/a'}): ${msg}`,
        { threadId: targetThreadId, requestedThreadId: threadId, model: cleanSpec || 'keep', recentStderr: this.recentStderr.slice(-3) }
      );
      if (ACTIVE_WRITER_RE.test(msg)) {
        throw new CodexActiveWriterError({
          threadId: targetThreadId,
          rawError: msg,
          rpcCode: turnErr.rpcCode ?? null,
          attempts: 1,
        });
      }
      throw turnErr;
    }
  }
}
