package ru.ruscrafting.farms.domain

/** Shared interaction baseline for farm events. */
enum class FarmEventArchetype { FIELD, PORTAL, UNDERGROUND }

sealed class FarmEventTypeDefinition(
    val archetype: FarmEventArchetype,
    val titlePath: String,
    val subtitlePath: String,
    val hintPath: String,
    val particleColumns: Boolean = archetype != FarmEventArchetype.PORTAL,
    val entryHint: Boolean = archetype != FarmEventArchetype.FIELD,
) {
    class FieldEvent(titlePath: String, subtitlePath: String, hintPath: String) : FarmEventTypeDefinition(
        FarmEventArchetype.FIELD, titlePath, subtitlePath, hintPath,
    )

    class PortalEvent(titlePath: String, subtitlePath: String, hintPath: String) : FarmEventTypeDefinition(
        FarmEventArchetype.PORTAL, titlePath, subtitlePath, hintPath,
    )

    class UndergroundEvent(titlePath: String, subtitlePath: String, hintPath: String) : FarmEventTypeDefinition(
        FarmEventArchetype.UNDERGROUND, titlePath, subtitlePath, hintPath,
    )
}

/** Explicit registry: adding an incident requires choosing its player-facing archetype. */
object FarmEventTypeRegistry {
    private val definitions: Map<FarmIncidentType, FarmEventTypeDefinition> = mapOf(
        FarmIncidentType.PESTS to FarmEventTypeDefinition.FieldEvent("farm.incident-started", "farm.incident-started-subtitle", "scoreboard.hint.pests"),
        FarmIncidentType.DROUGHT to FarmEventTypeDefinition.FieldEvent("farm.drought-started", "farm.drought-started-subtitle", "scoreboard.hint.drought"),
        FarmIncidentType.BIRDS to FarmEventTypeDefinition.FieldEvent("farm.birds-started", "farm.birds-started-subtitle", "scoreboard.hint.birds"),
        FarmIncidentType.FOOD_DELIVERY to FarmEventTypeDefinition.PortalEvent("farm.route-started", "farm.route-started-subtitle", "scoreboard.hint.food-delivery"),
        FarmIncidentType.GIANT_CROP to FarmEventTypeDefinition.FieldEvent("farm.giant-crop-started", "farm.giant-crop-started-subtitle", "scoreboard.hint.giant-crop"),
        FarmIncidentType.CHANNELS to FarmEventTypeDefinition.FieldEvent("farm.channels-started", "farm.channels-started-subtitle", "scoreboard.hint.channels"),
        FarmIncidentType.NIGHT_SHIFT to FarmEventTypeDefinition.FieldEvent("farm.night-shift-started", "farm.night-shift-started-subtitle", "scoreboard.hint.night-shift"),
        FarmIncidentType.MARKET to FarmEventTypeDefinition.FieldEvent("farm.market-started", "farm.market-started-subtitle", "scoreboard.hint.market"),
        FarmIncidentType.PROCESSING to FarmEventTypeDefinition.FieldEvent("farm.processing.started", "farm.processing.started-subtitle", "scoreboard.hint.processing"),
        FarmIncidentType.BARN_FIRE to FarmEventTypeDefinition.FieldEvent("farm.barn-fire.started", "farm.barn-fire.started-subtitle", "scoreboard.hint.barn-fire"),
        FarmIncidentType.FROST to FarmEventTypeDefinition.FieldEvent("farm.frost.started", "farm.frost.started-subtitle", "scoreboard.hint.frost"),
        FarmIncidentType.BOAR_BREAKOUT to FarmEventTypeDefinition.FieldEvent("farm.boar-breakout.started", "farm.boar-breakout.started-subtitle", "scoreboard.hint.boar-breakout"),
        FarmIncidentType.RIVAL_RAID to FarmEventTypeDefinition.PortalEvent("farm.rival-raid.started", "farm.rival-raid.started-subtitle", "scoreboard.hint.rival-raid"),
        FarmIncidentType.TORNADO to FarmEventTypeDefinition.FieldEvent("farm.tornado.started", "farm.tornado.started-subtitle", "scoreboard.hint.tornado"),
        FarmIncidentType.HELL_GREENHOUSE to FarmEventTypeDefinition.UndergroundEvent("farm.hell-greenhouse.started", "farm.hell-greenhouse.started-subtitle", "scoreboard.hint.hell-greenhouse"),
    ).also { registered ->
        check(registered.keys == FarmIncidentType.entries.toSet()) {
            "Farm event archetype registry is incomplete: ${FarmIncidentType.entries.toSet() - registered.keys}"
        }
    }

    private val mineDefinitions: Map<MineIncidentType, FarmEventTypeDefinition> = MineIncidentType.entries.associateWith { type ->
        val id = type.name.lowercase()
        if (MineScenarioCatalog.definition(id) != null) {
            FarmEventTypeDefinition.UndergroundEvent("mine.events.$id.title", "mine.events.$id.subtitle", "mine.events.$id.hint")
        } else {
            FarmEventTypeDefinition.FieldEvent("mine.guidance.title", "mine.guidance.$id", "mine.guidance.$id")
        }
    }

    fun definition(type: MineIncidentType): FarmEventTypeDefinition = mineDefinitions.getValue(type)

    fun definition(type: FarmIncidentType): FarmEventTypeDefinition = definitions.getValue(type)
}
