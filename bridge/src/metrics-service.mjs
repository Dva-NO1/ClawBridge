/**
 * ClawBridge Real-Time Speed, Token Usage & Cost Estimation Engine (v1.8.0)
 *
 * Follows GoogleQuotaService architecture (EventEmitter, cached snapshot, start/stop):
 *   - Tracks ONLY the currently subscribed session (zero idle overhead across other sessions).
 *   - Antigravity source:
 *       1) Primary: Hub ConnectRPC `GetCascadeTrajectoryGeneratorMetadata` (ms-exact TTFT,
 *          streamingDuration, decodeTps, e2eTps, thinking/response split, cache read/write,
 *          compaction maxContextTokens — matches agy-pulse).
 *       2) Fallback: local `transcript.jsonl` (exact input/cache_read/output tokens + filtered
 *          1s step-gap E2E estimate).
 *       3) Live mid-stream overlay (optional): CDP `live_tail` 3s sliding window (HTML-stripped,
 *          strictly marked estimated:true) when CDP 9222 is open and session is actively running.
 *   - Codex source:
 *       1) Live: `codex app-server` stdio notifications (`turn/started`, `item/agentMessage/delta`,
 *          `item/reasoning/*Delta`, `thread/tokenUsage/updated`, `turn/completed`) -> sub-ms TTFT,
 *          3s sliding-window live TPS (estimated:true), exact token counts & context window, and
 *          turn-end exact decode/e2e TPS calibration.
 *       2) History / Fallback: `~/.codex/sessions/.../rollout-*.jsonl` (`turn_context`, `token_count`,
 *          `token_usage_record`, `task_complete` with exact `duration_ms` & `time_to_first_token_ms`).
 *       3) Note: Codex `input_tokens` INCLUDES `cached_input_tokens` (uncached = input - cached),
 *          whereas Antigravity `input_tokens` EXCLUDES `cache_read_tokens` (totalInput = in + cache).
 *   - Cost estimation:
 *       Externalized to `%APPDATA%\clawbridge\prices.json` (auto-created on first start, hot-reloaded,
 *       graceful fallback to built-in defaults if corrupted/missing without crashing).
 *   - Throttling:
 *       During `generating`, pushes at most 1 `metrics.data` frame per 1000ms; pushes immediately on
 *       state transitions (`idle <-> generating`), session switch, or explicit `metrics.get`.
 *   - Security:
 *       NEVER logs or emits any OAuth token, CSRF token, or prompt/message body.
 */

import fs from 'node:fs';
import path from 'node:path';
import { EventEmitter } from 'node:events';
import { logger } from './logger.mjs';
import { getPricesFilePath, getSessionModelChoice } from './claw-config.mjs';
import { discoverActiveAntigravityHub } from './antigravity-executor.mjs';

const PUSH_THROTTLE_MS = 1000;
const GENERATING_POLL_MS = 1000;
const TPS_WINDOW_MS = 3000;
const MIN_LIVE_WINDOW_SEC = 0.35;
const MIN_DECODE_SPAN_SEC = 0.15;
const MIN_E2E_SPAN_SEC = 0.05;
const DEFAULT_AG_COMPACTION_MAX = 256_000;
const DEFAULT_AG_PHYSICAL_MAX = 1_048_576;
const TRANSCRIPT_MIN_TPS = 8;
const TRANSCRIPT_MAX_TURN_SEC = 600;
const PRICES_RELOAD_CHECK_MS = 5000;

export const DEFAULT_PRICES_UPDATED_AT = '2026-11-26';

export const BUILTIN_DEFAULT_RATE = {
  key: 'default',
  label: '默认兜底单价',
  inUncached: 2.0,
  inCached: 0.2,
  cacheWrite: 2.0,
  out: 10.0,
};

export function createDefaultPricesConfig() {
  return {
    version: 1,
    updatedAt: DEFAULT_PRICES_UPDATED_AT,
    currency: 'USD',
    unit: 'per_1M_tokens',
    note: '公开 API 挂牌单价（USD / 1M tokens），仅供参考；支持自行增改模型与单价，保存后桌面桥自动热加载。',
    default: {
      label: BUILTIN_DEFAULT_RATE.label,
      inUncached: BUILTIN_DEFAULT_RATE.inUncached,
      inCached: BUILTIN_DEFAULT_RATE.inCached,
      cacheWrite: BUILTIN_DEFAULT_RATE.cacheWrite,
      out: BUILTIN_DEFAULT_RATE.out,
    },
    models: [
      {
        key: 'gemini_4_neon',
        label: 'Gemini 4 Neon',
        match: ['gemini-4-neon', 'gemini 4 neon', 'neon'],
        inUncached: 3.0,
        inCached: 0.15,
        cacheWrite: 3.0,
        out: 15.0,
      },
      {
        key: 'gemini_4_argon',
        label: 'Gemini 4 Argon / Carbon',
        match: ['gemini-4-carbon', 'gemini 4 carbon', 'gemini-4-argon', 'gemini 4 argon', 'carbon', 'argon'],
        inUncached: 2.0,
        inCached: 0.1,
        cacheWrite: 2.0,
        out: 10.0,
        priceChange: {
          from: '2026-12-01',
          inUncached: 4.0,
          inCached: 0.2,
          cacheWrite: 4.0,
          out: 20.0,
        },
      },
      {
        key: 'gemini_4_hydrogen',
        label: 'Gemini 4 Hydrogen',
        match: ['gemini-4-hydrogen', 'gemini 4 hydrogen', 'hydrogen'],
        inUncached: 1.5,
        inCached: 0.075,
        cacheWrite: 1.5,
        out: 7.5,
      },
      {
        key: 'gemini_3_1_pro',
        label: 'Gemini 3.1 Pro',
        match: ['gemini-3.1-pro', 'gemini 3.1 pro', 'gemini-3-1-pro'],
        tierThreshold: 200000,
        inUncached: 2.0,
        inCached: 0.2,
        cacheWrite: 2.0,
        out: 12.0,
        high: {
          inUncached: 4.0,
          inCached: 0.4,
          cacheWrite: 4.0,
          out: 18.0,
        },
      },
      {
        key: 'gemini_3_8_flash',
        label: 'Gemini 3.6–3.8 Flash',
        match: ['gemini-3.8-flash', 'gemini 3.8 flash', 'gemini-3.7-flash', 'gemini-3.6-flash', '3.8-flash', '3.8 flash'],
        inUncached: 0.75,
        inCached: 0.075,
        cacheWrite: 0.75,
        out: 3.75,
        priceChange: {
          from: '2027-01-01',
          inUncached: 1.5,
          inCached: 0.15,
          cacheWrite: 1.5,
          out: 7.5,
        },
      },
      {
        key: 'gemini_3_flash',
        label: 'Gemini 3 Flash',
        match: ['gemini-3-flash', 'gemini 3 flash'],
        inUncached: 0.5,
        inCached: 0.05,
        cacheWrite: 0.5,
        out: 3.0,
      },
      {
        key: 'gemini_2_5_pro',
        label: 'Gemini 2.5 Pro',
        match: ['gemini-2.5-pro', 'gemini 2.5 pro'],
        tierThreshold: 200000,
        inUncached: 1.25,
        inCached: 0.125,
        cacheWrite: 1.25,
        out: 10.0,
        high: {
          inUncached: 2.5,
          inCached: 0.25,
          cacheWrite: 2.5,
          out: 15.0,
        },
      },
      {
        key: 'gemini_2_5_flash',
        label: 'Gemini 2.5 Flash',
        match: ['gemini-2.5-flash', 'gemini 2.5 flash'],
        inUncached: 0.3,
        inCached: 0.03,
        cacheWrite: 0.3,
        out: 2.5,
      },
      {
        key: 'claude_opus_5_5',
        label: 'Claude Opus 5.5',
        match: ['claude-opus-5.5', 'claude-opus-5-5', 'claude opus 5.5', 'opus 5.5', 'opus-5.5'],
        inUncached: 4.0,
        inCached: 0.2,
        cacheWrite: 5.0,
        out: 20.0,
      },
      {
        key: 'claude_sonnet_5_5',
        label: 'Claude Sonnet 5.5',
        match: ['claude-sonnet-5.5', 'claude-sonnet-5-5', 'claude sonnet 5.5', 'sonnet 5.5', 'sonnet-5.5'],
        inUncached: 2.0,
        inCached: 0.1,
        cacheWrite: 2.5,
        out: 10.0,
      },
      {
        key: 'claude_opus_4_6',
        label: 'Claude Opus 4.6–5',
        match: ['claude-opus', 'claude opus', 'opus'],
        inUncached: 5.0,
        inCached: 0.5,
        cacheWrite: 6.25,
        out: 25.0,
      },
      {
        key: 'claude_sonnet_4_6',
        label: 'Claude Sonnet 4.6',
        match: ['claude-sonnet', 'claude sonnet', 'sonnet', 'claude'],
        inUncached: 3.0,
        inCached: 0.3,
        cacheWrite: 3.75,
        out: 15.0,
      },
      {
        key: 'gpt_6_sol',
        label: 'GPT-6 Sol (Codex)',
        match: ['gpt-6.2-sol', 'gpt-6.1-sol', 'gpt-6-sol'],
        inUncached: 2.5,
        inCached: 0.25,
        cacheWrite: 2.5,
        out: 15.0,
      },
      {
        key: 'gpt_6_astra',
        label: 'GPT-6 Astra (Codex)',
        match: ['gpt-6.2-astra', 'gpt-6.1-astra', 'gpt-6-astra'],
        inUncached: 1.75,
        inCached: 0.175,
        cacheWrite: 1.75,
        out: 14.0,
      },
      {
        key: 'gpt_5_codex',
        label: 'GPT-5.x Codex',
        match: ['gpt-5.6', 'gpt-5.4', 'gpt-5.3', 'gpt-5.2', 'gpt-5.1', 'gpt-5-codex', 'gpt-5'],
        inUncached: 1.25,
        inCached: 0.125,
        cacheWrite: 1.25,
        out: 10.0,
      },
      {
        key: 'gpt_oss_120b',
        label: 'GPT-OSS 120B',
        match: ['gpt-oss-120b', 'gpt oss 120b', '120b'],
        inUncached: 0.15,
        inCached: 0.075,
        cacheWrite: 0.15,
        out: 0.6,
      },
      {
        key: 'openai_o3',
        label: 'OpenAI o3',
        match: ['o3-pro', 'o3'],
        inUncached: 2.0,
        inCached: 0.5,
        cacheWrite: 2.0,
        out: 8.0,
      },
      {
        key: 'openai_o4_mini',
        label: 'OpenAI o4-mini',
        match: ['o4-mini', 'o3-mini'],
        inUncached: 1.1,
        inCached: 0.275,
        cacheWrite: 1.1,
        out: 4.4,
      },
    ],
  };
}

function isValidRateObj(r) {
  return (
    r &&
    typeof r === 'object' &&
    Number.isFinite(Number(r.inUncached)) &&
    Number.isFinite(Number(r.out)) &&
    Number(r.inUncached) >= 0 &&
    Number(r.out) >= 0
  );
}

function normalizeRate(r, fallback = BUILTIN_DEFAULT_RATE) {
  const inUncached = Number.isFinite(Number(r?.inUncached)) ? Number(r.inUncached) : fallback.inUncached;
  const inCached = Number.isFinite(Number(r?.inCached)) ? Number(r.inCached) : inUncached * 0.1;
  const cacheWrite = Number.isFinite(Number(r?.cacheWrite)) ? Number(r.cacheWrite) : inUncached;
  const out = Number.isFinite(Number(r?.out)) ? Number(r.out) : fallback.out;
  return { inUncached, inCached, cacheWrite, out };
}

export class PricingBook {
  constructor() {
    this.config = createDefaultPricesConfig();
    this.loadedMtimeMs = -1;
    this.lastCheckAt = 0;
    this.lastWarnMtimeMs = -1;
    this.corrupted = false;
  }

  ensureAndLoad({ force = false } = {}) {
    const now = Date.now();
    if (!force && now - this.lastCheckAt < PRICES_RELOAD_CHECK_MS) {
      return this.config;
    }
    this.lastCheckAt = now;
    const p = getPricesFilePath();

    try {
      if (!fs.existsSync(p)) {
        const fresh = createDefaultPricesConfig();
        fs.mkdirSync(path.dirname(p), { recursive: true });
        const tmp = `${p}.tmp`;
        fs.writeFileSync(tmp, JSON.stringify(fresh, null, 2), 'utf-8');
        fs.renameSync(tmp, p);
        this.config = fresh;
        this.loadedMtimeMs = fs.statSync(p).mtimeMs;
        this.corrupted = false;
        logger.info('Metrics', `Generated default model pricing table at ${p} (updatedAt=${fresh.updatedAt})`);
        return this.config;
      }

      const st = fs.statSync(p);
      if (!force && st.mtimeMs === this.loadedMtimeMs) {
        return this.config;
      }

      const raw = JSON.parse(fs.readFileSync(p, 'utf-8'));
      if (!raw || typeof raw !== 'object' || !Array.isArray(raw.models)) {
        throw new Error('prices.json 缺少有效的 models 数组');
      }
      const defRate = isValidRateObj(raw.default)
        ? { label: String(raw.default.label || BUILTIN_DEFAULT_RATE.label), ...normalizeRate(raw.default) }
        : { ...BUILTIN_DEFAULT_RATE };

      const validModels = [];
      for (const m of raw.models) {
        if (!m || !Array.isArray(m.match) || !isValidRateObj(m)) continue;
        validModels.push({
          key: String(m.key || m.label || 'custom'),
          label: String(m.label || m.key || 'Custom Model'),
          match: m.match.map((s) => String(s || '').toLowerCase()).filter(Boolean),
          ...normalizeRate(m, defRate),
          tierThreshold: Number(m.tierThreshold || 0) || 0,
          high: isValidRateObj(m.high) ? normalizeRate(m.high, defRate) : null,
          priceChange:
            m.priceChange && m.priceChange.from && isValidRateObj(m.priceChange)
              ? { from: String(m.priceChange.from), ...normalizeRate(m.priceChange, defRate) }
              : null,
        });
      }

      this.config = {
        version: Number(raw.version || 1),
        updatedAt: String(raw.updatedAt || DEFAULT_PRICES_UPDATED_AT),
        currency: String(raw.currency || 'USD'),
        default: defRate,
        models: validModels,
      };
      this.loadedMtimeMs = st.mtimeMs;
      this.corrupted = false;
    } catch (err) {
      let curMtime = 0;
      try { curMtime = fs.statSync(p).mtimeMs; } catch { /* ignore */ }
      if (curMtime !== this.lastWarnMtimeMs) {
        this.lastWarnMtimeMs = curMtime;
        logger.warn('Metrics', `Failed parsing prices.json (${err.message}); falling back to built-in default pricing`);
      }
      this.config = {
        version: 1,
        updatedAt: DEFAULT_PRICES_UPDATED_AT,
        currency: 'USD',
        default: { ...BUILTIN_DEFAULT_RATE },
        models: [],
      };
      this.corrupted = true;
    }
    return this.config;
  }

  resolveModel(modelStr) {
    this.ensureAndLoad();
    const m = String(modelStr || '').toLowerCase().trim();
    if (m && !this.corrupted) {
      for (const entry of this.config.models) {
        for (const pat of entry.match) {
          if (pat && m.includes(pat)) {
            return {
              profile: entry,
              priceDefaulted: false,
              updatedAt: this.config.updatedAt,
            };
          }
        }
      }
    }
    return {
      profile: {
        key: 'default',
        label: this.config.default?.label || BUILTIN_DEFAULT_RATE.label,
        ...normalizeRate(this.config.default, BUILTIN_DEFAULT_RATE),
        tierThreshold: 0,
        high: null,
        priceChange: null,
      },
      priceDefaulted: true,
      updatedAt: this.config.updatedAt,
    };
  }

  computeCost({ inUncached = 0, inCached = 0, cacheWrite = 0, outTokens = 0, createdAt = null }, modelStr) {
    const { profile, priceDefaulted, updatedAt } = this.resolveModel(modelStr);
    const totalInput = Math.max(0, inUncached) + Math.max(0, inCached) + Math.max(0, cacheWrite);
    let tier = profile;
    if (profile.tierThreshold > 0 && profile.high && totalInput > profile.tierThreshold) {
      tier = profile.high;
    } else if (profile.priceChange?.from) {
      const at = typeof createdAt === 'number' ? createdAt : Date.parse(createdAt || '');
      const when = Number.isFinite(at) ? at : Date.now();
      const changeAt = Date.parse(profile.priceChange.from);
      if (Number.isFinite(changeAt) && when >= changeAt) {
        tier = profile.priceChange;
      }
    }
    const costUsd =
      (Math.max(0, inUncached) / 1_000_000) * tier.inUncached +
      (Math.max(0, inCached) / 1_000_000) * tier.inCached +
      (Math.max(0, cacheWrite) / 1_000_000) * (tier.cacheWrite ?? tier.inUncached) +
      (Math.max(0, outTokens) / 1_000_000) * tier.out;
    return {
      costUsd: Number.isFinite(costUsd) ? costUsd : 0,
      priceDefaulted,
      pricingLabel: profile.label,
      pricingUpdatedAt: updatedAt,
    };
  }
}

// ─── Pure Helper Functions ──────────────────────────────────────────────────

export function parseDurationSec(dur) {
  if (!dur) return 0;
  if (typeof dur === 'number') return Number.isFinite(dur) ? dur : 0;
  const s = String(dur).trim();
  if (s.endsWith('ms')) return (parseFloat(s.slice(0, -2)) || 0) / 1000;
  if (s.endsWith('s')) return parseFloat(s.slice(0, -1)) || 0;
  return parseFloat(s) || 0;
}

export function stripHtmlTags(html) {
  return String(html || '')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&nbsp;/gi, ' ')
    .replace(/&lt;/gi, '<')
    .replace(/&gt;/gi, '>')
    .replace(/&amp;/gi, '&')
    .replace(/&quot;/gi, '"');
}

/**
 * Honest heuristic token estimator for unpersisted mid-stream text deltas ONLY.
 * Always paired with `estimated: true` in protocol frames.
 */
export function estimateTokensFromText(text) {
  const s = String(text || '');
  if (!s) return 0;
  const cjkMatches = s.match(/[\u3000-\u9fff\uf900-\ufaff]/g);
  const cjkCount = cjkMatches ? cjkMatches.length : 0;
  const otherCount = Math.max(0, s.length - cjkCount);
  return Math.max(0, cjkCount / 1.7 + otherCount / 3.8);
}

function round1(n) {
  return Math.round(Number(n) * 10) / 10;
}

function round6(n) {
  return Math.round(Number(n) * 1_000_000) / 1_000_000;
}

function getGeminiAntigravityDir() {
  const home = process.env.USERPROFILE || process.env.HOME || '.';
  return path.join(home, '.gemini', 'antigravity');
}

// ─── Sliding Window Tracker for Live Mid-Stream TPS ─────────────────────────

class SlidingTpsTracker {
  constructor(windowMs = TPS_WINDOW_MS) {
    this.windowMs = windowMs;
    this.samples = []; // { ts, tokens }
    this.totalEstTokens = 0;
    this.firstDeltaTs = null;
    this.lastDeltaTs = null;
  }

  reset() {
    this.samples = [];
    this.totalEstTokens = 0;
    this.firstDeltaTs = null;
    this.lastDeltaTs = null;
  }

  addDeltaTokens(tokens, ts = Date.now()) {
    const t = Math.max(0, Number(tokens) || 0);
    if (t <= 0) return;
    if (this.firstDeltaTs === null) this.firstDeltaTs = ts;
    this.lastDeltaTs = ts;
    this.totalEstTokens += t;
    this.samples.push({ ts, tokens: t });
    this._prune(ts);
  }

  _prune(now = Date.now()) {
    const cutoff = now - this.windowMs;
    while (this.samples.length > 0 && this.samples[0].ts < cutoff) {
      this.samples.shift();
    }
  }

  getWindowTps(now = Date.now()) {
    this._prune(now);
    if (this.samples.length < 2) return null;
    const spanMs = Math.max(now - this.samples[0].ts, this.samples[this.samples.length - 1].ts - this.samples[0].ts);
    const spanSec = spanMs / 1000;
    if (spanSec < MIN_LIVE_WINDOW_SEC) return null;
    let sum = 0;
    for (const s of this.samples) sum += s.tokens;
    if (sum <= 0) return null;
    return round1(sum / spanSec);
  }
}

// ─── Main MetricsService ────────────────────────────────────────────────────

export class MetricsService extends EventEmitter {
  constructor({ sessionService = null, codexClient = null, enabled = true } = {}) {
    super();
    this.sessionService = sessionService;
    this.codexClient = codexClient;
    this.enabled = enabled !== false;

    this.pricing = new PricingBook();
    this.subscription = null; // { sessionId, source }
    this.cachedSnapshot = null;

    // File parse caches keyed by path -> { mtimeMs, size, parsed }
    this.agTranscriptCache = new Map();
    this.codexRolloutCache = new Map();

    // Live generation state for the subscribed session
    this.live = this._createEmptyLiveState();

    // Throttle / polling state
    this.pollTimer = null;
    this.lastPushAt = 0;
    this.lastPushSig = '';
    this.pendingPushTimer = null;
    this.refreshInFlight = false;
    this.started = false;

    // Bound listener refs for clean stop()
    this._onCodexTurnLifecycle = (ev) => this._handleCodexTurnLifecycle(ev);
    this._onCodexStreamDelta = (ev) => this._handleCodexStreamDelta(ev);
    this._onCodexTokenUsage = (ev) => this._handleCodexTokenUsage(ev);
    this._onSessionStream = (ev) => this._handleSessionStream(ev);
  }

  _createEmptyLiveState() {
    return {
      sessionId: '',
      source: '',
      state: 'idle', // 'idle' | 'generating'
      userSentAt: null,
      turnStartedAt: null,
      firstDeltaAt: null,
      lastDeltaAt: null,
      // Antigravity CDP live_tail tracking
      cdpPlainTextLen: 0,
      cdpBaselineStepCount: 0,
      cdpTracker: new SlidingTpsTracker(TPS_WINDOW_MS),
      // Codex live stdio delta & usage tracking
      codexTracker: new SlidingTpsTracker(TPS_WINDOW_MS),
      codexUnpersistedEstTokens: 0,
      codexTurnExactOutTokens: 0,
      codexTurnExactCostUsd: 0,
      codexSessionCostBeforeTurn: 0,
      codexTurnStepsSeen: 0,
      codexLastUsage: null, // { inputTokens, cachedInputTokens, cacheWriteInputTokens, outputTokens, reasoningOutputTokens, modelContextWindow }
      codexFinalizedTurn: null, // frozen exact stats when turn completes before rollout flushes
    };
  }

  start() {
    if (this.started) return;
    this.started = true;
    this.pricing.ensureAndLoad({ force: true });

    if (!this.enabled) {
      logger.info('Metrics', 'MetricsService disabled by config (metricsEnabled=false)');
      return;
    }

    if (this.codexClient) {
      this.codexClient.on('turnLifecycle', this._onCodexTurnLifecycle);
      this.codexClient.on('streamDelta', this._onCodexStreamDelta);
      this.codexClient.on('tokenUsage', this._onCodexTokenUsage);
    }
    if (this.sessionService) {
      this.sessionService.on('session.stream', this._onSessionStream);
    }

    this.pollTimer = setInterval(() => {
      if (!this.subscription) return;
      if (this.live.state === 'generating') {
        this.refreshSubscribed({ reason: 'generating_tick' }).catch(() => {});
      }
    }, GENERATING_POLL_MS);
    this.pollTimer.unref?.();

    logger.info('Metrics', `MetricsService started (prices: ${getPricesFilePath()})`);
  }

  stop() {
    this.started = false;
    if (this.pollTimer) {
      clearInterval(this.pollTimer);
      this.pollTimer = null;
    }
    if (this.pendingPushTimer) {
      clearTimeout(this.pendingPushTimer);
      this.pendingPushTimer = null;
    }
    if (this.codexClient) {
      this.codexClient.off('turnLifecycle', this._onCodexTurnLifecycle);
      this.codexClient.off('streamDelta', this._onCodexStreamDelta);
      this.codexClient.off('tokenUsage', this._onCodexTokenUsage);
    }
    if (this.sessionService) {
      this.sessionService.off('session.stream', this._onSessionStream);
    }
    this.subscription = null;
    this.cachedSnapshot = null;
  }

  async setSubscription(sessionId, source = 'antigravity') {
    const sid = String(sessionId || '').trim();
    const src = source === 'codex' ? 'codex' : 'antigravity';
    if (!this.enabled || !sid) return null;

    const changed = !this.subscription || this.subscription.sessionId !== sid || this.subscription.source !== src;
    this.subscription = { sessionId: sid, source: src };
    if (changed) {
      this.live = this._createEmptyLiveState();
      this.live.sessionId = sid;
      this.live.source = src;
      this.lastPushSig = '';
      this.cachedSnapshot = null;
    }

    const snap = await this.computeMetricsForSession(sid, src);
    this.cachedSnapshot = snap;
    this._scheduleEmit(snap, { immediate: true });
    return snap;
  }

  clearSubscription() {
    this.subscription = null;
    this.live = this._createEmptyLiveState();
    this.cachedSnapshot = null;
    this.lastPushSig = '';
    if (this.pendingPushTimer) {
      clearTimeout(this.pendingPushTimer);
      this.pendingPushTimer = null;
    }
  }

  onUserSend({ sessionId, source = 'antigravity', ts = Date.now() } = {}) {
    if (!this.enabled || !sessionId) return;
    if (!this.subscription || this.subscription.sessionId !== sessionId) return;

    const prevState = this.live.state;
    this.live.state = 'generating';
    this.live.userSentAt = ts;
    this.live.turnStartedAt = ts;
    this.live.firstDeltaAt = null;
    this.live.lastDeltaAt = null;
    this.live.cdpPlainTextLen = 0;
    this.live.cdpTracker.reset();
    this.live.codexTracker.reset();
    this.live.codexUnpersistedEstTokens = 0;
    this.live.codexTurnExactOutTokens = 0;
    this.live.codexTurnExactCostUsd = 0;
    this.live.codexSessionCostBeforeTurn = this.cachedSnapshot?.estCostSession || 0;
    this.live.codexTurnStepsSeen = 0;
    this.live.codexFinalizedTurn = null;

    if (source === 'antigravity') {
      const tr = this._readAntigravityTranscriptCached(sessionId);
      this.live.cdpBaselineStepCount = tr?.plannerSteps?.length || 0;
    }

    if (prevState !== 'generating') {
      this.refreshSubscribed({ immediate: true, reason: 'user_send' }).catch(() => {});
    }
  }

  onUserSendFailed({ sessionId } = {}) {
    if (!this.enabled || !sessionId) return;
    if (!this.subscription || this.subscription.sessionId !== sessionId) return;
    if (this.live.state === 'generating' && this.live.firstDeltaAt === null) {
      this.live.state = 'idle';
      this.refreshSubscribed({ immediate: true, reason: 'user_send_failed' }).catch(() => {});
    }
  }

  async getMetricsSnapshot({ sessionId = '', source = '' } = {}) {
    if (!this.enabled) {
      return {
        ok: true,
        enabled: false,
        sessionId: sessionId || this.subscription?.sessionId || '',
        source: source || this.subscription?.source || '',
        ts: Date.now(),
      };
    }
    const sid = String(sessionId || this.subscription?.sessionId || '').trim();
    const src = (source || this.subscription?.source || 'antigravity') === 'codex' ? 'codex' : 'antigravity';
    if (!sid) {
      return { ok: true, enabled: true, available: false, sessionId: '', source: src, ts: Date.now() };
    }
    const snap = await this.computeMetricsForSession(sid, src);
    if (this.subscription && this.subscription.sessionId === sid && this.subscription.source === src) {
      this.cachedSnapshot = snap;
    }
    return snap;
  }

  async refreshSubscribed({ immediate = false, reason = 'poll' } = {}) {
    if (!this.enabled || !this.subscription) return null;
    if (this.refreshInFlight && !immediate) return this.cachedSnapshot;
    this.refreshInFlight = true;
    try {
      const { sessionId, source } = this.subscription;
      const snap = await this.computeMetricsForSession(sessionId, source);
      const prevState = this.cachedSnapshot?.state || 'idle';
      const stateChanged = snap.state !== prevState;
      this.cachedSnapshot = snap;
      this._scheduleEmit(snap, { immediate: immediate || stateChanged });
      return snap;
    } catch (err) {
      logger.warn('Metrics', `Refresh failed (${reason}): ${err.message}`);
      return this.cachedSnapshot;
    } finally {
      this.refreshInFlight = false;
    }
  }

  _buildEmitSignature(snap) {
    if (!snap) return '';
    return [
      snap.sessionId,
      snap.source,
      snap.state,
      snap.model,
      snap.ttftMs ?? '',
      snap.decodeTps ?? '',
      snap.e2eTps ?? '',
      snap.outputTokens ?? '',
      snap.outputTokensEstimated ? 1 : 0,
      snap.contextPct ?? '',
      snap.cacheHitPct ?? '',
      snap.estCostReply ?? '',
      snap.estCostSession ?? '',
      snap.dataSource ?? '',
    ].join('|');
  }

  _scheduleEmit(snap, { immediate = false } = {}) {
    if (!snap || !this.enabled) return;
    const sig = this._buildEmitSignature(snap);
    if (!immediate && sig === this.lastPushSig) return;

    const now = Date.now();
    const elapsed = now - this.lastPushAt;

    if (immediate || elapsed >= PUSH_THROTTLE_MS) {
      if (this.pendingPushTimer) {
        clearTimeout(this.pendingPushTimer);
        this.pendingPushTimer = null;
      }
      this.lastPushAt = now;
      this.lastPushSig = sig;
      this.emit('updated', snap);
      return;
    }

    // Throttle during generation: coalesce into a single delayed frame
    if (!this.pendingPushTimer) {
      const waitMs = Math.max(50, PUSH_THROTTLE_MS - elapsed);
      this.pendingPushTimer = setTimeout(() => {
        this.pendingPushTimer = null;
        if (this.cachedSnapshot) {
          const latestSig = this._buildEmitSignature(this.cachedSnapshot);
          if (latestSig !== this.lastPushSig) {
            this.lastPushAt = Date.now();
            this.lastPushSig = latestSig;
            this.emit('updated', this.cachedSnapshot);
          }
        }
      }, waitMs);
      this.pendingPushTimer.unref?.();
    }
  }

  // ─── Live Event Hooks ──────────────────────────────────────────────────────

  _isSubscribedCodexThread(threadId) {
    if (!this.subscription || this.subscription.source !== 'codex') return false;
    const sid = this.subscription.sessionId;
    if (threadId === sid) return true;
    const fork = this.codexClient?.continuationForks?.get(sid);
    return Boolean(fork?.forkThreadId && fork.forkThreadId === threadId);
  }

  _handleCodexTurnLifecycle({ threadId, phase, ts }) {
    if (!this.enabled || !this._isSubscribedCodexThread(threadId)) return;
    const now = ts || Date.now();

    if (phase === 'started') {
      const alreadyResetBySend = this.live.userSentAt && now - this.live.userSentAt < 5000;
      this.live.state = 'generating';
      this.live.turnStartedAt = alreadyResetBySend ? this.live.userSentAt : now;
      this.live.firstDeltaAt = null;
      this.live.lastDeltaAt = null;
      this.live.codexTracker.reset();
      this.live.codexUnpersistedEstTokens = 0;
      this.live.codexTurnExactOutTokens = 0;
      this.live.codexTurnExactCostUsd = 0;
      if (!alreadyResetBySend) {
        this.live.codexSessionCostBeforeTurn = this.cachedSnapshot?.estCostSession || 0;
      }
      this.live.codexTurnStepsSeen = 0;
      this.live.codexFinalizedTurn = null;
      this.refreshSubscribed({ immediate: true, reason: 'codex_turn_started' }).catch(() => {});
    } else if (phase === 'completed') {
      const startedAt = this.live.turnStartedAt || this.live.userSentAt || now;
      const e2eSec = Math.max(0, (now - startedAt) / 1000);
      const ttftMs =
        this.live.firstDeltaAt !== null ? Math.max(0, Math.round(this.live.firstDeltaAt - startedAt)) : null;
      const decodeSpanSec =
        this.live.firstDeltaAt !== null && this.live.lastDeltaAt !== null
          ? Math.max(0, (this.live.lastDeltaAt - this.live.firstDeltaAt) / 1000)
          : 0;
      const exactOut = this.live.codexTurnExactOutTokens;
      const estOut = Math.round(this.live.codexTracker.totalEstTokens);
      const finalOut = exactOut > 0 ? exactOut : estOut;
      const outEstimated = exactOut <= 0 && estOut > 0;

      this.live.codexFinalizedTurn = {
        completedAt: now,
        ttftMs,
        ttftEstimated: false,
        outputTokens: finalOut,
        outputTokensEstimated: outEstimated,
        decodeTps:
          finalOut > 0 && decodeSpanSec >= MIN_DECODE_SPAN_SEC ? round1(finalOut / decodeSpanSec) : null,
        decodeTpsEstimated: outEstimated,
        e2eTps: finalOut > 0 && e2eSec >= MIN_E2E_SPAN_SEC ? round1(finalOut / e2eSec) : null,
        e2eTpsEstimated: outEstimated,
        estCostReply: this.live.codexTurnExactCostUsd > 0 ? round6(this.live.codexTurnExactCostUsd) : null,
      };
      this.live.state = 'idle';
      this.live.codexUnpersistedEstTokens = 0;
      this.refreshSubscribed({ immediate: true, reason: 'codex_turn_completed' }).catch(() => {});
    }
  }

  _handleCodexStreamDelta({ threadId, delta, ts }) {
    if (!this.enabled || !this._isSubscribedCodexThread(threadId)) return;
    if (!delta) return;
    const now = ts || Date.now();
    if (this.live.state !== 'generating') {
      this.live.state = 'generating';
      this.live.turnStartedAt = this.live.turnStartedAt || now;
    }
    if (this.live.firstDeltaAt === null) {
      this.live.firstDeltaAt = now;
    }
    this.live.lastDeltaAt = now;

    const estDelta = estimateTokensFromText(delta);
    this.live.codexTracker.addDeltaTokens(estDelta, now);
    this.live.codexUnpersistedEstTokens += estDelta;

    // Push throttled live snapshot synchronously from cached base + live overlay
    const snap = this._buildCodexLiveOverlaySnapshot(now);
    if (snap) {
      this.cachedSnapshot = snap;
      this._scheduleEmit(snap, { immediate: false });
    }
  }

  _handleCodexTokenUsage({ threadId, tokenUsage, ts }) {
    if (!this.enabled || !this._isSubscribedCodexThread(threadId)) return;
    if (!tokenUsage) return;
    const last = tokenUsage.last || null;
    const total = tokenUsage.total || null;
    const ctxWin = Number(tokenUsage.modelContextWindow || 0) || null;

    this.live.codexLastUsage = {
      last,
      total,
      modelContextWindow: ctxWin,
      ts: ts || Date.now(),
    };

    if (last && this.live.state === 'generating') {
      const stepOut = Number(last.outputTokens || 0);
      const stepInTotal = Number(last.inputTokens || 0);
      const stepInCached = Number(last.cachedInputTokens || 0);
      const stepCacheWrite = Number(last.cacheWriteInputTokens || 0);
      const stepInUncached = Math.max(0, stepInTotal - stepInCached);

      this.live.codexTurnExactOutTokens += stepOut;
      this.live.codexTurnStepsSeen += 1;
      this.live.codexUnpersistedEstTokens = 0; // reset unpersisted delta counter for next LLM step

      const modelStr = this._resolveCodexModelName(this.subscription.sessionId);
      const c = this.pricing.computeCost(
        { inUncached: stepInUncached, inCached: stepInCached, cacheWrite: stepCacheWrite, outTokens: stepOut },
        modelStr
      );
      this.live.codexTurnExactCostUsd += c.costUsd;
    }

    this.refreshSubscribed({ immediate: false, reason: 'codex_token_usage' }).catch(() => {});
  }

  _handleSessionStream(streamPayload) {
    if (!this.enabled || !this.subscription || !streamPayload) return;
    const { sessionId, source } = this.subscription;
    const payloadSid = streamPayload.session?.id || streamPayload.sessionId || '';
    if (payloadSid && payloadSid !== sessionId) return;

    if (source === 'antigravity') {
      if (streamPayload.mode === 'snapshot') {
        const isRunning = streamPayload.session?.status === 'running';
        const prevState = this.live.state;
        if (isRunning && prevState !== 'generating') {
          this.live.state = 'generating';
          if (!this.live.turnStartedAt) this.live.turnStartedAt = Date.now();
          this.live.cdpPlainTextLen = 0;
          this.live.cdpTracker.reset();
          this.refreshSubscribed({ immediate: true, reason: 'ag_snapshot_running' }).catch(() => {});
        } else if (!isRunning && prevState === 'generating') {
          this.live.state = 'idle';
          this.live.cdpPlainTextLen = 0;
          this.live.cdpTracker.reset();
          this.refreshSubscribed({ immediate: true, reason: 'ag_snapshot_idle' }).catch(() => {});
        } else {
          // Check if new step landed in transcript even while idle/running
          this.refreshSubscribed({ immediate: false, reason: 'ag_snapshot_update' }).catch(() => {});
        }
      } else if (streamPayload.mode === 'live_tail' && this.live.state === 'generating') {
        const liveMsg = streamPayload.liveMessage;
        if (!liveMsg) return;
        const plain = stripHtmlTags(liveMsg.text || '').trim();
        const now = liveMsg.ts || Date.now();

        if (plain.length > this.live.cdpPlainTextLen) {
          const deltaText = plain.slice(this.live.cdpPlainTextLen);
          this.live.cdpPlainTextLen = plain.length;
          if (this.live.firstDeltaAt === null && deltaText.trim().length > 0) {
            this.live.firstDeltaAt = now;
          }
          this.live.lastDeltaAt = now;
          this.live.cdpTracker.addDeltaTokens(estimateTokensFromText(deltaText), now);

          const overlay = this._buildAntigravityCdpOverlaySnapshot(now);
          if (overlay) {
            this.cachedSnapshot = overlay;
            this._scheduleEmit(overlay, { immediate: false });
          }
        }
      }
    }
  }

  // ─── Core Computation: Antigravity ─────────────────────────────────────────

  async _fetchAntigravityHubMetadata(sessionId) {
    const hub = discoverActiveAntigravityHub();
    if (!hub.ok) return null;
    try {
      const res = await fetch(
        `http://127.0.0.1:${hub.httpPort}/exa.language_server_pb.LanguageServerService/GetCascadeTrajectoryGeneratorMetadata`,
        {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'Connect-Protocol-Version': '1',
            'x-codeium-csrf-token': hub.csrfToken,
          },
          body: JSON.stringify({ cascadeId: sessionId, includeMessages: false }),
          signal: AbortSignal.timeout(1800),
        }
      );
      if (!res.ok) return null;
      const data = await res.json();
      const items = (data?.generatorMetadata || []).filter((m) => m && m.chatModel);
      return items.length > 0 ? items : null;
    } catch {
      return null;
    }
  }

  _readAntigravityTranscriptCached(sessionId) {
    const trPath = path.join(getGeminiAntigravityDir(), 'brain', sessionId, '.system_generated', 'logs', 'transcript.jsonl');
    if (!fs.existsSync(trPath)) return null;
    let st;
    try { st = fs.statSync(trPath); } catch { return null; }

    const cached = this.agTranscriptCache.get(trPath);
    if (cached && cached.mtimeMs === st.mtimeMs && cached.size === st.size) {
      return cached.parsed;
    }

    let raw = '';
    try { raw = fs.readFileSync(trPath, 'utf-8'); } catch { return null; }
    const lines = raw.split(/\r?\n/).filter((l) => l.trim().length > 0);
    const steps = [];
    for (const line of lines) {
      try {
        const s = JSON.parse(line);
        if (s && typeof s === 'object') steps.push(s);
      } catch { /* ignore malformed line */ }
    }

    let currentModel = '';
    let lastUserStepIndex = -1;
    const plannerSteps = [];

    for (let i = 0; i < steps.length; i++) {
      const s = steps[i];
      const stepIdx = s.step_index ?? i;

      if (
        (s.source === 'USER_EXPLICIT' && s.type === 'USER_INPUT') ||
        (s.source === 'SYSTEM' && s.type === 'SYSTEM_MESSAGE' && typeof s.content === 'string' && s.content.includes('手机端'))
      ) {
        lastUserStepIndex = stepIdx;
      }

      if (s.type === 'USER_INPUT' && typeof s.content === 'string') {
        for (const sm of s.content.matchAll(/<USER_SETTINGS_CHANGE>([\s\S]*?)<\/USER_SETTINGS_CHANGE>/g)) {
          for (const mm of sm[1].matchAll(/Model Selection` from ([^\n]+?) to ([^\n]+?)(?:\.\s+No need to comment|\.\s*$|\n|$)/g)) {
            currentModel = mm[2].trim();
          }
        }
      }

      if (s.type === 'PLANNER_RESPONSE') {
        const inUncached = Number(s.input_tokens || 0);
        const inCached = Number(s.cache_read_tokens || 0);
        const outTokens = Number(s.output_tokens || 0);
        const totalInput = inUncached + inCached;

        let estDurationSec = 0;
        const nextStep = steps[i + 1];
        const interrupted = Boolean(nextStep && nextStep.type === 'ERROR_MESSAGE');
        if (!interrupted && s.created_at && nextStep?.created_at && nextStep.type === 'GENERIC') {
          const dt = (Date.parse(nextStep.created_at) - Date.parse(s.created_at)) / 1000;
          if (!Number.isNaN(dt) && dt >= MIN_E2E_SPAN_SEC && dt <= TRANSCRIPT_MAX_TURN_SEC && outTokens / dt >= TRANSCRIPT_MIN_TPS) {
            estDurationSec = dt;
          }
        }

        const modelSlug = String(s.model || s.model_id || currentModel || '').trim();
        plannerSteps.push({
          stepIndex: stepIdx,
          userReplyGroupStart: lastUserStepIndex,
          createdAt: s.created_at || null,
          interrupted,
          model: currentModel || modelSlug,
          modelId: modelSlug || currentModel,
          inUncached,
          inCached,
          cacheWrite: 0,
          totalInput,
          outTokens,
          thinkTokens: 0,
          respTokens: outTokens,
          ttftSec: 0,
          streamSec: 0,
          e2eSec: estDurationSec,
          source: 'transcript',
        });
      }
    }

    const parsed = { plannerSteps, lastUserStepIndex, lastModel: currentModel };
    if (this.agTranscriptCache.size > 16) this.agTranscriptCache.clear();
    this.agTranscriptCache.set(trPath, { mtimeMs: st.mtimeMs, size: st.size, parsed });
    return parsed;
  }

  async _computeAntigravityMetrics(sessionId) {
    const [rpcItems, tr] = await Promise.all([
      this._fetchAntigravityHubMetadata(sessionId),
      Promise.resolve(this._readAntigravityTranscriptCached(sessionId)),
    ]);

    const tSteps = tr?.plannerSteps || [];
    const tByStep = new Map();
    for (let i = 0; i < tSteps.length; i++) {
      tByStep.set(Number(tSteps[i].stepIndex), i);
    }

    const mergedTurns = [];
    let rpcUsed = false;

    if (rpcItems && rpcItems.length > 0) {
      rpcUsed = true;
      const matchedT = new Set();

      for (let i = 0; i < rpcItems.length; i++) {
        const item = rpcItems[i];
        const cm = item.chatModel || {};
        const stepIndices = Array.isArray(item.stepIndices) ? item.stepIndices : [];
        let tIdx = -1;
        for (const rawIdx of stepIndices) {
          const cand = tByStep.get(Number(rawIdx));
          if (cand !== undefined && !matchedT.has(cand)) {
            tIdx = cand;
            break;
          }
        }
        if (tIdx === -1 && stepIndices.length === 0 && rpcItems.length === tSteps.length && !matchedT.has(i)) {
          tIdx = i;
        }
        const tTurn = tIdx >= 0 ? tSteps[tIdx] : null;
        if (tIdx >= 0) matchedT.add(tIdx);

        const usage = cm.usage || {};
        const ctxMeta = cm.chatStartMetadata?.contextWindowMetadata || {};
        const inUncached = Number(usage.inputTokens ?? tTurn?.inUncached ?? 0);
        const inCached = Number(usage.cacheReadTokens ?? tTurn?.inCached ?? 0);
        const cacheWrite = Number(usage.cacheWriteTokens ?? 0);
        const totalInput = inUncached + inCached + cacheWrite;
        const outTokens = Number(usage.outputTokens ?? tTurn?.outTokens ?? 0);
        const thinkTokens = Number(usage.thinkingOutputTokens ?? 0);
        const respTokens = Number(
          usage.responseOutputTokens ?? (outTokens >= thinkTokens ? outTokens - thinkTokens : outTokens)
        );
        const ttftSec = parseDurationSec(cm.timeToFirstToken);
        const streamSec = parseDurationSec(cm.streamingDuration);
        const hasRpcE2e = ttftSec + streamSec > 0;
        const e2eSec = hasRpcE2e ? ttftSec + streamSec : tTurn?.e2eSec || 0;
        const stepIndex = tTurn?.stepIndex ?? stepIndices[0] ?? i;
        const modelDisplay = cm.modelDisplayName || tTurn?.model || cm.responseModel || usage.model || '';
        const modelId = cm.responseModel || tTurn?.modelId || usage.model || cm.model || modelDisplay;
        const contextUsed = totalInput > 0 ? totalInput : Number(ctxMeta.estimatedTokensUsed || 0);
        const contextMax = Number(ctxMeta.maxContextTokens || DEFAULT_AG_COMPACTION_MAX);

        mergedTurns.push({
          stepIndex: Number(stepIndex),
          order: tSteps.length + i,
          createdAt: tTurn?.createdAt || cm.chatStartMetadata?.createdAt || null,
          interrupted: Boolean(tTurn?.interrupted),
          model: modelDisplay,
          modelId,
          inUncached,
          inCached,
          cacheWrite,
          totalInput,
          outTokens,
          thinkTokens,
          respTokens,
          hasThinkSplit: usage.thinkingOutputTokens !== undefined || usage.responseOutputTokens !== undefined,
          ttftSec,
          streamSec,
          e2eSec,
          e2eFromRpc: hasRpcE2e,
          contextUsed,
          contextMax,
          source: 'hub_rpc',
        });
      }

      // Include any earlier transcript turns not present in RPC window
      for (let i = 0; i < tSteps.length; i++) {
        if (matchedT.has(i)) continue;
        const tt = tSteps[i];
        mergedTurns.push({
          ...tt,
          order: i,
          hasThinkSplit: false,
          e2eFromRpc: false,
          contextUsed: tt.totalInput,
          contextMax: DEFAULT_AG_COMPACTION_MAX,
        });
      }

      mergedTurns.sort((a, b) => a.stepIndex - b.stepIndex || a.order - b.order);
    } else {
      for (let i = 0; i < tSteps.length; i++) {
        const tt = tSteps[i];
        mergedTurns.push({
          ...tt,
          order: i,
          hasThinkSplit: false,
          e2eFromRpc: false,
          contextUsed: tt.totalInput,
          contextMax: DEFAULT_AG_COMPACTION_MAX,
        });
      }
    }

    const savedChoice = getSessionModelChoice('antigravity', sessionId);
    const lastTurn = mergedTurns[mergedTurns.length - 1] || null;
    const activeModel = lastTurn?.model || savedChoice?.modelLabel || tr?.lastModel || '';
    const activeModelId = lastTurn?.modelId || savedChoice?.modelId || activeModel;

    // If a new PLANNER_RESPONSE landed while generating, reset CDP live delta buffer for next step
    if (this.live.sessionId === sessionId && mergedTurns.length > this.live.cdpBaselineStepCount) {
      this.live.cdpBaselineStepCount = mergedTurns.length;
      this.live.cdpPlainTextLen = 0;
      this.live.cdpTracker.reset();
    }

    // Group steps belonging to the current user reply (all planner steps after lastUserStepIndex)
    const lastUserStepIdx = tr?.lastUserStepIndex ?? -1;
    let replyTurns =
      lastUserStepIdx >= 0
        ? mergedTurns.filter((t) => t.stepIndex > lastUserStepIdx)
        : lastTurn
        ? [lastTurn]
        : [];
    if (replyTurns.length === 0 && lastTurn && this.live.state !== 'generating') {
      replyTurns = [lastTurn];
    }

    let estCostSession = 0;
    let estCostReply = 0;
    let replyOutTokens = 0;
    let replyThinkTokens = 0;
    let replyRespTokens = 0;
    let hasReplyThinkSplit = false;
    let lastPriceMeta = { priceDefaulted: false, pricingLabel: '', pricingUpdatedAt: this.pricing.config.updatedAt };

    const replySet = new Set(replyTurns);
    for (const t of mergedTurns) {
      const lookup = `${t.model || ''} ${t.modelId || ''}`.trim() || activeModel;
      const c = this.pricing.computeCost(t, lookup);
      estCostSession += c.costUsd;
      if (replySet.has(t)) {
        estCostReply += c.costUsd;
        replyOutTokens += t.outTokens;
        replyThinkTokens += t.thinkTokens || 0;
        replyRespTokens += t.respTokens || 0;
        if (t.hasThinkSplit) hasReplyThinkSplit = true;
      }
      lastPriceMeta = c;
    }

    // Latest completed turn for TTFT / decodeTps / e2eTps / context / cacheHit
    const perfTurn = [...mergedTurns].reverse().find((t) => !t.interrupted && t.outTokens > 0) || lastTurn;
    const ctxTurn = [...mergedTurns].reverse().find((t) => t.contextUsed > 0) || lastTurn;

    let ttftMs = perfTurn && perfTurn.ttftSec > 0 ? Math.round(perfTurn.ttftSec * 1000) : null;
    let ttftEstimated = false;
    let decodeTps =
      perfTurn && !perfTurn.interrupted && perfTurn.streamSec >= MIN_DECODE_SPAN_SEC && perfTurn.outTokens > 0
        ? round1(perfTurn.outTokens / perfTurn.streamSec)
        : null;
    let decodeTpsEstimated = false;
    let e2eTps =
      perfTurn && !perfTurn.interrupted && perfTurn.e2eSec >= MIN_E2E_SPAN_SEC && perfTurn.outTokens > 0
        ? round1(perfTurn.outTokens / perfTurn.e2eSec)
        : null;
    let e2eTpsEstimated = Boolean(perfTurn && !perfTurn.e2eFromRpc && e2eTps !== null);

    const contextUsed = ctxTurn?.contextUsed > 0 ? ctxTurn.contextUsed : null;
    const contextMax = contextUsed !== null ? ctxTurn.contextMax || DEFAULT_AG_COMPACTION_MAX : null;
    const contextPct = contextUsed !== null && contextMax > 0 ? round1((contextUsed / contextMax) * 100) : null;
    const cacheHitPct =
      ctxTurn && ctxTurn.totalInput > 0 ? round1((ctxTurn.inCached / ctxTurn.totalInput) * 100) : null;

    const isGenerating =
      this.live.sessionId === sessionId && this.live.source === 'antigravity' && this.live.state === 'generating';

    const snap = {
      ok: true,
      enabled: true,
      available: mergedTurns.length > 0 || isGenerating,
      sessionId,
      source: 'antigravity',
      model: activeModel || 'Antigravity',
      modelId: activeModelId || '',
      state: isGenerating ? 'generating' : 'idle',
      dataSource: rpcUsed ? 'hub_rpc' : mergedTurns.length > 0 ? 'transcript' : 'none',
      ttftMs,
      ttftEstimated,
      decodeTps,
      decodeTpsEstimated,
      e2eTps,
      e2eTpsEstimated,
      tpsWindowSec: Math.round(TPS_WINDOW_MS / 1000),
      outputTokens: replyOutTokens,
      outputTokensEstimated: false,
      thinkingTokens: hasReplyThinkSplit ? replyThinkTokens : null,
      responseTokens: hasReplyThinkSplit ? replyRespTokens : null,
      contextUsed,
      contextMax,
      contextPhysicalMax: DEFAULT_AG_PHYSICAL_MAX,
      contextPct,
      cacheHitPct,
      estCostReply: round6(estCostReply),
      estCostSession: round6(estCostSession),
      costEstimated: false,
      priceDefaulted: lastPriceMeta.priceDefaulted,
      pricingLabel: lastPriceMeta.pricingLabel,
      pricingUpdatedAt: lastPriceMeta.pricingUpdatedAt,
      replyTurns: replyTurns.length,
      sessionTurns: mergedTurns.length,
      ts: Date.now(),
    };

    if (isGenerating) {
      return this._applyAntigravityCdpOverlay(snap, Date.now());
    }
    return snap;
  }

  _buildAntigravityCdpOverlaySnapshot(now) {
    if (!this.cachedSnapshot || this.cachedSnapshot.source !== 'antigravity') return null;
    return this._applyAntigravityCdpOverlay({ ...this.cachedSnapshot, state: 'generating', ts: now }, now);
  }

  _applyAntigravityCdpOverlay(baseSnap, now) {
    const cdpEstTokens = Math.round(this.live.cdpTracker.totalEstTokens);
    const liveTps = this.live.cdpTracker.getWindowTps(now);
    if (cdpEstTokens <= 0 && liveTps === null) {
      return baseSnap;
    }

    const out = { ...baseSnap, ts: now };
    if (liveTps !== null) {
      out.decodeTps = liveTps;
      out.decodeTpsEstimated = true;
      out.dataSource = baseSnap.dataSource === 'hub_rpc' ? 'hub_rpc+cdp_live' : 'cdp_live';
    }
    if (cdpEstTokens > 0) {
      out.outputTokens = (baseSnap.outputTokens || 0) + cdpEstTokens;
      out.outputTokensEstimated = true;
      const extraCost = this.pricing.computeCost({ outTokens: cdpEstTokens }, out.model || out.modelId);
      out.estCostReply = round6((baseSnap.estCostReply || 0) + extraCost.costUsd);
      out.estCostSession = round6((baseSnap.estCostSession || 0) + extraCost.costUsd);
      out.costEstimated = true;
    }
    if (this.live.firstDeltaAt !== null && this.live.turnStartedAt !== null && out.replyTurns === 0) {
      out.ttftMs = Math.max(0, Math.round(this.live.firstDeltaAt - this.live.turnStartedAt));
      out.ttftEstimated = true;
    }
    return out;
  }

  // ─── Core Computation: Codex ───────────────────────────────────────────────

  _resolveCodexModelName(sessionId) {
    const saved = getSessionModelChoice('codex', sessionId);
    if (saved?.modelId) return saved.modelId.split('#')[0];
    const cached = this.codexClient?.cachedThreads?.find((t) => t.id === sessionId);
    if (cached?.currentModelId) return String(cached.currentModelId).split('#')[0];
    return '';
  }

  _resolveCodexRolloutFiles(sessionId) {
    if (!this.sessionService) return [];
    const codexList = this.sessionService._readCodexSessions?.() || [];
    const files = [];
    const visited = new Set();

    const addWithAncestors = (tid, hintPath = '') => {
      if (!tid || visited.has(tid) || visited.size > 4) return;
      visited.add(tid);
      const fp = this.sessionService._resolveCodexFilePath?.(tid, codexList, hintPath) || '';
      if (!fp || !fs.existsSync(fp)) return;
      const parsed = this._parseCodexRolloutMetricsCached(fp);
      if (parsed?.forkedFromId && !visited.has(parsed.forkedFromId)) {
        addWithAncestors(parsed.forkedFromId, '');
      }
      files.push({ threadId: tid, filePath: fp, parsed });
    };

    const meta = codexList.find((s) => s.id === sessionId);
    addWithAncestors(sessionId, meta?._filePath || '');

    const cont = this.codexClient?.continuationForks?.get(sessionId);
    if (cont?.forkThreadId && !visited.has(cont.forkThreadId)) {
      addWithAncestors(cont.forkThreadId, cont.forkPath || '');
    }
    return files;
  }

  _parseCodexRolloutMetricsCached(filePath) {
    if (!filePath || !fs.existsSync(filePath)) return null;
    let st;
    try { st = fs.statSync(filePath); } catch { return null; }

    const cached = this.codexRolloutCache.get(filePath);
    if (cached && cached.mtimeMs === st.mtimeMs && cached.size === st.size) {
      return cached.parsed;
    }

    let raw = '';
    try { raw = fs.readFileSync(filePath, 'utf-8'); } catch { return null; }
    const lines = raw.split(/\r?\n/).filter((l) => l.trim().length > 0);

    let forkedFromId = '';
    let currentModel = '';
    let currentTurnId = '';
    let modelContextWindow = null;

    // Per-turn aggregates keyed by turnId (preserves insertion order)
    const turnsMap = new Map();
    const ensureTurn = (tid, ts) => {
      const key = tid || `turn-${turnsMap.size}`;
      if (!turnsMap.has(key)) {
        turnsMap.set(key, {
          turnId: key,
          model: currentModel,
          startedAt: ts,
          completedAt: null,
          durationMs: null,
          ttftMs: null,
          inUncached: 0,
          inCached: 0,
          cacheWrite: 0,
          outTokens: 0,
          reasoningTokens: 0,
          lastInputTokens: 0,
          lastCachedTokens: 0,
          stepCount: 0,
          hasTokenUsageRecord: false,
        });
      }
      return turnsMap.get(key);
    };

    let lastTokenCountInfo = null;
    const seenResponseIds = new Set();

    for (const line of lines) {
      let obj;
      try { obj = JSON.parse(line); } catch { continue; }
      const ts = obj.timestamp ? Date.parse(obj.timestamp) || null : null;
      const p = obj.payload;
      if (!p) continue;

      if (obj.type === 'session_meta' && p.forked_from_id && !forkedFromId) {
        forkedFromId = String(p.forked_from_id);
      } else if (obj.type === 'turn_context') {
        if (p.model) currentModel = String(p.model);
        if (p.turn_id) currentTurnId = String(p.turn_id);
        const t = ensureTurn(currentTurnId, ts);
        if (currentModel) t.model = currentModel;
      } else if (obj.type === 'event_msg' && p.type === 'task_started') {
        if (p.turn_id) currentTurnId = String(p.turn_id);
        if (p.model_context_window) modelContextWindow = Number(p.model_context_window);
        const t = ensureTurn(currentTurnId, p.started_at ? Number(p.started_at) * 1000 : ts);
        if (currentModel && !t.model) t.model = currentModel;
      } else if (obj.type === 'token_usage_record' && p.usage) {
        const respId = p.response_id || `${obj.ordinal || ''}`;
        if (respId && seenResponseIds.has(respId)) continue;
        if (respId) seenResponseIds.add(respId);

        const tid = p.turn_id || currentTurnId;
        const t = ensureTurn(tid, ts);
        const u = p.usage;
        // CRITICAL Codex schema rule: input_tokens INCLUDES cached_input_tokens!
        const inTotal = Number(u.input_tokens || 0);
        const inCached = Number(u.cached_input_tokens || 0);
        const cacheWrite = Number(u.cache_write_input_tokens || 0);
        const inUncached = Math.max(0, inTotal - inCached);
        const outTokens = Number(u.output_tokens || 0);
        const reasoning = Number(u.reasoning_output_tokens || 0);

        t.inUncached += inUncached;
        t.inCached += inCached;
        t.cacheWrite += cacheWrite;
        t.outTokens += outTokens;
        t.reasoningTokens += reasoning;
        t.lastInputTokens = inTotal;
        t.lastCachedTokens = inCached;
        t.stepCount += 1;
        t.hasTokenUsageRecord = true;
      } else if (obj.type === 'event_msg' && p.type === 'token_count' && p.info) {
        if (p.info.model_context_window) modelContextWindow = Number(p.info.model_context_window);
        lastTokenCountInfo = p.info;
      } else if (obj.type === 'event_msg' && p.type === 'task_complete') {
        const tid = p.turn_id || currentTurnId;
        const t = ensureTurn(tid, ts);
        if (Number.isFinite(Number(p.duration_ms))) t.durationMs = Number(p.duration_ms);
        if (Number.isFinite(Number(p.time_to_first_token_ms))) t.ttftMs = Number(p.time_to_first_token_ms);
        if (p.completed_at) t.completedAt = Number(p.completed_at) * 1000;
      }
    }

    const turns = [...turnsMap.values()];
    const parsed = {
      forkedFromId,
      lastModel: currentModel,
      modelContextWindow,
      turns,
      lastTokenCountInfo,
    };

    if (this.codexRolloutCache.size > 20) this.codexRolloutCache.clear();
    this.codexRolloutCache.set(filePath, { mtimeMs: st.mtimeMs, size: st.size, parsed });
    return parsed;
  }

  _computeCodexMetrics(sessionId) {
    const rolloutChain = this._resolveCodexRolloutFiles(sessionId);
    const allTurns = [];
    let lastModel = '';
    let modelContextWindow = null;
    let lastTokenCountInfo = null;

    for (const item of rolloutChain) {
      const p = item.parsed;
      if (!p) continue;
      if (p.lastModel) lastModel = p.lastModel;
      if (p.modelContextWindow) modelContextWindow = p.modelContextWindow;
      if (p.lastTokenCountInfo) lastTokenCountInfo = p.lastTokenCountInfo;
      allTurns.push(...p.turns);
    }

    const effectiveThreadId = this.codexClient?.continuationForks?.get(sessionId)?.forkThreadId || sessionId;
    const rpcUsageEntry =
      this.live.codexLastUsage ||
      this.codexClient?.latestTokenUsage?.get(effectiveThreadId) ||
      this.codexClient?.latestTokenUsage?.get(sessionId) ||
      null;

    if (rpcUsageEntry?.modelContextWindow) {
      modelContextWindow = rpcUsageEntry.modelContextWindow;
    } else if (rpcUsageEntry?.tokenUsage?.modelContextWindow) {
      modelContextWindow = rpcUsageEntry.tokenUsage.modelContextWindow;
    }

    const activeModel = lastModel || this._resolveCodexModelName(sessionId) || 'Codex';

    // Compute session cost across all turns (or fallback to lastTokenCountInfo.total_token_usage)
    let estCostSession = 0;
    let lastPriceMeta = this.pricing.computeCost({}, activeModel);
    let turnsWithUsage = allTurns.filter((t) => t.stepCount > 0 || t.outTokens > 0);

    if (turnsWithUsage.length > 0) {
      for (const t of turnsWithUsage) {
        const c = this.pricing.computeCost(
          {
            inUncached: t.inUncached,
            inCached: t.inCached,
            cacheWrite: t.cacheWrite,
            outTokens: t.outTokens,
            createdAt: t.startedAt,
          },
          t.model || activeModel
        );
        estCostSession += c.costUsd;
        lastPriceMeta = c;
      }
    } else if (lastTokenCountInfo?.total_token_usage) {
      const tot = lastTokenCountInfo.total_token_usage;
      const inTot = Number(tot.input_tokens || 0);
      const inC = Number(tot.cached_input_tokens || 0);
      const c = this.pricing.computeCost(
        {
          inUncached: Math.max(0, inTot - inC),
          inCached: inC,
          cacheWrite: Number(tot.cache_write_input_tokens || 0),
          outTokens: Number(tot.output_tokens || 0),
        },
        activeModel
      );
      estCostSession = c.costUsd;
      lastPriceMeta = c;
    }

    const lastTurn = turnsWithUsage[turnsWithUsage.length - 1] || allTurns[allTurns.length - 1] || null;
    let estCostReply = 0;
    if (lastTurn) {
      estCostReply = this.pricing.computeCost(
        {
          inUncached: lastTurn.inUncached,
          inCached: lastTurn.inCached,
          cacheWrite: lastTurn.cacheWrite,
          outTokens: lastTurn.outTokens,
          createdAt: lastTurn.startedAt,
        },
        lastTurn.model || activeModel
      ).costUsd;
    }

    // Context & cache hit from newest available source (live RPC > lastTokenCountInfo > lastTurn)
    const rpcLast = rpcUsageEntry?.last || rpcUsageEntry?.tokenUsage?.last || null;
    const tcLast = lastTokenCountInfo?.last_token_usage || null;
    const latestInTotal = Number(rpcLast?.inputTokens ?? tcLast?.input_tokens ?? lastTurn?.lastInputTokens ?? 0);
    const latestInCached = Number(rpcLast?.cachedInputTokens ?? tcLast?.cached_input_tokens ?? lastTurn?.lastCachedTokens ?? 0);

    const contextUsed = latestInTotal > 0 ? latestInTotal : null;
    const contextMax = contextUsed !== null && modelContextWindow ? modelContextWindow : null;
    const contextPct = contextUsed !== null && contextMax > 0 ? round1((contextUsed / contextMax) * 100) : null;
    const cacheHitPct = latestInTotal > 0 ? round1((latestInCached / latestInTotal) * 100) : null;

    let ttftMs = lastTurn?.ttftMs ?? null;
    let ttftEstimated = false;
    let decodeTps = null;
    let decodeTpsEstimated = false;
    let e2eTps =
      lastTurn && lastTurn.outTokens > 0 && lastTurn.durationMs && lastTurn.durationMs / 1000 >= MIN_E2E_SPAN_SEC
        ? round1(lastTurn.outTokens / (lastTurn.durationMs / 1000))
        : null;
    let e2eTpsEstimated = false;
    let outputTokens = lastTurn?.outTokens ?? Number(tcLast?.output_tokens || 0);
    let outputTokensEstimated = false;
    let thinkingTokens = lastTurn && lastTurn.reasoningTokens > 0 ? lastTurn.reasoningTokens : null;
    let responseTokens =
      thinkingTokens !== null ? Math.max(0, outputTokens - thinkingTokens) : null;

    const isSubscribed = this.live.sessionId === sessionId && this.live.source === 'codex';
    const isGenerating =
      (isSubscribed && this.live.state === 'generating') ||
      Boolean(this.codexClient?.activeTurns?.has(effectiveThreadId)) ||
      Boolean(this.codexClient?.activeTurns?.has(sessionId));

    // Merge freshly finalized turn stats (gives exact decodeTps from live stdio delta span!)
    if (isSubscribed && !isGenerating && this.live.codexFinalizedTurn) {
      const fin = this.live.codexFinalizedTurn;
      if (fin.ttftMs !== null && ttftMs === null) ttftMs = fin.ttftMs;
      if (fin.decodeTps !== null) {
        decodeTps = fin.decodeTps;
        decodeTpsEstimated = fin.decodeTpsEstimated;
      }
      if (fin.e2eTps !== null && e2eTps === null) {
        e2eTps = fin.e2eTps;
        e2eTpsEstimated = fin.e2eTpsEstimated;
      }
      if (fin.outputTokens > 0 && outputTokens === 0) {
        outputTokens = fin.outputTokens;
        outputTokensEstimated = fin.outputTokensEstimated;
      }
      if (fin.estCostReply !== null && estCostReply === 0) {
        estCostReply = fin.estCostReply;
      }
    }

    const baseSnap = {
      ok: true,
      enabled: true,
      available: allTurns.length > 0 || Boolean(rpcLast) || Boolean(tcLast) || isGenerating,
      sessionId,
      source: 'codex',
      model: activeModel,
      modelId: activeModel,
      state: isGenerating ? 'generating' : 'idle',
      dataSource: rpcLast ? 'codex_rpc' : allTurns.length > 0 ? 'codex_rollout' : 'none',
      ttftMs,
      ttftEstimated,
      decodeTps,
      decodeTpsEstimated,
      e2eTps,
      e2eTpsEstimated,
      tpsWindowSec: Math.round(TPS_WINDOW_MS / 1000),
      outputTokens,
      outputTokensEstimated,
      thinkingTokens,
      responseTokens,
      contextUsed,
      contextMax,
      contextPct,
      cacheHitPct,
      estCostReply: round6(estCostReply),
      estCostSession: round6(estCostSession),
      costEstimated: false,
      priceDefaulted: lastPriceMeta.priceDefaulted,
      pricingLabel: lastPriceMeta.pricingLabel,
      pricingUpdatedAt: lastPriceMeta.pricingUpdatedAt,
      replyTurns: lastTurn ? 1 : 0,
      sessionTurns: allTurns.length,
      ts: Date.now(),
    };

    if (isGenerating && isSubscribed) {
      return this._applyCodexLiveOverlay(baseSnap, Date.now());
    }
    return baseSnap;
  }

  _buildCodexLiveOverlaySnapshot(now) {
    if (!this.cachedSnapshot || this.cachedSnapshot.source !== 'codex') return null;
    return this._applyCodexLiveOverlay({ ...this.cachedSnapshot, state: 'generating', ts: now }, now);
  }

  _applyCodexLiveOverlay(baseSnap, now) {
    const out = { ...baseSnap, state: 'generating', dataSource: 'codex_rpc', ts: now };
    const startedAt = this.live.turnStartedAt || this.live.userSentAt;

    if (this.live.firstDeltaAt !== null && startedAt !== null) {
      out.ttftMs = Math.max(0, Math.round(this.live.firstDeltaAt - startedAt));
      out.ttftEstimated = false;
    } else {
      out.ttftMs = null;
      out.ttftEstimated = false;
    }

    const liveWindowTps = this.live.codexTracker.getWindowTps(now);
    out.decodeTps = liveWindowTps;
    out.decodeTpsEstimated = liveWindowTps !== null;

    const exactTurnOut = this.live.codexTurnExactOutTokens;
    const unpersistedEst = Math.round(this.live.codexUnpersistedEstTokens);
    out.outputTokens = exactTurnOut + unpersistedEst;
    out.outputTokensEstimated = unpersistedEst > 0 || exactTurnOut === 0;

    if (startedAt !== null && out.outputTokens > 0) {
      const elapsedSec = (now - startedAt) / 1000;
      if (elapsedSec >= MIN_LIVE_WINDOW_SEC) {
        out.e2eTps = round1(out.outputTokens / elapsedSec);
        out.e2eTpsEstimated = out.outputTokensEstimated;
      }
    }

    const extraEstCost =
      unpersistedEst > 0 ? this.pricing.computeCost({ outTokens: unpersistedEst }, out.model).costUsd : 0;
    const liveReplyCost = this.live.codexTurnExactCostUsd + extraEstCost;
    out.estCostReply = round6(liveReplyCost);
    out.estCostSession = round6(
      Math.max(baseSnap.estCostSession || 0, (this.live.codexSessionCostBeforeTurn || 0) + liveReplyCost)
    );
    out.costEstimated = unpersistedEst > 0;
    return out;
  }

  async computeMetricsForSession(sessionId, source = 'antigravity') {
    if (!this.enabled) {
      return { ok: true, enabled: false, sessionId, source, ts: Date.now() };
    }
    try {
      if (source === 'codex') {
        return this._computeCodexMetrics(sessionId);
      }
      return await this._computeAntigravityMetrics(sessionId);
    } catch (err) {
      logger.warn('Metrics', `computeMetricsForSession(${source}:${sessionId}) error: ${err.message}`);
      return {
        ok: true,
        enabled: true,
        available: false,
        sessionId,
        source,
        state: 'idle',
        ts: Date.now(),
      };
    }
  }
}
