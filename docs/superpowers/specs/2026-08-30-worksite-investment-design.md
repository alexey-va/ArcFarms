# ArcFarms: промысловые компании и торговля акциями

Статус: канонический living design после уточнения владельца, обновлён
2026-08-31.
Следующие продуктовые решения обновляют этот файл на месте; новые параллельные
версии не создаются. Документ не разрешает production-изменения, списания,
выплаты, новые награды или миграцию старого stock market.

## Состояние реализации

Первый farm-only vertical slice реализован как выключенный по умолчанию контур
этапа B. `SHADOW` по-прежнему калибрует выручку, а dormant `LIVE`-ветка уже
содержит первичное финансирование и недельные расчёты:

- `WorksiteEnterpriseLedger` является общим platform-neutral ядром и принимает
  `ActivityKind`, company/worksite id, тариф, sequence и вклад работников;
- `WorksiteEnterpriseService` владеет единственным общим ledger;
  `FarmEnterpriseAdapter` переводит события начала, отмены и завершения
  фермерского заказа в общий контракт, а будущие адаптеры шахты и лесопилки
  подключаются к тому же владельцу;
- reservation, exact-once completion watermark, envelope, `20%` operating
  cost, `30%` post-cost worker bonus и недельные агрегаты сохраняются вместе с
  atomic ArcFarms state;
- reservation фиксирует тариф, финансовые условия и бизнес-неделю до показа
  заказа. Reload, переход недели и переименование компании не меняют уже
  принятое обязательство;
- startup/reload только сверяет существующие reservations с активными заказами
  и не создаёт коммерческую выручку задним числом для уже показанного заказа;
- admin completion и completion без финального вклада не создают shadow revenue;
- `WorksiteEnterpriseCapitalLedger` является вторым activity-neutral ядром:
  хранит funding escrow, именные доли, казну, недельные распределения,
  накопительный счёт игрока и журнал денежных операций;
- покупка сначала durable сохраняет `PREPARED`, только потом вызывает Vault и
  отдельной записью фиксирует результат; неоднозначный provider result и
  переживший рестарт `PREPARED` никогда автоматически не повторяются;
- полная подписка на `100` долей активирует двенадцатинедельную лицензию:
  `50%` капитала сгорают, `50%` попадают в казну; несобранный выпуск возвращает
  взносы на durable investment account независимо от онлайна игрока;
- закрытие недели начисляет worker bonus за фактически завершённые заказы и
  дивиденд владельцам record date; пустая неделя не печатает доход и всё равно
  сжигает содержание из казны;
- накопленные выплаты забираются отдельным exact-once Vault withdrawal; режим
  `OFF` прекращает новые продажи, но не прекращает закрытие уже возникших
  обязательств и не прячет счёт существующей компании;
- меню `Компании` показывает farm-only недельный отчёт, политику, остаток
  envelope, funding progress, владение, счёт, настраиваемые пакеты покупки и
  отдельное подтверждение списания; `Биржа` остаётся явно неактивна до этапа C;
- `SHADOW` не вызывает Vault, не платит работникам или акционерам и не меняет
  денежную массу; при открытии первого `LIVE` funding его калибровочные weeks,
  envelope usage и reservations очищаются и никогда не становятся выплатами.

Bundled-конфигурация остаётся `OFF`: каждый runtime явно выбирает режим. На
основном farm runtime включается `SHADOW`, relay-runtime остаются `OFF`, а
изолированный lab использует отдельные company/worksite id и малые тарифы.

Во всех tracked runtime-профилях реальные деньги по-прежнему выключены:
основная ферма и lab работают в `SHADOW`, остальные профили — в `OFF`.
Переключать `LIVE` нельзя до shadow-аудита экономики и появления операторской
процедуры разрешения `MANUAL_REVIEW`. Первичный рынок, доли и недельные claims
уже реализованы и протестированы локально, но вторичный рынок, голосования,
dormancy sale `90%` и обязательный buyback `50%` остаются этапом C.

Шахта, лесопилка и будущие промыслы должны добавлять собственный тонкий adapter
и настройки тарифа, не копируя ledger, недельную арифметику или будущие
ownership/auction lifecycle. До включения нескольких backend-писателей
ownership и биржа требуют transactional shared storage; текущий atomic local
state допустим только для одного authoritative farm runtime.

## Контракт live-конфигурации и reload

ArcFarms использует только собственный permission-gated reload lifecycle, без
глобального Bukkit/Paper `/reload`. Конфигурация предприятия, конфигурация GUI и
все locale-файлы собираются в одну изолированную immutable generation:

1. прочитать свежие файлы, не меняя текущий runtime;
2. нормализовать и проверить полный candidate, включая locale parity,
   placeholders, материалы, custom model data и финансовые ограничения;
3. классифицировать изменённые ключи как `live`, `runtime-rebuild` или
   `migration+restart`;
4. для `live` проверить runtime-зависимости, сверить enterprise state и
   подтвердить его durable-запись до публикации candidate на основном серверном
   потоке без остановки worksite tasks;
5. для `runtime-rebuild` сохранить snapshot, остановить старую runtime epoch,
   пересобрать граф и при ошибке восстановить прежнюю generation из snapshot;
6. после успешного config commit опубликовать подготовленные locale-каталоги и
   best-effort перерисовать открытые меню.

Подготовленные locale не публикуются до успешного config commit; обе публикации
происходят последовательно в одном main-thread вызове reload, поэтому gameplay
не видит промежуточное состояние. Ошибка чтения, валидации или подготовки
ресурса оставляет предыдущую generation активной. Ошибка после начала
`runtime-rebuild` запускает восстановление прежней generation из snapshot; если
и восстановление не удаётся, плагин останавливается. Если ошибка state write не
позволяет однозначно подтвердить durable результат live-перехода, candidate не
публикуется, а плагин fail-stop отключается до следующего чистого запуска. Reload
возвращает точную причину отклонения без секретов.

### Классы изменений

| Область | Класс | Когда начинает действовать |
|---|---|---|
| `mode`, `worksite-id`, тарифы order variants, license envelope, граница бизнес-недели | `live`, future orders | Только для заказа, у которого ещё нет reservation |
| Operating cost, worker bonus, dividend ratio, weekly upkeep и прочие недельные финансовые параметры | `live`, next unestablished week | Для следующей бизнес-недели, которая ещё не была зафиксирована первой reservation/weekly record |
| Пакеты кнопок `capital.purchase-options` | `live`, immediate presentation | После валидации; стоимость и ownership cap берутся из уже открытого выпуска |
| Total shares, share price, ownership cap, license burn/duration и reserve target | `live` только до открытия funding; затем immutable | После появления durable company reload с другими условиями отклоняется; нужен новый выпуск или явная миграция |
| Число хранимых недельных отчётов | `live`, immediate | Лишние закрытые отчёты pruning-ятся и durable сохраняются внутри того же publish transition |
| Начальная/максимальная задержка retry и частота логов для неоднозначной записи старта | `live`, immediate safety policy | Следующая попытка читает свежую политику; принятая смена и reservation остаются зафиксированы до подтверждённой durable-записи |
| Locale-тексты и тёплая ArcFarms-палитра | `live`, immediate presentation | Все открытые ArcFarms-меню перерисовываются из новой generation |
| Материалы domain-карточек/кнопок и custom model data | `live`, immediate presentation | После полной material/CMD validation и atomic swap |
| Зоны, world binding и topology worksite engine | `runtime-rebuild` | Только через validate → snapshot/flush → rebuild с rollback |
| `company-id` и durable namespace компании | `migration+restart` | Только отдельной проверенной миграцией и после рестарта |

`enterprise/UI-only` reload не останавливает и не пересоздаёт worksite engine,
не снимает слушатели, не отменяет смены и не дублирует scheduler tasks. При
`runtime-rebuild` candidate и перенос активного state полностью валидируются до
остановки текущей epoch; затем durable snapshot используется для rebuild. Если
rebuild не удаётся, ArcFarms пересобирает прежнюю generation из того же snapshot.
Если безопасный перенос смены невозможен уже на validation, candidate сразу
отклоняется, а текущий runtime продолжает работу.

Изменение `company-id` обычным reload запрещено: этот ключ входит в durable
operation ids, reservations, отчёты и будущий реестр акций. Reload возвращает
явную ошибку `migration+restart required`; молча создавать пустую новую компанию
или терять старый namespace нельзя.

### Неизменяемое принятое обязательство

Reservation в момент принятия сохраняет как минимум activity/company/worksite
identity, order sequence, business week, gross tariff, operating percentage,
worker percentage, corporate percentage и занятую часть license envelope.
Отдельный weekly fiscal snapshot хранит upkeep и прочие effective weekly terms.
После показа заказа эти snapshots неизменяемы:

- reload не пересчитывает gross или доли уже принятого заказа;
- смена `worksite-id` направляет только новые заказы и не освобождает старую
  reservation;
- `SHADOW → OFF` прекращает новые reservations, но уже принятые обязательства
  продолжают exact-once settlement;
- `OFF → SHADOW` не создаёт reservations задним числом для уже показанных
  заказов;
- смена тарифа, envelope или недельной границы относится только к будущим
  reservations;
- уже consumed/reserved envelope является senior usage: уменьшение нового
  лимита лишь даёт `available = max(0, limit - consumed - reserved)` для
  будущих заказов и не освобождает существующее обязательство;
- финансовые проценты и upkeep уже установленной бизнес-недели сохраняются до
  её закрытия; reload не смешивает две финансовые политики внутри недели.

Бизнес-неделя по умолчанию начинается в **воскресенье 20:00** в
`Europe/Moscow`. Zone, day и time валидируются как один boundary tuple. Новый
tuple определяет неделю только будущих reservations; сохранённый week id
принятого заказа остаётся прежним.

Уменьшение report retention сразу удаляет только закрытые отчёты старше нового
окна. Оно никогда не pruning-ит активные reservations, completion watermarks,
manual-review записи, investment claims или ownership audit. Удалённая
история не «воскресает» при последующем увеличении окна.

### Presentation ownership

Материал и `custom-model-data` каждой domain-карточки и кнопки задаются в
config, чтобы runtime мог выбирать проверенные ItemsAdder-модели без нового
JAR. Bundled defaults остаются vanilla-portable, а каждый runtime override
проверяется до atomic swap.

Текст, placeholders, тёплая harvest gold/green ArcFarms-палитра, semantic
success/warning/error roles и action footer остаются в locale/design layer.
Config не дублирует строки или hex-палитру. После успешного presentation reload
каждое уже открытое меню перерисовывает текущий экран и action map; stale click
из предыдущей generation является no-op, а не выполнением невидимого старого
действия.

## Решение

Заменить внешний фондовый рынок ARC тремя **промысловыми компаниями** ArcFarms:
фермой, лесопилкой и шахтой.

Эндгейм-игроки финансируют компанию, владеют её акциями, голосуют за недельную
политику и получают дивиденды. Обычные игроки выполняют тяжёлые заказы ArcFarms.
Каждый заранее принятый коммерческий заказ резервирует денежную выручку, поэтому
его нормальное завершение всегда оплачивается и работникам, и компании.

Доход компании зависит от реальной работы:

- нет завершённых заказов — нет операционной выручки;
- один завершённый заказ всегда даёт объявленную выручку;
- больше выполненных заказов — выше недельная прибыль и дивиденд;
- компания может повысить бонус работникам, напрямую уменьшив собственную
  прибыль;
- инвестор рискует капиталом, активностью работников, настройками компании и
  рыночной ценой акций.

Акции не привязаны к реальным компаниям, внешним котировкам, crypto, long,
short или leverage. Их цена образуется только из заявок игроков и фактических
результатов Minecraft-предприятия.

## Главный продуктовый контракт

### Работник

Работник заранее видит обычную награду ArcFarms и дополнительный бонус текущей
компании. После принятого completion seal обе суммы выдаются независимо от
онлайна владельцев, будущих продаж и биржевой цены.

### Акционер

Акционер получает не гарантированный процент, а долю распределяемой недельной
прибыли. Компания зарабатывает только на принятых и завершённых заказах. Чем
больше компания отдаёт работникам или оставляет в резерве, тем меньше текущий
дивиденд.

### Компания

Компания имеет собственные устав, казну, контрактный лимит, недельную политику,
историю отчётов, реестр акций и срок лицензии. Казна — durable liability, а не
новая валюта и не декоративное число.

### Экономика

Крупная лицензия, операционные расходы, еженедельное содержание и биржевые
комиссии сжигают деньги. Server-funded выручка ограничена на весь срок лицензии,
поэтому даже полностью загруженная компания остаётся net sink.

## Компания и лицензия

### Первичная капитализация

Стартовый shadow-кандидат для фермы:

- капитализация `C = 5 000 000`;
- первоначально `100` именных акций по `50 000`;
- максимум `20%` акций одной компании на одного игрока;
- funding длится семь дней;
- до полной капитализации деньги находятся в escrow;
- если капитал не собран, все взносы возвращаются exact-once;
- последняя покупка может немедленно запустить компанию, поэтому точка
  необратимости явно показана на подтверждении.

После успешной капитализации:

- `50% C` сгорают как первичная лицензия, строительство и оборудование;
- `50% C` остаются в корпоративной казне;
- акции начинают давать право на дивиденд, голос и ликвидационный остаток;
- общее число акций и владение фиксируются до первой разрешённой биржевой
  сессии.

Все числа — calibration candidates. Live капитал определяется только после
аудита кошельков, активности ArcFarms и shadow-модели дохода.

### Срок лицензии

Одна лицензия действует 12 бизнес-недель. Акции не исчезают автоматически в
конце срока: компания может продлить лицензию из казны и сохранить тот же
реестр владельцев.

Продление требует нового необратимого лицензионного платежа `L`. Для каждой
лицензии заранее фиксируется общий server-funded revenue envelope `M`, причём:

```text
M <= 0.80L
```

Если казны недостаточно, компания входит в `RECAPITALIZING`. В дальнейшей
версии существующие владельцы получают преимущественное право купить новый
выпуск, после чего остаток попадает на первичный аукцион. Новый выпуск размывает
процент старых владельцев и требует отдельного голосования и подтверждения.

Если продление не оплачено, компания переходит в ликвидацию: новые заказы не
создаются, завершённые обязательства рассчитываются, остаток казны делится по
акциям, сами акции погашаются. Деньги офлайн-владельцев сохраняются на их
инвестиционных счетах.

## Гарантированная выручка за работу

### Резервирование заказа

Коммерческий заказ получает `businessOrderId`, `businessWeekId`, объявленный
gross tariff `G` и reservation в контрактном envelope до показа игрокам.

```text
AVAILABLE
  -> RESERVED
  -> ACTIVE
  -> COMPLETED
  -> SETTLED

RESERVED/ACTIVE -> RELEASED
unknown money outcome -> MANUAL_REVIEW
```

Инварианты:

- ArcFarms не предлагает коммерческий заказ, если полный `G` не зарезервирован;
- после нормального завершения зарезервированный `G` начисляется всегда;
- отсутствие покупателей, владельцев онлайн или свободной биржевой ликвидности
  не влияет на расчёт;
- отказ, безопасный timeout или не начатый заказ освобождает reservation;
- admin force, дубликат completion, idle presence и recovery replay не создают
  выручку;
- исчерпание недельного лимита прекращает выдачу новых коммерческих заказов, но
  обычные смены ArcFarms продолжаются с обычной наградой.

Так низкий онлайн снижает число заказов и общий доход, но никогда не превращает
уже выполненную сложную смену в неоплаченный товар.

### Формирование gross tariff

`G` фиксируется при reservation из конфигурации полного order variant. Он может
учитывать сложность, объём и заранее известный grade range, но не текущий баланс
игрока, цену акции или внезапно изменившийся онлайн.

Фактическое качество выполнения может выбрать один из заранее показанных
результатов внутри диапазона заказа. Оно не может снизить обычную награду
ArcFarms и не меняется после completion.

Один completion seal создаёт одну settlement-запись. Денежная награда текущего
ArcFarms остаётся отдельной и не считается частью корпоративной прибыли.

## Экономика одного заказа

Каждый подтверждённый gross tariff `G` делится в момент settlement:

1. `20% G` — операционные расходы, немедленный burn;
2. оставшиеся `80% G` — post-cost margin;
3. из post-cost margin выплачивается выбранный компанией worker bonus;
4. остаток зачисляется в корпоративную казну как retained profit.

Стартовые варианты worker bonus:

| Настройка | Работникам от post-cost margin | Компании от post-cost margin |
|---|---:|---:|
| Бережём прибыль | 10% | 90% |
| Баланс | 30% | 70% |
| Сильная артель | 50% | 50% |

Worker bonus делится между accepted contributors заказа по нормализованному
вкладу. Он идёт поверх неизменённой обычной награды ArcFarms. Повышение bonus
не умножает `G`: каждый дополнительный рубль работникам является рублём,
который компания не получила в казну.

Пример при `G = 10 000` и политике `Баланс`:

- `2 000` сгорают как операционные расходы;
- `2 400` получают работники;
- `5 600` получает компания.

Изменение worker bonus с 30% на 50% даст работникам ещё `1 600`, а компания
получит на `1 600` меньше. GUI показывает этот counterfactual по фактическим
заказам прошлой недели до голосования.

## Почему компания остаётся sink

Для одной 12-недельной лицензии:

- `L` — сожжённая стоимость лицензии;
- `M` — максимальная суммарная server-funded gross revenue;
- `M <= 0.80L`;
- минимум 20% каждого `G` дополнительно сгорает.

Даже если весь envelope использован:

```text
incremental_delta <= -L + 0.80M
                  <= -0.36L
```

Worker bonus и retained profit уже входят в оставшиеся 80% и не добавляются
сверху. Недельное содержание и биржевые комиссии дают дополнительный burn.

При стартовом `C = 5 000 000`:

- первичная лицензия `L = 2 500 000`;
- казна получает `2 500 000`;
- максимальный gross envelope на 12 недель — `2 000 000`;
- операционный burn при полном использовании — минимум `400 000`;
- работники и компания вместе получают из новой выручки не более `1 600 000`;
- чистое сокращение денежной массы — минимум `900 000` плюс содержание и
  биржевые комиссии.

Если игроков мало, envelope используется не полностью и sink становится
больше. Выплата отдельного завершённого заказа от этого не уменьшается.

Дивиденды классифицируются как passive income. Shadow-калибровка обязана
доказать, что они не выводят общий сетевой passive share выше Economy V2
предела `30%`. Достижение порога не конфискует уже начисленные деньги и не
меняет live тариф автоматически; оно блокирует следующий rollout/renewal до
ручного решения по бюджету.

## Недельная политика бизнеса

Политика фиксируется на полную бизнес-неделю и не может измениться после того,
как работник увидел и принял заказ.

### Управляемые параметры

1. **Бонус работникам:** `10%`, `30%` или `50%` post-cost margin.
2. **Доля дивидендов:** `25%`, `50%` или `75%` распределяемой недельной
   прибыли.
3. **Целевой резерв:** одна, две или четыре недели максимальных обязательств.
4. **План заказов:** один заранее собранный worksite-specific набор риска и
   тарифов; для MVP план фиксирован, затем становится голосуемым.

Порядок недельной прибыли:

```text
retained order profit
  - fixed weekly upkeep burn
  - confirmed losses/manual adjustments
  = weekly corporate result

positive result
  -> top up target reserve
  -> apply dividend ratio to remaining distributable profit
  -> keep undistributed remainder in treasury

negative result
  -> cover from treasury
  -> dividend = 0
```

Нельзя занимать деньги у сервера, создавать отрицательную казну или выплачивать
дивиденд из неразрешённой/неподтверждённой операции.

### Как меняются настройки

- владелец не менее 5% акций может подать один policy proposal на следующую
  неделю;
- proposal содержит весь tuple настроек, а не отдельные несвязанные ползунки;
- одновременно участвуют текущая политика и не более двух новых proposals;
- одна акция даёт один голос;
- максимум 20% на игрока ограничивает единоличное управление;
- dormant-акции не входят в quorum, но сохраняют денежные права;
- если quorum или победитель отсутствует, текущая политика сохраняется;
- результат фиксируется до начала новой недели и показывается работникам до
  принятия заказа.

Это создаёт реальную игру между ростом активности, текущими дивидендами и
устойчивостью казны. Система не предполагает, что высокий worker bonus
автоматически привлечёт людей: эффект измеряется по completed orders и unique
contributors следующей недели.

## Недельный расчёт и дивиденды

Бизнес-неделя закрывается, а следующая начинается в воскресенье 20:00 по зоне
`Europe/Moscow`.

Порядок settlement:

1. закрыть week id для новых reservations;
2. включить только terminal completed order settlements;
3. перенести незавершённые reservations вместе с их stable ids;
4. списать weekly upkeep;
5. рассчитать corporate result и reserve target;
6. зафиксировать record-date реестр акций;
7. вычислить dividend pool и dividend per share в minor units;
8. durable-credit каждого владельца на личный инвестиционный счёт;
9. rounding remainder отправить в burn;
10. опубликовать недельный отчёт;
11. применить policy следующей недели.

Дивиденд начисляется раз в неделю независимо от онлайна владельца. Не нужен
ежедневный click или вход ровно в момент settlement.

Личный инвестиционный счёт — durable claim ledger. Деньги не лежат в казне
после начисления и не блокируют следующий цикл. Игрок может вывести их в Vault
при входе или оставить для биржевого escrow. Неизвестный результат Vault
withdraw/deposit входит в `MANUAL_REVIEW` и не повторяется новым operation id.

Недельный отчёт показывает:

- завершённые коммерческие заказы и unique workers;
- gross revenue;
- operating burn;
- worker bonus;
- retained profit;
- upkeep;
- казну до и после;
- dividend pool и dividend per share;
- использованный и оставшийся license envelope;
- политику следующей недели.

## Что происходит с владельцем офлайн

Права собственности не должны зависеть от присутствия в момент выплаты.

### Обычный офлайн

- акции остаются в реестре;
- дивиденды каждую неделю начисляются на инвестиционный счёт;
- деньги и liquidation proceeds не истекают;
- открытые биржевые заявки исполняются только до указанного игроком срока;
- standing-заявка никогда не продлевается бесконечно без явной настройки.

### Dividend instruction

Пока игрок активен, он может выбрать политику для будущих дивидендов:

- `На счёт` — безопасный вариант по умолчанию;
- `В казну` — добровольное пожертвование компании;
- `Работникам` — добровольный вклад в worker bonus reserve;
- `На покупку` — создать ограниченную buy-заявку не выше выбранной цены.

Последние три варианта только opt-in. Система не сжигает и не раздаёт деньги
отсутствующего игрока без заранее данного указания.

### Dormancy

`lastSeen` берётся из авторитетного network-wide источника, а не из локального
Paper-кэша.

- после 30 дней без входа акция становится governance-dormant: она не входит в
  quorum и не голосует, но получает полный дивиденд;
- после 90 дней весь пакет входит в двухступенчатый dormant exit;
- владелец получает дивиденд до фактического перехода каждой акции;
- вход на любой сервер сети отменяет ещё не исполненную автоматическую продажу
  или buyback, но не откатывает уже рассчитанную сделку.

### Reference price

Для dormant exit один раз на начало процедуры фиксируется reference price `R`:

```text
R = max(
  book value per outstanding share,
  median of up to four latest non-zero clearing prices,
  primary issue price when clearing history is empty
)
```

Book value использует подтверждённую казну за вычетом senior liabilities. Цена
не пересчитывается посреди уже начатой процедуры, поэтому вход, новый дивиденд
или чужая заявка не могут изменить условия задним числом.

### Ступень 1 — защищённая продажа

В ближайший недельный call auction система создаёт от имени dormant-владельца
sell-заявку по `90% R`:

- пакет можно исполнить частично;
- проданная часть рассчитывается как обычная сделка с seller fee;
- деньги exact-once поступают на инвестиционный счёт владельца;
- непроданная часть после clearing переходит в `BUYBACK_PENDING`;
- перед принудительным выкупом действует одна полная неделя grace period;
- вход игрока в grace period отменяет buyback оставшейся части.

### Ступень 2 — выкуп компанией

Если пакет не продался по `90% R` и владелец не вернулся за grace period,
компания обязана выкупить оставшиеся акции по `50% R`.

- полная сумма buyback резервируется до смены владельца;
- деньги зачисляются на инвестиционный счёт и не истекают;
- только после durable cash credit акции переходят компании;
- операция имеет один stable id и не повторяется при reload или неизвестном
  provider outcome;
- выполненный buyback нельзя отменить последующим входом игрока;
- точные сроки, `90% R` и `50% R` видны до первоначальной покупки акции и в
  личном investment status.

Скидка является явно принятой ценой длительного отсутствия, а не скрытым burn:
игрок получает деньги, компания получает актив, и денежная масса не меняется.
Это создаёт повод возвращаться, но не уничтожает накопленные дивиденды.

### Dormancy liquidity reserve

До обычных дивидендов компания поддерживает отдельный senior reserve-кандидат
в размере `10%` reference company value, где `reference company value = R ×
issued shares`. При максимальном ownership `20%` этого хватает на выкуп одного
максимального dormant-пакета по `50% R` за неделю.

- reserve нельзя направить в dividend, worker bonus или renewal;
- buyback-пакеты обрабатываются FIFO по моменту `BUYBACK_PENDING`;
- владение не меняется, пока полная сумма конкретного пакета не обеспечена;
- очередь не может ждать бесконечно: четыре нерассчитанные недели переводят
  компанию в `RECAPITALIZING` или `LIQUIDATING`;
- ликвидация сохраняет все buyback claims и dividend credits как senior
  liabilities до распределения остатка.

### Казначейские акции

Выкупленные акции становятся treasury shares:

- они не получают dividend, не голосуют и не входят в quorum;
- они остаются частью issued supply, поэтому ownership cap активных игроков не
  меняется только из-за buyback;
- компания выставляет их на следующих call auctions не дешевле большей из
  buyback cost и `50%` актуального reference price;
- выручка сначала восстанавливает dormancy liquidity reserve, затем поступает
  в обычную казну;
- последующая продажа использует обычный escrow, clearing и exchange fee.

Даже если treasury shares временно не покупают, бывший офлайн-владелец уже
получил durable cash, пакет не блокирует голосование, а компания может вернуть
его активным игрокам при появлении спроса.

## Биржа акций

При небольшом онлайне непрерывный order book будет пустым и легко
манипулируемым. Поэтому основной рынок — **недельный единый аукцион**.

### Ритм

- воскресенье 20:00 — record date, дивиденд и недельный отчёт;
- до понедельника 20:00 — окно реакции на отчёт и изменения заявок;
- понедельник 20:00 — единый clearing;
- покупатель получает право на результаты новой недели;
- seller record-date получает уже объявленный воскресный дивиденд.

### Заявки

- только limit buy и limit sell;
- одна акция — минимальный lot;
- buy-заявка блокирует полную максимальную сумму и комиссию в escrow;
- sell-заявка блокирует акции, но не уже начисленные деньги;
- заявка имеет точный expiry: текущий clearing или один добровольный rollover;
- partial fill разрешён;
- неиспользованные деньги и акции возвращаются exact-once;
- self-match одного UUID запрещён;
- short, margin, leverage, market order и отрицательный баланс запрещены.

### Clearing

Один clearing price выбирается детерминированно:

1. максимизировать число исполненных акций;
2. при равенстве минимизировать дисбаланс buy/sell;
3. затем выбрать цену, ближайшую к предыдущему clearing;
4. на самой границе распределить partial fills пропорционально заявленным
   объёмам, остаток — по stable order id.

Все сделки одной компании в этом clearing исполняются по одной цене. Излишне
заблокированная сумма покупателя возвращается.

### Комиссии и защита рынка

Стартовый кандидат — `2%` с покупателя и `2%` с продавца, полностью в burn.
Комиссия показывается до подтверждения и взимается только с фактически
исполненной части.

Exchange audit отслеживает концентрацию, self-cross attempts, связанные
заявки, резкие отклонения цены, долю одного игрока и wash-like volume. Сырые
аномалии не блокируют цену автоматически; они создают read-only review.

### Почему рынок интересен

Цена связана с понятными игровыми сигналами:

- сколько заказов выполнили работники;
- какой worker bonus выбрали владельцы;
- сколько осталось в казне и license envelope;
- дивиденд на акцию;
- риск продления лицензии;
- активность конкретного промысла.

Игрок торгует не абстрактной свечой Apple, а ожиданием того, смогут ли люди
следующую неделю реально работать на ферме, лесопилке или шахте.

## Три промысловые компании

| Компания | Основание выручки | Интерактивная политика | Отличающий риск |
|---|---|---|---|
| Ферма | завершённые crop orders, уход, инциденты и погрузка | бонус работникам и выбор надёжных/сложных заказов | трудоёмкость заказа и число вернувшихся работников |
| Лесопилка | полный цикл валки, трелёвки, распила и отгрузки | премия за rush и резерв на сложные партии | скорость команды и срыв срочной отгрузки |
| Шахта | разведка, добыча, погрузка и извлечение | глубина/риск заказа и премия экспедиции | recoverable hazards и качество завершения |

Ферма — единственный MVP. Лесопилка и шахта подключаются после доказанного
денежного и ownership settlement, сохраняя собственные verbs и state flows.

## Игровые поверхности

Пункт `Инвестиции` меняется на `Компании`. Основной вход — физическая контора
или доска на worksite; меню ArcFarms даёт общий сетевой обзор.

### Карточка компании

Scan path:

1. компания и текущая политика;
2. заказов завершено на этой неделе;
3. worker bonus;
4. казна и оставшийся license envelope;
5. ожидаемый не гарантированный dividend per share по уже закрытым операциям;
6. последняя clearing price и текущий indicative clearing;
7. одно действие: открыть компанию.

### Внутри компании

Отдельные focused-секции:

- `Отчёт недели`;
- `Работникам` — текущий bonus и прошлый эффект;
- `Политика` — proposals и голос;
- `Акции` — владение, book value и дивиденды;
- `Биржа` — агрегированные заявки и следующий clearing;
- `Мой счёт` — cash, escrow, orders и dividend instruction;
- `Лицензия` — срок, renewal cost и liquidation risk.

Не сваливать всё в один tooltip. Любая покупка акции, proposal, vote, donation,
withdraw и order submission использует отдельное подтверждение с точной суммой,
комиссией, сроком, escrow, partial-fill правилом и необратимым эффектом.

Не показывать projected dividend как обещание. Использовать `начислено по уже
закрытым заказам`, а не `вы получите`.

Все item names и lore roots, включая empty, disabled, dormant, partial,
expired, insufficient funds, ownership cap, stale click и manual review,
явно отключают italics. Реализация требует verified runtime background и
полного render review.

## Владение и durable accounting

Акция — запись реестра, не предмет и не bearer token. Её нельзя выбросить,
дюпнуть, положить в сундук или передать командой.

Минимальные durable owners:

- `EnterpriseCharter`: license state, envelope и renewal/liquidation;
- `EnterpriseOrderRevenue`: order reservation и settlement;
- `EnterpriseTreasury`: confirmed balance, upkeep и retained profit;
- `EnterprisePolicy`: proposals, quorum, vote и effective week;
- `EnterpriseShareRegistry`: issuance, ownership, escrow и record date;
- `EnterpriseWeeklySettlement`: report, dividend pool и per-share credits;
- `EnterpriseInvestmentAccount`: offline cash, claims и instructions;
- `EnterpriseCallAuction`: orders, clearing, fills, fees и returns;
- `EnterpriseDormancy`: last-seen projection, protected sale и grace period;
- `EnterpriseDormantBuyback`: liquidity reserve, buyback и treasury shares;
- `EnterprisePresentation`: locale/menu projections;
- `EnterpriseScene`: видимое состояние конторы и производства.

Эти lifecycle не добавляются картами, таймерами или очередями в
`ArcFarmsService`, `FarmComponentGraph` или существующий worksite controller.
ArcFarms подключает один narrow production-completion port после accepted
completion seal.

Каждый Vault side effect имеет полный durable intent и stable operation id до
вызова provider. Vault и фактический Economy provider — отдельные runtime
dependencies. Unknown outcome не повторяется новым id и входит в
`MANUAL_REVIEW`.

Ключевые operation ids:

- `company:funding:player:sequence`;
- `business-order:reservation`;
- `business-order:settlement`;
- `week:shareholder:dividend`;
- `auction:order`;
- `auction:fill:buy-order:sell-order`;
- `auction:order:return`;
- `company:dormancy:player:reference-snapshot`;
- `company:dormancy:player:protected-sale`;
- `company:dormancy:player:buyback`;
- `company:treasury-share:sale-sequence`;
- `company:shareholder:liquidation`.

Read-only QA публикует только bounded aggregates: company state, license
remaining, envelope reserved/settled, treasury, weekly revenue, worker bonus,
dividend pool, shares issued/escrowed/dormant/treasury, auction orders/fills,
buyback reserve/queue, pending claims и manual-review count. UUID, имена и
provider payload не выводятся.

## Закрытие старого рынка ARC

Старый stock module нельзя просто выключить, пока у игроков есть позиции или
trading balance.

1. Снять live aggregate stock audit и redeemable liability.
2. Ввести `CLOSING_ONLY`: запретить новые long/short и дивиденды, оставить
   обновление валидных цен, закрытие и вывод в Vault.
3. Провести объявленное окно расчёта и зафиксировать final price snapshot.
4. Закрыть оставшиеся счета через durable migration journal exact-once.
5. Отрицательное equity не превращать в долг игрока; неоднозначные счета — в
   manual review.
6. Удалять Redis stock data и код только при нулевых позициях, balance,
   redeemable liability и manual-review.
7. После расчёта перенаправить `/stocks` и `/invest` в компании ArcFarms.

## MVP и rollout

### A. Observe и shadow

Четырнадцать дней на реальных фермерских completion считать без денег:

- eligible commercial orders;
- gross tariff и envelope utilization;
- orders per week и unique workers;
- worker payouts при политиках 10/30/50%;
- company retained profit;
- dividends per share при политиках 25/50/75%;
- initial/renewal sink и incremental mint;
- концентрацию ownership и payouts;
- hypothetical call-auction liquidity.

### B. Недельная фермерская компания без биржи

Включить один ограниченный ownership cohort, guaranteed reserved revenue,
treasury и weekly dividend. Обычную награду ArcFarms не менять. Акции не
торгуются; доказать completion-to-revenue, policy trade-off, offline credit,
crash recovery и manual review.

### C. Недельный call auction

После чистого settlement включить limit orders, escrow, единый clearing и 2% +
2% fee. Сначала одна фермерская акция/компания, без recapitalization и
автоматического dormant auction.

### D. Dormancy и renewal

После нескольких чистых недель включить governance dormancy, protected
sale `90% R`, company buyback `50% R`, treasury-share resale и одну процедуру
renewal/liquidation. Затем принимать решение `keep/change/rollback/stop`.

### Expansion

Только `keep` открывает лесопилку, затем шахту отдельными vertical slices.

## Acceptance

Первый production-кандидат принимается только если:

- каждый показанный коммерческий заказ имеет durable full-tariff reservation;
- каждый normal completion получает один settlement, даже при нуле покупателей
  и владельцев онлайн;
- без completion нет company revenue;
- изменение worker bonus на `+X` уменьшает company retained profit ровно на
  тот же `X`;
- ordinary ArcFarms reward не уменьшилась и не стала новым multiplier;
- server gross envelope не превышает `0.80L`;
- incremental feature delta остаётся sink не менее `0.36L` до upkeep/fees;
- shareholder dividends учтены в passive income и не выводят его выше 30%;
- недельный dividend pool совпадает с policy и record-date shares до minor unit;
- офлайн-владелец получает durable weekly credit;
- protected dormant sale использует ровно `90% R` и одну grace week;
- company buyback использует ровно `50% R` и не меняет ownership до полного
  durable cash credit;
- treasury shares не голосуют, не получают dividend и не меняют issued supply;
- нерассчитанная buyback queue имеет четырёхнедельный terminal fallback;
- dormant policy не сжигает деньги или уже начисленные дивиденды;
- call auction имеет один deterministic price, exact escrows и returns;
- один игрок не превышает 20% ownership;
- текущая политика видна работнику до принятия заказа;
- GUI показывает risk, fees, expiry, record date и отсутствие гарантии;
- невалидный reload не меняет ни config, ни locale, ни работающий runtime;
- enterprise/UI-only reload не teardown-ит worksite и не теряет активную
  reservation;
- повторные reload не создают вторые listeners, tasks или settlement;
- открытые меню после успешного presentation reload показывают одну новую
  generation, а stale click остаётся no-op;
- `company-id` отклоняется как `migration+restart`, пока отдельная durable
  migration не доказана;
- изменение тарифов, envelope, границы недели и недельных финансовых условий
  проходит tests на future-only/next-week semantics;
- уменьшение report retention immediately pruning-ит только разрешённую
  закрытую историю и durable сохраняет результат;
- ledger coverage не ниже Economy V2 gate и все unknown outcomes находятся в
  manual review.

## Не входит в первую версию

- реальные компании, внешние цены и crypto;
- long, short, leverage, margin и market orders;
- бессрочно гарантированный процент;
- скрытое уменьшение обычной награды работника;
- dividend за незавершённый заказ;
- скрытый burn денег или начисленных дивидендов отсутствующего игрока;
- физические акции-предметы;
- автоматические price controls по сырому threshold;
- одновременный запуск трёх компаний;
- recapitalization до доказанного weekly settlement и call auction;
- удаление старых stock accounts до полного расчёта.
