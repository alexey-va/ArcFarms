package ru.ruscrafting.farms.paper.fixtures

import org.bukkit.Location
import org.bukkit.block.data.BlockData
import ru.ruscrafting.farms.paper.platform.FarmBlastDebrisVisuals

/** Records only visual calls; deliberately cannot create or collide with server entities. */
internal class RecordingFarmRaidDebrisVisuals : FarmBlastDebrisVisuals {
    class Fragment(val block: BlockData, at: Location) : FarmBlastDebrisVisuals.Fragment {
        val positions = mutableListOf(at.clone())
        var removed = false
        override fun move(location: Location) { check(!removed); positions += location.clone() }
        override fun remove() { removed = true }
    }
    val fragments = mutableListOf<Fragment>()
    var cleanups = 0
    override fun spawn(location: Location, block: BlockData): Fragment =
        Fragment(block.clone(), location).also(fragments::add)
    override fun cleanup() { cleanups++ }
}
