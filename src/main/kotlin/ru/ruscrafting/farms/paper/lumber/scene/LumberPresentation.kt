package ru.ruscrafting.farms.paper.lumber.scene

import org.bukkit.entity.Player
import ru.ruscrafting.farms.domain.lumber.LumberInput
import ru.ruscrafting.farms.domain.lumber.LumberVisualView

/** Read-only presentation. The worksite owns clocks, authority and every accepted transition. */
internal interface LumberPresentation {
    fun render(view: LumberVisualView)
    fun target(player: Player): LumberInput?
    fun spray(player: Player) = Unit
    fun release(player: Player)
    fun cleanup(reason: String)
}
