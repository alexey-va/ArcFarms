# Ordinary mine resource orders — 2026-09-09

Owner correction: the foreground baseline counts any matching mined resource in
its managed region, not selected/highlighted vein targets. Reuse existing order
rotation, MineShiftEngine progress, incident resume, block journal and shared HUD
and reward ledger. One mined ore block is one unit; Fortune/Silk Touch do not
multiply order credit. Physical drops retain their existing behavior.

## Balance assessment before implementation

Before: 16 arbitrary selected blocks, one invasion, 118% shared difficulty.
After: 100 matching ore blocks, one invasion, 160% shared difficulty. Coal/iron
orders alternate where those ores occur; infernal orders request quartz ore.
Regeneration weights, 60-second restoration and cooldown, payout bases and
probabilities are unchanged. Old Shafts completion XP259 →352, iron4 →6;
other zones XP147 →200. Direct vault0 →0 and tokens0 →0.
Low/base/high assumptions: old 3/12/48 versus new 0.5/2/8 completions/player-day,
0.5/1/2 active hours/day, 2/5/10 participants. Old Shafts XP/day777/3108/12432
→176/704/2816; iron/day12/48/192 →3/12/48. Network-week XP10878/108780/870240
→2464/24640/197120; iron168/1680/13440 →42/420/3360. Money/tokens remain zero.
These are scenarios, not measured rates: arbitrary stone can now be mined to
expose ores, with no quota credit. Native item/XP flow may grow independently of
completion throughput; exact yield depends on weights, tools and player speed.
One positive contributor still qualifies for shared completion rewards. Four
zones can run concurrently; the cooldown is per zone, not a global earning cap.
There is no new SELL or job payout event. Current SELL and budgeted contracts
remain possible later item conversion, not automatic money issuance. The same
item cannot be sold twice; future SELL-off must not be counted as active today.
No purchase price, entitlement or progression cost changes; time-to-purchase is
unknown without measured disposable income. Recovery and durable reward claims
remain the duplicate protection. No additional reward boost is introduced.
Read-only ledger 168h generatedAt1788970572235,18players,640135operations; one
crossing record excluded. Mixed-currency totals are not used as mine earnings.

## Verification and delivery

Focused MineConfigTest, MineBasicCycleMockBukkitTest and
MineProspectingMiningMockBukkitTest passed. They cover the 100-block order with
only two indexed positions, wrong-resource zero credit, matching-resource credit,
no target particles, regeneration reuse, persisted progress and incident resume.
Legacy selected-target journal checks still pass. Packaging and supported
spawn-only delivery follow; full live gameplay throughput is not measured.
