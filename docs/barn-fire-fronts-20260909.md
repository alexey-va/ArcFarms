# Barn fire fronts and compact shop

Requested behavior: alternate spreading between independent fires, favor wooden
structures and hay, avoid using the road to fill the hotspot quota.

Planning now extends one adjacent point per seed front in round-robin order.
When valid wooden/hay surfaces exist, only that pool is used; a completely
non-fuel scene retains the previous supported-surface fallback. Elevated hay
wins over nearby non-fuel ground. Runtime growth requires an adjacent still-lit
point. Unignited points are reordered after the stable ignited prefix, preserving
existing hotspot indexes and the persisted progress+active prefix invariant.
Extinguished points are not reused; disconnected extinguished fronts cannot
restart themselves. Existing saved plans are retained, with adjacency enforced.

Economy before/after: no changes to FARM-point prices, unit contributions per
extinguished hotspot, Vault grants, premium tokens, XP, item rewards, growth
interval or configured pulse/count caps. The candidate pool can be smaller when
only limited fuel is available, and putting out a whole front prevents further
work there. This changes routing and possibly event duration; no measured
currency/hour claim is made. Canonical context remains
ruscrafting-ops/docs/knowledge/economy-inventory.md.

Shop tables use width280 instead of468 and LABEL_WIDE; the wallet has balance
and active-perk count only. Resource-pack pixels and prices remain unchanged.

Focused verification: FarmBarnFireTest, FarmBarnFireIncidentMockBukkitTest,
FarmDialogTablesTest and FarmShopDialogTest; shadowJar. Offline native-renderer
shop QA: 13 one-line rows, two wallet rows, white glyph spans, no clipping.
Live fire gameplay has not been exercised.
