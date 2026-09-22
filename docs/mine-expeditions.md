# Mine expeditions — permanent locations and factory operation

Geometry version 3 uses separate 112-block cells in the configured mine world,
starting 112 blocks beyond the mine boundary. A completed site returns to the
ready stock without regenerating its terrain. The durable `siteBuilt` receipt
preserves administrator block edits across restarts. Explicit `rebuild` retires
unused sites through their original-block journals and prepares replacements.
Legacy geometry versions remain readable for recovery.

The factory is a 73 × 29 × 67 cavern with a symmetric production floor, clear
light-stone aisles, dark technical pads, large display assemblies and 25 pendant
lamps below the roof bars. Machinery is always present on prepared sites. Only
current objectives acquire gameplay glow. Completion returns participants;
the factory's single entry return portal remains available independently of
event markers; other expeditions may keep separate entry and exit portals.

## Factory work

1. Open the pump valve and switch the crusher's start lever. Its two drive
   gears belong to the roller shafts; the detached repair gear/socket is removed.
   Opening the water circuit also credits the omitted repair checkpoint, preserving
   the ten-credit total. Nearby workers share valve progress; duplicate clicks
   inside 250 ms do not count. Hand controls interpolate each turn; powered rolls
   turn continuously until the event ends. Consoles have floor-mounted plinths
   and uprights, with the handles kept at player height.
2. Push an ore-and-coal cart from the bunker to the crusher inlet. Switch the
   feed lever: a six-second cycle drops visible material between counter-rotating
   toothed rolls and carries fragments along the top of the moving belt. The
   processed charge is transferred by that belt into the furnace inlet and
   completes the receiving checkpoint after its visible one-way journey; no second
   cart or manual receiver step interrupts the line. Only the current source
   or control is highlighted, and stale clicks cannot grant credit.
3. Start the furnace with its mounted handle. The automatic six-second heating
   cycle shows a percentage; at 100% it turns green and rings a bell. Readiness
   stays latched until the release click advances the durable checkpoint.
   Repeated clicks cannot restart the run or switch it into cooling.
   Fire and chimney smoke follow the heating/pouring stages.
4. Start the flow from the separate console beside the casting bed. Close it
   with another right-click while the percentage and lever glow green from
   100% through 130% (the nominal fill is 100%). The 10-second fill has a
   3-second success window; closing above 130% resets the batch for a free
   retry. The player sees only the percentage, green from 100%, and a brief
   red retry message after an overflow. A bell, rising molten surface, droplets and lava sounds show the
   flow; leaving releases the control, and another worker cannot steal an active
   pour.
5. Transfer the casting across the seven-block crane span. In a normal run,
   the console starts an automatic lift, traverse and lowering cycle. The manual
   variant uses the physical six-button console described below. The chain follows
   the load. No casting is visible before production, and the receiving conveyor
   starts only after placement has completed.
6. The placed billet travels for four seconds from the conveyor start into the press; no second
   press cart is spawned. Its ram descends one block, strikes with sparks, dust
   and an anvil sound, then returns. The final domain
   checkpoint occurs after the whole 2.4-second stroke. The finished drive gear
   stays on the press for a 12-second result presentation with a bell, particles
   and return countdown; only then does automatic return occur. The return
   portal remains usable during work without objective glow, and glows after
   completion.

A powered cycle belongs to one operator. Repeated clicks cannot restart or
stack it. Walking away inside the factory leaves it running; leaving the site
or changing stage cancels it. The existing cargo lease also owns every cart:
its packet body, rolling wheels, visible load and vanilla leash disappear on
unload, departure, disconnect, stage change, reload and shutdown. Camera-only
turns do not orbit the cart. Unfinished loads become available at the source
again, and contact/click delivery cannot double-credit a load. No
ordinary item drops are created. Sounds are local (32-block range), respect
`ui.sounds`, and repeated effects are throttled; particles respect `ui.particles`.

Economy scope: the factory still has ten domain checkpoints and the same
completion reward path. Direct changes per completion are zero Vault coins,
zero premium tokens, zero XP and zero ordinary reward items. Temporary fuel and
castings remain non-loot displays. Added machine cycles change pacing; income
per hour has not been measured and is not claimed to be unchanged.

Modern factory plans expose `crusher_feed` and share one connected west-to-east
line. Legacy plans without that station retain the old commissioning variants.
Both flows keep ten checkpoints. The continuous dark floor is migrated through
bounded journal-first batches only where blocks still match the former default;
custom floor blocks remain untouched. Arrival faces inward along the line, with
one permanent return portal at the shared entry/exit anchor.

## Optional factory interactions

New connected-factory runs select zero to two distinct situations from three.
The selected and resolved situations are persisted with the expedition; restarting
never rerolls an existing choice. Retired mould, gear and routing situations are
filtered from old saved plans without resetting checkpoints. An older run without
a plan receives an empty selection. Animation progress safely resets after restart;
no extra material or checkpoint credit is created.

- **Rock jam:** a small stone visibly blocks the crusher. Three separated pries
  draw it out; the rolls remain stopped until it is removed.
- **Bearing overheating:** smoke and sparks mark the hot bearing. A short notice
  explains why the line stopped. Taking the nozzle starts water immediately;
  aim it at the hot part. A visible hose connects the reel and held nozzle, and
  water, steam and local sounds make the cooling visible. There is no sneak gate.
- **Manual crane:** use the floor-mounted console to lift the existing casting,
  move it left/right/forward/back in bounded steps, then lower it onto the glowing
  conveyor landing. Buttons have separate hitboxes. A misplaced load stays where
  it was lowered: lift it again and correct the position. Leaving releases the
  controller and returns the unfinished load to its source. The conveyor starts
  only after the actual lowering animation reaches the correct landing.

Glow marks the current task. Short labels name objects and actions; a one-time
notice explains each incident and its purpose. Temporary apparatus and held
displays are cleaned on departure, death, disconnect, stage change, reload and
shutdown. No reward amount or drop chance changes. The permanent room and saved
administrator furnishing placements are retained.

For repeatable testing, configure the **next** run, then start the event:

```text
/arcfarms admin expeditions factory old_shafts all
/arcfarms admin event old_shafts dead_factory
```

Accepted presets: `random`, `none`, `all`, `rock`, `crane`, `cooling`. `random` removes a queued override. The setting is one-shot,
scoped to that mine, and does not reset an active run. Pending admin overrides
are transient; the chosen set is durable once its run starts. Never rebuild the
permanent factory just to test a different interaction.

## Editing and preview

`/arcfarms admin expeditions edit dead_factory` enters an idle prepared site in
explicit editor mode. Right-click selects an assembly; F opens one-block moves,
15-degree turns, reset, save and cancel. Ordinary gameplay clicks cannot move
machines. Moving a furnace also moves its grouped controls. Whole animated
bounds are checked, and `data/mine-expedition-furnishings.json` saves placements
asynchronously. Never rebuild a site whose manual changes should be retained.

`scripts/mine-preview/export.gradle` exports geometry and display models from
Kotlin. `scripts/mine-preview/build.mjs` builds an editable Atelier preview with
ten factory camera positions. **Demo mechanisms** animates the same rolls, conveyor, material fragments,
lever and press transforms; it does not simulate gameplay, cargo, sound or
particles. Schematics contain blocks only; ArcFarms owns display assemblies.

## Verification and activation — 2026-09-21

The 0.44.1 focused run passed 38 tests across architecture, factory operations
and the existing farm processing lifecycle. New cases cover a real tether
anchor through duplicate clicks, completion, leaving the ring, stage change,
player release and shutdown, plus fuel lease retention on rejected delivery.
MockBukkit does not model client leash rendering. The preceding 0.44.0 run
passed 40 tests, including crane duplicate
clicks and departure, press cargo retention and cleanup, complete ram motion,
domain progression, compact geometry and the moving crane suspension. `shadowJar` and preview export passed.
The offline factory route has 43 supported/unblocked points and all 162 sampled
walking points meet block-light level 8. The web preview opens with an animated
press.

The first activation exposed an editor lifecycle initialization error: its
constructor requested a task epoch before the owning service was active.
Version 0.44.2 defers placement loading until reconciliation after activation.
Its separate focused run passed 38 tests across architecture, task supervision
and the editor startup regression; `shadowJar` passed. These run counts overlap
and are not a unique-test total.

Source `464289b` is active on spawn/classic as ArcFarms 0.44.2. JAR transaction
`jar-20260920T210523Z-48606` verified SHA-256
`8677e565cb077042cb758d289393bf06c1ab22ea74feff00d4c04e1328fa545b`.
The authorized spawn-only restart reached Paper ready at 00:08:30 MSK with PID
3339967. ArcFarms reported ready, no pending recovery and healthy content.
All three geometry-v3 receipts subsequently reached `siteBuilt=true`,
`reserved=true`, `restoring=false` in `rc_atelier_compact_mine`:

| Site | Journal | Origin |
| --- | --- | --- |
| Last Descent | 16 | 155, 106, -112 |
| Drilling Ark | 17 | 155, 106, -224 |
| Dead Factory | 18 | -69, 106, -336 |

A normal survival QA actor walked from the factory entry along the central
aisle without an embedded camera or health loss. The enhanced client viewer
received the large machinery, pendant lamps and both labelled return portals.
Right-click by the west return portal moved the actor from the factory back to
the mine surface at 68.5, 111, 31.5. This verifies a permanent room and its manual
return path, not a complete active expedition, automatic completion return,
client leash rendering, sound balance or runtime lag. The QA actor has no admin
permission, so forced event startup and the full production cycle were not
tested live. The viewer reports a stale world name after same-dimension travel;
scene receipts, coordinates and rendered terrain establish the room placement.

---

## Historical 0.43.0 release notes

Current locations are compact, self-contained caves in the configured mine world.
The scene stock allocates disjoint 64-block cells north of the mine, starting
80 blocks beyond its boundary. No expedition world is created. Each stored
receipt keeps its world, placement, seed, geometry version and original-block
journal across restarts. Version 1 plans remain available solely to restore the
old remote locations. New plans use version 2; occupied cells are not reused.

- Last Descent: 43 × 30 × 45 blocks, three docks and an open-front lift with
  20 blocks of travel. A copper engine sits behind the lower chamber.
- Drilling Ark: 49 × 19 × 51 blocks, two short branches, timber frames,
  hanging lamps and a complete clearance envelope for the crawler.
- Dead Factory: 49 × 22 × 41 blocks, a water wheel, foundry, overhead crane
  and separate working decks connected within one chamber.

Entry requires a supported, passable destination in the same world as the mine.
Travel verifies the actual position after Bukkit teleportation and preserves
its return receipt if a redirected player cannot be returned safely.

Lost Miner uses a 37 × 37 cave footprint with a winding route, side pockets,
and two weaker guards (8 health, 1 attack damage). Right-clicking the labelled
miner completes the rescue; there is no escort objective. Lateral workings now
extend 38 blocks from their authored entrance; the track test uses a real
minecart. Old geometry-version-3 workings are retired through their journals.

Flooding issues a temporary empty bucket. Its complete 20–30-cell flow area is
journalled before a water source activates; vanilla flow stays within those
owned cells. The bucket also targets flowing water and remains empty. Glow
sits beneath the water. Gas clouds have a 6.5-block hazard radius and bounded
particle updates. Nests can produce up to two additional living creatures each,
with at most seven tracked creatures and twelve births per incident. Creatures
follow small supported height changes but reject paths descending into the shaft.

Completion clears task markers, glow and interactive machinery immediately.
The physical cave retains its existing departure grace; walking back to the
expedition/rescue entrance returns a retained participant without an exit marker.
The long-stop warning and occupied-location cleanup deadline remain in place.

## Verification and activation — 2026-09-20

Source commit `7469a95` was built as 0.43.0. The focused regression run covered
31 tests: 30 passed and one environment-dependent test was skipped. Changed
compact-layout, stock and construction tests passed again after the final
geometry adjustments. `shadowJar` and changed-translation validation passed.
This was not a full-suite or client-playthrough check.

Only runtime `spawn` (classic) restarted, completing at 19:43:41 MSK; PID changed
from 3273837 to 3286563. ArcFarms enabled as 0.43.0. The deployed JAR SHA-256 is
`02cb3d029f140f0aef1f14d52ac6796202f0a8c22e94a86fc8ad95e57692d0e9`.
All three geometry-version-2 reserves became ready by 19:44:04 MSK and their
receipts are persisted in `mine-expedition-scenes.json`:

| Kind | Journal | Origin | Verified entry |
| --- | --- | --- | --- |
| LAST_DESCENT | 10 | -21, 106, -80 | -21, 131, -63 |
| DRILLING_ARK | 11 | 43, 106, -80 | 43, 111, -102 |
| DEAD_FACTORY | 12 | 107, 106, -80 | 90, 111, -66 |

All six world fields (scene and return for each reserve) name
`rc_atelier_compact_mine`. At 19:47 MSK native Denizen block reads confirmed
feet/head/floor as air/air/tuff, air/air/stone and air/air/stone respectively.
No ArcFarms WARN/ERROR appeared in the inspected startup log. RCNet restart job:
`69487ff403f7d4933e600dacad036c1e94fb614a27e869cb20e5a108ed418269`;
block/hash readback: `ad141b3bed87816e7957d906e77c0411303ed0d7093f1be077b53571d56344e9`.

Native water flow, creature pursuit, minecart travel and visual quality still
need a player walkthrough. No before/after lag measurement was taken. Historical
release evidence below does not certify this release's client behaviour.

---

# Экспедиции шахты

Три события отправляют игроков через подсвеченный вход из `old_shafts` в заранее подготовленные постоянные локации рядом с шахтой в том же мире. Их можно обустраивать вручную; завершение ивента не перестраивает комнаты. У завода один обратный портал, у длинных маршрутов — метки на обоих концах. Для игры достаточно обычного доступа к шахте.

## Последний спуск — `LAST_DESCENT`

Высокая пещера с тремя причалами, геологическими слоями, деревянными крепями, водой и большим медным двигателем. Игроки едут на общей платформе, на средней станции раскручивают три ворота противовесов и переносят три силовых элемента. Затем спускаются к двигателю, открывают три вентиля и включают пускатель.

Платформа 7×5 блоков движется вместе с пассажирами. Передняя сторона, направленная по локальной оси +Z, открыта: передних перил нет. Shift позволяет сойти; после выхода управляющего движение останавливается. Станционные полы соединяются с краем платформы и не пересекают её вертикальный путь.

## Буровой ковчег — `DRILLING_ARK`

Пещерный маршрут с развилкой, причалами, мостиком, опорами и буровой короной. Ковчег имеет настоящий корпус из блоков, котёл, трубы, боковые проходы, гусеницы и вращающийся бур.

Бригада приносит два груза топлива, едет до развилки и выбирает левый или правый проход. Перед дальнейшим ходом нужно разбить три камня у бура и принести два груза охлаждающей жидкости. После поездки по выбранной ветке игроки переносят три керна на борт и возвращают машину к причалу. Камни убираются по одному вместе со своим glow. Керны и топливо видны в руках как переносимый груз.

## Мёртвый завод — `DEAD_FACTORY`

Связная производственная линия слева направо: большая валковая дробилка, конвейер, печь, заливка, роликовый стол и пресс. Сначала нужно найти выпавшую шестерню и установить на подсвеченный вал, открыть охлаждение и включить привод. Затем тележкой подать руду с углём, запустить дробление, а лента сама подаст смесь в печь без второй тележки. Рычаг запускает автоматический нагрев на шесть секунд. На 100% проценты становятся зелёными; готовая печь ждёт нажатия для выпуска металла. У заливки видны только проценты: на 100% подсветка зеленеет, при опоздании ненадолго появляется красное предложение повторить попытку. Кран опускает заготовку на начало роликового стола, после чего она движется в пресс. После удара пресса игрок видит готовую деталь до возвращения. Вход смотрит на цех, подсветка указывает текущую операцию; работающая дробилка продолжает вращаться со звуком и пылью до конца ивента.

## Управление и генерация

Точка отправления задаётся с места, где стоит администратор:

```text
/arcfarms admin point old_shafts expedition_gate
/arcfarms admin worksite mine old_shafts incident LAST_DESCENT
/arcfarms admin worksite mine old_shafts incident DRILLING_ARK
/arcfarms admin worksite mine old_shafts incident DEAD_FACTORY
```

Начальная точка compact-карты — `68.5 111 29.5`, yaw `0`. Свободный пол и высота прохода проверены на карте игровым клиентом. Сохранённая администратором точка имеет приоритет над встроенной. Три события добавлены в пулы заказов `old_shafts`; другие шахты требуют собственной точки отправления и явного добавления событий в конфиг.

`MineExpeditionGenerator` — чистый генератор по seed. Крупный и мелкий связный шум меняет геометрию стен и сводов; пол имеет неровный рельеф за пределами обслуживаемых проходов. Зарезервированы рабочие площадки и весь путь движущихся машин. Практические фонари дополняет невидимый block light над маршрутами. Все интерактивные цели имеют glow; display-сущностям явно задана яркость 15/15.

Мир `rc_arcfarms_expeditions` заполнен камнем, а сцены вырезаются из массива. У каждой экспедиции своё размещение и seed. Подготовка разбита на проходы до 1024 блоков, вход открывается после полной сборки. Проекции машин и восстановление используют общий журнал временных сцен. Состояние этапов, размещение и отметка завершения сохраняются; частично построенная после сбоя сцена сначала восстанавливает исходные блоки.

`ArcFarmsPlugin.getDefaultWorldGenerator` предоставляет генератор этого мира до `onEnable`, чтобы My_Worlds мог загрузить сохранённый мир при старте сервера. Для остальных миров метод возвращает `null`; загрузка генератора не включает игровой модуль и не создаёт мир сама.

После завершения локация остаётся как минимум 60 секунд. Затем её убирают, когда обычные игроки вышли за её границы с запасом 8 блоков. Через 5 минут оставшихся возвращают к входу; последние 15 секунд показывается предупреждение. Наблюдатели могут свободно летать и не удерживают очистку.

## Экономика

Награды и квоты заказа сохранены. В действующем конфиге `old_shafts` завершение заказа даёт каждому подходящему участнику 220 XP и 4 железных слитка; выплата vault равна 0, случайных предметных бросков 0. Новые события сами не дают vault, tokens, XP или предметов. Переносимый груз является визуальным служебным объектом; декорации защищены от добычи, камни у бура не дают дроп или XP.

Продолжительность новых событий меняет возможное число завершений в час. Этот темп нужно измерять игровым прохождением; одинаковая награда за заказ не доказывает одинаковый доход в час. Предметная награда, XP и валюты учитываются раздельно.

## Проверка геометрии

Экспорт из собранного JAR использует тот же генератор и корпус машин:

```sh
java -cp build/libs/ArcFarms-0.41.2.jar scripts/ExportMineExpeditions.java /tmp/expedition-recipes 20921
```

Полученные `.atelier.json` собираются штатным `location-atelier/cli.mjs` ops-репозитория. Статический просмотр показывает блоки и первоначальное положение машины; анимации, glow, пассажиры и ввод требуют проверки на Paper.

## Проверка выпуска 0.41.2 — 20 сентября 2026

На `classic` установлен и включён ArcFarms 0.41.2, собранный из опубликованной ревизии `1580baf1651a61de09046bc9a45c385fff1df8a2`. SHA-256 установленного JAR совпал с кандидатом: `7b510d866e274dc67f1062980e080c98122bc2d51b83ce5b08d59bb47f96b447`. После рестарта сервер сообщил готовность в 05:26:41 МСК; My_Worlds загрузил существующий `rc_arcfarms_expeditions` с генератором ArcFarms без прежней ошибки поиска генератора. JAR и конфигурация активированы только на `classic`.

| Проверка | Результат |
| --- | --- |
| Целевые тесты домена шахты, экспедиций, Paper, журнала сцен и сохранения состояния | 225 прошли, 4 пропущены; ошибок нет. Прогон на `8c28de8` после интеграции arc-core 2.7.10. |
| Регрессия регистрации генератора до включения плагина | 1 тест прошёл на `1580baf`: генератор доступен для точного имени мира; посторонние миры отклоняются; плагин и миры не запускаются при запросе. |
| Инструмент игрового QA | 8 тестов прошли; проверены ограничения запуска, мира и целей взаимодействия, включая native INTERACT для 1.21.11. |
| Вход на compact-карте | Игровой клиент подтвердил свободные блоки для ног и головы, опору под текущей точкой `68.5 111 29.5`. |
| Полное прохождение трёх экспедиций | Пока не выполнено. Нужен запуск администратором или отдельно разрешённое временное право `arcfarms.admin` для тестового игрока. |

Пассажиры движущихся машин, видимость glow, работа переносимого груза и ворот, завершение и отложенная очистка требуют отдельного игрового приёмочного прогона. Успешная сборка, активация и статическая геометрия не заменяют эту проверку.


## Готовые локации и восстановление — 0.42.0

Для LAST_DESCENT, DRILLING_ARK и DEAD_FACTORY заранее строится по одной свободной
локации. Ивент получает уже готовую сцену; после выдачи запас пополняется в фоне.
При первом запуске сервера запас должен закончить первичную подготовку. До этого
новый ивент не захватывает смену. Администратор видит состояние через
`/arcfarms admin expeditions status`; `rebuild all` или
`rebuild dead_factory` удаляет свободные заготовки и создаёт новые. Занятые сцены
не попадают под эту команду.

Координаты, seed, состояние удаления и постоянный идентификатор журнала хранятся
в `plugins/ArcFarms/data/recovery/mine-expedition-scenes.json`; сами изменения
блоков остаются в журнале чанков. Выдача заготовки сначала сохраняется на диск,
после чего игрок получает доступ. Идентичность блочного журнала при выдаче не
меняется. Штатное выключение дожидается незавершённых записей. После рестарта
возвращаются существующие заготовки, включая незаконченные удаления. Обычная
очистка визуальных сцен больше не закрывает хранилище при старте сервиса.

Подготовка ограничена тремя минутами, ошибки чтения/записи и загрузки чанков
попадают в журнал. Отменённая выдача, завершившая запись позднее, уходит на
восстановление. Завершённые сцены сохраняют прежнее окно выхода: минимум минуту,
пока игроки рядом — до пяти минут, затем предупреждение и возврат.

### Логова, подсказки и уборка

Логова выбираются внутри доступных площадок: минимум два блока до внутреннего
провала и внешнего края, с повторной проверкой пола. Чудовища преследуют игроков
в пределах 24 блоков, включая креатив, только на своём этаже. Проверка всего
нативного маршрута запрещает путь через провалы. Монстр остаётся собственностью
ивента, когда переходит в соседний чанк. Он может разгрызать боковой камень и руду:
не чаще одного блока за 2,5 секунды на весь ивент, максимум 16 блоков. Пол, дерево,
рельсы и область у лифта исключены. Дропа и XP за это нет. Повреждения восстанавливаются
после завершения/отмены; обратная установка твёрдых блоков ждёт ухода игроков.

В обычном заказе четыре ближайшие доступные руды с незаполненной квотой получают
яркие END_ROD частицы на открытой грани. Поиск использует существующий индекс в
радиусе 12 блоков, проверяет текущий материал и не загружает новые чанки.

Журнал обвала/воды восстанавливается по прежней идентичности ивента при любом
выходе из него. Повторное применение записи ждёт исходного обработчика записи,
чтобы не оставить блок без журнала при гонке. Регрессии покрывают именно эту
последовательность, отмену выдачи заготовки, рестарт журнала и очистку при старте.

### Экономический эффект

Выплаты и квоты не изменены: 0 vault, 0 tokens, 220 XP и 4 железных слитка за
подходящее завершение заказа в проверенном конфиге classic. Само нашествие
добавляет 0 vault / 0 tokens / 0 XP / 0 предметов; повреждённая руда не выпадает,
возвращается только её исходный блок. При 1 / 3 / 6 нашествиях в час верхняя
граница временно повреждённых блоков — 16 / 48 / 96, прямых новых выплат — 0.
Это сценарные границы, не наблюдение доходности. Яркие подсказки и готовый запас
сокращают поиск и ожидание, поэтому XP/предметы в час могут вырасти; фактический
темп требует игрового замера. SELL EconomyShopGUI и будущие контракты остаются
отдельными сценариями, курс предметов в монеты не предполагается.

### Проверка выпуска 0.42.1

Ревизия `efd723e` содержит основное обновление 0.42.0, `a0411de` — исправление
обнаруженного на сервере пересоздания свободного запаса. Общий владелец сцен
блокирует строительство, пока восстанавливает другой журнал того же владельца.
Поэтому новая заготовка теперь получает собственный `journalZoneId`, который
сохраняется и после выдачи игрокам. Новая сцена может строиться параллельно
удалению предыдущей. Регрессия проверяет замену всех трёх видов при ещё
существующих журналах удаления и сохранение занятой экспедиции.

После основной сборки точечный прогон сохранения, восстановления, локалей и
админских команд дал 26 успешных тестов и один пропущенный. После поправки
пересоздания все 14 тестов запаса, репозитория и общего журнала сцен прошли.
Каталог RU/EN прошёл проверку новых/изменённых строк; старые предупреждения
каталога относятся к другим экранам. Полный локальный валидатор профиля имеет
прежнюю несовместимость с диапазоном количества ивентов в неизменённых конфигах;
проверка локалей отдельно подтверждает схему, placeholders и три зеркала.

На classic включён JAR 0.42.1 с SHA-256
`0c031b37866d230e78e7b8396d23fa3c0de07682c4420231efdaa1d87ecf3020`.
После настоящего рестарта файл квитанций совпал побайтно с сохранённым перед
остановкой: SHA-256
`713b029bd1942a850d0513b03bbefe63e0825f17cb2614602e0dbb301ceeda54`.
Сервер восстановил готовые запасы LAST_DESCENT (1), DRILLING_ARK (4),
DEAD_FACTORY (5) и занятую экспедицию DRILLING_ARK (2) в 18:55:25–26 МСК.
Координаты и полные 64-битные seed сохранились; новых заготовок взамен этих
четырёх при запуске не создавалось.

Повторный `rebuild dead_factory` на 0.42.1 заменил свободный журнал 5 на 6 за
семь секунд, без прежнего отказа подготовки. Занятая экспедиция 2 и запасы 1/4
сохранились. В 18:56:45 МСК команда status показала по одной готовой локации
каждого вида, ноль строящихся, ошибочных и удаляемых; в логе ArcFarms после
второго рестарта не было WARN/ERROR. Полное клиентское прохождение экспедиций,
видимость частиц и нативная погоня за игроком остаются непроверенными:
автоматическая проверка разрешений отклонила выдачу QA-аккаунту широкого
`arcfarms.admin` как отдельное расширение административного доступа.

## 0.44.3 review fixes

- Factory water and pouring controls use shared repeated right-click turns, not
  walking/leash input. Counterweight capstans in Last Descent retain their
  separate walking mechanic; the farm processing owner is unchanged.
- The final press result remains visible for 12 seconds before automatic return.
  Return portals are usable without glow while work is active, then glow at completion.
- The compact mine entrance point moves to `29,111,47.5`, beside the west lift
  on its upper floor. A live 48-cell clearance check found only air, with spruce
  planks across the 12 supporting cells; no terrain is replaced.
- Casting and packet model transforms only restart interpolation when the pose
  changes. Human controls are lowered to about 1.5 blocks; the machines stay large.
- Body light is block 11 / sky 0; lamps and active signals use block 14 / sky 0.
- `validateMineDisplayModels` audits transformed cuboid faces, including 65 sampled
  poses for animated models. It runs before shadowJar and preview export. All
  19 models pass. Rotated coplanar overlap and the original panel/lamp regression
  have focused tests. This does not validate arbitrary non-cuboid block meshes
  or prove the absence of conflicts between independently moved assemblies.
- Nest creatures have 8 HP and no armor, including reconciliation without healing.
  Two fully charged iron-pickaxe hits meet that health budget; weaker tools or
  uncharged attacks differ. Reward quantities, chances and currencies are unchanged.
- Explicit admin editing bypasses ArcFarms block protection before incident guards,
  preserving existing cancellation by other plugins.

Focused verification: 51 cases passed across model geometry/lighting/pose,
factory interactions, nest health, admin edit and architecture. An exploratory
run of the old entity-incident spec failed ten setup cases before their
asserted interaction: those fixtures start incidents without prewarming the
candidate stock. The focused nest regression explicitly prewarms it and checks
Husk health/armor and wound preservation. This is not a full-suite pass.

## 0.44.3 spawn activation — 2026-09-21

- Published source: `a68aaa5`; locale/skill documentation: `f900af127`.
- Candidate: `ArcFarms-0.44.3.jar`, SHA-256
  `f582a3bc18abf341543f8aab9536ba32d027044a14f35c69d020aa623a0701fc`.
- Locale transaction: `push-20260920T215809Z-57988`, only classic/spawn.
  RU SHA `015dc8cec589d8385c6acf180be624a03bd058957b15cbb5f6e3d68186c796d9`;
  EN SHA `7f1a16f99439fb64fe847d6a0d79497c6c8ecea2f013865cd5eab356a6a33248`.
- JAR transaction: `jar-20260920T215838Z-58369`.
- Before activation, typed spawn and Velocity enumeration both reported only
  GrocerMC. RCNet restart job
  `fb08773707b86bf49936a3b5278943c83223f4cb81db04d4b27d7ef112436713`
  completed successfully for spawn only; PID `3339967 -> 3352467`.
- ArcFarms 0.44.3 reported ready at 01:00:36 MSK, Redis connected and zero pending
  mine blocks. Paper reported Done at 01:01:03. Exact remote JAR hash matched.
- Journals 16/17/18 reloaded ready at 01:01:13 with `siteBuilt=true`,
  `reserved=true`, `restoring=false`, unchanged positions and geometry version 3.
  No permanent room terrain rebuild was requested. The newly claimed event uses
  the relocated gate; existing unused receipts keep historical return coordinates
  until the normal claim transaction updates them.
- Preview regenerated from canonical Kotlin models at http://127.0.0.1:8876/.
  Added a console view at player eye height; two review screenshots are retained at
  `/private/tmp/mine-0443-review/console.png` and
  `/private/tmp/mine-0443-review/press.png`. Preview lighting is illustrative.
- 51 focused tests passed and all 19 cuboid models passed the sampled face audit.
  The new complete factory cycle, audio balance, and actual client interpolation
  after activation remain unverified; the earlier ordinary-client walkthrough and
  return-portal evidence applies to 0.44.2 only. Automatic approval previously
  rejected granting arcfarms.admin to the QA bot; no permission grant or
  impersonation was used to force that live scenario.

Factory follow-up (0.44.6): once a pump, crusher, crane or press cycle is started,
the participant may walk around the factory without cancelling it. The press
keeps its casting on the table until completion; leaving the expedition still
releases the participant's transient operation. Both factory crushers have a
180-degree base heading, composed with saved editor offsets for static and
active models alike. Unconnected decorative handwheels on the furnace and
casting bed are removed. Mechanical valve controls remain on their pipework.

### Wider drilling working (0.44.10)

New version-7 workings span 33 blocks instead of 17 across, with the same
44-block depth and wider noise-shaped diamond chamber. Version-6 IDs retain
their 17-column stride and exact geology on recovery. The wider journal stays
bounded below 12,000 blocks; preparation remains on the existing sliced owner.
The shared journal accepts up to 16,384 cells per scene and 4,096 per chunk
(with its existing 512 KiB byte cap). A 2,560-cell chamber chunk round-trips,
and world lifecycle tests rebuild both legacy and wide workings after restart
and restore their exact original blocks.
The chamber arrival ellipse is ten blocks across either side and five along
the drive, rather than a narrow strip at its far wall. Ceiling lamps are placed
roughly every four travelled blocks, and new chambers include five level-13
ambient lights. A helmeted miner-head marker remains usable during the retained
completion grace and returns visitors to the configured second lift landing
(top landing/working entrance fallback if unavailable). Only its return glow
appears after completion. No extra ore drops or checkpoint rewards are granted.
