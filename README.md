# ArcFarms

Standalone RusCrafting Paper plugin that turns the existing shared farm,
lumbermill, and regenerating mine into three persistent cooperative activities.
It uses the existing worlds and WorldGuard regions; it does not add a season,
currency, payout, tract restoration, or another game mode.

Every active objective is deliberately solo-completable and has no inactivity
deadline. Zero players may leave it untouched indefinitely; the exact shared
progress resumes when somebody returns. Only a completed objective enters the
short configured cooldown before the next one becomes available.

## Player flows

### Harvest shift

Harvesting a mature crop starts a small shared order. Only the requested crops
fill it. At the configured threshold a non-destructive pest outbreak becomes a
second, persistent rescue counter over one requested crop; it never removes or
blocks the main order progress. Resolving it starts a short golden-harvest
window that doubles one remaining crop. The shift ends with participant and
top-contributor recognition; normal crop drops remain the material outcome.

### Lumber order

The order names one requested wood species. Cutting that species fills a small
raw-timber quota. The activity then moves to processing: players use a
configured sawmill block inside the station region until the batch is complete.
Logs still drop normally; processing does not consume inventory items, and both
phases wait indefinitely.

### Mine expedition

Each configured mine is a route with its own block mix. Mining fills a small
shared cart and triggers one instability phase. Players stabilize the face by
sneak-right-clicking a block with a pickaxe. Once the cart is full, any later
player can deliver it by leaving the mine; there is no extraction timer. Broken
mine blocks are durably journaled before replacement and regenerate from the
configured weighted material table.

Farm incidents, mine instability, phase changes, and completions use localized
titles, boss bars, sounds, and particles. Completion fireworks are client-side
particles and sounds only: no firework entity, explosion, damage, or block
change is created.

## Network workday

ArcFarms uses its own `arc-core-redis` connection and protocol; it does not
load configuration, state, or APIs from ARC. Significant transitions only
(incidents, rescue/processing/extraction calls, and completions) are relayed to
all Paper servers. Calls include a clickable route to the spawn worksites.

Every completed farm, lumber, or mine shift stamps the persistent network
workday. All three different stamps complete one cycle, trigger a cosmetic
network celebration, and immediately open the next cycle. Stamps have no
deadline and no inactivity reset, so one player may complete all three over any
amount of time. Atomic Redis compare-and-set prevents duplicate cross-server
stamps.

## Commands

- `/arcfarms` — localized activity menu and current state.
- `/arcfarms status` — compact status for all configured zones.
- `/arcfarms top <farm|lumber|mine>` — contribution leaderboard.
- `/arcfarms reload` — validate and reload configuration/locales (admin).

The menu navigation buttons run the configured existing warp commands. World
interaction remains the real entry point; the menu does not start or complete
a shift.

## Runtime ownership

- Tracked configuration, Redis profile, and active locales:
  `plugins/ArcFarms/{config.yml,modules/redis.yml,lang/{ru,en}.yml}` on
  `classic`, `classic_survival`, and `parkour`.
- `classic` owns all gameplay zones. The survival and parkour installs have no
  zones and act only as network relays.
- The same locale files are bundled as first-install defaults in the JAR.
- Server-owned state: `plugins/ArcFarms/data/`.
- WorldGuard is required only on a gameplay node that names WorldGuard regions.
  Empty relay nodes and explicit-cuboid lab profiles load without it.

## Build

```bash
../arc-core/gradlew -p . clean check shadowJar
```

The deployable artifact is `build/libs/ArcFarms-0.3.1.jar`.

## Isolated gameplay QA

`scripts/lab/plugin-configs/ArcFarms/config.yml` defines three small cuboid
fixtures. The player-bot session exposes only the fixed `arcfarms` operations
`fixture-setup`, `farm`, `lumber`, `mine`, `status`, and `fixture-cleanup` on
the lab port and documented OP QA identities; it accepts no command or target
arguments. Always clean the scene after a smoke run.
