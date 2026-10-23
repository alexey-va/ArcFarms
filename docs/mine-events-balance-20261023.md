# Mine events and workshop: economy assessment, 2026-10-23

This compares the parent-verified live ArcFarms .49 / ARC 1.4.451 state with the proposed event-pool and workshop revision. “Before” means that current .49 state, not the earlier pre-.49 reward configuration. The evaluation covers economic flows and supplied throughput sensitivities; final balance judgment stays with the parent.

## Direct completion rewards

The per-completion payout is unchanged. Four orders produce 276/284/270/268 Vault and 414/426/405/402 vanilla XP, respectively; the equal-order long-run mean is 274.5 Vault and 411.75 XP. Each positive contributor receives the full individual reward. Tokens remain 0. One exclusive weighted resource bundle is also unchanged; expected units per grant below apply floor scaling per item before weighting and averaging four order difficulties.

| Item unit | Expected units per grant |
|---|---:|
| Iron ingot | 4 |
| Coal | 8.125 |
| Gold ingot | 1.9 |
| Redstone | 4.875 |
| Diamond | 0.55 |
| Lapis lazuli | 1.575 |

The JSON/evaluator keeps Vault, vanilla XP, tokens and each material in separate units. `disposable_per_player_day` retains the prior 80% savings sensitivity; the remaining 20% is not an observed sink or a modeled burn. Existing price examples are unchanged: pig L1/L1–L3 = 25,000/205,000 Vault; horse L1/L1–L3 = 50,000/420,000 Vault.

## Throughput sensitivity

These are assumptions, not measured player cadence: hours/day = 0.25/1/2, cycles/hour before = 3/6/10 and after = 3.2/6.5/11. The resulting completions/day are 0.75/6/20 and 0.8/6.5/22, respectively; network cohort sizes are 5/10/20 active eligible contributors. Fractional completions mean a long-run average.

| Scenario | Completions/player-day before → after | Vault/player-day | Vault/network-week | Vanilla XP/player-day | Vanilla XP/network-week |
|---|---:|---:|---:|---:|---:|
| Low | 0.75 → 0.8 | 205.875 → 219.6 | 7,205.625 → 7,686 | 308.8125 → 329.4 | 10,808.4375 → 11,529 |
| Base | 6 → 6.5 | 1,647 → 1,784.25 | 115,290 → 124,897.5 | 2,470.5 → 2,676.375 | 172,935 → 187,346.25 |
| High | 20 → 22 | 5,490 → 6,039 | 768,600 → 845,460 | 8,235 → 9,058.5 | 1,152,900 → 1,268,190 |

The implied weekly Vault mint delta is +480.375 / +9,607.5 / +76,860 for low/base/high. It comes only from the assumed increase in completions; the Vault, XP and bundle amount per completion stay constant. Existing bundle-item volume also scales with the assumed completion count; per-material daily and weekly outputs are in the evaluator result.

The new pool is planned as the current 11 types plus `CRYSTAL_RESONANCE`, `FREIGHT_WINCH` and `DRAINAGE_PUMP` (14 total), with one scheduled incident per cycle. The current parent-verified pool has 11 types and Crystal Resonance is force-only. Selection is deterministic by cycle/order; these facts do not establish a uniform 1/14 rate, so the model assigns no Crystal frequency.

Crystal Resonance currently gives no direct Vault, tokens, vanilla XP, physical drops or player items. The proposed reward is at most **4 shared zone-order progress credits once per resolved Crystal incident**. It advances the one shared mine order and is not multiplied by contributors; it cannot be sold or submitted to contracts. This is not item or currency emission. Any effect on completions/hour is represented only by the stated after-throughput assumptions.

The new workshop requirement is 12 zone credits / 2 batches; already-accepted 18-credit work retains 3 batches. At the supplied 18 seconds per batch (4 crush + 3 transfer + 6 heat + 5 pour), the automatic minimum is 54 → 36 seconds. This is a 33.3% lower workshop minimum, not a measured 33.3% increase in mine cycles/hour. The maximum direct grant remains 284 Vault; the cooldown-only ceiling is 60 grants/hour, or 17,040 Vault/player-hour, before any nonzero mining, incident or travel time. It is not attainable as a physical-work rate.

## Existing item conversion paths

The existing completion bundles—not the four shared progress credits—have conditional conversion paths. The earlier short-shifts assessment values a fully liquidated average bundle at 151.10 Vault through tracked nominal SELL prices or 216.578 Vault through the then-current contract quote snapshot. These are alternatives for the same item units and neither is direct mine income. Under the supplied completion assumptions, all-items-converted network-week potentials are:

| Route | Before low/base/high | After low/base/high |
|---|---|---|
| SELL, nominal and uncapped | 3,966.375 / 63,462 / 423,080 Vault | 4,230.8 / 68,750.5 / 465,388 Vault |
| Contracts, before shared cap | 5,685.1725 / 90,962.76 / 606,418.4 Vault | 6,064.184 / 98,542.99 / 667,060.24 Vault |

The contract snapshot used 8.58 coal, 8.61 iron, 12.91 gold, 5.74 redstone, 11.48 lapis and 76.06 diamond Vault per unit, with a shared 250,000 Vault/week budget (249,686.50 remaining at that observation). At most the remaining shared budget is usable across all contracts, before personal limits, minimum batches and market movement; the table’s uncapped contract row is not a payout forecast. SELL remains the current alternative and the planned SELL-to-contract transition remains a separate future state. No four-credit conversion is added.

## Wider economy baseline and evidence limits

The saved read-only one-replica 168-hour ledger snapshot (`/private/tmp/mine-economy-168h-20261023.json`, 2026-10-16 09:40:05.881Z–2026-10-23 09:40:05.882Z) contains 3,370 records / 33,792 operations / 29 players. After removing the single 67,000,000 Vault admin grant, classified Vault mint/burn is 910,862.78 / 2,572,343.35 (net −1,661,480.57); transfer net −110.60 is separate. Vault has 128 unclassified operations / 189,141.78 absolute amount. Tokens are separately 62 minted / 15 burned (net +47), with 3 unclassified operations / 15 absolute amount. The broad `farms` source is +13,728.75 Vault across 14 operations / 6 players and is not mine-specific; `shop` minted 55,995.33 and `resource_contracts` minted 37,788.50 in this window. These are economy context, not additions to the mine model.

Source anchors: `src/main/resources/config.yml:872-901` (current base reward and bundle weights), `:902-933` (order resources and current 11 incident IDs); `FarmRewardPlanner.kt:27-52,78-85,119-123` (positive contribution, one weighted bundle, direct reward and integer-floor scaling); `MineSequenceIncident.kt:41-48` and `MineIncidentCoordinator.kt:54-75` (current Crystal target completion progresses the incident without a direct payout); `MineShift.kt:124-141` (one selected schedule entry is deterministic, not a stated uniform draw); `MineWorking.kt:90-108`, `MineWorkshopHeat.kt:3-27` and `MineOreWorkshopController.kt:616-618` (2/legacy-3 batch mapping and workshop timing components). Previous per-order payout, bundle and conversion evidence is in `mine-short-shifts-balance-20261023.md:7-23,55-59` and its JSON.

The earlier live reward readback and the current pool/version confirmation are parent-provided; no new live operation was performed here. After activation, measure actual completion cadence and event mix before replacing sensitivity assumptions. `evaluate.py` returned exit 0; the calculation is saved in [the result JSON](mine-events-balance-20261023-result.json). The calculator checks arithmetic and unit separation, not whether the assumed throughput or final design is balanced.
