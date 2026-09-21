package ru.ruscrafting.farms.paper.mine.expedition

import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.plugin.Plugin
import kotlin.math.sin
import kotlin.math.PI
import org.bukkit.entity.Player
import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionState
import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionStep
import ru.ruscrafting.farms.domain.mine.expedition.MineFactoryExperiment
import ru.ruscrafting.farms.domain.mine.expedition.MineFactoryExperiments

/** Target sets and transient packet pieces for the three object-selection scenes. */
internal class MineFactoryExperimentVisualScenes(
    private val plugin: Plugin,
    private val targetFactory: MineFactoryExperimentTargets,
    private val visuals: MineFactoryExperimentVisuals,
    private val scopeOf: (MineExpeditionScene) -> String,
    private val sessions: MutableMap<String, MineFactoryExperimentSession>,
) {
    fun craneTargets(scene: MineExpeditionScene, session: MineFactoryExperimentSession): List<MineExpeditionMarkers.Target> {
        val control = targetFactory.target(scene, MineFactoryExperimentLayout.craneControl, Material.IRON_BLOCK, "crane-grab")
        val landing = targetFactory.target(
            scene,
            MineFactoryExperimentLayout.craneLanding,
            Material.YELLOW_TERRACOTTA,
            if (session.grabbed) "crane-drop" else "crane-aim",
            interactive = session.grabbed,
        )
        return if (session.grabbed) listOfNotNull(landing) else listOfNotNull(control, landing)
    }

    fun coolingTargets(scene: MineExpeditionScene, session: MineFactoryExperimentSession): List<MineExpeditionMarkers.Target> {
        val reel = targetFactory.target(
            scene,
            MineFactoryExperimentLayout.hoseReel,
            Material.COPPER_BLOCK,
            "cooling-pickup",
            interactive = false,
            glowing = false,
        )?.copy(label = Component.empty())
        val nozzle = targetFactory.target(
            scene,
            MineFactoryExperimentLayout.hoseNozzle,
            Material.CUT_COPPER,
            "cooling-pickup",
            interactive = !session.hoseEquipped,
            glowing = !session.hoseEquipped,
            model = if (session.hoseEquipped) "factory_hose_stand" else "factory_hose_nozzle",
        )
        val bearing = targetFactory.target(
            scene,
            MineFactoryExperimentLayout.hotBearing,
            Material.ORANGE_STAINED_GLASS,
            "cooling-progress",
            values = mapOf("percent" to Component.text((session.sprayMillis * 100 / COOLING_MILLIS).coerceIn(0L, 100L))),
        )
        return listOfNotNull(reel, if (session.hoseEquipped) nozzle?.copy(label = Component.empty()) else nozzle, bearing)
    }

    fun mouldTargets(
        scene: MineExpeditionScene,
        state: MineExpeditionState,
        session: MineFactoryExperimentSession,
    ): List<MineExpeditionMarkers.Target> {
        val plan = state.factoryExperiments ?: return emptyList()
        val scope = scopeOf(scene)
        val selected = session.mouldChoice
        val socket = targetFactory.target(scene, MineFactoryExperimentLayout.mouldSocket, Material.POLISHED_DEEPSLATE, "mould-fitting")
            ?.copy(label = if (selected >= 0) targetFactory.text("mould-fitting") else Component.empty(), glowing = false)
        if (selected < 0) installedPose(scene, plan.product)?.let { at ->
            visuals.render(scope, MOULD_SAMPLE_ID, at, mouldVisual(plan.product).replace("_piece", "_sample"),
                MineFactoryExperimentLayout.mould(plan.product).scale, -at.yaw, glowing = true)
        } else visuals.remove(scope, MOULD_SAMPLE_ID)
        val choices = (0..2).mapNotNull { index ->
            val fixture = MineFactoryExperimentLayout.mould(index)
            targetFactory.target(scene, fixture, Material.IRON_BLOCK, "mould-select",
                interactive = selected < 0,
                model = if (selected == index) "factory_mould_stand" else fixture.model,
                glowing = selected < 0 && index == plan.product,
            )?.let { if (selected < 0 && index == plan.product) it else it.copy(label = Component.empty()) }
        }
        return listOfNotNull(socket) + choices
    }

    fun renderInstalledMould(scene: MineExpeditionScene, state: MineExpeditionState, scope: String) {
        val plan = state.factoryExperiments ?: return
        if (MineFactoryExperiment.MOULD !in plan.resolved) return
        val socket = targetFactory.location(scene, MineFactoryExperimentLayout.mouldSocket) ?: return
        val at = installedPose(scene, plan.product) ?: return
        (0..2).forEach { visuals.remove(scope, mouldVisualId(it)) }
        visuals.remove(scope, MOULD_SAMPLE_ID)
        val fixture = MineFactoryExperimentLayout.mould(plan.product)
        visuals.render(scope, "mould-socket", socket, "factory_mould_socket", fixture.scale, -socket.yaw)
        visuals.render(scope, MOULD_INSTALLED_ID, at, mouldVisual(plan.product), fixture.scale, -at.yaw)
    }

    fun tickMould(
        scope: String,
        state: MineExpeditionState,
        players: Collection<Player>,
        now: Long,
        session: MineFactoryExperimentSession,
        complete: (Player, MineExpeditionStep) -> Boolean,
    ) {
        val choice = session.mouldChoice
        if (choice < 0 || session.mouldStartedAt < 0L) return
        val fixture = MineFactoryExperimentLayout.mould(choice)
        val from = targetFactory.location(session.scene, fixture) ?: return
        val sourceParts = MineFactoryExperimentModels.model(fixture.model).drop(4)
        val centerY = (sourceParts.minOf { it.center.y - it.size.y / 2 } + sourceParts.maxOf { it.center.y + it.size.y / 2 }) / 2
        from.add(0.0, (centerY * fixture.scale).toDouble(), 0.0)
        val socket = installedPose(session.scene, choice) ?: return
        val phase = MineFactoryExperimentMotion.phase(session.mouldStartedAt, now, MOULD_MILLIS)
        val at = from.clone().add(
            (socket.x - from.x) * phase,
            (socket.y - from.y) * phase + sin(phase * PI) * .35,
            (socket.z - from.z) * phase,
        )
        visuals.render(scope, mouldVisualId(choice), at, mouldVisual(choice), fixture.scale, -at.yaw, 0f, true)
        if (phase < 1.0) return
        val owner = session.owner?.let { id -> players.firstOrNull { it.uniqueId == id } } ?: return
        val step = MineFactoryExperiments.resolve(state, MineFactoryExperiment.MOULD, now)
        if (step.accepted && complete(owner, step)) {
            if (plugin.config.getBoolean("ui.sounds", true)) socket.world.playSound(socket, Sound.BLOCK_ANVIL_PLACE, .6f, 1.3f)
            renderInstalledMould(session.scene, step.state, scope)
            sessions.remove(scope)
        }
    }

    private fun installedPose(scene: MineExpeditionScene, product: Int): Location? {
        val fixture = MineFactoryExperimentLayout.mould(product)
        val at = targetFactory.location(scene, MineFactoryExperimentLayout.mouldSocket) ?: return null
        val bottom = MineFactoryExperimentVisualGeometry.parts(mouldVisual(product)).minOf { it.center.y - it.size.y / 2 }
        return at.add(0.0, ((.29f - bottom) * fixture.scale).toDouble(), 0.0)
    }

    fun resetMould(scope: String, session: MineFactoryExperimentSession) {
        session.mouldChoice = -1
        session.mouldStartedAt = -1L
        (0..2).forEach { visuals.remove(scope, mouldVisualId(it)) }
    }

    private fun mouldVisual(index: Int): String = when (index) {
        0 -> "mould_gear_piece"
        1 -> "mould_plate_piece"
        else -> "mould_rod_piece"
    }

    private fun mouldVisualId(index: Int): String = "mould-choice-$index"

    private companion object {
        const val COOLING_MILLIS = 2_500L
        const val MOULD_MILLIS = 650L
        const val MOULD_INSTALLED_ID = "mould-installed"
        const val MOULD_SAMPLE_ID = "mould-sample"
    }
}
