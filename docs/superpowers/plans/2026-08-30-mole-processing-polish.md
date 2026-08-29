# ArcFarms Mole And Processing Polish Implementation Plan

> **For Codex:** Execute this plan in the current session with focused red/green tests and verify the live candidate only on QA.

**Goal:** Make mole burrows build quickly without hiding tick cost, show exact lair distance, and make processing props stable and readable without moving the saved output point.

**Architecture:** Keep slot identity in durable `FarmProcessingState` instead of deriving it from remaining counts. Reuse the farm activity bossbar while a player is inside a mole tunnel, backed by the scene's existing BFS distance map. Treat the manually saved processing output position as authoritative; only change the label's relative height and rendering.

**Tech Stack:** Kotlin 2.3, Paper 1.21, Kotest, Gradle, MockBukkit, Spark profiler, RusCrafting QA player-bot tooling.

---

### Task 1: Lock regressions with focused tests

**Files:**
- Modify: `src/test/kotlin/ru/ruscrafting/farms/domain/FarmProcessingTest.kt`
- Modify: `src/test/kotlin/ru/ruscrafting/farms/domain/FarmMoleGuidanceTest.kt`
- Modify: `src/test/kotlin/ru/ruscrafting/farms/config/ArcFarmsConfigTest.kt`
- Modify: `src/test/kotlin/ru/ruscrafting/farms/paper/farm/incident/FarmIncidentLifecycleMockBukkitIntegrationTest.kt`

1. Add tests proving a consumed processing slot never reappears and legacy count-only state maps to deterministic consumed slots.
2. Add tests for exact mole distance bossbar progress.
3. Change default expectations to 256 mole blocks/tick and crank radii 2.4/4.0.
4. Add scene assertions for no input labels and a centered upright output label.
5. Run only the affected tests and confirm the new assertions fail for the expected reasons.

### Task 2: Implement stable processing visuals and interaction geometry

**Files:**
- Modify: `src/main/kotlin/ru/ruscrafting/farms/domain/FarmShift.kt`
- Modify: `src/main/kotlin/ru/ruscrafting/farms/domain/FarmProcessingLayout.kt`
- Modify: `src/main/kotlin/ru/ruscrafting/farms/paper/farm/incident/processing/FarmProcessingIncident.kt`
- Modify: `src/main/kotlin/ru/ruscrafting/farms/config/ArcFarmsLocale.kt`
- Modify: `src/main/resources/config.yml`

1. Persist consumed input/output slot indexes with backward-compatible helpers.
2. Pass cargo slot identity through both click and proximity delivery paths.
3. Render only remaining original slot indexes; render delivered output by original identity.
4. Remove raw-input holograms.
5. Shift the crank annulus outward by exactly one block.
6. Keep the saved output point and item-display transform unchanged; center the output label at a model-derived 1.25-block height with vertical billboard behavior.
7. Run the focused processing tests until green.

### Task 3: Add exact mole-lair bossbar and faster build budget

**Files:**
- Modify: `src/main/kotlin/ru/ruscrafting/farms/domain/FarmMoleGuidance.kt`
- Modify: `src/main/kotlin/ru/ruscrafting/farms/paper/farm/care/mole/FarmMoleBurrowWorld.kt`
- Modify: `src/main/kotlin/ru/ruscrafting/farms/paper/farm/care/mole/FarmMoleBurrowController.kt`
- Modify: `src/main/kotlin/ru/ruscrafting/farms/paper/farm/FarmModule.kt`
- Modify: `src/main/kotlin/ru/ruscrafting/farms/config/ArcFarmsLocale.kt`
- Modify: `src/main/resources/lang/ru.yml`
- Modify: `src/main/resources/lang/en.yml`
- Modify: `src/main/resources/config.yml`

1. Expose exact BFS distance and normalized route progress from a mole scene.
2. Override the farm bossbar only for players inside the active tunnel and reconcile it through the existing activity-bar owner.
3. Raise the configurable mole mutation budget from 48 to 256 blocks/tick.
4. Run focused mole/config tests until green.

### Task 4: Synchronize deploy configuration without moving output

**Files:**
- Modify: `/private/tmp/ruscrafting-ops-deploy.3V8w09/classic/plugins/ArcFarms/config.yml`
- Modify: `/private/tmp/ruscrafting-ops-deploy.3V8w09/classic/plugins/ArcFarms/lang/ru.yml`
- Modify: `/private/tmp/ruscrafting-ops-deploy.3V8w09/classic/plugins/ArcFarms/lang/en.yml`
- Modify: `/private/tmp/ruscrafting-ops-deploy.3V8w09/classic_survival/plugins/ArcFarms/config.yml`
- Replace from spawn: `/private/tmp/ruscrafting-ops-deploy.3V8w09/classic_survival/plugins/ArcFarms/lang/{ru,en}.yml`

1. Apply only the new defaults/messages to the tracked deployment configuration.
2. Confirm `farm-points.yml` and the saved `processing-output` coordinates have no diff.
3. Mirror the spawn ArcFarms messages and UI background settings to survival without copying spawn zones or coordinates.
4. Build the shadow JAR and record its SHA-256.

### Task 5: QA events and Spark profiling

**Files:**
- Inspect: `/private/tmp/ruscrafting-ops-deploy.3V8w09/scripts/player-bot/**`
- Inspect: QA server logs and Spark report

1. Deploy the candidate to the QA server only and restart that QA instance.
2. Start a bounded Spark profiler session.
3. Launch moles, processing, and additional representative farm events through the admin/player-bot flow.
4. Stop Spark, record TPS/MSPT and the hottest main-thread stacks, and compare burrow completion time.
5. Delegate one visual QA pass for the bossbar, removed input labels, wider crank ring, and centered output label; do not let the tester mutate code or the saved output point.

### Task 6: Final verification and delivery

**Files:**
- Verify: all changed ArcFarms and ops files

1. Run `./gradlew test shadowJar` plus the relevant integration test target.
2. Inspect full diffs, ensure output point configuration is untouched, and verify clean artifact hashes.
3. Commit and push only if the user's release instruction for this increment authorizes it; otherwise report the ready commits/diffs and QA evidence without production deployment.
