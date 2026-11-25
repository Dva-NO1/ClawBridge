/**
 * Antigravity 2.34.0 Bidirectional Action Executor
 *
 * Root-cause fix (v1.3.1):
 *   - Dynamically discovers active Antigravity Hub (`httpPort` + `csrfToken`) from
 *     `%USERPROFILE%\.gemini\antigravity\daemon\hub_*.json` (verified by live PID check)
 *     and injects `ANTIGRAVITY_LS_ADDRESS`, `ANTIGRAVITY_CSRF_TOKEN`, `ANTIGRAVITY_APP_DATA_DIR`
 *     when invoking `language_server.exe agentapi`.
 *   - Captures full exitCode, stderr, and stdout JSON error on failure, writing full redacted
 *     diagnostics into Bridge logs and returning the exact failure reason to the mobile App.
 *   - Retains live CDP 9222 injection fallback when CDP is enabled.
 */

import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { logger } from './logger.mjs';
import { findEditorTarget, connectToTarget, injectAndSubmit } from './cdp-client.mjs';
import { buildSwitchConversationScript } from './antigravity-dom.mjs';

export const MOBILE_USER_PROMPT_PREFIX = '[用户本人通过手机端发来指令，请直接按用户请求执行]\n';
export const LEGACY_MOBILE_USER_PROMPT_PREFIX = '[ClawBridge手机端用户消息]\n';

let cachedModelCatalog = {
  fetchedAt: 0,
  models: [],
  defaultModelId: '',
};

const SLUG_TO_MODEL_HINT = [
  { test: /gemini-4-neon-xhigh/i, label: 'Gemini 4 Neon (XHigh)' },
  { test: /gemini-4-neon-high/i, label: 'Gemini 4 Neon (High)' },
  { test: /gemini-4-neon-medium/i, label: 'Gemini 4 Neon (Medium)' },
  { test: /gemini-4-neon-low/i, label: 'Gemini 4 Neon (Low)' },
  { test: /gemini-4-carbon-high/i, label: 'Gemini 4 Carbon (High)' },
  { test: /gemini-4-carbon-medium/i, label: 'Gemini 4 Carbon (Medium)' },
  { test: /gemini-4-carbon-low/i, label: 'Gemini 4 Carbon (Low)' },
  { test: /gemini-4-argon-high/i, label: 'Gemini 4 Argon (High)' },
  { test: /gemini-4-argon-medium/i, label: 'Gemini 4 Argon (Medium)' },
  { test: /gemini-4-argon-low/i, label: 'Gemini 4 Argon (Low)' },
  { test: /gemini-4-hydrogen.*xhigh/i, label: 'Gemini 4 Hydrogen (XHigh)' },
  { test: /gemini-4-hydrogen.*high/i, label: 'Gemini 4 Hydrogen (High)' },
  { test: /gemini-4-hydrogen.*medium/i, label: 'Gemini 4 Hydrogen (Medium)' },
  { test: /gemini-4-hydrogen.*low/i, label: 'Gemini 4 Hydrogen (Low)' },
  { test: /gemini-3\.8-flash.*high/i, label: 'Gemini 3.8 Flash (High)' },
  { test: /gemini-3\.8-flash.*medium/i, label: 'Gemini 3.8 Flash (Medium)' },
  { test: /gemini-3\.8-flash.*low/i, label: 'Gemini 3.8 Flash (Low)' },
  { test: /claude.*opus.*high/i, label: 'Claude Opus 5.5 (High)' },
  { test: /claude.*opus.*medium/i, label: 'Claude Opus 5.5 (Medium)' },
  { test: /claude.*opus.*low/i, label: 'Claude Opus 5.5 (Low)' },
  { test: /claude.*sonnet.*high/i, label: 'Claude Sonnet 5.5 (High)' },
  { test: /claude.*sonnet.*medium/i, label: 'Claude Sonnet 5.5 (Medium)' },
  { test: /claude.*sonnet.*low/i, label: 'Claude Sonnet 5.5 (Low)' },
  { test: /gpt.*120b/i, label: 'GPT-OSS 120B (Medium)' },
];

export function resolveAntigravityTranscriptModel(rawSlug) {
  const slug = String(rawSlug || '').trim();
  if (!slug) return { modelId: '', label: '' };
  const byExactId = cachedModelCatalog.models.find((m) => m.modelId === slug);
  if (byExactId) return { modelId: byExactId.modelId, label: byExactId.label };

  for (const h of SLUG_TO_MODEL_HINT) {
    if (h.test.test(slug)) {
      const matched = cachedModelCatalog.models.find((m) => m.label === h.label);
      return {
        modelId: matched?.modelId || '',
        label: matched?.label || h.label,
      };
    }
  }
  return { modelId: '', label: slug };
}

export function getCachedAntigravityModels() {
  return cachedModelCatalog.models;
}

function getAntigravityHomeDir() {
  const home = process.env.USERPROFILE || process.env.HOME || '.';
  return path.join(home, '.gemini', 'antigravity');
}

function getLanguageServerExePath() {
  const localAppData = process.env.LOCALAPPDATA || path.join(process.env.USERPROFILE || process.env.HOME || '.', 'AppData', 'Local');
  const candidates = [
    process.env.ANTIGRAVITY_LANGUAGE_SERVER_EXE || '',
    path.join(localAppData, 'Programs', 'antigravity', 'resources', 'bin', 'language_server.exe'),
  ].filter(Boolean);
  for (const p of candidates) {
    if (fs.existsSync(p)) return p;
  }
  return candidates[0] || '';
}

function isPidAlive(pid) {
  if (!pid || typeof pid !== 'number') return false;
  try {
    process.kill(pid, 0);
    return true;
  } catch (err) {
    // EPERM on Windows still means the process exists
    return err.code === 'EPERM';
  }
}

/**
 * Discover active Antigravity Hub address & CSRF token from ~/.gemini/antigravity/daemon/hub_*.json
 */
export function discoverActiveAntigravityHub() {
  const daemonDir = path.join(getAntigravityHomeDir(), 'daemon');
  if (!fs.existsSync(daemonDir)) {
    return { ok: false, reason: `未找到 Antigravity daemon 目录 (${daemonDir})，请确认 Antigravity 正在运行` };
  }

  const files = fs
    .readdirSync(daemonDir)
    .filter((f) => f.startsWith('hub_') && f.endsWith('.json'))
    .map((f) => {
      const full = path.join(daemonDir, f);
      let mtimeMs = 0;
      try { mtimeMs = fs.statSync(full).mtimeMs; } catch { /* ignore */ }
      return { name: f, full, mtimeMs };
    })
    .sort((a, b) => b.mtimeMs - a.mtimeMs);

  if (files.length === 0) {
    return { ok: false, reason: '未在 daemon 目录找到任何 hub_*.json，桌面 Antigravity 可能未启动' };
  }

  for (const hf of files) {
    try {
      const data = JSON.parse(fs.readFileSync(hf.full, 'utf-8'));
      if (data?.httpPort && data?.csrfToken && isPidAlive(Number(data.pid))) {
        return {
          ok: true,
          pid: Number(data.pid),
          httpPort: Number(data.httpPort),
          httpsPort: Number(data.httpsPort || 0),
          lsVersion: data.lsVersion || '',
          csrfToken: String(data.csrfToken),
          hubFile: hf.name,
        };
      }
    } catch { /* try next */ }
  }

  return {
    ok: false,
    reason: '检测到历史 hub 文件但对应 Antigravity language_server 进程均未存活，请先在桌面打开 Antigravity',
  };
}

/**
 * Spawn `language_server.exe agentapi ...` and always capture { code, signal, stdout, stderr, spawnError }
 */
function runAgentApiCommand(cliArgs, envOverrides, timeoutMs = 20_000) {
  const exe = getLanguageServerExePath();
  return new Promise((resolve) => {
    if (!fs.existsSync(exe)) {
      resolve({
        ok: false,
        exitCode: -1,
        signal: null,
        stdout: '',
        stderr: `Executable not found: ${exe}`,
      });
      return;
    }

    let stdout = '';
    let stderr = '';
    let timedOut = false;

    const child = spawn(exe, ['agentapi', ...cliArgs], {
      env: { ...process.env, ...envOverrides },
      windowsHide: true,
      stdio: ['ignore', 'pipe', 'pipe'],
    });

    const timer = setTimeout(() => {
      timedOut = true;
      try { child.kill(); } catch { /* ignore */ }
    }, timeoutMs);
    timer.unref?.();

    child.stdout.on('data', (d) => { stdout += d.toString('utf-8'); });
    child.stderr.on('data', (d) => { stderr += d.toString('utf-8'); });

    child.on('error', (err) => {
      clearTimeout(timer);
      resolve({
        ok: false,
        exitCode: -1,
        signal: null,
        stdout,
        stderr: stderr ? `${stderr}\n${err.message}` : err.message,
      });
    });

    child.on('close', (code, signal) => {
      clearTimeout(timer);
      if (timedOut) {
        resolve({
          ok: false,
          exitCode: code ?? -2,
          signal: signal || 'SIGTERM',
          stdout,
          stderr: stderr ? `${stderr}\nTimeout after ${timeoutMs}ms` : `Timeout after ${timeoutMs}ms`,
        });
        return;
      }
      let stdoutJsonErr = null;
      try {
        const j = stdout.trim() ? JSON.parse(stdout.trim()) : null;
        if (j?.error) stdoutJsonErr = j.error;
      } catch { /* ignore */ }

      resolve({
        ok: code === 0 && !stdoutJsonErr,
        exitCode: code ?? -1,
        signal: signal || null,
        stdout,
        stderr,
      });
    });
  });
}

function extractAgentApiFailureDetail(res) {
  const parts = [`exitCode=${res.exitCode}`];
  if (res.signal) parts.push(`signal=${res.signal}`);

  const trimmedErr = (res.stderr || '').trim();
  const trimmedOut = (res.stdout || '').trim();

  // `language_server.exe agentapi` emits JSON `{"error": "..."}` to stdout on failure!
  let parsedOutErr = '';
  if (trimmedOut) {
    try {
      const j = JSON.parse(trimmedOut);
      if (j?.error) parsedOutErr = typeof j.error === 'string' ? j.error : JSON.stringify(j.error);
    } catch {
      parsedOutErr = trimmedOut.slice(0, 240);
    }
  }

  if (trimmedErr) parts.push(`stderr=${trimmedErr.slice(0, 300)}`);
  if (parsedOutErr) parts.push(`detail=${parsedOutErr}`);
  return parts.join(', ');
}

export { runAgentApiCommand, extractAgentApiFailureDetail };

export async function fetchAntigravityModelCatalog({ forceRefresh = false } = {}) {
  if (!forceRefresh && cachedModelCatalog.models.length > 0 && Date.now() - cachedModelCatalog.fetchedAt < 60_000) {
    return cachedModelCatalog;
  }

  const hub = discoverActiveAntigravityHub();
  if (!hub.ok) {
    return cachedModelCatalog;
  }

  try {
    const res = await fetch(
      `http://127.0.0.1:${hub.httpPort}/exa.language_server_pb.LanguageServerService/GetCascadeModelConfigData`,
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Connect-Protocol-Version': '1',
          'x-codeium-csrf-token': hub.csrfToken,
        },
        body: JSON.stringify({
          metadata: { ideName: 'antigravity', extensionName: 'antigravity', locale: 'zh-CN' },
        }),
        signal: AbortSignal.timeout(6000),
      }
    );
    if (!res.ok) {
      throw new Error(`HTTP ${res.status}`);
    }
    const data = await res.json();
    const rawList = Array.isArray(data?.clientModelConfigs) ? data.clientModelConfigs : [];
    const models = rawList
      .map((m) => {
        const modelId = String(m?.modelOrAlias?.model || m?.modelOrAlias?.alias || '').trim();
        const label = String(m?.label || modelId).trim();
        if (!modelId || !label) return null;
        const remFrac = m?.quotaInfo?.remainingFraction;
        const remainingPercent = typeof remFrac === 'number' ? Math.round(Math.max(0, Math.min(1, remFrac)) * 100) : null;
        return {
          modelId,
          label,
          source: 'antigravity',
          tag: String(m?.tagTitle || '').trim(),
          description: String(m?.tagDescription || '').trim(),
          remainingPercent,
          resetTimeIso: m?.quotaInfo?.resetTime || null,
        };
      })
      .filter(Boolean);

    const defaultModelId = String(data?.defaultOverrideModelConfig?.modelOrAlias?.model || models[0]?.modelId || '');
    cachedModelCatalog = {
      fetchedAt: Date.now(),
      models,
      defaultModelId,
    };
  } catch (err) {
    logger.warn('AntigravityExec', `GetCascadeModelConfigData warning: ${err.message}`);
  }

  return cachedModelCatalog;
}

export function getAntigravityStateDefaultModelId() {
  try {
    const statePath = path.join(getAntigravityHomeDir(), 'antigravity_state.pbtxt');
    if (fs.existsSync(statePath)) {
      const txt = fs.readFileSync(statePath, 'utf-8');
      const m = txt.match(/^\s*last_selected_agent_model:\s*([A-Za-z0-9_.-]+)\s*$/m);
      if (m?.[1]) return m[1].trim();
    }
  } catch { /* ignore */ }
  return cachedModelCatalog.defaultModelId || '';
}

export function syncAntigravityLastSelectedModel(modelId) {
  const clean = String(modelId || '').trim();
  if (!/^MODEL_[A-Z0-9_]+$/.test(clean)) return false;
  try {
    const statePath = path.join(getAntigravityHomeDir(), 'antigravity_state.pbtxt');
    if (!fs.existsSync(statePath)) return false;
    const txt = fs.readFileSync(statePath, 'utf-8');
    let next;
    if (/^\s*last_selected_agent_model:\s*[A-Za-z0-9_.-]+\s*$/m.test(txt)) {
      next = txt.replace(
        /^(\s*last_selected_agent_model:\s*)[A-Za-z0-9_.-]+(\s*)$/m,
        `$1${clean}$2`
      );
    } else {
      next = `${txt.trimEnd()}\nlast_selected_agent_model: ${clean}\n`;
    }
    if (next !== txt) {
      fs.writeFileSync(statePath, next, 'utf-8');
    }
    return true;
  } catch (err) {
    logger.warn('AntigravityExec', `Failed updating antigravity_state.pbtxt model: ${err.message}`);
    return false;
  }
}

export async function sendAntigravityMessage(
  sessionId,
  text,
  { cdpAvailable = false, title = '', modelId = '', mediaItems = [] } = {}
) {
  const cleanText = String(text || '').trim();
  const validMedia = Array.isArray(mediaItems)
    ? mediaItems.filter((m) => m?.mimeType && m?.inlineData)
    : [];
  if (!sessionId || (!cleanText && validMedia.length === 0)) {
    throw new Error('sessionId 与消息/图片内容不能同时为空');
  }
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(sessionId)) {
    throw new Error(`无效的 Antigravity 会话 UUID: ${sessionId}`);
  }

  let agentApiErrorSummary = '';
  const cleanModelId = String(modelId || '').trim();
  const effectiveText = cleanText || '请查看我从手机发送的图片。';

  const hub = discoverActiveAntigravityHub();
  if (!hub.ok) {
    agentApiErrorSummary = `Hub 未就绪 (${hub.reason})`;
    logger.warn('AntigravityExec', `Hub discovery failed for session ${sessionId.slice(0, 8)}: ${hub.reason}`);
  } else {
    // 1A. Primary path: ConnectRPC `SendUserCascadeMessage` (native USER_REQUEST + media + live `requestedModel` switch!)
    try {
      const rpcBody = {
        cascadeId: sessionId,
        items: [{ text: effectiveText }],
        metadata: { ideName: 'antigravity', extensionName: 'antigravity', locale: 'zh-CN' },
      };
      if (validMedia.length > 0) {
        rpcBody.media = validMedia.map((m) => ({
          mimeType: String(m.mimeType),
          inlineData: String(m.inlineData),
        }));
      }
      if (cleanModelId) {
        rpcBody.cascadeConfig = {
          plannerConfig: {
            requestedModel: { model: cleanModelId },
          },
        };
      }

      const rpcRes = await fetch(
        `http://127.0.0.1:${hub.httpPort}/exa.language_server_pb.LanguageServerService/SendUserCascadeMessage`,
        {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'Connect-Protocol-Version': '1',
            'x-codeium-csrf-token': hub.csrfToken,
          },
          body: JSON.stringify(rpcBody),
          signal: AbortSignal.timeout(20_000),
        }
      );

      if (rpcRes.ok) {
        if (cleanModelId) syncAntigravityLastSelectedModel(cleanModelId);
        logger.info(
          'AntigravityExec',
          `Delivered native user message to session ${sessionId.slice(0, 8)} via SendUserCascadeMessage (hubPort=${hub.httpPort}, model=${cleanModelId || 'keep'}, media=${validMedia.length})`
        );
        return {
          ok: true,
          channel: 'cascade_rpc',
          hubPort: hub.httpPort,
          appliedModelId: cleanModelId,
          mediaCount: validMedia.length,
        };
      }

      const errBody = (await rpcRes.text()).slice(0, 240);
      logger.warn(
        'AntigravityExec',
        `SendUserCascadeMessage HTTP ${rpcRes.status} on ${sessionId.slice(0, 8)} (${errBody}); falling back to agentapi`
      );
    } catch (rpcErr) {
      logger.warn('AntigravityExec', `SendUserCascadeMessage warning on ${sessionId.slice(0, 8)}: ${rpcErr.message}; trying agentapi`);
    }

    // 1B. Secondary Hub path: `language_server.exe agentapi send-message` (handles mid-turn queuing cleanly)
    const mediaPathRefs = validMedia
      .filter((m) => m.savedPath)
      .map((m) => `@[${m.savedPath}]`)
      .join('\n');
    const fallbackBody = mediaPathRefs ? `${mediaPathRefs}\n${effectiveText}` : effectiveText;
    const wrappedPayload = `${MOBILE_USER_PROMPT_PREFIX}${fallbackBody}`;
    const cliArgs = ['send-message'];
    if (title) cliArgs.push(`--title=${title}`);
    cliArgs.push(sessionId, wrappedPayload);

    const res = await runAgentApiCommand(cliArgs, {
      ANTIGRAVITY_LS_ADDRESS: `localhost:${hub.httpPort}`,
      ANTIGRAVITY_CSRF_TOKEN: hub.csrfToken,
      ANTIGRAVITY_APP_DATA_DIR: getAntigravityHomeDir().replace(/\\/g, '/'),
    });

    if (res.ok) {
      if (cleanModelId) syncAntigravityLastSelectedModel(cleanModelId);
      logger.info(
        'AntigravityExec',
        `Delivered message to session ${sessionId.slice(0, 8)} via agentapi (hubPort=${hub.httpPort}, exitCode=0)`
      );
      return { ok: true, channel: 'agentapi', hubPort: hub.httpPort };
    }

    const detail = extractAgentApiFailureDetail(res);
    agentApiErrorSummary = `agentapi 失败 (${detail})`;
    logger.error('AntigravityExec', `agentapi send-message failed for session ${sessionId.slice(0, 8)}: ${detail}`);
  }

  // 2. Secondary fallback: CDP 9222 UI injection (if Antigravity was started with --remote-debugging-port=9222)
  if (cdpAvailable) {
    try {
      const target = await findEditorTarget();
      if (!target) throw new Error('未找到可用的 Antigravity 编辑器窗口');
      const client = await connectToTarget(target);
      try {
        await client.send('Runtime.evaluate', {
          expression: buildSwitchConversationScript(sessionId),
          returnByValue: true,
        });
        await new Promise((r) => setTimeout(r, 350));
      } finally {
        client.close();
      }
      await injectAndSubmit(cleanText);
      logger.info('AntigravityExec', `Delivered message to session ${sessionId.slice(0, 8)} via CDP 9222 fallback`);
      return { ok: true, channel: 'cdp' };
    } catch (cdpErr) {
      logger.error('AntigravityExec', `CDP fallback also failed: ${cdpErr.message}`);
      throw new Error(`${agentApiErrorSummary}；CDP 9222 兜底亦失败 (${cdpErr.message})`);
    }
  }

  throw new Error(`${agentApiErrorSummary}（且桌面端未启用 CDP 9222 备用通道）`);
}

export async function createAntigravityConversation(promptText, { title = 'ClawBridge 助理会话', model = 'flash' } = {}) {
  const hub = discoverActiveAntigravityHub();
  if (!hub.ok) {
    throw new Error(`无法创建会话：${hub.reason}`);
  }

  const res = await runAgentApiCommand(
    ['new-conversation', `--model=${model}`, `--title=${title}`, String(promptText)],
    {
      ANTIGRAVITY_LS_ADDRESS: `localhost:${hub.httpPort}`,
      ANTIGRAVITY_CSRF_TOKEN: hub.csrfToken,
      ANTIGRAVITY_APP_DATA_DIR: getAntigravityHomeDir().replace(/\\/g, '/'),
    },
    25_000
  );

  if (!res.ok) {
    const detail = extractAgentApiFailureDetail(res);
    logger.error('AntigravityExec', `agentapi new-conversation failed: ${detail}`);
    throw new Error(`创建会话失败 (${detail})`);
  }

  const stdout = res.stdout.trim();
  let conversationId = null;
  try {
    const parsed = JSON.parse(stdout);
    conversationId =
      parsed?.response?.newConversation?.conversationId ||
      parsed?.response?.conversationId ||
      parsed?.conversationId ||
      stdout.match(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i)?.[0] ||
      null;
  } catch {
    conversationId = stdout.match(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i)?.[0] || null;
  }
  logger.info('AntigravityExec', `Created new Antigravity conversation "${title}" -> ${conversationId}`);
  return { ok: true, conversationId, raw: stdout };
}

export async function clickAntigravityXPath(xpath) {
  if (!xpath) return false;
  const target = await findEditorTarget();
  if (!target) return false;
  const client = await connectToTarget(target);
  try {
    const res = await client.send('Runtime.evaluate', {
      expression: `(() => {
        const node = document.evaluate(${JSON.stringify(xpath)}, document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;
        if (!node) return { clicked: false };
        node.click();
        return { clicked: true };
      })()`,
      returnByValue: true,
    });
    return Boolean(res?.result?.value?.clicked);
  } finally {
    client.close();
  }
}
