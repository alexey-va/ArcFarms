# ArcFarms

Standalone RusCrafting Paper plugin that turns the existing shared farm,
lumbermill, and regenerating mine into three cooperative activities.
It uses the existing worlds and WorldGuard regions; it does not add a season,
currency, payout, tract restoration, or another game mode.

Every active objective is deliberately solo-completable. If nobody is present,
the objective pauses in place and resumes from the same shared progress when a
player returns. Only a completed objective enters the short configured cooldown
before the next one becomes available.

## Player flows

### Harvest shift

Entering the farm starts a shared order and shows every unfinished crop with its
current and required amount in a boss bar. Only requested mature crops fill it,
and accepted crops are consumed by the order instead of dropping. At the
configured threshold silverfish spawn
inside the farm; players must defeat them before harvesting continues. The main
order progress remains intact. Resolving the outbreak starts a short
golden-harvest window that doubles one remaining crop. The shift ends with
participant and top-contributor recognition.

### Lumber order

The order names one requested wood species. Cutting that species fills a small
raw-timber quota. The activity then moves to processing: players use a
configured sawmill block inside the station region until the batch is complete.
Logs still drop normally, and processing does not consume inventory items.

### Mine expedition

Each configured mine is a route with its own block mix. Mining fills a shared
cart and triggers one instability phase. Players stabilize the face by
sneak-right-clicking a block with a pickaxe. Once the cart is full, a player
delivers it by leaving the mine. Broken
mine blocks are durably journaled before replacement and regenerate from the
configured weighted material table.

Farm incidents, mine instability, phase changes, and completions use localized
titles, boss bars, sounds, and particles. Completion fireworks are client-side
particles and sounds only: no firework entity, explosion, damage, or block
change is created.

## Network workday

ArcFarms uses its own `arc-core-redis` connection and protocol; it does not
load configuration, state, or APIs from ARC. Significant transitions can be
relayed between Paper servers through Redis, but player-facing network
announcements are disabled by default. When enabled, calls include a clickable
route to the configured worksite.

Every completed farm, lumber, or mine shift stamps the persistent network
workday. All three different stamps complete one cycle, trigger a cosmetic
network celebration when announcements are enabled, and immediately open the
next cycle. Atomic Redis compare-and-set prevents duplicate cross-server stamps.

## Commands

- `/arcfarms` — localized activity menu and current state.
- `/arcfarms status` — compact status for all configured zones.
- `/arcfarms top <farm|lumber|mine>` — contribution leaderboard.
- `/arcfarms travel <farm|lumber|mine>` — route to the exact configured server,
  world, and location.
- `/arcfarms reload` — validate and reload configuration/locales (admin).

The menu uses the same exact destinations as `/arcfarms travel`. Local routes
use Paper asynchronous teleportation. Remote routes store a short-lived Redis
ticket, switch the player through the proxy, and consume that ticket on the
destination backend before the world teleport.

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

The deployable artifact is `build/libs/ArcFarms-0.4.0.jar`.

## Isolated gameplay QA

`scripts/lab/plugin-configs/ArcFarms/config.yml` defines three small cuboid
fixtures. The player-bot session exposes only the fixed `arcfarms` operations
`fixture-setup`, `reload`, `travel`, `pest-stability`, `farm`, `lumber`, `mine`,
`status`, and `fixture-cleanup` on
the lab port and documented OP QA identities; it accepts no command or target
arguments. Always clean the scene after a smoke run.
