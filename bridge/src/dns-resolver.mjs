/**
 * Resilient DNS Lookup with DoH Fallback
 * Prevents post-hibernation Windows DNS lag or stale negative DNS cache from blocking WSS reconnects.
 */

import dns from 'node:dns';
import https from 'node:https';
import net from 'node:net';

const dohCache = new Map(); // hostname -> { ip, family, expiresAt }

function queryDohJson(dohIp, dohHost, pathQuery) {
  return new Promise((resolve, reject) => {
    const req = https.request(
      {
        host: dohIp,
        port: 443,
        path: pathQuery,
        method: 'GET',
        servername: dohHost,
        headers: {
          Host: dohHost,
          Accept: 'application/dns-json',
        },
        timeout: 4000,
      },
      (res) => {
        let body = '';
        res.on('data', (c) => (body += c));
        res.on('end', () => {
          try {
            const parsed = JSON.parse(body);
            const ans = (parsed.Answer || []).find((a) => a.type === 1 && net.isIPv4(a.data));
            if (ans) resolve({ ip: ans.data, ttl: Math.max(60, Math.min(600, ans.TTL || 300)) });
            else reject(new Error('No A record in DoH response'));
          } catch (e) {
            reject(e);
          }
        });
      }
    );
    req.on('error', reject);
    req.on('timeout', () => {
      req.destroy();
      reject(new Error('DoH timeout'));
    });
    req.end();
  });
}

async function resolveViaDoh(hostname) {
  const cached = dohCache.get(hostname);
  if (cached && cached.expiresAt > Date.now()) {
    return cached.ip;
  }

  const providers = [
    ['1.1.1.1', 'cloudflare-dns.com', `/dns-query?name=${encodeURIComponent(hostname)}&type=A`],
    ['223.5.5.5', 'dns.alidns.com', `/resolve?name=${encodeURIComponent(hostname)}&type=1`],
  ];

  let lastErr = null;
  for (const [ip, host, q] of providers) {
    try {
      const res = await queryDohJson(ip, host, q);
      dohCache.set(hostname, { ip: res.ip, family: 4, expiresAt: Date.now() + res.ttl * 1000 });
      return res.ip;
    } catch (e) {
      lastErr = e;
    }
  }
  throw lastErr || new Error(`DoH lookup failed for ${hostname}`);
}

export function resilientLookup(hostname, options, callback) {
  const cb = typeof options === 'function' ? options : callback;
  const opts = typeof options === 'object' && options !== null ? options : {};
  const wantAll = Boolean(opts.all);
  const done = (err, ip, fam = 4) => {
    if (err) return cb(err);
    return wantAll ? cb(null, [{ address: ip, family: fam }]) : cb(null, ip, fam);
  };

  if (net.isIP(hostname)) {
    return process.nextTick(() => done(null, hostname, net.isIP(hostname)));
  }

  const cached = dohCache.get(hostname);
  if (cached && cached.expiresAt > Date.now()) {
    return process.nextTick(() => done(null, cached.ip, 4));
  }

  let settled = false;
  const settle = (err, ip, fam) => {
    if (settled) return;
    settled = true;
    clearTimeout(sysTimer);
    done(err, ip, fam);
  };

  const fallbackToDoh = async (origErr) => {
    try {
      const dohIp = await resolveViaDoh(hostname);
      settle(null, dohIp, 4);
    } catch (dohErr) {
      settle(origErr || dohErr);
    }
  };

  // Don't let hung system/TUN DNS stall WebSocket handshake > 1.5s
  const sysTimer = setTimeout(() => {
    fallbackToDoh(new Error('system dns timeout'));
  }, 1500);
  sysTimer.unref?.();

  dns.lookup(hostname, { ...opts, all: false }, (err, address, family) => {
    if (settled) return;
    const isFakeIp = typeof address === 'string' && /^198\.1[89]\./.test(address);
    if (!err && address && !isFakeIp) {
      dohCache.set(hostname, { ip: address, family: family || 4, expiresAt: Date.now() + 120_000 });
      return settle(null, address, family || 4);
    }
    fallbackToDoh(err);
  });
}
