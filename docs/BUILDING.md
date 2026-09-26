# Сборка проекта

## Требования

- **JDK 17** — для запуска Gradle/Loom (сам мод компилируется в Java 8 байткод).
- Node.js ≥ 16 и npm — только для тестов сервера.
- Интернет — для загрузки зависимостей (Minecraft, Yarn, Fabric Loader).

## Сборка мода

```bash
git clone https://github.com/ruillo747/GS-Multiplayer.git
cd GS-Multiplayer
./gradlew build
```

Результат: `build/libs/gsmultiplayer-<версия>.jar` — готовый мод для `.minecraft/mods/`.

Промежуточные артефакты:

| Файл | Назначение |
|---|---|
| `build/libs/gsmultiplayer-<версия>.jar` | основной мод (remapped в intermediary) |
| `build/libs/gsmultiplayer-1.0.0-sources.jar` | исходники (не для игры) |
| `build/devlibs/...-dev.jar` | дев-версия в named-маппингах (для запуска из IDE) |

## Запуск из IDE (разработка)

```bash
./gradlew runClient
```

Loom скачает Minecraft 1.16.5, применит маппинги Yarn и запустит клиент с модом
(клиентский entrypoint `com.gsmultiplayer.client.GsMultiplayerClient`).

## Тесты

**Сервер** (Node.js):

```bash
cd server
npm install
npm test
```

E2E-тест поднимает signaling и relay на свободных портах и прогоняет полный
сценарий: hello → комнаты → signaling → presence → приглашения → relay-токены →
реальный обмен UDP-пакетами через relay → закрытие комнаты хостом.

**Ядро мода** (без Minecraft): тесты reliable-канала и signaling-сессий описаны
в конце `docs/PROTOCOL.md`, в репозитории их можно прогнать любым JUnit-раннером —
классы `ReliableLink`, `SignalConnection`, `WsClient` не зависят от Minecraft.

## Сборка релиза

```bash
./gradlew build
cp build/libs/gsmultiplayer-<версия>.jar releases/
```

Публикация релиза — тегом (см. `.github/workflows/release.yml`):

```bash
# версия в gradle.properties должна совпадать с тегом
git tag v1.0.0
git push origin v1.0.0
```

GitHub Actions соберёт мод, прогонит тесты сервера и создаст Release с jar.
Черновики/пре-релизы: тег с суффиксом (`v1.1.0-beta`) пометится как prerelease.

## Структура версий

- Версия мода задаётся в `gradle.properties` (`mod_version`).
- `fabric.mod.json` получает версию автоматически через `processResources`.
- Теги релизов: `v<мажор>.<минор>.<патч>` (`v1.0.0`).
