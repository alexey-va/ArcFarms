# ArcFarms Worksite V2 Design

Status: approved direction, specification candidate for owner review, 2026-08-30.

## Goal

Replace the rudimentary lumbermill and mine implementations with two complete,
distinct worksite experiences at the same production-quality level as the farm.
The rewrite must also extract the activity-neutral lifecycle, objective, UX,
service-item, participant-safety and recovery mechanics proven by the farm so
future worksites do not start as another monolithic controller.

The existing farm remains behavior-compatible throughout the migration. Existing
menu buttons, travel destinations, permissions, network activity identifiers and
admin entry points for lumber and mine remain valid. The release ends with lab
acceptance, a production deployment and production readback.

## Architectural choice

ArcFarms uses a shared worksite kernel with independent vertical modules. It does
not use a universal YAML event DSL and it does not clone farm controllers.

```text
ArcFarmsApplication
  -> WorksiteModuleRegistry
       -> FarmModule
       -> LumbermillModule
       -> MineModule
  -> WorksiteEventRouter (typed capabilities only)
  -> PersistenceCoordinator

worksite modules
  -> WorksiteObjectiveCoordinator
  -> WorksiteGuidancePresenter
  -> WorksiteServiceItemController
  -> WorksiteParticipantSafety
  -> WorksiteRewardGrantService
  -> module-owned recovery and world adapters
```

Each player-facing concept has one feature owner and a mirrored test. The kernel
contains mechanics and invariants, never story names, materials, phase ordering
or module-specific rules.

## Common module contract

`WorksiteModule<S>` retains queries and ticking and gains the complete runtime
lifecycle through `RuntimeComponent`:

```kotlin
internal interface RuntimeComponent {
    fun activateLoadedState()
    fun reconcileChunk(chunk: Chunk)
    fun beforeReload(reason: String)
    fun cleanup(reason: String)
}

internal interface WorksiteModule<S> : RuntimeComponent {
    val kind: ActivityKind
    val zoneCount: Int
    fun states(): Map<String, S>
    fun statuses(): List<ActivityStatus>
    fun tick(now: Long)
    fun canAccess(player: Player): Boolean
}
```

Paper events remain capability-based. Separate interfaces cover block break,
block interaction, entity interaction, movement, inventory boundaries, player
lifecycle, damage, projectiles and chunk load. There is no universal event
object and no module may call another concrete module.

`WorksiteModuleRegistry` contains farm, lumber and mine. `FarmEventRouter` stops
routing mine and lumber. A top-level `WorksiteEventRouter` classifies an event
once and invokes matching module capabilities in deterministic priority order.

Domain engines return `EngineResult<S, E>` where `E` is a module-local sealed
event type. `FarmShiftEvent`, `LumberShiftEvent` and `MineShiftEvent` replace the
current global `ShiftEvent`; one module cannot accidentally consume another
module's phase event.

## Worksite objective model

The common objective model owns identity and safety, not gameplay semantics.

- `WorksiteObjectiveKey`: activity, zone, shift sequence, stage and objective
  nonce.
- `ObjectiveTargetState`: stable target id, typed role, world position and
  `AVAILABLE`, `LEASED`, `COMPLETED` or `INVALID` status.
- `ObjectiveTargetPool`: required completion count, placement count, completed
  ids and bounded replacement history.
- `ObjectiveLease`: target id, player id and last accepted progress time.
- `ObjectiveProgress`: accepted progress, required progress and optional bonus
  progress kept outside completion.

Generated physical objectives request `placementCount = requiredCount * 2`.
Completion remains capped at `requiredCount`. If terrain cannot fit twice the
quota, creation succeeds only when at least the required count is valid and
emits one bounded warning containing the zone, sequence, objective, requested,
accepted and rejection counters. If fewer than the required count are valid,
the objective is rejected before world mutation and the module selects another
event or prepares another candidate batch.

Natural resources are never invented merely to satisfy the multiplier. Their
durable index supplies the candidate pool; selection aims for twice the quota
and requires at least the quota. Invalid, unloaded, replaced or unreachable
entries do not count.

No required target is permanently owned by one participant. A lease is released
on completion, quit, death, server transfer, teleport out, portal out, reload or
objective replacement. An in-zone participant is not punished for slow play.
A lease may expire after 45 seconds without accepted progress only when the
participant is outside the objective's configured interaction radius; expiry
returns the target and service item to the pool.

## Durable spatial indexes

Lumber and mine gain chunk-PDC indexes with the same two-phase bounded rebuild
rules as the farm index:

1. Resolve configured chunks without a synchronous full-region scan.
2. Load through exact plugin tickets.
3. Scan a configurable block budget per tick.
4. Validate material, world, region, accessibility and current block state.
5. Replace one chunk's index atomically and release its exact ticket.

Hot guidance and objective ticks read only these indexes and already-loaded
chunks. They never call `World#getEntities`, scan an entire region or load a
chunk. An automatic bounded bootstrap runs when an enabled V2 zone lacks enough
indexed candidates; the worksite shows a preparing title until the minimum pool
is ready. Admin reindex/status/cancel commands expose progress and rejection
reasons.

The lumber index records eligible logs by species and accessible interaction
face. The mine index records mineable blocks, safe support faces, rail anchors,
vent anchors, pump anchors and lamp anchors. Module recovery updates affected
entries after every journalled mutation and restoration.

## Guidance and participant UX

`WorksiteGuidancePresenter` consumes an objective read model supplied by each
module.

- Stage changes always show a localized title and next-action subtitle.
- Boss bars contain only the immediate objective and exact progress.
- Existing action bars remain supplementary.
- Every participant receives a personal marker for the nearest available target
  of each currently required role.
- Marker colors are stable per role and particles use the existing supervised
  `guidance_particles` cadence.
- If a participant has no accepted progress for 12 seconds, a long title repeats
  the current action. Further reminders are rate-limited to 12 seconds.
- A participant carrying a service item receives a destination title when moving
  away from every valid destination or remaining outside its interaction radius.
- Success feedback identifies what changed and what action comes next.

All text exists in mirrored Russian and English locale keys. Titles never embed
internal implementation details such as target indexes, restored materials or
left/right assumptions.

## Service items and inventory safety

`WorksiteServiceItemController` owns all temporary lumber and mine items. Every
item carries bounded PDC identity: activity, zone, sequence, objective nonce,
role and item id.

Service items may move inside the participant's own storage slots. They cannot
enter crafting slots, containers, shulkers, ender chests, item frames, armor
slots or another player's inventory. They cannot be dropped, retained on death,
consumed by vanilla recipes or used outside their owning objective.

Quit, death, zone exit, backend transfer, reload and shutdown remove the item and
release its lease. Startup and join cleanup remove stale service items from an
older sequence. Removing an item never advances progress; delivery to a valid
target is the only completion path.

## World mutation and recovery

Every recoverable block mutation writes durable intent before changing the
world. Common code owns lifecycle tokens, bounded queue processing, stale
callback rejection and exact retirement rules. Modules own material selection,
drop calculation and restoration semantics.

The mine retains its existing recovery journal and migrates it without dropping
records. Lumber gains a journal for selected logs and temporary incident blocks.
Breaking a selected log preserves its vanilla material outcome while preventing
duplicate drops. The original block data is restored with physics disabled after
the configured deadline. Incident cleanup restores only records confirmed in the
world and keeps unloaded or failed records for another pass.

Generated entities use PDC identity, bounded UUID caches and loaded-chunk
reconciliation. Decorative vehicles use `ItemDisplay` plus `Interaction`, never
collision-capable minecarts. Any ItemsAdder/custom-model display must pass the
`itemsadder-item-display-grounding` contact report for its final model,
transform and production surface before placement.

## Lumbermill V2

### Main shift

`LumberPhase` becomes:

```text
IDLE -> FELLING -> SKIDDING -> SAWING -> STACKING -> DISPATCH -> COOLDOWN
                    \          |          /
                     -> INCIDENT -> resume
```

- **FELLING:** an order selects one configured species. The index exposes up to
  twice the required number of correct logs. Only marked mature targets advance
  the order. Wrong species, unindexed logs and already completed targets remain
  intact and produce a title naming the required species.
- **SKIDDING:** non-colliding log-bundle displays appear at validated selected
  felling sites. A participant picks one bundle up and carries it to the landing.
  The visible placement count is twice the required delivery count.
- **SAWING:** participants alternate the highlighted saw controls. The sequence
  accepts either direction after the previous control, has a forgiving timing
  window and never resets already accepted progress for a mistimed interaction.
- **STACKING:** finished plank bundles are carried from the saw output to marked
  pallet slots. Pallet slots are generated at twice the completion quota.
- **DISPATCH:** the foreman bell becomes active after stacking. One interaction
  seals the batch, records contributions and creates the exact-once reward.

### Incident schedule

Each configured lumber order resolves three to five distinct incidents at
deterministic progress checkpoints. An incident pauses the foreground stage and
resumes that exact stage and progress after resolution.

- `WINDTHROW`: clear required branch/root obstacles from twice as many visible
  props.
- `BARK_BEETLES`: scrape marked infected log faces with an axe; invalidated logs
  are replaced from the index.
- `SAW_JAM`: stop the saw, release safety switches in sequence and remove jam
  props before restarting it.
- `CONVEYOR_BREAKDOWN`: carry bound belt and gear kits from supplies to repair
  anchors.
- `FOREST_FIRE`: take a bound water tool and extinguish journalled fire targets;
  zero participants pauses spread and damage.
- `LOST_LOAD`: return scattered bundles to the landing; a carrier leaving the
  zone releases the bundle at its safe origin.
- `WARPED_BATCH`: sort marked planks into accept and reject pallets. Both roles
  have stable colors and personal nearest-target guidance.
- `RUSH_ORDER`: a non-blocking bonus timer rewards quick stacking. Expiry removes
  only the bonus and continues the ordinary objective without lost progress.

## Mine V2

### Main expedition

`MinePhase` becomes:

```text
IDLE -> PROSPECTING -> MINING -> LOADING -> EXTRACTION -> COOLDOWN
             \           |         /
              -> INCIDENT -> resume
```

- **PROSPECTING:** inspect marked safe faces to reveal the expedition's vein.
  Twice the required number of faces is available; inspection does not mutate
  blocks.
- **MINING:** only indexed vein targets advance the cart. Every accepted block
  uses the recovery journal before mutation and keeps the existing weighted
  material rewards.
- **LOADING:** bound ore crates are carried from completed mining clusters to the
  cart. A participant cannot hold the global expedition hostage.
- **EXTRACTION:** participants move a non-colliding display cart along a recorded
  route to the exit. Step-height changes are tolerated. Collision, no movement
  or route deviation pauses the cart and rewinds it to the last safe route sample
  without damaging or pinning a passenger.

### Incident schedule

Each expedition resolves three to five distinct incidents and resumes its exact
foreground phase.

- `CAVE_IN`: carry bound support kits to safe support faces. Visible faces are
  twice the support quota.
- `GAS_LEAK`: locate vents and operate them in the displayed sequence. An error
  repeats the current vent; it does not reset completed vents.
- `FLOODING`: carry pump components, assemble pumps and operate them until the
  bounded journalled water set is cleared. Zero participants pauses spread.
- `TRACK_DAMAGE`: carry rail kits to twice the required number of route anchors;
  only the required count advances completion.
- `CRYSTAL_RESONANCE`: strike highlighted nodes in sequence with a forgiving
  window. Wrong nodes retain completed progress and re-highlight the next node.
- `CREATURE_NEST`: spawn twice the defeat quota when safe positions allow.
  Completion and contribution stay capped at the quota; unloaded entities are
  reconciled by PDC identity.
- `POWER_FAILURE`: install bound lamps at marked anchors and activate the main
  switch. Temporary light blocks are journalled and removed on every cleanup
  path.
- `LOST_MINER`: follow personal sound/particle guidance, find the tagged miner and
  escort them to a safe point. The miner is objective-owned rather than leased
  by one player and is reconstructed after chunk load or restart.

## Rewards

The farm's exact-once grant mechanics become `WorksiteRewardGrantService`.
Resolution is activity- and difficulty-aware; harder orders and additional
incidents increase the configured reward within validated caps. The resolved
grant is persisted before delivery and claimed durably before money, experience,
items or commands execute.

Farm reward behavior and stored grants remain byte-compatible. Lumber and mine
receive their own configured reward tables and activity contribution totals.
Relog, restart, failed economy calls and partial item delivery cannot reroll or
duplicate a grant.

## Configuration and administration

Existing zone ids, regions, station regions, permissions, travel destinations
and network activity kinds remain valid. V2 settings add nested `orders`,
`phases`, `incidents`, `targets`, `guidance`, `recovery`, `visuals` and `rewards`
sections with portable vanilla defaults.

Production zones use `engine-version: 2`. A missing value retains the legacy
parser only during the migration release; the following release removes the
legacy controllers after production readback. Active legacy lumber and mine
shifts reset to `IDLE` once with a bounded migration log. Pending mine recovery
records are never reset and must converge before the corresponding zone starts.

Admin commands provide status, start, stop, force-stage, force-incident,
objective inspect, target reseed, reindex start/status/cancel, route record,
route preview and cleanup preview. Existing travel/menu actions are unchanged.
Admin mutations use typed module APIs and cannot reach module collections.

The existing menu buttons remain usable throughout owner testing. V2 does not
add automatic player assignment, public invitations or new network broadcasts;
the ordinary workday continues to direct players to the farm unless they choose
the existing mine or lumber destination themselves.

## Performance and failure boundaries

- Gameplay world and inventory APIs run only on the Paper thread.
- Index rebuilds and recovery are explicitly budgeted per tick.
- Hot ticks use bounded target/UUID indexes and loaded chunks only.
- No periodic full-world entity scan or whole-region block scan is permitted.
- Zero participants pauses hazards, bonus timers and moving objectives.
- Reload invalidates every async callback before replacing runtime state.
- One zone or incident failure is logged with context and does not stop another
  worksite.
- Failed objective creation reports rejection counters and selects another
  configured incident; it never leaves the shift in an unresolvable state.
- Debug events include activity, zone, sequence, phase, objective, player,
  accepted/rejected reason, target counts and cleanup reason.

## Test strategy

1. Pure domain tests cover every phase transition, incident schedule,
   non-repetition, resume phase, capped contribution and non-punitive failure.
2. Shared contract tests use fake lumber and mine adapters to prove target
   overprovisioning, replacement, lease release, stale callback rejection,
   reminder timing and zero-participant pause.
3. MockBukkit module scenarios run complete lumber and mine shifts, every
   incident, inventory/drop/death/quit/teleport/portal cleanup, reload,
   unloaded chunks and duplicate entity reconciliation.
4. Recovery tests prove journal-before-mutation, restart convergence, exact
   retirement, no duplicate drops and bounded restoration.
5. Architecture tests forbid cross-module calls, global `ShiftEvent`, direct
   Bukkit scheduling, hot entity scans, monolithic files and locale drift.
6. `./gradlew clean test shadowJar` is the workstation gate.
7. The leased lab workflow runs admin-started full shifts, incident forcing,
   reload/restart recovery, bot screenshots and Spark sampling during generated
   targets and moving displays.

## Delivery decomposition

The implementation is delivered as five integrated increments on one V2 branch;
production remains on the legacy modules until all increments pass together.

1. **Kernel and compatibility:** typed module events, complete lifecycle,
   top-level routing, objective pools, guidance, service items and safety
   contracts. Farm behavior stays unchanged and architecture tests enforce the
   new boundaries.
2. **Lumber vertical:** durable index/recovery, every main phase, all eight
   incidents, rewards, admin controls and complete MockBukkit scenarios.
3. **Mine vertical:** index/recovery migration, every main phase, all eight
   incidents, cart route, rewards, admin controls and complete MockBukkit
   scenarios.
4. **Configuration and operations:** mirrored locale/config profiles, migration
   validation, route/point tooling, debug status and versioned artifact.
5. **Acceptance and rollout:** full workstation gate, lab scenarios, restart
   probes, performance evidence, production deploy and runtime readback.

An increment may be committed independently for review, but it is not described
as production-complete and does not enable a partially implemented V2 zone.

## Rollout

1. Deploy the candidate to the lab profile with V2 enabled for lab lumber and
   mine zones.
2. Complete one ordinary shift and every forced incident in both modules.
3. Restart during one carried item, one journalled block mutation and one moving
   cart; verify convergence and cleanup.
4. Verify TPS/Spark evidence, no recent severe ArcFarms errors, correct locale,
   menu travel and route/point placement.
5. Commit and push the tested source and exact versioned JAR.
6. Use the supported operations workflow to deploy the tracked production
   profiles, restart only the required backend, and read back artifact hash,
   plugin readiness, zone counts and bounded recent logs.

Production success requires the exact source commit, artifact SHA-256, deployed
hash, runtime version, enabled V2 zones, successful worksite status and no new
ArcFarms errors. Push, CI, deployment and runtime verification are reported as
separate facts.

## Acceptance criteria

- Farm behavior and travel remain green under the existing suite.
- Lumber and mine no longer use their legacy monolithic controllers when
  `engine-version: 2` is active.
- Both modules implement all main phases and all listed incidents.
- Generated targets use a two-times visible pool and cannot be monopolized by a
  player or stale lease.
- Titles, boss bars and personal markers always identify the current action.
- Service items cannot be stolen, stored or retained across lifecycle exits.
- Every temporary entity/block/item converges after reload and restart.
- Recovery intent precedes every destructive recoverable mutation.
- Hot guidance and gameplay ticks use durable indexes and loaded chunks only.
- Complete MockBukkit flows, full Gradle verification, lab acceptance and
  production readback all pass before the goal is marked complete.
