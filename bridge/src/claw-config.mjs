/**
 * ClawBridge Configuration Manager (%APPDATA%\clawbridge\config.json)
 *
 * All settings are user-editable and reload on Bridge restart:
 *   - relayUrl (default wss://relay.example.com, NEVER hardcoded)
 *   - deviceId, deviceToken
 *   - quotaRefreshIntervalSec
 *   - assistantSessionId, personaPath, schedules
 */

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { getAppDataDir, logger } from './logger.mjs';

export const PLACEHOLDER_RELAY_URL = 'wss://relay.example.com';

export function isPlaceholderRelayUrl(url) {
  const u = String(url || '').trim();
  return !u || /relay\.example\.com|your-domain|example\.com/i.test(u);
}

export function getDefaultDesktopDir() {
  const home = process.env.USERPROFILE || os.homedir();
  return path.join(home, 'Desktop');
}

export function getConfigPath() {
  return path.join(getAppDataDir(), 'config.json');
}

export function getPairingFilePath() {
  return path.join(getAppDataDir(), 'pairing-code.json');
}

export function getLanPairingFilePath() {
  return path.join(getAppDataDir(), 'lan-pairing.json');
}

export function readLanPairingInfo() {
  try {
    const p = getLanPairingFilePath();
    if (fs.existsSync(p)) {
      return JSON.parse(fs.readFileSync(p, 'utf-8')) || {};
    }
  } catch { /* ignore */ }
  return {};
}

export function writeLanPairingInfo(info) {
  try {
    const p = getLanPairingFilePath();
    fs.mkdirSync(path.dirname(p), { recursive: true });
    const tmp = `${p}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(info, null, 2), 'utf-8');
    fs.renameSync(tmp, p);
  } catch (err) {
    logger.warn('Config', `Failed writing lan-pairing.json: ${err.message}`);
  }
}

export function getRepairTriggerPath() {
  return path.join(getAppDataDir(), 'repair.trigger');
}

export function getStatusFilePath() {
  return path.join(getAppDataDir(), 'runtime-status.json');
}

export function getSessionModelsFilePath() {
  return path.join(getAppDataDir(), 'session-models.json');
}

export function getPricesFilePath() {
  return path.join(getAppDataDir(), 'prices.json');
}

export function getCreatedProjectsFilePath() {
  return path.join(getAppDataDir(), 'created-projects.json');
}

export function getModelCatalogCachePath() {
  return path.join(getAppDataDir(), 'model-catalog-cache.json');
}

export function loadCreatedProjectsMap() {
  try {
    const p = getCreatedProjectsFilePath();
    if (fs.existsSync(p)) {
      return JSON.parse(fs.readFileSync(p, 'utf-8')) || {};
    }
  } catch { /* ignore */ }
  return {};
}

export function saveCreatedProjectRecord(sessionId, record) {
  if (!sessionId || !record) return;
  const map = loadCreatedProjectsMap();
  map[sessionId] = { ...record, sessionId, updatedAt: Date.now() };
  // Keep newest 60 entries
  const entries = Object.entries(map).sort((a, b) => (b[1]?.updatedAt || 0) - (a[1]?.updatedAt || 0)).slice(0, 60);
  const pruned = Object.fromEntries(entries);
  try {
    const p = getCreatedProjectsFilePath();
    fs.mkdirSync(path.dirname(p), { recursive: true });
    const tmp = `${p}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(pruned, null, 2), 'utf-8');
    fs.renameSync(tmp, p);
  } catch (err) {
    logger.warn('Config', `Failed saving created-projects.json: ${err.message}`);
  }
}

export function loadModelCatalogCache() {
  try {
    const p = getModelCatalogCachePath();
    if (fs.existsSync(p)) {
      return JSON.parse(fs.readFileSync(p, 'utf-8')) || {};
    }
  } catch { /* ignore */ }
  return {};
}

export function saveModelCatalogCache(patch) {
  try {
    const cur = loadModelCatalogCache();
    const next = { ...cur, ...patch, updatedAt: Date.now() };
    const p = getModelCatalogCachePath();
    fs.mkdirSync(path.dirname(p), { recursive: true });
    const tmp = `${p}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(next, null, 2), 'utf-8');
    fs.renameSync(tmp, p);
  } catch { /* ignore */ }
}

export function loadSessionModelsMap() {
  try {
    const p = getSessionModelsFilePath();
    if (fs.existsSync(p)) {
      return JSON.parse(fs.readFileSync(p, 'utf-8')) || {};
    }
  } catch { /* ignore */ }
  return {};
}

export function saveSessionModelChoice(source, sessionId, { modelId, modelLabel }) {
  if (!source || !sessionId) return;
  const map = loadSessionModelsMap();
  const key = `${source}:${sessionId}`;
  if (!modelId) {
    delete map[key];
  } else {
    map[key] = {
      modelId: String(modelId),
      modelLabel: String(modelLabel || modelId),
      updatedAt: Date.now(),
    };
  }
  try {
    const p = getSessionModelsFilePath();
    fs.mkdirSync(path.dirname(p), { recursive: true });
    const tmp = `${p}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(map, null, 2), 'utf-8');
    fs.renameSync(tmp, p);
  } catch (err) {
    logger.warn('Config', `Failed saving session-models.json: ${err.message}`);
  }
}

export function getSessionModelChoice(source, sessionId) {
  const map = loadSessionModelsMap();
  return map[`${source}:${sessionId}`] || null;
}

function defaultConfig() {
  const repoPersona = path.resolve(import.meta.dirname, '..', '..', 'persona.md');
  const localAppData = process.env.LOCALAPPDATA || path.join(os.homedir(), 'AppData', 'Local');
  return {
    relayUrl: process.env.CLAWBRIDGE_RELAY_URL || PLACEHOLDER_RELAY_URL,
    deviceId: crypto.randomUUID(),
    deviceToken: '',
    lastBoundRelayUrl: '',
    trustSelfSigned: false,
    cdpPort: 9222,
    antigravityExePath: path.join(localAppData, 'Programs', 'antigravity', 'Antigravity.exe'),
    targetAntigravityVersion: '2.34.0',
    quotaRefreshIntervalSec: 300,
    metricsEnabled: true,
    lanEnabled: false,
    lanPort: 18791,
    lanToken: '',
    lastConnectionMode: 'wan',
    projectsRootDir: getDefaultDesktopDir(),
    assistantSessionId: '',
    personaPath: repoPersona,
    schedules: {
      morning: '07:30',
      night: '23:00',
    },
  };
}

export function loadConfig() {
  const cfgPath = getConfigPath();
  const base = defaultConfig();
  let loaded = {};

  try {
    if (fs.existsSync(cfgPath)) {
      loaded = JSON.parse(fs.readFileSync(cfgPath, 'utf-8'));
    }
  } catch (err) {
    logger.error('Config', `Failed to read ${cfgPath}, falling back to defaults: ${err.message}`);
  }

  const merged = {
    ...base,
    ...loaded,
    schedules: {
      ...base.schedules,
      ...(loaded.schedules || {}),
    },
  };

  if (!merged.deviceId) {
    merged.deviceId = crypto.randomUUID();
  }

  merged.relayUrl = String(merged.relayUrl || base.relayUrl).trim();
  merged.projectsRootDir = String(merged.projectsRootDir || '').trim() || getDefaultDesktopDir();
  merged.lanEnabled = Boolean(merged.lanEnabled);
  const parsedLanPort = Number(merged.lanPort);
  merged.lanPort = parsedLanPort > 0 && parsedLanPort <= 65535 ? parsedLanPort : 18791;
  merged.lanToken = typeof merged.lanToken === 'string' ? merged.lanToken.trim() : '';
  merged.lastConnectionMode = merged.lanEnabled
    ? 'lan'
    : String(merged.lastConnectionMode || 'wan').toLowerCase() === 'lan'
      ? 'lan'
      : 'wan';

  // Spec 5.5: If relayUrl changed since last bound session, clear old token & rotate deviceId if needed for clean re-pair
  if (merged.lastBoundRelayUrl && merged.lastBoundRelayUrl !== merged.relayUrl) {
    logger.info('Config', `Detected relayUrl change (${merged.lastBoundRelayUrl} -> ${merged.relayUrl}); clearing old relay token for re-pairing`);
    merged.deviceToken = '';
    merged.lastBoundRelayUrl = merged.relayUrl;
  } else if (!merged.lastBoundRelayUrl) {
    merged.lastBoundRelayUrl = merged.relayUrl;
  }

  saveConfig(merged);
  return merged;
}

export function saveConfig(cfg) {
  const cfgPath = getConfigPath();
  const dir = path.dirname(cfgPath);
  fs.mkdirSync(dir, { recursive: true });
  const tmp = `${cfgPath}.tmp`;
  fs.writeFileSync(tmp, JSON.stringify(cfg, null, 2), 'utf-8');
  fs.renameSync(tmp, cfgPath);
}

export function writePairingCodeInfo(info) {
  const p = getPairingFilePath();
  fs.mkdirSync(path.dirname(p), { recursive: true });
  fs.writeFileSync(p, JSON.stringify(info, null, 2), 'utf-8');
}

export function writeRuntimeStatus(status) {
  try {
    const p = getStatusFilePath();
    fs.mkdirSync(path.dirname(p), { recursive: true });
    const tmp = `${p}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify({ ...status, updatedAt: new Date().toISOString() }, null, 2), 'utf-8');
    fs.renameSync(tmp, p);
  } catch {
    // ignore transient status file errors
  }
}
