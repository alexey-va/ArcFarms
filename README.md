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
switches to one randomly selected field-care story before harvesting begins.
Every story is spread across the active bed instead of clustering around its
center:

- weeds place several stubborn glowing roots that need two hoe strikes each;
- irrigation exposes a chain of valves that must be opened in order;
- pollination asks the player to collect two charges from a hive and carry them
  to distant flower patches, returning to the hive as needed;
- storm preparation distributes cover anchors around the actual field corners;
- scarecrow duty asks the player to assemble several decoys in separate parts
  of the field;
- animal rescue spawns glowing tagged farm animals, attaches a visible leash
  when a player calls one, and leads them to the highlighted barn.

The current instruction and exact progress remain in the boss bar. The next
useful target has one restrained long-range particle column, while nearby
targets use glowing models and small local feedback. There is no failure timer:
an empty farm, one player, disconnect, chunk unload, or restart leaves the same
shared objective waiting. Finishing field care starts normal harvesting, whose
boss bar shows every unfinished crop with its current and required amount. Only
requested mature crops fill it, and accepted crops are consumed by the order
instead of dropping.

Care fixtures do not assume that the map already contains hives, valves,
covers, scarecrows, or animals. By default ArcFarms finds a real hive where one
exists and otherwise creates temporary, tagged display fixtures and animals at
safe points derived from the selected field. `procedural-care-fixtures: false`
turns that behavior off for fixture-dependent stories; those stories are then
skipped until an administrator saves the required point. The optional points
are `hive`, `irrigation`, `covers`, `scarecrows`, and `barn`. The barn falls
back to the crop receiving point until an administrator saves its own override.
Their entities are
removed when the story ends and are reconstructed from persisted state after a
restart.

Dynamic animals and delivery crates prefer safe ground at least
`placement-min-objective-distance` blocks from their destination while staying
within `placement-max-player-distance` of a current participant. Candidate
search is capped by `placement-search-radius`; constrained fixtures degrade to
the best available safe position instead of making a story impossible.

Three configured free-floating item displays stand on the path near the farm
entrance, each with a short text label and interaction hitbox but no barrel or
base block. During the matching phase they issue a tagged unbreakable hoe, the
required seeds, or one infinite service water bucket. These tools cannot be
dropped, stored in another
inventory, used for unrelated farm changes, carried outside the farm, or moved
to another backend; ArcFarms removes them at every such boundary.

At the configured threshold one of the farm incidents starts. A pest outbreak
places several breakable nests across distant parts of the field. Each nest can
spawn only a configured number of glowing silverfish, with a separate cap on
simultaneously living pests. The pests eat any configured crops around them to
open visible fighting space. Every eaten crop returns at its first growth stage
only after all nests and pests are gone. A
drought creates several widely separated patches of visibly dry dirt and
removes their dead plants. The total dry area is derived from the active garden
size, clamped by configured minimum and maximum bed counts, and distributed
between the configured number of patches. The service bucket may be poured on a
replaceable block within flow reach instead of only on the dry block itself. It
creates real flowing water: several pours may coexist, each flow spreads up to
normal vanilla reach, hydrates only the dry beds it actually reaches, and clears
plants without creating crop or seed drops. ArcFarms tracks and removes every
temporary flow after each pour. Managed plants remain absent
until the entire drought is resolved, then their captured state is restored at
once. Either incident pauses harvesting without resetting the main order.
Resolving it resumes the ordinary crop order at the next unfinished crop. When
the crop quota is ready, a configured set of interactive harvest crates appears
at the last crop. Players
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

Each farm may configure one streamed background track. ArcFarms plays it from
the player's own Adventure sound emitter while they remain inside the farm,
stops it on every exit/reload/shutdown boundary, and restarts it only after the
configured duration. A per-player session guard prevents movement and the
periodic region check from starting the same track twice.

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
- `/arcfarms admin point <zone> <tool|seeds|water|crates|receiving|travel|hive|irrigation|covers|scarecrows|barn>` —
  save the administrator's current world, coordinates, yaw, and pitch for a farm
  operation point. Non-travel points must be inside the farm and off crop beds.
- `/arcfarms admin points <zone>` — list the effective configured and overridden
  farm points.
- `/arcfarms admin stage <zone> <preparation|planting|harvesting|weeds|irrigation|pollination|covers|scarecrows|animals|pests|drought|delivery|complete|reset>` —
  switch the current farm to an exact QA stage while preserving normal recovery.
- `/arcfarms admin next <zone>` — advance to the next useful QA stage.
- `/arcfarms admin event <zone> <pests|drought>` — start an exact incident.
- `/arcfarms admin care <zone> <weeds|irrigation|pollination|covers|scarecrows|animals>` —
  start one exact field-care story, or report that its required fixture cannot
  be placed.
- `/arcfarms debug <zone> status` — print the exact shift, patch, crop damage,
  water-flow, care targets, animal followers, nest, pest, and delivery state
  used by the server.
- `/arcfarms debug <zone> stage <stage>` / `event <pests|drought>` / `next` —
  force a deterministic QA transition without waiting for random gameplay.
- `/arcfarms debug <zone> care <weeds|irrigation|pollination|covers|scarecrows|animals>` —
  build a selected care scene immediately for visual and interaction QA.
- `/arcfarms debug <zone> give <tool|seeds|water>` — issue the tagged service
  item for the requested interaction, even before that stage is active.
- `/arcfarms debug <zone> show` — repeat active-target and configured-point
  columns for five seconds; `points` lists exact coordinates and `reset`
  removes temporary entities/water and restores managed blocks.

## PlaceholderAPI

When PlaceholderAPI is installed, ArcFarms exposes the persistent all-time farm
contribution table for CMI holograms or Citizens scenes:

- `%arcfarms_farm_top_1_name%` through rank `50`;
- `%arcfarms_farm_top_1_skin%` — the same resolvable account name for a skin
  provider, falling back to the UUID;
- `%arcfarms_farm_top_1_uuid%` and `%arcfarms_farm_top_1_score%`;
- `%arcfarms_farm_score%` and `%arcfarms_farm_rank%` for the viewing player.

Replace `1` with the desired rank. Missing ranks return an empty string.

The menu uses the same exact destinations as `/arcfarms travel`. Local routes
use Paper asynchronous teleportation. Remote routes store a short-lived Redis
ticket, switch the player through the proxy, and consume that ticket on the
destination backend before the world teleport. Empty menu slots may use a
server-owned `ui.menu-background` Material/CMD style; the bundled default leaves
that override disabled. Reload remains available as a command, not as a GUI
button.

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
../arc-core/gradlew clean check shadowJar
```

The deployable artifact is `build/libs/ArcFarms-0.10.0.jar`.

## Isolated gameplay QA

`scripts/lab/plugin-configs/ArcFarms/config.yml` defines three small cuboid
fixtures. The player-bot session exposes only the fixed `arcfarms` operations
`fixture-setup`, `reload`, `travel`, `debug-controls`, `care-stories`, `drought-flow`,
`pest-stability`, `farm`, `lumber`, `mine`, `status`, and `fixture-cleanup` on
the lab port and documented OP QA identities; it accepts no command or target
arguments. Always clean the scene after a smoke run.
