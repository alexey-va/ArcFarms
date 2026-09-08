# Farm shop: food and premium perks — 2026-09-08

Scope: ArcFarms 0.36.0, existing farm perk merchant on classic only. Native main
menu and shop gain ARC-owned T16/T17 tables, semantic button colors and typed
active/locked states. Inventory fallback expands to six rows. Existing eight
perks keep their prices, duration and effects.

## Offers and currencies

| New offer | FARM points | Delivery/effect |
| --- | ---: | --- |
| Bread | 20 | 16 ordinary BREAD |
| Steak | 40 | 16 ordinary COOKED_BEEF |
| Golden carrot | 80 | 8 ordinary GOLDEN_CARROT |
| Iron Farmer | 900 | 24 wall-clock hours, Strength III and Resistance III |
| Sky Courier | 700 | 24 wall-clock hours, Speed V and Slow Falling I |

FARM points are the player's weekly FARM contribution minus the same spent
counter used by existing perks. They are not vault, tokens, XP or item resources.
Prices before are absent, not zero. All five new offers cost 1740 points once;
both premium perks cost 1600. Existing offers remain 140–400 points / 72 hours.
Food is a repeatable point sink and item mint, with no direct vault/token/XP mint.
Purchased food is ordinary transferable food. Existing farm food reward rolls
and Sustenance are alternative ways to obtain food or restore hunger.

## Assumptions and calculation

`food-shop-balance-20260908.json` is checked with the canonical economy evaluator.
The assumed low/base/high activity is 60/300/600 points per active hour,
1/2/4 active hours daily and 2/5/10 participating players. These are scenario
assumptions, not measured play time or observed earnings. Food demand is
0.25/1/2 packs of each SKU per player-day; premium demand is 0/0.1/0.25 purchases
of each premium perk per player-day, representing cohort averages.

This burns 35/300/680 points per player-day and 490/10500/47600 network-week.
Separate item issuance is 4/16/32 bread, 4/16/32 steaks and 2/8/16 golden carrots
per player-day. At the base 600 points/day the initial Iron Farmer requires
1.5 days, Sky Courier 1.17 days, both 2.67 days. Bread/steak/carrot require
4/8/16 minutes at 300 points/hour. At low 60/day neither premium perk is
reachable within a weekly reset: they deliberately target established active
farmers. There is no permanent progression or premium entitlement carry-over;
only the purchased 24-hour effect and paid food survive week rollover.

## Indirect mint and upper bounds

The read-only spawn economy audit covered 168 hours on 2026-09-08, with an exact
window boundary and separate currencies. No matching bread/steak/carrot sale
rows were observed; this does not prove SELL is disabled. The tracked
EconomyShopGUI-Premium Food.yml declares sell rates 8/4/7 vault per item.
At those rates a whole pack could mint 128/64/56 vault when sold. The conditional
resale scenario is included separately in the calculator: 62/248/496 vault per
player-day, 868/8680/34720 network-week if every issued item is sold. Actual
active price/permission and demand are unmeasured. In the owner's target
SELL-off scenario that NPC resale mint is zero; player resale is a transfer.
The selected foods do not match the currently observed open contract materials.
Food never converts back to FARM points, so purchase/resale is a one-way limited
conversion, not a self-funding loop.

The strongest theoretical food cashout is bread: 6.4 vault per spent point at
the tracked SELL rate. Spending all 2400 high-scenario daily points on bread
would produce 1920 bread and potentially 15360 vault/day per player; this is a
stress bound, incompatible with buying the other offers from those same points.
No independent point mint or multiplier is introduced. Multi-account totals
scale with independently earned contributions; this task adds no passive
income or shared budget bypass. Monitor actual demand before further price cuts.

Iron Farmer upgrades existing Strength II / Resistance II to III; effects do
not stack additively. Resistance is 60% rather than 40% for ordinary eligible
damage, not invulnerability. Sky Courier replaces Speed III (+60%) with V
(+100%), so the pure travel-speed ratio is at most 2/1.6 = 1.25; whole-shift
throughput improvement is lower and unmeasured. Slow Falling improves traversal.
Neither perk increases the reward multiplier, chance or quantity directly.
Existing reward boost and MARKET continue their existing behavior; no multiplier
product is introduced. Potion refreshes run in the farm region and expire after
the existing refresh duration (normally 60 ticks) after leaving, like old perks.

Decision: keep basic food accessible and make the two stronger, shorter perks
optional point sinks. Food-to-vault cashout is explicitly conditional and bounded
by earned points, not treated as zero risk or a fixed exchange rate. No global
SELL, contract, vault, tokens or existing reward rates are changed.

## Persistence and acceptance

A food purchase atomically persists the spent points and immutable food intent
(UUID, exact material, amount, price and charged week). Perk and food mutations
share the pending-purchase lock. Delivery requires a persisted claim before any
inventory mutation. Full inventory or disconnect before insertion leaves the
purchase pending; it is retried on merchant open or while in the farm. Neither
pending nor ambiguous claimed food can be charged again. Inventory capacity is
rechecked after async persistence, on the same game-thread step as insertion.

A hard crash between durable claim and inventory delivery is inherently
ambiguous across the plugin journal and player inventory. Such a CLAIMED record
is retained for operator reconciliation and never automatically replayed; the
UI reports review. The purchase record retains the original price/week. This
matches the existing reward ledger's claim-before-side-effect boundary; it is
not an exactly-once claim across independent storage systems.

Focused tests cover spending/insufficient points, duplicate pending/claimed
purchases, overflow, week rollover, state-file round trip, asynchronous capacity
changes, no duplicate delivery, old-config migration, actual menu content and
button roles. Offline previews export actual ARC DialogTables components.
Disk JAR/config delivery, process activation and real-client purchase/render
acceptance are separate facts in the task report. No unrequested player-data
mutation or production economy QA is performed.

## Repricing accepted 2026-09-08 (0.36.1)

Supersedes the initial food prices above: 16 bread 20 -> 100 FARM points,
16 steaks 40 -> 120, 8 golden carrots 80 -> 160. Premium perks are unchanged.
The separate food-shop-reprice-20260908.json compares the delivered initial
offers with this revision; neither snapshot proves runtime activation.
At the same assumed demand, point burn changes from 35/300/680 to
95/540/1160 per player-day, or 490/10500/47600 to 1330/18900/81200 per
network-week. Low demand now exceeds 60 points/day and cannot be sustained;
these are desired-demand potentials, not actual purchases or measured income.
At assumed 300 points/hour, food takes 20/24/32 minutes instead of 4/8/16.
One of each costs 380 instead of 140; all five new offers cost 1980.

Item quantities and conditional resale per pack remain 128/64/56 vault.
At fixed pack demand, item and vault flows therefore do not change.
Conditional vault per point falls from 6.4/1.6/0.7 to 1.28/0.5333/0.35.
Spending the entire hypothetical 2400-point daily budget on bread yields
24 packs, 384 bread and at most 3072 vault instead of 15360 at tracked SELL
rates. This excludes buying other offers with those points; multi-account
output scales with separately earned points. Live SELL availability and
actual point income remain unmeasured. Target NPC SELL-off makes resale mint
zero; player trading remains a transfer. No direct vault, token or XP change.

Decision: reduce cheap repeatable food issuance and resale conversion while
keeping food below premium perks. Existing food rewards and 150-point/72h
farm-only Sustenance remain alternatives. Purchases retain their recorded
price and quantity across recovery; no retrospective debit or change to
paid pending deliveries. Validate defaults/config and publish to classic
on disk; activation and real-player economics remain separate checks.
