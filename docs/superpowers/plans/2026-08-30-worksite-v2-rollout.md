# Worksite V2 Configuration, QA and Rollout Implementation Plan

> **For Codex:** Execute this plan only after the kernel, lumbermill and mine V2 plans are green. Use the `superpowers:executing-plans` skill, follow every verification gate, and do not call production healthy until active-JAR hashes and runtime smoke both agree.

**Goal:** Ship Worksite V2 as a reversible ArcFarms 0.28.0 release on both `classic` and `classic_survival`, with tracked configuration, automated player-bot acceptance, Spark evidence, exact artifact identity and verified production activation.

**Architecture:** The plugin owns code and default resources; `.deploy-ruscrafting-ops` owns the two live profile configs and language overrides. Lab is the mandatory behavioral gate. Production uses one verified JAR transaction for both Paper targets, one coordinated restart, active hash verification and post-start readback. A failure at any gate stops promotion and preserves the prior JAR/config for rollback.

**Tech Stack:** Kotlin/JVM 25, Gradle, Paper 1.21.11, YAML, Mineflayer QA bot, Spark profiler, `scripts/mc lab`, `scripts/mc jar`, RusCrafting deployment audit.

---

## Global Constraints

- Implementation checkout: `/private/tmp/arcfarms-guidance-perk.DzoKw2/repo` on `codex/worksite-v2`.
- Operations checkout: `/Users/alexey23/RusCrafting/.deploy-ruscrafting-ops`.
- Do not edit or clean unrelated dirty files in either checkout.
- Use `./gradlew test shadowJar` during iteration and `./gradlew clean test shadowJar` for the release candidate. Do not use `check`.
- Release version is `0.28.0`; the release candidate JAR is `build/libs/ArcFarms-0.28.0.jar`.
- Production targets are exactly `classic` and `classic_survival` because both track `plugins/ArcFarms/{config.yml,lang/{ru,en}.yml,modules/redis.yml}`.
- No in-place `/reload` for code. JAR activation requires a Paper restart.
- Never promote an artifact that differs from the lab-tested SHA-256.
- Every mutation command carries an explicit `--goal` and its protocol output is retained.
- Roll back immediately if the plugin is red/disabled, readiness fails, active hash differs, a V2 zone cannot restore state, or a service item/entity survives cleanup.

## Task 1: Add V2 Resource Schema and Compatibility Gate

**Files:**
- Modify: `src/main/resources/config.yml`
- Modify: `src/main/resources/lang/ru.yml`
- Modify: `src/main/resources/lang/en.yml`
- Modify: `src/main/kotlin/ru/ruscrafting/farms/config/ArcFarmsConfig.kt`
- Modify: `src/main/kotlin/ru/ruscrafting/farms/config/ConfigLoader.kt`
- Test: `src/test/kotlin/ru/ruscrafting/farms/config/WorksiteV2ConfigContractTest.kt`
- Test: `src/test/kotlin/ru/ruscrafting/farms/config/ResourceContractTest.kt`

- [ ] **Step 1: Write failing resource and migration tests**

Assert:

```kotlin
test("default config exposes explicit V2 engines") {
    val config = loadPackagedConfig()
    config.int("lumber.engine-version") shouldBe 2
    config.int("mine.engine-version") shouldBe 2
    config.int("worksites.objective-pool.multiplier") shouldBe 2
}

test("legacy zones stay disabled until migrated") {
    val legacy = loadConfig("lumber: { enabled: true }")
    legacy.lumber.engineVersion shouldBe 1
    legacy.lumber.enabled shouldBe false
    legacy.validationErrors shouldContain "lumber.engine-version"
}
```

Also assert all message keys referenced by `WorksiteGuidancePresenter`, eight lumber incidents and eight mine incidents exist in both languages.

- [ ] **Step 2: Run RED**

Run: `./gradlew test --tests '*WorksiteV2ConfigContractTest' --tests '*ResourceContractTest'`

Expected: missing V2 schema and message keys.

- [ ] **Step 3: Implement strict V2 loading**

Add these explicit roots:

```yaml
worksites:
  objective-pool:
    multiplier: 2
  guidance:
    title-stall-seconds: 12
    particle-period-ticks: 10
  cleanup:
    recovery-blocks-per-tick: 256
lumber:
  enabled: false
  engine-version: 2
mine:
  enabled: false
  engine-version: 2
```

Rules:

- absent `engine-version` is legacy V1;
- an enabled V1 worksite fails closed with a localized operator error;
- V2 validates all mandatory regions, stations, routes and incident quotas at load time;
- unknown enum names produce one precise path/value error;
- farm config keeps its current semantics.

- [ ] **Step 4: Run GREEN**

Run: `./gradlew test --tests '*WorksiteV2ConfigContractTest' --tests '*ResourceContractTest'`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources src/main/kotlin/ru/ruscrafting/farms/config src/test/kotlin/ru/ruscrafting/farms/config
git commit -m "feat: add worksite v2 configuration contract"
```

## Task 2: Migrate Tracked Spawn and Survival Profiles

**Files:**
- Modify: `/Users/alexey23/RusCrafting/.deploy-ruscrafting-ops/classic/plugins/ArcFarms/config.yml`
- Modify: `/Users/alexey23/RusCrafting/.deploy-ruscrafting-ops/classic/plugins/ArcFarms/lang/ru.yml`
- Modify: `/Users/alexey23/RusCrafting/.deploy-ruscrafting-ops/classic/plugins/ArcFarms/lang/en.yml`
- Modify: `/Users/alexey23/RusCrafting/.deploy-ruscrafting-ops/classic_survival/plugins/ArcFarms/config.yml`
- Modify: `/Users/alexey23/RusCrafting/.deploy-ruscrafting-ops/classic_survival/plugins/ArcFarms/lang/ru.yml`
- Modify: `/Users/alexey23/RusCrafting/.deploy-ruscrafting-ops/classic_survival/plugins/ArcFarms/lang/en.yml`
- Test: `/Users/alexey23/RusCrafting/.deploy-ruscrafting-ops/batches/arcfarms/validate.py`

- [ ] **Step 1: Snapshot ownership and diff before edits**

Run:

```bash
git -C /Users/alexey23/RusCrafting/.deploy-ruscrafting-ops status --short
git -C /Users/alexey23/RusCrafting/.deploy-ruscrafting-ops diff -- classic/plugins/ArcFarms classic_survival/plugins/ArcFarms
```

Record pre-existing paths and do not stage them unless they are part of this migration.

- [ ] **Step 2: Extend config validation before migration**

Make `batches/arcfarms/validate.py` reject:

- enabled lumber/mine profiles without `engine-version: 2`;
- a missing required station/route/region;
- objective pool multiplier below `2`;
- missing RU/EN guidance or incident keys;
- server profile mismatch for shared GUI/message structure.

Run the validator and capture the expected RED result against the old profiles.

- [ ] **Step 3: Migrate both tracked profiles**

Preserve existing farm points and manually positioned model data. Add V2 lumber/mine configuration only where concrete points and regions exist. If one server lacks complete physical points, leave that activity `enabled: false` instead of inventing coordinates.

Synchronize GUI background, worksite UX keys and message structure between `classic` and `classic_survival`; keep server-specific coordinates, travel behavior and Redis server ID distinct.

- [ ] **Step 4: Validate operator-owned YAML**

Run:

```bash
./scripts/mc validate --changed
python3 batches/arcfarms/validate.py
git diff --check -- classic/plugins/ArcFarms classic_survival/plugins/ArcFarms batches/arcfarms/validate.py
```

Expected: PASS with no unrelated file changes.

- [ ] **Step 5: Commit and push the ops migration before rollout**

Stage only the six ArcFarms profile files and validator. Inspect the staged diff, commit `ArcFarms: configure worksite v2`, push `main`, and prove local `HEAD` equals `origin/main`.

Do not apply profiles to production yet; the JAR lab gate comes first.

## Task 3: Extend Automated Player-Bot Acceptance

**Files:**
- Modify: `/Users/alexey23/RusCrafting/.deploy-ruscrafting-ops/scripts/player-bot/src/cli.cjs`
- Modify: `/Users/alexey23/RusCrafting/.deploy-ruscrafting-ops/scripts/player-bot/src/arcfarms-qa.cjs`
- Modify: `/Users/alexey23/RusCrafting/.deploy-ruscrafting-ops/scripts/player-bot/test/arcfarms-qa.test.cjs`
- Create: `/Users/alexey23/RusCrafting/.deploy-ruscrafting-ops/scripts/player-bot/test/arcfarms-worksite-v2.test.cjs`

- [ ] **Step 1: Write failing bot contract tests**

Add operations `lumber-v2`, `mine-v2`, `worksite-cleanup-v2`, and `worksite-guidance-v2`. Tests assert each operation is bounded, records the zone ID/engine version, and rejects a fixture that lacks a mandatory target or cleanup proof.

- [ ] **Step 2: Run RED**

Run: `npm test -- --test-name-pattern='ArcFarms Worksite V2'`

Expected: unsupported operation failures.

- [ ] **Step 3: Implement deterministic lab scenarios**

`lumber-v2` must:

- reset and start a V2 shift;
- complete felling, skidding, sawing, stacking and dispatch;
- force all eight lumber incidents through `/arcfarms admin event <zone> <event>`;
- verify title/bossbar/particle guidance after deliberate stalls;
- prove a rejected/unreachable target is replaced from the spare pool;
- quit once while carrying a service item and verify recovery.

`mine-v2` mirrors this for prospecting, mining, loading, extraction and all eight mine incidents, including a restart checkpoint with pending block restoration.

`worksite-cleanup-v2` proves no tagged item, display, interaction, mount, mob, leash or temporary block remains after reset/quit/restart.

- [ ] **Step 4: Run bot unit tests**

Run: `npm test`

Expected: PASS.

- [ ] **Step 5: Commit with the ops migration**

If Task 2 has not yet been committed, include these exact player-bot files in the same ops commit. Otherwise create `ArcFarms: add worksite v2 QA flows`. Push and prove `HEAD == origin/main`.

## Task 4: Build the Immutable Release Candidate

**Files:**
- Modify: `build.gradle.kts`
- Verify: `src/main/resources/plugin.yml`
- Verify: `build/libs/ArcFarms-0.28.0.jar`

- [ ] **Step 1: Bump the release version**

Change:

```kotlin
version = "0.28.0"
```

Add/update a version contract test that opens `plugin.yml` from the processed resources and requires `0.28.0`.

- [ ] **Step 2: Run the complete local gate**

Run:

```bash
./gradlew clean test shadowJar
```

Expected: all tests PASS and `build/libs/ArcFarms-0.28.0.jar` exists.

- [ ] **Step 3: Inspect the artifact**

Run:

```bash
unzip -p build/libs/ArcFarms-0.28.0.jar plugin.yml
shasum -a 256 build/libs/ArcFarms-0.28.0.jar
jar tf build/libs/ArcFarms-0.28.0.jar
```

Require:

- descriptor name `ArcFarms`;
- descriptor version `0.28.0`;
- V2 kernel/lumber/mine classes present;
- no duplicate plugin descriptor;
- record the SHA-256 as `RELEASE_SHA` without rebuilding afterward.

- [ ] **Step 4: Commit and push source**

Inspect the entire branch diff from `e91bf4a`, run `git diff --check`, commit the version/artifact contract, rebase on current `origin/main`, rerun `./gradlew clean test shadowJar`, recompute `RELEASE_SHA`, and push `codex/worksite-v2` to `main` only after the exact rebased state is green.

Prove:

```bash
git rev-parse HEAD
git rev-parse origin/main
```

Both hashes must match.

## Task 5: Lab Deploy and Functional Acceptance

**Files:**
- Artifact: `/private/tmp/arcfarms-guidance-perk.DzoKw2/repo/build/libs/ArcFarms-0.28.0.jar`
- Evidence: `/Users/alexey23/RusCrafting/.deploy-ruscrafting-ops/docs/deployments/` audit generated by `scripts/mc`

- [ ] **Step 1: Deploy the exact candidate to lab**

From the ops checkout run:

```bash
./scripts/mc lab deploy --goal "ArcFarms 0.28.0 Worksite V2 acceptance" -- /private/tmp/arcfarms-guidance-perk.DzoKw2/repo/build/libs/ArcFarms-0.28.0.jar
```

Require a successful lab audit and verify the deployed artifact hash equals `RELEASE_SHA`.

- [ ] **Step 2: Run the four bounded QA-bot scenarios**

Use one authenticated lab session and invoke `arcfarms lumber-v2`, `arcfarms mine-v2`, `arcfarms worksite-guidance-v2`, and `arcfarms worksite-cleanup-v2`. Capture the structured JSON result from each operation; no scenario may rely on manual interpretation alone.

- [ ] **Step 3: Run restart recovery probes**

For each worksite, stop at a phase with temporary blocks and a carried service item, restart the lab through:

```bash
./scripts/mc lab restart --goal "ArcFarms Worksite V2 recovery probe"
```

Reconnect the bot and require:

- the journal is replayed;
- original blocks are restored before new work starts;
- service items are absent from all inventories and dropped-item sets;
- the zone either resumes safely or returns to IDLE with an operator-visible reason.

- [ ] **Step 4: Profile the heavy paths**

Run Spark while forcing objective-pool creation, a bounded recovery batch, the largest lumber incident and the largest mine incident. Capture tick duration, ArcFarms hot methods and allocation summary. Reject promotion if a synchronous full-region scan, `World#getEntities`, unbounded block loop, or repeated per-player full target scan appears.

- [ ] **Step 5: Review lab logs and screenshots**

Run:

```bash
./scripts/mc lab logs
./scripts/mc audit --tail 20 lab
```

Require no ArcFarms ERROR/SEVERE, disabled-plugin message, orphan cleanup warning or recovery retry loop. Visually inspect the key stations and service models using real ItemsAdder resources; all ItemDisplay transforms must have grounding evidence from the mandatory grounding workflow.

## Task 6: Production Preflight and Atomic Rollout

**Files:**
- Artifact: `/private/tmp/arcfarms-guidance-perk.DzoKw2/repo/build/libs/ArcFarms-0.28.0.jar`
- Targets: `classic`, `classic_survival`

- [ ] **Step 1: Verify quiet deployment window and current state**

Use read-only status/player count checks. Record current active ArcFarms descriptor version and SHA-256 on both targets. If players are active in a relevant activity, defer restart; do not terminate a running event.

- [ ] **Step 2: Dry-run the exact transaction**

Run:

```bash
./scripts/mc jar --dry-run --restart --wait classic classic_survival -- /private/tmp/arcfarms-guidance-perk.DzoKw2/repo/build/libs/ArcFarms-0.28.0.jar
```

Require that both destinations resolve to the existing ArcFarms plugin and no unrelated JAR is selected.

- [ ] **Step 3: Apply tracked profile changes**

Deploy the already-pushed ArcFarms profile commit through the supported ops transaction for `classic` and `classic_survival`. Preserve the emitted deployment manifest. Validate both live config hashes before the JAR restart.

- [ ] **Step 4: Replace and activate one immutable JAR on both targets**

Run:

```bash
./scripts/mc jar --restart --wait --goal "Activate ArcFarms 0.28.0 Worksite V2" classic classic_survival -- /private/tmp/arcfarms-guidance-perk.DzoKw2/repo/build/libs/ArcFarms-0.28.0.jar
```

Require successful `JAR_EVENT`, `JAR_RESULT`, and `JAR_SUMMARY` records for both targets.

- [ ] **Step 5: Verify production readback**

For both `classic` and `classic_survival`, prove:

- Paper readiness completed with a new PID/start time;
- ArcFarms is enabled and descriptor version is `0.28.0`;
- active plugin JAR SHA-256 equals `RELEASE_SHA`;
- `/arcfarms status` answers without exception;
- enabled farm/lumber/mine zones report the expected engine version;
- startup logs contain no config migration, recovery or listener-registration failure.

Run one non-destructive status/admin diagnostic on each target. Do not force production incidents as part of post-deploy smoke.

- [ ] **Step 6: Roll back on any failed invariant**

If a target fails activation or readback, use the transaction's recorded backup/rollback ID, restart both targets back to the prior coherent version, verify prior active hashes, and leave Worksite V2 disabled in tracked config until the failure is diagnosed in lab.

## Task 7: Final Evidence and Cleanup

**Files:**
- Verify: ArcFarms git repository
- Verify: operations git repository
- Verify: generated lab/production audit records

- [ ] **Step 1: Prove repository synchronization**

Record exact ArcFarms and ops `HEAD`, `origin/main`, branch status and remaining untracked/modified files. Source and operations commits must both be pushed; unrelated user files may remain but must be named explicitly.

- [ ] **Step 2: Prove artifact lineage**

Present one chain:

```text
ArcFarms source commit -> ArcFarms-0.28.0.jar RELEASE_SHA -> lab active SHA -> classic active SHA -> classic_survival active SHA
```

All four artifact hashes must match.

- [ ] **Step 3: Summarize acceptance evidence**

Report:

- full Gradle result;
- player-bot unit result;
- four lab scenario results;
- restart recovery result;
- Spark result and profiling URL/file;
- production readiness/status result;
- exact rollback manifest ID;
- all remaining uncommitted files in both repositories.

- [ ] **Step 4: Mark delivery complete only after every gate is satisfied**

If any gate is missing, report the release as partial and continue working; do not describe staged files or a pushed commit as a completed production rollout.
