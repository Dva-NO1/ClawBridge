/**
 * Rolling 7-Day Sanitized Logger for ClawBridge Desktop
 * Hard security requirement (Section 7):
 *   - deviceToken, OAuth tokens, CSRF tokens NEVER enter logs
 *   - Log files roll daily and retain at most 7 days
 */

import fs from 'node:fs';
import path from 'node:path';

const RETENTION_DAYS = 7;

export function getAppDataDir() {
  const base = process.env.APPDATA || path.join(process.env.USERPROFILE || '.', 'AppData', 'Roaming');
  return path.join(base, 'clawbridge');
}

export function getLogDir() {
  return path.join(getAppDataDir(), 'logs');
}

export function sanitizeLogText(input) {
  if (input == null) return '';
  const str = typeof input === 'string' ? input : JSON.stringify(input);
  return str
    .replace(/"(deviceToken|lanToken|issuedToken|accessToken|refreshToken|access_token|refresh_token|csrf_token|token)"\s*:\s*"[^"]*"/gi, '"$1":"[REDACTED]"')
    .replace(/(--csrf_token[=\s]+)([a-zA-Z0-9_-]+)/gi, '$1[REDACTED]')
    .replace(/(Bearer\s+)[A-Za-z0-9._~+/=-]+/gi, '$1[REDACTED]')
    .replace(/\b[a-f0-9]{64}\b/gi, '[REDACTED_TOKEN]');
}

function todayDateStr() {
  const d = new Date();
  const yyyy = d.getFullYear();
  const mm = String(d.getMonth() + 1).padStart(2, '0');
  const dd = String(d.getDate()).padStart(2, '0');
  return `${yyyy}-${mm}-${dd}`;
}

let lastPruneDay = '';
const recentLogBuffer = [];
const MAX_RECENT_LOGS = 120;

export function getRecentLogs() {
  return [...recentLogBuffer];
}

export function pruneOldLogs() {
  try {
    const dir = getLogDir();
    if (!fs.existsSync(dir)) return;
    const cutoffMs = Date.now() - RETENTION_DAYS * 24 * 60 * 60 * 1000;
    for (const name of fs.readdirSync(dir)) {
      if (!name.startsWith('bridge-') || !name.endsWith('.log')) continue;
      const full = path.join(dir, name);
      const st = fs.statSync(full);
      if (st.mtimeMs < cutoffMs) {
        fs.unlinkSync(full);
      }
    }
  } catch {
    // ignore log cleanup errors
  }
}

export function log(level, scope, message, meta = undefined) {
  const now = new Date();
  const day = todayDateStr();
  if (day !== lastPruneDay) {
    lastPruneDay = day;
    pruneOldLogs();
  }

  const metaStr = meta !== undefined ? ' ' + sanitizeLogText(meta) : '';
  const cleanLine = `[${now.toLocaleTimeString('zh-CN', { hour12: false })}] [${level}] [${scope}] ${sanitizeLogText(message)}${metaStr}`;
  const line = `[${now.toISOString()}] [${level}] [${scope}] ${sanitizeLogText(message)}${metaStr}\n`;

  recentLogBuffer.push(cleanLine);
  if (recentLogBuffer.length > MAX_RECENT_LOGS) {
    recentLogBuffer.shift();
  }

  process.stdout.write(line);

  try {
    const dir = getLogDir();
    fs.mkdirSync(dir, { recursive: true });
    const file = path.join(dir, `bridge-${day}.log`);
    fs.appendFileSync(file, line, 'utf-8');
  } catch {
    // ignore disk log errors
  }
}

export const logger = {
  info: (scope, msg, meta) => log('INFO', scope, msg, meta),
  warn: (scope, msg, meta) => log('WARN', scope, msg, meta),
  error: (scope, msg, meta) => log('ERROR', scope, msg, meta),
};
