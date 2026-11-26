#!/usr/bin/env bash
# ClawBridge Relay — Self-Hosted VPS One-Click Deployment Script (Ubuntu / Debian)
#
# Supports BOTH Domain-Free Public IPv4 Direct WSS (default!) AND Optional Domain TLS:
#
#   1) Zero-Domain Public IPv4 Direct Mode (Recommended — only a VPS needed, NO domain required!):
#      sudo bash deploy.sh
#      # Or specify IP / port explicitly (default port in IP mode is 8443):
#      sudo bash deploy.sh --ip 203.0.113.10 --port 8443
#
#   2) Optional Domain Mode (Nginx + Let's Encrypt auto-TLS):
#      sudo bash deploy.sh --domain relay.yourdomain.com --email you@yourdomain.com
#
#   3) Optional Caddy Domain Mode:
#      sudo bash deploy.sh --domain relay.yourdomain.com --proxy caddy
#
#   4) Local HTTP Backend Only (bring your own reverse proxy):
#      sudo bash deploy.sh --proxy none --port 8787

set -euo pipefail

PUBLIC_IP=""
DOMAIN=""
EMAIL=""
PORT=""
HOST=""
PROXY="auto"
INSTALL_DIR="/opt/clawbridge-relay"
STATE_DIR="/var/lib/clawbridge-relay"
ENV_DIR="/etc/clawbridge-relay"
TLS_DIR="${ENV_DIR}/tls"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RELAY_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --ip)     PUBLIC_IP="$2"; shift 2 ;;
    --domain) DOMAIN="$2"; shift 2 ;;
    --email)  EMAIL="$2"; shift 2 ;;
    --port)   PORT="$2"; shift 2 ;;
    --host)   HOST="$2"; shift 2 ;;
    --proxy)  PROXY="$2"; shift 2 ;;
    -h|--help)
      sed -n '2,20p' "$0"
      exit 0
      ;;
    *) echo "[ERROR] Unknown argument: $1" >&2; exit 1 ;;
  esac
done

if [[ $EUID -ne 0 ]]; then
  echo "[ERROR] Please run this script with sudo / root privileges (e.g. sudo bash deploy.sh)." >&2
  exit 1
fi

# Determine deployment mode automatically:
# - If --domain is NOT provided (and --proxy is auto or ip), use Domain-Free Native WSS on Public IPv4!
if [[ "${PROXY}" == "auto" ]]; then
  if [[ -n "${DOMAIN}" && "${DOMAIN}" != "relay.example.com" ]]; then
    PROXY="nginx"
  else
    PROXY="ip-tls"
  fi
fi

if [[ "${PROXY}" == "nginx" || "${PROXY}" == "caddy" ]]; then
  if [[ -z "${DOMAIN}" || "${DOMAIN}" == "relay.example.com" ]]; then
    echo "[ERROR] --proxy ${PROXY} requires a real domain via --domain (or omit --proxy to deploy directly with your VPS IPv4!)." >&2
    exit 1
  fi
  HOST="${HOST:-127.0.0.1}"
  PORT="${PORT:-8787}"
elif [[ "${PROXY}" == "ip-tls" || "${PROXY}" == "ip" ]]; then
  PROXY="ip-tls"
  HOST="${HOST:-0.0.0.0}"
  PORT="${PORT:-8443}"
elif [[ "${PROXY}" == "none" ]]; then
  HOST="${HOST:-127.0.0.1}"
  PORT="${PORT:-8787}"
else
  echo "[ERROR] Unsupported --proxy mode: ${PROXY} (use ip-tls | nginx | caddy | none)" >&2
  exit 1
fi

echo "[1/5] Checking Node.js (>= 20) & OpenSSL..."
NEED_NODE=0
if ! command -v node >/dev/null 2>&1; then
  NEED_NODE=1
else
  NODE_MAJOR="$(node -v | sed -E 's/^v([0-9]+).*/\1/')"
  if [[ "${NODE_MAJOR}" -lt 20 ]]; then
    NEED_NODE=1
  fi
fi

if [[ "${NEED_NODE}" -eq 1 ]]; then
  apt-get update -y
  apt-get install -y ca-certificates curl gnupg openssl
  curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
  apt-get install -y nodejs
elif ! command -v openssl >/dev/null 2>&1 || ! command -v curl >/dev/null 2>&1; then
  apt-get update -y
  apt-get install -y ca-certificates curl openssl
fi
echo "      Node.js $(node -v) ready."

# In IP-TLS mode, auto-detect public IPv4 if not passed via --ip
CERT_FINGERPRINT=""
if [[ "${PROXY}" == "ip-tls" ]]; then
  if [[ -z "${PUBLIC_IP}" ]]; then
    echo "      Auto-detecting VPS public IPv4 address..."
    PUBLIC_IP="$(curl -4 -fsSL --max-time 5 https://api.ipify.org 2>/dev/null || true)"
    if [[ -z "${PUBLIC_IP}" ]]; then
      PUBLIC_IP="$(curl -4 -fsSL --max-time 5 https://ifconfig.me 2>/dev/null || true)"
    fi
    if [[ -z "${PUBLIC_IP}" ]]; then
      PUBLIC_IP="$(curl -4 -fsSL --max-time 5 https://icanhazip.com 2>/dev/null | tr -d '[:space:]' || true)"
    fi
    if [[ -z "${PUBLIC_IP}" ]]; then
      PUBLIC_IP="$(hostname -I 2>/dev/null | awk '{print $1}' || true)"
    fi
  fi

  if [[ -z "${PUBLIC_IP}" ]]; then
    echo "[ERROR] Could not auto-detect VPS IPv4. Please pass it explicitly: sudo bash deploy.sh --ip YOUR_VPS_IPV4" >&2
    exit 1
  fi
  echo "      Target VPS IPv4: ${PUBLIC_IP} (WSS port: ${PORT})"
fi

echo "[2/5] Installing Relay files to ${INSTALL_DIR}..."
mkdir -p "${INSTALL_DIR}/src" "${INSTALL_DIR}/deploy" "${STATE_DIR}" "${ENV_DIR}"
chmod 700 "${STATE_DIR}"
cp -f "${RELAY_ROOT}/package.json" "${INSTALL_DIR}/package.json"
if [[ -f "${RELAY_ROOT}/package-lock.json" ]]; then
  cp -f "${RELAY_ROOT}/package-lock.json" "${INSTALL_DIR}/package-lock.json"
fi
cp -f "${RELAY_ROOT}/src/"*.mjs "${INSTALL_DIR}/src/"
cp -f "${RELAY_ROOT}/deploy/"* "${INSTALL_DIR}/deploy/"

if [[ "${PROXY}" == "ip-tls" ]]; then
  echo "      Generating 10-year self-signed TLS certificate for IP ${PUBLIC_IP}..."
  mkdir -p "${TLS_DIR}"
  chmod 700 "${TLS_DIR}"
  OPENSSL_CNF="${TLS_DIR}/openssl-ip.cnf"
  cat > "${OPENSSL_CNF}" <<EOF
[req]
default_bits = 2048
prompt = no
default_md = sha256
x509_extensions = v3_req
distinguished_name = dn

[dn]
CN = ${PUBLIC_IP}
O = ClawBridge Self-Hosted Relay

[v3_req]
basicConstraints = CA:FALSE
keyUsage = digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName = @alt_names

[alt_names]
IP.1 = ${PUBLIC_IP}
IP.2 = 127.0.0.1
EOF
  openssl req -x509 -nodes -newkey rsa:2048 -days 3650 \
    -keyout "${TLS_DIR}/key.pem" \
    -out "${TLS_DIR}/cert.pem" \
    -config "${OPENSSL_CNF}" >/dev/null 2>&1
  chmod 600 "${TLS_DIR}/key.pem" "${TLS_DIR}/cert.pem"
  CERT_FINGERPRINT="$(openssl x509 -in "${TLS_DIR}/cert.pem" -noout -fingerprint -sha256 | sed 's/^.*=//')"

  cat > "${ENV_DIR}/relay.env" <<EOF
RELAY_HOST=${HOST}
RELAY_PORT=${PORT}
RELAY_STATE_FILE=${STATE_DIR}/state.json
RELAY_TLS_CERT=${TLS_DIR}/cert.pem
RELAY_TLS_KEY=${TLS_DIR}/key.pem
EOF
else
  cat > "${ENV_DIR}/relay.env" <<EOF
RELAY_HOST=${HOST}
RELAY_PORT=${PORT}
RELAY_STATE_FILE=${STATE_DIR}/state.json
EOF
fi
chmod 600 "${ENV_DIR}/relay.env"

cd "${INSTALL_DIR}"
npm install --omit=dev --no-audit --no-fund

echo "[3/5] Registering systemd service clawbridge-relay.service..."
cp -f "${INSTALL_DIR}/deploy/clawbridge-relay.service" /etc/systemd/system/clawbridge-relay.service
systemctl daemon-reload
systemctl enable clawbridge-relay.service
systemctl restart clawbridge-relay.service
sleep 1
systemctl is-active --quiet clawbridge-relay.service
echo "      clawbridge-relay.service is active on ${HOST}:${PORT}"

echo "[4/5] Configuring network / reverse proxy (mode=${PROXY})..."
if [[ "${PROXY}" == "ip-tls" ]]; then
  if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q "Status: active"; then
    ufw allow "${PORT}/tcp" >/dev/null 2>&1 || true
    echo "      Opened TCP ${PORT} in UFW firewall."
  fi
elif [[ "${PROXY}" == "nginx" ]]; then
  apt-get update -y
  apt-get install -y nginx certbot python3-certbot-nginx

  NGINX_CONF="/etc/nginx/sites-available/clawbridge-relay.conf"
  cat > "${NGINX_CONF}" <<EOF
map \$http_upgrade \$connection_upgrade {
    default upgrade;
    ''      close;
}

server {
    listen 80;
    listen [::]:80;
    server_name ${DOMAIN};

    client_max_body_size 20m;

    location = /healthz {
        proxy_pass http://${HOST}:${PORT}/healthz;
        proxy_set_header Host \$host;
    }

    location / {
        proxy_pass http://${HOST}:${PORT};
        proxy_http_version 1.1;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection \$connection_upgrade;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
        proxy_buffering off;
    }
}
EOF
  ln -sf "${NGINX_CONF}" /etc/nginx/sites-enabled/clawbridge-relay.conf
  nginx -t
  systemctl enable nginx
  systemctl reload nginx

  if [[ -n "${EMAIL}" ]]; then
    echo "      Requesting Let's Encrypt TLS certificate via Certbot for ${DOMAIN}..."
    certbot --nginx -d "${DOMAIN}" --email "${EMAIL}" --agree-tos --non-interactive --redirect
    systemctl enable certbot.timer >/dev/null 2>&1 || true
    echo "      HTTPS/WSS ready: wss://${DOMAIN}"
  else
    echo "      Nginx HTTP reverse proxy configured for ${DOMAIN}."
    echo "      Run: sudo certbot --nginx -d ${DOMAIN} --email you@yourdomain.com --agree-tos --redirect"
  fi
elif [[ "${PROXY}" == "caddy" ]]; then
  if ! command -v caddy >/dev/null 2>&1; then
    apt-get update -y
    apt-get install -y debian-keyring debian-archive-keyring apt-transport-https curl
    curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg --yes
    curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | tee /etc/apt/sources.list.d/caddy-stable.list >/dev/null
    apt-get update -y
    apt-get install -y caddy
  fi
  mkdir -p /etc/caddy
  cat > /etc/caddy/Caddyfile <<EOF
${DOMAIN} {
    reverse_proxy ${HOST}:${PORT}
}
EOF
  systemctl enable caddy
  systemctl restart caddy
  echo "      Caddy configured for wss://${DOMAIN} -> ${HOST}:${PORT}"
elif [[ "${PROXY}" == "none" ]]; then
  echo "      Skipping reverse proxy setup (--proxy none). Forward your TLS proxy to http://${HOST}:${PORT}."
fi

echo "[5/5] Verifying local Relay health endpoint..."
if [[ "${PROXY}" == "ip-tls" ]]; then
  curl -fkS "https://127.0.0.1:${PORT}/healthz" && echo ""
  echo ""
  echo "=================================================================="
  echo "  ClawBridge VPS 中继部署成功（公网 IPv4 免域名直连模式）"
  echo "=================================================================="
  echo "  1. 请确保云厂商控制台安全组/防火墙已放行入站端口：TCP ${PORT}"
  echo "  2. 在 Windows 电脑控制台与手机 App「外网中继」中填入以下地址："
  echo ""
  echo "       wss://${PUBLIC_IP}:${PORT}"
  echo ""
  echo "     （直接填 ${PUBLIC_IP}:${PORT} 亦可，两端会自动启用自签 TLS 加密）"
  echo "  3. TLS 证书 SHA-256 指纹：${CERT_FINGERPRINT}"
  echo "=================================================================="
else
  curl -fsS "http://127.0.0.1:${PORT}/healthz" && echo ""
  echo "Deployment complete!"
fi
