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

## Native mining compatibility in 0.45.51

Ordinary managed ore moves from a cancelled event plus a deferred AIR callback to a vanilla break backed by a precommitted recovery intent. Drops and vanilla XP remain zero; the shared order quota, positive-contributor reward rule, currency amounts and bundles do not change. Native fracture/durability and a quiet sound replace the manual disappearance/resync. The throughput scenarios above remain unmeasured sensitivities; this change does not establish a new cycles/hour rate.

A read-only Spawn inventory confirmed enabled EcoJobs 2026.33 / ArcEcoJobs 0.1.43. Libreforge's HIGH `mine_block` trigger ignores cancelled events but can process a native accepted event even when drops/vanilla XP are suppressed. ArcEcoJobs' miner guard checks AFK state, with no mine-world exclusion. Therefore native breaking alone would open a separate miner-money/job-XP channel for enrolled, eligible players. Its base money is 2.5 Vault per ceil(10−level/10) accepted blocks, scaled by level and existing boosts; it is not part of the shift reward.

The paired configuration adds only `in_world(world=rc_atelier_compact_mine), inverse=true` to Spawn miner's shared `&pickaxe` conditions. That anchor is used by both miner XP and money. Libreforge 2026.33's pinned `ConditionInWorld`, `Conditions.makeBlock` and `ConditionBlock` confirm the scalar argument, block-location world and inversion semantics (artifact SHA-256 `50320eac38f4a71ea43a3b688a0c89cd6d9f243175e6eb641b22a25f41e8794c`). Other worlds and jobs retain their conditions.

Before → after, managed mine blocks: EcoJobs money **0 → 0 Vault**, miner-job XP **0 → 0 job XP**, vanilla XP **0 → 0**, item drops **0 → 0 units**, tokens **0 → 0**. Existing shift rewards remain 268–284 Vault / 402–426 vanilla XP / one bundle per positive contributor. No item-to-SELL or contract conversion is added. ArcRanks' block listener accepts mature crops only, so these ore materials do not create harvest credit; its mining-shift hook and disabled `mine_job` quest are unchanged.

The current live Spawn miner YAML hash is `255e6ac8d6388cd5c28098f7296781bc0181d2eec0484dd948fbfacad172fc02`, byte-identical to the tracked before file. This compatibility condition must be delivered and activated with the native-break JAR. No third-party binary is modified.


AuraSkills 2.3.12 also observes natural ore breaks. Its active Mining sources use `trigger: break`; ordinary source XP and Mining Luck bonus drops would become new channels despite Bukkit drops/XP being zero. The paired optional API guard cancels only natural configured ore-source Mining XP and `MINING_LUCK` loot in active managed mine worlds. Other skills, combat loot, manual/source-null XP and other worlds remain outside this guard. Before → after: **0 → 0 AuraSkills Mining XP**, **0 → 0 Mining Luck bonus item units**. These units are separate from vanilla XP and EcoJobs job XP. AE mining activations are checked separately using its native per-world/enchant configuration.


AdvancedEnchantments 9.24.15 is active. Its eleven pure `MINING` enchantments are excluded only in `rc_atelier_compact_mine` through the documented native `disable-enchants` world map. This keeps extra XP, souls, bonus/smelted drops, additional block excavation and mining-only buffs off the managed ore path, matching the former cancelled-event path. Existing combat/world settings remain unchanged. Two mixed combat/mining durability enchantments (`reforged`, `unbreakable`) retain their native behavior so this world exception does not remove their attack hooks; accepted native mining can therefore benefit from their repair behavior. This affects possible tool lifetime, not a new currency, XP or item grant; the cost/throughput effect is unmeasured. The native durability change is within restoring ordinary block behavior. No new source amount, probability or progression multiplier is added. Before → after direct AE ore channels: **0 → 0 vanilla XP**, **0 → 0 AE souls**, **0 → 0 bonus item units**.
