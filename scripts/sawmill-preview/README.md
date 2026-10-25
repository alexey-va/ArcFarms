# Кедровая круговая пилорама

Нативная сцена ArcFarms для `server-id=spawn`, мира
`rc_atelier_cedar_sawmill`, с началом `[188.5, 73, 180.5]` и подачей вдоль
`+Z`. Ближайший ориентир в цехе — `191 73 182`.

Один проход занимает 500 серверных ticks: каретка проводит бревно через
круговую пилу, один горбыль остаётся на выходном столе, а то же бревно с
сохранённой спиленной гранью отводится от диска и возвращается. Физический
рычаг запускает, приостанавливает и продолжает цикл; после завершения
запускает повтор. Состояние общее для зрителей рядом. Reload сбрасывает
демонстрацию в покой. Смена работы, инвентари и награды не добавляются.

Визуалы обслуживают существующие `PaperPacketDisplays` и `PacketDisplayScene`,
а один native `Interaction` и проверку попадания — `PaperSpatialControls`.
Функция сцены не меняет блоки мира. Удаление старого декора при установке
выполняется отдельно, с before-image и проверкой точных preimage.

## Канонические источники

- `src/main/resources/sawmill/cedar-machine.json` — детали, материалы, размеры,
  опоры, камеры и привязки моделей.
- `paper/lumber/sawmill/CedarSawmillGeometry.kt` и `CedarSawmillAnimation.kt`
  — геометрия и единственный расчёт поз.
- `CedarSawmillController.kt`, `CedarSawmillDisplays.kt`, `CedarSawmillPlayback.kt`
  — жизненный цикл, управление, пакетные визуалы и часы.
- `generate-native-models.mjs`, `native-models/` — нативные JSON-модели с
  обычными текстурами Minecraft, служебные PAPER ID и точные contact bounds.
- `ground-native-models.py` — воспроизводимая проверка моделей через
  `itemsadder-item-display-grounding` с явно помеченной локальной кандидатной
  allocation fixture; она не меняет активный кеш ItemsAdder.
- `room.snapshot.json` — очищенный снимок помещения от 25 октября 2026 года,
  только блоки и их состояния, без данных игроков.

## Проверки и локальное превью

Из корня ArcFarms, с Java 25:

```sh
./gradlew validateCedarSawmill
python3 scripts/sawmill-preview/ground-native-models.py --client-jar /absolute/path/to/minecraft-1.21.11-client.jar
node scripts/sawmill-preview/check.mjs
node scripts/sawmill-preview/build.mjs
python3 -m http.server 8848 --bind 127.0.0.1 --directory build/reports/sawmill-preview
```

`shadowJar` также запускает проверку и экспорт канонических поз.
`generate-native-models.mjs` нужен при изменении самих моделей; после него
повторите экспорт, grounding и сборку превью. Путь к grounding skill по
умолчанию — `~/.codex/skills/itemsadder-item-display-grounding`; параметр
`--skill=/path` задаёт другой установленный путь.

Откройте `http://127.0.0.1:8848/?view=operator`. Для сравнения используйте
`?view=drive&t=5.55&paused=1`, включая промежуточные позы между ключевыми
кадрами. Превью читает экспорт скомпилированного Kotlin и грани настоящих
моделей, включая native Y180 и контекст `NONE`. JavaScript не реализует
вторую анимацию станка. Manifest связывает байты классов, экспорт и модели;
сборщик отклоняет устаревший экспорт граней.

Браузерный рычаг и таймлайн служат для просмотра. Превью не подключено к
живому серверу. `preview-mask.json` перечисляет старые интерьерные детали,
скрытые только в браузере; `.schem` для импорта после сборки не сохраняется.
Свет и рендер браузера приближённые: нативное появление, пакетную доставку,
управление и плавность проверяют отдельно после активации.

## Модели и установка

Набор состоит из `arc_farms:cedar_sawmill_{raw,sawn,slab,blade,pulley}`.
Служебные PAPER model ID — `271001..271005` в том же порядке; предметы не
выдаются игрокам и не добавляются в награды или магазины. Runtime задаёт
прямой `item_model` и соответствующий CMD.

Канонический pack owner — `ruscrafting-ops`:
`classic*/plugins/ItemsAdder/contents/arc_farms/`. У обоих source mirrors
одинаковые определения и модели. Публикация выполняется обычным ItemsAdder
workflow на Spawn; серверная JAR-сцена включается только на Spawn и в
указанном мире. Backup и точное чтение результата замены блоков сохраняются
в отдельном deployment artifact, а не в исходниках preview.
