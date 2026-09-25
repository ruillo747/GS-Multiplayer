# GS Multiplayer Server

Signaling + relay для мода GS Multiplayer (Minecraft Java 1.16.5, Fabric).

Состоит из двух независимых процессов:

| Процесс | Порт по умолчанию | Назначение |
|---|---|---|
| `signaling/` | 35500 TCP | WebSocket-signaling, комнаты, presence, приглашения, выдача relay-токенов, HTTP `/health` |
| `relay/` | 35501 UDP | Ретрансляция игровых пакетов, когда P2P-пробивка NAT не удалась |

Зависимости: только Node.js >= 16 и npm-пакет `ws`.

## Быстрый старт

```bash
npm install
cp .env.example .env       # укажите RELAY_PUBLIC_HOST и RELAY_SECRET
node signaling/index.js    # терминал 1
node relay/index.js        # терминал 2
```

Проверка: `curl http://localhost:35500/health`

Тесты: `npm test` (поднимает оба сервиса на случайных портах и прогоняет полный сценарий).

Подробное руководство по развёртыванию на VPS (systemd, TLS/wss, firewall): [../docs/DEPLOY.md](../docs/DEPLOY.md).
Описание протокола и API: [../docs/SERVER_API.md](../docs/SERVER_API.md).
