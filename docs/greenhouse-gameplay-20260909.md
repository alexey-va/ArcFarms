# Underground greenhouse gameplay

The greenhouse is a journalled 9×11×5 underground chamber. Its surface hatch
enters only after construction completes and a durable return record commits.
The return journal is isolated from mole returns. Exit/completion evacuates
players before restoration; rejected returns retain the record and chamber.
Offline players recover on join. Legacy surface scenes move underground while
preserving cooled progress and carried plant identity.

The beds alternate on an 8-second cycle: 3 seconds of yellow warning,
2 seconds of magma heat, then 3 seconds of rest. The central aisle is safe.
Floor displays communicate the warning with particles disabled. Ceiling
shroomlights are real journalled blocks, providing actual light underground.
Picking requires approaching the bed; the central aisle cannot reach every
pepper through the old generous click radius. Hot peppers use the existing
configured carrying deadline. Expiry or exposure loses the carried pepper,
regrows its plant and preserves delivered progress. Heat pushes exposed
players toward the aisle. No personal items or health are consumed.

## Economy scope

Quota, contribution per cooled pepper, eligibility and all reward quantities,
chances, multipliers and currencies are unchanged. The per-completion change
is 0 vault, 0 tokens, 0 XP and 0 reward items. Timing now requires movement and
waiting for safe beds, so completion throughput may decrease; no measured
hourly income claim is made. Expired peppers give zero contribution, journalled
room blocks are protected, and temporary peppers never become inventory loot.
The planned EconomyShopGUI SELL-to-contract transition is unchanged.

## Verification

Focused domain tests cover warning/active/rest timing, alternating sides,
expiry, participant pause and legacy normalization. Chamber tests cover
geometry, depth, real lighting, surface-only region ownership, exact restore
and bidirectional namespace collision rejection. The incident regression
covers descent after a durable return, preserved hotbar selection, rejecting
harvest from the aisle, expiry without progress, evacuation before restore,
and migration without resetting cooled progress.
