---
layout: default
title: GS Multiplayer
---

# 🎮 GS Multiplayer

**Играйте в обычный одиночный мир Minecraft Java 1.16.5 с друзьями через интернет.**
P2P-соединение напрямую (быстро), relay как резерв. Без своего сервера Minecraft. До 4 игроков.

[⬇ Скачать мод (Releases)](https://github.com/ruillo747/GS-Multiplayer/releases/latest){: .btn .btn-primary}
[🚀 Развернуть signaling-сервер](deploy.md){: .btn}
[🛠 Проверить статус сервера](status.html){: .btn}
[⚙ Сгенерировать конфиг](config.html){: .btn}

## Быстрый старт

1. Установите [Fabric Loader](https://fabricmc.net/use/installer/) для Minecraft **1.16.5** (Java 8–17).
2. Скачайте `gsmultiplayer-<версия>.jar` со страницы [Releases](https://github.com/ruillo747/GS-Multiplayer/releases/latest) и положите в `mods`.
3. Запустите игру — кнопка **GS Multiplayer** появится в главном меню.
4. Хост: **Одиночная игра → Esc → GS Multiplayer → Открыть комнату**. Друг: **Подключиться по коду**.

## Сервер (signaling)

Для игры через интернет нужен небольшой signaling-сервер (Node.js, 512 МБ RAM хватит):

```bash
git clone https://github.com/ruillo747/GS-Multiplayer.git && cd GS-Multiplayer/server
npm install && cp .env.example .env   # укажите RELAY_PUBLIC_HOST и RELAY_SECRET
node signaling/index.js               # порт 35500 TCP (WebSocket)
node relay/index.js                   # порт 35501 UDP (relay, опционально)
```

Дальше: [инструкция по развёртыванию](DEPLOY.md) · [бесплатный деплой на Render](deploy.md#render) ·
[API сервера](SERVER_API.md) · [сетевые протоколы](PROTOCOL.md)

После развёртывания впишите адрес в игре: **GS Multiplayer → Настройки → Signaling-сервер** → `ws://ваш-сервер:35500`.
Готовый конфиг можно сгенерировать на странице [конфига](config.html).

## Документация

| Документ | О чём |
|---|---|
| [Запуск для игроков](RUNNING.md) | установка, комнаты, друзья, диагностика |
| [Сборка из исходников](BUILDING.md) | Gradle, тесты, релизы |
| [Развёртывание сервера](DEPLOY.md) | VPS, systemd, TLS/wss, файрвол |
| [Деплой на Pterodactyl-панели](PTERODACTYL.md) | zertix.pw и любые Pterodactyl: загрузка, переменные, старт | 
| [API signaling-сервера](SERVER_API.md) | протокол WebSocket, комнаты, presence |
| [Сетевые протоколы](PROTOCOL.md) | P2P, NAT punch, relay, надёжный канал |

## Исходники

Репозиторий: [github.com/ruillo747/GS-Multiplayer](https://github.com/ruillo747/GS-Multiplayer) ·
CI собирает мод и прогоняет тесты на каждый push; релизы публикуются по тегу `v*`.
