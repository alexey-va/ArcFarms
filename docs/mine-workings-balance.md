# Mine lateral workings balance

## Last Descent redesign (0.45.0)

Reviewed against the canonical economy inventory's ArcFarms mine and planned
SELL-to-contract rows. This replaces one existing incident and leaves the
selection pool, order quotas, reward configuration and completion ledger intact.

| Unit per completed order | Before | After | Change from redesign |
| --- | --- | --- | --- |
| Vault coins | Existing configured reward | Same | 0 |
| Premium tokens | No expedition grant | Same | 0 |
| XP | Existing order reward | Same | 0 |
| Ordinary items | Existing order reward | Same | 0 |
| Contribution checkpoints | 12 for Last Descent | 12 | 0 |

The repair stone and power cell are scene/service materials, not saleable loot.
The existing one-time order reward remains the only reward path. One power-cell
delivery now credits the former three-delivery budget, and a return ride owns
the final credit. Two 24-block descents plus a 48-block ascent replace twenty
blocks of old travel. Net completion time and participant distribution remain
unmeasured; equal rewards do not imply equal earnings per hour. There is no new
item supply for current SELL or future contracts and no currency conversion.

---

This is a balance note for `TUNNEL_DRIVE`, `RAIL_EXTENSION`, `ORE_WORKSHOP`,
and the reworked `TRACK_DAMAGE`. The change adds work to an existing mine order; it does not add
a reward, price, chance, quota completion grant, or currency conversion.

## Before and after

| Unit | Before | After | Delta |
| --- | --- | --- | --- |
| Vault | No direct mine payment; bundled mine reward money remains `0`. | Unchanged. | `0` Vault mint/day from the workings. |
| Tokens | No mine token grant. | Unchanged. | `0` token mint/burn from the workings. |
| XP | Existing order completion reward; `old_shafts` source default is `220 XP` per completion. | Same completion reward, claimed once through the existing reward ledger. | `0 XP` attributable to a working stage. |
| Ordinary item reward | Existing `4 IRON_INGOT` completion item in `old_shafts`. | Same item amount and chance. | `0` additional ingots/order. |
| Mined blocks and world drops | Existing mining and material-weight behavior. | Side geometry is temporary and journaled; service items are consumed by the activity and are not ordinary loot. | `0` new ordinary-drop grant. |

The source anchors are `src/main/resources/config.yml` under
`mine-zones.old_shafts.rewards` and the completion path in the existing mine
reward service. The new domain engine (`MineWorkingEngine`) contains only
stage transitions and timing; it has no reward or currency operation.

## Activity and cadence bounds

Each working is one incident inside the current order. Its service actions do
not create a second order completion and do not multiply the existing reward.
The bundled source profiles retain `incident-count-min: 1` and
`incident-count-max: 1`, so a bundled order still has one scheduled incident;
the new IDs only expand the candidate pool.

For profiles with several scheduled incidents, the source cadence test checks
quota thresholds of `25/50/75` for three incidents in a 100-block order. This
spreads incident work through the order instead of starting the whole schedule
after the halfway point. It changes when work is requested, not how much XP,
items, Vault, or tokens an order pays. Exact player-hours, completions/day,
and network-week throughput are unmeasured here; no income-per-hour or
time-to-purchase claim follows from this change.

## Separate units and side effects

- **Service items:** supports, rails, ore, billet, and the checking minecart
  are activity materials. They are not deposited into the ordinary player
  reward inventory and have no sell price in this change.
- **XP:** no stage gives XP. The existing completion grant remains the only
  source.
- **Items:** no stage gives ingots, ore drops, or a new bundle. The existing
  completion item remains unchanged.
- **Vault and tokens:** no direct mint, burn, transfer, escrow, or price is
  introduced. A later SELL-to-contract migration remains outside this change;
  current item conversion must not be counted as a new working payout.
- **Recovery:** restoring temporary blocks is a world-state repair, not an
  item sink or an economic mint.

## Unknowns and verification boundary

The active `classic` configuration was read before this change: its four
`old_shafts` orders use one incident each, reward money `0`, `220 XP`, four
iron ingots and zero random-bundle rolls. The configuration change only
expands those incident pools from five to nine types. Player session
throughput and the resulting income per hour remain unmeasured.
After delivery, verify that the active mine reward settings still match the
source, service items are removed on every exit/recovery path, and one complete
working produces exactly the existing order completion reward once.

## Drivable tunnel update (0.44.4)

The old drive budget was 90 excavation actions plus three supports (93). The
new drive retains a single budget of 93, credited once when the machine reaches
the destination. No actions, drops or XP are credited per excavated block.
The cutter only changes journal-owned blocks. Vault and token deltas remain
zero; order XP and item quantities remain unchanged. The route is 44 blocks
long with two alternating bedrock bypasses at a commanded speed of 1.7 blocks
per second. Actual traversal time, participant distribution and income per hour
remain unmeasured; the reward cap does not establish equal player throughput.


## Diamond discovery and factory programs (0.44.5)

Reviewed against the canonical ops `docs/knowledge/economy-inventory.md`, basic
mine/resource-order sections. The diamond chamber is journal-owned scenery:
normal block breaks remain protected, and drilling produces no item/XP drops.
Before/after direct chamber issuance: 0/0 diamond items, 0/0 Vault coins,
0/0 premium tokens, 0/0 XP. Existing order rewards are unchanged. Current SELL
and future contract conversion therefore receive no additional diamond supply.

Factory programs all retain ten checkpoint credits and the same final reward
path; direct reward changes are 0 Vault, 0 tokens, 0 XP and 0 ordinary items.
Tunnel Drive retains 93 contribution credits once at its goal. The new
three-second machine cycles and wider caves alter pacing; hourly income has
not been measured and is not asserted to be identical.


## Free steering and continuous boring (0.44.8)

Reviewed against the canonical economy inventory's ArcFarms mine row. The
excavation buffer is preparation permission, not an action or reward. Boring in
reverse, turning and retracing a carved cell never add contribution credits.
Before/after per drive: 93/93 contribution credits, issued once at the goal;
0/0 Vault coins, 0/0 premium tokens, 0/0 XP and 0/0 ordinary item drops directly
from excavation. Existing order rewards, quotas, cooldowns and food rolls are
unchanged. No additional ore enters SELL or the planned contract replacement.

Commanded peak speed changes from 1.7 to 2.4 blocks/second, with acceleration
and braking rather than resetting velocity while awaiting every write. For an
identical unobstructed route, motion-only time is 70.8% of the old value; this
is a theoretical bound, not a measured 41.2% increase in hourly rewards.
Removing forced waits and allowing better detours can shorten this incident;
full order completion remains gated by the unchanged mining/loading phases
and cooldown. Per-hour XP/items and indirect conversion remain unmeasured.
No price, payout, probability, multiplier or currency conversion is changed.
