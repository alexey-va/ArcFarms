# Mine scenarios

Ordinary orders count matching mined blocks, without a highlighted objective pool.
The scheduled incident starts automatically after half the order quota. Its
completion resumes the same order and mining progress; stages do not grant
separate items, money or XP.

## Reused owners

- `MineIncidentCoordinator` and `ObjectiveTargetPool`: interruption, contribution,
  progress, exclusive carried targets and order resumption.
- `WorksiteGuidancePresenter`: automatic bossbar, sidebar and target guidance.
- `WorksiteEntryMarker` and `WorksiteExpeditionTravel`: clickable entrances,
  durable return intent and scoped teleport authorization; the farm uses these
  same owners through its existing adapters.
- `FarmMoleBurrowWorld.ensurePreparedScene`: journaled temporary geometry and
  restoration, shared with farm underground rooms.
- `WorksiteCarriedDisplayRenderer`: the same renderer used by farm delivery.
- Existing mine cart/entity effects and the real `MineLiftAccess`: visuals,
  passengers, landing safety and exclusive maintenance.

The mine-specific controller owns stage verbs and floor placement. It does not
introduce a second farm runtime, reward ledger or inventory delivery system.

## Event catalog

| ID | Physical sequence |
| --- | --- |
| `CREATURE_NEST` | Defeat raiders; destroy the nest core |
| `CAVE_IN` | Clear rubble; install supports |
| `FLOODING` | Start pumps; remain at the drain control |
| `GAS_LEAK` | Open vents in order; operate ventilation |
| `POWER_FAILURE` | Replace a fuse; reconnect circuits in order |
| `INJURED_MINER` | Free the miner; carry them to another floor |
| `RUNAWAY_CART` | Time switch changes; brake; escort and deliver across floors |
| `CONVOY` | Clear the track; escort and deliver across floors |
| `LIFT_BREAKDOWN` | Secure access; carry a repair part; test the repaired lift |
| `BAT_SWARM` | Activate the lure; close the cleared passage |
| `FUNGAL_BLOOM` | Collect samples; clear spores |
| `ROOT_INVASION` | Clear roots; remove their heart |
| `LAVA_BREACH` | Carry barriers; close sluices in order |
| `ANCIENT_DOOR` | Carry a gear; operate the lock |
| `OLD_WAREHOUSE` | Recover crates; operate the winch |
| `DRILL_TRIAL` | Deliver coolant; hold the controls; break through |

## Floors and recovery

Placement chooses a lift floor near eligible participants. Cross-floor delivery
requires another configured floor in the same world. Entrances cannot overlap
an active entrance within six blocks. Lift maintenance waits for a safe stop
and cannot overlap cross-floor delivery.
Abandoned lift maintenance expires after three minutes, releases the lift and
resumes the same ordinary order without awarding incident completion credit.

The bundled standalone configuration enables the twelve events that do not
require an enabled lift. The spawn runtime profile enables all sixteen. Keep
lift-dependent events out of profiles without a matching surveyed lift.

Rooms use the checked-in editable `mine/events/room.atelier.json`, compiled block
data and route metadata. Placement uses existing chunks only, preserves original
block data and rejects unsafe occupied volumes. Temporary blocks are protected
from normal mining, placement, fluid and explosion changes. Players leave before
restoration; unfinished return/restore intent survives shutdown.

Offline and expired cargo leases are reclaimed without losing completed targets
or contributions. Unauthorized teleports release participation before movement
can count as delivery. An escorted cart must finish its chamber route before a
destination-floor arrival can complete the event.

## Gameplay checks

Mine and mine-lift E2E scenarios have been removed while the mine mechanics are
unfinished. Reintroduce gameplay coverage against the finished mechanics rather
than the historical room scenarios described above. Existing unit and storage
integration checks remain in place; `./scripts/test-mine-lift` runs the focused
lift unit tests, not a Paper E2E server.
