# API signaling-сервера

Адрес: `ws://<host>:35500/` (или `wss://`). Сообщения — JSON-объекты c полем `t` (тип).
HTTP-эндпоинты: `GET /health`, `GET /stats`.

## Поток работы

```
клиент                                сервер
  │ ws connect                           │
  │ ─── hello {name, gsid} ────────────► │  регистрирует presence
  │ ◄──────────── ready {name} ───────── │
  │ ─── room.create {name, maxPlayers} ► │
  │ ◄──────── room.created {room} ────── │  (хост)
  │ ─── room.join {code} ──────────────► │
  │ ◄──── room.joined {room, peers} ──── │  (гость; peers = остальные)
  │ ◄──── room.peerJoined {peer, room} ─ │  (всем остальным)
  │ ─── signal {to, data} ─────────────► │  P2P-кандидаты и т.п.
  │ ◄──── signal {from, data} ────────── │
  │ ─── presence.watch {gsids} ────────► │  подписка на друзей
  │ ◄──── presence.snapshot {entries} ── │
  │ ◄──── presence.update {entry} ────── │  (по мере изменений)
  │ ─── invite {to, roomCode} ─────────► │  (только хост)
  │ ◄──── invite {from, roomCode, ...} ─ │  (адресату)
  │ ─── invite.response {to, accepted} ► │
  │ ◄──── invite.response {from, ...} ── │  (инициатору)
  │ ─── relay.request ─────────────────► │
  │ ◄──── relay.token {host, port, ...}─ │
  │ ─── heartbeat ─────────────────────► │  раз в ~15 c (иначе presence истечёт)
  │ ─── room.leave ────────────────────► │
  │ ◄──── room.left ──────────────────── │
```

## Форматы сообщений

### От клиента

| `t` | Поля | Ответ/эффект |
|---|---|---|
| `hello` | `name`, `gsid` | `ready`; регистрирует presence |
| `room.create` | `name`, `maxPlayers`≤4 | `room.created`; хост выходит из прежней комнаты |
| `room.join` | `code` | `room.joined` + `peers`, остальным `room.peerJoined` |
| `room.leave` | — | `room.left`; хост закрывает комнату (`room.closed` всем) |
| `room.list` | `query`? | `room.listed` (до 50 комнат, поиск по имени/коду) |
| `signal` | `to` (sessionId), `data` (объект) | адресату: `signal {from, data}`; только внутри одной комнаты |
| `invite` | `to` (GS ID), `roomCode` | адресату `invite`; только хост комнаты |
| `invite.response` | `to` (sessionId), `accepted` | инициатору `invite.response` |
| `presence.watch` | `gsids[]` (≤64) | `presence.snapshot`, затем `presence.update` |
| `relay.request` | — | `relay.token` (если relay настроен) |
| `heartbeat` | — | продлевает presence |
| `ping` | `t` | `pong {clientTime, serverTime}` |

### От сервера

| `t` | Поля |
|---|---|
| `welcome` | `sessionId`, `heartbeatMs`, `maxPlayers` |
| `ready` | `name` |
| `room.created` | `room` |
| `room.joined` | `room`, `peers[]` (`{id, name, gsid}`) |
| `room.peerJoined` | `peer`, `room` |
| `room.peerLeft` | `peerId`, `room` |
| `room.update` | `room` |
| `room.closed` | `code`, `reason` (`host_left`) |
| `room.listed` | `rooms[]` (`{code, name, players, maxPlayers, hostName, hostId}`) |
| `signal` | `from`, `name`, `data` |
| `invite` | `from {sessionId, name, gsid}`, `roomCode`, `roomName` |
| `invite.response` | `from`, `accepted` |
| `presence.snapshot` | `entries[]` (`{gsid, name, status, roomCode}`) |
| `presence.update` | `entry` |
| `relay.token` | `host`, `port`, `token`, `roomCode`, `peerId`, `ttlMs` |
| `pong` | `clientTime`, `serverTime` |
| `error` | `of` (тип исходного сообщения), `code` |

## Коды ошибок

`UNKNOWN_TYPE`, `BAD_CODE`, `ROOM_NOT_FOUND`, `ROOM_FULL`, `NOT_IN_ROOM`,
`PEER_NOT_FOUND`, `BAD_DATA`, `NOT_HOST`, `TARGET_OFFLINE`, `RELAY_DISABLED`.

## Правила

- Максимум игроков в комнате: 4 (`MAX_PLAYERS`).
- Код комнаты: 6 символов из алфавита `23456789ABCDEFGHJKMNPQRSTUVWXYZ` (без 0/O/1/I/L).
- Presence: `online` / `in-room`; запись исчезает через 60 с без heartbeat (TTL).
- Закрытие WebSocket = выход из комнаты + offline в presence.
- Rate limit: 40 сообщений в 5 секунд, иначе соединение закрывается (close 1008).
- GS ID валидного вида: `GS-[A-Z0-9]{4,16}`; сервер не хранит соответствие GS ID ↔ сессия.

## Relay-токен

`relay.token` выдаётся только участникам комнаты:

```
token = "<roomCode>.<peerId>.<expiryMs>.<hmac>"
hmac  = HMAC-SHA256(secret, "<roomCode>.<peerId>.<expiryMs>"), base64url
```

Relay проверяет токен **локально** (общий `RELAY_SECRET`), без обращения к signaling.
Протокол relay описан в [PROTOCOL.md](PROTOCOL.md).

## HTTP

`GET /health`:

```json
{ "ok": true, "service": "gs-signaling", "version": "1.0.0",
  "uptimeSec": 512, "sessions": 7, "rooms": 2, "presence": {"online": 7, "watchers": 3} }
```

`GET /stats` — то же + список комнат (до 20) и счётчики.
