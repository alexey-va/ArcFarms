# Mine V2 extraction cart grounding

The scene uses `elitecreatures:medieval_market_decoration_v1_cart_2` as
`PAPER:10747`, `ItemDisplayTransform.GROUND`, entity scale `3,3,3`, and a
grounding offset of exactly `+0.5625` blocks above the sampled floor surface.

Analyzer command:

```bash
python3 -B /Users/alexey23/.codex/skills/itemsadder-item-display-grounding/scripts/analyze_itemsadder_display.py \
  --itemsadder-root /Users/alexey23/RusCrafting/.deploy-ruscrafting-ops/classic/plugins/ItemsAdder \
  --resource-pack /Users/alexey23/RusCrafting/ArcRanks/build/reports/.surface-cache/resource-pack-3049c78a7c43a1038b1863b4a4b53fcaf24269705584bf996f3df19c04c6c2c6.zip \
  --item-id elitecreatures:medieval_market_decoration_v1_cart_2 \
  --context ground --scale 3,3,3 --yaw 0 \
  --entity-position 0,64,0 --surface-y 64 \
  --output docs/evidence/mine-cart-grounding.json --report
```

- Status: `grounded`; automatic placement suitable.
- Model SHA-256: `06fcde3cc7fdd62b8bcd5920dbd806c3867dd719abcae279c8fc11f58a3d425c`.
- Structural minimum before adjustment: `-0.5625`.
- Recommended entity Y for surface Y=64: `64.5625`.
- Post-adjustment residual: `0` (tolerance `1e-4`).
- Exact generated/client-pack textures resolved and embedded into the retained HTML render.
- Analyzer warning retained: it cannot independently prove that this cached pack is the pack currently selected by every production client.

The full numeric and textured-face evidence is generated locally as
`mine-cart-grounding.json` by the command above. The generated 17k-line JSON is
intentionally ignored; this document retains the reviewed placement inputs and
result needed to reproduce it.
