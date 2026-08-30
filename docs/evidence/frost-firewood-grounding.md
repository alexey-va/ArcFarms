# Frost firewood pile grounding

The frost incident uses `elitecreatures:farmer_decoration_v1_firewood` as
`PAPER:11866`, `ItemDisplayTransform.GROUND`, entity scale `3,3,3`, and a
grounding offset of exactly `+0.375` blocks above the saved contact surface.
The saved admin-point yaw is preserved while pitch is always normalized to
zero, so moving the point cannot tilt the pile.

Analyzer command:

```bash
python3 -B /Users/alexey23/.codex/skills/itemsadder-item-display-grounding/scripts/analyze_itemsadder_display.py \
  --itemsadder-root /Users/alexey23/RusCrafting/.deploy-ruscrafting-ops/classic/plugins/ItemsAdder \
  --resource-pack /private/tmp/RusCraftingResource.zip \
  --item-id elitecreatures:farmer_decoration_v1_firewood \
  --context ground --scale 3,3,3 --yaw 0 \
  --entity-position 0,0.375,0 --surface-y 0 \
  --output /private/tmp/arcfarms-frost-firewood-grounding-final.json --report
```

- Status: `grounded`; automatic placement suitable.
- Active public pack URL from the server configuration:
  `https://storage.yandexcloud.net/ruscraftinresources/RusCraftingResource.zip`.
- Downloaded pack SHA-256: `35c88e0e9f88ae5e1739e403f2a7cf95880388403788c405989cd1f8d0047e8e`.
- Model SHA-256: `597213217e0b31ae27aa09c701590de4fa2046b8be8d6cc399167acb9c542876`.
- Source-model SHA-256: `e8a58760c361d2892ada9b46ce559e832205d3e810580c4d2125dc3b5dfc0977`.
- Textured world bounds at the configured transform: Y `[0, 0.5625]`.
- Post-adjustment contact residual: `0` (tolerance `1e-4`).
- Exact client-pack texture resolved at
  `assets/elitecreatures/textures/farmer_decoration_v1/farmer_firewood.png`.

The analyzer conservatively leaves `production_client_verified=false`; the
pack identity above was established separately by downloading the exact public
URL configured by the server and hashing that archive.
