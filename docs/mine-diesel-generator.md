# Dead Factory diesel generator

The modern factory includes one editable `decor_diesel_generator` furnishing
at local `(24, 5, 10)`, yaw 180 degrees. Its open service face points toward the
central aisle. Existing geometry-v3 scenes gain the furnishing through the
normal reconciliation owner; no terrain regeneration or journal migration is
needed. Legacy factory plans retain their original models.

`MineDieselGeneratorModel` owns the block-display parts: radiator and fan,
inline-six cylinder bank, visible crankshaft and piston/rod linkage, coolant
hoses, inlet/exhaust manifolds, turbocharger, muffler, flywheel, coupling,
alternator, starting battery and instrument cabinet. This is an enlarged
industrial cutaway, not a dimensionally exact replica of a manufactured engine.
The layout reference is the radiator-cooled, inline-six
[Cat C18 generator package](https://emc.cat.com/pubdirect.ashx?media_string_id=LEHE1817-).

The crank, flywheel and alternator share the longitudinal Z axis at Y=1.8.
`MineDieselGeneratorMotion` supplies exact slider-crank endpoint transforms,
with radius 0.45, rod length 2.5 and paired cylinder phases 1/6, 2/5 and 3/4.
The preview mirrors these transforms. One displayed revolution takes three
seconds so the mechanism can be inspected; this is not an engine RPM simulation.
The exposed camshaft turns once per two crank revolutions. Twelve cam noses
drive separate inlet/exhaust followers, with a visible front timing belt.
Runtime and preview share a six-second, 720-degree crank cycle so the cam does
not jump back after the first crank revolution. The split head covers expose
this mechanism from the aisle and the dedicated camshaft preview camera.

`MineFactoryPresentation` powers the display from the existing commissioning
state. A stopped or jammed crusher does not switch off a commissioned supply.
Completion/scene cleanup stops updates and turns the indicator red. After repair
and cooling, the existing generator checkpoint requires eight shared flywheel
clicks at least 250 ms apart, followed by a six-second run-up. This replaces the
old checkpoint action without changing fuel consumption or rewards.
All parts use `MineExpeditionMarkers` and `PaperPacketDisplays`; static parts
are not updated by the rotating-parts pass.

For the standalone inspection stage:

```sh
./gradlew -I scripts/mine-preview/drive.gradle exportMineDrive \
  -PmineDrivePreviewKind=factory_diesel_generator -PminePreviewOut=/tmp/diesel-source
```

Use `scripts/mine-preview/build.mjs` with the exported
`diesel_generator.atelier.json` and the installed Atelier asset pipeline.
The complete factory export also has service, linkage, radiator and reverse
camera presets. Neither export is evidence of Minecraft client rendering.

Focused checks cover linkage endpoints through a full revolution, the whole
swept model envelope against factory blocks and walking routes, legacy-plan
exclusion, and commissioning/jam/completion behavior. The shared face validator
audits all model poses; its conservative AABB rejection avoids comparing every
face of distant details while preserving the original plane tolerance.

## Shared inspection guide

`MineEngineGuide` registers one priority-100 source with `ArcInspectionService`
from Core's Paper API. ARC hosts the sole `PaperArcInspectionService` renderer
and keeps its existing ItemInfo source at priority 0. Player preferences choose
the hologram, bossbar or disabled mode; scale and position settings still apply.
The service/API are supplied by ARC; ArcFarms does not shade a second API or
inspection renderer. Deploy the matching ARC and ArcFarms versions together.

The 19 inspection IDs on `MineDieselGeneratorModel` link parts to titles and short
explanations in both locale files. The marker owner selects the nearest animated
cuboid within eight blocks, using its current phase, yaw, scale and editor offset.
World blocks and unlabelled model casing occlude labels. A miss falls through to
ordinary ItemInfo; unlabelled casing deliberately suppresses the block behind it.
Registration follows existing visual reconciliation and is closed on cleanup;
after an ARC module reload it binds to the replacement service.

The cutaway exposes both valve heads in every cylinder, their moving stems and
springs, plus a stationary injector extending into the chamber. Select the
“Разрез: клапаны и форсунка” preview camera to inspect these parts. The web guide
uses the exported model IDs and the same Russian locale descriptions; move the
pointer over a part. In Minecraft, aim the crosshair instead.

Guide tests cover animated/rotated selection, solid occlusion, source replacement,
empty suppression and formatting. Core owns arbitration, mode switching and
viewer cleanup tests; ARC tests preserve its existing block names and preferences.

## Disable the cutaway

`ui.diesel-generator-enabled` defaults to `true`. Set it to `false` and run the
normal `/arcfarms reload` to remove the large cutaway from active, retained and
editor scene markers and stop its animation, particles, sound and inspection
source. The ordinary flywheel start checkpoint and factory production logic
remain available; re-enabling restores it during the next active-scene reconciliation.
