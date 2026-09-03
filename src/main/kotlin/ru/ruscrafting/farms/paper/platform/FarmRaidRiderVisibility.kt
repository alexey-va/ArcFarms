package ru.ruscrafting.farms.paper.platform

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata
import net.kyori.adventure.util.TriState
import org.bukkit.Bukkit
import org.bukkit.entity.Ghast
import org.bukkit.entity.Player

internal fun interface FarmRaidRiderVisibility {
    fun setHidden(player: Player, ghast: Ghast, hidden: Boolean)
}

internal object PaperFarmRaidRiderVisibility : FarmRaidRiderVisibility {
    override fun setHidden(player: Player, ghast: Ghast, hidden: Boolean) {
        if (!Bukkit.getPluginManager().isPluginEnabled("packetevents") || !player.isOnline || !ghast.isValid) return
        val metadata = WrapperPlayServerEntityMetadata(
            ghast.entityId,
            listOf(EntityData(0, EntityDataTypes.BYTE, commonFlags(ghast, hidden))),
        )
        PacketEvents.getAPI().playerManager.sendPacket(player, metadata)
    }

    private fun commonFlags(ghast: Ghast, hidden: Boolean): Byte {
        var flags = 0
        if (ghast.fireTicks > 0 || ghast.visualFire == TriState.TRUE) flags = flags or 0x01
        if (ghast.isSneaking) flags = flags or 0x02
        if (ghast.isSwimming) flags = flags or 0x10
        if (hidden) flags = flags or 0x20
        if (!hidden && ghast.isGlowing) flags = flags or 0x40
        if (ghast.isGliding) flags = flags or 0x80
        return flags.toByte()
    }
}
