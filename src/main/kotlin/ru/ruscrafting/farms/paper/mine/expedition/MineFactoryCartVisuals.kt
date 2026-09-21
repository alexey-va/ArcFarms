package ru.ruscrafting.farms.paper.mine.expedition

import org.bukkit.Location
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PaperPacketDisplays

/** Narrow packet presentation boundary; native leash and cargo ownership remain in the real owner. */
internal interface MineFactoryCartVisuals : AutoCloseable {
    interface Body {
        fun render(at: Location, yaw: Float, phase: Float)
        fun remove()
    }
    fun spawn(at: Location, parts: List<MineDisplayBlueprints.Part>): Body
}

internal class PacketMineFactoryCartVisuals(private val plugin: Plugin) : MineFactoryCartVisuals {
    private var renderer: PaperPacketDisplays? = null
    override fun spawn(at: Location, parts: List<MineDisplayBlueprints.Part>): MineFactoryCartVisuals.Body {
        val displays=mutableListOf<PacketBlockDisplay>()
        try {
            val renderer=renderer ?: PaperPacketDisplays(plugin).also { renderer=it }
            parts.forEach { part -> displays += renderer.spawnBlock(at,part.material.createBlockData()).apply {
                brightness=MineDisplayLighting.brightness(part.material)
                viewRange=2f;interpolationDuration=2;teleportDuration=2
            } }
        } catch(failure: Throwable) { displays.forEach(PacketBlockDisplay::remove);throw failure }
        return object : MineFactoryCartVisuals.Body {
            override fun render(at: Location, yaw: Float, phase: Float) {
                val rotation=Quaternionf().rotateY(-Math.toRadians(yaw.toDouble()).toFloat())
                displays.zip(parts).forEach { (display,part) ->
                    val turn=Quaternionf(rotation).mul(MineDisplayBlueprints.rotation(part,phase))
                    val center=rotation.transform(MineDisplayBlueprints.center(part,phase))
                    val corner=turn.transform(Vector3f(part.size).mul(-.5f)).add(center)
                    val pose=Transformation(corner,turn,Vector3f(part.size),Quaternionf())
                    if(display.transformation!=pose) { display.interpolationDelay=0;display.transformation=pose }
                    if(display.location!=at) display.teleport(at)
                }
            }
            override fun remove() = displays.forEach(PacketBlockDisplay::remove)
        }
    }
    override fun close() { renderer?.close();renderer=null }
}
