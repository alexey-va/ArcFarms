# AGENTS.md — ArcFarms

Standalone Kotlin/Paper plugin for the three shared RusCrafting worksite
activities: farm, lumbermill, and mine.

- Target Purpur/Paper 1.21.11, WorldGuard 7.0.16, Java 25, and Kotlin 2.3.0.
- Use `arc-core`, `arc-core-paper`, and `arc-core-redis` through the sibling
  composite build. ArcFarms owns its Redis profile and protocol; do not import
  ARC configuration or add ARC API/path compatibility.
- Keep shift state machines and persistence DTOs independent of Bukkit.
- Use `Tasks.scheduler`; never schedule gameplay directly through Bukkit.
- Farm, lumbermill, and mine must have different player verbs and phase flows.
- Do not add money, item rewards, seasons, tract restoration, or world projects.
  Vanilla harvested resources and non-economic recognition are the only
  outcomes in the first release.
- Mine block replacement is journaled before mutation and must converge after
  restart without duplicate drops or permanent temporary blocks.
- All player text belongs in `lang/ru.yml` and `lang/en.yml`; keys stay equal
  and dynamic player/config values use non-parsing Adventure placeholders.
- Runtime state belongs under `plugins/ArcFarms/data/` and is never tracked or
  deployed as configuration.
- Network workday seals are persistent and deadline-free. Redis loss may
  temporarily degrade relays, but must never disable or reset local activities.
- Build and test with `../arc-core/gradlew -p . clean check shadowJar`.
