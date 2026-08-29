# ItemsAdder ItemDisplay Grounding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to execute this plan task by task.

**Goal:** Create and verify an automatically discoverable Codex skill that resolves an ItemsAdder item model, reproduces Minecraft's item/display transforms, computes the exact entity Y needed for ground contact, and proves the result on the ArcFarms cart in the QA world.

**Architecture:** A user-owned skill contains a deterministic Python analyzer and a renderer. The analyzer is the sole owner of model resolution, transform math, contact classification, and machine-readable output; the renderer consumes that output. ArcFarms adds a repository gate requiring the report before custom `ItemDisplay` placement. The live smoke test uses a tagged `ItemDisplay` in `arc_qa_flat`, not ItemsAdder furniture placement.

**Tech Stack:** Python 3 standard library, PyYAML when available, JSON/ZIP resource-pack parsing, `unittest`, Paper 1.21.11 display entities, ItemsAdder 4.x content layout, ArcFarms Kotlin/Paper configuration.

**Spec:** `docs/superpowers/specs/2026-08-29-itemsadder-item-display-grounding-design.md`

## Global constraints

- Never read or print ItemsAdder `secret.yml` or resource-pack credentials.
- Treat the generated client item definition as stronger evidence than the allocation cache.
- Fail closed on unresolved identity, path escape, unsupported transform, or contradictory client evidence.
- Do not modify canonical ItemsAdder content or production worlds.
- The only live mutation is one uniquely tagged test display in `arc_qa_flat`, explicitly authorized by the owner.
- Use tests before implementation for transform and resolver behavior.

### Task 1: Build failing geometry and contact tests

**Files:**
- Create: `/Users/alexey23/.codex/skills/itemsadder-item-display-grounding/tests/test_analyze_itemsadder_display.py`
- Create: `/Users/alexey23/.codex/skills/itemsadder-item-display-grounding/tests/fixtures/`

- [ ] Add fixtures for a centered cube, negative coordinates, rotated/rescaled elements, inherited parents, identity agreement/contradiction, full block, slab, and uneven terrain.
- [ ] Add assertions for element rotation, Minecraft model-unit centering, `ground`/`fixed`/`head`/`none` contexts, entity `T * L * S * R`, yaw, support offset, and `1e-4` contact tolerance.
- [ ] Run `python3 -m unittest discover -s .../tests -v` and capture the expected initial failure because the analyzer does not exist.

### Task 2: Implement the deterministic analyzer

**Files:**
- Create: `/Users/alexey23/.codex/skills/itemsadder-item-display-grounding/scripts/analyze_itemsadder_display.py`

- [ ] Implement safe ItemsAdder content/cache/generated-pack resolution and parent-model confinement.
- [ ] Implement cuboid vertex expansion, element rotations including `rescale`, model centering, display-context transform, display-entity transform, yaw, world bounds, and support footprint.
- [ ] Implement flat-plane and sampled-terrain classifications plus hash-bound semantic overrides.
- [ ] Provide JSON output and a compact human report with exact recommended entity Y/config `y-offset`.
- [ ] Run the complete unit suite until it passes.

### Task 3: Create the skill instructions and renderer

**Files:**
- Create: `/Users/alexey23/.codex/skills/itemsadder-item-display-grounding/SKILL.md`
- Create: `/Users/alexey23/.codex/skills/itemsadder-item-display-grounding/agents/openai.yaml`
- Create: `/Users/alexey23/.codex/skills/itemsadder-item-display-grounding/references/minecraft-item-transform.md`
- Create: `/Users/alexey23/.codex/skills/itemsadder-item-display-grounding/scripts/render_itemsadder_display.py`

- [ ] Document automatic routing, required evidence, stop conditions, terrain sampling, and exact authorization boundary for live placement.
- [ ] Record source-backed coordinate conventions and external visual oracles.
- [ ] Render a self-contained HTML/SVG view from analyzer JSON without redoing transform math.
- [ ] Add a renderer test that checks the analyzer's vertices, support plane, origin and status are represented.
- [ ] Run the system skill validator and all skill tests.

### Task 4: Add the ArcFarms grounding gate

**Files:**
- Modify: `/Users/alexey23/RusCrafting/ArcFarms/AGENTS.md`

- [ ] Require the installed grounding skill and a non-ambiguous report before ItemsAdder/custom-model `ItemDisplay` placement or transform changes.
- [ ] Require re-analysis when model hash, display context, scale, rotation, yaw, or surface changes.
- [ ] Commit the repository plan and gate without committing machine-specific paths or vendor assets.

### Task 5: Prove the current cart offline

**Inputs:**
- `/Users/alexey23/mcserver/classic/plugins/ItemsAdder/contents/elitecreatures/`
- Item: `elitecreatures:medieval_market_decoration_v1_cart_2`
- Runtime transform: `PAPER`, custom-model-data `10747`, `GROUND`, uniform scale `4.4`, yaw `0`

- [ ] Resolve and hash the current cart model and, when obtainable, the active generated client definition.
- [ ] Run the analyzer against a flat full-block surface and save the JSON grounding report and HTML render in a temporary evidence directory.
- [ ] Verify the post-adjustment minimum residual is at most `1e-4` block and the report is suitable for automatic placement.
- [ ] Independently compare the model-context result with the ItemsAdder/Blockbench display-preview convention and the entity matrix with Paper's documented contract.

### Task 6: Place and verify the cart in the QA world

**Live scope:** `classic_survival`, world `arc_qa_flat`, one entity tagged `arcfarms_grounding_cart_10747`.

- [ ] Prepare/read the QA fixture and obtain an exact flat support Y and safe inspection coordinates.
- [ ] Summon a real `minecraft:item_display` with the current paper/CMD item, `ground` context, scale `4.4`, and entity Y from the analyzer.
- [ ] Read the spawned entity back by its unique tag and confirm item, transform, world position, and persistence.
- [ ] Provide server, world, coordinates, computed offset, residual, visual artifact, and an exact cleanup command.
- [ ] Do not claim completion until the skill validator, deterministic tests, cart smoke, render, and live entity readback all succeed.
