/**
 * ClawBridge Notification & Risk Classification Engine (M3 / M4)
 *
 * Hard Requirements (Spec 3.2, 3.3, 6, 7):
 *   - Monitors 3 core event categories: `approval.pending`, `task.done`, `task.error`
 *   - Sends `event.notify` envelopes to Relay (Relay buffers up to 200 FIFO when phone is offline)
 *   - Hardcodes high-risk approval rules in Bridge (`rm/del/format/install/script execution` etc.);
 *     App CANNOT downgrade riskLevel.
 */

import crypto from 'node:crypto';
import { EventEmitter } from 'node:events';
import { logger } from './logger.mjs';
import { extractStructured } from './antigravity-dom.mjs';
import { findEditorTarget, connectToTarget } from './cdp-client.mjs';
import { clickAntigravityXPath } from './antigravity-executor.mjs';

const HIGH_RISK_PATTERNS = [
  // File/directory deletion & disk formatting
  /\b(rm|rmdir|del|erase|rd|Remove-Item|Clear-Content|format|diskpart|fdisk|mkfs|dd|shred)\b/i,
  // Software / package installation or removal
  /\b(install|uninstall|winget|choco|scoop|apt|apt-get|yum|dnf|pacman|brew|msiexec|pip|pip3)\b/i,
  /\b(npm|pnpm|yarn)\s+(i|install|add|ci|remove|uninstall|link)\b/i,
  // Script execution & shell interpreters
  /\b(powershell|pwsh|cmd\.exe|cmd\s+\/c|bash|sh|zsh|wsl|cscript|wscript|mshta|regsvr32|rundll32|Invoke-Expression|iex)\b/i,
  /\.(ps1|bat|cmd|sh|vbs|reg|msi)\b/i,
  // Destructive Git / System / Process / Privilege / Database operations
  /\b(git\s+reset\s+--hard|git\s+clean|git\s+push\s+(-f|--force)|Stop-Process|taskkill|shutdown|restart-computer|reboot)\b/i,
  /\b(sc\s+(delete|stop)|reg\s+(add|delete)|netsh|icacls|takeown|chmod|chown|sudo|DROP\s+(TABLE|DATABASE)|TRUNCATE\s+TABLE)\b/i,
];

/**
 * Hardcoded high-risk approval classifier (Spec Section 7).
 * Never trust client-supplied risk levels.
 */
export function classifyApprovalRisk(commandOrText) {
  const text = String(commandOrText || '').trim();
  if (!text) return 'high'; // Fail-closed if command text is missing/ambiguous
  for (const re of HIGH_RISK_PATTERNS) {
    if (re.test(text)) {
      return 'high';
    }
  }
  return 'low';
}

export class NotificationEngine extends EventEmitter {
  constructor({ sessionService, relay, codexClient = null }) {
    super();
    this.sessionService = sessionService;
    this.relay = relay;
    this.codexClient = codexClient;

    this.seeded = false;
    // key `${source}:${id}` -> { status, title, project, doneDebounceTimer }
    this.sessionStates = new Map();
    // key `${source}:${id}` -> timestampMs of last emitted task.done (prevents duplicate notifications)
    this.recentTaskDoneAt = new Map();
    this.codexTurnTimers = new Map();
    // approvalId -> { approvalId, sessionId, source, title, project, commandPreview, riskLevel, acceptXpath, rejectXpath, createdAt, resolved }
    this.pendingApprovals = new Map();
    this.lastCdpApprovalSig = '';
    this.cdpPollTimer = null;
    this.seqCounter = 1;
  }

  start() {
    // Seed initial states without firing notifications for historical idle sessions
    try {
      const initial = this.sessionService.listSessions();
      this._processSessionList(initial.sessions || [], true);
      this.seeded = true;
    } catch (err) {
      logger.warn('NotifyEngine', `Initial seed warning: ${err.message}`);
    }

    this.sessionService.on('sessions.changed', (payload) => {
      this._processSessionList(payload?.sessions || [], !this.seeded);
      this.seeded = true;
    });

    if (this.codexClient) {
      this.codexClient.on('approval.request', (req) => {
        this._onCodexApprovalRequest(req);
      });
      this.codexClient.on('turnLifecycle', (evt) => {
        this._onCodexTurnLifecycle(evt);
      });
    }

    this.cdpPollTimer = setInterval(() => {
      this._pollCdpPendingApprovals().catch(() => {});
    }, 2500);
    this.cdpPollTimer.unref?.();
  }

  stop() {
    if (this.cdpPollTimer) {
      clearInterval(this.cdpPollTimer);
      this.cdpPollTimer = null;
    }
    for (const st of this.sessionStates.values()) {
      if (st.doneDebounceTimer) clearTimeout(st.doneDebounceTimer);
    }
    for (const t of this.codexTurnTimers.values()) {
      clearTimeout(t);
    }
    this.codexTurnTimers.clear();
  }

  _resolveVisibleCodexSessionId(rawThreadId) {
    if (!rawThreadId || !this.codexClient?.continuationForks) return rawThreadId;
    for (const [origId, fk] of this.codexClient.continuationForks.entries()) {
      if (fk?.forkThreadId === rawThreadId) return origId;
    }
    return rawThreadId;
  }

  _extractLatestReplySummary(source, sessionId, fallbackText = '') {
    try {
      const detail = this.sessionService.getSessionDetail(sessionId, source);
      const msgs = Array.isArray(detail?.messages) ? detail.messages : [];
      for (let i = msgs.length - 1; i >= 0; i--) {
        if (msgs[i]?.role === 'agent' && msgs[i]?.text) {
          const clean = String(msgs[i].text)
            .replace(/```[\s\S]*?```/g, '[代码块]')
            .replace(/\s+/g, ' ')
            .trim();
          if (clean) return clean.slice(0, 110);
        }
      }
    } catch { /* ignore */ }
    return String(fallbackText || '').replace(/\s+/g, ' ').trim().slice(0, 110);
  }

  _emitSessionTaskDoneOnce({ source, sessionId, title, project, summaryText }) {
    if (!sessionId) return;
    const key = `${source}:${sessionId}`;
    const now = Date.now();
    const lastAt = this.recentTaskDoneAt.get(key) || 0;
    if (now - lastAt < 12_000) return;
    this.recentTaskDoneAt.set(key, now);

    const badge = source === 'codex' ? 'Codex' : 'AGY';
    const projLabel = project || badge;
    const cleanSummary = summaryText || '本轮会话任务已执行完毕';
    this.pushNotify({
      category: 'task.done',
      title: `✅ 任务已完成 · ${projLabel} (${badge})`,
      body: title ? `「${title}」：${cleanSummary}` : cleanSummary,
      sessionId,
      source,
      project: projLabel,
    });
  }

  _onCodexTurnLifecycle({ threadId, phase }) {
    if (!threadId) return;
    const visibleId = this._resolveVisibleCodexSessionId(threadId);
    const key = `codex:${visibleId}`;

    if (phase === 'started') {
      this.recentTaskDoneAt.delete(key);
      const pendingTimer = this.codexTurnTimers.get(visibleId);
      if (pendingTimer) {
        clearTimeout(pendingTimer);
        this.codexTurnTimers.delete(visibleId);
      }
      const existing = this.sessionStates.get(key);
      if (existing) {
        existing.status = 'running';
        if (existing.doneDebounceTimer) {
          clearTimeout(existing.doneDebounceTimer);
          existing.doneDebounceTimer = null;
        }
      } else {
        this.sessionStates.set(key, {
          status: 'running',
          title: `Codex 会话 ${visibleId.slice(0, 8)}`,
          project: 'Codex',
          doneDebounceTimer: null,
        });
      }
      return;
    }

    if (phase === 'completed') {
      const prevTimer = this.codexTurnTimers.get(visibleId);
      if (prevTimer) clearTimeout(prevTimer);
      const timer = setTimeout(() => {
        this.codexTurnTimers.delete(visibleId);
        if (this.codexClient?.activeTurns?.has(threadId) || this.codexClient?.activeTurns?.has(visibleId)) {
          return;
        }
        try { this.sessionService._pollSessionListChanges?.(); } catch { /* ignore */ }
        const list = this.sessionService.listSessions()?.sessions || [];
        const matched = list.find((s) => s.source === 'codex' && s.id === visibleId);
        const liveText = this.codexClient?.liveStreams?.get(threadId)?.text || '';
        const summary = liveText
          ? String(liveText).replace(/```[\s\S]*?```/g, '[代码块]').replace(/\s+/g, ' ').trim().slice(0, 110)
          : this._extractLatestReplySummary('codex', visibleId, matched?.preview || '');
        const st = this.sessionStates.get(key);
        if (st) st.status = 'done';
        this._emitSessionTaskDoneOnce({
          source: 'codex',
          sessionId: visibleId,
          title: matched?.title || st?.title || '',
          project: matched?.project || st?.project || 'Codex',
          summaryText: summary,
        });
      }, 1500);
      timer.unref?.();
      this.codexTurnTimers.set(visibleId, timer);
    }
  }

  getPendingApprovalsList() {
    return [...this.pendingApprovals.values()].filter((a) => !a.resolved);
  }

  /**
   * Emit a unified `event.notify` frame to Relay.
   * When phone is online -> delivered immediately;
   * When phone is offline -> buffered FIFO (up to 200) on Relay and flushed in order on reconnect!
   */
  pushNotify({
    category, // 'approval.pending' | 'task.done' | 'task.error' | 'assistant.persona'
    title,
    body,
    sessionId = '',
    source = 'antigravity',
    project = '',
    riskLevel = null,
    approvalId = null,
    commandPreview = null,
  }) {
    const notifyId = crypto.randomUUID();
    const seq = this.seqCounter++;
    const ts = Date.now();
    const payload = {
      notifyId,
      seq,
      category,
      title,
      body,
      sessionId,
      source,
      project,
      riskLevel,
      approvalId,
      commandPreview,
      ts,
    };

    const sent = this.relay.sendEnvelope('event.notify', payload);
    logger.info('NotifyEngine', `Emitted event.notify [${category}] (seq=${seq}, risk=${riskLevel || 'n/a'}, sentToRelay=${sent})`, {
      title,
      sessionId: sessionId.slice(0, 12),
    });

    if (category === 'approval.pending' && approvalId) {
      this.relay.sendEnvelope('approval.pending', {
        approvalId,
        sessionId,
        source,
        project,
        title,
        commandPreview: commandPreview || body,
        riskLevel: riskLevel || 'high',
        ts,
      });
    }

    this.emit('notified', payload);
    return payload;
  }

  _onCodexApprovalRequest({ approvalKey, threadId, cwd, commandPreview }) {
    const risk = classifyApprovalRisk(commandPreview);
    const approvalId = `codex-${crypto.randomUUID().slice(0, 8)}`;
    const sessions = this.sessionService.listSessions()?.sessions || [];
    const matched = sessions.find((s) => s.source === 'codex' && s.id === threadId);
    const project = matched?.project || (cwd ? cwd.split(/[\\/]/).pop() : 'Codex');
    const title = matched?.title || `Codex 会话 ${String(threadId).slice(0, 8)}`;

    this.pendingApprovals.set(approvalId, {
      approvalId,
      codexApprovalKey: approvalKey,
      sessionId: threadId,
      source: 'codex',
      title,
      project,
      commandPreview,
      riskLevel: risk,
      createdAt: Date.now(),
      resolved: false,
    });

    this.pushNotify({
      category: 'approval.pending',
      title: risk === 'high' ? `⚠️ Codex 高风险审批 · ${project}` : `⏳ Codex 待确认操作 · ${project}`,
      body: commandPreview,
      sessionId: threadId,
      source: 'codex',
      project,
      riskLevel: risk,
      approvalId,
      commandPreview,
    });
  }

  /**
   * Handle `approval.respond` from phone (low-risk quick notification action or in-app response).
   * Enforces PIN verification for high-risk approvals on Bridge side (Spec Section 7).
   */
  async handleApprovalRespond({ approvalId, decision, pinVerified = false }, reqId) {
    const entry = approvalId ? this.pendingApprovals.get(approvalId) : null;
    if (entry && entry.riskLevel === 'high' && decision === 'approve' && !pinVerified) {
      logger.warn('NotifyEngine', `Blocked high-risk approval ${approvalId} without PIN verification!`);
      this.relay.sendEnvelope(
        'approval.ack',
        {
          ok: false,
          approvalId,
          code: 'pin_required',
          message: '高风险操作必须在 App 内完成 PIN 校验后方可批准',
        },
        reqId
      );
      return;
    }

    const approved = decision === 'approve';
    let executedVia = 'state_ack';

    if (entry) {
      entry.resolved = true;
      entry.decision = decision;

      if (entry.codexApprovalKey && this.codexClient) {
        const sent = this.codexClient.respondToApproval(entry.codexApprovalKey, approved);
        executedVia = sent ? 'codex_rpc' : 'codex_expired';
      } else if (approved && entry.acceptXpath) {
        const clicked = await clickAntigravityXPath(entry.acceptXpath).catch(() => false);
        executedVia = clicked ? 'cdp_xpath' : 'cdp_ack';
      } else if (!approved && entry.rejectXpath) {
        const clicked = await clickAntigravityXPath(entry.rejectXpath).catch(() => false);
        executedVia = clicked ? 'cdp_xpath' : 'cdp_ack';
      }
    }

    logger.info('NotifyEngine', `Executed approval response: id=${approvalId || 'unknown'} decision=${decision} via=${executedVia}`);
    this.relay.sendEnvelope(
      'approval.ack',
      {
        ok: true,
        approvalId,
        sessionId: entry?.sessionId || '',
        decision,
        executedVia,
      },
      reqId
    );
    this.relay.sendEnvelope('approval.list', {
      approvals: this.getPendingApprovalsList(),
    });
  }

  /**
   * Trigger real `event.notify` frames from Desktop GUI for M3 lockscreen & 10-min offline queue verification
   */
  triggerTestNotification(kind = 'task_done') {
    const topSession = this.sessionService.listSessions()?.sessions?.[0] || {
      id: '00000000-0000-4000-8000-000000000001',
      source: 'antigravity',
      title: 'Antigravity与Codex项目开发',
      project: 'ClawBridge',
    };

    if (kind === 'sequence_3') {
      const batchTag = new Date().toLocaleTimeString('zh-CN', { hour12: false });
      const approvalId1 = `test-appr-${crypto.randomUUID().slice(0, 8)}`;
      const cmd1 = 'git status && npm test';
      const risk1 = classifyApprovalRisk(cmd1); // 'low'
      this.pendingApprovals.set(approvalId1, {
        approvalId: approvalId1,
        sessionId: topSession.id,
        source: topSession.source,
        title: topSession.title,
        project: topSession.project,
        commandPreview: cmd1,
        riskLevel: risk1,
        createdAt: Date.now(),
        resolved: false,
      });

      this.pushNotify({
        category: 'approval.pending',
        title: `[#1/3] 待确认操作 (${batchTag})`,
        body: `${topSession.project} 请求执行: ${cmd1}`,
        sessionId: topSession.id,
        source: topSession.source,
        project: topSession.project,
        riskLevel: risk1,
        approvalId: approvalId1,
        commandPreview: cmd1,
      });

      setTimeout(() => {
        this.pushNotify({
          category: 'task.done',
          title: `[#2/3] 任务已完成 (${batchTag})`,
          body: `${topSession.project} · "${topSession.title}" 构建与单测全部通过`,
          sessionId: topSession.id,
          source: topSession.source,
          project: topSession.project,
        });
      }, 180);

      setTimeout(() => {
        this.pushNotify({
          category: 'task.error',
          title: `[#3/3] 任务异常告警 (${batchTag})`,
          body: `${topSession.project} · 子任务连接超时，点击直达会话查看详情`,
          sessionId: topSession.id,
          source: topSession.source,
          project: topSession.project,
        });
      }, 360);

      return { ok: true, count: 3 };
    }

    if (kind === 'approval_low') {
      const cmd = 'git diff --stat && npm run lint';
      const risk = classifyApprovalRisk(cmd);
      const approvalId = `appr-${crypto.randomUUID().slice(0, 8)}`;
      this.pendingApprovals.set(approvalId, {
        approvalId,
        sessionId: topSession.id,
        source: topSession.source,
        title: topSession.title,
        project: topSession.project,
        commandPreview: cmd,
        riskLevel: risk,
        createdAt: Date.now(),
        resolved: false,
      });
      return this.pushNotify({
        category: 'approval.pending',
        title: `⏳ 待确认操作（低风险）`,
        body: `[${topSession.project}] 请求运行普通命令: ${cmd}`,
        sessionId: topSession.id,
        source: topSession.source,
        project: topSession.project,
        riskLevel: risk,
        approvalId,
        commandPreview: cmd,
      });
    }

    if (kind === 'approval_high') {
      const cmd = 'powershell -ExecutionPolicy Bypass -File deploy.ps1 && rm -rf ./build';
      const risk = classifyApprovalRisk(cmd); // guaranteed 'high'
      const approvalId = `appr-${crypto.randomUUID().slice(0, 8)}`;
      this.pendingApprovals.set(approvalId, {
        approvalId,
        sessionId: topSession.id,
        source: topSession.source,
        title: topSession.title,
        project: topSession.project,
        commandPreview: cmd,
        riskLevel: risk,
        createdAt: Date.now(),
        resolved: false,
      });
      return this.pushNotify({
        category: 'approval.pending',
        title: `⚠️ 高风险操作待确认（需PIN）`,
        body: `[${topSession.project}] 请求执行脚本/删除: ${cmd}`,
        sessionId: topSession.id,
        source: topSession.source,
        project: topSession.project,
        riskLevel: risk,
        approvalId,
        commandPreview: cmd,
      });
    }

    if (kind === 'task_error') {
      return this.pushNotify({
        category: 'task.error',
        title: `❌ 任务报错 · ${topSession.project}`,
        body: `会话「${topSession.title}」执行命令返回非零退出码，请点击查看`,
        sessionId: topSession.id,
        source: topSession.source,
        project: topSession.project,
      });
    }

    // default: task_done
    return this.pushNotify({
      category: 'task.done',
      title: `✅ 任务完成 · ${topSession.project}`,
      body: `会话「${topSession.title}」已完成当前阶段执行`,
      sessionId: topSession.id,
      source: topSession.source,
      project: topSession.project,
    });
  }

  // ─── Internal Transition Watchers ──────────────────────────────────────────

  _processSessionList(sessions, seedOnly = false) {
    for (const s of sessions) {
      const key = `${s.source}:${s.id}`;
      const prev = this.sessionStates.get(key);

      if (!prev || seedOnly) {
        this.sessionStates.set(key, {
          status: s.status,
          title: s.title,
          project: s.project,
          doneDebounceTimer: null,
        });
        continue;
      }

      const oldStatus = prev.status;
      const newStatus = s.status;

      // Cancel pending done timer & reset dedup cooldown if session resumed running
      if (newStatus === 'running') {
        this.recentTaskDoneAt.delete(key);
        if (prev.doneDebounceTimer) {
          clearTimeout(prev.doneDebounceTimer);
          prev.doneDebounceTimer = null;
        }
      }

      if (oldStatus !== newStatus) {
        prev.status = newStatus;
        prev.title = s.title;
        prev.project = s.project;

        // 1. Transition running/waiting_approval -> done (debounced 2.2s so step gaps don't false-fire)
        if ((oldStatus === 'running' || oldStatus === 'waiting_approval') && newStatus === 'done') {
          if (prev.doneDebounceTimer) clearTimeout(prev.doneDebounceTimer);
          prev.doneDebounceTimer = setTimeout(() => {
            prev.doneDebounceTimer = null;
            const latestList = this.sessionService.listSessions()?.sessions || [];
            const cur = latestList.find((x) => x.source === s.source && x.id === s.id);
            if (cur && cur.status === 'done') {
              const summary = this._extractLatestReplySummary(cur.source, cur.id, cur.preview);
              this._emitSessionTaskDoneOnce({
                source: cur.source,
                sessionId: cur.id,
                title: cur.title,
                project: cur.project,
                summaryText: summary,
              });
            }
          }, 2200);
          prev.doneDebounceTimer.unref?.();
        }

        // 2. Transition -> error
        if (newStatus === 'error' && oldStatus !== 'error') {
          this.pushNotify({
            category: 'task.error',
            title: `❌ 执行异常报错 · ${s.project}`,
            body: `${s.title}：${s.preview || '请打开会话查看错误日志'}`,
            sessionId: s.id,
            source: s.source,
            project: s.project,
          });
        }

        // 3. Transition -> waiting_approval
        if (newStatus === 'waiting_approval' && oldStatus !== 'waiting_approval') {
          const cmd = s.preview || s.title;
          const risk = classifyApprovalRisk(cmd);
          const approvalId = `appr-${crypto.randomUUID().slice(0, 8)}`;
          this.pendingApprovals.set(approvalId, {
            approvalId,
            sessionId: s.id,
            source: s.source,
            title: s.title,
            project: s.project,
            commandPreview: cmd,
            riskLevel: risk,
            createdAt: Date.now(),
            resolved: false,
          });
          this.pushNotify({
            category: 'approval.pending',
            title: risk === 'high' ? `⚠️ 高风险操作待确认 · ${s.project}` : `⏳ 待确认操作 · ${s.project}`,
            body: `${s.title}：${cmd}`,
            sessionId: s.id,
            source: s.source,
            project: s.project,
            riskLevel: risk,
            approvalId,
            commandPreview: cmd,
          });
        }
      }
    }
  }

  async _pollCdpPendingApprovals() {
    if (!this.sessionService.cdpStatus?.available) return;
    try {
      const target = await findEditorTarget();
      if (!target) return;
      const client = await connectToTarget(target);
      try {
        const dom = await extractStructured(client);
        if (!dom?.found || !Array.isArray(dom.messages) || dom.messages.length === 0) return;
        const lastMsg = dom.messages[dom.messages.length - 1];
        const actions = lastMsg?.actions || [];
        const acceptBtn = actions.find((a) => a.kind === 'accept');
        const rejectBtn = actions.find((a) => a.kind === 'reject');

        if (!acceptBtn) {
          this.lastCdpApprovalSig = '';
          return;
        }

        const cmdContext = [
          ...(lastMsg.activity || []).map((a) => a.text),
          lastMsg.text || '',
          acceptBtn.label || '',
        ]
          .join(' ')
          .trim()
          .slice(0, 240);

        const sig = `${acceptBtn.xpath}:${cmdContext.slice(0, 80)}`;
        if (sig === this.lastCdpApprovalSig) return;
        this.lastCdpApprovalSig = sig;

        const topSession = this.sessionService.listSessions()?.sessions?.find((s) => s.source === 'antigravity');
        const sessionId = topSession?.id || '';
        const project = topSession?.project || 'Antigravity';
        const title = topSession?.title || 'Antigravity 会话';
        const risk = classifyApprovalRisk(cmdContext);
        const approvalId = `cdp-${crypto.randomUUID().slice(0, 8)}`;

        this.pendingApprovals.set(approvalId, {
          approvalId,
          sessionId,
          source: 'antigravity',
          title,
          project,
          commandPreview: cmdContext,
          riskLevel: risk,
          acceptXpath: acceptBtn.xpath,
          rejectXpath: rejectBtn?.xpath || null,
          createdAt: Date.now(),
          resolved: false,
        });

        this.pushNotify({
          category: 'approval.pending',
          title: risk === 'high' ? `⚠️ 高风险命令待审批 · ${project}` : `⏳ 待确认操作 · ${project}`,
          body: cmdContext || `请确认操作: ${acceptBtn.label}`,
          sessionId,
          source: 'antigravity',
          project,
          riskLevel: risk,
          approvalId,
          commandPreview: cmdContext,
        });
      } finally {
        client.close();
      }
    } catch { /* ignore transient CDP error */ }
  }
}
