package ru.ruscrafting.farms.paper.mine.expedition

import net.kyori.adventure.text.Component
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.*

/** Large readable controls; only their click hitboxes are server entities. */
internal class MineExpeditionMarkers(private val plugin: Plugin) {
    data class Target(val id: String, val location: Location, val material: Material, val label: Component,
        val block: Boolean = false, val model: String? = null, val modelScale: Float = 1f, val glowing: Boolean = true,
        val yaw: Int = 0, val editKey: String? = null,
        val editBase: ru.ruscrafting.farms.domain.mine.expedition.ExpeditionPoint? = null)
    private data class Marker(val displays: List<PacketBlockDisplay>, val hitbox: Interaction,
        val label: PacketTextDisplay, var target: Target, val parts: List<MineDisplayBlueprints.Part>, var signal: Material = Material.AIR)
    private val key = NamespacedKey(plugin, "mine_expedition_control")
    private val markers = linkedMapOf<String, Marker>()
    private var renderer: PaperPacketDisplays? = null
    private fun renderer() = renderer ?: PaperPacketDisplays(plugin).also { renderer = it }

    fun identity(entity: Entity): String? = entity.persistentDataContainer.get(key, PersistentDataType.STRING)
    fun editable(entity: Entity): Target? = identity(entity)?.let { markers[it]?.target }?.takeIf { it.editKey != null }
    private fun portal(t: Target) = t.id == "enter" || t.id.startsWith("return_")

    fun reconcile(scope: String, targets: List<Target>) {
        val expected = targets.mapTo(hashSetOf()) { "$scope/${it.id}" }
        markers.keys.filter { it.startsWith("$scope/") && it !in expected }.forEach(::remove)
        val deadline=System.nanoTime()+2_000_000L
        var spawned=0
        for (target in targets) {
            val id = "$scope/${target.id}"
            val old = markers[id]
            if (old == null || !old.hitbox.isValid || old.target.material != target.material ||
                old.target.block != target.block || old.target.model != target.model || old.target.modelScale != target.modelScale || old.target.yaw != target.yaw || old.target.location != target.location) {
                if(spawned>=2 || System.nanoTime()>deadline) continue
                remove(id); markers[id] = spawn(id, target);spawned++
            } else {
                if (old.target.label != target.label) old.label.text(target.label)
                if (old.target.glowing != target.glowing) old.displays.forEach { it.isGlowing = target.glowing }
                old.target = target
            }
        }
    }

    fun nearest(location: Location, scope: String): Target? = markers.entries.asSequence()
        .filter { it.key.startsWith("$scope/") && it.value.target.location.world === location.world }
        .map { it.value.target }.filter { it.location.distanceSquared(location) <= 9.0 }
        .minByOrNull { it.location.distanceSquared(location) }

    fun at(scope:String,id:String,x:Double=0.0,y:Double=0.0,z:Double=0.0):Location? {
        val target=markers["$scope/$id"]?.target ?: return null
        val offset=Quaternionf().rotateY(Math.toRadians(target.yaw.toDouble()).toFloat())
            .transform(Vector3f(x.toFloat(),y.toFloat(),z.toFloat()).mul(target.modelScale))
        return target.location.clone().add(offset.x.toDouble(),offset.y.toDouble(),offset.z.toDouble())
    }

    fun clear(scope: String) = markers.keys.filter { it.startsWith("$scope/") }.forEach(::remove)
    fun retainSites(ids: Set<Long>) {
        markers.keys.filter { key ->
            (key.startsWith("exit:") || key.startsWith("furnish:") || key.startsWith("result:")) &&
                key.substringBefore('/').substringAfter(':').toLongOrNull() !in ids
        }.forEach(::remove)
    }
    fun cleanup() { markers.keys.toList().forEach(::remove); renderer?.close(); renderer = null }
    fun onChunkLoad(chunk: org.bukkit.Chunk) {
        val owned = markers.values.mapTo(hashSetOf()) { it.hitbox.uniqueId }
        chunk.entities.filter { identity(it) != null && it.uniqueId !in owned }.forEach(Entity::remove)
    }

    fun rotate(scope: String, id: String, radians: Double) {
        val marker = markers["$scope/$id"] ?: return
        if (portal(marker.target) || marker.target.block) return
        if (marker.parts.isEmpty()) return
        positionParts(marker.displays,marker.parts,marker.target.yaw,radians.toFloat(),marker.target.modelScale,onlyMoving=true)
    }

    fun signal(scope: String, id: String, lit: Material) {
        val marker = markers["$scope/$id"] ?: return
        if (marker.signal == lit) return
        marker.signal = lit
        marker.parts.forEachIndexed { index, part ->
            if (part.center.y >= 3.3f && part.material in setOf(Material.LIME_CONCRETE, Material.YELLOW_CONCRETE, Material.RED_CONCRETE)) {
                val material = if (part.material == lit) lit else Material.GRAY_CONCRETE
                marker.displays[index].blockData = material.createBlockData()
                marker.displays[index].brightness = MineDisplayLighting.brightness(material, part.material == lit)
            }
        }
        val color = if (lit == Material.LIME_CONCRETE) Color.fromRGB(85, 217, 139) else Color.fromRGB(255, 187, 77)
        marker.displays.forEach { it.glowColorOverride = color }
    }

    private fun positionParts(displays: List<PacketBlockDisplay>, parts: List<MineDisplayBlueprints.Part>, yaw: Int, phase: Float, scale: Float, onlyMoving: Boolean = false) {
        val worldRotation=Quaternionf().rotateY(Math.toRadians(yaw.toDouble()).toFloat())
        displays.zip(parts).forEach { (display,part) ->
            if(onlyMoving && !part.moving) return@forEach
            val rotation=Quaternionf(worldRotation).mul(MineDisplayBlueprints.rotation(part,phase))
            val center=worldRotation.transform(MineDisplayBlueprints.center(part,phase).mul(scale))
            val corner=Vector3f(part.size).mul(-.5f*scale)
            rotation.transform(corner).add(center)
            val desired=Transformation(corner,rotation,Vector3f(part.size).mul(scale),Quaternionf())
            if (display.transformation != desired) {
                display.interpolationDelay=0
                display.transformation=desired
            }
        }
    }

    private fun spawn(id: String, target: Target): Marker {
        val visuals = mutableListOf<PacketBlockDisplay>()
        fun part(material: Material, x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float, glow: Boolean = target.glowing) {
            visuals += renderer().spawnBlock(target.location, material.createBlockData()).apply {
                transformation = Transformation(Vector3f(x,y,z), Quaternionf(), Vector3f(sx,sy,sz), Quaternionf())
                brightness = MineDisplayLighting.brightness(material); viewRange = 3f; isGlowing = glow
                glowColorOverride = Color.fromRGB(255,187,77); interpolationDuration = 2
            }
        }
        val blueprint=target.model?.let(MineDisplayBlueprints::model).orEmpty()
        when {
            blueprint.isNotEmpty() -> {
                blueprint.forEach { part(it.material,0f,0f,0f,1f,1f,1f) }
                positionParts(visuals,blueprint,target.yaw,0f,target.modelScale)
            }
            portal(target) -> {
                part(Material.POLISHED_DEEPSLATE, -1.35f,0f,-.25f,.35f,2.8f,.5f)
                part(Material.POLISHED_DEEPSLATE, 1f,0f,-.25f,.35f,2.8f,.5f)
                part(Material.CHISELED_COPPER,-1.35f,2.6f,-.25f,2.7f,.4f,.5f)
                part(Material.SEA_LANTERN,-1.05f,.15f,-.08f,2.1f,2.35f,.08f,false)
                part(Material.CYAN_STAINED_GLASS,-1.05f,.15f,-.18f,2.1f,2.35f,.08f)
            }
            target.block -> part(target.material,-.505f,-.005f,-.505f,1.01f,1.01f,1.01f)
            else -> {
                val material = if (target.material.isBlock) target.material else when(target.material) {
                    Material.COAL -> Material.COAL_BLOCK
                    Material.RAW_IRON -> Material.RAW_IRON_BLOCK
                    Material.IRON_INGOT -> Material.IRON_BLOCK
                    else -> Material.COPPER_BLOCK
                }
                part(material,-.5f,.1f,-.5f,1f,.8f,1f)
                if (target.id.contains("valve") || target.id.contains("weight") || target.id.contains("control")) {
                    part(Material.EXPOSED_COPPER,-.8f,1f,-.09f,1.6f,.18f,.18f)
                    part(Material.EXPOSED_COPPER,-.09f,1f,-.8f,.18f,.18f,1.6f)
                }
            }
        }
        val hitbox = target.location.world.spawn(target.location, Interaction::class.java) {
            it.interactionWidth = if (portal(target)) 2.7f else if (blueprint.isNotEmpty()) 2.8f*target.modelScale else 1.8f
            it.interactionHeight = if (portal(target)) 3f else if (blueprint.isNotEmpty()) 3.6f*target.modelScale else 2f
            it.isResponsive = true; it.isPersistent = false
            it.persistentDataContainer.set(key, PersistentDataType.STRING, id)
        }
        val labelHeight = when {
            portal(target) -> 3.25
            target.model == "finished_gear" -> 2.1
            target.model in setOf("crane_console", "furnace_console") -> 2.3
            blueprint.isNotEmpty() -> 4.0*target.modelScale
            else -> 2.0
        }
        val label = renderer().spawnText(target.location.clone().add(0.0, labelHeight, 0.0), target.label).apply {
            billboard = Display.Billboard.CENTER; brightness = Display.Brightness(15,15); viewRange = .65f
            backgroundColor = Color.fromARGB(100,12,18,24); isShadowed = true; isSeeThrough = true
            lineWidth = 180
            transformation = Transformation(Vector3f(), Quaternionf(), Vector3f(.75f), Quaternionf())
        }
        return Marker(visuals,hitbox,label,target,blueprint)
    }

    private fun remove(id: String) {
        val marker = markers.remove(id) ?: return
        marker.displays.forEach { it.remove() }; marker.hitbox.remove(); marker.label.remove()
    }
}
