---
layout: default
title: "DEPLOY"
---

# Развёртывание signaling/relay-сервера

Сервер GS Multiplayer — два процесса без состояния:

| Процесс | Порт | Протокол | Назначение |
|---|---|---|---|
| `signaling/index.js` | 35500 TCP | WebSocket + HTTP (`/health`, `/stats`) | комнаты, поиск, signaling, presence, приглашения, relay-токены |
| `relay/index.js` | 35501 UDP | свой бинарный | ретрансляция игровых пакетов при провале P2P |

Требования: любой VPS (512 МБ RAM достаточно), Node.js ≥ 16, открытые порты 35500/TCP и 35501/UDP.

## Render (бесплатно, без VPS)

Signaling можно развернуть бесплатно на [Render](https://render.com) — в репозитории есть blueprint:

1. Нажмите кнопку **Deploy to Render** на [сайте проекта](https://ruillo747.github.io/GS-Multiplayer/deploy.html)
   или создайте "New → Blueprint" и укажите этот репозиторий (файл `render.yaml`).
2. Render соберёт и запустит signaling; UDP-relay на бесплатном тарифе недоступен —
   мод при этом работает по прямому P2P, резервный relay можно добавить позже на VPS.
3. Адрес вида `wss://gs-signaling-xxxx.onrender.com` впишите в настройках мода.
   Free-инстанс засыпает без трафика; первый запрос после сна отвечает ~30 секунд.

Проверить сервер: [страница статуса](https://ruillo747.github.io/GS-Multiplayer/status.html).

## 1. Установка

```bash
# Node.js 20 (Debian/Ubuntu)
curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
sudo apt-get install -y nodejs

cd /opt
sudo git clone https://github.com/ruillo747/GS-Multiplayer.git gs
cd gs/server
sudo npm install --omit=dev
```

## 2. Конфигурация

```bash
sudo cp .env.example .env
sudo nano .env
```

```ini
SIGNALING_PORT=35500
RELAY_PORT=35501
RELAY_PUBLIC_HOST=203.0.113.10        # публичный IP/домен ВАШЕГО сервера
RELAY_SECRET=<длинная случайная строка>
MAX_PLAYERS=4
```

Сгенерировать `RELAY_SECRET`:

```bash
node -e "console.log(require('crypto').randomBytes(32).toString('hex'))"
```

**Важно**: `RELAY_PUBLIC_HOST` — адрес, по которому игроки достучатся до relay
из интернета. Если signaling и relay запущены на разных машинах, задайте один
`RELAY_SECRET` в окружении обоих процессов.

## 3. systemd

`/etc/systemd/system/gs-signaling.service`:

```ini
[Unit]
Description=GS Multiplayer signaling
After=network.target

[Service]
Type=simple
User=gs
WorkingDirectory=/opt/gs/server
EnvironmentFile=/opt/gs/server/.env
ExecStart=/usr/bin/node signaling/index.js
Restart=always
RestartSec=3
# жесткие лимиты памяти для signaling не критичны, но не помешают
MemoryMax=256M

[Install]
WantedBy=multi-user.target
```

`/etc/systemd/system/gs-relay.service`:

```ini
[Unit]
Description=GS Multiplayer relay
After=network.target

[Service]
Type=simple
User=gs
WorkingDirectory=/opt/gs/server
EnvironmentFile=/opt/gs/server/.env
ExecStart=/usr/bin/node relay/index.js
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
```

Запуск:

```bash
sudo useradd -r -s /usr/sbin/nologin gs || true
sudo chown -R gs:gs /opt/gs
sudo systemctl daemon-reload
sudo systemctl enable --now gs-signaling gs-relay
sudo systemctl status gs-signaling gs-relay
```

Проверка: `curl http://127.0.0.1:35500/health` → `{"ok":true,...}`

## 4. Файрвол

```bash
sudo ufw allow 35500/tcp   # signaling (клиенты)
sudo ufw allow 35501/udp   # relay (клиенты)
```

## 5. TLS / wss (рекомендуется)

Мод поддерживает и `ws://`, и `wss://`. Для wss поставьте reverse-proxy
(Caddy — самый короткий путь, сертификаты сам):

`/etc/caddy/Caddyfile`:

```
gs.example.com {
    reverse_proxy /ws* 127.0.0.1:35500
    reverse_proxy /health 127.0.0.1:35500
}
```

```bash
sudo apt install caddy
sudo systemctl reload caddy
```

Игроки указывают в настройках мода `wss://gs.example.com/ws`.

Альтернатива — nginx с `proxy_pass` и заголовками `Upgrade`/`Connection`.

> Relay через TLS не проходит: это чистый UDP, оставьте 35501/UDP открытым.

## 6. Мониторинг

- `GET /health` — живость, число сессий/комнат (для uptime-мониторов).
- `GET /stats` — комнаты, игроки, presence-подписки, счётчики relay (`forwarded`/`drops` через логи).
- Логи: `journalctl -u gs-signaling -f`, `journalctl -u gs-relay -f`.

## 7. Несколько регионов (масштабирование)

Схема рассчитана на расширение:

1. Поднимите пары signaling+relay в каждом регионе (свои `RELAY_SECRET` на регион).
2. signaling каждого региона выдаёт адрес своего relay.
3. Клиент выбирает регион по настройке адреса signaling; в будущем можно добавить
   общий каталог регионов — протокол сообщения `relay.token` уже содержит host/port.

## 8. Безопасность

- signaling не хранит пользователей: всё в памяти, TTL presence 60 секунд.
- relay принимает только пакеты с валидным HMAC-токеном (2 часа жизни), выданным signaling.
- Держите `RELAY_SECRET` в секрете: утечка позволяет посторонним занимать relay-сессии.
- Rate limit signaling: 40 сообщений / 5 секунд на соединение.
- Обновляйте Node.js в рамках security-обновлений дистрибутива.

## 9. Обновление сервера

```bash
cd /opt/gs
sudo git pull
cd server && sudo npm install --omit=dev
sudo systemctl restart gs-signaling gs-relay
```

Короткий разрыв соединения для игроков: активные P2P-туннели не страдают
(трафик идёт напрямую), Relay-сессии переподключатся автоматически.
