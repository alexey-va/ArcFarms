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
moisture and protected from trampling and drying. Grass, mycelium, and podzol
spread into any block inside the farm region is always cancelled, even when
that block is not part of the current patch or durable recovery ledger.

Patch selection rotates between spatially distinct beds instead of always
starting beside the entering player. Beds are connected only on one height,
with a one-block irrigation channel allowed between rows. A selected bed grows
from the configured target to its full connected shape when that shape fits
under `preparation-patch-max-size`; larger fields remain hard-bounded. Recovery
may add the missing edge of an old partial bed without discarding existing
tilling or planting progress. Admin-forced field-care stages select a fresh
full-sized patch as well; startup automatically expands a legacy zero-progress
two-cell seeder patch, so this case does not require reindexing the farm.

The active patch, tilling progress, and planting progress survive an empty
farm, chunk unload, plugin reload, or process restart. Recovery replays an
unfinished patch release idempotently and reconciles already tilled or planted
blocks before accepting more actions. On every configured Nth shift,
`seeder-every-shifts` replaces both manual tilling and planting with
horse-drawn field machinery; `0` disables this variant. It receives a separate,
larger field bounded by `seeder-patch-size` and `seeder-patch-max-size`. The
player calls the glowing horse, keeps its visible leash, and leads it through
the field without checkpoints or a prescribed route. Every managed bed inside
`seeder-working-radius` is processed where the horse physically travels; the
story cannot finish until the whole persisted field is tilled and then planted.
After either planting path, the boss bar switches to one
randomly selected field-care story before harvesting begins.
Every story is spread across the active bed instead of clustering around its
center:

- weeds place a configurable number of physical glowing roots; one root is one
  hoe action, so the displayed progress is the number of weeds removed;
- irrigation first dries the selected farmland, then exposes a chain of valves
  that must be opened in order. Each valve sends a bounded circular particle
  front across its assigned beds; the soil hydrates behind the front and the
  target counts only after the wave has actually completed;
- pollination asks the player to collect two charges from a hive and carry them
  to distant flower patches, returning to the hive as needed;
- storm preparation distributes a configurable set of cover anchors across the
  actual field;
- scarecrow duty stocks one decoy stand at receiving. Workers carry individual
  scarecrows to marked beds; leaving the farm returns an unfinished decoy;
- animal rescue spawns glowing tagged farm animals, attaches a visible leash
  when a player calls one, and leads them to the highlighted barn;
- crop disease begins with a few purple outbreaks. It grows locally around the
  live frontier only while someone is present, kills an untreated crop after a
  configured deadline, and stops at `disease-max-spots`. One outbreak is one
  hoe action; killed crops are journaled and restored in bounded slices only
  after the activity ends;
- moles surface as glowing earth mounds. A hoe strike keeps the target's
  persisted hit progress but moves it to a distant part of the same patch until
  the mole is finally caught.
- orchard care hangs a large randomized set of apples across every loaded,
  reindexed open tree canopy, while the player may collect any smaller
  configured quota. Apple display creation is spread across updates, and its
  model and interaction height can be tuned independently.

The current instruction and exact progress remain in the boss bar. The next
useful target has one restrained long-range particle column, while nearby
targets use glowing models and small local feedback. There is no failure timer:
an empty farm, one player, disconnect, chunk unload, or restart leaves the same
shared objective waiting. Finishing field care starts normal harvesting. Its
boss bar shows every unfinished crop with its current and required amount;
other active boss bars show only the current action and progress. The contract
name stays in the roomier farm scoreboard instead of consuming boss-bar space.
Only requested mature crops fill the order, and accepted crops are consumed
instead of dropping.

While a player is inside an active farm, the optional `ui.farm-scoreboard`
sidebar expands that compact guidance into the contract name, current action,
contextual next-action hint, phase progress, every crop quota, and cart fill.
It restores the player's prior scoreboard on exit, reload, quit, or shutdown.
If another plugin replaces the
farm sidebar during a visit, ArcFarms yields until the player next enters the
farm instead of fighting it and causing flicker. The portable default does not
replace an existing sidebar; `replace-existing` is an explicit runtime choice.

Orders are complete contract variants rather than temporary multipliers. Most
shifts rotate through ordinary bakery, mine-supply, and market contracts; a
bounded configurable roll may choose one of the larger rare contracts instead.
Each contract owns its crop quota, possible care stories, possible incident,
customer, and cart cargo visual. It remains one shared indefinite objective and
does not add a second progress track.

Care fixtures do not assume that the map already contains hives, valves,
covers, scarecrows, or animals. By default ArcFarms finds a real hive where one
exists and otherwise creates temporary, tagged display fixtures and animals at
safe points derived from the selected field. `procedural-care-fixtures: false`
turns that behavior off for fixture-dependent stories; those stories are then
skipped until an administrator saves the required point. The optional points
are `hive`, `irrigation`, `covers`, `scarecrows`, and `barn`. Irrigation,
covers, and scarecrow points orient the procedural field layout without moving
one fixture off the crop beds. The barn falls back to the crop receiving point
until an administrator saves its own override. Any override can be removed with
`/arcfarms admin point <zone> <point> clear`.
Their entities are
removed when the story ends and are reconstructed from persisted state after a
restart.

Physical care density is controlled by `care-targets-per-player` and
`care-targets-max`; the portable defaults produce 15 objects for one worker and
cap the shared objective at 45. Disease timing and shape use
`disease-spread-seconds`, `disease-spread-radius`, and `disease-kill-seconds`.
Scarecrow delivery radius and carry height are configurable independently.
Bird incidents keep their defeat quota separate from the visible flock through
`special-incidents.birds.spawn-multiplier` (default `2`). Irrigation wave
height, spread, density, and ring pacing are runtime config, as are drought
growth speed and the smooth per-player night transition duration.

Dynamic animals and delivery crates prefer safe ground away from their
destination while staying within reach of a current participant. The general
search limits are `placement-min-objective-distance`,
`placement-max-player-distance`, and `placement-search-radius`. Animal rescue
adds its own `animal-rescue-targets`, `animal-rescue-min-spacing`,
`animal-rescue-max-player-distance`, and `animal-delivery-radius` controls.
Delivery crates use `delivery.spawn-radius` and
`delivery.min-crate-spacing`. Constrained fixtures degrade to
the best available safe position instead of making a story impossible.

Three configured free-floating item displays stand on the path near the farm
entrance, each with a short text label and interaction hitbox but no barrel or
base block. At any time they issue a tagged unbreakable hoe, the
required seeds, or one infinite service water bucket. These tools cannot be
dropped, stored in another
inventory, used for unrelated farm changes, carried outside the farm, or moved
to another backend; ArcFarms removes them at every such boundary.

At each selected threshold one of the farm incidents starts. Every order picks
a deterministic count inside `incident-count.min..max`, so reloads cannot reroll
an active order, then distributes that count across
`incident-trigger-percents`. The spawn profile produces three to five distinct
interruptions per harvest, with at most one classic field disaster. Four
special incidents deliberately avoid repeating the same marker interaction: a
giant crop is one large physical target hit with a hoe, with crop families and
field positions rotated separately so a large wheat field cannot dominate every
spawn; a blocked irrigation canal places configurable debris along the route,
lets players clear it cooperatively in any order, and advances a visible water
trail only through the cleared prefix; night shift uses per-player night without changing the world clock,
spreads highlighted mature crops across the farm, and releases bounded,
non-persistent torch patrols across the full indexed field. Patrol count scales
with the number of indexed usable beds between configured minimum and maximum
bounds, and patrols walk between in-region waypoints through Paper pathfinding
instead of being teleported. Each patrol carries an actual temporary `LIGHT`
block with chunk-PDC recovery instead of pretending that particles emit light;
and the living market
offers an optional timed rush order through the existing customer for a
configured final money bonus. Declining or timing out the market has no penalty
and never resets the main order. Market deadlines and every target or decision
are persisted; all non-timed goals still wait indefinitely when zero players
are present. Birds fly over the indexed field, eat crops and can be defeated
reliably with the service bow even inside a protected WorldGuard region. Food
delivery chooses one of the farm's named routes deterministically, persists
that choice across restarts and shows its riders a bounded personal particle
trail with a distinct next-checkpoint marker. A pest outbreak
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
the configured number of blocks per tick. Completed-order field recovery uses
the same batch limit, so a large mechanized field cannot produce one restoration
lag spike. Every incident pauses harvesting without resetting the main order.
Resolving it resumes the ordinary crop order at the next unfinished crop. At
each quarter of the harvest quota another visible cargo bundle appears in the
stationary order cart. The tagged cart and its customer are reconstructed from
persisted shift state after reload or restart. Clicking the cart reports its
fill percentage; clicking the baker, mine supplier, or market trader repeats
the current order. The customer waits beside receiving by default, while both
`cart` and `customer` support administrator point overrides. When the crop
quota is ready, a configured set of interactive harvest crates appears
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
stop. `/arcfarms admin edit` remains authoritative during an active shift and
lets an administrator deliberately remove a bed and its ArcFarms record without
the current objective blocking the edit. For WorldEdit rebuilds, select the
area and run `/arcfarms admin unmanage <zone>` before or after replacing it; ArcFarms
removes every selected managed bed from its durable state and chunk ledger in
one operation. Phase-colored
particle columns mark the active patch from a distance; drought and delivery
use their own local action areas.

`MELON` and `PUMPKIN` are fixed block crops rather than preparation crops.
ArcFarms records their exact block data and coordinates in a separate chunk PDC
ledger before the first harvest, suppresses natural fruit placement from stems
inside the farm, and restores a harvested fruit at that same coordinate after
`fixed-crop-respawn-seconds`. Pending repairs are reconstructed whenever their
chunk loads. Before the fruit disappears, an atomic pending-repair journal is
also flushed to `data/fixed-farm-crops.json`; this closes the hard-stop window
before the chunk itself is saved. A solid obstruction is never overwritten;
both durable records are retained and retried instead.

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

Every `/arcfarms admin` subcommand accepts a trailing `help`. Contextual lists
are available through commands such as `/arcfarms admin point <zone> help`,
`/arcfarms admin stage <zone> help`, and `/arcfarms admin event <zone> help`.

- `/arcfarms` — localized activity menu and current state.
- `/arcfarms status` — compact status for all configured zones.
- `/arcfarms top <farm|lumber|mine>` — contribution leaderboard.
- `/arcfarms travel <farm|lumber|mine>` — route to the exact configured server,
  world, and location.
- `/arcfarms reload` — validate and reload configuration/locales (admin).
- `/arcfarms admin edit` — toggle deliberate farm-bed deletion and PDC cleanup;
  edit mode stays authoritative even during an active scene (admin).
- `/arcfarms admin inspect` — toggle read-only block inspection. Clicking a
  block prints its BlockData, fixed-crop or bed ledger entry, pending restore,
  and current patch/incident ownership.
- `/arcfarms admin point <zone> <tool|seeds|water|crates|receiving|cart|customer|travel|hive|irrigation|covers|scarecrows|barn>` —
  save the administrator's current world, coordinates, yaw, and pitch for a farm
  operation point. Non-travel points must be inside the farm and off crop beds.
- `/arcfarms admin point <zone> <point> clear` — remove an administrator point
  override and return to the configured or procedural placement.
- `/arcfarms admin points <zone>` — list the effective configured and overridden
  farm points.
- `/arcfarms admin unmanage <zone>` — remove ArcFarms control and recovery
  records from every managed bed or fixed crop inside the player's exact
  WorldEdit selection.
- `/arcfarms admin blockreset <zone>` — while the farm is idle, rebuild its
  durable block index from the current WorldGuard region. The bounded two-phase
  scan records usable farmland, fixed melons/pumpkins, and open leaf anchors;
  it keeps indexed farmland wet and excludes beds covered by structures,
  stems, or fixed fruit. Use the optional `status` argument to inspect progress.
- `/arcfarms admin backup <zone> save` — save the current cuboid WorldEdit
  selection as an immutable Sponge v3 schematic plus a validated SHA-256
  manifest under server-owned plugin data. `list` and `status` inspect it.
- `/arcfarms admin backup <zone> restore <id>` — restore the schematic to its
  original coordinates in bounded tick slices. ArcFarms first saves the exact
  target bounds as a `pre_restore` safety backup, then rebuilds the farm block
  index; a failed restore leaves the farm paused.
- `/arcfarms admin route <zone> start [name]` — record a named food-delivery
  route on foot. Omit the name for the backward-compatible `main` route; use
  `finish`, `status [name]`, `clear [name]`, or `cancel` to manage recordings.
- `/arcfarms admin stage <zone> <preparation|planting|harvesting|seeder|weeds|irrigation|pollination|apples|covers|scarecrows|animals|disease|moles|pests|drought|birds|giant-crop|channels|night-shift|market|food-delivery|delivery|complete|reset>` —
  switch the current farm to an exact QA stage while preserving normal recovery.
- `/arcfarms admin next <zone>` — advance to the next useful QA stage.
- `/arcfarms admin finish <zone>` — finish the current order through its normal
  completion and reward path.
- `/arcfarms admin event <zone> <seeder|weeds|irrigation|pollination|apples|covers|scarecrows|animals|disease|moles|pests|drought|birds|giant-crop|channels|night-shift|market|food-delivery>` —
  start any exact farm story or harvest incident.

Farm counts, manual and mechanized patch sizes, machinery radius, spacing,
spawn/search radii, incident ranges/checkpoints, special-event quotas, display
scales, personal night time, crop/patrol spacing, patrol density, entity,
movement and route refresh, market timer and bonus, apple completion/placement
counts, spawn batching and display/hitbox height,
block-reindex and backup batch/size limits, display
scale/offset/view range, care timings, drought/pest tuning, UI toggles, sounds,
particles, rewards, fixed-crop respawn delay, restoration batch size, and
operation points are hot-reloadable. Only
`server-id`, the Redis network enablement boundary, the plugin JAR itself, and
server-wide living-entity tracking in `spigot.yml` require a restart.
- `/arcfarms debug <zone> status` — print the exact shift, patch, crop damage,
  order rarity, customer, cart fill, water-flow, care targets, animal followers,
  nest, pest, and delivery state used by the server.
- `/arcfarms debug <zone> contract <order-id>` — reset the QA scene and start
  that exact ordinary or rare contract with normal patch selection.
- `/arcfarms debug <zone> stage <stage>` / `event <story>` / `next` / `finish` —
  force a deterministic QA transition without waiting for random gameplay.
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
- `%arcfarms_farm_weekly_top_1_name%`, `%arcfarms_farm_weekly_top_1_skin%`,
  `%arcfarms_farm_weekly_top_1_uuid%`, and `%arcfarms_farm_weekly_top_1_score%`
  through rank `50` for the current Moscow-time week;
- `%arcfarms_farm_weekly_score%` and `%arcfarms_farm_weekly_rank%` for the
  viewing player. The weekly table rolls over at 00:00 Monday without changing
  the persistent all-time table.

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

## Reliability boundaries

`ArcFarmsService` now orchestrates several focused lifecycle owners instead of
keeping every mutable concern in one collection. `RuntimeTaskSupervisor` assigns
each startup or reload a fresh epoch, tracks both repeating and one-shot work,
and rejects an asynchronous completion from an old runtime. Pollination charges
are scoped to an exact farm and shift. `FarmRewardLedger` owns deduplication and
the persist-before-delivery claim transaction with exact rollback on storage
failure. Giant-crop recovery has a bounded, strictly validated chunk-PDC codec;
a journal entry remains durable until its original block data was actually
decoded and restored.

Architecture tests forbid scheduler bypasses in the service and cap the existing
monolith while decomposition continues. New stateful gameplay subsystems must be
introduced as focused owners with their own invariant tests rather than adding
more service-local maps.

The reusable worksite layer is split by ownership:

- `WorksiteModule` is the lifecycle contract for one activity type;
- `WorksiteModuleRegistry` aggregates availability, access, status, ticks, and
  optional block/interact/movement event capabilities;
- `PaperWorksiteRuntimePort` owns shared messages, title/subtitle rendering,
  boss bars, effects, contribution statistics, Redis signals, and lifecycle
  scheduling;
- `LumbermillController` owns every lumber zone and its two-stage state machine;
- `MineController` owns every mine zone, reservations, journal reconciliation,
  extraction, and block recovery;
- `FarmRuntimeFactory` and `ArcFarmsRuntimeValidator` centralize multi-zone farm
  construction and fail-closed reload/world validation.

Add another activity by implementing a controller behind `WorksiteModule`; do
not add its runtime list or state machine branches back to `ArcFarmsService`.
Paper-only behavior that MockBukkit cannot emulate stays behind an injectable
adapter. Controller integration tests use `arc-core-paper-testing`, which pins
MockBukkit 4.110.0 and Paper 1.21.11; the resolved MockBukkit artifact
manifest targets Paper 1.21.11.

## Build

```bash
./gradlew clean check shadowJar
```

The deployable artifact is `build/libs/ArcFarms-0.21.0.jar`.

## Isolated gameplay QA

`scripts/lab/plugin-configs/ArcFarms/config.yml` defines three small cuboid
fixtures. The player-bot session exposes only the fixed `arcfarms` operations
`fixture-setup`, `reload`, `travel`, `debug-controls`, `scoreboard`, `market-flow`, `night-shift`, `channels-flow`, `giant-crop`, `orchard-flow`, `care-stories`, `seeder-rig`, `drought-flow`,
`pest-stability`, `farm`, `lumber`, `mine`, `status`, and `fixture-cleanup` on
the lab port and documented OP QA identities; it accepts no command or target
arguments. Always clean the scene after a smoke run.
