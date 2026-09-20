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
| `TUNNEL_DRIVE` | Mount the drilling machine with right-click; drive with W/S and steer with A/D. Bore to the goal around alternating bedrock ribs; overhead lights are installed along the cleared route. | Native carrier and animated display body; excavation produces no ordinary drops. |
| `RAIL_EXTENSION` | Clear the collapse, click the rail markers to lay one continuous ordered line from the entrance, then run the checking minecart along it. | Rails and checking cart; the engine rejects out-of-order segments. |
| `TRACK_DAMAGE` | Reuse the rail layout with the existing bed already present: clear rubble, replace damaged or missing segments, and run the checking cart. A persisted legacy incident without `working` remains readable by `MineTrackDamageIncident`. | Rails and checking cart; no new reward item. |

`ORE_WORKSHOP` is a separate fixed workshop incident, not a side-working
entrance. It uses the five mapped stations in order for three batches: carry
ore from `ore_input` to `ore_crusher`, walk three laps for the three `CRUSH`
strokes, heat for 4 seconds, right-click the furnace during the following
4-second cooling window, then carry the billet from `ore_output` to
`ore_shipping`. A missed window reheats the same batch.

The active working contract provides at least 60 seconds of grace, an 8-block
clear area around the cave, a five-minute deadline and a 15-second warning.
Entering the scene has no cancel action. The scene brightness contract is
15/15. The lateral working geometry is a 24-cell cave including the entry,
with noise width 7–9, height 5–7 and maximum forward 14 in the bounded region
`x=72..87, z=12..41`; it opens into large chambers with decoration. Rescue
remains a separate off-site noise cave with unchanged dimensions; its QA
lifecycle is documented below. These are geometry rules, not a final
client-visual claim.

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

The final source cutoff is `b167f5a7d8bee2cf8d7e6f1fe90c1067ed76b907` and is
pushed. The final report has 167 tests, 166 passes, 0 failures and 1 known
skip. CI run `35475982937` and Paper E2E run `35475982915` both succeeded.

ArcFarms `0.40.40` is active on `classic`: activation was observed on 2026-09-19 at 23:32:24
UTC, the server reported ready at 23:32:54 UTC, PID `2953352`, JAR SHA-256
`6eb8a6d3f66d750848462984a1bda956ca8633852e61aac2702c1dfcf9d0121d`, size
14,267,145 bytes. The final JAR transaction is
`jar-20260919T233015Z-44971`; locale transaction
`push-20260919T231439Z-36106` was classic-only.

A subsequent coordinated ARC restart completed at 23:49:29 UTC; that server
PID was `2963053`, and ArcFarms `0.40.40` remained enabled with native mine
workings available.

After the ARC174 restart, the current runtime became ready at 00:09:41 UTC on
2026-09-20 with PID `2983408`. The remote JAR hash remained
`6eb8a6d3f66d750848462984a1bda956ca8633852e61aac2702c1dfcf9d0121d`, size
14,267,145 bytes; the plugins API reported ArcFarms `0.40.40` enabled and OK,
with server API at 20 TPS and Redis connected.

Native `0.40.40` working readback covered the `ORE_WORKSHOP` to
`RAIL_EXTENSION` transition with nonce 92 and one title. In survival, the
player entered at `(70.5,111,27.5)` and walked continuously to
`(79.34648,111,27.5)` without rollback; server NBT confirmed the path. Scene
brightness NBT was 15/15. Visual inspection of geometry version 3 passed with
the full pack; valid captures are
[working-eye.png](https://github.com/alexey-va/ruscrafting-ops/blob/main/docs/assets/mine-20260920/working-eye.png)
and
[working-orbit.png](https://github.com/alexey-va/ruscrafting-ops/blob/main/docs/assets/mine-20260920/working-orbit.png).
The viewer does
not render glow outlines, so outline evidence is limited to server/protocol
metadata and is not a client-outline claim.

The active lateral working geometry is a 24-cell cave including the entry, with
noise width 7–9, height 5–7 and maximum forward 14 in `x=72..87, z=12..41`.
Rescue remains a separate off-site noise cave with unchanged dimensions.

`RAIL_EXTENSION` completed natively: 3/3 cobble targets became AIR, 12/12 rail
markers accepted right-clicks, and 12/12 cart-escort checks completed (27/27).
It finished around 23:44:09 UTC and preserved `MINING` progress 35/448. The
player remained at `(80.35,111,25.43)` through 23:45:40 UTC (over 90 seconds);
the cave remained present and the radius-3 readback contained 8 rail,
4 spruce_log, 2 lantern and 2 iron_chain.

The native exit reached `(68.3128,111,28.7099)`, beyond entry 72, before the
pathfinder stuck on the main map; QA was disabled for the coordinated ARC
restart. After QA disconnected at 23:47:32 UTC, the compact-world console
probe `execute in compact if block 81 111 24 minecraft:stone run time query
gametime` returned `The time11904811`, and the former rail was restored to
STONE. This proves retention while inside and restoration after logout; it does
not prove the `>8` online-player condition. The corresponding condition is
covered by a unit test.

`TUNNEL_DRIVE` nonce 93 used the bottom entrance `(72,82,27)`. Native cart
control used `Interaction` `2632`; the cart reached 27/93 at 23:52:01 UTC at
`(76.5,83,27.5)` with head Y=83.82, then 54/93 at `(79.5,83,24.5)` with the
same head Y, without a cart-head jump. All 90/90 drill actions and 3/3 support
actions completed through native interactions at 23:57:29 UTC (93/93 total),
returning `MINING` progress 35/448. Support click entities were `2706`, `2708` and
`2704`; the last was 2.28 blocks from player feet at `(75.5,83,30.5)`. QA
positioning used console teleports after pathfinder stalls, including
`(77.5,83,31.5)`, `(70.5,83,27.5)` and `(75.5,83,30.5)` in addition to the
initial approach; gameplay progression used native interactions. This is not a
claim of a fully native walk route.

The wood-block display had brightness NBT 15/15 at `(75,84,33)` (console
readback 23:53:12 UTC). For the live completion-distance, grace and clear-area check, after
completion QA teleported to the workshop at `(40.5,111,25.5)` at 23:58:20 UTC;
the support was still `SPRUCE_LOG` at 23:58:24 UTC (under 60 seconds), then was
`DEEPSLATE` at 23:58:46 UTC, matching the original fixture. The console probe
`execute if block 75 84 33 minecraft:spruce_log run time query gametime`
returned `The time11916030`. This verifies the live minimum-60-second retention
and clear-area restoration after teleport departure; it does not prove a
walking threshold path.

`LOST_MINER` nonce 94 survived the coordinated restarts. At 00:11:18 UTC,
native `Interaction` `1939` clicked the entry from 1.61 blocks and led to
`(43.5,87,102.5)`. Native movement reached `(53.5,87,110.55)`; at 00:11:54
UTC, native villager `1942` interaction from 1.76 blocks returned the order to
`MINING` 35/448 without eject. At 00:12:24 UTC the player was still at the
miner location and the mobs/miner had been removed.

The return pathfinder stalled, so at 00:13:11 UTC QA used a console teleport to
the verified AIR cell `(43.5,87,104.5)`. Creative mode protected the QA actor
from mobs throughout this rescue pass; native combat was not tested. At
00:13:44 UTC the cave was still present at `(43.66,87,104.56)`; exit
`Interaction` `1941` and glowing `BlockDisplay` `1940` remained for more than
110 seconds after completion. At 00:13:45 UTC a native exit click from 1.89
blocks reached surface `(34.5,87,11.5)` without immediate ejection. Cleanup
ran in survival at 00:14:16 UTC; at 00:14:21 exactly one test bucket was
removed. Both temporary permission nodes had expired, confirmed by an unset
readback, and the bot quit at 00:14:30 UTC. This proves the native entry,
find-miner, completion, retention and exit lifecycle; the return positioning
included a console teleport and is not a fully native walk claim.

The active immutable JAR remains
`6eb8a6d3f66d750848462984a1bda956ca8633852e61aac2702c1dfcf9d0121d`.
At 00:15 UTC the order readback was `MINING`, `incident=null`, `mined=35`,
`cart=35`, `sequence=5`, `incidentCursor=94`. At 00:15:51 UTC `ARC_HEALTH`
reported `up/ready=true`, `recovery_backlog=0`, `active_leases=0` and
Redis/service `true`; bot offline/online `0` was confirmed after the 00:14
quit. Physical off-site restoration to AIR was not separately confirmed.

### Historical QA

Historical `.39` native checks remain recorded for `CAVE_IN` 56/56,
`FLOODING` 30/30 with an empty bucket, `TRACK_DAMAGE` 18/18 and
`TUNNEL_DRIVE` 90/93 where the remaining three supports were partial work by
another player, so no solo completion was claimed. Historical `.39` rescue
checks covered native entry/exit and spectator flight Y=87 to Y=98 with server
NBT, but immediate eject after completion reproduced in the historical `.39`
build; that result is superseded by the final nonce 94 readback above.
Historical `.39` creature rotation
used distinct nonce 89/90 layouts; nest-kill was not tested. Historical
`.40.38` checks covered the three-batch workshop, gas with two required clicks
among four candidate vents, resonance, and the earlier rescue captures.

The `.39` workshop carry readback used `Interaction` `1965` and glowing
`raw_iron` `ItemDisplay` `3128` at `(40.5,112,24.75)`, with player feet at
`(40.5,111,25.5)`: the carry was exactly +1 Y and the old below-feet issue was
fixed. The older `rescue-cave-orbit` frame used the wrong scene and is excluded.

## Related sources

- `src/main/kotlin/ru/ruscrafting/farms/domain/MineWorking.kt`
- `src/main/kotlin/ru/ruscrafting/farms/paper/mine/incident/MineIncidentScheduler.kt`
- `src/main/kotlin/ru/ruscrafting/farms/paper/mine/working/MineWorkingController.kt`
- `src/main/kotlin/ru/ruscrafting/farms/paper/mine/workshop/MineOreWorkshopController.kt`
- `src/main/kotlin/ru/ruscrafting/farms/paper/mine/point/MinePointService.kt`
- `src/main/kotlin/ru/ruscrafting/farms/persistence/MineLocationRepository.kt`
- `src/main/resources/mine/compact-map-points.json`
- `plugins/ArcFarms/data/mine-locations.json` (runtime override store)


## Drivable passage and rescue repair — 0.44.4

New `TUNNEL_DRIVE` scenes use geometry version 5: a 44-block journaled rock
volume with two alternating bedrock ribs. Right-click the machine to board its
native carrier, use W/S to drive and A/D to steer, and Shift to dismount. The
49-part client display body follows the carrier; the cutter spins during work,
with rock particles and drilling sounds. The cutter reveals bedrock without
breaking it. Sparse hanging lamps follow the excavated route. Cell IDs, lamps,
checkpoint and heading are saved asynchronously before block changes. Geometry
version 4 scenes already in progress keep their original layout and controls.

Lost Miner completion uses a nearby valid click on the NPC, independent of a
stale portal return receipt. Flying within the cavern keeps the player inside
its volume. Larger rescue caves use deterministic branching curved routes and
spatially separated lamps. Existing journaled caves retain their built blocks.

Admin edit now bypasses the early mine left-click gate as well as damage/break
gates. Teleports, portals and zone changes retain the explicit edit session;
quit, death, reload and shutdown still clear it. Foreign cancellations are not
uncancelled.

Verification: 74 focused Kotest checks, no failures or skips, including delayed
save/failure/stale callbacks, journal reconstruction/restoration, repository
roundtrip, all four drill orientations, 16 rescue seeds, miner clicks without a
return receipt, and admin edit interaction/lifetime. Display validation inspected
20 models over 65 poses with no coplanar overlap. `shadowJar` produced 0.44.4.
Browser preview inspected the canonical drill model from the front and rear.
Native steering smoothness, riding camera, sounds and particles still need a
Minecraft player pass; these are not established by unit tests or browser QA.


CI follow-up: the first 0.44.4 run passed Paper E2E and storage integration.
The complete unit job reported 25 failures; comparison with the 0.44.3 run
identified the same 23 prior failures plus two old-drill fixtures inadvertently
using the new default geometry. Those two fixtures now explicitly use version
4. The expanded local selection passed 78 checks with no failures or skips.
The 23 prior CI failures remain unresolved; the full suite is not green.
