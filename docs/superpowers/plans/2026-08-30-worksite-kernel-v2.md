# Worksite Kernel V2 Implementation Plan

> **For Codex:** Use `superpowers:executing-plans` to implement this plan task-by-task in the current worktree. Do not delegate this execution; steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the activity-neutral lifecycle, typed events, objective pools, guidance, service-item safety and event routing used by farm, lumbermill and mine.

**Architecture:** Pure Kotlin domain types own objective state and transitions. Focused Paper owners render guidance and enforce inventory/player lifecycle boundaries. `WorksiteModuleRegistry` contains all modules and routes only typed capabilities; the existing farm keeps its behavior while adopting the common contracts.

**Tech Stack:** Kotlin 2.3.0, Java 25, Paper/Purpur 1.21.11, MockBukkit, Kotest, MockK, arc-core `Tasks.scheduler`.

**Spec:** `docs/superpowers/specs/2026-08-30-worksite-v2-design.md`

## Global Constraints

- Domain state and engines do not import Bukkit.
- Paper scheduling uses `RuntimeTaskSupervisor`/`Tasks.scheduler`, never Bukkit scheduling directly.
- Hot ticks use bounded indexes and loaded chunks; `World#getEntities` is lifecycle-only.
- Farm behavior, menu actions, travel destinations, permissions and persistence remain compatible.
- Player text is mirrored in `lang/ru.yml` and `lang/en.yml`.
- Use `./gradlew test shadowJar`; never run `check` on the workstation.

---

### Task 1: Module-local engine events

**Files:**
- Modify: `src/main/kotlin/ru/ruscrafting/farms/domain/ActivityDomain.kt`
- Modify: `src/main/kotlin/ru/ruscrafting/farms/domain/FarmShift.kt`
- Modify: `src/main/kotlin/ru/ruscrafting/farms/domain/FarmSpecialIncident.kt`
- Modify: `src/main/kotlin/ru/ruscrafting/farms/domain/LumberShift.kt`
- Modify: `src/main/kotlin/ru/ruscrafting/farms/domain/MineShift.kt`
- Modify: every production/test file returned by `rg -l 'ShiftEvent|EngineResult<' src/main/kotlin src/test/kotlin`
- Test: `src/test/kotlin/ru/ruscrafting/farms/domain/TypedEngineEventTest.kt`
- Test: `src/test/kotlin/ru/ruscrafting/farms/paper/ArcFarmsArchitectureContractTest.kt`

**Interfaces:**
- Produces: `EngineResult<S, E>`, `FarmShiftEvent`, `LumberShiftEvent`, `MineShiftEvent`.
- Consumers: all domain engines, transition coordinators and module event applicators.

- [ ] **Step 1: Write the failing type and architecture tests**

```kotlin
test("each engine exposes only its own event type") {
    val farm: EngineResult<FarmShiftState, FarmShiftEvent> =
        FarmShiftEngine.start(FarmShiftState(), farmOrder, patch, "WHEAT", rules, 1L)
    val lumber: EngineResult<LumberShiftState, LumberShiftEvent> =
        LumberShiftEngine.start(LumberShiftState(), "OAK", lumberRules, 1L)
    val mine: EngineResult<MineShiftState, MineShiftEvent> =
        MineShiftEngine.start(MineShiftState(), mineRules, 1L)
    farm.events.all { it is FarmShiftEvent } shouldBe true
    lumber.events.all { it is LumberShiftEvent } shouldBe true
    mine.events.all { it is MineShiftEvent } shouldBe true
}
```

Add an architecture assertion that `ActivityDomain.kt` no longer declares `enum class ShiftEvent`.

- [ ] **Step 2: Run the focused tests and verify RED**

Run: `./gradlew test --tests '*TypedEngineEventTest' --tests '*ArcFarmsArchitectureContractTest'`

Expected: compilation fails because `EngineResult` has one type parameter and module event types do not exist.

- [ ] **Step 3: Introduce typed results and migrate all callers**

```kotlin
data class EngineResult<S, E>(
    val state: S,
    val accepted: Boolean,
    val contribution: Int = 0,
    val events: List<E> = emptyList(),
    val contributionCredits: Map<UUID, Int> = emptyMap(),
)

sealed interface FarmShiftEvent
sealed interface LumberShiftEvent
sealed interface MineShiftEvent
```

Use module-local enums or data objects implementing the relevant sealed interface. Keep existing event names where their meaning is unchanged, but do not share instances across modules.

- [ ] **Step 4: Run the focused and domain suites**

Run: `./gradlew test --tests 'ru.ruscrafting.farms.domain.*' --tests '*ArcFarmsArchitectureContractTest'`

Expected: PASS and no source occurrence of the old global type.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin src/test/kotlin
git commit -m "refactor: type worksite engine events"
```

### Task 2: Pure objective target pool

**Files:**
- Create: `src/main/kotlin/ru/ruscrafting/farms/domain/worksite/WorksiteObjective.kt`
- Create: `src/main/kotlin/ru/ruscrafting/farms/domain/worksite/ObjectiveTargetPool.kt`
- Test: `src/test/kotlin/ru/ruscrafting/farms/domain/worksite/ObjectiveTargetPoolTest.kt`

**Interfaces:**
- Produces: `WorksiteObjectiveKey`, `WorksitePosition`, `ObjectiveTargetRole`, `ObjectiveTargetStatus`, `ObjectiveTargetState`, `WorksiteObjectiveState`, `ObjectiveTargetPool.plan`, `.lease`, `.release`, `.complete`, `.invalidate`.
- Consumers: lumber and mine objective planners, service-item leases, guidance views.

- [ ] **Step 1: Write failing pool tests**

```kotlin
test("generated objectives place twice the quota and cap completion") {
    val planned = ObjectiveTargetPool.plan(key, required = 3, candidates = sixCandidates)
    planned.targets shouldHaveSize 6
    val completed = planned.targets.take(4).fold(planned) { state, target ->
        ObjectiveTargetPool.complete(state, target.id, playerId).state
    }
    completed.completed shouldBe 3
}

test("release and invalidation return or replace targets without losing progress") {
    val leased = ObjectiveTargetPool.lease(state, "target-1", playerId, now = 1_000L).state
    ObjectiveTargetPool.release(leased, playerId).state.target("target-1").status shouldBe AVAILABLE
    val replaced = ObjectiveTargetPool.invalidate(state, "target-2", reserveCandidate).state
    replaced.completed shouldBe state.completed
    replaced.targets.any { it.position == reserveCandidate.position } shouldBe true
}
```

- [ ] **Step 2: Run and verify RED**

Run: `./gradlew test --tests '*ObjectiveTargetPoolTest'`

Expected: compilation fails because the objective package is absent.

- [ ] **Step 3: Implement immutable target transitions**

`plan` selects deterministic candidates by score/id, requests `required * 2`, rejects fewer than `required`, and returns unused candidates as a bounded reserve. `complete` changes only `AVAILABLE`/matching `LEASED` targets and caps contributions at `required`. `release` changes the player's `LEASED` targets back to `AVAILABLE`. `invalidate` keeps progress and consumes one reserve candidate.

- [ ] **Step 4: Run objective and serialization tests**

Run: `./gradlew test --tests '*ObjectiveTargetPoolTest' --tests '*Persistence*Test'`

Expected: PASS with deterministic equality after JSON round trips.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/ru/ruscrafting/farms/domain/worksite src/test/kotlin/ru/ruscrafting/farms/domain/worksite
git commit -m "feat: add resilient worksite objective pools"
```

### Task 3: Complete lifecycle and narrow runtime ports

**Files:**
- Modify: `src/main/kotlin/ru/ruscrafting/farms/paper/WorksiteModule.kt`
- Create: `src/main/kotlin/ru/ruscrafting/farms/paper/worksite/WorksitePorts.kt`
- Modify: `src/main/kotlin/ru/ruscrafting/farms/paper/PaperWorksiteRuntimePort.kt`
- Modify: `src/main/kotlin/ru/ruscrafting/farms/paper/farm/FarmModule.kt`
- Test: `src/test/kotlin/ru/ruscrafting/farms/paper/WorksiteModuleRegistryTest.kt`
- Test: `src/test/kotlin/ru/ruscrafting/farms/paper/WorksiteLifecycleContractTest.kt`

**Interfaces:**
- Produces: `RuntimeComponent`, `WorksiteAudiencePort`, `WorksiteAccessPort`, `WorksiteStatePort`, `WorksiteTaskPort`, `WorksiteStatsPort`, `WorksiteNetworkPort` and compatibility composite `WorksiteRuntimePort`.
- Consumers: all modules; V2 feature owners depend on the smallest relevant port.

- [ ] **Step 1: Write failing lifecycle tests**

```kotlin
test("registry activates reconciles and cleans every module exactly once") {
    val farm = RecordingWorksiteModule(ActivityKind.FARM)
    val lumber = RecordingWorksiteModule(ActivityKind.LUMBER)
    val mine = RecordingWorksiteModule(ActivityKind.MINE)
    val registry = WorksiteModuleRegistry(listOf(farm, lumber, mine))
    registry.activateLoadedState()
    registry.reconcileChunk(chunk)
    registry.beforeReload("reload")
    registry.cleanup("shutdown")
    listOf(farm, lumber, mine).forEach { it.calls shouldBe expectedCalls }
}
```

- [ ] **Step 2: Run and verify RED**

Run: `./gradlew test --tests '*WorksiteModuleRegistryTest' --tests '*WorksiteLifecycleContractTest'`

Expected: lifecycle methods are unresolved on the current module and registry contracts.

- [ ] **Step 3: Add lifecycle and split ports without changing farm behavior**

Make `WorksiteRuntimePort` extend all narrow ports so existing farm constructors compile. Implement registry fan-out in stable `ActivityKind` order. Move `FarmModule.activateLoadedState`, chunk reconciliation and cleanup behind `RuntimeComponent` overrides.

- [ ] **Step 4: Run lifecycle, farm architecture and farm scenario tests**

Run: `./gradlew test --tests '*Worksite*Test' --tests '*ArcFarmsArchitectureContractTest' --tests '*FarmIncidentLifecycle*'`

Expected: PASS; farm callbacks are invoked once through the registry.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/ru/ruscrafting/farms/paper src/test/kotlin/ru/ruscrafting/farms/paper
git commit -m "refactor: complete worksite lifecycle contract"
```

### Task 4: Shared guidance presenter

**Files:**
- Create: `src/main/kotlin/ru/ruscrafting/farms/paper/worksite/WorksiteGuidance.kt`
- Create: `src/main/kotlin/ru/ruscrafting/farms/paper/worksite/WorksiteGuidancePresenter.kt`
- Test: `src/test/kotlin/ru/ruscrafting/farms/paper/worksite/WorksiteGuidancePresenterMockBukkitTest.kt`

**Interfaces:**
- Produces: `WorksiteGuidanceView`, `WorksiteGuidanceTarget`, `WorksiteGuidanceSource`, `WorksiteGuidancePresenter.updateHud`, `.emitParticles`, `.recordProgress`, `.releasePlayer`.
- Consumes: narrow audience/task/access ports and module-local localized views.

- [ ] **Step 1: Write failing guidance tests**

```kotlin
test("a stalled participant receives a title and only personal nearest targets") {
    source.view(player.uniqueId) returns view(progressVersion = 7, targets = roleTargets)
    presenter.updateHud(now = 1_000L)
    presenter.updateHud(now = 13_001L)
    verify(exactly = 1) { audience.showScreenTitle(player, view.title, view.subtitle) }
    presenter.emitParticles()
    verify { audience.spawnGuidanceDust(player, nearestRoleA.position, nearestRoleA.color, any()) }
    verify(exactly = 0) { audience.spawnGuidanceDust(otherPlayer, any(), any(), any()) }
}
```

- [ ] **Step 2: Run and verify RED**

Run: `./gradlew test --tests '*WorksiteGuidancePresenterMockBukkitTest'`

Expected: guidance types do not exist.

- [ ] **Step 3: Implement bounded per-player sessions**

Store only player UUID, runtime key, observed progress version and last reminder time. Render boss bars every HUD cadence, titles only after 12 seconds without progress, and one nearest loaded target per required role. Remove sessions on player release and objective/version change.

- [ ] **Step 4: Run focused tests**

Run: `./gradlew test --tests '*WorksiteGuidancePresenterMockBukkitTest' --tests '*FarmHarvestGuidance*'`

Expected: PASS and existing farm particle behavior remains unchanged.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/ru/ruscrafting/farms/paper/worksite src/test/kotlin/ru/ruscrafting/farms/paper/worksite
git commit -m "feat: share worksite guidance and reminders"
```

### Task 5: Service-item and participant safety kernel

**Files:**
- Create: `src/main/kotlin/ru/ruscrafting/farms/paper/worksite/WorksiteServiceItemController.kt`
- Create: `src/main/kotlin/ru/ruscrafting/farms/paper/worksite/WorksiteParticipantSafety.kt`
- Create: `src/main/kotlin/ru/ruscrafting/farms/paper/worksite/WorksitePlayerReleaseReason.kt`
- Test: `src/test/kotlin/ru/ruscrafting/farms/paper/worksite/WorksiteServiceItemControllerMockBukkitTest.kt`
- Test: `src/test/kotlin/ru/ruscrafting/farms/paper/worksite/WorksiteParticipantSafetyTest.kt`

**Interfaces:**
- Produces: `ServiceItemIdentity`, `WorksiteServiceItemOwner`, `WorksiteServiceItemController.issue`, `.consume`, `.guardInventory`, `.cleanupPlayer`; `WorksiteParticipantSafety.release`.
- Consumers: lumber/mine carry objectives and the top-level event router.

- [ ] **Step 1: Write failing inventory/lifecycle tests**

```kotlin
test("service items cannot leave personal storage and release their lease on exit") {
    val item = controller.issue(player, identity, Material.IRON_NUGGET, name)
    controller.guardInventory(externalContainerClick(item)).isCancelled shouldBe true
    controller.guardDrop(player, item).isCancelled shouldBe true
    safety.release(player, ZONE_EXIT)
    player.inventory.contains(item) shouldBe false
    owner.released shouldContain identity
}

test("stale sequence items are removed on join without progress") {
    player.inventory.addItem(codec.item(identity.copy(sequence = 3)))
    owner.activeSequence returns 4
    controller.cleanupPlayer(player, JOIN)
    player.inventory.any(codec::isServiceItem) shouldBe false
    owner.progress shouldBe 0
}
```

- [ ] **Step 2: Run and verify RED**

Run: `./gradlew test --tests '*WorksiteServiceItemControllerMockBukkitTest' --tests '*WorksiteParticipantSafetyTest'`

Expected: service-item/safety classes are missing.

- [ ] **Step 3: Implement strict PDC identity and cleanup**

Use one `NamespacedKey` per bounded field, reject malformed identifiers, permit moves only between the player's normal storage slots, and notify the owning module on every removal reason. Release is idempotent by `(player, objective, itemId)`.

- [ ] **Step 4: Run focused safety and existing farm supply tests**

Run: `./gradlew test --tests '*WorksiteServiceItem*' --tests '*WorksiteParticipantSafety*' --tests '*FarmSupply*' --tests '*ScarecrowDelivery*'`

Expected: PASS; the common guard does not weaken farm-specific restrictions.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/ru/ruscrafting/farms/paper/worksite src/test/kotlin/ru/ruscrafting/farms/paper/worksite
git commit -m "feat: protect worksite service items and leases"
```

### Task 6: Top-level capability router and farm integration

**Files:**
- Create: `src/main/kotlin/ru/ruscrafting/farms/paper/worksite/WorksiteEventRouter.kt`
- Modify: `src/main/kotlin/ru/ruscrafting/farms/paper/farm/FarmEventRouter.kt`
- Modify: `src/main/kotlin/ru/ruscrafting/farms/paper/ArcFarmsService.kt`
- Modify: `src/main/kotlin/ru/ruscrafting/farms/paper/ArcFarmsListener.kt`
- Modify: `src/main/kotlin/ru/ruscrafting/farms/paper/farm/FarmComponentGraph.kt`
- Test: `src/test/kotlin/ru/ruscrafting/farms/paper/worksite/WorksiteEventRouterMockBukkitTest.kt`
- Test: `src/test/kotlin/ru/ruscrafting/farms/paper/ArcFarmsArchitectureContractTest.kt`

**Interfaces:**
- Consumes: registry lifecycle, event capabilities, service item guard and participant safety.
- Produces: one application-owned `WorksiteEventRouter`; `FarmEventRouter` handles farm only.

- [ ] **Step 1: Write failing routing tests**

```kotlin
test("block breaks route once without farm knowing mine or lumber") {
    router.onBreakHigh(event)
    farm.breakCalls shouldBe 0
    lumber.breakCalls shouldBe 0
    mine.breakCalls shouldBe 1
}

test("quit teleport portal and death each release service items and module leases") {
    listOf(QUIT, TELEPORT_OUT, PORTAL_OUT, DEATH).forEach { reason ->
        router.release(player, reason)
        safety.releaseCount(reason) shouldBe 1
    }
}
```

Add architecture assertions forbidding `ActivityKind.MINE`, `ActivityKind.LUMBER` and `WorksiteModuleRegistry` references from `FarmEventRouter.kt`.

- [ ] **Step 2: Run and verify RED**

Run: `./gradlew test --tests '*WorksiteEventRouterMockBukkitTest' --tests '*ArcFarmsArchitectureContractTest'`

Expected: router is absent and farm still dispatches auxiliary modules.

- [ ] **Step 3: Move routing and lifecycle composition**

Construct farm, lumber and mine modules before a registry containing all three. Route listener entry points through the new owner. `ArcFarmsService` delegates lifecycle, HUD and particles through registry capabilities and remains under 600 lines.

- [ ] **Step 4: Run the full kernel/farm regression gate**

Run: `./gradlew test --tests '*Worksite*' --tests '*ArcFarmsArchitectureContractTest' --tests '*Farm*MockBukkit*' --tests '*Farm*IntegrationTest'`

Expected: PASS; current farm full flows and menu/travel tests are unchanged.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin src/test/kotlin
git commit -m "refactor: route all worksites through typed capabilities"
```

### Task 7: Kernel verification checkpoint

**Files:**
- Modify only files required by failures attributable to Tasks 1-6.
- Test: all project tests.

**Interfaces:**
- Produces: a green kernel baseline consumed by the lumber and mine plans.

- [ ] **Step 1: Run static invariants**

Run: `git diff --check && ! rg 'enum class ShiftEvent|Bukkit.getScheduler|server.scheduler' src/main/kotlin`

Expected: exit 0.

- [ ] **Step 2: Run the complete workstation gate**

Run: `./gradlew clean test shadowJar`

Expected: `BUILD SUCCESSFUL` with a versioned shadow JAR.

- [ ] **Step 3: Record the artifact evidence**

Run: `shasum -a 256 build/libs/ArcFarms-*.jar && git status --short`

Expected: hashes are printed and the worktree has no uncommitted production/test changes.
