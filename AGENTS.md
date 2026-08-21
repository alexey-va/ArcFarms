# AGENTS.md — ArcFarms

Standalone Kotlin/Paper plugin for the three shared RusCrafting worksite
activities: farm, lumbermill, and mine.

- Target Purpur/Paper 1.21.11, WorldGuard 7.0.16, Java 25, and Kotlin 2.3.0.
- WorldGuard is a soft dependency: require it only when a configured zone uses
  a named region. Relay-only and explicit-cuboid nodes must load without it.
- Use `arc-core`, `arc-core-paper`, and `arc-core-redis` through the sibling
  composite build. ArcFarms owns its Redis profile and protocol; do not import
  ARC configuration or add ARC API/path compatibility.
- Keep shift state machines and persistence DTOs independent of Bukkit.
- Use `Tasks.scheduler`; never schedule gameplay directly through Bukkit.
- Farm, lumbermill, and mine must have different player verbs and phase flows.
- Do not add money, item rewards, seasons, tract restoration, or world projects.
  Farm crops accepted by an order are consumed by that order and never drop;
  lumber and mine resources keep their existing material outcomes.
- Mine block replacement is journaled before mutation and must converge after
  restart without duplicate drops or permanent temporary blocks.
- All player text belongs in `lang/ru.yml` and `lang/en.yml`; keys stay equal
  and dynamic player/config values use non-parsing Adventure placeholders.
- Keep the three runtime locale copies synchronized through the `arcfarms`
  translation profile. Chat may use the locale prefix; titles, action bars,
  boss bars, entity names, and inventory titles must not.
- Runtime state belongs under `plugins/ArcFarms/data/` and is never tracked or
  deployed as configuration.
- Network workday seals are persistent and deadline-free. Redis loss may
  temporarily degrade relays, but must never disable or reset local activities.
- Cross-server travel is configured as an exact server, world, and location.
  Redis owns the short-lived handoff ticket; Paper uses the BungeeCord plugin
  messaging channel only for the backend switch.
- Player-facing network announcements are disabled by default.
- Build and test with `../arc-core/gradlew -p . clean check shadowJar`.
