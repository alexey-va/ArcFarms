# Lumbermill V2 Implementation Plan

> **For Codex:** Use `superpowers:executing-plans` to implement this plan task-by-task in the current worktree. Do not delegate this execution; steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the legacy two-stage lumber controller with the full indexed, recoverable, guided lumber shift and all eight specified incidents.

**Architecture:** `LumbermillComponentGraph` wires focused vertical owners into `LumbermillModule`. Pure domain state controls phases, schedules and incident resume behavior. Paper owners use the common objective, guidance, service-item and participant-safety kernel.

**Tech Stack:** Kotlin 2.3.0, Java 25, Paper/Purpur 1.21.11, MockBukkit, Kotest, MockK, chunk PDC, atomic JSON recovery journals.

**Spec:** `docs/superpowers/specs/2026-08-30-worksite-v2-design.md`

## Global Constraints

- Complete `docs/superpowers/plans/2026-08-30-worksite-kernel-v2.md` first.
- Preserve existing lumber button, destination, permission, activity kind and network signals.
- Natural lumber outcomes remain available to the player; duplicate drops are forbidden.
- World mutation is journalled before mutation and restored without physics.
- Generated physical targets request twice the completion quota.
- Hot ticks use the lumber index and loaded chunks only.
- Every message is mirrored in Russian and English.

---

### Task 1: Lumber configuration, orders and phase engine

**Files:**
- Create: `src/main/kotlin/ru/ruscrafting/farms/config/LumberConfig.kt`
- Modify: `src/main/kotlin/ru/ruscrafting/farms/config/ArcFarmsConfig.kt`
- Replace: `src/main/kotlin/ru/ruscrafting/farms/domain/LumberShift.kt`
- Modify: `src/main/kotlin/ru/ruscrafting/farms/domain/PersistedState.kt`
- Test: `src/test/kotlin/ru/ruscrafting/farms/config/LumberConfigTest.kt`
- Test: `src/test/kotlin/ru/ruscrafting/farms/domain/LumberShiftV2Test.kt`

**Interfaces:**
- Produces: `LumberOrderSettings`, `LumberPhase`, `LumberIncidentType`, `LumberIncidentState`, `LumberShiftState`, `LumberRules`, `LumberShiftEngine`.
- Consumers: all lumber Paper owners and state persistence.

- [x] **Step 1: Write failing config and phase tests**

```kotlin
test("lumber shift keeps foreground progress across a distinct incident schedule") {
    val started = LumberShiftEngine.start(LumberShiftState(), order, rules, now = 1_000L).state
    started.phase shouldBe LumberPhase.FELLING
    started.incidentSchedule.distinct().size shouldBe started.incidentSchedule.size
    val interrupted = LumberShiftEngine.startIncident(started, WINDTHROW, required = 3).state
    interrupted.phase shouldBe LumberPhase.INCIDENT
    interrupted.resumePhase shouldBe LumberPhase.FELLING
    LumberShiftEngine.resolveIncident(interrupted).state.phase shouldBe LumberPhase.FELLING
}

test("legacy active state resets once while a cooldown sequence is retained") {
    LumberStateMigration.migrate(legacyProcessingState).phase shouldBe LumberPhase.IDLE
    LumberStateMigration.migrate(legacyCooldownState).sequence shouldBe legacyCooldownState.sequence
}
```

- [x] **Step 2: Run and verify RED**

Run: `./gradlew test --tests '*LumberConfigTest' --tests '*LumberShiftV2Test'`

Expected: V2 phases, order settings and incident state are unresolved.

- [x] **Step 3: Implement parser, state and pure transitions**

Parse `engine-version`, orders, phase quotas, incident count/range, target multiplier and rewards in `LumberConfigParser`. Validate three to five distinct incidents per order and all bounded quotas. Implement transitions:

```text
IDLE -> FELLING -> SKIDDING -> SAWING -> STACKING -> DISPATCH -> COOLDOWN
foreground -> INCIDENT -> exact foreground phase
```

Persist objective state and incident schedule; do not persist entity UUIDs.

- [x] **Step 4: Run focused tests**

Run: `./gradlew test --tests '*Lumber*Test' --tests '*ArcFarmsConfigTest' --tests '*Persistence*Test'`

Expected: PASS including legacy payload deserialization.

- [x] **Step 5: Commit**

```bash
git add src/main/kotlin/ru/ruscrafting/farms/config src/main/kotlin/ru/ruscrafting/farms/domain src/test/kotlin/ru/ruscrafting/farms
git commit -m "feat: define lumbermill v2 orders and phases"
```

### Task 2: Lumber module composition and runtime registry

**Files:**
- Create: `src/main/kotlin/ru/ruscrafting/farms/paper/lumber/LumbermillComponentGraph.kt`
- Create: `src/main/kotlin/ru/ruscrafting/farms/paper/lumber/LumbermillModule.kt`
- Create: `src/main/kotlin/ru/ruscrafting/farms/paper/lumber/LumberRuntimeRegistry.kt`
- Create: `src/main/kotlin/ru/ruscrafting/farms/paper/lumber/LumberRuntimeFactory.kt`
- Modify: `src/main/kotlin/ru/ruscrafting/farms/paper/ArcFarmsService.kt`
- Test: `src/test/kotlin/ru/ruscrafting/farms/paper/lumber/LumbermillModuleLifecycleMockBukkitTest.kt`

**Interfaces:**
- Produces: `LumbermillModule : WorksiteModule<LumberShiftState>` and one authoritative runtime registry.
- Consumes: common registry/lifecycle and V2 config/state.

- [x] **Step 1: Write failing lifecycle scenario**

```kotlin
test("v2 module rebuilds activates and cleans without a second runtime collection") {
    module.rebuild(config, persisted)
    module.zoneCount shouldBe 1
    module.activateLoadedState()
    module.cleanup("reload")
    module.states().keys shouldContainExactly setOf("sawmill")
    graph.mutableRuntimeCollectionCount shouldBe 1
}
```

- [x] **Step 2: Run and verify RED**

Run: `./gradlew test --tests '*LumbermillModuleLifecycleMockBukkitTest'`

Expected: V2 graph/module classes are absent.

- [x] **Step 3: Implement composition-only graph and module coordinator**

`LumberRuntimeRegistry` alone owns runtime instances and lookup by location/id. `LumbermillModule` coordinates feature owners and contains no Paper entity maps. `ArcFarmsService` selects V2 for `engine-version: 2` while retaining the legacy controller only as a migration fallback.

- [x] **Step 4: Run lifecycle/architecture tests**

Run: `./gradlew test --tests '*LumbermillModuleLifecycle*' --tests '*WorksiteLifecycle*' --tests '*ArcFarmsArchitectureContractTest'`

Expected: PASS and `ArcFarmsService.kt` remains at most 600 lines.

- [x] **Step 5: Commit**

```bash
git add src/main/kotlin/ru/ruscrafting/farms/paper/lumber src/main/kotlin/ru/ruscrafting/farms/paper/ArcFarmsService.kt src/test/kotlin/ru/ruscrafting/farms/paper
git commit -m "refactor: compose lumbermill v2 module"
```

### Task 3: Durable lumber index and recoverable log mutation

**Files:**
- Create: `src/main/kotlin/ru/ruscrafting/farms/paper/lumber/index/LumberBlockIndex.kt`
- Create: `src/main/kotlin/ru/ruscrafting/farms/paper/lumber/index/LumberReindexJob.kt`
- Create: `src/main/kotlin/ru/ruscrafting/farms/persistence/LumberRecoveryJournal.kt`
- Create: `src/main/kotlin/ru/ruscrafting/farms/paper/lumber/recovery/LumberBlockRecoveryController.kt`
- Test: `src/test/kotlin/ru/ruscrafting/farms/paper/lumber/index/LumberBlockIndexMockBukkitTest.kt`
- Test: `src/test/kotlin/ru/ruscrafting/farms/paper/lumber/recovery/LumberBlockRecoveryMockBukkitTest.kt`

**Interfaces:**
- Produces: indexed log candidates by species, bounded reindex lifecycle and journal-before-mutation recovery API.
- Consumers: felling and target incidents.

- [x] **Step 1: Write failing index/recovery tests**

```kotlin
test("reindex applies one chunk only after bounded validation") {
    job.tick(blockBudget = 2).finished shouldBe false
    job.tick(blockBudget = 2).finished shouldBe true
    index.logs("sawmill", "OAK") shouldContainExactly indexedAccessibleLogs
    ticket.releasedChunks shouldContainExactly scannedChunks
}

test("log remains intact until journal prepare succeeds and restores once") {
    recovery.prepare(runtime, player, log, tool)
    log.type shouldBe OAK_LOG
    journal.completePrepare()
    log.type shouldBe AIR
    effects.deliveredDrops shouldBe expectedDrops
    recovery.processDue(afterDeadline)
    log.blockData.asString shouldBe originalData
    journal.records() shouldBe emptyList()
}
```

- [x] **Step 2: Run and verify RED**

Run: `./gradlew test --tests '*LumberBlockIndex*' --tests '*LumberBlockRecovery*'`

Expected: index and recovery classes are missing.

- [x] **Step 3: Implement chunk-PDC index and atomic journal**

Copy only the proven two-phase/ticket/budget shape from `FarmBlockRegistry`; use lumber-specific records and validation. Capture block data and calculated drops before submitting the journal. Mutate with physics disabled only in a lifecycle-valid sync callback. Retire records only after confirmed restoration.

- [x] **Step 4: Run focused recovery tests**

Run: `./gradlew test --tests '*LumberBlockIndex*' --tests '*LumberBlockRecovery*' --tests '*RuntimeTaskSupervisor*'`

Expected: PASS for unloaded chunks, stale callback, duplicate preparation and failed retirement.

- [x] **Step 5: Commit**

```bash
git add src/main/kotlin/ru/ruscrafting/farms/persistence src/main/kotlin/ru/ruscrafting/farms/paper/lumber src/test/kotlin/ru/ruscrafting/farms/paper/lumber
git commit -m "feat: index and recover lumber resources"
```

### Task 4: Felling and skidding vertical slice

**Files:**
- Create: `src/main/kotlin/ru/ruscrafting/farms/paper/lumber/felling/LumberFellingController.kt`
- Create: `src/main/kotlin/ru/ruscrafting/farms/paper/lumber/skidding/LumberSkiddingController.kt`
- Create: `src/main/kotlin/ru/ruscrafting/farms/paper/lumber/skidding/LumberBundleScene.kt`
- Test: `src/test/kotlin/ru/ruscrafting/farms/paper/lumber/LumberFellingSkiddingMockBukkitTest.kt`

**Interfaces:**
- Produces: accepted indexed log breaks, two-times bundle objective and safe carry-to-landing flow.
- Consumes: lumber index/recovery, objective pool, service items/safety, module transition sink.

- [x] **Step 1: Write failing full slice scenario**

```kotlin
test("correct indexed logs start the order and bundles cannot be monopolized") {
    breakLog(indexedOak1, playerA)
    breakLog(indexedOak2, playerA)
    runtime.state.phase shouldBe SKIDDING
    bundleScene.visibleBundles shouldHaveSize runtime.rules.skiddingRequired * 2
    bundleScene.pickup(playerA, bundle1)
    safety.release(playerA, ZONE_EXIT)
    bundleScene.status(bundle1) shouldBe AVAILABLE
    deliver(bundle1, playerB)
    runtime.state.skidded shouldBe 1
}
```

- [x] **Step 2: Run and verify RED**

Run: `./gradlew test --tests '*LumberFellingSkiddingMockBukkitTest'`

Expected: feature owners and V2 transitions are absent.

- [x] **Step 3: Implement indexed felling and bundle carry scene**

Reject unindexed/wrong/completed logs without mutation and show the required species title. Reconcile bundle `ItemDisplay`/`Interaction` pairs by PDC identity. A carried bundle follows the player without collision; quit/exit returns it to its safe origin.

- [x] **Step 4: Run slice/recovery tests**

Run: `./gradlew test --tests '*LumberFellingSkidding*' --tests '*LumberBlockRecovery*' --tests '*WorksiteServiceItem*'`

Expected: PASS with exact progress and cleanup.

- [x] **Step 5: Commit**

```bash
git add src/main/kotlin/ru/ruscrafting/farms/paper/lumber src/test/kotlin/ru/ruscrafting/farms/paper/lumber
git commit -m "feat: add lumber felling and skidding"
```

### Task 5: Sawing, stacking and dispatch

**Files:**
- Create: `src/main/kotlin/ru/ruscrafting/farms/paper/lumber/sawing/LumberSawingController.kt`
- Create: `src/main/kotlin/ru/ruscrafting/farms/domain/lumber/LumberSawSequence.kt`
- Create: `src/main/kotlin/ru/ruscrafting/farms/paper/lumber/stacking/LumberStackingController.kt`
- Create: `src/main/kotlin/ru/ruscrafting/farms/paper/lumber/dispatch/LumberDispatchController.kt`
- Test: `src/test/kotlin/ru/ruscrafting/farms/paper/lumber/LumberWorkshopFlowMockBukkitTest.kt`

**Interfaces:**
- Produces: forgiving alternating saw controls, two-times pallet slots and exact dispatch completion.
- Consumes: objective/service item kernel and lumber transitions.

- [x] **Step 1: Write failing workshop flow test**

```kotlin
test("mistimed saw use keeps progress and stacked pallets enable dispatch") {
    saw.use(LEFT, player, now = 1_000L).accepted shouldBe true
    saw.use(LEFT, player, now = 1_100L).accepted shouldBe false
    runtime.state.sawCuts shouldBe 1
    saw.use(RIGHT, player, now = 5_000L).accepted shouldBe true
    completeSawing()
    stacking.visibleSlots shouldHaveSize runtime.rules.stackingRequired * 2
    deliverRequiredPlanks()
    dispatch.ringBell(player).accepted shouldBe true
    runtime.state.phase shouldBe COOLDOWN
}
```

- [x] **Step 2: Run and verify RED**

Run: `./gradlew test --tests '*LumberWorkshopFlowMockBukkitTest'`

Expected: workshop owners do not exist.

- [x] **Step 3: Implement workshop phases**

Controls alternate after an accepted input; wrong/late input only re-highlights the required control. Stacking uses bound plank bundles and extra pallet slots. Dispatch is idempotent and invokes contribution/reward completion once.

- [x] **Step 4: Run focused tests**

Run: `./gradlew test --tests '*LumberWorkshopFlow*' --tests '*ObjectiveTargetPool*' --tests '*WorksiteServiceItem*'`

Expected: PASS.

- [x] **Step 5: Commit**

```bash
git add src/main/kotlin/ru/ruscrafting/farms/domain/lumber src/main/kotlin/ru/ruscrafting/farms/paper/lumber src/test/kotlin/ru/ruscrafting/farms/paper/lumber
git commit -m "feat: add lumber workshop and dispatch"
```

### Task 6: Windthrow and bark-beetle incidents

**Files:**
- Create: `src/main/kotlin/ru/ruscrafting/farms/paper/lumber/incident/windthrow/LumberWindthrowIncident.kt`
- Create: `src/main/kotlin/ru/ruscrafting/farms/paper/lumber/incident/beetle/LumberBarkBeetleIncident.kt`
- Test: `src/test/kotlin/ru/ruscrafting/farms/paper/lumber/incident/LumberForestTargetIncidentsMockBukkitTest.kt`

**Interfaces:**
- Produces: recoverable two-times obstacle targets and indexed infected-face targets.

- [x] **Step 1: Write failing incident scenarios**

```kotlin
test("windthrow and beetles replace invalid targets and resume felling") {
    forceIncident(WINDTHROW)
    windthrow.visibleTargets shouldHaveSize required * 2
    invalidateOneTarget()
    windthrow.availableCount shouldBe required * 2
    resolveWindthrow()
    forceIncident(BARK_BEETLES)
    replaceIndexedLogWithAir()
    beetles.tick()
    beetles.targets.none { it.position == missingLog } shouldBe true
    resolveBeetles()
    runtime.state.phase shouldBe FELLING
}
```

- [x] **Step 2: Run and verify RED**

Run: `./gradlew test --tests '*LumberForestTargetIncidentsMockBukkitTest'`

Expected: incident owners are missing.

- [x] **Step 3: Implement both target incidents with separate owners**

Windthrow journals every temporary obstruction. Beetles mutate no world blocks and accept only the current highlighted indexed face with an axe. Both report bounded placement rejection counters.

- [x] **Step 4: Run tests and commit**

Run: `./gradlew test --tests '*LumberForestTargetIncidents*' --tests '*LumberBlockRecovery*'`

```bash
git add src/main/kotlin/ru/ruscrafting/farms/paper/lumber/incident src/test/kotlin/ru/ruscrafting/farms/paper/lumber/incident
git commit -m "feat: add lumber forest incidents"
```

### Task 7: Saw-jam and warped-batch incidents

**Files:**
- Create: `src/main/kotlin/ru/ruscrafting/farms/paper/lumber/incident/jam/LumberSawJamIncident.kt`
- Create: `src/main/kotlin/ru/ruscrafting/farms/paper/lumber/incident/warped/LumberWarpedBatchIncident.kt`
- Test: `src/test/kotlin/ru/ruscrafting/farms/paper/lumber/incident/LumberWorkshopIncidentsMockBukkitTest.kt`

**Interfaces:**
- Produces: ordered safety-switch/jam flow and two-role plank sorting.

- [ ] **Step 1: Write failing tests**

```kotlin
test("wrong switch and wrong pallet never erase accepted incident progress") {
    jam.useSwitch(2, player).accepted shouldBe false
    jam.completedSwitches shouldBe emptySet()
    jam.useSwitch(0, player).accepted shouldBe true
    jam.useSwitch(2, player).accepted shouldBe false
    jam.completedSwitches shouldBe setOf(0)
    warped.deliver(warpedPlank, ACCEPT, player).accepted shouldBe false
    warped.completed shouldBe 0
    warped.deliver(warpedPlank, REJECT, player).accepted shouldBe true
}
```

- [ ] **Step 2: Run RED, implement separate owners, run GREEN**

Run RED: `./gradlew test --tests '*LumberWorkshopIncidentsMockBukkitTest'`

Implement stable role colors, personal nearest target views and non-resetting sequences.

Run GREEN: `./gradlew test --tests '*LumberWorkshopIncidentsMockBukkitTest' --tests '*WorksiteGuidance*'`

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/ru/ruscrafting/farms/paper/lumber/incident src/test/kotlin/ru/ruscrafting/farms/paper/lumber/incident
git commit -m "feat: add lumber workshop incidents"
```

### Task 8: Conveyor and lost-load incidents

**Files:**
- Create: `src/main/kotlin/ru/ruscrafting/farms/paper/lumber/incident/conveyor/LumberConveyorIncident.kt`
- Create: `src/main/kotlin/ru/ruscrafting/farms/paper/lumber/incident/load/LumberLostLoadIncident.kt`
- Test: `src/test/kotlin/ru/ruscrafting/farms/paper/lumber/incident/LumberCarryIncidentsMockBukkitTest.kt`

**Interfaces:**
- Produces: bound repair kits and recoverable scattered bundles.

- [ ] **Step 1: Write failing lifecycle tests**

```kotlin
test("carried incident objects return after quit death and zone exit") {
    listOf(QUIT, DEATH, ZONE_EXIT).forEach { reason ->
        startIncident(CONVEYOR_BREAKDOWN)
        val item = conveyor.pickupKit(player)
        safety.release(player, reason)
        player.inventory.contains(item) shouldBe false
        conveyor.availableKits shouldBe conveyor.initialKits
    }
}
```

- [ ] **Step 2: Run RED, implement, run GREEN**

Run RED: `./gradlew test --tests '*LumberCarryIncidentsMockBukkitTest'`

Implement service-item identities for belts/gears and PDC-reconciled lost-load displays with safe origins.

Run GREEN: `./gradlew test --tests '*LumberCarryIncidentsMockBukkitTest' --tests '*WorksiteServiceItem*'`

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/ru/ruscrafting/farms/paper/lumber/incident src/test/kotlin/ru/ruscrafting/farms/paper/lumber/incident
git commit -m "feat: add lumber carry incidents"
```

### Task 9: Forest-fire and rush-order incidents

**Files:**
- Create: `src/main/kotlin/ru/ruscrafting/farms/paper/lumber/incident/fire/LumberForestFireIncident.kt`
- Create: `src/main/kotlin/ru/ruscrafting/farms/paper/lumber/incident/rush/LumberRushOrderIncident.kt`
- Test: `src/test/kotlin/ru/ruscrafting/farms/paper/lumber/incident/LumberTimedIncidentsMockBukkitTest.kt`

**Interfaces:**
- Produces: journalled bounded fire with zero-player pause and non-blocking bonus timer.

- [ ] **Step 1: Write failing timer/recovery tests**

```kotlin
test("zero players pauses fire and rush expiry loses only the bonus") {
    forceIncident(FOREST_FIRE)
    tickWithPlayers(0, seconds = 30)
    fire.damagedTargets shouldBe initialDamage
    resolveFire()
    forceIncident(RUSH_ORDER)
    advancePastDeadline()
    rush.bonusEarned shouldBe false
    rush.ordinaryProgress shouldBe progressBeforeExpiry
    rush.canContinue shouldBe true
}
```

- [ ] **Step 2: Run RED, implement, run GREEN**

Run RED: `./gradlew test --tests '*LumberTimedIncidentsMockBukkitTest'`

Implement durable fire intent before mutation, bounded spread/restore, service water tool and a pure bonus deadline that never gates completion.

Run GREEN: `./gradlew test --tests '*LumberTimedIncidentsMockBukkitTest' --tests '*LumberBlockRecovery*'`

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/ru/ruscrafting/farms/paper/lumber/incident src/test/kotlin/ru/ruscrafting/farms/paper/lumber/incident
git commit -m "feat: add lumber timed incidents"
```

### Task 10: Lumber guidance, rewards, admin and locale

**Files:**
- Create: `src/main/kotlin/ru/ruscrafting/farms/paper/lumber/presentation/LumberGuidanceSource.kt`
- Create: `src/main/kotlin/ru/ruscrafting/farms/paper/lumber/admin/LumberAdminService.kt`
- Modify: `src/main/kotlin/ru/ruscrafting/farms/paper/farm/reward/FarmRewardService.kt`
- Create: `src/main/kotlin/ru/ruscrafting/farms/paper/worksite/WorksiteRewardGrantService.kt`
- Modify: `src/main/resources/config.yml`
- Modify: `src/main/resources/lang/ru.yml`
- Modify: `src/main/resources/lang/en.yml`
- Test: `src/test/kotlin/ru/ruscrafting/farms/paper/lumber/LumberGuidanceAdminRewardMockBukkitTest.kt`
- Test: `src/test/kotlin/ru/ruscrafting/farms/config/LocaleParityTest.kt`

**Interfaces:**
- Produces: per-stage views, force-stage/incident/reindex/objective commands and exact-once lumber rewards.

- [ ] **Step 1: Write failing UI/reward tests**

```kotlin
test("every lumber phase and incident exposes a localized next action") {
    allLumberObjectives.forEach { objective ->
        val view = guidance.view(player, runtimeWith(objective))
        plain(view.title).shouldNotBeBlank()
        plain(view.subtitle).shouldNotBeBlank()
        view.targets.groupBy { it.role }.values.forEach { targets -> targets.count { it.nearest } shouldBe 1 }
    }
}

test("dispatch persists and claims one reward") {
    dispatch.complete(player)
    repository.pendingRewards shouldHaveSize 1
    rewardService.deliverPending(player)
    economy.depositCalls shouldBe 1
    rewardService.deliverPending(player)
    economy.depositCalls shouldBe 1
}
```

- [ ] **Step 2: Run RED, implement, run GREEN**

Run RED: `./gradlew test --tests '*LumberGuidanceAdminReward*' --tests '*LocaleParityTest'`

Extract the activity-neutral grant transaction without changing farm payload semantics. Add all RU/EN keys and portable vanilla config defaults.

Run GREEN: `./gradlew test --tests '*LumberGuidanceAdminReward*' --tests '*FarmReward*' --tests '*LocaleParityTest' --tests '*ArcFarmsConfigTest'`

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin src/main/resources src/test/kotlin
git commit -m "feat: finish lumber guidance rewards and admin"
```

### Task 11: Complete lumber scenario and legacy-controller isolation

**Files:**
- Create: `src/test/kotlin/ru/ruscrafting/farms/paper/lumber/LumbermillV2FullFlowMockBukkitIntegrationTest.kt`
- Modify: `src/test/kotlin/ru/ruscrafting/farms/paper/ArcFarmsArchitectureContractTest.kt`
- Modify: `src/main/kotlin/ru/ruscrafting/farms/paper/LumbermillController.kt` only to isolate it behind the version-1 adapter.

**Interfaces:**
- Produces: one end-to-end V2 acceptance fixture and proof that engine-version 2 cannot execute the legacy controller.

- [ ] **Step 1: Write the failing full-flow and architecture assertions**

Run an order through every main phase, force each of the eight incidents in isolated repetitions, restart during a carried bundle and recovery record, and assert final reward/completion once. Assert `LumbermillController` is never constructed for a V2 zone.

- [ ] **Step 2: Run RED and complete missing wiring**

Run: `./gradlew test --tests '*LumbermillV2FullFlow*' --tests '*ArcFarmsArchitectureContractTest'`

Expected: failures identify unconnected capabilities or lifecycle paths, not changed expectations.

- [ ] **Step 3: Run the entire lumber/farm gate**

Run: `./gradlew test --tests '*Lumber*' --tests '*Farm*' --tests '*Worksite*' --tests '*ArcFarmsArchitectureContractTest'`

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin src/test/kotlin
git commit -m "test: cover complete lumbermill v2 lifecycle"
```
