# Mine scenarios

Mine v2 advances one shared ore order through prospecting, mining and loading.
Incidents interrupt that order; completion resumes the same quota and does not
create a second payout.

## Supported incidents

The current `MineIncidentScheduler` supports ten player-facing types: the
seven existing incidents `CAVE_IN`, `GAS_LEAK`, `FLOODING`, `TRACK_DAMAGE`,
`CRYSTAL_RESONANCE`, `CREATURE_NEST`, `LOST_MINER`, plus
`TUNNEL_DRIVE`, `RAIL_EXTENSION` and `ORE_WORKSHOP`. `POWER_FAILURE` remains
readable in old saves and in the enum, but is retired: the scheduler skips it
and the manual force path rejects it. It is not a current player scenario.

| ID | Player route |
| --- | --- |
| `CAVE_IN` | Follow the glowing rubble marker and break the journaled cobble with a pickaxe. Each cleared block restores the original scene. |
| `GAS_LEAK` | Close the glowing vents. Every live vent renders cloud, smoke and dust; a non-creative participant within 3.5 blocks takes 1 damage once per second and receives Nausea for 3 seconds. |
| `FLOODING` | Drain 20–30 real water blocks with an empty bucket. The interaction is consumed by the incident, so the bucket stays empty; the journal restores the footprint after completion. |
| `TRACK_DAMAGE` | Use the rail-working route: clear the collapse, replace missing rail sections in entrance order, then escort the checking minecart along the repaired line. |
| `CRYSTAL_RESONANCE` | Activate the glowing amethyst targets during the interaction window. |
| `CREATURE_NEST` | Defeat the spawned cave creatures and destroy each glowing nest core. Incident creatures and nests do not drop ordinary loot. |
| `LOST_MINER` | Find the marked natural-cave entry and click it to enter. Explore the cave with hostile creatures and click the glowing miner to complete the rescue and return. The exit marker also allows leaving early, and spectator flight is unrestricted. |

The physical incident owners are `MineCaveInIncident`, `MineGasLeakIncident`,
`MineFloodingIncident`, `MineTrackDamageIncident`, the crystal/creature owners,
`MineLostMinerIncident`, and the working controllers. Focused checks cover their interaction and recovery contracts; live activation
is tracked separately below.

## Physical workings

`TUNNEL_DRIVE`, `RAIL_EXTENSION` and `TRACK_DAMAGE` are the three rail/side-
working routes. A fresh route starts from a configured mine floor entrance,
which the player enters on foot. The temporary geometry and progress are
journaled before mutation and restored or resumed through the shared worksite
scene lifecycle.

| ID | Lifecycle | Service equipment |
| --- | --- | --- |
| `TUNNEL_DRIVE` | Right-click the drill cart to control the boring front, walk beside it while it cuts the real rock, then install supports at the completed front. | Drill cart and supports; neither becomes ordinary loot. |
| `RAIL_EXTENSION` | Clear the collapse, click the rail markers to lay one continuous ordered line from the entrance, then run the checking minecart along it. | Rails and checking cart; the engine rejects out-of-order segments. |
| `TRACK_DAMAGE` | Reuse the rail layout with the existing bed already present: clear rubble, replace damaged or missing segments, and run the checking cart. A persisted legacy incident without `working` remains readable by `MineTrackDamageIncident`. | Rails and checking cart; no new reward item. |

`ORE_WORKSHOP` is a separate fixed workshop incident, not a side-working
entrance. It uses the five mapped stations in order for three batches:
carry visible ore from `ore_input` to `ore_crusher`, walk three laps for the
three `CRUSH` strokes, heat for 4 seconds, right-click the furnace during the
following 4-second cooling window, then carry the visible billet from
`ore_output` to `ore_shipping`. A missed window reheats the same batch.

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
`ore_output (40.5,97,23.5)` and `ore_shipping (48.5,97,23.5)`. Administrators
can override or clear these runtime points with `/arcfarms admin point`;
clearing returns to the bundled fallback.

Temporary blocks, service items and working entities belong to the shared scene
and journal owners. Completion, cancellation, reload and restart reconcile the
saved stage before a new activity can use the floor. Supports, rails, ore,
billets and the checking cart are service materials only: no stage awards XP,
Vault money, tokens or ordinary loot. Existing order rewards and prices are
unchanged, including the source `old_shafts` completion of 220 XP and 4 iron.

## QA boundary

Release `0.40.38`, source `eb0e4fd`, is active on `classic` (the `spawn`
runtime) as of 2026-09-20 MSK. Its delivered JAR SHA-256 is
`9407e98132b267948b95f5cc9a7f4359a09bf78d8ec623d667b5582e4e7eeb40`.
The plugin reported ready after restart with an empty recovery backlog; the
legacy unfinished working returned to the existing mining order.

All 138 selected domain, scene, equipment, cadence, locale and recovery checks
pass. The same source cutoff passed the full
[unit/package and storage CI](https://github.com/alexey-va/ArcFarms/actions/runs/35469860832)
and [real Paper E2E](https://github.com/alexey-va/ArcFarms/actions/runs/35469860859).
The real compact-map fixture accepts 64 seeds on all three default floors for
each of the three working types, including rail headroom and crusher clearance.

A live QA player confirmed all five permanent station entities while no
incident was active, and inspected both workshop floors. Complete live event
journeys remain unverified: starting an incident requires an administrator
player, and the QA actor has not been granted that permission. Remaining live
checks include shrinking rubble glow, gas damage and nausea, empty-bucket
flooding, rescue entry/exit, drill-cart control, rail/cart repair, full workshop
production and point overrides. The viewer cannot render glow outlines and
has missing fallback item textures for grindstone/blast furnace, so its images
prove scene geometry only, not full Minecraft client fidelity.

## Related sources

- `src/main/kotlin/ru/ruscrafting/farms/domain/MineWorking.kt`
- `src/main/kotlin/ru/ruscrafting/farms/paper/mine/incident/MineIncidentScheduler.kt`
- `src/main/kotlin/ru/ruscrafting/farms/paper/mine/working/MineWorkingController.kt`
- `src/main/kotlin/ru/ruscrafting/farms/paper/mine/workshop/MineOreWorkshopController.kt`
- `src/main/resources/mine/compact-map-points.json`
