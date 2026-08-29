# ItemsAdder ItemDisplay grounding skill

Date: 2026-08-29  
Status: approved design, implementation pending

## Problem

ArcFarms uses ItemsAdder-backed `ItemDisplay` entities for large world props.
Their visible position cannot be derived from the entity location or ArcFarms
`y-offset` alone. The client applies, in order, model element geometry and
rotations, the selected item display context such as `ground`, and the display
entity transformation. Models may use coordinates outside the ordinary
`0..16` cube, so a zero offset can leave a prop floating or below the terrain.

The development agent must determine grounding before it places or configures a
custom model. The owner must not have to open Blockbench and tune offsets by
eye. Manual offsets remain possible only as documented semantic overrides for
models which intentionally cross their support plane.

The first production proof is
`elitecreatures:medieval_market_decoration_v1_cart_2`: `PAPER`, custom model
data `10747`, `GROUND`, ArcFarms scale `4.4`. Its source model contains negative
coordinates and its JSON `ground` transform adds its own translation and scale.

## Scope

This change creates a user-owned Codex skill named
`itemsadder-item-display-grounding`, its deterministic analysis and rendering
helpers, and an ArcFarms repository rule that requires a grounding report for
new custom `ItemDisplay` placement.

The skill is a development tool. It does not add a player or administrator
command, change ArcFarms runtime behavior, edit ItemsAdder content, publish a
resource pack, or mutate a Minecraft world. Production placement remains a
separately authorized operation through the existing RusCrafting workflows.

## Inputs and result

The analyzer accepts:

- an explicit ItemsAdder root;
- either a namespaced ItemsAdder ID or an exact material/custom-model-data pair;
- an item display context (`NONE`, `GROUND`, `FIXED`, or `HEAD`);
- display-entity translation, left rotation, scale, and right rotation;
- entity yaw;
- either a flat support-plane Y or explicit terrain samples;
- an optional semantic contact override with a required reason.

The machine-readable result contains:

- resolved namespace, item, material, custom-model-data and model path;
- provenance and consistency checks for content, allocation cache, and an
  optional generated resource pack;
- raw, model-context and world-space bounds;
- the lowest visible support height and horizontal support footprint;
- the required entity Y or configuration `y-offset`;
- contact residuals for every supplied terrain sample;
- one status: `grounded`, `floating`, `intersecting`, `terrain_mismatch`, or
  `ambiguous`;
- warnings which make the result unsuitable for automatic placement.

Human output is a compact grounding report. Numbers retain sufficient
precision to reproduce the transform; configuration suggestions are not
silently rounded to a visually convenient value.

## Model resolution

Resolution is fail-closed and treats the generated client pack as the strongest
available evidence:

1. Parse ItemsAdder content declarations to resolve the current namespace,
   material and `model_path`.
2. Read `storage/items_ids_cache.yml` to resolve or reverse-resolve the custom
   model data allocation. Its material group is allocation evidence, not
   necessarily the current semantic material.
3. When a generated pack is available, resolve its modern item definition and
   model chain. That definition is the client-facing authority.
4. Resolve model parents without escaping the selected ItemsAdder root or
   generated archive.
5. Reject missing, multiple or contradictory identities. In particular, a
   material mismatch between content, cache and generated definition must be
   reported rather than guessed away.

The skill defaults to the canonical `classic` ItemsAdder tree only when that
path exists and is proven to belong to the expected RusCrafting layout. Other
repositories and hosts pass the root explicitly. It never reads `secret.yml`
or emits resource-pack credentials.

## Geometry pipeline

The analyzer reproduces the Minecraft Java item-model pipeline rather than
using the raw minimum of `elements[].from`:

1. Resolve inherited textures, elements and display settings.
2. Expand every cuboid into corners and declared faces.
3. Apply each element rotation around its declared origin, axis and optional
   rescale behavior.
4. Convert model units and pivot to the client item coordinate system.
5. Apply the selected model display context using its rotation, translation and
   scale in the same order as the client.
6. Apply the display entity matrix as translation, left rotation, scale and
   right rotation.
7. Apply entity yaw and translate into world space.

Bounds are computed from transformed vertices, not from two rotated AABB
corners. Cuboids without declared faces do not establish visible contact.
Texture alpha may refine the visible bounds when a decodable texture exists;
missing, animated, protected or ambiguous alpha data is surfaced explicitly
and never changes structural bounds silently.

The implementation must validate its matrix conventions against Blockbench's
Java Block/Item display preview and Paper's transformation contract. A formula
which merely appears correct for the cart is insufficient.

## Ground and terrain semantics

For a flat plane, the recommended vertical adjustment is the difference
between the plane and the transformed support height. Applying that adjustment
must leave the minimum support residual within `1e-4` block.

For real terrain, the skill samples the surface below the support footprint.
Read-only live placement probes and world snapshots may supply samples when the
target is on RusCrafting. Slabs, carpets, snow layers and other collision shapes
must use their actual support height rather than integer block Y.

A rigid model on uneven terrain cannot always touch at every intended support
point. The analyzer chooses no deceptive compromise:

- any penetration produces `intersecting`;
- clearance at some support points while others touch produces
  `terrain_mismatch`;
- a flat compatible surface produces `grounded`;
- insufficient surface evidence produces `ambiguous`.

Yaw changes the horizontal footprint and therefore requires re-sampling.
Automatic placement must not reuse a report produced for a materially different
yaw, scale, model hash or surface.

## Semantic contact overrides

The geometric minimum is the default support. Some models intentionally contain
hanging ropes, roots, shadows, particle planes or underground parts. Such a
model may declare an override selecting a named contact plane or an explicit
model-space support Y.

An override requires a reason, remains part of the report, and is keyed by model
hash. It cannot suppress resolver contradictions or terrain penetration. A
changed model invalidates the override until it is reviewed again.

## Visual verification

The renderer consumes the analyzer's result rather than reimplementing the
math. It shows:

- textured model geometry when textures are available;
- the entity origin and pivot axes;
- the support plane or sampled terrain;
- the world-space bounding box;
- contact, clearance and penetration points with exact residuals.

Blockbench's `Frame` / `Invisible Top` display preview is the external oracle
for the model-context portion. Paper's Transformation Visualizer is a secondary
oracle for the display-entity matrix. The custom renderer is useful evidence,
but it is not allowed to declare its own math correct.

## Agent workflow and gate

The skill triggers for ItemsAdder or custom-model `ItemDisplay` creation,
placement, movement, scale/rotation changes, and review inside ArcFarms or a
RusCrafting world scene.

Before proposing coordinates or changing configuration, the agent must:

1. resolve the current client model;
2. obtain or state the target support surface;
3. run the grounding analyzer with the intended transform and yaw;
4. inspect the visual result when the model is nontrivial or the report is not
   unambiguously grounded;
5. retain the command and compact result as verification evidence;
6. stop instead of placing when the result is ambiguous, intersecting or a
   terrain mismatch not explicitly accepted by the owner.

ArcFarms `AGENTS.md` will encode this gate so future development does not rely
only on skill discovery.

## Implementation layout

The user-owned skill lives outside the ArcFarms checkout:

```text
itemsadder-item-display-grounding/
  SKILL.md
  scripts/
    analyze_itemsadder_display.py
    render_itemsadder_display.py
  references/
    minecraft-item-transform.md
  tests/
    fixtures/
    test_analyze_itemsadder_display.py
```

The analyzer owns identity resolution and geometry. The renderer only presents
an analyzer result. `SKILL.md` owns routing, workflow and safety gates. The
reference documents the verified coordinate conventions and evidence sources.

ArcFarms receives only the repository rule and this design document. No
machine-specific ItemsAdder path, copied vendor model or generated pack is
committed to ArcFarms.

## Verification

Deterministic tests cover:

- a centered vanilla cube on a full-block plane;
- a model with negative coordinates;
- rotated and rescaled elements where a raw AABB minimum is wrong;
- all supported item display contexts;
- entity translation, rotations, nonuniform scale and yaw;
- reverse resolution from material/custom-model-data;
- parent model resolution and path confinement;
- content/cache/generated-definition agreement and contradiction;
- full blocks, slabs and incompatible uneven terrain;
- semantic override hash invalidation;
- rendering from the exact analyzer result.

The production smoke runs against the current canonical cart model and records
its SHA-256, resolved identity, transformed support height, recommended
ArcFarms offset and final contact residual. A visual render must show the cart
support on the plane without penetration. If a current generated pack is not
available locally, the report states that the client definition was not
verified and does not upgrade that evidence to a production guarantee.

## Completion criteria

The work is complete when:

- the installed skill routes future ItemsAdder `ItemDisplay` placement through
  the grounding workflow;
- its analyzer and renderer pass their deterministic tests;
- the cart `10747` smoke produces a reproducible grounding report and visual;
- applying the recommendation yields a contact residual within `1e-4` block;
- contradictory identity and uneven-terrain fixtures fail closed;
- ArcFarms contains the mandatory grounding rule;
- no ItemsAdder content, runtime state or production world was mutated.
