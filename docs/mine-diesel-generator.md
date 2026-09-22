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

`MineFactoryPresentation` powers the display from the existing commissioning
state. A stopped or jammed crusher does not switch off a commissioned supply.
Completion/scene cleanup stops updates and turns the indicator red. No new
interaction, production checkpoint, fuel consumption or reward is introduced.
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
