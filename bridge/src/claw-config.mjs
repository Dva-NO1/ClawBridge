/**
 * ClawBridge Configuration Manager (%APPDATA%\clawbridge\config.json)
 *
 * All settings are user-editable and reload on Bridge restart:
 *   - relayUrl (default placeholder wss://relay.example.com — replace with your own VPS relay URL)
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
  const s = String(url || '').trim();
  return !s || /relay\.example\.com|your-domain|example\.com/i.test(s);
}

export function getConfigPath() {
  return path.join(getAppDataDir(), 'config.json');
}

export function getPairingFilePath() {
  return path.join(getAppDataDir(), 'pairing-code.json');
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
