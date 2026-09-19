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
import ru.ruscrafting.farms.paper.worksite.WorksiteCooldownTimer
import java.util.UUID
import kotlin.math.absoluteValue

internal class MineGuidanceSource(
    private val registry: MineRuntimeRegistry,
    private val audience: WorksiteAudiencePort,
    private val locale: ArcFarmsLocale? = null,
    private val routeTarget: (MineRuntime) -> WorksitePosition? = { null },
    private val routeTotal: (MineRuntime) -> Int = { 1 },
    private val clock: () -> Long = System::currentTimeMillis,
    private val workingHint: (MineRuntime, Player, Long) -> Component? = { _, _, _ -> null },
    private val workingTargets: (MineRuntime, Player) -> List<WorksiteGuidanceTarget> = { _, _ -> emptyList() },
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
        val (done, total) = progress(runtime)
        val action = actionKey(runtime)
        val resource = resourceSummary(runtime, player)
        val cooldownValues = if (runtime.state.phase == MinePhase.COOLDOWN) {
            mapOf("seconds" to text(WorksiteCooldownTimer.remainingSeconds(runtime.state.cooldownEndsAt, clock())))
        } else {
            emptyMap()
        }
        val resourceValues = mapOf("resource" to resource)
        val hint = workingHint(runtime, player, clock())
        val values = resourceValues + cooldownValues + mapOf(
            "done" to text(done), "total" to text(total),
            "action" to (hint ?: render("mine.guidance.$action", player, resourceValues + cooldownValues)),
        )
        return WorksiteGuidanceView(
            "mine:${runtime.settings.id}", progressVersion(runtime),
            render("mine.guidance.title", player, values),
            hint ?: render("mine.guidance.$action", player, values),
            render("mine.guidance.bar", player, values),
            barProgress(runtime, done, total),
            when (runtime.state.phase) {
                MinePhase.INCIDENT -> BossBar.Color.RED
                MinePhase.COOLDOWN -> BossBar.Color.YELLOW
                else -> BossBar.Color.BLUE
            },
            targets(player, runtime),
            quietProgress = runtime.settings.miningOnly,
            screenTitles = runtime.state.phase != MinePhase.INCIDENT,
            sidebarRows = listOf(
                render("route.mine.${runtime.settings.id}", player),
                hint ?: render("mine.guidance.$action", player, values),
                render("scoreboard.progress", player, values),
            ) + resourceRows(runtime, player),
        )
    }

    private fun resourceSummary(runtime: MineRuntime, player: Player): Component {
        val resources = runtime.currentOrder()?.requestedResources.orEmpty()
        return resources.map { resourceName(it, player) }.foldIndexed(Component.empty()) { index, result, resource ->
            result.append(if (index == 0) Component.empty() else Component.text(" · ")).append(resource)
        }
    }

    private fun resourceRows(runtime: MineRuntime, player: Player): List<Component> {
        val requirements = runtime.currentOrder()?.normalizedRequirements.orEmpty()
        if (requirements.isEmpty() || runtime.state.phase != MinePhase.MINING) return emptyList()
        return requirements.map { (resource, required) ->
            render("mine.guidance.resource-progress", player, mapOf(
                "resource" to resourceName(resource, player),
                "done" to text((runtime.state.minedByMaterial[resource] ?: 0).coerceAtMost(required)),
                "total" to text(required),
            ))
        }
    }

    private fun resourceName(resource: String, player: Player): Component =
        MineResourceText.name(locale, resource, player)

    private fun targets(player: Player, runtime: MineRuntime): List<WorksiteGuidanceTarget> {
        if (runtime.state.incident?.working != null) return workingTargets(runtime, player)
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

    private fun runtimeFor(player: Player): MineRuntime? = registry.forAudience(player.location)

    private fun WorksitePosition.guidance(id: String, role: ObjectiveTargetRole): WorksiteGuidanceTarget? {
        val world = Bukkit.getWorld(world) ?: return null
        return WorksiteGuidanceTarget(
            id, role, Location(world, x + 0.5, y + 0.35, z + 0.5), color(role),
            world.isChunkLoaded(x shr 4, z shr 4),
            particleSize = if (role.value == "lost_miner") 1.9f else 1.65f,
            columnParticles = 18,
            columnStep = 0.45,
        )
    }

    private fun actionKey(runtime: MineRuntime): String = if (runtime.state.phase == MinePhase.INCIDENT) {
        runtime.state.incident?.type?.name?.lowercase() ?: "incident"
    } else if (runtime.settings.miningOnly && runtime.state.phase == MinePhase.EXTRACTION) "completion_pending"
    else if (runtime.settings.miningOnly && runtime.state.phase == MinePhase.MINING &&
        runtime.currentOrder()?.normalizedRequirements?.isNotEmpty() == true) "multi_resource_order"
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

    private fun barProgress(runtime: MineRuntime, done: Int, total: Int): Float =
        if (runtime.state.phase == MinePhase.COOLDOWN) {
            WorksiteCooldownTimer.progress(runtime.state.cooldownEndsAt, clock(), runtime.cooldownMillis)
        } else {
            done.toFloat() / total.coerceAtLeast(1)
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
        val COLORS = mapOf(
            "prospect" to Color.fromRGB(69, 200, 245), "mineable" to Color.fromRGB(255, 200, 87),
            "ore_crate" to Color.fromRGB(255, 173, 66), "support_kit" to Color.fromRGB(85, 217, 139),
            "gas_vent" to Color.fromRGB(139, 211, 255), "crystal_node" to Color.fromRGB(189, 82, 214),
            "flood_pump" to Color.fromRGB(69, 150, 245), "lost_miner" to Color.fromRGB(255, 95, 109),
            "power_switch" to Color.fromRGB(255, 214, 72), "creature_nest" to Color.fromRGB(255, 90, 55),
            "creature" to Color.fromRGB(255, 135, 60),
            "extraction" to Color.fromRGB(85, 217, 139),
        )
    }
}
