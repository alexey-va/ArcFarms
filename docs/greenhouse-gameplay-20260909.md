# Hell rift gameplay

The event reuses the mole expedition entrance policy, marker/hitbox renderer,
particle pillar, durable return records and journalled room pipeline. Its
`UndergroundEvent` type inherits the common title/subtitle and HUD hint flow.
The entrance is chosen from interior indexed beds, not the farm boundary.

A separate 9×11×5 hell chamber uses native blackstone, nether bricks, basalt,
crying obsidian and nine real shroomlights. Stand on the highlighted rune for
60 consecutive ticks to seal it. Leaving or entering an active heat zone
resets only the current hold. Completed seals persist across restart.
Sides alternate on an eight-second cycle: three seconds of warning, two of
heat, three of rest. Heat pushes toward the safe central aisle. Visible floor
materials communicate the warning even with particles disabled. There are no
pepper items, forced hotbar selection, item consumption or health damage.

Entry waits for construction and a committed return record. Exit and completion
evacuate players before restoration; rejected teleports retain the room and
return record. Offline players recover on join. Legacy greenhouse layouts are
restored before rebuilding, preserving completed progress and dropping obsolete
carried-pepper state without granting contribution.

## Economy assessment

Quota and all reward quantities, chances and multipliers are unchanged.
Per-completion deltas are 0 vault, 0 tokens, 0 XP and 0 reward items. Each newly
completed seal contributes one unit; interrupted holds and migration contribute
zero. Completion throughput changes with movement and heat timing; no measured
hourly-income neutrality is claimed. Temporary blocks remain protected and are
restored. The planned EconomyShopGUI SELL-to-contract transition is unchanged.

## Verification

Focused tests cover continuous hold/reset, ordered idempotent seals, hazard
phases, participant pause, legacy progress, durable entry/return and preserved
hotbar selection. Chamber checks cover native lighting, walkable routes to all
runes, depth, region footprint, exact restoration and journal namespace
collisions. Registry tests enforce all incident types; startup locale validation
requires their titles, subtitles and hints. These checks do not substitute for
a player-client visual inspection.
