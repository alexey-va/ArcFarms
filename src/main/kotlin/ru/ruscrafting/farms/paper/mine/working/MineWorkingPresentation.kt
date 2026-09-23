package ru.ruscrafting.farms.paper.mine.working

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.FarmCareVisualSettings
import ru.ruscrafting.farms.config.FarmItemDisplayTransform
import ru.ruscrafting.farms.domain.MineWorkingEngine
import ru.ruscrafting.farms.domain.MineWorkingStage
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.extraction.MineCartEffects
import ru.ruscrafting.farms.paper.platform.FarmTextDisplayRenderer
import ru.ruscrafting.farms.paper.worksite.WorksiteEntryMarker
import java.util.UUID

internal data class MineWorkingTarget(val id: String, val position: WorksitePosition, val label: String)

/** Farm marker grammar and the existing cart renderer, attached to the current physical stage. */
internal class MineWorkingPresentation(
    plugin: Plugin,
    private val locale: ArcFarmsLocale?,
    textDisplays: FarmTextDisplayRenderer,
    private val cart: MineCartEffects,
    private val floorNumber: (String) -> Int? = { null },
    private val drill: MineDrillScene = MineDrillScene(plugin),
) {
    private data class Marker(val target: MineWorkingTarget, val entities: List<Entity>)
    private val highlights = MineWorkingBlockHighlights(plugin)
    private val marker = locale?.let { WorksiteEntryMarker(it, textDisplays) }
    private val tag = NamespacedKey(plugin, "mine_working_marker")
    private val markers = mutableMapOf<String, MutableMap<String, Marker>>()
    private val returnMarkers=ru.ruscrafting.farms.paper.mine.expedition.MineExpeditionMarkers(plugin, "mine_working_return")
    private val returnTargets=mutableMapOf<String,MineWorkingTarget>()
    private val interactions = mutableMapOf<UUID, Pair<String, MineWorkingTarget>>()

    fun target(entity: Entity): Pair<String, MineWorkingTarget>? = returnMarkers.identity(entity)?.let { identity ->
        val zone=identity.substringBefore('/').removePrefix("working-return:")
        returnTargets[zone]?.let { zone to it }
    } ?: interactions[entity.uniqueId]
        ?: drill.target(entity)?.let { (zone, position) -> zone to MineWorkingTarget("drill", position, "excavate") }

    fun drillPosition(runtime: MineRuntime, scene: MineWorkingScene): WorksitePosition? =
        scene.plan.excavation.indices.firstOrNull { it !in runtime.state.incident?.working?.completed.orEmpty() }
            ?.let(scene.plan.excavation::get)

    fun animateDrill(runtime: MineRuntime, scene: MineWorkingScene, running: Boolean, now: Long) = drill.reconcile(runtime, scene, running, now)

    fun reconcile(runtime: MineRuntime, scene: MineWorkingScene) {
        val working = runtime.state.incident?.working ?: return
        highlights.reconcile(runtime, scene)
        reconcileReturn(runtime,scene,false)
        val drive = MineDriveLayout.machine(scene.plan.type, working.placement)
        val targets = targets(runtime, scene).filter { it.label !in setOf("excavate", "clear_track") } +
            if (drive) emptyList() else listOf(MineWorkingTarget("entry", scene.plan.entrance.copy(y = scene.floor + 1), "entry"))
        val current = markers.getOrPut(runtime.settings.id) { linkedMapOf() }
        current.keys.toList().forEach { id ->
            val existing = current.getValue(id)
            if (targets.none { it == existing.target } || existing.entities.any { !it.isValid }) {
                existing.entities.forEach { interactions.remove(it.uniqueId); it.remove() }
                current.remove(id)
            }
        }
        targets.forEach { target ->
            if (target.id in current) return@forEach
            val entities = marker?.spawn(
                target.position.location(runtime.region.world), MARKER_VISUAL,
                if (target.label in STATIONS) "mine.working.marker.${target.label}" else "mine.working.stage.${target.label}",
                glowing = true, viewRange = 1.5f,
                mark = { entity ->
                    entity.persistentDataContainer.set(tag, PersistentDataType.STRING, runtime.settings.id)
                    if (entity is Interaction) interactions[entity.uniqueId] = runtime.settings.id to target
                },
                values = mapOf("floor" to Component.text(working.placement.floorId),
                    "batch" to Component.text(working.batch + 1), "batches" to Component.text(MineWorkingEngine.BATCHES)),
            ).orEmpty()
            current[target.id] = Marker(target, entities)
        }
        if (working.stage == MineWorkingStage.TEST_TRACK) {
            scene.plan.cartRoute.getOrNull(working.completed.size.coerceAtMost(scene.plan.cartRoute.lastIndex))?.let {
                cart.show(runtime, it.copy(y = it.y - 1), working.placement.direction * 90f)
            }
        } else cart.hide(runtime.settings.id)
    }

    fun targets(runtime: MineRuntime, scene: MineWorkingScene): List<MineWorkingTarget> {
        val working = runtime.state.incident?.working ?: return emptyList()
        val plan = scene.plan
        fun blocks(values: List<WorksitePosition>, label: String, one: Boolean = false): List<MineWorkingTarget> =
            values.mapIndexedNotNull { index, position ->
                if (index in working.completed) null else MineWorkingTarget(index.toString(), position, label)
            }.let { if (one) it.take(1) else it.take(9) }
        fun station(id: String) = plan.stations[id]?.let { listOf(MineWorkingTarget(id, it, id)) }.orEmpty()
        if (MineDriveLayout.machine(scene.plan.type, working.placement)) {
            val rail = MineDriveLayout.rail(scene.plan.type, working.placement)
            return listOf(MineWorkingTarget("drive-goal", working.placement.position(0,1,MineDriveLayout.length(scene.plan.type, working.placement) - 3), if(rail) "rail_goal" else "drive_goal"))
        }
        return when (working.stage) {
            MineWorkingStage.EXCAVATE -> blocks(plan.excavation, "excavate", one = true)
            MineWorkingStage.SUPPORT -> blocks(plan.supports, "support")
            MineWorkingStage.CLEAR_TRACK -> blocks(plan.rubble, "clear_track")
            MineWorkingStage.LAY_TRACK -> blocks(plan.rails, "lay_track", one = true)
            MineWorkingStage.TEST_TRACK -> blocks(plan.cartRoute, "test_track", one = true)
            MineWorkingStage.LOAD -> station("ore") + station("crusher")
            MineWorkingStage.CRUSH -> station("crusher")
            MineWorkingStage.HEAT -> station("furnace")
            MineWorkingStage.SHIP -> station("output") + station("shipping")
        }
    }

    fun text(path: String, player: Player? = null): Component =
        locale?.renderPath("mine.working.$path", player) ?: Component.empty()

    fun feedback(player: Player, path: String, values: Map<String, Component> = emptyMap()) {
        player.sendActionBar(locale?.renderPath("mine.working.$path", player, values) ?: Component.empty())
    }

    fun stageHint(runtime: MineRuntime, player: Player, now: Long): Component? {
        val working = runtime.state.incident?.working ?: return null
        val readyToQuench = MineWorkingEngine.canQuench(working, now)
        val path = if (working.stage == MineWorkingStage.HEAT) {
            if (readyToQuench) "heat-ready" else "heat-wait"
        } else if (runtime.state.incident?.type?.let { MineDriveLayout.machine(it,working.placement) } == true) {
            val rail=working.drive?.rail
            if(rail?.service != null) if(rail.service.kind==ru.ruscrafting.farms.domain.MineRailServiceKind.JAM) "rail-jammed" else "rail-empty"
            else if(runtime.state.incident?.type==ru.ruscrafting.farms.domain.MineIncidentType.RAIL_EXTENSION) "rail-controls" else "drive-controls"
        }
        else "hint.${working.stage.name.lowercase()}"
        val deadline = working.heatStartedAt + MineWorkingEngine.HEAT_MILLIS +
            if (readyToQuench) MineWorkingEngine.HEAT_WINDOW_MILLIS else 0L
        return locale?.renderPath("mine.working.$path", player, mapOf(
            "seconds" to Component.text(((deadline - now).coerceAtLeast(0) + 999) / 1000),
            "batch" to Component.text(working.batch + 1), "batches" to Component.text(MineWorkingEngine.BATCHES),
            "floor" to Component.text(working.placement.floorId),
        ))
    }

    fun entryHint(runtime: MineRuntime, player: Player): Component? {
        val working = runtime.state.incident?.working ?: return null
        val id = working.placement.floorId
        val floor = floorNumber(id)?.let { number ->
            locale?.renderPath("mine-lift.floors.$id", player, mapOf("number" to Component.text(number)))
        } ?: Component.text("Y=${working.placement.entrance.y + 1}")
        return locale?.renderPath("mine.working.entry", player, mapOf("floor" to floor))
    }

    fun reconcileReturn(runtime: MineRuntime, scene: MineWorkingScene, completed: Boolean) {
        if(!MineDriveLayout.machine(scene.plan.type,scene.plan.placement)) return
        val target=MineWorkingTarget("return-lift",MineDriveLayout.returnPoint(scene.plan.placement),"return-lift")
        returnTargets[runtime.settings.id]=target
        returnMarkers.reconcile("working-return:${runtime.settings.id}",listOf(
            ru.ruscrafting.farms.paper.mine.expedition.MineExpeditionMarkers.Target(target.id,target.position.location(runtime.region.world),
                org.bukkit.Material.CUT_COPPER,locale?.renderPath("mine.working.return-lift") ?: Component.text("return-lift"),
                model="return_miner",yaw=Math.floorMod(180-scene.plan.placement.direction*90,360),glowing=completed)))
    }
    fun cleanup(zoneId: String) {
        returnTargets.remove(zoneId)
        returnMarkers.clear("working-return:$zoneId")
        markers.remove(zoneId)?.values?.flatMap { it.entities }?.forEach { interactions.remove(it.uniqueId); it.remove() }
        cart.hide(zoneId)
        drill.cleanup(zoneId)
        highlights.cleanup(zoneId)
    }

    fun close() { returnMarkers.cleanup();returnTargets.clear() }

    fun reconcileLoaded() {
        drill.reconcileLoaded()
        highlights.reconcileLoaded()
        Bukkit.getWorlds().forEach { world -> world.loadedChunks.forEach { chunk ->
            chunk.entities.filter { it.persistentDataContainer.has(tag, PersistentDataType.STRING) }.forEach(Entity::remove)
        } }
        markers.clear(); interactions.clear();returnMarkers.cleanup();returnTargets.clear()
    }

    private companion object {
        val MARKER_VISUAL = FarmCareVisualSettings("AIR", 0, FarmItemDisplayTransform.FIXED, 1f, 0.0)
        val STATIONS = setOf("entry", "ore", "crusher", "furnace", "output", "shipping")
    }
}
