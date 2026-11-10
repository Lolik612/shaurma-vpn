#!/usr/bin/env python3
"""
Шаурма VPN — сервер для хостингов-контейнеров (Pterodactyl и т.п.), без root.

Что делает:
  1. скачивает официальный Shadowsocks-сервер (ssserver) с GitHub;
  2. один раз генерирует пароль и сохраняет его в ss-config.json;
  3. запускает сервер на порту SERVER_PORT (по умолчанию 13666);
  4. печатает в консоль КЛЮЧ ss://... — его вставляют в приложении.

Запуск: просто `python main.py` (в панели это уже стартовая команда).
Если внешний IP определился неверно — задай переменную PUBLIC_IP.
"""
import base64
import io
import json
import os
import platform
import secrets
import signal
import subprocess
import sys
import tarfile
import time
import urllib.request

BASE = os.path.dirname(os.path.abspath(__file__))
BIN_DIR = os.path.join(BASE, "bin")
BIN = os.path.join(BIN_DIR, "ssserver")
CFG = os.path.join(BASE, "ss-config.json")
KEY_FILE = os.path.join(BASE, "key.txt")

PORT = int(os.environ.get("SERVER_PORT") or 13666)
PUBLIC_IP = os.environ.get("PUBLIC_IP") or "78.154.103.10"
METHOD = "chacha20-ietf-poly1305"
PINNED = "v1.25.0"


def log(msg):
    print(f"[shaurma] {msg}", flush=True)


def http_get(url, timeout=60):
    req = urllib.request.Request(url, headers={"User-Agent": "shaurma-vpn"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def candidate_urls(arch):
    """Список ссылок на архив ssserver: сначала свежий релиз, потом запасной."""
    urls = []
    try:
        rel = json.loads(http_get("https://api.github.com/repos/shadowsocks/shadowsocks-rust/releases/latest", 30))
        for suffix in (f"{arch}-unknown-linux-musl.tar.xz", f"{arch}-unknown-linux-gnu.tar.xz"):
            for a in rel.get("assets", []):
                if a["name"].endswith(suffix):
                    urls.append(a["browser_download_url"])
    except Exception as e:  # noqa: BLE001
        log(f"GitHub API недоступен ({e}), пробую запасную версию")
    base = f"https://github.com/shadowsocks/shadowsocks-rust/releases/download/{PINNED}/shadowsocks-{PINNED}"
    urls.append(f"{base}.{arch}-unknown-linux-musl.tar.xz")
    urls.append(f"{base}.{arch}-unknown-linux-gnu.tar.xz")
    return urls


def ensure_binary():
    if os.path.isfile(BIN):
        return
    arch = {"x86_64": "x86_64", "amd64": "x86_64", "aarch64": "aarch64", "arm64": "aarch64"}.get(
        platform.machine().lower()
    )
    if not arch:
        sys.exit(f"Неподдерживаемая архитектура: {platform.machine()}")
    os.makedirs(BIN_DIR, exist_ok=True)
    last_error = None
    for url in candidate_urls(arch):
        try:
            log(f"Скачиваю {url}")
            data = http_get(url, 180)
            with tarfile.open(fileobj=io.BytesIO(data), mode="r:xz") as tar:
                for m in tar.getmembers():
                    if m.isfile() and os.path.basename(m.name) == "ssserver":
                        with open(BIN, "wb") as f:
                            f.write(tar.extractfile(m).read())
                        os.chmod(BIN, 0o755)
                        log("ssserver установлен")
                        return
            last_error = "в архиве нет ssserver"
        except Exception as e:  # noqa: BLE001
            last_error = e
            log(f"не вышло: {e}")
    sys.exit(f"Не удалось скачать ssserver: {last_error}")


def load_config():
    if os.path.isfile(CFG):
        with open(CFG, encoding="utf-8") as f:
            cfg = json.load(f)
    else:
        cfg = {
            "server": "0.0.0.0",
            "server_port": PORT,
            "password": secrets.token_urlsafe(18),
            "method": METHOD,
            "mode": "tcp_and_udp",
            "timeout": 300,
        }
    cfg["server_port"] = PORT
    with open(CFG, "w", encoding="utf-8") as f:
        json.dump(cfg, f, indent=2)
    return cfg


def make_key(cfg):
    userinfo = base64.urlsafe_b64encode(f"{cfg['method']}:{cfg['password']}".encode()).decode().rstrip("=")
    return f"ss://{userinfo}@{PUBLIC_IP}:{cfg['server_port']}#Shaurma"


def print_key(key):
    with open(KEY_FILE, "w", encoding="utf-8") as f:
        f.write(key + "\n")
    line = "=" * 60
    print(f"\n{line}\n  КЛЮЧ ДЛЯ ПРИЛОЖЕНИЯ (скопируй строку целиком):\n\n{key}\n\n{line}\n", flush=True)


def main():
    ensure_binary()
    cfg = load_config()
    key = make_key(cfg)
    child = None

    def stop(*_):
        if child and child.poll() is None:
            child.terminate()
        sys.exit(0)

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)

    while True:
        print_key(key)
        log(f"Запускаю Shadowsocks на порту {cfg['server_port']} (TCP+UDP)")
        child = subprocess.Popen([BIN, "-c", CFG])
        code = child.wait()
        log(f"ssserver остановился (код {code}), перезапуск через 3 секунды")
        time.sleep(3)


if __name__ == "__main__":
    main()
