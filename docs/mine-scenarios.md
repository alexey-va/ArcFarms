# Mine scenarios

This document describes the mine incidents that exist in the current source
tree. The former version listed sixteen room scenarios that are not registered
by `MineIncidentType` and are not started by the scheduler; that catalog has
been removed.

## Evidence status

- **Source and focused tests** means the type is registered in
  `MineIncidentType`, listed by `MineIncidentScheduler.SUPPORTED_TYPES`, and
  has an incident owner with focused MockBukkit or domain coverage.
- **Controller source and component wiring** means the working controller,
  presentation, equipment, placement service, scheduler, and component-graph
  route exist in source. Live placement, restart recovery of an active event,
  and real-client interaction are unverified.
- Source/config evidence does not prove delivery, activation, or a successful
  player journey on `classic`; those remain separate checks.

### Delivery on 2026-09-19

- ArcFarms `0.40.37` was packaged from source commit `98afc87`; the configuration
  and locale cutoff is ops commit `2a3272b3b`.
- All 48 tests selected by `./scripts/test-mine-workings` passed. The complete
  test suite and live gameplay automation were not run.
- The canonical Minecraft 1.21.11 preview passed route and visual checks:
  14 route cells, no blocked steps, and estimated minimum block light 10.
  Placement feasibility uses the saved 2026-09-14 mine capture and is not a
  fresh player journey.
- Configuration transaction `deploy-20260919T180755Z-75252` applied exactly
  `config.yml`, `lang/ru.yml`, and `lang/en.yml` to `classic`.
- JAR transaction `jar-20260919T180823Z-75512` restarted `classic`, verified
  readiness and the active artifact, and retired `ArcFarms-0.40.36.jar` into
  its rollback backup. The plugin API reports `ArcFarms 0.40.37`, enabled.
- Artifact SHA-256:
  `d08a8b7808f2b45c66fd6280b04ba1a4b9c36fd95d961c9e6939fe1467477091`.

The new event journeys have not yet been completed with a real Minecraft
client. In particular, production interaction, progress recovery during an
active working, and player-visible lighting remain separate gameplay checks.

## Existing incidents

These eight incident IDs remain in the scheduler. `TRACK_DAMAGE` now shares
the physical rail-working route described below; the other seven keep their
own incident owners:

| ID | Physical sequence | Evidence |
| --- | --- | --- |
| `CAVE_IN` | Select a safe indexed front, journal the temporary rubble, clear it with a pickaxe, then restore the scene. | Source and focused tests: `MineCaveInIncident`, `MineBlockJournal`, `MineIncidentPlacementOrderTest`. |
| `GAS_LEAK` | Operate the indexed ventilation targets in sequence. | Source and focused tests: `MineGasLeakIncident`, `MineSequenceIncidentsMockBukkitTest`. |
| `FLOODING` | Interact with the real water target, drain its temporary footprint, and restore it. | Source and focused tests: `MineFloodingIncident`, `MineWorldIncidentsMockBukkitTest`. |
| `TRACK_DAMAGE` | On the laid rail line, clear the collapse, replace the missing rail sections, and escort the checking minecart. Fresh starts reuse the `RAIL_EXTENSION` working layout; a persisted incident without `working` remains on the legacy `MineTrackDamageIncident` path for save compatibility. | Source routing: `MineIncidentScheduler`, `MineIncidentSet`, and `MineWorkingController`; live world repair and legacy-save migration are unverified. |
| `CRYSTAL_RESONANCE` | Activate indexed amethyst targets with the forgiving interaction window. | Source and focused tests: `MineCrystalResonanceIncident`, `MineSequenceIncidentsMockBukkitTest`. |
| `CREATURE_NEST` | Defeat the spawned creatures and destroy each glowing nest core. | Source and focused tests: `MineCreatureNestIncident`, `MineEntityIncidentsMockBukkitTest`. |
| `POWER_FAILURE` | Activate the temporary light/power targets and restore the journaled lights. | Source and focused tests: `MinePowerFailureIncident`, `MineWorldIncidentsMockBukkitTest`. |
| `LOST_MINER` | Enter the temporary maze, find the miner, and return through the scoped entrance. | Source and focused tests: `MineLostMinerIncident`, `MineLostMinerMazeWorldMockBukkitTest`, `MineLostMinerMazeJournalCodecTest`. |

The ordinary mine order still resumes after an incident. Incidents do not
create a separate payout or ordinary resource quota.

## Lateral workings

The three new events begin at a validated floor entrance and open a temporary
side working that the player enters on foot. The geometry is journaled before
mutation; the entrance, stage, direction, and progress must survive reload or
restart, and unfinished scene records must restore before the next activity.
The domain contract is in `domain/MineWorking.kt`, with regression coverage in
`MineWorkingEngineTest`. `MineWorkingController`, `MineWorkingWorld`,
`MineWorkingPresentation`, `MineWorkingEquipment`, the placement service, the
scheduler, and the component graph own the source-level journey. Live world
placement, restart recovery during an active working, and client interaction are
**unverified**.

| ID | Lifecycle | Service-only materials | Evidence |
| --- | --- | --- | --- |
| `TUNNEL_DRIVE` | `EXCAVATE` the rock front in order, then `SUPPORT` the completed front. | Supports and temporary blocks. | Controller, placement service, scheduler, and component-graph route exist in source; domain progression is covered, while live placement and scene lifecycle are unverified. |
| `RAIL_EXTENSION` | `CLEAR_TRACK` the collapse, `LAY_TRACK` one continuous rail line from the entrance, then `TEST_TRACK` it with a checking minecart. | Rails, the test cart, and temporary blocks. | Controller and placement route exist in source; out-of-order rail placement is rejected by the engine, while live rail placement, cart escort, and recovery are unverified. |
| `ORE_WORKSHOP` | Repeat three batches: `LOAD` ore, perform three `CRUSH` strokes, `HEAT` for 4 seconds, cool during the following 4-second window, then `SHIP` the billet at the entrance. A missed window automatically starts another heat attempt for the same batch. | Ore, the billet, and workstation interactions. | Controller and placement route exist in source; three-batch and heat-window behavior are covered by `MineWorkingEngineTest`, while live workshop placement and recovery are unverified. |

The working is a foreground incident in the current order. Completing it
returns the party to the same order and preserves ordinary mining progress.
None of the service items become player loot, and no working stage awards a
separate item, XP, Vault payment, token, or price discount.

## Scheduling and placement

Order incident selection remains deterministic for a shift, while the
incident thresholds are distributed across the mining quota. For three
scheduled incidents and a 100-block quota, the tested thresholds are 25, 50,
and 75 rather than draining the schedule at the halfway point. A failed
placement is retried with bounded diagnostics; it must not silently create a
partial scene. The source contract is exercised by
`MineIncidentCadenceTest`.

The current working presentation resolves these canonical locale paths:
`mine.incident-name.<type>`, `mine.guidance.<type>`,
`mine.working.item.<role>`, `mine.working.marker.<label>`,
`mine.working.stage.<label>`, `mine.working.hint.<stage>`, `mine.working.inventory-full`,
`mine.working.preparing`, `mine.working.next-section`,
`mine.working.heat-wait`, `mine.working.heat-ready`, and the stage feedback
paths under `mine.working.*`. The required `needs-kit`, `heat-missed`, and
`returned` entries are present for the remaining lifecycle feedback but are
not direct `renderPath` calls in the current controller. Marker values carry
the `floor` placeholder; heat guidance carries `seconds`, and workshop
guidance carries `batch` and `batches`.

The bundled mine pools keep their existing incident IDs and add
`TUNNEL_DRIVE`, `RAIL_EXTENSION`, and `ORE_WORKSHOP`. Zone-specific runtime
profiles can still narrow their pools. Pool entries and source wiring do not
prove deployment, activation, or a successful player journey on the active
server.

## Recovery and player safety

Temporary side-working blocks use the shared worksite journal/scene owner.
Completion and cancellation return players to the floor before idempotent
restoration. Reload and restart preserve an unfinished incident's stage and
complete journal, so it can resume; orphaned records restore on activation.
The entrance is on the selected floor. A scene may not overlap a
lift, another journal, an occupied block, an unloaded chunk, or an unsafe
front. Exact live geometry, restart recovery, and real-client affordances are
still deployment/QA evidence, not claims made by this source document.

Run `./scripts/test-mine-workings` for the focused domain, scene, equipment,
reload and return-recovery checks. The script uses class discovery compatible
with Kotest 6.0.7 and does not run the repository's storage integration suite.

## Related sources

- `src/main/kotlin/ru/ruscrafting/farms/domain/MineShift.kt`
- `src/main/kotlin/ru/ruscrafting/farms/domain/MineWorking.kt`
- `src/main/kotlin/ru/ruscrafting/farms/paper/mine/incident/MineIncidentScheduler.kt`
- `src/main/kotlin/ru/ruscrafting/farms/paper/worksite/scene/WorksitePreparedScene.kt`
- `src/test/kotlin/ru/ruscrafting/farms/domain/MineWorkingEngineTest.kt`
- `src/test/kotlin/ru/ruscrafting/farms/paper/mine/incident/MineIncidentCadenceTest.kt`
