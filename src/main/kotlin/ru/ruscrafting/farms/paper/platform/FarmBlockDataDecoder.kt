package ru.ruscrafting.farms.paper.platform

import org.bukkit.Bukkit
import org.bukkit.block.data.BlockData

/** Decodes one journalled Paper BlockData string without gameplay fallback. */
internal fun interface FarmBlockDataDecoder {
    fun decode(serialized: String): BlockData
}

internal object PaperFarmBlockDataDecoder : FarmBlockDataDecoder {
    override fun decode(serialized: String): BlockData = Bukkit.createBlockData(serialized)
}
