# Infernal plantation

The event inherits the underground event type and the shared mole expedition:
interior indexed-bed entrance, purple particle pillar, title/subtitle, durable
return record and journalled construction/restoration.

## Player flow

Enter a 21×25-block underground farming hall with four raised 5×5 soul-sand
beds, basalt arches, a contained furnace and 17 real shroomlights. Each bed has
its own lever and visible heat channel. Right-click the lever to heat the bed.
Nether wart visibly grows through its native stages over eight active seconds.

Once mature, the bed warns for four seconds. Turn off its heat, wait two seconds
for cooling, then right-click the wart to collect one batch. The bed replants
automatically. Players can stagger several beds, cool one while growing another,
and divide the work. Closing a bed early preserves its partial growth. Leaving
mature crops heated for four extra seconds scorches only that planting and
closes its valve; previously collected batches remain safe. No health, inventory
items or forced hotbar selection are involved.

Labels name the next action and remaining time. Lever labels match bed numbers;
orange channels show open heat, full-grown crops and green particles identify
ready harvest. State wording remains available with particles disabled. The
entrance and exit use the common expedition markers. The scoreboard counts
collected batches against the existing configured quota.

## Recovery and bounds

Four physical beds support the full existing 1..16 batch quota. Growth, valve,
cooling and overheat state persists; no participants inside pauses all timers.
Legacy rune/pepper rooms restore before layout version 2 is built, preserving
completed progress. The return journal keeps its existing namespace.

The eight-layer room contains 4,200 journal entries. A regression checks every
in-chunk center alignment against the existing 2,048-record chunk limit and
8,192-record scene limit. Placement still requires loaded chunks, the full farm
footprint, permitted materials and no overlapping foreign journal. The sealed
shell and connected aisles prevent accidental access outside the temporary room.

## Economy assessment

Per-completion deltas: 0 vault, 0 tokens, 0 XP and 0 reward items. Quota,
contribution per completed batch and reward amounts/chances/multipliers are
unchanged. Valves, growth, scorching and migration grant zero contribution.
Displayed crops create no inventory loot; room blocks are protected/restored.
Four parallel beds can theoretically mature/cool in ten seconds plus input and
travel, while the previous rune mechanic required three seconds per seal plus
travel. This is a timing change, not a claim of neutral measured hourly income.
The planned EconomyShopGUI SELL-to-contract transition is unchanged.

## Verification

Focused tests cover growth, cooling, overheat, early-close/resume, duplicate
harvest, paused timers, idempotent initialization, persisted timers and quotas
larger than the physical bed count. Integration covers actual valve/crop entity
routing, held-slot preservation, entry/return and restoration. Geometry checks
cover native light, every valve/bed approach, sealed bounds and namespace
collisions. Startup validation checks new locale keys and placeholders.
