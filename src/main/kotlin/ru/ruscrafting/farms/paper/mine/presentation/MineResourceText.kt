package ru.ruscrafting.farms.paper.mine.presentation

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.domain.MineResource

internal object MineResourceText {
    fun name(locale: ArcFarmsLocale?, resource: String, player: Player): Component =
        locale?.renderPath("mine.resources.${MineResource.normalize(resource).lowercase()}", player)
            ?: Component.text(MineResource.displayName(resource))
}
