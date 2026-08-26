package ru.ruscrafting.farms.paper

import net.kyori.adventure.text.Component
import org.bukkit.command.CommandSender
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.domain.FarmCareType
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmSeederStage

internal data class FarmScoreboardView(
    val orderId: String,
    val phase: FarmPhase,
    val done: Int,
    val total: Int,
    val required: Map<String, Int>,
    val cropProgress: Map<String, Int>,
    val careType: FarmCareType? = null,
    val seederStage: FarmSeederStage? = null,
    val incidentType: FarmIncidentType? = null,
    val incidentCrop: String? = null,
    val marketAccepted: Boolean = false,
    val carrying: Boolean = false,
)

internal class FarmScoreboardRenderer(
    private val locale: ArcFarmsLocale,
) {
    fun title(audience: CommandSender?): Component = locale.renderPath("scoreboard.title", audience)

    fun rows(view: FarmScoreboardView, audience: CommandSender?): List<Component> {
        val rows = buildList {
            add(locale.renderPath("scoreboard.section.order", audience))
            add(locale.renderPath(
                "scoreboard.order",
                audience,
                mapOf("order" to locale.renderPath("order.farm.${view.orderId}", audience)),
            ))
            add(Component.empty())
            add(locale.renderPath("scoreboard.section.current", audience))
            add(locale.renderPath("scoreboard.objective-line", audience, mapOf("objective" to objective(view, audience))))
            if (view.phase == FarmPhase.INCIDENT && view.incidentType == FarmIncidentType.MARKET && !view.marketAccepted) {
                add(locale.renderPath("scoreboard.market-pending", audience))
            } else {
                add(locale.renderPath(
                    "scoreboard.progress",
                    audience,
                    mapOf(
                        "done" to locale.text(view.done.coerceAtLeast(0)),
                        "total" to locale.text(view.total.coerceAtLeast(1)),
                    ),
                ))
            }
            add(locale.renderPath("scoreboard.hint-line", audience, mapOf("hint" to hint(view, audience))))
            hintDetails(view, audience).forEach { detail ->
                add(locale.renderPath("scoreboard.hint-line", audience, mapOf("hint" to detail)))
            }
            add(Component.empty())
            add(locale.renderPath("scoreboard.section.crops", audience))
            val cropRows = (MAX_ROWS - size).coerceIn(0, MAX_CROP_ROWS)
            view.required.entries.take(cropRows).forEach { (cropName, required) ->
                add(locale.renderPath(
                    "scoreboard.crop",
                    audience,
                    mapOf(
                        "crop" to locale.renderPath(
                            "crop.${MaterialRules.material(cropName).name.lowercase()}",
                            audience,
                        ),
                        "done" to locale.text(view.cropProgress[cropName].orZero().coerceAtMost(required)),
                        "total" to locale.text(required),
                    ),
                ))
            }
        }
        require(rows.size <= MAX_ROWS) { "Farm scoreboard exceeds $MAX_ROWS rows" }
        return rows
    }

    private fun objective(view: FarmScoreboardView, audience: CommandSender?): Component {
        val path = when (view.phase) {
            FarmPhase.IDLE -> "scoreboard.objective.idle"
            FarmPhase.PREPARATION -> "scoreboard.objective.preparation"
            FarmPhase.PLANTING -> "scoreboard.objective.planting"
            FarmPhase.CARE -> when (view.seederStage) {
                FarmSeederStage.TILLING -> "scoreboard.objective.seeder-tilling"
                FarmSeederStage.PLANTING -> "scoreboard.objective.seeder-planting"
                null -> "scoreboard.objective.care"
            }
            FarmPhase.HARVESTING -> "scoreboard.objective.harvesting"
            FarmPhase.INCIDENT -> if (view.incidentType == FarmIncidentType.MARKET) {
                if (view.marketAccepted) "scoreboard.objective.market-active" else "scoreboard.objective.market-pending"
            } else {
                "scoreboard.objective.${view.incidentType.scoreboardId()}"
            }
            FarmPhase.DELIVERY -> if (view.carrying) {
                "scoreboard.objective.delivery-carrying"
            } else {
                "scoreboard.objective.delivery"
            }
            FarmPhase.COOLDOWN -> "scoreboard.objective.cooldown"
        }
        val values = buildMap {
            view.careType?.let { type ->
                put("care", locale.renderPath("care.${type.name.lowercase()}.name", audience))
            }
            view.incidentCrop?.let { crop ->
                put("crop", locale.renderPath("crop.${MaterialRules.material(crop).name.lowercase()}", audience))
            }
        }
        return locale.renderPath(path, audience, values)
    }

    private fun hint(view: FarmScoreboardView, audience: CommandSender?): Component {
        val path = when (view.phase) {
            FarmPhase.IDLE -> "scoreboard.hint.idle"
            FarmPhase.PREPARATION -> "scoreboard.hint.preparation"
            FarmPhase.PLANTING -> "scoreboard.hint.planting"
            FarmPhase.CARE -> when (view.seederStage) {
                FarmSeederStage.TILLING -> "scoreboard.hint.care.seeder-tilling"
                FarmSeederStage.PLANTING -> "scoreboard.hint.care.seeder-planting"
                null -> view.careType?.let { "scoreboard.hint.care.${it.name.lowercase()}" }
                    ?: "scoreboard.hint.care.generic"
            }
            FarmPhase.HARVESTING -> "scoreboard.hint.harvesting"
            FarmPhase.INCIDENT -> if (view.incidentType == FarmIncidentType.MARKET) {
                if (view.marketAccepted) "scoreboard.hint.market-active" else "scoreboard.hint.market-pending"
            } else {
                "scoreboard.hint.${view.incidentType.scoreboardId()}"
            }
            FarmPhase.DELIVERY -> if (view.carrying) {
                "scoreboard.hint.delivery-carrying"
            } else {
                "scoreboard.hint.delivery"
            }
            FarmPhase.COOLDOWN -> "scoreboard.hint.cooldown"
        }
        val values = view.incidentCrop?.let { crop ->
            mapOf("crop" to locale.renderPath("crop.${MaterialRules.material(crop).name.lowercase()}", audience))
        }.orEmpty()
        return locale.renderPath(path, audience, values)
    }

    private fun hintDetails(view: FarmScoreboardView, audience: CommandSender?): List<Component> {
        val id = when {
            view.phase == FarmPhase.PREPARATION -> "preparation"
            view.phase == FarmPhase.PLANTING -> "planting"
            view.phase == FarmPhase.CARE && view.seederStage == FarmSeederStage.TILLING -> "seeder-tilling"
            view.phase == FarmPhase.CARE && view.seederStage == FarmSeederStage.PLANTING -> "seeder-planting"
            view.phase == FarmPhase.INCIDENT && view.incidentType == FarmIncidentType.CHANNELS -> "channels"
            view.phase == FarmPhase.INCIDENT && view.incidentType == FarmIncidentType.NIGHT_SHIFT -> "night-shift"
            view.phase == FarmPhase.INCIDENT && view.incidentType == FarmIncidentType.MARKET && !view.marketAccepted ->
                "market-pending"
            else -> null
        } ?: return emptyList()
        return listOf(locale.renderPath("scoreboard.hint-detail.$id", audience))
    }

    private fun Int?.orZero(): Int = this ?: 0

    private fun FarmIncidentType?.scoreboardId(): String = when (this) {
        null, FarmIncidentType.PESTS -> "pests"
        FarmIncidentType.DROUGHT -> "drought"
        FarmIncidentType.GIANT_CROP -> "giant-crop"
        FarmIncidentType.CHANNELS -> "channels"
        FarmIncidentType.NIGHT_SHIFT -> "night-shift"
        FarmIncidentType.MARKET -> "market"
    }

    companion object {
        const val MAX_ROWS = 15
        const val MAX_CROP_ROWS = 5
    }
}
