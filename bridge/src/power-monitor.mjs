/**
 * Windows Power / Hibernation Resume Monitor
 *
 * Spec 3.2 & 8.5:
 *   Laptop power policy is "Hibernate only (no sleep)"; Bridge MUST self-recover
 *   within 1 minute of waking from hibernation.
 *
 * Dual detection:
 *   1. Wall-clock jump detector (2s interval; >8s jump = system woke from hibernate/suspend)
 *   2. Windows SystemEvents.PowerModeChanged (Resume) background listener
 */

import { EventEmitter } from 'node:events';
import { spawn } from 'node:child_process';
import { logger } from './logger.mjs';

export class PowerMonitor extends EventEmitter {
  constructor({ tickMs = 2000, jumpThresholdMs = 8000 } = {}) {
    super();
    this.tickMs = tickMs;
    this.jumpThresholdMs = jumpThresholdMs;
    this.timer = null;
    this.psProc = null;
    this.lastEmitAt = 0;
    this.stopped = false;
  }

  start() {
    this.stopped = false;
    let lastTick = Date.now();

    this.timer = setInterval(() => {
      const now = Date.now();
      const delta = now - lastTick;
      lastTick = now;
      if (delta > this.jumpThresholdMs) {
        this._emitResume(`clock_jump_${delta}ms`);
      }
    }, this.tickMs);
    this.timer.unref?.();

    if (process.platform === 'win32') {
      this._startWindowsPowerWatcher();
    }
  }

  _emitResume(source) {
    const now = Date.now();
    // Debounce duplicate resume signals within 3 seconds
    if (now - this.lastEmitAt < 3000) return;
    this.lastEmitAt = now;
    logger.info('PowerMonitor', `System wake/resume detected (${source})`);
    this.emit('resume', { source, ts: now });
  }

  _startWindowsPowerWatcher() {
    try {
      const psScript = `
Add-Type -AssemblyName System.Windows.Forms -ErrorAction SilentlyContinue
$handler = [Microsoft.Win32.PowerModeChangedEventHandler]{
  param($sender, $e)
  [Console]::Out.WriteLine("POWER_MODE:" + $e.Mode.ToString())
  [Console]::Out.Flush()
}
[Microsoft.Win32.SystemEvents]::add_PowerModeChanged($handler)
while ($true) { Start-Sleep -Seconds 30 }
`;
      this.psProc = spawn(
        'powershell.exe',
        ['-NoProfile', '-NonInteractive', '-WindowStyle', 'Hidden', '-Command', psScript],
        { stdio: ['ignore', 'pipe', 'ignore'], windowsHide: true }
      );
      this.psProc.stdout.on('data', (chunk) => {
        const text = chunk.toString('utf-8');
        if (text.includes('POWER_MODE:Resume')) {
          this._emitResume('win32_power_mode_resume');
        } else if (text.includes('POWER_MODE:Suspend')) {
          logger.info('PowerMonitor', 'System entering hibernate/suspend');
          this.emit('suspend', { ts: Date.now() });
        }
      });
      this.psProc.on('exit', () => {
        this.psProc = null;
        if (!this.stopped) {
          const t = setTimeout(() => {
            if (!this.stopped) this._startWindowsPowerWatcher();
          }, 10_000);
          t.unref?.();
        }
      });
      this.psProc.unref?.();
    } catch (err) {
      logger.warn('PowerMonitor', `Win32 power event helper unavailable (clock-jump detector active): ${err.message}`);
    }
  }

  /** Used in automated self-test to simulate hibernate wake */
  simulateResume() {
    this._emitResume('simulated_resume');
  }

  stop() {
    this.stopped = true;
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = null;
    }
    if (this.psProc) {
      try {
        this.psProc.kill();
      } catch { /* ignore */ }
      this.psProc = null;
    }
  }
}
