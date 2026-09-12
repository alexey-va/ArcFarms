package ru.ruscrafting.farms.paper.mine.presentation

import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.entity.Player
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.domain.FarmEventTypeRegistry
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.MineScenarioCatalog
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetStatus
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.mine.incident.scenario.MineScenarioRooms
import ru.ruscrafting.farms.paper.worksite.WorksiteAudiencePort
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.MineRuntimeRegistry
import ru.ruscrafting.farms.paper.worksite.WorksiteGuidanceSource
import ru.ruscrafting.farms.paper.worksite.WorksiteGuidanceTarget
import ru.ruscrafting.farms.paper.worksite.WorksiteGuidanceView
import java.util.UUID
import kotlin.math.absoluteValue

internal class MineGuidanceSource(
    private val registry: MineRuntimeRegistry,
    private val audience: WorksiteAudiencePort,
    private val locale: ArcFarmsLocale? = null,
    private val routeTarget: (MineRuntime) -> WorksitePosition? = { null },
    private val routeTotal: (MineRuntime) -> Int = { 1 },
    private val rooms: MineScenarioRooms? = null,
) : WorksiteGuidanceSource {
    override fun participants(): Collection<Player> = registry.snapshot().flatMap { runtime ->
        runtime.region.world.players.filter { runtimeFor(it) === runtime }
    }.distinctBy(Player::getUniqueId)

    override fun view(playerId: UUID): WorksiteGuidanceView? {
        val player = Bukkit.getPlayer(playerId) ?: return null
        val runtime = runtimeFor(player) ?: return null
        return view(player, runtime)
    }

    internal fun view(player: Player, runtime: MineRuntime): WorksiteGuidanceView {
        scenarioView(player, runtime)?.let { return it }
        val (done, total) = progress(runtime)
        val action = actionKey(runtime)
        val resource = resourceSummary(runtime)
        val resourceValues = mapOf("resource" to resource)
        val values = resourceValues + mapOf(
            "done" to text(done), "total" to text(total),
            "action" to render("mine.guidance.$action", player, resourceValues),
        )
        return WorksiteGuidanceView(
            "mine:${runtime.settings.id}", progressVersion(runtime),
            render("mine.guidance.title", player, values),
            render("mine.guidance.$action", player, values),
            render("mine.guidance.bar", player, values),
            done.toFloat() / total.coerceAtLeast(1),
            if (runtime.state.phase == MinePhase.INCIDENT) BossBar.Color.RED else BossBar.Color.BLUE,
            targets(player, runtime),
            quietProgress = runtime.settings.miningOnly,
            sidebarRows = listOf(
                render("route.mine.${runtime.settings.id}", player),
                render("mine.guidance.$action", player, values),
                render("scoreboard.progress", player, values),
            ) + resourceRows(runtime, player),
        )
    }

    private fun resourceSummary(runtime: MineRuntime): Component {
        val materials = runtime.currentOrder()?.miningMaterials.orEmpty()
        return materials.map(::resourceName).foldIndexed(Component.empty()) { index, result, resource ->
            result.append(if (index == 0) Component.empty() else Component.text(" · ")).append(resource)
        }
    }

    private fun resourceRows(runtime: MineRuntime, player: Player): List<Component> {
        val requirements = runtime.currentOrder()?.miningRequirements.orEmpty()
        if (requirements.isEmpty() || runtime.state.phase != MinePhase.MINING) return emptyList()
        return requirements.map { (material, required) ->
            render("mine.guidance.resource-progress", player, mapOf(
                "resource" to resourceName(material),
                "done" to text((runtime.state.minedByMaterial[material] ?: 0).coerceAtMost(required)),
                "total" to text(required),
            ))
        }
    }

    private fun resourceName(material: String): Component = ru.ruscrafting.farms.paper.MaterialRules.cropComponent(
        ru.ruscrafting.farms.paper.MaterialRules.material(material),
    )

    private fun targets(player: Player, runtime: MineRuntime): List<WorksiteGuidanceTarget> {
        if (runtime.settings.miningOnly && runtime.state.phase == MinePhase.MINING) return emptyList()
        val playerId = player.uniqueId
        val objective = runtime.state.objective?.targets.orEmpty().filter { target ->
            target.status != ObjectiveTargetStatus.COMPLETED &&
                (target.status != ObjectiveTargetStatus.LEASED || target.leasedBy == playerId)
        }.mapNotNull { target -> target.position.guidance(target.id, target.role) }
        if (objective.isNotEmpty()) return objective
        val route = if (!runtime.settings.miningOnly && runtime.state.phase == MinePhase.EXTRACTION) routeTarget(runtime) else null
        if (route != null) return listOfNotNull(route.guidance("route_next", ObjectiveTargetRole("extraction")))
        if (runtime.state.phase in setOf(MinePhase.IDLE, MinePhase.COOLDOWN, MinePhase.EXTRACTION)) return emptyList()
        val bounds = runtime.region.bounds
        return listOf(
            WorksiteGuidanceTarget(
                "${runtime.state.phase.name.lowercase()}_destination", ObjectiveTargetRole("destination"),
                Location(runtime.region.world, (bounds.minX + bounds.maxX + 1) / 2.0, bounds.minY + 1.0,
                    (bounds.minZ + bounds.maxZ + 1) / 2.0),
                color(ObjectiveTargetRole("destination")),
            ),
        )
    }

    private fun scenarioView(player: Player, runtime: MineRuntime): WorksiteGuidanceView? {
        if (runtime.state.phase != MinePhase.INCIDENT) return null
        val incident = runtime.state.incident ?: return null
        if (incident.scenarioPlacement == null) return null
        val id = incident.type.name.lowercase()
        val definition = MineScenarioCatalog.definition(id) ?: return null
        val stage = definition.stageAt(incident.progress) ?: return null
        val currentStage = stage.definition.stages[stage.index]
        val event = FarmEventTypeRegistry.definition(incident.type)
        val floor = floorName(incident.scenarioPlacement.floorId, runtime, player)
        val destinationFloor = floorName(incident.scenarioPlacement.destinationFloorId, runtime, player)
        val stageAction = render(
            "mine.events.$id.stages.${currentStage.id}", player,
            mapOf("floor" to floor, "destination" to destinationFloor),
        )
        val values = mapOf(
            "done" to text(incident.progress), "total" to text(incident.required),
            "stage" to text(stage.index + 1), "stages" to text(definition.stages.size),
            "floor" to floor, "destination" to destinationFloor,
            "action" to stageAction,
        )
        val stageValues = values + mapOf(
            "done" to text(stage.progressWithinStage), "total" to text(currentStage.required),
        )
        val insideRoom = rooms?.at(player.location)?.let(registry::byId) === runtime
        val carrying = runtime.state.objective?.targets.orEmpty().any { it.leasedBy == player.uniqueId }
        val nextAction = if (insideRoom || carrying) stageAction else render(
            if (rooms?.scene(runtime)?.ready == true) "mine.events.common.entry" else "mine.events.common.building", player, values)
        val shownValues = values + ("action" to nextAction)
        return WorksiteGuidanceView(
            "mine:${runtime.settings.id}", progressVersion(runtime),
            render(event.titlePath, player, values),
            render(event.hintPath, player, values),
            render("mine.guidance.bar", player, shownValues),
            incident.progress.toFloat() / incident.required.coerceAtLeast(1),
            BossBar.Color.RED,
            scenarioTargets(player, runtime, insideRoom, currentStage.action.name),
            sidebarRows = listOf(
                render(event.titlePath, player, values),
                render("mine.events.common.floor", player, stageValues),
                nextAction,
                render("mine.events.common.stage-progress", player, stageValues),
            ),
        )
    }

    private fun scenarioTargets(
        player: Player,
        runtime: MineRuntime,
        insideRoom: Boolean,
        stageAction: String,
    ): List<WorksiteGuidanceTarget> {
        val placement = runtime.state.incident?.scenarioPlacement ?: return emptyList()
        val objective = objectiveTargets(player.uniqueId, runtime)
        if (stageAction in setOf("CARRY", "ESCORT")) {
            val leased = runtime.state.objective?.targets.orEmpty().firstOrNull {
                it.status == ObjectiveTargetStatus.LEASED && it.leasedBy == player.uniqueId
            }
            if (leased != null) {
                val destination = if (!insideRoom) placement.destination
                else if (stageAction == "ESCORT") placement.origin.let {
                    WorksitePosition(it.world, it.x + 8, it.y + 2,
                        it.z + 3 + (runtime.state.incident?.scenarioStep ?: 0).coerceAtMost(19))
                }
                else rooms?.scene(runtime)?.lair?.let { WorksitePosition(it.world.name, it.blockX, it.blockY, it.blockZ) }
                    ?: placement.entrance
                return listOfNotNull(destination.guidance("scenario_carry_destination", ObjectiveTargetRole("destination")))
            }
        }
        if (!insideRoom) return listOfNotNull(placement.entrance.guidance("scenario_entrance", ObjectiveTargetRole("destination")))
        return if (stageAction in setOf("ORDERED_INTERACT", "STEER")) objective.take(1) else objective
    }

    private fun objectiveTargets(playerId: UUID, runtime: MineRuntime): List<WorksiteGuidanceTarget> =
        runtime.state.objective?.targets.orEmpty().filter { target ->
            target.status != ObjectiveTargetStatus.COMPLETED &&
                (target.status != ObjectiveTargetStatus.LEASED || target.leasedBy == playerId)
        }.mapNotNull { target -> target.position.guidance(target.id, target.role) }

    private fun runtimeFor(player: Player): MineRuntime? = registry.snapshot().firstOrNull { runtime ->
        runtime.state.incident?.scenarioPlacement != null &&
            runtime.state.objective?.targets.orEmpty().any { target ->
                target.status == ObjectiveTargetStatus.LEASED && target.leasedBy == player.uniqueId
            }
    } ?: rooms?.at(player.location)?.let(registry::byId) ?: registry.forAudience(player.location)

    private fun floorName(id: String, runtime: MineRuntime, player: Player): Component =
        if (id in LIFT_FLOORS) render("mine-lift.floors.$id", player)
        else render("route.mine.${runtime.settings.id}", player)

    private fun WorksitePosition.guidance(id: String, role: ObjectiveTargetRole): WorksiteGuidanceTarget? {
        val world = Bukkit.getWorld(world) ?: return null
        return WorksiteGuidanceTarget(
            id, role, Location(world, x + 0.5, y + 0.35, z + 0.5), color(role),
            world.isChunkLoaded(x shr 4, z shr 4), if (role.value == "lost_miner") 1.45f else 1.1f,
        )
    }

    private fun actionKey(runtime: MineRuntime): String = if (runtime.state.phase == MinePhase.INCIDENT) {
        runtime.state.incident?.type?.name?.lowercase() ?: "incident"
    } else if (runtime.settings.miningOnly && runtime.state.phase == MinePhase.EXTRACTION) "completion_pending"
    else if (runtime.settings.miningOnly && runtime.state.phase == MinePhase.MINING &&
        runtime.currentOrder()?.miningRequirements?.isNotEmpty() == true) "multi_resource_order"
    else if (runtime.settings.miningOnly && runtime.state.phase == MinePhase.MINING) "resource_order"
    else runtime.state.phase.name.lowercase()

    private fun progress(runtime: MineRuntime): Pair<Int, Int> = when (runtime.state.phase) {
        MinePhase.PROSPECTING -> runtime.state.prospected to runtime.rules().prospectingQuota
        MinePhase.MINING -> runtime.state.mined to runtime.rules().miningQuota
        MinePhase.LOADING -> runtime.state.loaded to runtime.rules().loadingQuota
        MinePhase.EXTRACTION -> runtime.state.routeIndex to routeTotal(runtime).coerceAtLeast(1)
        MinePhase.INCIDENT -> runtime.state.incident?.let { it.progress to it.required } ?: (0 to 1)
        MinePhase.IDLE, MinePhase.HAZARD, MinePhase.COOLDOWN -> 0 to 1
    }

    private fun progressVersion(runtime: MineRuntime): Long = (listOf(
        runtime.state.sequence, runtime.state.phase.ordinal.toLong(), runtime.state.prospected.toLong(),
        runtime.state.mined.toLong(), runtime.state.loaded.toLong(), runtime.state.routeIndex.toLong(),
        runtime.state.incident?.progress?.toLong() ?: 0L, runtime.state.incidentCursor.toLong(),
    ) + runtime.state.minedByMaterial.toSortedMap().flatMap { (material, count) ->
        listOf(material.hashCode().toLong(), count.toLong())
    }).fold(17L) { hash, value -> hash * 31L + value }.and(Long.MAX_VALUE)

    private fun color(role: ObjectiveTargetRole): Color = COLORS[role.value] ?: run {
        val hash = role.value.hashCode().absoluteValue
        Color.fromRGB(80 + hash % 150, 80 + hash / 17 % 150, 80 + hash / 31 % 150)
    }

    private fun render(path: String, player: Player, values: Map<String, Component> = emptyMap()): Component =
        locale?.renderPath(path, player, values) ?: Component.text(path.substringAfterLast('.').replace('_', ' '))
    private fun text(value: Any): Component = locale?.text(value) ?: Component.text(value.toString())

    private companion object {
        val LIFT_FLOORS = setOf("top", "upper", "middle", "lower", "bottom")
        val COLORS = mapOf(
            "prospect" to Color.fromRGB(69, 200, 245), "mineable" to Color.fromRGB(255, 200, 87),
            "ore_crate" to Color.fromRGB(255, 173, 66), "support_kit" to Color.fromRGB(85, 217, 139),
            "gas_vent" to Color.fromRGB(139, 211, 255), "crystal_node" to Color.fromRGB(189, 82, 214),
            "flood_pump" to Color.fromRGB(69, 150, 245), "lost_miner" to Color.fromRGB(255, 95, 109),
            "extraction" to Color.fromRGB(85, 217, 139),
        )
    }
}
