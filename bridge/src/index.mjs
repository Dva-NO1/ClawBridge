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
import { logger } from './logger.mjs';
import {
  loadConfig,
  getConfigPath,
  getPairingFilePath,
  getRepairTriggerPath,
  getStatusFilePath,
  getSessionModelChoice,
  saveSessionModelChoice,
  isPlaceholderRelayUrl,
} from './claw-config.mjs';
import { inspectAntigravityVersion } from './antigravity-inspector.mjs';
import { PowerMonitor } from './power-monitor.mjs';
import { RelayClient } from './relay-client.mjs';
import { GoogleQuotaService } from './google-quota.mjs';
import { CodexAppServerClient } from './codex-client.mjs';
import { SessionService } from './session-service.mjs';
import { NotificationEngine } from './notification-engine.mjs';
import {
  sendAntigravityMessage,
  fetchAntigravityModelCatalog,
  syncAntigravityLastSelectedModel,
} from './antigravity-executor.mjs';
import { startLocalControlServer } from './local-ipc.mjs';

const QUICK_COMMAND_TEMPLATES = {
  continue: '继续执行下一步，完成后简要告知结果。',
  start_plan: '按计划开始开工执行，遇到高风险操作前请先请求确认。',
  report_progress: '请先简要汇报当前已完成进度、正在执行的任务与下一步计划。',
};

const args = new Set(process.argv.slice(2));

if (args.has('--show-pair-code') || args.has('--status')) {
  const statusPath = getStatusFilePath();
  const pairPath = getPairingFilePath();
  if (fs.existsSync(statusPath)) {
    console.log('=== ClawBridge Runtime Status ===');
    console.log(fs.readFileSync(statusPath, 'utf-8'));
  }
  if (fs.existsSync(pairPath)) {
    console.log('=== Pairing Info ===');
    console.log(fs.readFileSync(pairPath, 'utf-8'));
  } else {
    console.log('No pairing-code.json generated yet. Start ClawBridge first.');
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

  codexClient.on('activity', () => {
    codexClient.refreshThreadsAndLimits().catch(() => {});
  });

  const relay = new RelayClient(config, powerMonitor);

  const notificationEngine = new NotificationEngine({
    sessionService,
    relay,
    codexClient,
  });
  notificationEngine.start();

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
          break;
        }
        case 'model.list.get': {
          const [agCatalog, codexModels] = await Promise.all([
            fetchAntigravityModelCatalog({ forceRefresh: Boolean(payload?.force) }),
            codexClient.getModelCatalog(),
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
          const modelId = String(payload?.modelId || '').trim();
          const modelLabel = String(payload?.modelLabel || modelId).trim();
          if (!sessionId || !modelId) {
            relay.sendEnvelope(
              'session.model.ack',
              { ok: false, sessionId, source, error: '缺少 sessionId 或 modelId' },
              reqId
            );
            return;
          }
          saveSessionModelChoice(source, sessionId, { modelId, modelLabel });
          if (source === 'antigravity') {
            syncAntigravityLastSelectedModel(modelId);
          }
          logger.info('ModelSwitch', `Set ${source}:${sessionId.slice(0, 8)} model -> ${modelLabel} (${modelId})`);
          relay.sendEnvelope(
            'session.model.ack',
            { ok: true, sessionId, source, currentModelId: modelId, currentModelLabel: modelLabel },
            reqId
          );
          if (sessionService.subscription?.sessionId === sessionId) {
            const detail = sessionService.subscribeSession(sessionId, source);
            relay.sendEnvelope('session.stream', { ok: true, mode: 'snapshot', ...detail });
          }
          break;
        }
        case 'session.close': {
          const prevSub = sessionService.subscription;
          sessionService.unsubscribeSession();
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

  const triggerFile = getRepairTriggerPath();
  const triggerPoll = setInterval(() => {
    try {
      if (fs.existsSync(triggerFile)) {
        fs.unlinkSync(triggerFile);
        logger.info('Main', 'Received re-pair trigger; requesting fresh 6-digit pairing code');
        relay.requestNewPairingCode();
      }
    } catch { /* ignore */ }
  }, 2000);
  triggerPoll.unref?.();

  if (isPlaceholderRelayUrl(config.relayUrl)) {
    logger.warn(
      'Main',
      `First-launch setup required: relayUrl is still the placeholder "${config.relayUrl}". ` +
        `Please open ClawBridge Desktop Console or edit ${getConfigPath()} to set your own VPS relay URL (e.g. wss://relay.yourdomain.com).`
    );
  } else {
    relay.start();
  }

  const shutdown = (sig) => {
    logger.info('Main', `Shutting down on ${sig}`);
    clearInterval(triggerPoll);
    clearInterval(codexRefreshTimer);
    try { ipcServer.close(); } catch { /* ignore */ }
    notificationEngine.stop();
    quotaService.stop();
    sessionService.stop();
    codexClient.stop();
    powerMonitor.stop();
    relay.stop();
    process.exit(0);
  };

  const ipcServer = startLocalControlServer({
    relay,
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

main().catch((err) => {
  logger.error('Main', `Fatal startup error: ${err.stack || err.message}`);
  process.exit(1);
});
