/**
 * ClawBridge Desktop Daemon Entry Point
 *
 * Manages:
 *   - %APPDATA%\clawbridge\config.json
 *   - Antigravity 2.34.0 startup version check & `agentapi` + CDP 9222 executor
 *   - Codex `app-server` (0.167.0) JSON-RPC stdio client & `.codex/sessions` fallback
 *   - WSS Relay connection, 6-digit pairing code lifecycle, presence & hibernate wake recovery
 *   - Google 5h/7d dual-model quota polling & cache (`quota.get` / `quota.data`)
 *   - Unified session mirror, live streaming, subagents & artifacts (`session.*` / `artifact.*`)
 *   - Bidirectional control: `chat.send`, `quick.command`, `approval.pending` / `approval.respond`
 */

import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { logger } from './logger.mjs';
import {
  loadConfig,
  saveConfig,
  getDefaultDesktopDir,
  getConfigPath,
  getPairingFilePath,
  getLanPairingFilePath,
  getRepairTriggerPath,
  getStatusFilePath,
  getSessionModelChoice,
  saveSessionModelChoice,
} from './claw-config.mjs';
import { inspectAntigravityVersion } from './antigravity-inspector.mjs';
import { PowerMonitor } from './power-monitor.mjs';
import { RelayClient } from './relay-client.mjs';
import { LanServer } from './lan-server.mjs';
import { BridgeTransportRouter } from './transport-router.mjs';
import { GoogleQuotaService } from './google-quota.mjs';
import { MetricsService } from './metrics-service.mjs';
import { CodexAppServerClient } from './codex-client.mjs';
import { SessionService } from './session-service.mjs';
import { NotificationEngine } from './notification-engine.mjs';
import {
  sendAntigravityMessage,
  fetchAntigravityModelCatalog,
  syncAntigravityLastSelectedModel,
  resolveValidAntigravityModel,
  createAntigravityProjectSession,
} from './antigravity-executor.mjs';
import { startLocalControlServer } from './local-ipc.mjs';

const QUICK_COMMAND_TEMPLATES = {
  continue: '继续执行下一步，完成后简要告知结果。',
  start_plan: '按计划开始开工执行，遇到高风险操作前请先请求确认。',
  report_progress: '请先简要汇报当前已完成进度、正在执行的任务与下一步计划。',
};

const RESERVED_WIN_NAMES = /^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$/i;

export function sanitizeProjectName(rawName) {
  const trimmed = String(rawName || '').trim().replace(/[. ]+$/, '');
  if (!trimmed) {
    throw new Error('请输入项目文件夹名称');
  }
  if (trimmed === '.' || trimmed === '..') {
    throw new Error('项目文件夹名称不合法');
  }
  if (trimmed.length > 80) {
    throw new Error('项目文件夹名称不能超过 80 个字符');
  }
  if (/[<>:"/\\|?*\x00-\x1f]/.test(trimmed)) {
    throw new Error('文件夹名称不能包含 \\ / : * ? " < > | 等特殊字符');
  }
  if (RESERVED_WIN_NAMES.test(trimmed)) {
    throw new Error('该名称为 Windows 系统保留名称，请换一个名字');
  }
  return trimmed;
}

export function validateWorkspaceRootDir(rawDir) {
  const input = String(rawDir || '').trim();
  if (!input) {
    throw new Error('默认工作目录路径不能为空');
  }
  const resolved = path.resolve(input);
  const parsed = path.parse(resolved);
  if (resolved === parsed.root) {
    throw new Error('安全保护：不允许直接使用磁盘根目录，请指定具体文件夹（如 C:\\Users\\...\\Desktop）');
  }
  const lower = resolved.toLowerCase().replace(/\//g, '\\');
  const blockedPrefixes = [
    'c:\\windows',
    'c:\\program files',
    'c:\\program files (x86)',
    'c:\\programdata',
    'c:\\$recycle.bin',
  ];
  if (blockedPrefixes.some((p) => lower === p || lower.startsWith(`${p}\\`))) {
    throw new Error('安全保护：禁止将项目工作目录设置在 Windows 系统目录内');
  }
  if (lower.includes('\\.ssh') || lower.includes('\\.antigravity_tools') || lower.includes('\\.gnupg')) {
    throw new Error('安全保护：禁止将项目工作目录设置在敏感凭据目录内');
  }
  fs.mkdirSync(resolved, { recursive: true });
  const st = fs.statSync(resolved);
  if (!st.isDirectory()) {
    throw new Error('目标路径不是有效的文件夹');
  }
  return resolved;
}

const args = new Set(process.argv.slice(2));

if (args.has('--show-pair-code') || args.has('--status')) {
  const statusPath = getStatusFilePath();
  const pairPath = getPairingFilePath();
  const lanPairPath = getLanPairingFilePath();
  if (fs.existsSync(statusPath)) {
    console.log('=== ClawBridge Runtime Status ===');
    console.log(fs.readFileSync(statusPath, 'utf-8'));
  }
  if (fs.existsSync(pairPath)) {
    console.log('=== WAN Relay Pairing Info ===');
    console.log(fs.readFileSync(pairPath, 'utf-8'));
  } else {
    console.log('No pairing-code.json generated yet. Start ClawBridge first.');
  }
  if (fs.existsSync(lanPairPath)) {
    console.log('=== LAN Direct Pairing Info ===');
    console.log(fs.readFileSync(lanPairPath, 'utf-8'));
  }
  process.exit(0);
}

if (args.has('--trigger-repair')) {
  const trigger = getRepairTriggerPath();
  fs.mkdirSync(getConfigPath().replace(/\\[^\\]+$/, ''), { recursive: true });
  fs.writeFileSync(trigger, String(Date.now()), 'utf-8');
  console.log(`Triggered re-pair signal at ${trigger}`);
  process.exit(0);
}

async function main() {
  const config = loadConfig();
  logger.info('Main', `Starting ClawBridge Desktop (config: ${getConfigPath()})`);
  logger.info('Main', `Relay URL: ${config.relayUrl} | Device ID: ${config.deviceId}`);

  await inspectAntigravityVersion(config.antigravityExePath, config.targetAntigravityVersion);

  const powerMonitor = new PowerMonitor();
  powerMonitor.start();

  const codexClient = new CodexAppServerClient();
  codexClient.start().catch((err) => {
    logger.warn('Main', `Codex app-server startup warning: ${err.message}`);
  });

  // Periodically refresh Codex thread metadata & rate limits every 30s
  const codexRefreshTimer = setInterval(() => {
    codexClient.refreshThreadsAndLimits().catch(() => {});
  }, 30_000);
  codexRefreshTimer.unref?.();

  const quotaService = new GoogleQuotaService({
    refreshIntervalSec: config.quotaRefreshIntervalSec || 300,
  });
  quotaService.start();

  const sessionService = new SessionService({
    cdpPort: config.cdpPort || 9222,
    codexClient,
  });
  sessionService.start();

  const metricsService = new MetricsService({
    sessionService,
    codexClient,
    enabled: config.metricsEnabled !== false,
  });
  metricsService.start();

  codexClient.on('activity', () => {
    codexClient.refreshThreadsAndLimits().catch(() => {});
  });

  const relayClient = new RelayClient(config, powerMonitor);
  const lanServer = new LanServer(config);
  const transport = new BridgeTransportRouter({
    config,
    relayClient,
    lanServer,
  });

  const notificationEngine = new NotificationEngine({
    sessionService,
    relay: transport,
    codexClient,
  });
  notificationEngine.start();

  attachBusinessProtocolRouter({
    config,
    transport,
    quotaService,
    codexClient,
    sessionService,
    metricsService,
    notificationEngine,
  });

  const triggerFile = getRepairTriggerPath();
  const triggerPoll = setInterval(() => {
    try {
      if (fs.existsSync(triggerFile)) {
        fs.unlinkSync(triggerFile);
        logger.info('Main', 'Received re-pair trigger; requesting fresh 6-digit pairing code');
        if (config.lanEnabled) {
          lanServer.regeneratePairingCode({ forceUnpair: false });
        } else {
          relayClient.requestNewPairingCode();
        }
      }
    } catch { /* ignore */ }
  }, 2000);
  triggerPoll.unref?.();

  await transport.startInitial();

  const shutdown = (sig) => {
    logger.info('Main', `Shutting down on ${sig}`);
    clearInterval(triggerPoll);
    clearInterval(codexRefreshTimer);
    try { ipcServer.close(); } catch { /* ignore */ }
    notificationEngine.stop();
    metricsService.stop();
    quotaService.stop();
    sessionService.stop();
    codexClient.stop();
    powerMonitor.stop();
    transport.stopAll().finally(() => process.exit(0));
  };

  const ipcServer = startLocalControlServer({
    relay: relayClient,
    lanServer,
    transportRouter: transport,
    quotaService,
    codexClient,
    sessionService,
    notificationEngine,
    onShutdown: shutdown,
  });

  process.on('SIGINT', () => shutdown('SIGINT'));
  process.on('SIGTERM', () => shutdown('SIGTERM'));
  process.on('uncaughtException', (err) => {
    logger.error('Main', `Uncaught exception: ${err.stack || err.message}`);
  });
  process.on('unhandledRejection', (reason) => {
    logger.error('Main', `Unhandled rejection: ${String(reason)}`);
  });
}

/**
 * Transport-agnostic business protocol handler shared identically by WAN `RelayClient` and LAN `LanServer`.
 */
export function attachBusinessProtocolRouter({
  config,
  transport,
  quotaService,
  codexClient,
  sessionService,
  metricsService,
  notificationEngine,
}) {
  const relay = transport;

  const buildCombinedQuotaPayload = async ({ forceRefresh = false } = {}) => {
    const [agSnap, codexLimits] = await Promise.all([
      quotaService.getQuotaSnapshot({ forceRefresh }),
      codexClient.getRateLimitsSnapshot({ forceRefresh }),
    ]);
    return {
      ...agSnap,
      codexOpenAi: codexLimits || null,
    };
  };

  // Push live updates when phone is online
  quotaService.on('updated', (snap) => {
    if (relay.peerOnline) {
      relay.sendEnvelope('quota.data', {
        ...snap,
        codexOpenAi: codexClient.cachedRateLimits || null,
      });
    }
  });

  codexClient.on('rateLimits.updated', () => {
    if (relay.peerOnline && quotaService.cached) {
      relay.sendEnvelope('quota.data', {
        ...quotaService.cached,
        codexOpenAi: codexClient.cachedRateLimits || null,
      });
    }
  });

  sessionService.on('sessions.changed', (listPayload) => {
    if (relay.peerOnline) {
      relay.sendEnvelope('session.list', listPayload);
    }
  });

  sessionService.on('session.stream', (streamPayload) => {
    if (relay.peerOnline) {
      relay.sendEnvelope('session.stream', streamPayload);
    }
  });

  metricsService.on('updated', (metricsSnap) => {
    if (relay.peerOnline) {
      relay.sendEnvelope('metrics.data', metricsSnap);
    }
  });

  notificationEngine.on('notified', () => {
    if (relay.peerOnline) {
      relay.sendEnvelope('approval.list', {
        approvals: notificationEngine.getPendingApprovalsList(),
      });
    }
  });

  const pushInitialStateToPhone = async () => {
    try {
      const [q, agCatalog, codexModels] = await Promise.all([
        buildCombinedQuotaPayload({ forceRefresh: false }),
        fetchAntigravityModelCatalog(),
        codexClient.getModelCatalog(),
      ]);
      relay.sendEnvelope('quota.data', q);
      relay.sendEnvelope('session.list', sessionService.listSessions());
      relay.sendEnvelope('model.list', {
        ok: true,
        antigravityModels: agCatalog.models || [],
        codexModels: codexModels || [],
        defaultAntigravityModelId: agCatalog.defaultModelId || '',
      });
      relay.sendEnvelope('approval.list', {
        approvals: notificationEngine.getPendingApprovalsList(),
      });
      const defaultDesktop = getDefaultDesktopDir();
      relay.sendEnvelope('projects.config.data', {
        ok: true,
        projectsRootDir: config.projectsRootDir || defaultDesktop,
        defaultDesktopDir: defaultDesktop,
      });
    } catch (err) {
      logger.warn('Main', `Initial state push warning: ${err.message}`);
    }
  };

  relay.on('paired', () => {
    pushInitialStateToPhone();
  });

  relay.on('presence', ({ online }) => {
    if (online) {
      pushInitialStateToPhone();
    } else {
      sessionService.unsubscribeSession();
      metricsService.clearSubscription();
      codexClient.releaseThreadWriter('', 'phone_offline').catch(() => {});
    }
  });

  relay.on('authenticated', ({ paired, peerOnline }) => {
    if (paired && peerOnline) {
      pushInitialStateToPhone();
    }
  });

  async function dispatchChatMessage({ sessionId, source, text, modelId }, reqId) {
    const cleanText = String(text || '').trim();
    if (!sessionId || !cleanText) {
      relay.sendEnvelope('chat.ack', { ok: false, sessionId, error: '会话 ID 或消息内容为空' }, reqId);
      return;
    }

    const effectiveModelId =
      String(modelId || '').trim() || getSessionModelChoice(source, sessionId)?.modelId || '';

    try {
      metricsService.onUserSend({ sessionId, source, ts: Date.now() });
      let codexResult = null;
      if (source === 'codex') {
        codexResult = await codexClient.sendMessageToThread(sessionId, cleanText, { modelSpec: effectiveModelId });
      } else {
        await sendAntigravityMessage(sessionId, cleanText, {
          cdpAvailable: Boolean(sessionService.cdpStatus?.available),
          modelId: effectiveModelId,
        });
      }
      const forkedFrom = codexResult?.forkedFrom || null;
      const ackMessage = forkedFrom
        ? '原会话正被电脑 Codex 锁定，已自动无损续接并送达'
        : null;
      relay.sendEnvelope(
        'chat.ack',
        {
          ok: true,
          sessionId,
          effectiveSessionId: codexResult?.effectiveThreadId || sessionId,
          forked: Boolean(forkedFrom),
          message: ackMessage,
          source,
          sentText: cleanText,
          appliedModelId: effectiveModelId,
        },
        reqId
      );
      if (source === 'codex' && sessionService.subscription?.sessionId === sessionId) {
        sessionService._pollSubscribedSession().catch(() => {});
      }
    } catch (err) {
      metricsService.onUserSendFailed({ sessionId });
      const fullErr = err?.rawError || err?.message || String(err);
      const userErr = err?.userMessage || err?.message || '发送失败';
      logger.error(
        'ChatSend',
        `Failed sending to ${source}:${sessionId}: ${fullErr}`,
        {
          sessionId,
          source,
          code: err?.code || null,
          rpcCode: err?.rpcCode ?? null,
          attempts: err?.attempts ?? 1,
        }
      );
      relay.sendEnvelope(
        'chat.ack',
        {
          ok: false,
          sessionId,
          source,
          code: err?.code || null,
          error: userErr,
          rawError: fullErr,
        },
        reqId
      );
    }
  }

  // Active mobile-to-desktop chunked uploads: uploadId -> { fileName, mimeType, totalChunks, chunks, bytes, createdAt }
  const activeMobileUploads = new Map();
  const MAX_MOBILE_UPLOAD_BYTES = 64 * 1024 * 1024;

  function resolveSessionUploadDir(sessionId, source) {
    try {
      const detail = sessionService.getSessionDetail(sessionId, source);
      const ws = detail?.session?.workspacePath;
      if (ws) {
        const wsNative = ws.replace(/\//g, path.sep);
        if (fs.existsSync(wsNative) && fs.statSync(wsNative).isDirectory()) {
          return path.join(wsNative, '手机上传');
        }
      }
    } catch { /* fallback below */ }
    const home = process.env.USERPROFILE || os.homedir();
    return path.join(home, 'Desktop', 'ClawBridge手机上传');
  }

  function allocateSafeUploadFilePath(dir, rawFileName) {
    fs.mkdirSync(dir, { recursive: true });
    const baseClean = path.basename(String(rawFileName || 'upload.bin')).replace(/[<>:"/\\|?*\x00-\x1f]/g, '_');
    const safeName = baseClean || `upload_${Date.now()}.bin`;
    const candidate = path.join(dir, safeName);
    if (!fs.existsSync(candidate)) return candidate;
    const ext = path.extname(safeName);
    const stem = safeName.slice(0, safeName.length - ext.length);
    return path.join(dir, `${stem}_${Date.now().toString().slice(-5)}${ext}`);
  }

  // Protocol v1 request router
  relay.on('message', async ({ type, reqId, payload }) => {
    try {
      switch (type) {
        case 'session.list.get': {
          const list = sessionService.listSessions();
          relay.sendEnvelope('session.list', list, reqId);
          break;
        }
        case 'session.open': {
          const sessionId = String(payload?.sessionId || '').trim();
          const source = String(payload?.source || 'antigravity').trim();
          if (!sessionId) {
            relay.sendEnvelope('session.stream', { ok: false, error: 'Missing sessionId' }, reqId);
            return;
          }
          const prevSub = sessionService.subscription;
          if (prevSub && prevSub.source === 'codex' && prevSub.sessionId && prevSub.sessionId !== sessionId) {
            codexClient.releaseThreadWriter(prevSub.sessionId, 'session_switch').catch(() => {});
          }
          if (source === 'antigravity') {
            await fetchAntigravityModelCatalog().catch(() => {});
          } else {
            await codexClient.getModelCatalog().catch(() => {});
          }
          const detail = sessionService.subscribeSession(sessionId, source);
          relay.sendEnvelope('session.stream', { ok: true, mode: 'snapshot', ...detail }, reqId);
          relay.sendEnvelope('approval.list', {
            approvals: notificationEngine.getPendingApprovalsList(),
          });
          metricsService.setSubscription(sessionId, source).catch(() => {});
          break;
        }
        case 'model.list.get': {
          const forceRefresh = Boolean(payload?.force);
          const [agCatalog, codexModels] = await Promise.all([
            fetchAntigravityModelCatalog({ forceRefresh }),
            codexClient.getModelCatalog({ forceRefresh }),
          ]);
          relay.sendEnvelope(
            'model.list',
            {
              ok: true,
              antigravityModels: agCatalog.models || [],
              codexModels: codexModels || [],
              defaultAntigravityModelId: agCatalog.defaultModelId || '',
            },
            reqId
          );
          break;
        }
        case 'session.model.set': {
          const sessionId = String(payload?.sessionId || '').trim();
          const source = String(payload?.source || 'antigravity').trim();
          const rawModelId = String(payload?.modelId || '').trim();
          const rawModelLabel = String(payload?.modelLabel || rawModelId).trim();
          if (!sessionId || !rawModelId) {
            relay.sendEnvelope(
              'session.model.ack',
              { ok: false, sessionId, source, error: '缺少 sessionId 或 modelId' },
              reqId
            );
            return;
          }

          let finalId = rawModelId;
          let finalLabel = rawModelLabel;

          if (source === 'antigravity') {
            const resolved = await resolveValidAntigravityModel(rawModelId, rawModelLabel);
            finalId = resolved.modelId || rawModelId;
            finalLabel = resolved.modelLabel || rawModelLabel;
            saveSessionModelChoice('antigravity', sessionId, { modelId: finalId, modelLabel: finalLabel });
            syncAntigravityLastSelectedModel(finalId);
          } else {
            const catalog = await codexClient.getModelCatalog({ forceRefresh: false }).catch(() => []);
            const hit =
              catalog.find((m) => m.modelId === rawModelId) ||
              catalog.find((m) => m.label.toLowerCase() === rawModelLabel.toLowerCase());
            if (hit) {
              finalId = hit.modelId;
              finalLabel = hit.label;
            }
            saveSessionModelChoice('codex', sessionId, { modelId: finalId, modelLabel: finalLabel });
            const forkId = codexClient.continuationForks.get(sessionId)?.forkThreadId;
            if (forkId) {
              saveSessionModelChoice('codex', forkId, { modelId: finalId, modelLabel: finalLabel });
            }
            codexClient.applyThreadModelChoice(sessionId, finalId, finalLabel);
          }

          logger.info('ModelSwitch', `Set ${source}:${sessionId.slice(0, 8)} model -> ${finalLabel} (${finalId})`);
          relay.sendEnvelope(
            'session.model.ack',
            { ok: true, sessionId, source, currentModelId: finalId, currentModelLabel: finalLabel },
            reqId
          );
          relay.sendEnvelope('session.list', sessionService.listSessions());
          if (sessionService.subscription?.sessionId === sessionId) {
            const detail = sessionService.subscribeSession(sessionId, source);
            relay.sendEnvelope('session.stream', { ok: true, mode: 'snapshot', ...detail });
          }
          break;
        }
        case 'projects.config.get': {
          const defaultDesktop = getDefaultDesktopDir();
          relay.sendEnvelope(
            'projects.config.data',
            {
              ok: true,
              projectsRootDir: config.projectsRootDir || defaultDesktop,
              defaultDesktopDir: defaultDesktop,
            },
            reqId
          );
          break;
        }
        case 'projects.config.set': {
          const defaultDesktop = getDefaultDesktopDir();
          try {
            let nextDir;
            if (payload?.resetDefault === true || !String(payload?.projectsRootDir || '').trim()) {
              nextDir = validateWorkspaceRootDir(defaultDesktop);
            } else {
              nextDir = validateWorkspaceRootDir(payload.projectsRootDir);
            }
            config.projectsRootDir = nextDir;
            saveConfig(config);
            logger.info('ProjectsConfig', `Updated projectsRootDir -> ${nextDir}`);
            relay.sendEnvelope(
              'projects.config.data',
              {
                ok: true,
                updated: true,
                projectsRootDir: nextDir,
                defaultDesktopDir: defaultDesktop,
                message: `默认项目工作目录已更新为：${nextDir}`,
              },
              reqId
            );
          } catch (cfgErr) {
            relay.sendEnvelope(
              'projects.config.data',
              {
                ok: false,
                error: cfgErr.message || '保存项目工作目录失败',
                projectsRootDir: config.projectsRootDir || defaultDesktop,
                defaultDesktopDir: defaultDesktop,
              },
              reqId
            );
          }
          break;
        }
        case 'project.create': {
          try {
            const projectName = sanitizeProjectName(payload?.projectName);
            const source = String(payload?.source || 'antigravity').trim().toLowerCase() === 'codex'
              ? 'codex'
              : 'antigravity';
            const modelId = String(payload?.modelId || '').trim();
            const modelLabel = String(payload?.modelLabel || '').trim();
            const initialPrompt = String(payload?.initialPrompt || '').trim();
            const rootDir = validateWorkspaceRootDir(
              String(payload?.customRootDir || '').trim() || config.projectsRootDir || getDefaultDesktopDir()
            );
            const targetDir = path.join(rootDir, projectName);
            fs.mkdirSync(targetDir, { recursive: true });

            let createdSession;
            if (source === 'codex') {
              createdSession = await codexClient.createProjectThread({
                workspaceDir: targetDir,
                title: projectName,
                modelSpec: modelId,
                initialPrompt,
              });
              if (modelId) {
                saveSessionModelChoice('codex', createdSession.id, {
                  modelId,
                  modelLabel: createdSession.currentModelLabel || modelLabel || modelId,
                });
              }
            } else {
              createdSession = await createAntigravityProjectSession({
                workspaceDir: targetDir,
                title: projectName,
                modelId,
                modelLabel,
                initialPrompt,
                cdpAvailable: Boolean(sessionService.cdpStatus?.available),
              });
            }

            const freshList = sessionService.listSessions();
            relay.sendEnvelope('session.list', freshList);
            relay.sendEnvelope(
              'project.create.ack',
              {
                ok: true,
                session: createdSession,
                workspacePath: createdSession.workspacePath,
                projectName,
                source,
              },
              reqId
            );
          } catch (createErr) {
            logger.error('ProjectCreate', `Failed creating project: ${createErr.message}`);
            relay.sendEnvelope(
              'project.create.ack',
              {
                ok: false,
                error: createErr.message || '新建项目失败',
              },
              reqId
            );
          }
          break;
        }
        case 'session.close': {
          const prevSub = sessionService.subscription;
          sessionService.unsubscribeSession();
          metricsService.clearSubscription();
          if (prevSub && prevSub.source === 'codex' && prevSub.sessionId) {
            codexClient.releaseThreadWriter(prevSub.sessionId, 'session_close').catch(() => {});
          }
          relay.sendEnvelope('ack', { ok: true, closed: true }, reqId);
          break;
        }
        case 'chat.send': {
          await dispatchChatMessage(
            {
              sessionId: String(payload?.sessionId || '').trim(),
              source: String(payload?.source || 'antigravity').trim(),
              text: String(payload?.text || ''),
              modelId: String(payload?.modelId || ''),
            },
            reqId
          );
          break;
        }
        case 'quick.command': {
          const cmdKey = String(payload?.commandKey || '').trim();
          const mappedText = QUICK_COMMAND_TEMPLATES[cmdKey] || String(payload?.text || '').trim();
          await dispatchChatMessage(
            {
              sessionId: String(payload?.sessionId || '').trim(),
              source: String(payload?.source || 'antigravity').trim(),
              text: mappedText,
              modelId: String(payload?.modelId || ''),
            },
            reqId
          );
          break;
        }
        case 'approval.list.get': {
          relay.sendEnvelope(
            'approval.list',
            { approvals: notificationEngine.getPendingApprovalsList() },
            reqId
          );
          break;
        }
        case 'approval.respond': {
          await notificationEngine.handleApprovalRespond(payload || {}, reqId);
          break;
        }
        case 'quota.get': {
          const forceRefresh = Boolean(payload?.force);
          const snap = await buildCombinedQuotaPayload({ forceRefresh });
          relay.sendEnvelope('quota.data', snap, reqId);
          break;
        }
        case 'metrics.get': {
          const sessionId = String(payload?.sessionId || sessionService.subscription?.sessionId || '').trim();
          const source = String(payload?.source || sessionService.subscription?.source || 'antigravity').trim();
          const snap = await metricsService.getMetricsSnapshot({ sessionId, source });
          relay.sendEnvelope('metrics.data', snap, reqId);
          break;
        }
        case 'artifact.get': {
          const sessionId = String(payload?.sessionId || '').trim();
          const artifactId = String(payload?.artifactId || '').trim();
          const mode = String(payload?.mode || 'preview').trim();
          await sessionService.handleArtifactRequest(
            { sessionId, artifactId, mode },
            (replyType, replyPayload) => {
              relay.sendEnvelope(replyType, replyPayload, reqId || crypto.randomUUID());
            }
          );
          break;
        }
        case 'file.download': {
          const sessionId = String(payload?.sessionId || '').trim();
          const filePath = String(payload?.filePath || '').trim();
          await sessionService.handleDirectFileDownloadRequest(
            { sessionId, filePath },
            (replyType, replyPayload) => {
              relay.sendEnvelope(replyType, replyPayload, reqId || crypto.randomUUID());
            }
          );
          break;
        }
        case 'upload.chunk': {
          const uploadId = String(payload?.uploadId || '').trim();
          const fileName = String(payload?.fileName || 'upload.bin').trim();
          const mimeType = String(payload?.mimeType || 'application/octet-stream').trim();
          const chunkIndex = Number(payload?.chunkIndex ?? 0);
          const totalChunks = Math.max(1, Number(payload?.totalChunks ?? 1));
          const b64 = String(payload?.base64Chunk || '');
          if (!uploadId || !b64) {
            relay.sendEnvelope('upload.ack', { ok: false, uploadId, error: '无效的上传分片' }, reqId);
            return;
          }

          // Prune stale incomplete uploads older than 10 minutes
          const now = Date.now();
          for (const [uid, st] of activeMobileUploads) {
            if (now - st.createdAt > 10 * 60 * 1000) activeMobileUploads.delete(uid);
          }

          let entry = activeMobileUploads.get(uploadId);
          if (!entry) {
            entry = {
              fileName,
              mimeType,
              totalChunks,
              chunks: new Array(totalChunks).fill(null),
              bytes: 0,
              createdAt: now,
            };
            activeMobileUploads.set(uploadId, entry);
          }

          const decoded = Buffer.from(b64, 'base64');
          entry.bytes += decoded.length;
          if (entry.bytes > MAX_MOBILE_UPLOAD_BYTES) {
            activeMobileUploads.delete(uploadId);
            relay.sendEnvelope(
              'upload.ack',
              { ok: false, uploadId, error: '上传文件超过 64 MB 大小上限' },
              reqId
            );
            return;
          }

          if (chunkIndex >= 0 && chunkIndex < entry.totalChunks) {
            entry.chunks[chunkIndex] = decoded;
          }
          break;
        }
        case 'upload.complete': {
          const uploadId = String(payload?.uploadId || '').trim();
          const sessionId = String(payload?.sessionId || '').trim();
          const source = String(payload?.source || 'antigravity').trim();
          const deliverToChat = payload?.deliverToChat !== false;
          const promptText = String(payload?.promptText || '').trim();
          const modelId =
            String(payload?.modelId || '').trim() || getSessionModelChoice(source, sessionId)?.modelId || '';

          const entry = activeMobileUploads.get(uploadId);
          if (!entry) {
            relay.sendEnvelope('upload.ack', { ok: false, uploadId, error: '未找到上传分片缓存或已超时' }, reqId);
            return;
          }
          activeMobileUploads.delete(uploadId);

          if (entry.chunks.some((c) => c === null)) {
            relay.sendEnvelope('upload.ack', { ok: false, uploadId, error: '部分上传分片缺失，请重试' }, reqId);
            return;
          }

          try {
            const fullBuf = Buffer.concat(entry.chunks);
            const targetDir = resolveSessionUploadDir(sessionId, source);
            const savedPath = allocateSafeUploadFilePath(targetDir, entry.fileName);
            fs.writeFileSync(savedPath, fullBuf);
            logger.info(
              'MobileUpload',
              `Saved mobile upload "${path.basename(savedPath)}" (${(fullBuf.length / 1024).toFixed(1)} KB) -> ${savedPath}`
            );

            const isImage = entry.mimeType.toLowerCase().startsWith('image/') ||
              /\.(png|jpg|jpeg|webp|gif)$/i.test(savedPath);

            if (deliverToChat && sessionId) {
              metricsService.onUserSend({ sessionId, source, ts: Date.now() });
              if (source === 'codex') {
                const textForCodex = isImage
                  ? promptText || `请查看我从手机发送的图片（已同步保存于 ${savedPath}）。`
                  : `@[${savedPath}]\n${promptText || `我已从手机上传文件「${path.basename(savedPath)}」至 ${savedPath}，请查看处理。`}`;
                await codexClient.sendMessageToThread(sessionId, textForCodex, {
                  modelSpec: modelId,
                  imagePaths: isImage ? [savedPath] : [],
                });
              } else {
                if (isImage) {
                  const textForAg =
                    promptText || `请查看我从手机发来的图片（同时已保存至电脑目录：${savedPath}）。`;
                  await sendAntigravityMessage(sessionId, textForAg, {
                    cdpAvailable: Boolean(sessionService.cdpStatus?.available),
                    modelId,
                    mediaItems: [
                      {
                        mimeType: entry.mimeType.startsWith('image/') ? entry.mimeType : 'image/png',
                        inlineData: fullBuf.toString('base64'),
                        savedPath,
                      },
                    ],
                  });
                } else {
                  const textForAg = `@[${savedPath}]\n${
                    promptText || `我已从手机实时上传文件「${path.basename(savedPath)}」到项目目录 (${savedPath})，请查看处理。`
                  }`;
                  await sendAntigravityMessage(sessionId, textForAg, {
                    cdpAvailable: Boolean(sessionService.cdpStatus?.available),
                    modelId,
                  });
                }
              }
            }

            relay.sendEnvelope(
              'upload.ack',
              {
                ok: true,
                uploadId,
                sessionId,
                source,
                fileName: path.basename(savedPath),
                savedPath: savedPath.replace(/\\/g, '/'),
                deliveredToChat: Boolean(deliverToChat && sessionId),
              },
              reqId
            );
          } catch (upErr) {
            logger.error('MobileUpload', `Upload completion failed: ${upErr.message}`);
            relay.sendEnvelope('upload.ack', { ok: false, uploadId, error: upErr.message }, reqId);
          }
          break;
        }
        default: {
          relay.sendEnvelope('ack', { receivedType: type, ok: true }, reqId);
          break;
        }
      }
    } catch (err) {
      logger.error('Router', `Error handling ${type}: ${err.message}`);
      relay.sendEnvelope('protocol.error', { code: 'handler_error', message: err.message }, reqId);
    }
  });
}

const isDirectRun =
  Boolean(process.argv[1]) &&
  path.resolve(process.argv[1]) === path.resolve(fileURLToPath(import.meta.url));

if (isDirectRun) {
  main().catch((err) => {
    logger.error('Main', `Fatal startup error: ${err.stack || err.message}`);
    process.exit(1);
  });
}
