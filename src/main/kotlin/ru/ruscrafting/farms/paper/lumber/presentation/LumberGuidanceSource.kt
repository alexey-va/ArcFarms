package ru.ruscrafting.farms.paper.lumber.presentation

import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.entity.Player
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.domain.LumberIncidentType
import ru.ruscrafting.farms.domain.LumberPhase
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetStatus
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.lumber.LumberRuntime
import ru.ruscrafting.farms.paper.lumber.LumberRuntimeRegistry
import ru.ruscrafting.farms.paper.worksite.WorksiteGuidanceSource
import ru.ruscrafting.farms.paper.worksite.WorksiteGuidanceTarget
import ru.ruscrafting.farms.paper.worksite.WorksiteGuidanceView
import java.util.UUID
import kotlin.math.absoluteValue

internal class LumberGuidanceSource(
    private val registry: LumberRuntimeRegistry,
    private val port: WorksiteRuntimePort,
    private val locale: ArcFarmsLocale? = null,
) : WorksiteGuidanceSource {
    override fun participants(): Collection<Player> = registry.snapshot()
        .flatMap { runtime -> port.players(runtime.region) + port.players(runtime.station) }
        .distinctBy(Player::getUniqueId)

    override fun view(playerId: UUID): WorksiteGuidanceView? {
        val player = Bukkit.getPlayer(playerId) ?: return null
        val runtime = registry.snapshot().firstOrNull { runtime ->
            runtime.region.contains(player.location) || runtime.station.contains(player.location)
        } ?: return null
        if (runtime.state.phase == LumberPhase.IDLE) return null
        return view(player, runtime)
    }

    internal fun view(player: Player, runtime: LumberRuntime): WorksiteGuidanceView {
        val (done, total) = progress(runtime)
        val action = actionKey(runtime)
        val values = mapOf(
            "done" to text(done),
            "total" to text(total),
            "action" to render("lumber.guidance.$action", player),
        )
        return WorksiteGuidanceView(
            runtimeKey = "lumber:${runtime.settings.id}",
            progressVersion = progressVersion(runtime),
            title = render("lumber.guidance.title", player, values),
            subtitle = render("lumber.guidance.$action", player, values),
            barName = render("lumber.guidance.bar", player, values),
            barProgress = done.toFloat() / total.coerceAtLeast(1),
            barColor = if (runtime.state.phase == LumberPhase.INCIDENT) BossBar.Color.RED else BossBar.Color.YELLOW,
            targets = targets(playerId = player.uniqueId, runtime = runtime),
        )
    }

    private fun targets(playerId: UUID, runtime: LumberRuntime): List<WorksiteGuidanceTarget> {
        val objectiveTargets = runtime.state.objective?.targets.orEmpty()
            .filter { target ->
                target.status != ObjectiveTargetStatus.COMPLETED &&
                    (target.status != ObjectiveTargetStatus.LEASED || target.leasedBy == playerId)
            }
            .mapNotNull { target ->
                val world = Bukkit.getWorld(target.position.world) ?: return@mapNotNull null
                WorksiteGuidanceTarget(
                    id = target.id,
                    role = target.role,
                    position = Location(world, target.position.x + 0.5, target.position.y + 0.35, target.position.z + 0.5),
                    color = color(target.role),
                    loaded = world.isChunkLoaded(target.position.x shr 4, target.position.z shr 4),
                    particleSize = if (target.role.value == "fire") 1.45f else 1.1f,
                )
            }
        if (objectiveTargets.isNotEmpty()) return objectiveTargets
        if (runtime.state.phase == LumberPhase.COOLDOWN) return emptyList()
        val region = if (runtime.state.phase == LumberPhase.FELLING) runtime.region else runtime.station
        val bounds = region.bounds
        return listOf(
            WorksiteGuidanceTarget(
                id = "${runtime.state.phase.name.lowercase()}_destination",
                role = ObjectiveTargetRole(destinationRole(runtime)),
                position = Location(
                    region.world,
                    (bounds.minX + bounds.maxX + 1) / 2.0,
                    bounds.minY + 1.0,
                    (bounds.minZ + bounds.maxZ + 1) / 2.0,
                ),
                color = color(ObjectiveTargetRole(destinationRole(runtime))),
            ),
        )
    }

    private fun destinationRole(runtime: LumberRuntime): String = when (runtime.state.phase) {
        LumberPhase.SAWING -> "saw"
        LumberPhase.STACKING -> "plank_rack"
        LumberPhase.DISPATCH -> "dispatch"
        LumberPhase.INCIDENT -> runtime.state.incident?.type?.name?.lowercase() ?: "incident"
        else -> "destination"
    }

    private fun actionKey(runtime: LumberRuntime): String {
        if (runtime.state.rushOrder?.bonusAvailable == true && runtime.state.phase == LumberPhase.STACKING) return "rush_order"
        if (runtime.state.phase == LumberPhase.INCIDENT) {
            return runtime.state.incident?.type?.name?.lowercase() ?: "incident"
        }
        return runtime.state.phase.name.lowercase()
    }

    private fun progress(runtime: LumberRuntime): Pair<Int, Int> = when (runtime.state.phase) {
        LumberPhase.FELLING -> runtime.state.felled to runtime.rules().fellingQuota
        LumberPhase.SKIDDING -> runtime.state.skidded to runtime.rules().skiddingQuota
        LumberPhase.SAWING -> runtime.state.sawCuts to runtime.rules().sawingQuota
        LumberPhase.STACKING, LumberPhase.DISPATCH -> runtime.state.stacked to runtime.rules().stackingQuota
        LumberPhase.INCIDENT -> runtime.state.incident?.let { it.progress to it.required } ?: (0 to 1)
        LumberPhase.PROCESSING -> runtime.state.processed to runtime.rules().processingQuota
        LumberPhase.IDLE, LumberPhase.COOLDOWN -> 0 to 1
    }

    private fun progressVersion(runtime: LumberRuntime): Long = listOf(
        runtime.state.sequence,
        runtime.state.phase.ordinal.toLong(),
        runtime.state.felled.toLong(),
        runtime.state.skidded.toLong(),
        runtime.state.sawCuts.toLong(),
        runtime.state.stacked.toLong(),
        runtime.state.incident?.progress?.toLong() ?: 0L,
        runtime.state.incidentCursor.toLong(),
        runtime.state.rushOrder?.bonusAvailable?.compareTo(false)?.toLong() ?: 0L,
    ).fold(17L) { hash, value -> hash * 31L + value }.and(Long.MAX_VALUE)

    private fun color(role: ObjectiveTargetRole): Color = KNOWN_COLORS[role.value] ?: run {
        val hash = role.value.hashCode().absoluteValue
        Color.fromRGB(96 + hash % 128, 96 + hash / 17 % 128, 96 + hash / 31 % 128)
    }

    private fun render(path: String, player: Player, values: Map<String, Component> = emptyMap()): Component =
        locale?.renderPath(path, player, values) ?: Component.text(FALLBACKS[path] ?: path.substringAfterLast('.').replace('_', ' '))

    private fun text(value: Any): Component = locale?.text(value) ?: Component.text(value.toString())

    private companion object {
        val KNOWN_COLORS = mapOf(
            "log" to Color.fromRGB(216, 165, 109),
            "bundle" to Color.fromRGB(255, 173, 66),
            "saw" to Color.fromRGB(255, 200, 87),
            "pallet" to Color.fromRGB(85, 217, 139),
            "dispatch" to Color.fromRGB(69, 200, 245),
            "fire" to Color.fromRGB(255, 95, 109),
        )
        val FALLBACKS = mapOf(
            "lumber.guidance.title" to "Lumbermill task",
            "lumber.guidance.bar" to "Lumbermill progress",
        )
    }
}
