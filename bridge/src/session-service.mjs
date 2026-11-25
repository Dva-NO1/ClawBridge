/**
 * ClawBridge Unified Session, Live Stream, Subagent & Artifact Service
 *
 * Covers Spec 3.2, 4, 5.1, 6:
 *   - Unified session list across Antigravity (SQLite + brain transcripts + optional CDP) and Codex (.codex/sessions)
 *   - Real-time status updates (<= 1.5s latency) pushed via `session.list`
 *   - Real-time session detail & incremental streaming (`session.open` -> `session.stream`)
 *   - Child subagent status tracking (`parent_conversation_id` + `invoke_subagent` metadata)
 *   - Artifact discovery (images, markdown/docs, code diffs from write_file/edit_file) + preview & chunked download
 */

import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { EventEmitter } from 'node:events';
import { DatabaseSync } from 'node:sqlite';
import { logger } from './logger.mjs';
import { extractStructured } from './antigravity-dom.mjs';
import { findEditorTarget, connectToTarget } from './cdp-client.mjs';
import {
  discoverActiveAntigravityHub,
  MOBILE_USER_PROMPT_PREFIX,
  LEGACY_MOBILE_USER_PROMPT_PREFIX,
  fetchAntigravityModelCatalog,
  getCachedAntigravityModels,
  getAntigravityStateDefaultModelId,
  resolveAntigravityTranscriptModel,
} from './antigravity-executor.mjs';
import { getSessionModelChoice } from './claw-config.mjs';

const LIST_POLL_MS = 1200;
const STREAM_POLL_MS = 500;
const CHUNK_RAW_BYTES = 256 * 1024; // 256 KB per download chunk
const MAX_DOWNLOAD_BYTES = 64 * 1024 * 1024; // 64 MB cap (supports APK / ZIP / PDF / HD images)

function normalizeLocalFilePath(rawInput) {
  let s = String(rawInput || '').trim();
  if (!s) return '';
  if (s.toLowerCase().startsWith('file:///')) {
    s = s.slice(8);
  } else if (s.toLowerCase().startsWith('file://')) {
    s = s.slice(7);
  }
  try {
    s = decodeURIComponent(s);
  } catch { /* ignore */ }
  // Strip markdown line anchors such as #L10-L25
  s = s.replace(/#L\d+(?:-L?\d+)?$/i, '');
  if (/^\/[A-Za-z]:\//.test(s)) {
    s = s.slice(1);
  }
  return path.resolve(s);
}

function isSensitiveHostPath(absPath) {
  const norm = String(absPath || '').replace(/\\/g, '/').toLowerCase();
  if (!norm) return true;
  if (
    norm.includes('/.ssh/') ||
    norm.includes('/.antigravity_tools/') ||
    norm.includes('/.gemini/antigravity/daemon/') ||
    norm.includes('/appdata/roaming/clawbridge/config.json') ||
    norm.includes('/appdata/roaming/clawbridge/ipc.token') ||
    norm.includes('/.codex/auth') ||
    norm.includes('/windows/system32/config/') ||
    /\.(jks|keystore|pem|key|pfx|p12)$/i.test(norm)
  ) {
    return true;
  }
  return false;
}

function getGeminiAntigravityDir() {
  const home = process.env.USERPROFILE || process.env.HOME || '.';
  return path.join(home, '.gemini', 'antigravity');
}

function getCodexSessionsDir() {
  const home = process.env.USERPROFILE || process.env.HOME || '.';
  return path.join(home, '.codex', 'sessions');
}

function decodeWorkspaceInfo(workspaceUrisRaw) {
  try {
    const arr = JSON.parse(workspaceUrisRaw || '[]');
    if (Array.isArray(arr) && arr.length > 0 && typeof arr[0] === 'string') {
      let u = decodeURIComponent(arr[0]);
      u = u.replace(/^file:\/\/\//i, '').replace(/\\/g, '/');
      const parts = u.split('/').filter(Boolean);
      const project = parts.length > 0 ? parts[parts.length - 1] : '默认工作区';
      return { project, workspacePath: u };
    }
  } catch { /* ignore */ }
  return { project: '未分组对话', workspacePath: '' };
}

function cleanUserPrompt(raw) {
  if (!raw || typeof raw !== 'string') return '';
  const m = raw.match(/<USER_REQUEST>([\s\S]*?)<\/USER_REQUEST>/i);
  const extracted = m ? m[1] : raw;
  return extracted
    .replace(/<ADDITIONAL_METADATA>[\s\S]*?<\/ADDITIONAL_METADATA>/gi, '')
    .replace(/<USER_SETTINGS_CHANGE>[\s\S]*?<\/USER_SETTINGS_CHANGE>/gi, '')
    .replace(/<SYSTEM_MESSAGE>[\s\S]*?<\/SYSTEM_MESSAGE>/gi, '')
    .trim();
}

function isCodexSystemWrapper(text) {
  const t = (text || '').trim();
  if (!t) return true;
  return (
    t.startsWith('# AGENTS.md') ||
    t.startsWith('<external_codex_') ||
    t.startsWith('<environment_context>') ||
    t.startsWith('<permissions') ||
    t.startsWith('<app-context>') ||
    t.startsWith('<multi_agent_') ||
    t.startsWith('<codex_apps_')
  );
}

function guessMimeType(fileName) {
  const ext = path.extname(fileName).toLowerCase();
  switch (ext) {
    case '.png': return 'image/png';
    case '.jpg':
    case '.jpeg': return 'image/jpeg';
    case '.webp': return 'image/webp';
    case '.gif': return 'image/gif';
    case '.md': return 'text/markdown';
    case '.html': return 'text/html';
    case '.json': return 'application/json';
    case '.pdf': return 'application/pdf';
    case '.apk': return 'application/vnd.android.package-archive';
    case '.zip': return 'application/zip';
    case '.7z': return 'application/x-7z-compressed';
    case '.rar': return 'application/vnd.rar';
    case '.csv': return 'text/csv';
    case '.mp4': return 'video/mp4';
    case '.mp3': return 'audio/mpeg';
    case '.docx': return 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';
    case '.xlsx': return 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
    case '.pptx': return 'application/vnd.openxmlformats-officedocument.presentationml.presentation';
    case '.txt':
    case '.log': return 'text/plain';
    case '.kt':
    case '.js':
    case '.mjs':
    case '.ts':
    case '.py':
    case '.sh':
    case '.ps1': return 'text/plain';
    default: return 'application/octet-stream';
  }
}

function classifyArtifactKind(fileName) {
  const ext = path.extname(fileName).toLowerCase();
  if (['.png', '.jpg', '.jpeg', '.webp', '.gif'].includes(ext)) return 'image';
  if (ext === '.md') return 'markdown';
  return 'file';
}

function buildCompactDiff(filePath, targetStr, replacementStr, isWriteFile = false) {
  const base = path.basename(filePath || 'file');
  if (isWriteFile) {
    const lines = String(replacementStr || '').split(/\r?\n/);
    const previewLines = lines.slice(0, 80).map((l) => `+ ${l}`);
    if (lines.length > 80) previewLines.push(`... (共 ${lines.length} 行)`);
    return {
      addLines: lines.length,
      delLines: 0,
      diffText: `--- /dev/null\n+++ b/${base}\n@@ -0,0 +1,${lines.length} @@\n` + previewLines.join('\n'),
    };
  }
  const oldLines = String(targetStr || '').split(/\r?\n/);
  const newLines = String(replacementStr || '').split(/\r?\n/);
  const out = [`--- a/${base}`, `+++ b/${base}`, `@@ -1,${oldLines.length} +1,${newLines.length} @@`];
  for (const l of oldLines.slice(0, 50)) out.push(`- ${l}`);
  if (oldLines.length > 50) out.push(`... (省略 ${oldLines.length - 50} 行删除)`);
  for (const l of newLines.slice(0, 60)) out.push(`+ ${l}`);
  if (newLines.length > 60) out.push(`... (省略 ${newLines.length - 60} 行新增)`);
  return {
    addLines: newLines.length,
    delLines: oldLines.length,
    diffText: out.join('\n'),
  };
}

function summarizeToolCall(tc) {
  const name = tc.name || tc.tool_name || 'tool';
  const args = tc.args || tc.arguments || {};
  if (name === 'run_command') {
    return `执行命令: ${args.toolSummary || String(args.CommandLine || '').split(/\r?\n/)[0].slice(0, 60)}`;
  }
  if (name === 'write_file') {
    return `写入文件: ${path.basename(String(args.Path || ''))}`;
  }
  if (name === 'edit_file') {
    return `修改文件: ${path.basename(String(args.Path || ''))}`;
  }
  if (name === 'read_file') {
    return `读取文件: ${path.basename(String(args.Path || ''))}`;
  }
  if (name === 'invoke_subagent') {
    return `调用子代理: ${args.Role || args.TypeName || 'subagent'}`;
  }
  if (name === 'update_steps' || name === 'update_focus') {
    return null;
  }
  return `工具调用: ${name}`;
}

export class SessionService extends EventEmitter {
  constructor({ cdpPort = 9222, codexClient = null } = {}) {
    super();
    this.cdpPort = cdpPort;
    this.codexClient = codexClient;
    this.listTimer = null;
    this.streamTimer = null;
    this.lastListFingerprint = '';

    // Active phone subscription: { sessionId, source, lastSig }
    this.subscription = null;

    // Security whitelist per session: sessionId -> Map<artifactId, { path, name, kind, mimeType, diffText, filePath }>
    this.artifactRegistry = new Map();

    // Codex file metadata cache: filePath -> { mtimeMs, size, summary }
    this.codexCache = new Map();

    // Full transcript line cache for truncated diff/content lookups: `${sessionId}:${lineIdx}` -> parsedStep
    this.fullLineCache = new Map();
    // Whole-file line array cache for transcript_full.jsonl keyed by path -> { mtimeMs, size, lines }
    this.fullFileLinesCache = new Map();

    this.cdpStatus = { available: false, browser: null, note: '' };
  }

  start() {
    this._checkCdpStatus().catch(() => {});
    fetchAntigravityModelCatalog({ forceRefresh: true }).catch(() => {});
    if (this.listTimer) clearInterval(this.listTimer);
    this.listTimer = setInterval(() => {
      this._pollSessionListChanges();
    }, LIST_POLL_MS);
    this.listTimer.unref?.();

    if (this.streamTimer) clearInterval(this.streamTimer);
    this.streamTimer = setInterval(() => {
      this._pollSubscribedSession().catch(() => {});
    }, STREAM_POLL_MS);
    this.streamTimer.unref?.();
  }

  stop() {
    if (this.listTimer) clearInterval(this.listTimer);
    if (this.streamTimer) clearInterval(this.streamTimer);
    this.listTimer = null;
    this.streamTimer = null;
  }

  async _checkCdpStatus() {
    const hub = discoverActiveAntigravityHub();
    if (hub.ok) {
      await fetchAntigravityModelCatalog().catch(() => {});
    }
    try {
      const res = await fetch(`http://127.0.0.1:${this.cdpPort}/json/version`, {
        signal: AbortSignal.timeout(1200),
      });
      if (res.ok) {
        const info = await res.json();
        this.cdpStatus = {
          available: true,
          hubAvailable: hub.ok,
          hubPort: hub.httpPort || null,
          browser: info.Browser || 'Antigravity',
          note: `agentapi Hub(${hub.httpPort || 'n/a'}) + CDP 9222 双通道已就绪`,
        };
        return this.cdpStatus;
      }
    } catch { /* not open on 9222 */ }

    this.cdpStatus = {
      available: false,
      hubAvailable: hub.ok,
      hubPort: hub.httpPort || null,
      browser: null,
      note: hub.ok
        ? `agentapi Hub (端口 ${hub.httpPort}) 双向投递与实时 Transcript 镜像已就绪`
        : `警告：${hub.reason}`,
    };
    return this.cdpStatus;
  }

  listSessions() {
    const agSessions = this._readAntigravitySessions();
    const codexSessions = this._readCodexSessions();
    const rawMerged = [...agSessions, ...codexSessions].sort((a, b) => b.updatedAt - a.updatedAt);
    const seen = new Set();
    const deduped = [];
    for (const s of rawMerged) {
      const key = `${s.source}:${s.id}`;
      if (!s.id || seen.has(key)) continue;
      seen.add(key);
      deduped.push(s);
    }
    return {
      sessions: deduped.slice(0, 80),
      cdpAvailable: this.cdpStatus.available,
      cdpNote: this.cdpStatus.note,
      ts: Date.now(),
    };
  }

  subscribeSession(sessionId, source = 'antigravity') {
    const detail = this.getSessionDetail(sessionId, source);
    const sig = this._computeDetailSignature(detail);
    this.subscription = { sessionId, source, lastSig: sig, lastLiveText: '' };
    return detail;
  }

  unsubscribeSession() {
    this.subscription = null;
  }

  getSessionDetail(sessionId, source = 'antigravity') {
    if (source === 'codex') {
      return this._readCodexSessionDetail(sessionId);
    }
    return this._readAntigravitySessionDetail(sessionId);
  }

  /**
   * Read artifact preview or stream chunked download frames via callback
   */
  async handleArtifactRequest({ sessionId, artifactId, mode = 'preview' }, sendFrame) {
    // Ensure registry is populated for this session
    if (!this.artifactRegistry.has(sessionId)) {
      this.getSessionDetail(sessionId, 'antigravity');
    }
    const sessionMap = this.artifactRegistry.get(sessionId);
    const meta = sessionMap?.get(artifactId);
    if (!meta) {
      sendFrame('artifact.data', {
        ok: false,
        artifactId,
        error: '未找到该产物或无权访问',
      });
      return;
    }

    if (mode === 'preview') {
      if (meta.kind === 'diff') {
        sendFrame('artifact.data', {
          ok: true,
          mode: 'preview',
          sessionId,
          artifactId,
          name: meta.name,
          kind: 'diff',
          mimeType: 'text/x-diff',
          filePath: meta.filePath || '',
          addLines: meta.addLines || 0,
          delLines: meta.delLines || 0,
          textContent: meta.diffText || '',
          canDownloadFile: Boolean(meta.path && fs.existsSync(meta.path)),
        });
        return;
      }

      if (!meta.path || !fs.existsSync(meta.path)) {
        sendFrame('artifact.data', { ok: false, artifactId, error: '原文件已不存在' });
        return;
      }

      const st = fs.statSync(meta.path);
      if (meta.kind === 'image') {
        if (st.size > 6 * 1024 * 1024) {
          sendFrame('artifact.data', {
            ok: false,
            artifactId,
            error: '图片超过 6MB，请直接点击下载到手机查看',
          });
          return;
        }
        const buf = fs.readFileSync(meta.path);
        sendFrame('artifact.data', {
          ok: true,
          mode: 'preview',
          sessionId,
          artifactId,
          name: meta.name,
          kind: 'image',
          mimeType: meta.mimeType,
          sizeBytes: st.size,
          base64Data: buf.toString('base64'),
        });
        return;
      }

      // Markdown or text preview (read first 128 KB)
      const fd = fs.openSync(meta.path, 'r');
      const readLen = Math.min(st.size, 128 * 1024);
      const buf = Buffer.alloc(readLen);
      fs.readSync(fd, buf, 0, readLen, 0);
      fs.closeSync(fd);

      sendFrame('artifact.data', {
        ok: true,
        mode: 'preview',
        sessionId,
        artifactId,
        name: meta.name,
        kind: meta.kind,
        mimeType: meta.mimeType,
        sizeBytes: st.size,
        textContent: buf.toString('utf-8'),
      });
      return;
    }

    // mode === 'download'
    let targetPath = meta.path;
    let downloadName = meta.name;
    let mimeType = meta.mimeType;
    let rawBuffer = null;

    if (targetPath && fs.existsSync(targetPath)) {
      const st = fs.statSync(targetPath);
      if (st.size > MAX_DOWNLOAD_BYTES) {
        sendFrame('artifact.data', {
          ok: false,
          artifactId,
          error: `文件过大 (${(st.size / 1048576).toFixed(1)} MB > 64 MB 上限)`,
        });
        return;
      }
      rawBuffer = fs.readFileSync(targetPath);
    } else if (meta.kind === 'diff' && meta.diffText) {
      rawBuffer = Buffer.from(meta.diffText, 'utf-8');
      downloadName = `${meta.name}.patch`;
      mimeType = 'text/plain';
    } else {
      sendFrame('artifact.data', { ok: false, artifactId, error: '文件不存在，无法下载' });
      return;
    }

    await this._streamBufferAsChunks({
      rawBuffer,
      sessionId,
      artifactId,
      downloadName,
      mimeType,
      sendFrame,
    });
  }

  async handleDirectFileDownloadRequest({ sessionId = '', filePath = '' }, sendFrame) {
    const resolved = normalizeLocalFilePath(filePath);
    const pseudoId = 'direct-' + crypto.createHash('sha1').update(resolved || 'empty').digest('hex').slice(0, 12);

    if (!resolved || isSensitiveHostPath(resolved)) {
      sendFrame('artifact.data', {
        ok: false,
        artifactId: pseudoId,
        error: '受保护路径或无效文件路径，拒绝下载',
      });
      return;
    }

    if (!fs.existsSync(resolved)) {
      sendFrame('artifact.data', {
        ok: false,
        artifactId: pseudoId,
        error: `桌面端未找到该文件：${path.basename(resolved)}`,
      });
      return;
    }

    const st = fs.statSync(resolved);
    if (!st.isFile()) {
      sendFrame('artifact.data', {
        ok: false,
        artifactId: pseudoId,
        error: '该路径是文件夹而非可下载文件',
      });
      return;
    }

    if (st.size > MAX_DOWNLOAD_BYTES) {
      sendFrame('artifact.data', {
        ok: false,
        artifactId: pseudoId,
        error: `文件过大 (${(st.size / 1048576).toFixed(1)} MB > 64 MB 上限)`,
      });
      return;
    }

    const rawBuffer = fs.readFileSync(resolved);
    const downloadName = path.basename(resolved);
    const mimeType = guessMimeType(downloadName);
    logger.info(
      'FileDownload',
      `Streaming direct file "${downloadName}" (${(rawBuffer.length / 1024).toFixed(1)} KB) to phone`
    );

    await this._streamBufferAsChunks({
      rawBuffer,
      sessionId,
      artifactId: pseudoId,
      downloadName,
      mimeType,
      sendFrame,
    });
  }

  async _streamBufferAsChunks({ rawBuffer, sessionId, artifactId, downloadName, mimeType, sendFrame }) {
    const totalChunks = Math.max(1, Math.ceil(rawBuffer.length / CHUNK_RAW_BYTES));
    const transferId = crypto.randomUUID();

    for (let i = 0; i < totalChunks; i++) {
      const slice = rawBuffer.subarray(i * CHUNK_RAW_BYTES, (i + 1) * CHUNK_RAW_BYTES);
      sendFrame('artifact.chunk', {
        ok: true,
        transferId,
        sessionId,
        artifactId,
        name: downloadName,
        mimeType,
        sizeBytes: rawBuffer.length,
        chunkIndex: i,
        totalChunks,
        base64Chunk: slice.toString('base64'),
      });
      if (totalChunks > 1 && i < totalChunks - 1) {
        await new Promise((r) => setTimeout(r, 6));
      }
    }
  }

  // ─── Internal Pollers ──────────────────────────────────────────────────────

  _pollSessionListChanges() {
    try {
      const res = this.listSessions();
      const fp = res.sessions
        .slice(0, 30)
        .map((s) => `${s.id}:${s.status}:${s.updatedAt}:${s.subagentCount}:${(s.preview || '').slice(0, 30)}`)
        .join('|');
      if (fp !== this.lastListFingerprint) {
        this.lastListFingerprint = fp;
        this.emit('sessions.changed', res);
      }
    } catch (err) {
      logger.warn('SessionService', `List poll error: ${err.message}`);
    }
  }

  async _pollSubscribedSession() {
    if (!this.subscription) return;
    const { sessionId, source } = this.subscription;

    const detail = this.getSessionDetail(sessionId, source);
    const sig = this._computeDetailSignature(detail);
    if (sig !== this.subscription.lastSig) {
      this.subscription.lastSig = sig;
      this.emit('session.stream', {
        mode: 'snapshot',
        ...detail,
      });
    }

    // Also poll live CDP DOM streaming if CDP is active for Antigravity
    if (source === 'antigravity' && this.cdpStatus.available) {
      try {
        const target = await findEditorTarget();
        if (target) {
          const client = await connectToTarget(target);
          try {
            const domData = await extractStructured(client);
            if (domData?.found && Array.isArray(domData.messages) && domData.messages.length > 0) {
              const lastDomMsg = domData.messages[domData.messages.length - 1];
              if (lastDomMsg.role === 'agent' && (lastDomMsg.working || lastDomMsg.text)) {
                const liveKey = `${lastDomMsg.working}:${lastDomMsg.worked}:${(lastDomMsg.text || '').length}`;
                if (liveKey !== this.subscription.lastLiveText) {
                  this.subscription.lastLiveText = liveKey;
                  this.emit('session.stream', {
                    mode: 'live_tail',
                    sessionId,
                    source,
                    liveMessage: {
                      id: 'cdp-live-tail',
                      role: 'agent',
                      text: lastDomMsg.text || '',
                      thinkingSummary: lastDomMsg.worked || '',
                      toolSteps: (lastDomMsg.activity || []).map((a) => a.text),
                      status: lastDomMsg.working ? 'streaming' : 'done',
                      ts: Date.now(),
                    },
                  });
                }
              }
            }
          } finally {
            client.close();
          }
        }
      } catch {
        // CDP transiently unavailable
      }
    }
  }

  _computeDetailSignature(detail) {
    const lastMsg = detail.messages?.[detail.messages.length - 1];
    return [
      detail.session?.status,
      detail.session?.currentModelId || '',
      detail.session?.currentModelLabel || '',
      detail.messages?.length || 0,
      lastMsg?.id || '',
      (lastMsg?.text || '').length,
      (lastMsg?.toolSteps || []).length,
      lastMsg?.status || '',
      (detail.subagents || []).map((s) => `${s.id}:${s.status}:${s.stepCount}`).join(','),
      (detail.artifacts || []).length,
    ].join('|');
  }

  // ─── Antigravity Readers ───────────────────────────────────────────────────

  _readAntigravitySessions() {
    const dbPath = path.join(getGeminiAntigravityDir(), 'conversation_summaries.db');
    if (!fs.existsSync(dbPath)) return [];

    let rows = [];
    let childRows = [];
    let db = null;
    try {
      db = new DatabaseSync(dbPath, { open: true, readOnly: true });
      rows = db
        .prepare(
          `SELECT conversation_id, title, preview, step_count, last_modified_time,
                  workspace_uris, status, not_fully_idle, killed
             FROM conversation_summaries
            WHERE parent_conversation_id = '' AND killed = 0
            ORDER BY last_modified_time DESC
            LIMIT 60`
        )
        .all();

      childRows = db
        .prepare(
          `SELECT conversation_id, parent_conversation_id, agent_name, status, not_fully_idle, step_count, last_modified_time
             FROM conversation_summaries
            WHERE parent_conversation_id != ''`
        )
        .all();
    } catch (err) {
      logger.warn('SessionService', `SQLite read warning: ${err.message}`);
      return [];
    } finally {
      try { db?.close(); } catch { /* ignore */ }
    }

    const childMap = new Map();
    for (const c of childRows) {
      const list = childMap.get(c.parent_conversation_id) || [];
      list.push(c);
      childMap.set(c.parent_conversation_id, list);
    }

    const brainRoot = path.join(getGeminiAntigravityDir(), 'brain');

    return rows.map((r) => {
      const cid = r.conversation_id;
      const { project, workspacePath } = decodeWorkspaceInfo(r.workspace_uris);
      const children = childMap.get(cid) || [];
      const runningSubagents = children.filter(
        (c) => c.status === 'CASCADE_RUN_STATUS_RUNNING' || Number(c.not_fully_idle) === 1
      ).length;

      let updatedAt = Date.parse(r.last_modified_time) || Date.now();
      const trPath = path.join(brainRoot, cid, '.system_generated', 'logs', 'transcript.jsonl');
      try {
        if (fs.existsSync(trPath)) {
          const st = fs.statSync(trPath);
          if (st.mtimeMs > updatedAt) updatedAt = Math.floor(st.mtimeMs);
        }
      } catch { /* ignore */ }

      const rawStatus = String(r.status || '');
      let mappedStatus = 'done';
      if (rawStatus === 'CASCADE_RUN_STATUS_RUNNING' || Number(r.not_fully_idle) === 1 || runningSubagents > 0) {
        mappedStatus = 'running';
      } else if (rawStatus.includes('WAIT')) {
        mappedStatus = 'waiting_approval';
      } else if (rawStatus.includes('ERROR')) {
        mappedStatus = 'error';
      }

      return {
        id: cid,
        source: 'antigravity',
        title: (r.title || '').trim() || '未命名会话',
        project,
        workspacePath,
        preview: cleanUserPrompt(r.preview || '').slice(0, 120) || `共 ${r.step_count || 0} 步`,
        status: mappedStatus,
        stepCount: Number(r.step_count || 0),
        subagentCount: children.length,
        runningSubagentCount: runningSubagents,
        updatedAt,
      };
    });
  }

  _readFullStepLine(sessionId, fullPath, zeroBasedLineIdx) {
    try {
      if (!fs.existsSync(fullPath)) return null;
      const st = fs.statSync(fullPath);
      const sig = `${st.mtimeMs}:${st.size}`;
      const cacheKey = `${sessionId}:${sig}:${zeroBasedLineIdx}`;
      if (this.fullLineCache.has(cacheKey)) {
        return this.fullLineCache.get(cacheKey);
      }

      let fileEntry = this.fullFileLinesCache.get(fullPath);
      if (!fileEntry || fileEntry.mtimeMs !== st.mtimeMs || fileEntry.size !== st.size) {
        const lines = fs.readFileSync(fullPath, 'utf-8').split(/\r?\n/);
        if (this.fullFileLinesCache.size >= 4) {
          this.fullFileLinesCache.clear();
        }
        fileEntry = { mtimeMs: st.mtimeMs, size: st.size, lines };
        this.fullFileLinesCache.set(fullPath, fileEntry);
      }

      const raw = fileEntry.lines[zeroBasedLineIdx];
      if (!raw) return null;
      const parsed = JSON.parse(raw);
      if (this.fullLineCache.size > 400) {
        this.fullLineCache.clear();
      }
      this.fullLineCache.set(cacheKey, parsed);
      return parsed;
    } catch {
      return null;
    }
  }

  _readAntigravitySessionDetail(sessionId) {
    const allMeta = this._readAntigravitySessions();
    const sessionMeta = allMeta.find((s) => s.id === sessionId) || {
      id: sessionId,
      source: 'antigravity',
      title: sessionId.slice(0, 8),
      project: 'Antigravity',
      workspacePath: '',
      status: 'done',
      updatedAt: Date.now(),
    };

    const brainDir = path.join(getGeminiAntigravityDir(), 'brain', sessionId);
    const trPath = path.join(brainDir, '.system_generated', 'logs', 'transcript.jsonl');
    const trFullPath = path.join(brainDir, '.system_generated', 'logs', 'transcript_full.jsonl');

    const messages = [];
    const subagentRoleHints = new Map();
    const diffArtifacts = new Map(); // filePath -> artifact entry
    let lastTranscriptModelSlug = '';

    if (fs.existsSync(trPath)) {
      const rawLines = fs.readFileSync(trPath, 'utf-8').split(/\r?\n/).filter(Boolean);
      let currentAgentTurn = null;

      rawLines.forEach((line, idx) => {
        let step;
        try {
          step = JSON.parse(line);
        } catch {
          return;
        }

        const ts = step.created_at ? Date.parse(step.created_at) || Date.now() : Date.now();
        const trunc = Array.isArray(step.truncated_fields) ? step.truncated_fields : [];

        if (step.source === 'USER_EXPLICIT' && step.type === 'USER_INPUT') {
          currentAgentTurn = null;
          let rawContent = step.content || '';
          if (trunc.includes('content')) {
            const full = this._readFullStepLine(sessionId, trFullPath, idx);
            if (full?.content) rawContent = full.content;
          }
          let cleanText = cleanUserPrompt(rawContent);
          if (Array.isArray(step.media) && step.media.length > 0) {
            const mediaLinks = step.media
              .filter((m) => m?.uri)
              .map((m) => `[🖼️ 图片 ${path.basename(String(m.uri))}](file:///${String(m.uri).replace(/\\/g, '/')})`)
              .join('\n');
            if (mediaLinks) {
              cleanText = cleanText ? `${cleanText}\n\n${mediaLinks}` : mediaLinks;
            }
          }
          if (cleanText) {
            messages.push({
              id: `step-${step.step_index ?? idx}`,
              role: 'user',
              text: cleanText,
              thinkingSummary: '',
              toolSteps: [],
              status: 'done',
              ts,
            });
          }
          return;
        }

        // Messages sent from ClawBridge Mobile via official `agentapi send-message`
        if (step.source === 'SYSTEM' && step.type === 'SYSTEM_MESSAGE') {
          let rawSys = step.content || '';
          if (
            trunc.includes('content') ||
            rawSys.includes(MOBILE_USER_PROMPT_PREFIX.slice(0, 8)) ||
            rawSys.includes(LEGACY_MOBILE_USER_PROMPT_PREFIX.slice(0, 8))
          ) {
            const full = this._readFullStepLine(sessionId, trFullPath, idx);
            if (full?.content) rawSys = full.content;
          }
          const activePrefix = rawSys.includes(MOBILE_USER_PROMPT_PREFIX)
            ? MOBILE_USER_PROMPT_PREFIX
            : rawSys.includes(LEGACY_MOBILE_USER_PROMPT_PREFIX)
            ? LEGACY_MOBILE_USER_PROMPT_PREFIX
            : '';
          if (activePrefix) {
            const prefixIdx = rawSys.indexOf(activePrefix);
            currentAgentTurn = null;
            const extracted = rawSys
              .slice(prefixIdx + activePrefix.length)
              .replace(/<\/SYSTEM_MESSAGE>[\s\S]*$/i, '')
              .trim();
            if (extracted) {
              messages.push({
                id: `step-${step.step_index ?? idx}`,
                role: 'user',
                text: extracted,
                thinkingSummary: '',
                toolSteps: [],
                status: 'done',
                ts,
              });
            }
          }
          return;
        }

        if (step.source === 'MODEL' && step.type === 'PLANNER_RESPONSE') {
          if (step.model) {
            lastTranscriptModelSlug = String(step.model);
          }
          let contentText = (step.content || '').trim();
          let toolCalls = Array.isArray(step.tool_calls) ? step.tool_calls : [];

          if (trunc.includes('content') || trunc.includes('tool_calls')) {
            const hasWriteOrEdit = toolCalls.some(
              (t) => t.name === 'write_file' || t.name === 'edit_file' || t.name === 'invoke_subagent'
            );
            if (trunc.includes('content') || hasWriteOrEdit) {
              const full = this._readFullStepLine(sessionId, trFullPath, idx);
              if (full) {
                if (trunc.includes('content') && full.content) contentText = full.content.trim();
                if (Array.isArray(full.tool_calls)) toolCalls = full.tool_calls;
              }
            }
          }

          const toolStepLabels = [];
          for (const tc of toolCalls) {
            const label = summarizeToolCall(tc);
            if (label) toolStepLabels.push(label);

            const args = tc.args || tc.arguments || {};
            if (tc.name === 'invoke_subagent') {
              const key = args.TypeName || 'subagent';
              subagentRoleHints.set(key, args.Role || key);
            } else if ((tc.name === 'write_file' || tc.name === 'edit_file') && args.Path) {
              const fPath = String(args.Path);
              const isWrite = tc.name === 'write_file';
              const diffInfo = buildCompactDiff(
                fPath,
                args.Target || '',
                isWrite ? args.Content || '' : args.Replacement || '',
                isWrite
              );
              const artId = 'diff-' + crypto.createHash('sha1').update(fPath).digest('hex').slice(0, 12);
              diffArtifacts.set(fPath, {
                artifactId: artId,
                name: path.basename(fPath),
                kind: 'diff',
                mimeType: 'text/x-diff',
                path: fPath,
                filePath: fPath.replace(/\\/g, '/'),
                addLines: diffInfo.addLines,
                delLines: diffInfo.delLines,
                diffText: diffInfo.diffText,
                sizeBytes: Buffer.byteLength(diffInfo.diffText, 'utf-8'),
                updatedAt: ts,
              });
            }
          }

          const thinkSnippet = step.thinking
            ? String(step.thinking).replace(/\s+/g, ' ').trim().slice(0, 140)
            : '';

          if (!currentAgentTurn) {
            currentAgentTurn = {
              id: `step-${step.step_index ?? idx}`,
              role: 'agent',
              text: contentText,
              thinkingSummary: thinkSnippet,
              toolSteps: [...toolStepLabels],
              status: step.status === 'ERROR' ? 'error' : 'done',
              ts,
            };
            messages.push(currentAgentTurn);
          } else {
            // Merge consecutive tool-execution planner steps into a cohesive agent turn until prose arrives
            if (toolStepLabels.length > 0) {
              for (const l of toolStepLabels) {
                if (currentAgentTurn.toolSteps.length < 25) {
                  currentAgentTurn.toolSteps.push(l);
                }
              }
            }
            if (thinkSnippet && !currentAgentTurn.thinkingSummary) {
              currentAgentTurn.thinkingSummary = thinkSnippet;
            }
            if (contentText) {
              currentAgentTurn.text = currentAgentTurn.text
                ? `${currentAgentTurn.text}\n\n${contentText}`
                : contentText;
            }
            if (step.status === 'ERROR') currentAgentTurn.status = 'error';
            currentAgentTurn.ts = ts;
          }
        }
      });

      if (sessionMeta.status === 'running' && messages.length > 0) {
        const tail = messages[messages.length - 1];
        if (tail.role === 'agent') {
          tail.status = 'streaming';
        } else {
          messages.push({
            id: 'agent-pending',
            role: 'agent',
            text: '',
            thinkingSummary: 'Agent 正在思考与执行工具...',
            toolSteps: [],
            status: 'streaming',
            ts: Date.now(),
          });
        }
      }
    }

    // Subagents from conversation_summaries.db
    const subagents = this._readChildSubagents(sessionId, subagentRoleHints);

    // Brain directory artifacts + code diff artifacts
    const regMap = new Map();
    const artifactsOut = [];

    const extraDirs = [brainDir, path.join(brainDir, 'scratch')];
    if (sessionMeta.workspacePath) {
      const wsNative = sessionMeta.workspacePath.replace(/\//g, path.sep);
      extraDirs.push(path.join(wsNative, 'dist'));
      extraDirs.push(path.join(wsNative, '手机上传'));
    }

    for (const sDir of extraDirs) {
      if (!fs.existsSync(sDir)) continue;
      const isScratch = sDir.endsWith('scratch');
      let entries = [];
      try { entries = fs.readdirSync(sDir, { withFileTypes: true }); } catch { continue; }
      for (const entry of entries) {
        if (!entry.isFile()) continue;
        if (entry.name.startsWith('.') || entry.name.endsWith('.metadata.json') || entry.name.endsWith('.tmp')) continue;
        if (isScratch && !/\.(png|jpg|jpeg|webp|md|html|pdf|apk|zip)$/i.test(entry.name)) continue;

        const fullPath = path.join(sDir, entry.name);
        if (isSensitiveHostPath(fullPath)) continue;
        const st = fs.statSync(fullPath);
        const kind = classifyArtifactKind(entry.name);
        const mimeType = guessMimeType(entry.name);
        const artId = 'file-' + crypto.createHash('sha1').update(fullPath).digest('hex').slice(0, 12);
        const item = {
          artifactId: artId,
          name: entry.name,
          kind,
          mimeType,
          sizeBytes: st.size,
          updatedAt: Math.floor(st.mtimeMs),
          addLines: 0,
          delLines: 0,
          filePath: fullPath.replace(/\\/g, '/'),
        };
        regMap.set(artId, { ...item, path: fullPath });
        artifactsOut.push(item);
      }
    }

    for (const diffItem of diffArtifacts.values()) {
      regMap.set(diffItem.artifactId, diffItem);
      const { diffText: _omit, path: _p, ...publicMeta } = diffItem;
      artifactsOut.push(publicMeta);
    }

    artifactsOut.sort((a, b) => {
      const rank = (x) => (x.kind === 'diff' ? 2 : /\.(apk|zip|pdf|png|jpg|jpeg|webp)$/i.test(x.name || '') ? 0 : 1);
      const rDiff = rank(a) - rank(b);
      if (rDiff !== 0) return rDiff;
      return (b.updatedAt || 0) - (a.updatedAt || 0);
    });
    this.artifactRegistry.set(sessionId, regMap);

    const availableModels = getCachedAntigravityModels();
    const savedModel = getSessionModelChoice('antigravity', sessionId);
    const resolvedFromTr = resolveAntigravityTranscriptModel(lastTranscriptModelSlug);
    const stateDefaultId = getAntigravityStateDefaultModelId();
    const stateDefaultObj = availableModels.find((m) => m.modelId === stateDefaultId);

    const currentModelId =
      savedModel?.modelId ||
      resolvedFromTr.modelId ||
      stateDefaultObj?.modelId ||
      availableModels[0]?.modelId ||
      '';
    const currentModelLabel =
      savedModel?.modelLabel ||
      availableModels.find((m) => m.modelId === currentModelId)?.label ||
      resolvedFromTr.label ||
      stateDefaultObj?.label ||
      '默认模型';

    return {
      session: {
        ...sessionMeta,
        cdpAvailable: this.cdpStatus.available,
        currentModelId,
        currentModelLabel,
      },
      availableModels,
      messages: messages.slice(-100),
      subagents,
      artifacts: artifactsOut.slice(0, 100),
      ts: Date.now(),
    };
  }

  _readChildSubagents(parentId, roleHints) {
    const dbPath = path.join(getGeminiAntigravityDir(), 'conversation_summaries.db');
    if (!fs.existsSync(dbPath)) return [];
    let db = null;
    try {
      db = new DatabaseSync(dbPath, { open: true, readOnly: true });
      const rows = db
        .prepare(
          `SELECT conversation_id, title, preview, agent_name, status, not_fully_idle, step_count, last_modified_time
             FROM conversation_summaries
            WHERE parent_conversation_id = ?
            ORDER BY last_modified_time DESC`
        )
        .all(parentId);

      return rows.map((r) => {
        const isRunning = r.status === 'CASCADE_RUN_STATUS_RUNNING' || Number(r.not_fully_idle) === 1;
        const agentType = r.agent_name || 'subagent';
        return {
          id: r.conversation_id,
          agentName: agentType,
          role: r.title || roleHints.get(agentType) || `${agentType} 子代理`,
          status: isRunning ? 'running' : r.status?.includes('ERROR') ? 'error' : 'done',
          stepCount: Number(r.step_count || 0),
          preview: cleanUserPrompt(r.preview || '').slice(0, 80),
          updatedAt: Date.parse(r.last_modified_time) || Date.now(),
        };
      });
    } catch {
      return [];
    } finally {
      try { db?.close(); } catch { /* ignore */ }
    }
  }

  // ─── Codex Session Readers (.codex/sessions fallback & read-only mirror) ───

  _collectCodexFiles(dir, out = []) {
    if (!fs.existsSync(dir)) return out;
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, e.name);
      if (e.isDirectory()) {
        this._collectCodexFiles(full, out);
      } else if (e.isFile() && e.name.endsWith('.jsonl')) {
        const st = fs.statSync(full);
        out.push({ full, mtimeMs: st.mtimeMs, size: st.size });
      }
    }
    return out;
  }

  _readCodexSessions() {
    const byId = new Map();

    // 1. Primary: live `codex app-server` thread/list metadata (official names & live status)
    if (this.codexClient && Array.isArray(this.codexClient.cachedThreads)) {
      for (const t of this.codexClient.cachedThreads) {
        if (t?.id) byId.set(t.id, { ...t });
      }
    }

    // 2. Fallback & rollout path enrichment from %USERPROFILE%\.codex\sessions
    const root = getCodexSessionsDir();
    if (fs.existsSync(root)) {
      const files = this._collectCodexFiles(root)
        .sort((a, b) => b.mtimeMs - a.mtimeMs)
        .slice(0, 25);

      for (const f of files) {
        let summary = null;
        const cached = this.codexCache.get(f.full);
        if (cached && cached.mtimeMs === f.mtimeMs && cached.size === f.size) {
          summary = cached.summary;
        } else {
          try {
            summary = this._parseCodexSummary(f.full, f.mtimeMs);
            if (summary) {
              this.codexCache.set(f.full, { mtimeMs: f.mtimeMs, size: f.size, summary, filePath: f.full });
            }
          } catch { /* ignore */ }
        }
        if (summary?.id) {
          const existing = byId.get(summary.id);
          if (!existing) {
            byId.set(summary.id, summary);
          } else {
            // Enrich app-server metadata with newest rollout file path & preview
            if (!existing._filePath && summary._filePath) existing._filePath = summary._filePath;
            if (summary.updatedAt > existing.updatedAt) existing.updatedAt = summary.updatedAt;
            if (summary.preview && (!existing.preview || existing.preview === existing.title)) {
              existing.preview = summary.preview;
            }
          }
        }
      }
    }

    return [...byId.values()];
  }

  _parseCodexSummary(filePath, mtimeMs) {
    const raw = fs.readFileSync(filePath, 'utf-8');
    const lines = raw.split(/\r?\n/).filter(Boolean);
    if (lines.length === 0) return null;

    let id = path.basename(filePath, '.jsonl');
    let cwd = '';
    let firstUserPrompt = '';
    let lastPreview = '';
    let lastEvent = '';

    for (const line of lines) {
      let obj;
      try { obj = JSON.parse(line); } catch { continue; }
      if (obj.type === 'session_meta' && obj.payload) {
        id = obj.payload.id || id;
        cwd = obj.payload.cwd || '';
      } else if (obj.type === 'event_msg' && obj.payload?.type) {
        lastEvent = obj.payload.type;
      } else if (obj.type === 'response_item' && (obj.payload?.type === 'message' || obj.payload?.role)) {
        const role = obj.payload.role;
        const text = (obj.payload.content || [])
          .map((c) => c.text || '')
          .join('\n')
          .trim();
        if (role === 'user' && !isCodexSystemWrapper(text)) {
          if (!firstUserPrompt) firstUserPrompt = text.split(/\r?\n/)[0].slice(0, 60);
          lastPreview = text.slice(0, 100);
        } else if (role === 'assistant' && text) {
          lastPreview = text.replace(/\s+/g, ' ').slice(0, 120);
        }
      }
    }

    const project = cwd ? path.basename(cwd.replace(/[\\/]+$/, '')) : 'Codex CLI';
    const recentlyActive = Date.now() - mtimeMs < 10_000 && lastEvent !== 'task_complete';

    return {
      id,
      source: 'codex',
      title: firstUserPrompt || `Codex 会话 ${id.slice(0, 8)}`,
      project: project || 'Codex',
      workspacePath: cwd.replace(/\\/g, '/'),
      preview: lastPreview || 'Codex 会话记录',
      status: recentlyActive ? 'running' : 'done',
      stepCount: lines.length,
      subagentCount: 0,
      runningSubagentCount: 0,
      updatedAt: Math.floor(mtimeMs),
      _filePath: filePath,
    };
  }

  _parseCodexRolloutMessages(filePath, idPrefix = 'codex') {
    if (!filePath || !fs.existsSync(filePath)) {
      return { forkedFromId: '', messages: [] };
    }
    const lines = fs.readFileSync(filePath, 'utf-8').split(/\r?\n/).filter(Boolean);
    const messages = [];
    let pendingTools = [];
    let forkedFromId = '';

    lines.forEach((line, idx) => {
      let obj;
      try { obj = JSON.parse(line); } catch { return; }
      const ts = obj.timestamp ? Date.parse(obj.timestamp) || Date.now() : Date.now();
      const p = obj.payload;
      if (!p) return;

      if (obj.type === 'session_meta' && p.forked_from_id && !forkedFromId) {
        forkedFromId = String(p.forked_from_id);
      } else if (obj.type === 'response_item' && p.type === 'function_call') {
        pendingTools.push(`工具: ${p.name || 'shell'}`);
      } else if (obj.type === 'response_item' && (p.type === 'message' || p.role)) {
        const text = (p.content || []).map((c) => c.text || '').join('\n').trim();
        if (!text) return;
        if (p.role === 'user' && !isCodexSystemWrapper(text)) {
          pendingTools = [];
          messages.push({
            id: `${idPrefix}-${idx}`,
            role: 'user',
            text,
            thinkingSummary: '',
            toolSteps: [],
            status: 'done',
            ts,
          });
        } else if (p.role === 'assistant') {
          messages.push({
            id: `${idPrefix}-${idx}`,
            role: 'agent',
            text,
            thinkingSummary: '',
            toolSteps: pendingTools.slice(0, 20),
            status: 'done',
            ts,
          });
          pendingTools = [];
        }
      }
    });

    return { forkedFromId, messages };
  }

  _resolveCodexFilePath(threadId, codexList, hintPath = '') {
    if (hintPath && fs.existsSync(hintPath)) return hintPath;
    const found = codexList.find((s) => s.id === threadId);
    if (found?._filePath && fs.existsSync(found._filePath)) return found._filePath;
    for (const entry of this.codexCache.values()) {
      if (entry?.summary?.id === threadId && entry.filePath && fs.existsSync(entry.filePath)) {
        return entry.filePath;
      }
    }
    const root = getCodexSessionsDir();
    if (fs.existsSync(root)) {
      const files = this._collectCodexFiles(root);
      const match = files.find((f) => path.basename(f.full).includes(threadId));
      if (match) return match.full;
    }
    return '';
  }

  _readCodexSessionDetail(sessionId) {
    const codexList = this._readCodexSessions();
    const meta = codexList.find((s) => s.id === sessionId);
    const availableModels = this.codexClient?.cachedModels || [];
    const savedModel = getSessionModelChoice('codex', sessionId);
    const currentModelId =
      savedModel?.modelId ||
      meta?.currentModelId ||
      availableModels.find((m) => m.tag === 'Default')?.modelId ||
      availableModels[0]?.modelId ||
      '';
    const currentModelLabel =
      savedModel?.modelLabel ||
      availableModels.find((m) => m.modelId === currentModelId)?.label ||
      meta?.currentModelLabel ||
      currentModelId ||
      'Codex 默认模型';

    const mainFilePath = this._resolveCodexFilePath(sessionId, codexList, meta?._filePath || '');
    const continuation = this.codexClient?.continuationForks?.get(sessionId) || null;
    const effectiveActiveId = continuation?.forkThreadId || sessionId;

    // Collect full chain: optional parent (if this session is a fork) + this session + optional active continuation fork
    const allMessages = [];
    const visited = new Set();

    const loadWithAncestors = (tid, hintPath, prefix) => {
      if (!tid || visited.has(tid) || visited.size > 4) return;
      visited.add(tid);
      const fp = this._resolveCodexFilePath(tid, codexList, hintPath);
      const parsed = this._parseCodexRolloutMessages(fp, prefix);
      if (parsed.forkedFromId && !visited.has(parsed.forkedFromId)) {
        loadWithAncestors(parsed.forkedFromId, '', `codex-p-${parsed.forkedFromId.slice(0, 8)}`);
      }
      allMessages.push(...parsed.messages);
    };

    loadWithAncestors(sessionId, mainFilePath, `codex-${sessionId.slice(0, 8)}`);

    if (continuation?.forkThreadId && !visited.has(continuation.forkThreadId)) {
      visited.add(continuation.forkThreadId);
      const forkPath = this._resolveCodexFilePath(continuation.forkThreadId, codexList, continuation.forkPath || '');
      const forkParsed = this._parseCodexRolloutMessages(forkPath, `codex-c-${continuation.forkThreadId.slice(0, 8)}`);
      allMessages.push(...forkParsed.messages);
    }

    // Append live in-progress streaming assistant delta if Codex is currently replying
    const live = this.codexClient?.liveStreams?.get(effectiveActiveId);
    const isRunningNow =
      Boolean(this.codexClient?.activeTurns?.has(effectiveActiveId)) ||
      Boolean(this.codexClient?.activeTurns?.has(sessionId)) ||
      meta?.status === 'running';

    if (live && live.streaming && live.text) {
      const last = allMessages[allMessages.length - 1];
      if (!last || last.role !== 'agent' || last.text !== live.text) {
        allMessages.push({
          id: `codex-live-${effectiveActiveId.slice(0, 8)}`,
          role: 'agent',
          text: live.text,
          thinkingSummary: '',
          toolSteps: [],
          status: 'streaming',
          ts: live.ts || Date.now(),
        });
      }
    }

    const baseSession = meta
      ? (() => {
          const { _filePath: _omit, ...rest } = meta;
          return rest;
        })()
      : {
          id: sessionId,
          source: 'codex',
          title: 'Codex 会话',
          project: 'Codex',
          workspacePath: '',
          status: 'done',
          updatedAt: Date.now(),
        };

    return {
      session: {
        ...baseSession,
        status: isRunningNow ? 'running' : baseSession.status || 'done',
        cdpAvailable: false,
        currentModelId,
        currentModelLabel,
      },
      availableModels,
      messages: allMessages.slice(-100),
      subagents: [],
      artifacts: [],
      ts: Date.now(),
    };
  }
}
