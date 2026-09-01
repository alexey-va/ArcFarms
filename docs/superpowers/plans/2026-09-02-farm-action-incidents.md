# ArcFarms action incidents implementation plan

> **For Codex:** Execute this plan inline with `superpowers:executing-plans`; preserve unrelated work and keep source, resource-pack publication, JAR activation and live smoke as separate gates.

**Goal:** Add boar shield knockback, a smooth four-seat rival raid with outdoor patrolling husks, upgraded RPL-20/M79 weapons, and channel water that persists for the whole incident.

**Architecture:** Extract the raid lifecycle from `FarmActionIncidentController` into one cohesive `FarmRivalRaidController`. Keep Paper-only motion, navigation and ray/projectile behavior behind existing or one-purpose ports, and put deterministic math/selection in domain code. Treat ItemsAdder definitions and production config as separately versioned deployment inputs.

**Tech Stack:** Kotlin 2.3, Java 25, Paper/Purpur 1.21.11, MockBukkit 4.116.3, Kotest/JUnit, ItemsAdder 4.

---

### Task 1: Characterize and extract rival-raid ownership

**Files:**
- Create: `src/main/kotlin/ru/ruscrafting/farms/paper/farm/incident/FarmRivalRaidController.kt`
- Modify: `src/main/kotlin/ru/ruscrafting/farms/paper/FarmActionIncidentController.kt`
- Modify: farm composition/event routing files that construct and call the action controller
- Test: existing rival-raid MockBukkit and architecture tests

**Steps:**
1. Run the focused existing raid/action tests and record the baseline.
2. Move raid state, entity UUID collections, item handling, tick/reconcile and cleanup together into `FarmRivalRaidController`; do not leave mirrored collections in the old owner.
3. Route interact, damage, projectile-hit, player cleanup and zone cleanup to the new controller.
4. Re-run the focused tests and the architecture contract.

### Task 2: Add boar shield knockback

**Files:**
- Modify: boar domain policy/calculation files
- Modify: `src/main/kotlin/ru/ruscrafting/farms/paper/FarmActionIncidentController.kt`
- Modify: `src/main/kotlin/ru/ruscrafting/farms/config/ArcFarmsConfig.kt`
- Modify: `src/main/resources/config.yml`
- Test: boar policy and MockBukkit integration tests

**Steps:**
1. Add a failing test for a normalized horizontal vector, configured magnitude, vertical lift and zero-distance fallback.
2. Add `shield-knockback-horizontal: 0.9` and `shield-knockback-vertical: 0.32` with bounded parsing.
3. Apply the computed velocity only after `FarmBoarShieldPolicy.canDeflect` succeeds.
4. Prove an unblocked player receives no new velocity and a blocking player does.

### Task 3: Smooth flight and four passenger seats

**Files:**
- Modify: raid controller and `FarmRaidFlight` domain code
- Modify: `ArcFarmsService`/farm scheduling so raid motion ticks every tick without accelerating unrelated ambient work
- Modify: config model and `config.yml`
- Test: flight domain, session seating and MockBukkit lifecycle tests

**Steps:**
1. Add failing tests for capped velocity steering, orbit altitude/radius and four distinct seats with rejection of a fifth rider.
2. Schedule the raid motion owner every tick; keep the existing five-tick ambient cadence for unrelated systems.
3. Replace five-tick Ghast teleports with velocity steering toward a continuous orbit target; update seat positions every tick.
4. Add defaults: height `20`, radius `28`, period `48`, speed `0.24`, smoothing `0.22`, max riders `4`.
5. Verify cleanup removes every seat and passenger mapping.

### Task 4: Restrict husks to outdoor beds and patrol the field

**Files:**
- Create/modify: a pure rival-field candidate selector in `domain`
- Modify: raid controller and `FarmSurfacePolicy` use
- Modify: config model and `config.yml`
- Test: candidate rejection, bounded selection and navigation tests

**Steps:**
1. Add failing tests for roofed, indoor, non-farmland, obstructed and unloaded candidates.
2. Build a bounded candidate pool around rival point using loaded columns and `MOTION_BLOCKING`; accept only crop-bed surface positions with headroom and open sky.
3. Abort planning with one bounded diagnostic when fewer valid points than `worker-count` exist.
4. Persist the chosen positions for the session and assign a new in-pool waypoint every `40` ticks through `FarmMobNavigation` at speed `1.1`.
5. Prove patrol never teleports or chooses a point outside the accepted field pool.

### Task 5: Upgrade the machine gun and add the grenade launcher

**Files:**
- Modify: raid controller/service item routing and cleanup
- Modify: config model and `config.yml`
- Modify: `ArcFarmsLocale.kt`, `lang/ru.yml`, `lang/en.yml`
- Modify: `visual-preview.yml` and fragments if required
- Test: item identity, cooldown, projectile routing, safe AoE and cleanup tests

**Steps:**
1. Add failing tests for two distinct service-item ids, two-tick gun cooldown, grenade cooldown, projectile ownership and worker-only AoE.
2. Configure the gun as `PAPER`/CMD `2100006` and cooldown `2`.
3. Add the M79 as `PAPER`/CMD `2100009`, issue both weapons, and keep Adventure display names explicitly non-italic.
4. Launch a marked projectile at speed `1.2`; on impact apply `8` damage within radius `5` only to the active session worker set, then render non-destructive effects and retire the projectile.
5. Expire surviving projectiles after `60` ticks and remove them on every cleanup/reload/shutdown path.
6. Run locale key parity and full visual-dump validation.

### Task 6: Guarantee channel water ownership until event completion

**Files:**
- Modify: `FarmSpecialIncidentController.kt`, `FarmFieldController.kt` and recovery ownership as the trace requires
- Test: full lifecycle MockBukkit integration test combining special incident updates and field maintenance

**Steps:**
1. Reproduce the overwrite by advancing the complete event/field tick path, including scheduled flow and maintenance.
2. Add explicit active-channel ownership to the field maintenance/recovery guard.
3. Prove water survives repeated active-event ticks, then restores only after the event completes.
4. Add restart/reconcile coverage so active channels do not restore early and completed channels do not persist forever.

### Task 7: Update ItemsAdder and production configuration

**Files:**
- Modify: `ruscrafting-ops/configs/classic/plugins/ItemsAdder/contents/gold_guns/configs/items.yml`
- Modify: mirrored `classic_survival` ItemsAdder definition
- Modify: tracked ArcFarms production config and locale profile outputs

**Steps:**
1. Reconfirm existing model/texture paths and prove CMD `2100009` is collision-free.
2. Pin M79 to CMD `2100009` in both canonical/mirrored definitions and verify byte-equivalent intended entries.
3. Merge only the new ArcFarms config keys/defaults into production config; preserve runtime coordinates and unrelated tuning.
4. Run the `arcfarms` translation profile validation and apply only generated locale targets.
5. Validate the complete ItemsAdder archive, run `iazip`, publish the pack and read back the live custom IDs.

### Task 8: Verify, publish and activate

**Files:**
- Modify: `build.gradle.kts` version and any release metadata required by the repository

**Steps:**
1. Run focused tests after each slice, then `./gradlew test shadowJar` with isolated temporary Gradle/Kotlin homes.
2. Inspect the full ArcFarms visual report; block on any structural or overflow failure.
3. Review exact diffs and repository status; commit owned paths and ordinary-push ArcFarms and mcserver `main`.
4. Deploy the exact newly built ArcFarms shadow JAR to `classic` with an explicit goal and restart that backend.
5. Verify readiness, plugin version, active config values, no new ArcFarms ERROR stack traces, ItemsAdder pack readiness and a bounded event-level smoke.
6. Report source push, pack publication, JAR activation and player-visible smoke as separate outcomes.

