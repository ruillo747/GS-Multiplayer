---
title: GS Multiplayer — деплой signaling на Pterodactyl-панели
permalink: /pterodactyl/
---

# Деплой на Pterodactyl-панели (zertix.pw и любые другие)

Инструкция для игровых панелей на базе [Pterodactyl](https://pterodactyl.io)
(скрин-пример: `panel.zertix.pw`, адрес выдаётся вида `z1.zertix.pw:25607`).
Подойдёт любой сервер с Egg **«Node.js» (generic)** — signaling очень лёгкий,
хватает 1 ГБ ОЗУ (реально занято 50–80 МБ).

## Что получится

- **signaling** (WebSocket + HTTP `/health`) — на главном порту сервера;
- **relay** (UDP, запасной путь, когда P2P не пробился) — на втором порту, если хостер его выдал;
- обе части поднимаются **одной startup-командой** через `start-all.js`.

## Шаг 1. Загрузите файлы сервера

1. Скачайте папку `server` из репозитория
   ([github.com/ruillo747/GS-Multiplayer](https://github.com/ruillo747/GS-Multiplayer) → каталог `server`)
   и упакуйте в ZIP. В корне архива должны лежать: `package.json`, `start-all.js`,
   папки `signaling/`, `relay/`, `common/` (папку `test/` можно не включать, `node_modules` не нужен).
2. В панели: **Files → Upload** → загрузите ZIP.
3. Правой кнопкой по архиву → **Unarchive** («Распаковать»).
   Важно: файлы должны оказаться прямо в `/home/container`
   (путь `/home/container/package.json`, `/home/container/signaling/index.js`),
   а не во вложенной папке `server/`.

## Шаг 2. Настройте Startup

Вкладка **Startup**:

| Поле | Значение |
|---|---|
| Egg / Docker image | Node.js (generic), Node **18+** |
| Startup Command или Main/JS file | `node start-all.js` (если панель даёт только имя файла — впишите `start-all.js`; подходит и обычный `npm start` — он теперь делает то же самое) |

## Шаг 3. Задайте переменные

Там же, во вкладке Startup (Variables), заполните:

### Вариант без переменных: файл .env

Проще всего загрузить в корень сервера файл `.env` — сервер прочитает его сам
(переменные окружения панели при этом имеют приоритет):

```
RELAY_SECRET=длинная-случайная-строка
RELAY_PUBLIC_HOST=z1.zertix.pw
```

Обязательны только две переменные, остальные панель подставит сама
(generic Node.js Egg всегда выставляет `SERVER_PORT` = порт аллокации — signaling
использует его автоматически, если не задан `SIGNALING_PORT`):

| Переменная | Значение | Зачем |
|---|---|---|
| `RELAY_SECRET` | любая длинная случайная строка | подпись relay-токенов |
| `RELAY_PUBLIC_HOST` | `z1.zertix.pw` (ваш адрес без порта) | хост, который прописывается клиентам в relay-токен |
| `SIGNALING_PORT` | не обязательно: по умолчанию берётся `SERVER_PORT` панели | порт signaling |
| `RELAY_PORT` | второй порт, если выдали (иначе оставьте `35501`) | порт relay (UDP) |

Если панель не позволяет добавлять переменные — задайте их прямо в startup-команде:

```
RELAY_SECRET=моя-строка RELAY_PUBLIC_HOST=z1.zertix.pw node start-all.js
```

## Шаг 4. Установите зависимости и запустите

1. Если у Egg есть кнопка **Reinstall** (Settings → Reinstall) — нажмите:
   generic Node.js Egg выполнит `npm install` (зависимость одна — `ws`).
2. Нажмите зелёную **Start**.
3. В консоли должно появиться:

```
[start-all] signaling on 0.0.0.0:25607, relay on udp/35501
[signaling] listening on 0.0.0.0:25607
[relay] listening on 0.0.0.0:35501/udp
```

## Шаг 5. Проверьте

Откройте в браузере `http://z1.zertix.pw:25607/health` — должно быть:

```json
{"ok":true,"service":"gs-signaling","version":"1.0.0",...}
```

## Шаг 6. Подключите мод

В игре: **GS Multiplayer → Settings → Signaling server** = `ws://z1.zertix.pw:25607` → **Save**.
В главном меню статус сменится на «Server: online».

## Если что-то не так

| Симптом | Причина и решение |
|---|---|
| В меню «Server: offline, retrying…» | signaling не поднялся или порт закрыт снаружи: проверьте `/health`, спросите хостера, проброшен ли порт аллокации наружу |
| `Cannot find module 'ws'` | не выполнен `npm install` — нажмите Reinstall или выполните установку через startup |
| `EADDRINUSE` | порт занят: укажите правильный порт аллокации в `SIGNALING_PORT` |
| relay «не снаружи» | хостер не дал UDP-порт. Это **не критично**: основной путь — P2P (NAT punch + STUN), relay лишь запасной. Попросите у хостера вторую аллокацию с UDP |
| Хочется `wss://` | Pterodactyl сам TLS не терминирует; попросите у хостера SSL-проксирование или используйте `ws://` (мод работает и так) |

## Обновление сервера

Загрузите новые файлы поверх старых (Files → Upload → Unarchive) и нажмите **Restart**.
Сессии переподключатся сами (мод делает авто-reconnect signaling).
