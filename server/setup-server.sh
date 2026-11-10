#!/usr/bin/env bash
# ============================================================
#  Шаурма VPN — автоустановка сервера (Ubuntu / Debian)
#  Запусти один раз от root:   bash setup-server.sh
#  В конце получишь КЛЮЧ shaurma://… — вставь его в приложении.
#  Новые ключи для других людей:   shaurma-add-client имя
# ============================================================
set -euo pipefail

[ "$(id -u)" -eq 0 ] || { echo "Запусти от root: sudo bash setup-server.sh"; exit 1; }

PORT="${WG_PORT:-51820}"
WG_DIR=/etc/wireguard
ENV_FILE="$WG_DIR/shaurma.env"

if [ -f "$ENV_FILE" ] && [ -f "$WG_DIR/wg0.conf" ]; then
  echo "Сервер уже установлен — создаю новый ключ."
  exec /usr/local/bin/shaurma-add-client "${1:-client-$(date +%s)}"
fi

export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y wireguard iptables curl ca-certificates

IFACE="$(ip -4 route show default | awk '{print $5; exit}')"
[ -n "$IFACE" ] || { echo "Не нашёл сетевой интерфейс"; exit 1; }
ENDPOINT="${WG_ENDPOINT:-$(curl -4 -fsS https://ifconfig.me 2>/dev/null || curl -4 -fsS https://api.ipify.org 2>/dev/null || true)}"
[ -n "$ENDPOINT" ] || { echo "Не удалось определить IP. Запусти так: WG_ENDPOINT=1.2.3.4 bash $0"; exit 1; }

# IPv6 включаем только если он реально работает на сервере
HAS_V6=0
if [ -f /proc/net/if_inet6 ] && [ "$(sysctl -n net.ipv6.conf.all.disable_ipv6 2>/dev/null || echo 1)" = "0" ]; then
  HAS_V6=1
fi

mkdir -p "$WG_DIR" && chmod 700 "$WG_DIR"
umask 077
wg genkey | tee "$WG_DIR/server.key" | wg pubkey > "$WG_DIR/server.pub"

if [ "$HAS_V6" = "1" ]; then
  ADDR="10.66.66.1/24, fd66:66:66::1/64"
  UP6="; ip6tables -A FORWARD -i %i -j ACCEPT || true; ip6tables -A FORWARD -o %i -j ACCEPT || true; ip6tables -t nat -A POSTROUTING -o ${IFACE} -j MASQUERADE || true"
  DOWN6="; ip6tables -D FORWARD -i %i -j ACCEPT || true; ip6tables -D FORWARD -o %i -j ACCEPT || true; ip6tables -t nat -D POSTROUTING -o ${IFACE} -j MASQUERADE || true"
else
  ADDR="10.66.66.1/24"
  UP6=""
  DOWN6=""
fi

cat > "$WG_DIR/wg0.conf" <<CONF
[Interface]
Address = ${ADDR}
ListenPort = ${PORT}
PrivateKey = $(cat "$WG_DIR/server.key")
PostUp = iptables -A FORWARD -i %i -j ACCEPT; iptables -A FORWARD -o %i -j ACCEPT; iptables -t nat -A POSTROUTING -o ${IFACE} -j MASQUERADE${UP6}
PostDown = iptables -D FORWARD -i %i -j ACCEPT; iptables -D FORWARD -o %i -j ACCEPT; iptables -t nat -D POSTROUTING -o ${IFACE} -j MASQUERADE${DOWN6}
CONF
chmod 600 "$WG_DIR/wg0.conf"

cat > "$ENV_FILE" <<ENVF
PORT=${PORT}
ENDPOINT=${ENDPOINT}
HAS_V6=${HAS_V6}
SERVER_PUB=$(cat "$WG_DIR/server.pub")
ENVF

echo 'net.ipv4.ip_forward=1' > /etc/sysctl.d/99-shaurma.conf
if [ "$HAS_V6" = "1" ]; then
  echo 'net.ipv6.conf.all.forwarding=1' >> /etc/sysctl.d/99-shaurma.conf
fi
sysctl --system >/dev/null

if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q "Status: active"; then
  ufw allow "${PORT}/udp" >/dev/null
fi

# ---------- помощник: создаёт новый ключ для клиента ----------
cat > /usr/local/bin/shaurma-add-client <<'HELPER'
#!/usr/bin/env bash
set -euo pipefail
[ "$(id -u)" -eq 0 ] || { echo "Запусти от root"; exit 1; }
NAME="${1:-client-$(date +%s)}"
WG_DIR=/etc/wireguard
# shellcheck disable=SC1091
. "$WG_DIR/shaurma.env"
cd "$WG_DIR"

IDX=$(( $(grep -c '^\[Peer\]' wg0.conf) + 2 ))
[ "$IDX" -lt 250 ] || { echo "Слишком много клиентов"; exit 1; }

umask 077
PRIV="$(wg genkey)"; PUB="$(echo "$PRIV" | wg pubkey)"; PSK="$(wg genpsk)"

if [ "$HAS_V6" = "1" ]; then
  PEER_IPS="10.66.66.${IDX}/32, fd66:66:66::${IDX}/128"
  CLIENT_ADDR="10.66.66.${IDX}/32, fd66:66:66::${IDX}/128"
  CLIENT_DNS="1.1.1.1, 2606:4700:4700::1111"
else
  PEER_IPS="10.66.66.${IDX}/32"
  CLIENT_ADDR="10.66.66.${IDX}/32"
  CLIENT_DNS="1.1.1.1, 8.8.8.8"
fi

cat >> wg0.conf <<PEER

# ${NAME}
[Peer]
PublicKey = ${PUB}
PresharedKey = ${PSK}
AllowedIPs = ${PEER_IPS}
PEER

# применяем сразу, без перезапуска (остальные клиенты не отваливаются)
PSKFILE="$(mktemp)"; echo "$PSK" > "$PSKFILE"
wg set wg0 peer "$PUB" preshared-key "$PSKFILE" allowed-ips "${PEER_IPS// /}"
rm -f "$PSKFILE"

mkdir -p /root/shaurma-clients
CONF_FILE="/root/shaurma-clients/${NAME}.conf"
cat > "$CONF_FILE" <<CLIENT
[Interface]
PrivateKey = ${PRIV}
Address = ${CLIENT_ADDR}
DNS = ${CLIENT_DNS}
MTU = 1280

[Peer]
PublicKey = ${SERVER_PUB}
PresharedKey = ${PSK}
Endpoint = ${ENDPOINT}:${PORT}
AllowedIPs = 0.0.0.0/0, ::/0
PersistentKeepalive = 25
CLIENT

KEY="shaurma://$(base64 -w0 < "$CONF_FILE" | tr '+/' '-_' | tr -d '=')"
echo "$KEY" > "/root/shaurma-clients/${NAME}.key.txt"

echo
echo "=========== КЛЮЧ для «${NAME}» ==========="
echo
echo "$KEY"
echo
echo "==========================================="
echo "Скопируй строку shaurma://… целиком и вставь в приложении."
echo "Ключ также сохранён: /root/shaurma-clients/${NAME}.key.txt"
HELPER
chmod +x /usr/local/bin/shaurma-add-client

systemctl enable wg-quick@wg0 >/dev/null 2>&1
systemctl restart wg-quick@wg0

echo
echo "WireGuard запущен. Порт ${PORT}/udp должен быть открыт в панели хостинга."
/usr/local/bin/shaurma-add-client "${1:-main}"
echo
echo "Ещё один ключ (для друга):  shaurma-add-client friend"
