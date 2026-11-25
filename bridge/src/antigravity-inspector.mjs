/**
 * Antigravity Installation & Version Inspector
 * Spec 8.1: Detect Antigravity version on Bridge startup and log it; target locked to 2.34.0.
 */

import fs from 'node:fs';
import path from 'node:path';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { logger } from './logger.mjs';

const execFileAsync = promisify(execFile);

export async function inspectAntigravityVersion(exePath, targetVersion = '2.34.0') {
  try {
    if (!fs.existsSync(exePath)) {
      logger.warn('Antigravity', `Executable not found at ${exePath}`);
      return { installed: false, version: null, matchesTarget: false };
    }

    const dir = path.dirname(exePath);
    let detectedVersion = null;

    // 1. Read Windows PE ProductVersion / FileVersion via PowerShell
    try {
      const { stdout } = await execFileAsync(
        'powershell.exe',
        [
          '-NoProfile',
          '-NonInteractive',
          '-Command',
          `$v = (Get-Item -LiteralPath '${exePath.replace(/'/g, "''")}').VersionInfo; Write-Output "$($v.ProductVersion)|$($v.FileVersion)"`,
        ],
        { timeout: 6000, windowsHide: true }
      );
      const parts = stdout.trim().split('|').map((s) => s.trim()).filter(Boolean);
      if (parts.length > 0) {
        detectedVersion = parts[0];
      }
    } catch {
      // fallback below
    }

    // 2. Fallback: read app.asar header JSON if needed
    if (!detectedVersion) {
      const versionFile = path.join(dir, 'version');
      if (fs.existsSync(versionFile)) {
        detectedVersion = `electron-${fs.readFileSync(versionFile, 'utf-8').trim()}`;
      }
    }

    const matchesTarget = Boolean(detectedVersion && detectedVersion.startsWith(targetVersion));
    if (matchesTarget) {
      logger.info('Antigravity', `Detected Antigravity version ${detectedVersion} (matches locked target ${targetVersion})`);
    } else {
      logger.warn(
        'Antigravity',
        `Detected Antigravity version "${detectedVersion || 'unknown'}" (locked target is ${targetVersion}; verify CDP selectors if UI changed)`
      );
    }

    return {
      installed: true,
      version: detectedVersion,
      targetVersion,
      matchesTarget,
    };
  } catch (err) {
    logger.error('Antigravity', `Version check failed: ${err.message}`);
    return { installed: false, version: null, matchesTarget: false };
  }
}
