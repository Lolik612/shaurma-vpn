#!/usr/bin/env bash
# Установка WireGuard-сервера для приложения «Шаурма VPN».
# Запуск на сервере (Ubuntu/Debian) от root:   bash setup-server.sh
set -euo pipefail

[ "$(id -u)" -eq 0 ] || { echo "Запусти от root (sudo bash setup-server.sh)"; exit 1; }

PORT="${WG_PORT:-51820}"
NET="10.66.66"
DNS="1.1.1.1"

apt-get update -y
apt-get install -y wireguard qrencode iptables curl

IFACE="$(ip -4 route show default | awk '{print $5; exit}')"
PUBIP="${WG_ENDPOINT:-$(curl -4 -s https://ifconfig.me || curl -4 -s https://api.ipify.org)}"
[ -n "$PUBIP" ] || { echo "Не удалось определить IP. Запусти: WG_ENDPOINT=1.2.3.4 bash $0"; exit 1; }

mkdir -p /etc/wireguard && chmod 700 /etc/wireguard
cd /etc/wireguard

if [ ! -f server.key ]; then
  umask 077
  wg genkey | tee server.key | wg pubkey > server.pub
  wg genkey | tee client.key | wg pubkey > client.pub
  wg genpsk > client.psk
fi

cat > /etc/wireguard/wg0.conf <<CONF
[Interface]
Address = ${NET}.1/24
ListenPort = ${PORT}
PrivateKey = $(cat server.key)
PostUp = iptables -A FORWARD -i %i -j ACCEPT; iptables -A FORWARD -o %i -j ACCEPT; iptables -t nat -A POSTROUTING -o ${IFACE} -j MASQUERADE
PostDown = iptables -D FORWARD -i %i -j ACCEPT; iptables -D FORWARD -o %i -j ACCEPT; iptables -t nat -D POSTROUTING -o ${IFACE} -j MASQUERADE

[Peer]
PublicKey = $(cat client.pub)
PresharedKey = $(cat client.psk)
AllowedIPs = ${NET}.2/32
CONF
chmod 600 wg0.conf

echo 'net.ipv4.ip_forward=1' > /etc/sysctl.d/99-wireguard.conf
sysctl --system >/dev/null

cat > /root/client.conf <<CONF
[Interface]
PrivateKey = $(cat client.key)
Address = ${NET}.2/32
DNS = ${DNS}
MTU = 1280

[Peer]
PublicKey = $(cat server.pub)
PresharedKey = $(cat client.psk)
Endpoint = ${PUBIP}:${PORT}
AllowedIPs = 0.0.0.0/0, ::/0
PersistentKeepalive = 25
CONF
chmod 600 /root/client.conf

if command -v ufw >/dev/null && ufw status | grep -q active; then ufw allow "${PORT}/udp"; fi

systemctl enable wg-quick@wg0
systemctl restart wg-quick@wg0

echo
echo "================ ГОТОВО ================"
echo "Порт ${PORT}/udp должен быть открыт в панели хостинга (firewall)."
echo "Конфиг для приложения сохранён в /root/client.conf:"
echo "----------------------------------------"
cat /root/client.conf
echo "----------------------------------------"
echo "Скопируй его и вставь в приложении (нажми на «Выбранная локация»)."
