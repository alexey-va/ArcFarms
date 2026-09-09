# ArcFarms target architecture

Status: architecture baseline implemented, 2026-08-26.

## Goal

ArcFarms must be easy to extend with another worksite or farm story without
reading or modifying a monolithic service. A feature has one discoverable
owner, that owner contains its mutable runtime state and full lifecycle, and a
small focused test suite proves the feature across reload and restart.

The current `ArcFarmsService` is a compatibility/application facade. Moving its
methods into another large class is not a valid decomposition.

## Dependency direction

```text
ArcFarmsPlugin
  -> ArcFarmsService (thin compatibility/application facade)
      -> FarmComponentGraph (composition only)
      -> FarmModule (farm lifecycle coordinator)
      -> WorksiteEnterpriseService (one shared enterprise ledger and adapters)
      -> WorksiteModuleRegistry (lumbermill and mine)
      -> ActivityTravelService
      -> state persistence lifecycle
```

The longer-term platform-neutral direction remains:

```text
ArcFarmsApplication (start/reload/close and public queries)
      -> WorksiteRegistry
          -> FarmModule
          -> LumbermillModule
          -> MineModule
      -> NetworkModule
      -> PersistenceCoordinator

worksite modules -> narrow application/platform ports
Paper adapters   -> Bukkit/Paper/WorldGuard/Vault/Redis
domain engines   -> Kotlin data and pure rules only
```

Dependencies point inward. Domain code never imports Bukkit. A worksite may use
typed ports but may not call another concrete worksite. Paper listeners and
commands only validate/route input; they do not contain gameplay rules.

## Top-level owners

| Owner | Responsibility | Explicitly does not own |
|---|---|---|
| `ArcFarmsPlugin` | Construct adapters and the application, register listeners and commands | gameplay, recovery, entity state |
| `ArcFarmsApplication` | Global lifecycle, reload transaction, cross-worksite queries | farm phases or Paper event logic |
| `WorksiteRegistry` | Route typed capabilities to worksite modules | concrete worksite state |
| `PersistenceCoordinator` | Build immutable snapshots, coalesce writes, lifecycle-token stale callbacks | world mutation |
| `FarmRuntimeRegistry` | The one active farm-zone index and its lookups | gameplay and world mutation |
| `FarmModule` | `WorksiteModule` lifecycle and coordination of farm features | implementation details of care, incidents, delivery, UI, or admin |
| `LumbermillModule` | Complete lumbermill lifecycle | farm/mine state |
| `MineModule` | Complete mine lifecycle and its recovery journal | farm/lumber state |

`ArcFarmsService` is a compatibility facade of at most 600 lines and owns
no mutable gameplay collection, `NamespacedKey`, spawned entity, recovery queue,
or domain state-machine call.

`FarmComponentGraph` is the only farm wiring file. It may construct and expose
typed feature owners, but architecture tests forbid listeners, ticks, spawning,
world mutation and state-machine calls there. It must never become a service
locator passed into gameplay code.

## Worksite contract

Every worksite implements a common lifecycle and only the typed event
capabilities it needs:

```kotlin
interface WorksiteModule<S> : RuntimeComponent {
    val kind: ActivityKind
    fun rebuild(config: ArcFarmsConfig, persisted: Map<String, S>)
    fun states(): Map<String, S>
    fun statuses(): List<ActivityStatus>
    fun tick(now: Long)
    fun canAccess(player: Player): Boolean
}

interface RuntimeComponent {
    fun reconcileLoaded()
    fun beforeReload(reason: String)
    fun close(reason: String)
}
```

Event routing stays capability-based (`BlockBreak`, `BlockInteract`, `Move`,
`EntityInteract`, `EntityDamage`, `ChunkLoad`, player boundary, inventory). Do
not introduce one untyped universal event bus or a `when` over every Bukkit
event in every module.

Worksite infrastructure is split into narrow capability ports:

- `WorksiteAudiencePort`: localized chat, action bar, title, boss bar, particles,
  sound;
- `WorksiteAccessPort`: permission and region access;
- `WorksiteStatePort`: asynchronous gameplay persistence requests, lifecycle-only
  blocking flushes and immutable snapshot contribution;
- `WorksiteNetworkPort`: bounded cross-server signals;
- `WorksiteTaskPort`: epoch/token-aware sync scheduling;
- `WorksiteStatsPort`: contributions and completion.

`PaperWorksiteAdapter` implements all six capabilities, while the wiring-only
`WorksitePorts` bundle is restricted to composition roots. Gameplay owners
receive only the ports they actually call; there is no production compatibility
composite.

`WorksiteCarryable` owns the shared geometry for display-backed portable
objects: front-of-player positioning, proximity selection and entity hitbox
reach. Farm crates, scarecrows and processing cargo use it directly, and lumber
display carriers use the same pose contract. Feature controllers still own
leases, state transitions, permissions and cleanup.

A feature constructor should normally depend on no more than five typed ports.
Clock and random sources are injectable values, not service callbacks. Do not
replace the god class with a god context or dozens of lambdas.

## Platform testability boundary

MockBukkit is a partial Paper implementation, not a server emulator. In the
pinned 4.110.0 artifact, APIs used by ArcFarms such as block passability,
plugin chunk tickets, display billboards and mob despawn policy are absent,
while passenger ejection has weaker bookkeeping than Paper. A missing mock API
must not change the production gameplay contract.

ArcFarms isolates only proven gaps behind one-purpose ports:

- `FarmBlockPassability` answers only the exact Paper passability query;
- `FarmBlockDataDecoder` decodes journalled block state without a permissive
  fallback that could discard properties;
- `FarmTextDisplayRenderer` applies the complete visual contract of one text
  display;
- `FarmMobDespawnPolicy` owns only distance-despawn configuration;
- `FarmVehiclePassengerControl` owns only passenger unlinking;
- `MoleBurrowChunkRetention` returns an idempotent lease owned by the mole
  tunnel lifecycle instead of exposing a caller-paired retain/release protocol.

`FarmComponentGraph` wires the Paper adapters. MockBukkit scenario fixtures wire
their explicit test adapters from `src/test`; those adapters may approximate an
unsupported API, but must preserve the gameplay-observable contract and state
the pinned limitation. Gameplay constructors never accept raw callbacks such as
`blockPassable`, `configureDisplay`, `setRemoveWhenFarAway` or `ejectPassengers`.
Do not add a test-mode branch, catch `UnimplementedOperationException` in
production, or collect unrelated ports into a service locator. A new port is
justified only by a verified external boundary, not merely to make mocking easy.
Do not merge ports merely because their parameters are Bukkit entities or
blocks; that recreates a service locator under a narrower name.

## Farm module

`FarmModule` is a coordinator, not another god class. `FarmRuntimeRegistry`
owns the zone collection; the module delegates to the following vertical owners.

| Farm package/owner | Owns state and behavior | Primary tests |
|---|---|---|
| `farm.shift/FarmShiftCoordinator` | start, transition application, completion, persistence request, network/stats/reward handoff | complete phase flow and rejected transition |
| `farm.field/FarmFieldController` | bed discovery, patch selection, till/plant, wet soil, managed block index | field selection, 90% quota, covered bed exclusion |
| `farm.placement/FarmPlacementService`, `FarmSurfacePolicy` | bounded outdoor placement, loaded-column surface checks, delivery layouts | roof/cave rejection and no chunk loads |
| `domain.placement/WorksitePlacementPlanner` and strategies | platform-neutral candidate distribution profiles shared by any worksite event | Bukkit validation, entity spawning, event-specific state |
| `farm.recovery/FarmFixedCropRecoveryController` and `FarmIncidentRecoveryController` | fixed-crop and incident restore queues with per-tick budgets | restart, unloaded chunk, partial restore, stale callback |
| `farm.harvest/FarmHarvestController` | accepted crop validation, drop suppression, fixed fruit intent and respawn | wrong phase/crop, no drops, journal-before-mutation |
| `farm.care/FarmCareController`, `FarmDiseaseController`, `FarmCarePresentation` | routes care, owns shared entities and seeder/animal lifecycles, local disease frontier/death journal and shared feedback | cleanup and activity scenarios |
| `farm.care.scarecrow/FarmScarecrowDeliveryController` | receiving stock, one-player carriers, moving displays and placed scarecrow convergence | pickup, carry, leave/reload and completion |
| `farm.expedition/FarmUndergroundExpedition`, `FarmUndergroundSurfaceOwner`; `farm.care.mole/FarmMoleBurrowController`, `FarmMoleBurrowWorld` | shared underground travel, surface candidate/marker ownership, plus mole entrance/lair scene, durable player return, chunk-PDC tunnel journal and bounded build/restore | deterministic maze, codec corruption, restart return, non-mutating preview, authorized teleport reconciliation |
| `farm.shift/FarmShiftCoordinator` | transition dispatch and configured incident schedule | configured count/range and non-repeat rules |
| `farm.incident/drought`, `pest`, `special` | drought, pests, giant crop, channels, night shift, market; each owns entities/blocks/maps/recovery | restart/dedup/cleanup plus story flow |
| `farm.incident.processing/FarmProcessingIncident`, `FarmProcessingScene` | durable load/operate/pack flow from one oriented anchor; bounded transient workshop, cargo carriers, interactions and timing visual | three-stage domain flow, persistence invariants, bounded spawn/restart reconciliation/cleanup |
| `farm.incident.greenhouse/FarmHellGreenhouseIncident`, `FarmHellGreenhouseScene` | hell-rift chamber and rune/heat interaction lifecycle; shared travel and surface entry remain in `farm.expedition/FarmUndergroundExpedition`; pure rules in `domain/FarmHellGreenhouse` | full run, sweep interruption, pause/restart/exit, obstruction/water placement and persistence corruption |
| `farm.delivery/FarmDeliveryController` | crate placement, carrier state, display following, receiving and return | two players, quit/leave/reload, no duplicate crate |
| `farm.presentation/FarmActivityPortal`, `FarmPortalRenderer` | shared portal scene, particle pulse, title countdown, cancellation and destination action; delivery and rival raid supply `FarmPortalDestination` | delayed target readiness, off-phase pulses, exit/re-entry, cleanup and both activity boarding flows |
| `domain/FarmTerminalDelivery`, `farm.incident.route/FarmFoodDeliveryIncident`, `FarmFoodDeliverySession` | fixed crate-to-route finale, delayed portal seating, mounted/walking participant identity, rifle/HUD/time/damage lifecycle | portal driver/gunner assignment, dismount, varied ambush, restart, unavailable route and final cleanup |
| `farm.scene/FarmContractSceneController` | cart/customer/cargo scene reconciliation | unloaded chunk and dedup |
| `farm.supply/FarmSupplyController` | supply displays, tagged service items, inventory boundary cleanup | issue/replace/leave/death/reload |
| `farm.reward/FarmRewardService` | reward ledger, durable claim, economy/items/commands | persistence failure, exactly-once claim, partial provider failure |
| `domain.enterprise/WorksiteEnterpriseLedger` | activity-neutral reservation, settlement arithmetic, weekly aggregates and exact-once watermarks | Bukkit, Vault, farm crops or any concrete worksite engine |
| `domain.enterprise/WorksiteEnterpriseCapitalLedger` | primary funding, ownership, treasury, weekly distributions, player claims and durable money-operation journal | Bukkit/Vault calls, menus or concrete worksite rules |
| `paper.enterprise/WorksiteEnterpriseService` | the single enterprise state owner, lifecycle-safe persistence-before-Vault orchestration and typed activity adapters | concrete crop/order mechanics or secondary-market matching |
| `farm.enterprise/FarmEnterpriseAdapter` | translates farm order start/cancellation/completion into the shared enterprise contract | ownership, dividends, auctions or reusable accounting rules |
| `worksite/WorksiteSidebarController` | shared native sidebar sessions, incremental dynamic rows, scoped cleanup and prior-board restoration; TAB 6 automatically yields to its objective packets | grow/shrink, activity handoff, leave/reload and existing sidebar |
| `farm.presentation/FarmHudController` and `FarmGuidanceController` | boss bars, scoreboard, guidance, entry UI, music and stage feedback | join/leave/reload and competing scoreboard |
| `farm.admin/FarmGameplayAdminService`, `FarmPointAdminService`, `FarmWorldAdminService` | typed admin operations through feature APIs | invalid stage/point/selection, active event edit |
| `farm/FarmEventRouter` | Paper event classification and delegation only | listener routing and cancelled-event policy |
| `navigation/ActivityTravelService` | local/cross-server travel and ticket claims | stale callback and failed transfer |
| `paper/platform` | exact Paper adapters for proven test-double gaps | gameplay decisions or test approximations |

Care and incident dispatch remains bounded inside the corresponding feature
package. Adding a type requires a domain enum, one focused handler,
config/locale wiring and its tests; it must not require editing the application
facade or another worksite.

Spatial preference is selected rather than reimplemented. An event maps its
own candidate type to `WorksitePlacementPoint`, supplies a deterministic seed,
and chooses a `WorksitePlacementProfile`. `BalancedRingPlacementStrategy`
prefers the varied middle ellipse while retaining broad/full-field and spacing
fallbacks; `FarthestPointPlacementStrategy` distributes targets across the
whole candidate pool. A new layout policy implements `WorksitePlacementStrategy`
and receives contract tests independent of farm, lumbermill or mine types.

## State ownership

Only `FarmRuntimeRegistry` owns the active `FarmRuntime` collection;
`FarmModule` coordinates it through explicit lookup/snapshot methods. Persisted
farm state remains in `FarmShiftState` to avoid a risky data migration, but
all mutations go through `FarmShiftCoordinator.apply(zoneId, EngineResult)`.
Feature owners may read the runtime and own their own ephemeral maps; they may
not keep a second farm list or mutate another feature's maps.

Each mutable collection has exactly one owner. In particular:

- delivery carriers/displays -> delivery controller;
- pest and nest entities -> pest incident;
- drought flows/growth -> drought incident;
- care entities/followers -> care controller;
- disease frontier/death timers -> disease controller; killed crop intent -> incident recovery journal;
- scarecrow supply/carriers/placed displays -> scarecrow delivery controller;
- mole tunnel blocks, scene entities and active explorers -> mole burrow owner;
- processing workshop scene, cargo carriers and timing cycles -> processing incident owner;
- supply displays/item tags -> supply controller;
- scoreboard sessions/music -> presentation;
- restore queues -> recovery controller;
- reward pending/claimed -> reward service.
- enterprise reservations/reports/capital/claims -> `WorksiteEnterpriseService` and its shared ledgers; farm event translation -> farm enterprise adapter.

Cross-feature effects are typed results such as `FarmTransition`,
`IncidentResolved`, `DeliveryCompleted`, or a narrow method on the owning
coordinator. Features never reach into another owner's collection.

## Entity lifecycle contract

Every entity-owning feature must implement the same convergence rules:

1. Store a bounded typed identity in PDC: owner, zone, sequence, role and local
   id where applicable.
2. Treat an in-memory UUID as a cache only. Reconciliation scans loaded chunks
   for the PDC identity; `Bukkit.getEntity(UUID) == null` is not proof that a
   persistent entity no longer exists.
3. Choose at most one canonical entity per identity and remove loaded
   duplicates before spawning a replacement.
4. Spawn only while the owning persisted objective is active and its chunk is
   loaded. Decorative entities should normally be non-persistent.
5. `reconcileLoaded`, objective cleanup, reload cleanup and shutdown cleanup are
   idempotent and tested.
6. Collision-capable vehicles are forbidden for decoration. Item models use
   `ItemDisplay` plus a separate `Interaction` hitbox.

World mutation uses durable intent before the mutation where recovery matters.
Large repair/create/delete work is budgeted across ticks and never performed in
one unbounded loop.

Procedural outdoor objectives share `FarmSurfacePolicy`. A loaded column is
eligible only when the entity feet are walkable at the terrain height, or when
an indexed crop bed has no motion-blocking terrain above its crop layer.
Persisted coordinates are checked again before an entity or guidance marker is
created. Explicit indoor service points remain administrator-owned; underground events use the shared indexed-bed entrance policy and journalled
room pipeline.

## Threading and failure model

- Bukkit world, entity and inventory APIs run only on the Paper thread.
- File, Redis, database and expensive scans do not block the Paper thread.
- State snapshots are submitted through `CoalescingAsyncWriter`; gameplay,
  clicks and admin event routes never wait for the file writer. Reload and
  shutdown are the only blocking full-state flush boundaries.
- Reward-ledger mutations are serialized. Enqueue and claim effects wait for a
  successful durability future; a lifecycle boundary restores an in-flight
  claim to pending before its final flush.
- Enterprise state shares the atomic ArcFarms snapshot. `SHADOW` performs no
  provider side effects; single-authority primary funding and player claims use
  a durable `PREPARED -> provider -> terminal` journal and never retry an
  ambiguous provider result. Multi-node ownership and secondary-market matching
  still require a transactional shared store with idempotency constraints.
- Every async callback captures a `RuntimeTasks` epoch and is rejected after
  reload/close.
- Periodic entity updates use feature-owned bounded UUID indexes. Full-world
  scans are lifecycle reconciliation or cleanup operations only.
- Procedural placement reports bounded rejection counters when an activity
  cannot start. Mole layouts treat the region as a horizontal farm footprint
  while preserving material, height, loaded-chunk and journal safety checks;
  animal rescue selects only indexed outdoor beds, never generic roof surfaces.
- Reload publishes one staged config-and-locale generation. Supplier-backed UI,
  locale, navigation and enterprise policy take a fast path that keeps active
  worksite objects and tasks alive; open owned menus are redrawn after commit.
  Structural zone and timer changes validate the candidate, invalidate the old
  epoch, clean up features, replace runtime, reconcile loaded state, then
  activate tasks. Validation failures leave the complete old generation usable;
  failures after mutation trigger snapshot rollback, and a failed rollback disables
  the plugin instead of exposing a mixed generation. Any unconfirmed live-state
  write failure likewise fail-stops the plugin without publishing candidate settings.
- Zero players pauses progress but never resets a goal.
- Cleanup and reconciliation are safe to repeat after partial failure.
- Collections, coordinates, entity counts, commands and recovery work are
  bounded by validated config or hard safety caps.

## Security boundary

Player clicks, items/NBT, commands, Redis data, JSON state and operator config
are untrusted at their ingress.

- Listener routing verifies zone, active sequence, role, permission and current
  phase again before mutation.
- Admin commands use typed/allowlisted identifiers and an exact admin
  permission. Console and player identities remain distinct.
- Player UUID is authoritative. Names are presentation only.
- Reward intent is durable before delivery and claimed before side effects.
  Console reward templates come only from validated config and never interpolate
  arbitrary player text.
- Redis messages are schema/version/size/target checked and idempotent; Redis
  connectivity never grants local access or resets gameplay.
- Logs contain stable ids and outcomes, not raw NBT, Redis payloads, secrets, or
  command contents.

## Agentic-first repository rules

Inspect the feature owner, its callers and the focused tests before editing; widen
the inspection when the behavior crosses component or persistence boundaries.

1. Package and class names use the player-facing concept (`drought`, `delivery`,
   `orchard`, `mine`), not generic `Manager2`, `Utils`, or numbered phases.
2. This document's ownership table is the canonical feature map. Update it in
   the same change when an owner moves.
3. Production file target is 600 lines; 800 is a hard review threshold. Domain
   tables/codecs may exceed the target only with a documented reason. No
   gameplay file may exceed 1,000 lines.
4. One test file mirrors each owner. Shared scenario builders live under
   `src/test/.../fixtures`, not as 900-line test classes.
5. A feature package exposes a small entry class and keeps Paper details
   internal. Public methods are player verbs or lifecycle verbs.
6. Every stateful feature has `reconcile`, `cleanup`, and restart tests before
   its extraction is considered complete.
7. Architecture tests enforce file caps, dependency direction, forbidden state
   in facades, registry coverage, and direct scheduler/Bukkit use.
8. No `Utils` dumping ground and no callback bag. Repeated mechanics become a
   named port or a small value object only after two real consumers exist.

## Migration plan

Each slice must compile and pass its focused tests before the next begins. A
slice moves state, PDC keys, event handling, tick/reconcile, cleanup and tests
together.

1. Establish this architecture contract and enforce the final 600-line service
   ceiling plus the 800-line gameplay review ceiling.
2. Finish supply ownership, including every inventory and entity lifecycle
   boundary.
3. Extract delivery and contract scene ownership.
4. Extract classic incidents: pests and drought, including crop recovery.
5. Extract special incidents into the typed incident registry.
6. Extract care stories into the typed care registry; separate mechanized field
   work from target-based care.
7. Extract field selection/preparation, harvest and all bounded recovery.
8. Extract presentation/music/guidance and admin APIs.
9. Keep `FarmRuntimeRegistry` as the sole zone-list owner, `FarmModule` as the
   lifecycle coordinator, and `ArcFarmsService` as the thin application facade.
10. Split the oversized config and test fixtures by the same feature map.
11. Run local unit, architecture and MockBukkit lifecycle suites with `test`,
    build `shadowJar`, then run platform/integration acceptance through the
    leased lab workflow before deployment. Do not run containerized integration
    suites directly on the developer workstation.

No migration step is complete when it only reduces a line count. It is complete
when the old owner has no state or lifecycle branch for that feature and the new
owner passes restart/dedup/cleanup tests.

### Native worksite sidebar and proxy TAB

`PaperWorksiteAdapter` owns one `WorksiteSidebarController` for farm, mine and
lumber. Renderers supply a title and up to 15 component rows; updates keep the
objective and unchanged entries, deleting only vanished rows. Farm retains its
rich order layout; mine/lumber reuse their current guidance and progress. The
legacy `ui.farm-scoreboard` settings apply to this shared sidebar. `BUKKIT` is the
native provider; `TAB` retains only the old farm placeholder integration.

With native rendering, `arcfarms_farm_active` is false so TAB cannot select its
legacy farm layout. Proxy TAB 6.1.0 automatically detects another sidebar's
display/objective removal packets, yields while it is present and restores its
own afterward, respecting the player's `/sb` preference. No polling placeholder
is used to arbitrate ownership, and tablist/nametag features remain enabled.
See [TAB's compatibility contract](https://github.com/NEZNAMY/TAB/wiki/Feature-guide:-Scoreboard#compatibility-with-other-plugins).

## Event types and inherited presentation

Every incident selects `FieldEvent`, `PortalEvent` or `UndergroundEvent` in
`FarmEventTypeRegistry`. The registry is exhaustive; locale validation requires
each type’s title, subtitle and hint. `FarmShiftCoordinator` announces the
resolved event through this definition, `FarmHudController` reads its hint,
and `FarmGuidanceController` applies its shared marker policy. Portal variants
use the existing common countdown portal; underground variants compose
`FarmUndergroundExpedition` for entrance, durable travel and return recovery.
Add variant mechanics after selecting an existing type. Extend a shared owner
when a QoL fix applies to siblings; do not copy its implementation into a new
event controller.
