# Basic mine cycle — 2026-09-09

The existing V2 module remains the owner of indexing, journaled block restoration,
incident state, objective targets and reward claims. `mining-only` selects direct
mining with an interruption at half quota, followed by automatic completion.
The initial runtime profile uses one CREATURE_NEST invasion (three husks defeated,
six visible targets); no extra mob drops or XP. Existing extraction regions and
weighted restoration materials are unchanged. Only exposed indexed blocks are
selected. The original expedition flow remains available for other profiles.

`guidance-radius: 128` includes the lift landings without granting block editing
outside each worksite. The nearest worksite favours the current floor. An eligible
player entering this area starts the order automatically after index readiness;
idle preparation and cooldown remain visible. Startup reindexes basic idle zones.
Incompatible unfinished expedition phases reset without replaying completion;
pending block journals still restore before a new order can start.

## Balance

See mine-basic-balance-20260909.json. Before: 16 mined blocks, 3 prospecting,
4 loading, route and 3–5 incidents. After: 16 mined blocks and one invasion.
The shared reward multiplier declines from at least142% to118%; old_shafts
completion becomes259 XP and4 iron rather than at least312 XP and5 iron.
Other zones become147 XP (base125). Monetary reward stays0 vault,0 tokens.
Weighted block drops and Fortune/Silk Touch semantics remain unchanged;
ordinary item mint is not a Vault payout. No reward from idle presence: only
positive contributors receive the existing durable per-sequence grant.

The model intentionally assumes twice as many completed cycles: the simpler loop
is faster. Rates are not measured and do not establish income per hour. The
60-second per-zone cooldown bounds completion to at most60 cycles/hour/zone
before action time; four zones can be alternated, so this is not a player-wide cap.
An extreme bound is240 zone completions/hour with240*16 mined blocks across
all zones; party participants share physical mining progress but each positive
contributor retains a reward. No new multiaccount policy is introduced.

Live ledger window at1788968727029 covers168h,18 players, with one crossing
record excluded. Farms source minted30178.75 vault in30 operations among3 players;
this cannot distinguish mine rates (mine direct money is zero) and is not session
throughput. Legacy mixed-currency totals were not used. SELL/auto-sale remains a
current possible conversion path; the planned SELL-to-contract transition is
preserved. Contract weekly envelope observed250000 vault; conversion shares and
item sale frequency are not measured. No new prices or progression purchase
requirements; no accumulation-time claim. Repeated clicks remain journal-gated,
completion uses the existing reward ledger. Enemy material transformations are
avoided by using husks instead of silverfish.

## Lift

Speed6→16 blocks/s, smoothstep maximum retained; boarding3→1.5s,
docking5→2s. Full85-block travel falls from21.25s to8s of motion
(plus boarding/docking); no change to floor exits or passenger recovery.

## Verification

Focused domain, lift and journaled-mining tests passed before final integration.
Final source/config checks and delivery receipts are recorded at completion.
