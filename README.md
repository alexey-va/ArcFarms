# ArcFarms

Standalone RusCrafting Paper plugin that turns the existing shared farm,
lumbermill, and regenerating mine into three short cooperative activities.
It uses the existing worlds and WorldGuard regions; it does not add a season,
currency, payout, tract restoration, or another game mode.

## Player flows

### Harvest shift

Harvesting a mature crop starts a shared order. Only the requested crops fill
the order. At the configured progress threshold a short golden-harvest window
temporarily doubles one remaining crop. The shift ends with participant and
top-contributor recognition; normal crop drops remain the material outcome.

### Lumber order

The shift names one requested wood species. Cutting that species fills the raw
timber quota. The activity then moves to processing: players use a configured
sawmill block inside the station region until the batch is complete. Logs still
drop normally; processing does not consume inventory items.

### Mine expedition

Each configured mine is a route with its own block mix. Mining fills a shared
cart and eventually triggers an instability phase. Players stabilize the face
by sneak-right-clicking a block with a pickaxe. Once the cart is full, one
contributor must leave the mine before the extraction timer expires. Ore is
kept even if extraction fails. Broken mine blocks are durably journaled before
replacement and regenerate from the configured weighted material table.

## Commands

- `/arcfarms` — localized activity menu and current state.
- `/arcfarms status` — compact status for all configured zones.
- `/arcfarms top <farm|lumber|mine>` — contribution leaderboard.
- `/arcfarms reload` — validate and reload configuration/locales (admin).

The menu navigation buttons run the configured existing warp commands. World
interaction remains the real entry point; the menu does not start or complete
a shift.

## Runtime ownership

- Tracked configuration: `plugins/ArcFarms/config.yml`.
- Bundled locale defaults: `lang/ru.yml`, `lang/en.yml`.
- Server-owned state: `plugins/ArcFarms/data/`.
- WorldGuard is required. A zone may use a named WorldGuard region or explicit
  cuboid bounds (the latter is primarily for the isolated lab).

## Build

```bash
../arc-core/gradlew -p . clean check shadowJar
```

The deployable artifact is `build/libs/ArcFarms-0.1.0.jar`.

## Isolated gameplay QA

`scripts/lab/plugin-configs/ArcFarms/config.yml` defines three small cuboid
fixtures. The player-bot session exposes only the fixed `arcfarms` operations
`fixture-setup`, `farm`, `lumber`, `mine`, `status`, and `fixture-cleanup` on
the lab port and documented OP QA identities; it accepts no command or target
arguments. Always clean the scene after a smoke run.
