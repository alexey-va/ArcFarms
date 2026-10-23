# Native managed-ore breaking — 2026-10-23

The old controller cancelled `BlockBreakEvent`, waited for durable storage, then removed the ore in a separate callback. The damage handler also scheduled an intact-block resync. Both paths caused the reported rebound/late disappearance.

Ordinary V2 ore now prepares its exact existing V1 recovery intent before breaking, from left-click/damage and bounded nearby loaded-index preparation. LOWEST admits only a ready exact intent, HIGHEST never clears foreign cancellation, and MONITOR accounts once. Native Paper removal supplies fracture, sound and tool wear; the successful extraction adds a quiet pickup accent. Cold/pending/failed storage cancels the attempt permanently until a later player break; its callback never removes terrain.

Preparation is capped at256 including actual unfinished writes across TTL, phase changes and reload. Each existing visual tick reads/prepares at most8 candidates in one batch and prunes at most16 entries. A synchronous admission reservation retains durable recovery through event dispatch. Healthy regrowth starts at actual acceptance; the V1 prearm deadline remains crash fallback. Still-visible prearmed ore is excluded from future-supply counting.

Optional AuraSkills2.3.12 listeners suppress natural configured ore Mining XP and MINING_LUCK loot in enabled V2 mine worlds. Missing AuraSkills remains optional; an installed unsupported provider fails normal startup. Other skills/worlds, source-null XP and combat loot retain their owners. Cancellation occurs at LOWEST: AuraSkills has NORMAL XP-conversion and Mending listeners, so a late XP cancellation would leave those side effects behind. A NORMAL observer regression verifies early suppression. API classes are compile-only and absent from the shipped JAR. Spawn EcoJobs and AdvancedEnchantments world guards are paired configuration changes; see the balance assessment. Only old_shafts is enabled on the verified Spawn profile. Enabling another managed world requires matching reward/enchantment configuration review.

## Verified source/build evidence

- Seven-class mining run:45 cases,43 passed,2 existing MockBukkit skips. The final native/recovery rerun after the healthy-reload correction:28 cases,27 passed,1 existing unsupported async-chunk skip.
- Real listener/service/router dispatch coverage includes HIGH/HIGHEST cancellation, expiry and forced recovery between handlers, zero drops/XP and duplicate MONITOR delivery. Delayed-journal coverage retains256 submissions across repeated expiry and reload.
- AuraSkills guard:8/8 passed, including a classloader without the optional API, unsupported provider, typed event dispatch, world changes and cleanup.
- shadowJar, display geometry validation and production-JAR verification passed. Convergence and floor-preview startup changes passed independent rendered preview/playback checks.
- Previous .50 full CI had two failures also present in .49 (HellGreenhouseContractTest and ArcFarmsCompletionTest) plus a new nullable crystal-guidance fallback failure. The latter is fixed and its focused guidance check passes. This does not declare the full suite green.

MockBukkit does not execute Paper ServerPlayerGameMode native post-event removal; fixtures model that boundary explicitly. No native-client particles, sound or mining feel were observed. Cold teleport or unusually slow storage can require a retry, with an intact block and a short preparing hint.

Detailed evidence is retained under task-artifacts/mine-farms-20261023/break-feel and independent-visual-qa; economy calculations are in mine-events-balance-20261023.json. Live delivery and runtime activation are recorded separately in the ops mine-event receipt.
