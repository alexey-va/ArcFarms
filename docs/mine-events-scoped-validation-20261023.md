# Scoped mine locale validation — 2026-10-23

The 41-key scope is the 34 keys in `/private/tmp/mine-floor-activities-20261023/locale-flat.json`, the six `mine.crystal.*` progress messages, and `mine.guidance.crystal_resonance`.

**Result: PASS for the scoped paths.** YAML parsed in the source RU/EN catalogs and all three ops mirrors (`classic`, `parkour`, `classic_survival`). All 41 keys exist in each file. Source values match all three RU/EN mirrors; the RU source translation matches the classic RU catalog for all 41 keys. RU/EN placeholder sets and normalized MiniMessage tag-token sequences match for every scoped key.

Sources: `src/main/resources/lang/ru.yml` and `src/main/resources/lang/en.yml` in the farms worktree; mirrors under `classic/plugins/ArcFarms/lang/`, `parkour/plugins/ArcFarms/lang/`, and `classic_survival/plugins/ArcFarms/lang/` in the ops worktree. The scoped key list and machine-readable comparisons are in `/private/tmp/mine-events-scoped-validation-20261023.json`.

The parent-reported full-profile dry run still has unrelated baseline blockers: stale MiniMessage in an unrelated catalog, plus `classic_survival` English differences at `admin.moles-unavailable` and `companies.participation.personal-shadow`. These keys are outside the 41-key scope and were left untouched. This check validates YAML and token parity; it does not run runtime MiniMessage deserialization.
