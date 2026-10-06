package ru.ruscrafting.farms.paper.platform

import org.bukkit.Location
import org.bukkit.block.data.BlockData
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.PaperPacketDisplays

/** One-purpose boundary for projectile-transparent raid debris, never a world entity. */
internal interface FarmBlastDebrisVisuals {
    interface Fragment {
        fun move(location: Location)
        fun remove()
    }
    fun spawn(location: Location, block: BlockData): Fragment
    fun cleanup()
}

internal class PacketFarmBlastDebrisVisuals(private val plugin: Plugin) : FarmBlastDebrisVisuals {
    private var renderer: PaperPacketDisplays? = null

    override fun spawn(location: Location, block: BlockData): FarmBlastDebrisVisuals.Fragment {
        val owner = renderer ?: PaperPacketDisplays(plugin, "farm-blast-debris").also { renderer = it }
        val display = owner.spawnBlock(location, block).apply {
            viewRange = 1.5f
            teleportDuration = 1
            transformation = Transformation(Vector3f(-0.35f), Quaternionf(), Vector3f(0.7f), Quaternionf())
        }
        return object : FarmBlastDebrisVisuals.Fragment {
            override fun move(location: Location) = display.teleport(location)
            override fun remove() = display.remove()
        }
    }

    override fun cleanup() {
        renderer?.close()
        renderer = null
    }
}
