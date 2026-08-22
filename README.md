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

Entering the farm starts a shared order by selecting a compact patch of existing
farm beds. The configured target is 100 plots on spawn; if the farm contains a
smaller connected patch, every available plot is used, while zero suitable
plots leaves the shift idle instead of creating an impossible objective. The
chosen coordinates are persisted before the plugin clears their crops and
returns the soil to dirt. Players till every marked plot with a hoe, then plant
the requested crop with its matching seed item. Seeds act as a tool and are not
consumed. Selected and previously discovered beds are kept at maximum farmland
moisture and protected from trampling and drying.

Patch selection rotates between spatially distinct beds instead of always
starting beside the entering player. Beds are connected only on one height,
with a one-block irrigation channel allowed between rows. A selected bed grows
from the configured target to its full connected shape when that shape fits
under `preparation-patch-max-size`; larger fields remain hard-bounded. Recovery
may add the missing edge of an old partial bed without discarding existing
tilling or planting progress.

The active patch, tilling progress, and planting progress survive an empty
farm, chunk unload, plugin reload, or process restart. Recovery replays an
unfinished patch release idempotently and reconciles already tilled or planted
blocks before accepting more actions. Once the patch is planted, the boss bar
shows every unfinished crop with its current and required amount. Only
requested mature crops fill it, and accepted crops are consumed by the order
instead of dropping.

Three configured free-floating item displays stand on the path near the farm
entrance, each with a short text label and interaction hitbox but no barrel or
base block. During the matching phase they issue a tagged unbreakable hoe, the
required seeds, or one infinite service water bucket. These tools cannot be
dropped, stored in another
inventory, used for unrelated farm changes, carried outside the farm, or moved
to another backend; ArcFarms removes them at every such boundary.

At the configured threshold one of the farm incidents starts. A pest outbreak
spawns glowing silverfish that eat nearby managed crops until defeated. A
drought creates several widely separated patches of visibly dry dirt and
removes their dead plants. The total dry area is derived from the active garden
size, clamped by configured minimum and maximum bed counts, and distributed
between the configured number of patches. The service bucket may be poured on a
replaceable block within flow reach instead of only on the dry block itself. It
creates real flowing water: the flow spreads, hydrates only the dry beds it
actually reaches, and breaks plants
using normal water physics without leaving duplicate crop drops. ArcFarms tracks
and removes every temporary flow after each pour. Managed plants remain absent
until the entire drought is resolved, then their captured state is restored at
once. Either incident pauses harvesting
without resetting the main order. Resolving it starts a short golden-harvest
window that doubles one remaining crop. When the crop quota is ready, an
configured set of interactive harvest crates appears at the last crop. Players
carry their visual displays to the configured receiving point; leaving the farm
returns only the carried crate, while the shared delivery objective remains
available indefinitely. Delivering every crate completes the order, restores
the selected beds to their captured pre-shift state, awards configured
experience to online contributors, and triggers participant and top-contributor
recognition.

Every modified bed is recorded before mutation in the owning chunk's Paper PDC
with its exact coordinates, original soil and crop block data, and current
recoverable crop state. Startup reconciliation uses those records after a hard
stop. `/arcfarms admin edit` is available only between active shifts and lets an
administrator deliberately remove a bed and its ArcFarms record. Phase-colored
particle columns mark the active patch from a distance; drought and delivery
use their own local action areas.

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
- `/arcfarms admin edit` — toggle deliberate farm-bed deletion and PDC cleanup
  between shifts (admin).

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
../ARC/gradlew -p . clean check shadowJar
```

The deployable artifact is `build/libs/ArcFarms-0.5.0.jar`.

## Isolated gameplay QA

`scripts/lab/plugin-configs/ArcFarms/config.yml` defines three small cuboid
fixtures. The player-bot session exposes only the fixed `arcfarms` operations
`fixture-setup`, `reload`, `travel`, `pest-stability`, `farm`, `lumber`, `mine`,
`status`, and `fixture-cleanup` on
the lab port and documented OP QA identities; it accepts no command or target
arguments. Always clean the scene after a smoke run.
