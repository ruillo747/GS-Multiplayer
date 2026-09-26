# GS Multiplayer

**Сайт проекта:** <https://ruillo747.github.io/GS-Multiplayer/> — статус сервера, генератор конфига, документация.

**Играйте в обычный одиночный мир Minecraft Java 1.16.5 с друзьями через интернет — без своего сервера.**

P2P-соединение как основной способ связи, Relay-сервер как резервный. Максимум 4 игрока. Языки: русский + английский (определяется автоматически по языку игры).

```
Minecraft Java 1.16.5
        ↓
 GS Multiplayer
        ↓
 Играть с друзьями
        ↓
  Создать комнату
        ↓
Мир доступен друзьям
        ↓
 Друг нажимает на мир
        ↓
        P2P
   ↓ успешно      ↓ не получилось
Игра напрямую     Relay
 (быстро)           ↓
                Игра продолжается
```

## Как это работает

1. **Хост** открывает свой одиночный мир: мод публикует интегрированный сервер Minecraft на локальном порту и создаёт комнату на signaling-сервере.
2. **Гость** вводит код комнаты (или принимает приглашение от друга) — мод сам согласует соединение.
3. Мод пробивает NAT (UDP hole punching + STUN). Успех → прямое P2P-соединение.
4. Не удалось (симметричный NAT, жёсткий файрвол) → трафик идёт через **Relay**-сервер. Игра продолжается.
5. Внутри туннеля работает надёжный упорядоченный канал (SACK-протокол поверх UDP), через который мостится TCP-трафик Minecraft.

Игровой трафик идёт напрямую между игроками. Сервер видит только signaling (поиск комнат, presence, приглашения) и ничего не хранит: друзья и настройки — локально в `.minecraft/config/gsmultiplayer/`.

## Установка (игроку)

1. Установите [Fabric Loader](https://fabricmc.net/use/installer/) для Minecraft **1.16.5**.
2. Положите `gsmultiplayer-<версия>.jar` (со страницы [Releases](../../releases)) в папку `.minecraft/mods/`.
3. Запустите игру. В главном меню и в меню паузы появится кнопка **GS Multiplayer**.

## Быстрый старт (двое друзей)

1. Оба игрока ставят мод.
2. Хост: **Одиночная игра → ваш мир → Esc → GS Multiplayer → Открыть комнату**.
3. Хост передаёт другу код комнаты (кнопка «Скопировать код») — по любому каналу связи.
4. Друг: **GS Multiplayer → Подключиться по коду → ввести код → Подключиться**.
5. Всё. Игра сама запустит подключение к миру: P2P если получилось, Relay если нет.

Друзья добавляются по GS ID (виден в настройках) — тогда доступен список с presence и приглашения в один клик.

## Свой сервер (signaling + relay)

Подойдёт любой VPS с Node.js ≥ 16:

```bash
git clone https://github.com/ruillo747/GS-Multiplayer.git
cd GS-Multiplayer/server
npm install
cp .env.example .env      # укажите RELAY_PUBLIC_HOST и RELAY_SECRET
node signaling/index.js   # TCP 35500: signaling + health
node relay/index.js       # UDP 35501: relay
```

Затем в игре: **Настройки GS Multiplayer → Signaling-сервер** → `ws://ВАШ_IP:35500`.

Полные инструкции:
- [docs/RUNNING.md](docs/RUNNING.md) — запуск для игроков (RU)
- [docs/BUILDING.md](docs/BUILDING.md) — сборка из исходников (RU)
- [docs/DEPLOY.md](docs/DEPLOY.md) — развёртывание signaling/relay на VPS: systemd, TLS/wss, файрвол (RU)
- [docs/PTERODACTYL.md](docs/PTERODACTYL.md) — деплой на панелях Pterodactyl (zertix.pw и др.) (RU)
- [docs/SERVER_API.md](docs/SERVER_API.md) — API signaling-сервера (RU)
- [docs/PROTOCOL.md](docs/PROTOCOL.md) — сетевые протоколы P2P/Relay (RU)

## Структура проекта

```
GS-Multiplayer/
├── src/main/java/com/gsmultiplayer/
│   ├── client/          # клиентская точка входа, утилиты GUI
│   │   └── gui/         # экраны мода
│   ├── mixin/           # точки интеграции с Minecraft (кнопки меню и т.п.)
│   ├── network/         # signaling-клиент, собственный WebSocket (RFC 6455)
│   ├── p2p/             # UDP-ендпоинт, кандидаты, STUN, NAT punch, туннель
│   │   ├── reliable/    # надёжный упорядоченный канал (SACK) поверх UDP
│   │   └── bridge/      # мост Minecraft TCP ↔ туннель
│   ├── relay/           # работа с relay-сервером
│   ├── room/            # комнаты, приглашения, оркестрация соединений
│   ├── friends/         # локальный список друзей + presence
│   ├── config/          # config/gsmultiplayer.json
│   ├── security/        # коды комнат, GS ID, санитизация ввода
│   ├── update/          # проверка обновлений через GitHub Releases
│   └── util/            # лог, потоки
├── server/
│   ├── signaling/       # WebSocket-signaling, комнаты, presence (Node.js)
│   ├── relay/           # UDP-ретранслятор пакетов
│   ├── common/          # конфигурация, HMAC-токены
│   └── test/e2e.js      # интеграционные тесты сервера
├── docs/                # документация (RU)
├── .github/workflows/   # CI: сборка + релизы
├── gradle/              # Gradle wrapper
└── build.gradle
```

## Сборка

```bash
./gradlew build          # мод: build/libs/gsmultiplayer-<версия>.jar
cd server && npm test    # тесты сервера
```

Подробности — [docs/BUILDING.md](docs/BUILDING.md).

## Архитектурные свойства

- **Модульность**: GUI, сеть, P2P, relay, комнаты, друзья, конфигурация, безопасность и диагностика разделены; серверный signaling не знает о Minecraft.
- **Без центральной БД**: сервер stateless, данные друзей — локально.
- **Relay с токенами**: выдача UUID-подобных HMAC-токенов на signaling, проверка на relay без общей памяти.
- **Диагностика**: экран «Настройки → Диагностика» с GS Multiplayer Log и режимом разработчика (P2P: Connected / Latency / Relay / Packet loss).
- **Обновления**: проверка GitHub Releases, уведомление с кнопками «Подробнее»/«Скачать», никаких автозамен .jar.
- **Открыть в сеть (как в Open2Online)**: авто-проброс порта через UPnP/NAT-PMP/PCP, адрес мира (`ip:порт`) — в чат и на экран, кнопка «Подключиться по IP» у друзей, пересоздание правил брандмауэра Windows.
- **Расширяемость**: рассчитано на новые relay-регионы, больше игроков, новые версии Minecraft.

## Статус проекта

- Мод (Fabric, Java 8, MC 1.16.5): протокол P2P/Relay, GUI, локализация, CI — готово и покрыто тестами ядра (WebSocket-клиент, signaling-сессии, reliable-канал с 25% потерь).
- Сервер (Node.js): signaling + relay, e2e-тесты — готово.
- Реальные P2P-сессии между машинами рекомендуется перепроверить на вашей сети перед публичным анонсом: пробивка NAT сильно зависит от окружения.

## Лицензия

[MIT](LICENSE)
