package ru.ruscrafting.farms.paper.mine.presentation

import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.entity.Player
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetStatus
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
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
) : WorksiteGuidanceSource {
    override fun participants(): Collection<Player> = registry.snapshot().flatMap { audience.players(it.region) }
        .distinctBy(Player::getUniqueId)

    override fun view(playerId: UUID): WorksiteGuidanceView? {
        val player = Bukkit.getPlayer(playerId) ?: return null
        val runtime = registry.at(player.location) ?: return null
        if (runtime.state.phase == MinePhase.IDLE) return null
        return view(player, runtime)
    }

    internal fun view(player: Player, runtime: MineRuntime): WorksiteGuidanceView {
        val (done, total) = progress(runtime)
        val action = actionKey(runtime)
        val values = mapOf(
            "done" to text(done), "total" to text(total),
            "action" to render("mine.guidance.$action", player),
        )
        return WorksiteGuidanceView(
            "mine:${runtime.settings.id}", progressVersion(runtime),
            render("mine.guidance.title", player, values),
            render("mine.guidance.$action", player, values),
            render("mine.guidance.bar", player, values),
            done.toFloat() / total.coerceAtLeast(1),
            if (runtime.state.phase == MinePhase.INCIDENT) BossBar.Color.RED else BossBar.Color.BLUE,
            targets(player.uniqueId, runtime),
            sidebarRows = listOf(
                render("mine.guidance.$action", player, values),
                render("scoreboard.progress", player, values),
            ),
        )
    }

    private fun targets(playerId: UUID, runtime: MineRuntime): List<WorksiteGuidanceTarget> {
        val objective = runtime.state.objective?.targets.orEmpty().filter { target ->
            target.status != ObjectiveTargetStatus.COMPLETED &&
                (target.status != ObjectiveTargetStatus.LEASED || target.leasedBy == playerId)
        }.mapNotNull { target -> target.position.guidance(target.id, target.role) }
        if (objective.isNotEmpty()) return objective
        val route = if (runtime.state.phase == MinePhase.EXTRACTION) routeTarget(runtime) else null
        if (route != null) return listOfNotNull(route.guidance("route_next", ObjectiveTargetRole("extraction")))
        if (runtime.state.phase == MinePhase.COOLDOWN) return emptyList()
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

    private fun WorksitePosition.guidance(id: String, role: ObjectiveTargetRole): WorksiteGuidanceTarget? {
        val world = Bukkit.getWorld(world) ?: return null
        return WorksiteGuidanceTarget(
            id, role, Location(world, x + 0.5, y + 0.35, z + 0.5), color(role),
            world.isChunkLoaded(x shr 4, z shr 4), if (role.value == "lost_miner") 1.45f else 1.1f,
        )
    }

    private fun actionKey(runtime: MineRuntime): String = if (runtime.state.phase == MinePhase.INCIDENT) {
        runtime.state.incident?.type?.name?.lowercase() ?: "incident"
    } else runtime.state.phase.name.lowercase()

    private fun progress(runtime: MineRuntime): Pair<Int, Int> = when (runtime.state.phase) {
        MinePhase.PROSPECTING -> runtime.state.prospected to runtime.rules().prospectingQuota
        MinePhase.MINING -> runtime.state.mined to runtime.rules().miningQuota
        MinePhase.LOADING -> runtime.state.loaded to runtime.rules().loadingQuota
        MinePhase.EXTRACTION -> runtime.state.routeIndex to routeTotal(runtime).coerceAtLeast(1)
        MinePhase.INCIDENT -> runtime.state.incident?.let { it.progress to it.required } ?: (0 to 1)
        MinePhase.IDLE, MinePhase.HAZARD, MinePhase.COOLDOWN -> 0 to 1
    }

    private fun progressVersion(runtime: MineRuntime): Long = listOf(
        runtime.state.sequence, runtime.state.phase.ordinal.toLong(), runtime.state.prospected.toLong(),
        runtime.state.mined.toLong(), runtime.state.loaded.toLong(), runtime.state.routeIndex.toLong(),
        runtime.state.incident?.progress?.toLong() ?: 0L, runtime.state.incidentCursor.toLong(),
    ).fold(17L) { hash, value -> hash * 31L + value }.and(Long.MAX_VALUE)

    private fun color(role: ObjectiveTargetRole): Color = COLORS[role.value] ?: run {
        val hash = role.value.hashCode().absoluteValue
        Color.fromRGB(80 + hash % 150, 80 + hash / 17 % 150, 80 + hash / 31 % 150)
    }

    private fun render(path: String, player: Player, values: Map<String, Component> = emptyMap()): Component =
        locale?.renderPath(path, player, values) ?: Component.text(path.substringAfterLast('.').replace('_', ' '))
    private fun text(value: Any): Component = locale?.text(value) ?: Component.text(value.toString())

    private companion object {
        val COLORS = mapOf(
            "prospect" to Color.fromRGB(69, 200, 245), "mineable" to Color.fromRGB(255, 200, 87),
            "ore_crate" to Color.fromRGB(255, 173, 66), "support_kit" to Color.fromRGB(85, 217, 139),
            "gas_vent" to Color.fromRGB(139, 211, 255), "crystal_node" to Color.fromRGB(189, 82, 214),
            "flood_pump" to Color.fromRGB(69, 150, 245), "lost_miner" to Color.fromRGB(255, 95, 109),
            "extraction" to Color.fromRGB(85, 217, 139),
        )
    }
}
