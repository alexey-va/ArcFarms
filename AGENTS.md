# AGENTS.md — ArcFarms

Standalone Kotlin/Paper plugin for the three shared RusCrafting worksite
activities: farm, lumbermill, and mine.

- Target Purpur/Paper 1.21.11, WorldGuard 7.0.16, Java 25, and Kotlin 2.3.0.
- WorldGuard is a soft dependency: require it only when a configured zone uses
  a named region. Relay-only and explicit-cuboid nodes must load without it.
- Use `arc-core`, `arc-core-paper`, and `arc-core-redis` through the sibling
  composite build. ArcFarms owns its Redis profile and protocol; do not import
  ARC configuration or add ARC API/path compatibility.
- Keep shift state machines and persistence DTOs independent of Bukkit.
- Use `Tasks.scheduler`; never schedule gameplay directly through Bukkit.
  `ArcFarmsService` callbacks additionally belong to `RuntimeTaskSupervisor`:
  activate its epoch before startup/reload reconciliation, invalidate it before
  replacing runtime state, and capture its token before every asynchronous
  completion that later re-enters the Paper thread. Direct `Tasks.scheduler`
  calls in the service are forbidden.
- `ArcFarmsService.kt` is an orchestration boundary under active decomposition,
  not a home for new cohesive subsystems. Keep it at or below 8,400 lines and
  reduce that ceiling as code moves out; new stateful features, codecs,
  transactions, entity lifecycles, and recovery queues need a focused owner
  with unit tests instead of more service-local maps and helpers.
- New worksite types implement `WorksiteModule`, use `WorksiteRuntimePort`, and
  register through `WorksiteModuleRegistry`. A controller owns all runtime
  state, validation, event routing, guidance, recovery, and phase application
  for its activity; `ArcFarmsService` must not mirror those collections.
- Paper-only operations that a test double cannot model belong behind a narrow
  injectable seam such as `MineBlockEffects`. Keep the production adapter and
  test fixture behavior separate instead of weakening world logic for a mock.
- `RuntimeTaskSupervisor` is activity-neutral. Capture its token before every
  journal/database future and reject stale completions after reload. Durable
  recovery intent is written before the world mutation, and a rejected stale
  callback retires intent only when it has not mutated the world.
- Farm, lumbermill, and mine must have different player verbs and phase flows.
- A farm shift has one foreground objective. Resolving an incident resumes the
  ordinary crop order directly; do not insert harvest multipliers or parallel
  crop bonus windows between the incident and the next required crop.
- Farm orders are complete contract variants: rarity, crop quota, permitted
  care and incident pools, customer identity, and cart-load visual belong to
  the order. The active order name belongs on the farm scoreboard; keep boss
  bars compact and limited to the immediate objective. Harvest progress fills
  one four-step cart visual, while the customer and cart are tagged scene
  entities reconstructed from shift state. The cart itself is a configurable,
  non-persistent `ItemDisplay` with a separate non-persistent `Interaction`
  hitbox; never use a `Minecart` or another ticking collision vehicle for this
  decoration. Keep portable vanilla defaults in the bundled config and apply
  the verified ItemsAdder material/custom-model-data override only in the
  owning runtime config.
- Farm completion rewards may use independently-chanced experience, Vault
  money, ordinary items, weighted item bundles, and bounded console commands.
  Persist each resolved grant before delivery and claim it durably before side
  effects so a relog or restart cannot reroll or duplicate it. Farm crops
  accepted by an order are consumed by that order and never drop; lumber and
  mine resources keep their existing material outcomes.
- Reward pending/claimed state belongs to `FarmRewardLedger`. Its claim mutation
  and persistence callback are one rollback-safe operation; never mutate the
  persisted reward collections directly in the service.
- Mine block replacement is journaled before mutation and must converge after
  restart without duplicate drops or permanent temporary blocks.
- All player text belongs in `lang/ru.yml` and `lang/en.yml`; keys stay equal
  and dynamic player/config values use non-parsing Adventure placeholders.
- Keep the three runtime locale copies synchronized through the `arcfarms`
  translation profile. Chat may use the locale prefix; titles, action bars,
  boss bars, entity names, and inventory titles must not.
- Every gameplay title uses its subtitle for the next action or supporting
  detail; never concatenate title and subtitle with a bullet separator.
- Farm supply points are free-floating item and text displays with an
  interaction hitbox. Do not add a barrel/base block or a visible custom name,
  and keep configured points outside selectable crop beds.
- Farm preparation rotates between spatially distinct same-height beds. It may
  bridge a one-block irrigation channel and expand a whole bed only up to the
  configured hard cap; active recovery may expand progress but never reset it.
- Clear farm recovery entries only after the corresponding world repair was
  confirmed. Patch restoration keeps its block ledger until the cleared state
  is durably saved; unloaded or failed plots remain pending for a later retry.
- Chunk-PDC recovery codecs must reject oversized, trailing, duplicate, or
  structurally invalid records. A world-repair journal is removed only for the
  exact records whose original `BlockData` was decoded and restored successfully.
- Persistent farm topology is owned by the chunk-PDC block index, not by a
  service-local cache. Rebuild it only through the bounded two-phase admin
  reindex: load chunks asynchronously, hold and release exact plugin tickets,
  scan a configured block budget per tick, validate again before applying, and
  replace one chunk at a time. Indexed farmland is kept fully moist; any bed
  covered by a non-plantable block is removed from event selection.
- Orchard care uses reindexed open leaf anchors and non-persistent
  `ItemDisplay`/`Interaction` pairs. Keep the visible placement count separate
  from the smaller any-target completion quota, batch entity creation, and keep
  spacing, display scale, independent display/hitbox Y offsets, and leaf-index
  bounds configurable. Degrade safely when an orchard has fewer valid anchors.
- Durable reindex data is the authoritative candidate pool for preparation and
  incidents. A bounded scan around the current player may supplement and
  revalidate that pool, but must never replace the full loaded index with its
  local result.
- A forced admin bed-care stage must select a fresh full-sized patch instead of
  reusing stale saved coordinates. Startup recovery expands a zero-progress
  legacy mechanized patch without discarding its already-managed plots; reindex
  is not a workaround for active-stage selection bugs.
- Night patrol illumination uses temporary `LIGHT` blocks, not particles.
  Record each placed light in its chunk before mutation and remove it on patrol
  movement, incident cleanup, chunk reconciliation, reload, and shutdown.
- Scale night patrol count from the indexed usable bed count within configured
  minimum/maximum bounds. Patrol movement uses Paper pathfinding and walkable
  in-region waypoints; never position-correct a live patrol by teleporting it.
- Register move, teleport, and portal cleanup handlers separately: Paper gives
  these event classes distinct handler lists despite their class inheritance.
- Farm service items may move inside the player's own inventory, but must never
  enter a crafting grid or external inventory and must be removed on every
  farm/server exit path.
- Runtime state belongs under `plugins/ArcFarms/data/` and is never tracked or
  deployed as configuration.
- Network workday seals are persistent and deadline-free. Redis loss may
  temporarily degrade relays, but must never disable or reset local activities.
- Cross-server travel is configured as an exact server, world, and location.
  Redis owns the short-lived handoff ticket; Paper uses the BungeeCord plugin
  messaging channel only for the backend switch.
- Player-facing network announcements are disabled by default.
- Build and test with `../arc-core/gradlew -p . clean check shadowJar`.
