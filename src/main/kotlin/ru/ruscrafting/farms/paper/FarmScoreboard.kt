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
    val carrying: Boolean = false,
)

internal class FarmScoreboardRenderer(
    private val locale: ArcFarmsLocale,
) {
    fun title(audience: CommandSender?): Component = locale.renderPath("scoreboard.title", audience)

    fun rows(view: FarmScoreboardView, audience: CommandSender?): List<Component> = buildList {
        add(locale.renderPath("scoreboard.section.order", audience))
        add(locale.renderPath(
            "scoreboard.order",
            audience,
            mapOf("order" to locale.renderPath("order.farm.${view.orderId}", audience)),
        ))
        add(Component.empty())
        add(locale.renderPath("scoreboard.section.current", audience))
        add(locale.renderPath("scoreboard.objective-line", audience, mapOf("objective" to objective(view, audience))))
        add(locale.renderPath(
            "scoreboard.progress",
            audience,
            mapOf(
                "done" to locale.text(view.done.coerceAtLeast(0)),
                "total" to locale.text(view.total.coerceAtLeast(1)),
            ),
        ))
        add(locale.renderPath("scoreboard.hint-line", audience, mapOf("hint" to hint(view, audience))))
        add(Component.empty())
        add(locale.renderPath("scoreboard.section.crops", audience))
        view.required.entries.take(MAX_CROP_ROWS).forEach { (cropName, required) ->
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
    }.also { rows ->
        require(rows.size <= MAX_ROWS) { "Farm scoreboard exceeds $MAX_ROWS rows" }
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
            FarmPhase.INCIDENT -> if (view.incidentType == FarmIncidentType.DROUGHT) {
                "scoreboard.objective.drought"
            } else {
                "scoreboard.objective.pests"
            }
            FarmPhase.DELIVERY -> if (view.carrying) {
                "scoreboard.objective.delivery-carrying"
            } else {
                "scoreboard.objective.delivery"
            }
            FarmPhase.COOLDOWN -> "scoreboard.objective.cooldown"
        }
        val values = view.careType?.let { type ->
            mapOf("care" to locale.renderPath("care.${type.name.lowercase()}.name", audience))
        }.orEmpty()
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
            FarmPhase.INCIDENT -> if (view.incidentType == FarmIncidentType.DROUGHT) {
                "scoreboard.hint.drought"
            } else {
                "scoreboard.hint.pests"
            }
            FarmPhase.DELIVERY -> if (view.carrying) {
                "scoreboard.hint.delivery-carrying"
            } else {
                "scoreboard.hint.delivery"
            }
            FarmPhase.COOLDOWN -> "scoreboard.hint.cooldown"
        }
        return locale.renderPath(path, audience)
    }

    private fun Int?.orZero(): Int = this ?: 0

    companion object {
        const val MAX_ROWS = 15
        const val MAX_CROP_ROWS = 5
    }
}
