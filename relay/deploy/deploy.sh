#!/usr/bin/env bash
# ClawBridge Relay — Self-Hosted VPS One-Click Deployment Script (Ubuntu / Debian)
#
# Usage:
#   # Recommended: Nginx + Let's Encrypt Certbot auto-TLS
#   sudo bash deploy.sh --domain relay.yourdomain.com --email you@yourdomain.com
#
#   # Custom internal port:
#   sudo bash deploy.sh --domain relay.yourdomain.com --email you@yourdomain.com --port 8787
#
#   # Alternative: Caddy automatic HTTPS:
#   sudo bash deploy.sh --domain relay.yourdomain.com --proxy caddy
#
#   # Relay only (bring your own reverse proxy):
#   sudo bash deploy.sh --proxy none --port 8787

set -euo pipefail

DOMAIN=""
EMAIL=""
PORT="8787"
HOST="127.0.0.1"
PROXY="nginx"
INSTALL_DIR="/opt/clawbridge-relay"
STATE_DIR="/var/lib/clawbridge-relay"
ENV_DIR="/etc/clawbridge-relay"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RELAY_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --domain) DOMAIN="$2"; shift 2 ;;
    --email)  EMAIL="$2"; shift 2 ;;
    --port)   PORT="$2"; shift 2 ;;
    --host)   HOST="$2"; shift 2 ;;
    --proxy)  PROXY="$2"; shift 2 ;;
    -h|--help)
      sed -n '2,18p' "$0"
      exit 0
      ;;
    *) echo "Unknown argument: $1" >&2; exit 1 ;;
  esac
done

if [[ $EUID -ne 0 ]]; then
  echo "[ERROR] Please run this script with sudo / root privileges." >&2
  exit 1
fi

if [[ "${PROXY}" != "none" ]]; then
  if [[ -z "${DOMAIN}" || "${DOMAIN}" == "relay.example.com" ]]; then
    echo "[ERROR] Please pass your real domain via --domain (e.g. --domain relay.yourdomain.com)." >&2
    exit 1
  fi
fi

echo "[1/5] Checking Node.js (>= 20)..."
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
  apt-get install -y ca-certificates curl gnupg
  curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
  apt-get install -y nodejs
fi
echo "      Node.js $(node -v) ready."

echo "[2/5] Installing Relay files to ${INSTALL_DIR}..."
mkdir -p "${INSTALL_DIR}/src" "${INSTALL_DIR}/deploy" "${STATE_DIR}" "${ENV_DIR}"
chmod 700 "${STATE_DIR}"
cp -f "${RELAY_ROOT}/package.json" "${INSTALL_DIR}/package.json"
if [[ -f "${RELAY_ROOT}/package-lock.json" ]]; then
  cp -f "${RELAY_ROOT}/package-lock.json" "${INSTALL_DIR}/package-lock.json"
fi
cp -f "${RELAY_ROOT}/src/"*.mjs "${INSTALL_DIR}/src/"
cp -f "${RELAY_ROOT}/deploy/"* "${INSTALL_DIR}/deploy/"

cat > "${ENV_DIR}/relay.env" <<EOF
RELAY_HOST=${HOST}
RELAY_PORT=${PORT}
RELAY_STATE_FILE=${STATE_DIR}/state.json
EOF
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

echo "[4/5] Configuring reverse proxy & TLS (mode=${PROXY})..."
if [[ "${PROXY}" == "nginx" ]]; then
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
    echo "      Run the following command to enable Let's Encrypt HTTPS/WSS:"
    echo "        sudo certbot --nginx -d ${DOMAIN} --email you@yourdomain.com --agree-tos --redirect"
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
else
  echo "[ERROR] Unsupported --proxy mode: ${PROXY} (use nginx | caddy | none)" >&2
  exit 1
fi

echo "[5/5] Verifying local Relay health endpoint..."
curl -fsS "http://${HOST}:${PORT}/healthz" && echo ""
echo "Deployment complete!"
