package ru.ruscrafting.farms.paper.mine.workshop

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Display
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PaperPacketDisplays

/** Fixed full-size workshop machinery, with a rotating two-stone crusher. */
internal class MineWorkshopMachines(private val plugin: Plugin) {
    data class Machine(val body: PacketBlockDisplay, val parts: List<PacketBlockDisplay>, val wheels: List<PacketBlockDisplay>) {
        fun remove() = parts.forEach { it.remove() }
        fun highlight(active: Boolean) = parts.forEach { it.isGlowing = active }
        fun turn(angle: Float) = wheels.forEachIndexed { i,d ->
            val rotation = Quaternionf().rotateY(angle + i * Math.PI.toFloat())
            d.transformation = Transformation(rotation.transform(Vector3f(-1.1f,.55f,-.3f)),rotation,Vector3f(2.2f,.65f,.6f),Quaternionf())
            d.interpolationDelay = 0
        }
    }
    private var renderer: PaperPacketDisplays? = null
    private fun renderer() = renderer ?: PaperPacketDisplays(plugin).also { renderer = it }
    fun close() { renderer?.close(); renderer = null }

    fun create(role: String, at: Location): Machine {
        val parts=mutableListOf<PacketBlockDisplay>(); val wheels=mutableListOf<PacketBlockDisplay>()
        fun block(material: Material,x:Float,y:Float,z:Float,sx:Float,sy:Float,sz:Float): PacketBlockDisplay =
            renderer().spawnBlock(at,material.createBlockData()).apply {
                transformation=Transformation(Vector3f(x,y,z),Quaternionf(),Vector3f(sx,sy,sz),Quaternionf())
                brightness=Display.Brightness(15,15);viewRange=2f;interpolationDuration=3
                glowColorOverride=org.bukkit.Color.fromRGB(255,183,65);parts+=this
            }
        val body=block(Material.POLISHED_ANDESITE,-1.15f,0f,-1.15f,2.3f,.3f,2.3f)
        when(role) {
            "crusher" -> {
                block(Material.SMOOTH_STONE,-1f,.3f,-1f,2f,.3f,2f)
                repeat(2) { wheels+=block(Material.POLISHED_DEEPSLATE,-1.1f,.55f,-.3f,2.2f,.65f,.6f) }
                block(Material.IRON_BLOCK,-.13f,.3f,-.13f,.26f,1.5f,.26f)
                block(Material.SPRUCE_PLANKS,-1.7f,1.45f,-.12f,3.4f,.18f,.24f)
            }
            "furnace" -> {
                block(Material.DEEPSLATE_BRICKS,-1f,.3f,-1f,2f,2.3f,1.8f)
                block(Material.ORANGE_STAINED_GLASS,-.6f,.5f,.8f,1.2f,.9f,.15f)
                block(Material.POLISHED_BLACKSTONE,-1.1f,1.45f,.7f,2.2f,.25f,.4f)
                block(Material.CUT_COPPER,-.55f,2.6f,-.7f,1.1f,1.4f,1.1f)
                block(Material.IRON_BARS,-.8f,.5f,.98f,1.6f,1f,.1f)
            }
            "shipping" -> {
                block(Material.SPRUCE_PLANKS,-1f,.3f,-1f,2f,.35f,2f)
                for(x in listOf(-.9f,.7f)) block(Material.IRON_BLOCK,x,.6f,-1f,.2f,.3f,2f)
                block(Material.RAIL,-.65f,.65f,-.9f,1.3f,.1f,1.8f)
                block(Material.CUT_COPPER,-.75f,.7f,-.65f,1.5f,.6f,1.3f)
            }
            else -> {
                block(Material.SPRUCE_PLANKS,-1f,.3f,-1f,2f,.3f,2f)
                val material=if(role=="ore") Material.RAW_IRON_BLOCK else Material.IRON_BLOCK
                for(x in listOf(-.8f,.05f)) for(z in listOf(-.8f,.05f)) block(material,x,.6f,z,.7f,.65f,.7f)
                block(Material.IRON_BLOCK,-1f,.55f,-1f,.12f,.8f,2f)
            }
        }
        return Machine(body,parts,wheels)
    }
}
