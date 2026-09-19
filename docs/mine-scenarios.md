# Mine scenarios

Mine v2 advances one shared ore order through prospecting, mining and loading.
Incidents interrupt that order; completion resumes the same quota and does not
create a second payout.

## Supported incidents

The current `MineIncidentScheduler` supports ten player-facing types: the
seven existing incidents `CAVE_IN`, `GAS_LEAK`, `FLOODING`, `TRACK_DAMAGE`,
`CRYSTAL_RESONANCE`, `CREATURE_NEST`, `LOST_MINER`, plus `TUNNEL_DRIVE`,
`RAIL_EXTENSION` and `ORE_WORKSHOP`. `POWER_FAILURE` remains readable in old
saves and in the enum, but is retired: the scheduler skips it and the manual
force path rejects it. It is not a current player scenario.

| ID | Player route |
| --- | --- |
| `CAVE_IN` | Follow the glowing rubble marker and break the journaled rubble with a pickaxe. Each cleared block restores the original scene. |
| `GAS_LEAK` | Close the glowing vents. Every live vent renders cloud, smoke and dust; a non-creative participant within 3.5 blocks takes 1 damage once per second and receives Nausea for 3 seconds. |
| `FLOODING` | Drain 20–30 real water blocks with an empty bucket. The interaction is consumed by the incident, so the bucket stays empty; the journal restores the footprint after completion. |
| `TRACK_DAMAGE` | Use the rail-working route: clear the collapse, replace missing rail sections in entrance order, then escort the checking minecart along the repaired line. |
| `CRYSTAL_RESONANCE` | Activate the glowing amethyst targets during the interaction window. |
| `CREATURE_NEST` | Defeat the spawned cave creatures and destroy each glowing nest core. Incident creatures and nests do not drop ordinary loot. |
| `LOST_MINER` | Find the marked natural-cave entry and click it to enter. Find the miner inside, complete the rescue, and leave through the marked exit. |

The physical incident owners are `MineCaveInIncident`, `MineGasLeakIncident`,
`MineFloodingIncident`, `MineTrackDamageIncident`, the crystal/creature owners,
`MineLostMinerIncident`, and the working controllers. Focused checks cover their
interaction and recovery contracts; current activation is tracked separately
below.

## Physical workings

`TUNNEL_DRIVE`, `RAIL_EXTENSION` and `TRACK_DAMAGE` are the three rail/side-
working routes. A fresh route starts from a configured mine-floor entrance,
which the player enters on foot. The temporary geometry and progress are
journaled before mutation and restored or resumed through the shared worksite
scene lifecycle.

| ID | Lifecycle | Service equipment |
| --- | --- | --- |
| `TUNNEL_DRIVE` | Right-click the drill cart to control the boring front, walk beside it while it cuts the real rock, then install supports at the completed front. | Drill cart and supports; neither becomes ordinary loot. |
| `RAIL_EXTENSION` | Clear the collapse, click the rail markers to lay one continuous ordered line from the entrance, then run the checking minecart along it. | Rails and checking cart; the engine rejects out-of-order segments. |
| `TRACK_DAMAGE` | Reuse the rail layout with the existing bed already present: clear rubble, replace damaged or missing segments, and run the checking cart. A persisted legacy incident without `working` remains readable by `MineTrackDamageIncident`. | Rails and checking cart; no new reward item. |

`ORE_WORKSHOP` is a separate fixed workshop incident, not a side-working
entrance. It uses the five mapped stations in order for three batches: carry
ore from `ore_input` to `ore_crusher`, walk three laps for the three `CRUSH`
strokes, heat for 4 seconds, right-click the furnace during the following
4-second cooling window, then carry the billet from `ore_output` to
`ore_shipping`. A missed window reheats the same batch.

The pending `.40` working/rescue contract specifies at least 60 seconds of
grace, an 8-block clear area around the cave, a five-minute deadline and
a 15-second warning. Entering the scene has no cancel action. The scene
brightness contract is 15/15. The lateral working geometry is a 24-cell
winding cave in the bounded region `x=72..87, z=12..41`, widened into large
chambers with decoration. Rescue remains a separate off-site noise cave; its
dimensions are unchanged by this working geometry. These are geometry rules,
not a final client-visual claim.

## Scheduling, points and recovery

Incident selection is deterministic for a shift. When several incidents are
scheduled, their thresholds are spread through the quota; a 100-block order
with three slots uses 25, 50 and 75 rather than starting the whole schedule at
halfway. Bundled mine order pools preserve their existing entries and add the
three new configured working IDs; a zone may still narrow its pool.

The bundled fallback is
`src/main/resources/mine/compact-map-points.json`, zone `old_shafts`, world
`rc_atelier_compact_mine`. Its crusher is `(44.5, 111, 24.5)`. The default
side-working entrances are `(72.5, 111, 27.5)`, `(72.5, 97, 27.5)` and
`(72.5, 83, 27.5)`, each with cardinal yaw `-90`. The other fixed stations are
`ore_input (40.5,111,23.5)`, `ore_furnace (44.5,97,23.5)`,
`ore_output (40.5,97,23.5)` and `ore_shipping (48.5,97,23.5)`.

Administrators capture a player-feet position and cardinal facing with
`/arcfarms admin point <zone> <kind>`, where `<kind>` is one of
`ore_input`, `ore_crusher`, `ore_furnace`, `ore_output`, `ore_shipping` or
`working_1` through `working_12`. Append `clear` to remove an override:
`/arcfarms admin point <zone> <kind> clear`. Runtime overrides are persisted in
`plugins/ArcFarms/data/mine-locations.json`; clearing returns to the bundled
fallback for the compact map. The source fallback remains
`src/main/resources/mine/compact-map-points.json`.

Temporary blocks, service items and working entities belong to the shared scene
and journal owners. Completion, cancellation, reload and restart reconcile the
saved stage before a new activity can use the floor. Supports, rails, ore,
billets and the checking cart are service materials only: no stage awards XP,
Vault money, tokens or ordinary loot. Existing order rewards and prices are
unchanged, including the source `old_shafts` completion of 220 XP and 4 iron.

## Verification and delivery state

The `.39` source cutoff is `a9e0ec74` and is pushed. CI run `35473091522` and
Paper E2E run `35473091467` both succeeded; the focused `.39` report has 153
passes and one known skip. `.39` is the historical QA and activated build,
while `.40` is the pending final release because of the transient guidance and
grace fixes.

ArcFarms `.39` reached `classic` at 22:32:55 UTC; the parallel ARC classic
restart completed at 22:35 UTC. Health reported ready, the disk hash matched
`dcb09a909d6848dd3be9119cf346c720ef2585ea35c985a6d6e53b392c8a8259`, and the
server had one ArcFarms root JAR of 14,242,670 bytes. The process changed from
PID 2913811 to 2920519.

Native `.39` checks passed for the following actions:

- `CAVE_IN`: 56/56 survival-mode interactions with an iron pick; each
  per-block glow vanished when cleared and the final mined count stayed 35.
- `FLOODING`: 30/30 owned water blocks at Y=83 were drained with native
  `use_item` while holding `Bucket`, ending as empty `Bucket`; 21 nearby Y=81 blocks
  were pre-existing and outside the incident footprint. The order returned to
  `MINING` with progress 35/448.
- `TRACK_DAMAGE`: 18/18 native rail-repair interactions passed.
- `TUNNEL_DRIVE`: native QA bored 90/93 actions. The remaining three support
  actions were partially performed by another player, so this is not a solo
  completion claim.
- `CREATURE_NEST`: rotation nonce 89 produced nests at `(9,115,42)`,
  `(77,101,50)` and `(48,99,9)`; nonce 90 produced a different set at
  `(74,115,37)`, `(11,99,46)` and `(47,110,83)`. At the first new nest,
  client entity `3113` was the glowing `mangrove_roots` nest display and Husk
  `3112` reported glowing. Native nest-kill was not tested in this pass; old
  combat behavior is unchanged.
- `LOST_MINER`: native click-entry, native exit and spectator flight from
  Y=87 to Y=98 were confirmed by server NBT. The live route reached
  `(43.5,87,102.5)`, then `(53.33,87,110.33)`; the villager target `3066` was
  selected with mouse 0, hand 0 at 1.97 blocks, and the order returned to
  `MINING` with progress 35/448. Full goal click passed natively, but `.39`
  still reproduces immediate eject after completion; the new grace fix is pending.
  Creative mode was used only to protect the QA actor from mobs, not to replace
  the rescue combat interaction.

Earlier native `0.40.38` checks remain historical QA: the workshop completed
three batches in 18 actions; gas required two clicks among four candidate vents,
showed CLOUD and SMOKE, changed health from 20 to 16.2 and applied Nausea NBT;
resonance passed two target clicks with glow. The `.39` live workshop carry
readback used input `Interaction` `1965` and a glowing `raw_iron` `ItemDisplay`
`3128` at `(40.5,112,24.75)`, with player feet at `(40.5,111,25.5)`: the
chest carry is exactly +1 Y and the old below-feet issue is fixed. Full `.40`
workflow readback is still pending.

A forced `LOST_MINER` to `CAVE_IN` replacement reproduced a transient title
from the old incident. Root commit `8327c29` for `.40` fixes
`WorksiteGuidancePresenter`'s same-runtime `false → true` presentation path;
its tests and build succeeded. The 60-second grace/8-block area/five-minute
deadline/15-second warning change is also pending `.40` validation. The viewer
cannot render outline pixels, so no final visual/client pass is claimed; the
older `rescue-cave-orbit` frame used the wrong scene and is excluded.

Immutable JAR
`dcb09a909d6848dd3be9119cf346c720ef2585ea35c985a6d6e53b392c8a8259` is the
activated `.39` artifact; `.40` remains the pending final release.

## Related sources

- `src/main/kotlin/ru/ruscrafting/farms/domain/MineWorking.kt`
- `src/main/kotlin/ru/ruscrafting/farms/paper/mine/incident/MineIncidentScheduler.kt`
- `src/main/kotlin/ru/ruscrafting/farms/paper/mine/working/MineWorkingController.kt`
- `src/main/kotlin/ru/ruscrafting/farms/paper/mine/workshop/MineOreWorkshopController.kt`
- `src/main/kotlin/ru/ruscrafting/farms/paper/mine/point/MinePointService.kt`
- `src/main/kotlin/ru/ruscrafting/farms/persistence/MineLocationRepository.kt`
- `src/main/resources/mine/compact-map-points.json`
- `plugins/ArcFarms/data/mine-locations.json` (runtime override store)
