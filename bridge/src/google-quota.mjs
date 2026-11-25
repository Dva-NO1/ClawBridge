/**
 * Google Dual-Model Quota Engine (Gemini + Claude/GPT, 5h & 7d windows)
 *
 * Spec 3.2 & 5.2 & 6 & 7:
 *   - Refreshes every 5 minutes (configurable) using local logged-in OAuth token
 *     against Google's `v1internal:retrieveUserQuotaSummary` (exact same endpoint as Antigravity Tools).
 *   - Extracts Plan Name (ULTRA / PRO) + Gemini (5h, 7d) + Claude/GPT (5h, 7d) used%, remaining%, resetAt.
 *   - Falls back to Antigravity Tools local quota snapshot or Bridge disk cache on transient failure,
 *     preserving the real `fetchedAt` timestamp (never faking stale data as live; >15m flagged stale).
 *   - Strictly local token usage: OAuth tokens NEVER leave this machine and NEVER enter logs.
 */

import fs from 'node:fs';
import path from 'node:path';
import { EventEmitter } from 'node:events';
import { getAppDataDir, logger } from './logger.mjs';

const QUOTA_SUMMARY_ENDPOINTS = [
  'https://daily-cloudcode-pa.googleapis.com/v1internal:retrieveUserQuotaSummary',
  'https://daily-cloudcode-pa.sandbox.googleapis.com/v1internal:retrieveUserQuotaSummary',
];

const STALE_THRESHOLD_MS = 15 * 60 * 1000; // 15 minutes

function getQuotaCachePath() {
  return path.join(getAppDataDir(), 'quota-cache.json');
}

function getAntigravityToolsDir() {
  const home = process.env.USERPROFILE || process.env.HOME || '.';
  return path.join(home, '.antigravity_tools');
}

function formatPlanLabel(tier) {
  const upper = String(tier || '').trim().toUpperCase();
  if (upper === 'ULTRA') return 'Google AI Ultra';
  if (upper === 'PRO') return 'Google AI Pro';
  if (upper === 'FREE') return 'Google AI Free';
  return upper || 'Antigravity';
}

function normalizeBucket(rawBucket) {
  if (!rawBucket) return null;
  const remFracRaw =
    typeof rawBucket.remainingFraction === 'number'
      ? rawBucket.remainingFraction
      : typeof rawBucket.remaining_fraction === 'number'
      ? rawBucket.remaining_fraction
      : 1;
  const remFrac = Math.max(0, Math.min(1, remFracRaw));
  // Match Antigravity Tools integer rounding (`Math.round(remainingFraction * 100)`)
  const remainingPercent = Math.round(remFrac * 100);
  const usedPercent = Math.max(0, Math.min(100, 100 - remainingPercent));
  const resetIso = rawBucket.resetTime || rawBucket.reset_time || null;
  const resetAt = resetIso ? Date.parse(resetIso) || null : null;

  return {
    bucketId: rawBucket.bucketId || rawBucket.bucket_id || '',
    window: rawBucket.window || '',
    remainingFraction: Number(remFrac.toFixed(4)),
    remainingPercent,
    usedPercent,
    resetAt,
    resetTimeIso: resetIso,
  };
}

function parseGroupsPayload(groups, tierRaw, fetchedAt, fromCache = false, source = 'live_api') {
  let geminiGroup = null;
  let claudeGptGroup = null;

  for (const g of groups || []) {
    const name = String(g.displayName || g.display_name || '').toLowerCase();
    const buckets = g.buckets || [];
    const hasGemini = name.includes('gemini') || buckets.some((b) => String(b.bucketId || b.bucket_id || '').startsWith('gemini'));
    const has3p =
      name.includes('claude') ||
      name.includes('gpt') ||
      buckets.some((b) => String(b.bucketId || b.bucket_id || '').startsWith('3p'));

    if (hasGemini && !geminiGroup) geminiGroup = g;
    else if (has3p && !claudeGptGroup) claudeGptGroup = g;
  }

  const extractCard = (group, defaultTitle) => {
    if (!group) return null;
    const buckets = group.buckets || [];
    const b5h = buckets.find((b) => b.window === '5h' || String(b.bucketId || b.bucket_id || '').endsWith('5h'));
    const b7d = buckets.find(
      (b) => b.window === 'weekly' || b.window === '7d' || String(b.bucketId || b.bucket_id || '').includes('weekly')
    );
    return {
      title: group.displayName || group.display_name || defaultTitle,
      description: group.description || '',
      window5h: normalizeBucket(b5h),
      window7d: normalizeBucket(b7d),
    };
  };

  const ts = fetchedAt || Date.now();
  return {
    available: true,
    planName: formatPlanLabel(tierRaw),
    tierCode: String(tierRaw || 'PRO').toUpperCase(),
    fetchedAt: ts,
    fromCache,
    isStale: Date.now() - ts > STALE_THRESHOLD_MS,
    source,
    gemini: extractCard(geminiGroup, 'Gemini Models'),
    claudeGpt: extractCard(claudeGptGroup, 'Claude / GPT Models'),
  };
}

function readLocalAntigravityToolsAccount() {
  const dir = getAntigravityToolsDir();
  const idxPath = path.join(dir, 'accounts.json');
  if (!fs.existsSync(idxPath)) return null;

  const idx = JSON.parse(fs.readFileSync(idxPath, 'utf-8'));
  const curId =
    idx.current_account_id ||
    idx.current_classic_account_id ||
    (Array.isArray(idx.accounts) && idx.accounts.find((a) => !a.disabled)?.id);
  if (!curId) return null;

  const accPath = path.join(dir, 'accounts', `${curId}.json`);
  if (!fs.existsSync(accPath)) return null;

  return JSON.parse(fs.readFileSync(accPath, 'utf-8'));
}

export class GoogleQuotaService extends EventEmitter {
  constructor({ refreshIntervalSec = 300 } = {}) {
    super();
    this.refreshIntervalMs = Math.max(60, refreshIntervalSec) * 1000;
    this.timer = null;
    this.cached = this._loadDiskCache();
    this.inFlight = null;
  }

  start() {
    this.refreshNow().catch(() => {});
    if (this.timer) clearInterval(this.timer);
    this.timer = setInterval(() => {
      this.refreshNow().catch(() => {});
    }, this.refreshIntervalMs);
    this.timer.unref?.();
  }

  stop() {
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = null;
    }
  }

  async getQuotaSnapshot({ forceRefresh = false } = {}) {
    if (forceRefresh || !this.cached || Date.now() - this.cached.fetchedAt > 60_000) {
      await this.refreshNow().catch(() => {});
    }
    if (this.cached) {
      return {
        ...this.cached,
        isStale: Date.now() - this.cached.fetchedAt > STALE_THRESHOLD_MS,
      };
    }
    return {
      available: false,
      error: '尚未获取到配额数据，请确认本机已登录账号',
      planName: 'Antigravity',
      tierCode: '',
      fetchedAt: null,
      fromCache: true,
      isStale: true,
      gemini: null,
      claudeGpt: null,
    };
  }

  async refreshNow() {
    if (this.inFlight) return this.inFlight;
    this.inFlight = this._doRefresh().finally(() => {
      this.inFlight = null;
    });
    return this.inFlight;
  }

  async _doRefresh() {
    let acc = null;
    try {
      acc = readLocalAntigravityToolsAccount();
    } catch (err) {
      logger.warn('Quota', `Failed reading local account store: ${err.message}`);
    }

    const tierRaw = acc?.quota?.subscription_tier || acc?.quota?.raw_tier || 'PRO';
    const accessToken = acc?.token?.access_token;
    const projectId = acc?.token?.project_id || '';

    // 1. Live Google API fetch using local OAuth access_token
    if (accessToken) {
      for (const endpoint of QUOTA_SUMMARY_ENDPOINTS) {
        try {
          const res = await fetch(endpoint, {
            method: 'POST',
            headers: {
              Authorization: `Bearer ${accessToken}`,
              'Content-Type': 'application/json',
              'User-Agent': 'antigravity/2.34.0 windows/amd64',
            },
            body: JSON.stringify(projectId ? { project: projectId } : {}),
            signal: AbortSignal.timeout(8_000),
          });

          if (res.ok) {
            const data = await res.json();
            if (Array.isArray(data.groups) && data.groups.length > 0) {
              const parsed = parseGroupsPayload(data.groups, tierRaw, Date.now(), false, 'google_api');
              this.cached = parsed;
              this._saveDiskCache(parsed);
              logger.info('Quota', 'Refreshed live 5h/7d quota from Google API', {
                tier: parsed.tierCode,
                gemini5hRem: parsed.gemini?.window5h?.remainingPercent,
                gemini7dRem: parsed.gemini?.window7d?.remainingPercent,
                claude5hRem: parsed.claudeGpt?.window5h?.remainingPercent,
                claude7dRem: parsed.claudeGpt?.window7d?.remainingPercent,
              });
              this.emit('updated', parsed);
              return parsed;
            }
          } else {
            logger.warn('Quota', `Google quota endpoint returned HTTP ${res.status}`);
          }
        } catch (err) {
          logger.warn('Quota', `Google quota fetch attempt failed: ${err.message}`);
        }
      }
    }

    // 2. Fallback to Antigravity Tools local snapshot (preserving its real observed timestamp!)
    if (Array.isArray(acc?.quota?.quota_groups) && acc.quota.quota_groups.length > 0) {
      const observedMs =
        acc.quota.quota_groups[0]?.buckets?.[0]?.observed_at ||
        (acc.quota.last_updated ? acc.quota.last_updated * 1000 : Date.now());
      if (!this.cached || observedMs >= this.cached.fetchedAt) {
        const fallback = parseGroupsPayload(
          acc.quota.quota_groups,
          tierRaw,
          observedMs,
          true,
          'antigravity_tools_cache'
        );
        this.cached = fallback;
        this._saveDiskCache(fallback);
        logger.info('Quota', 'Loaded quota fallback from Antigravity Tools local snapshot', {
          fetchedAtIso: new Date(observedMs).toISOString(),
          isStale: fallback.isStale,
        });
        this.emit('updated', fallback);
        return fallback;
      }
    }

    // 3. Keep previous disk cache with updated isStale flag
    if (this.cached) {
      this.cached = {
        ...this.cached,
        fromCache: true,
        isStale: Date.now() - this.cached.fetchedAt > STALE_THRESHOLD_MS,
      };
      return this.cached;
    }

    throw new Error('No live or cached quota available');
  }

  _loadDiskCache() {
    try {
      const p = getQuotaCachePath();
      if (fs.existsSync(p)) {
        const data = JSON.parse(fs.readFileSync(p, 'utf-8'));
        if (data && data.available && data.fetchedAt) {
          return {
            ...data,
            fromCache: true,
            isStale: Date.now() - data.fetchedAt > STALE_THRESHOLD_MS,
          };
        }
      }
    } catch { /* ignore */ }
    return null;
  }

  _saveDiskCache(snapshot) {
    try {
      const p = getQuotaCachePath();
      fs.mkdirSync(path.dirname(p), { recursive: true });
      const tmp = `${p}.tmp`;
      fs.writeFileSync(tmp, JSON.stringify(snapshot, null, 2), 'utf-8');
      fs.renameSync(tmp, p);
    } catch { /* ignore */ }
  }
}
