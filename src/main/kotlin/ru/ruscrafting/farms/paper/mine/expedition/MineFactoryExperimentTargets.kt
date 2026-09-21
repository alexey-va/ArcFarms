package ru.ruscrafting.farms.paper.mine.expedition

import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import ru.ruscrafting.farms.config.ArcFarmsLocale
import org.bukkit.util.Vector

/** Pure target/anchor projection shared by the optional experiment owner. */
internal class MineFactoryExperimentTargets(
    private val locale: ArcFarmsLocale?,
    private val position: (MineExpeditionScene, String, Vector) -> Location?,
) {
    fun location(scene: MineExpeditionScene, fixture: MineFactoryExperimentLayout.Fixture): Location? =
        runCatching { position(scene, fixture.anchor, fixture.copyOffset()) }.getOrNull()

    fun target(
        scene: MineExpeditionScene,
        fixture: MineFactoryExperimentLayout.Fixture,
        material: Material,
        key: String,
        interactive: Boolean = fixture.interactive,
        glowing: Boolean = true,
        model: String? = fixture.model,
        values: Map<String, Component> = emptyMap(),
    ): MineExpeditionMarkers.Target? {
        val at = location(scene, fixture) ?: return null
        return MineExpeditionMarkers.Target(
            fixture.id,
            at,
            material,
            text(key, values = values),
            model = model,
            modelScale = fixture.scale,
            glowing = glowing,
            yaw = at.yaw.toInt() + fixture.yaw,
            interactive = interactive,
        )
    }

    fun text(key: String, player: Player? = null, values: Map<String, Component> = emptyMap()): Component =
        locale?.renderPath("mine.expedition.experiment.$key", player, values) ?: Component.text(key)
}
