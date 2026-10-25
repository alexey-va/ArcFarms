package ru.ruscrafting.farms.paper.lumber.scene

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.PacketDisplay
import ru.arc.paper.display.PacketDisplayAudienceGroup
import ru.arc.paper.display.PacketItemDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.ruscrafting.farms.domain.lumber.LumberPhase
import ru.ruscrafting.farms.domain.lumber.LumberPose
import ru.ruscrafting.farms.domain.lumber.LumberVisualView
import ru.ruscrafting.farms.domain.lumber.LumberTiming
import ru.ruscrafting.farms.paper.lumber.sawmill.CedarSawmillDisplays
import ru.ruscrafting.farms.paper.worksite.WorksiteCarryable
import kotlin.math.cos
import kotlin.math.sin

/** Fifteen existing output parts retain shape while native passenger attachment follows the carrier. */
internal class LumberBoardCarry(private val displays: PaperPacketDisplays, private val audience: PacketDisplayAudienceGroup) {
    private val handles = mutableMapOf<String, PacketDisplay>()
    private var round = -1L

    fun render(view: LumberVisualView, world: World) {
        val player = view.state.boardsCarrier?.let(Bukkit::getPlayer)
        if (view.state.phase != LumberPhase.DELIVERING || player == null || player.world.uid != world.uid) { clear(); return }
        if (round != view.state.roundId) { clear(); round = view.state.roundId }
        val target = carriedPose(player)
        val attached = view.phaseAgeTicks >= LumberTiming.BOARD_PICKUP_TICKS
        val at = LumberSceneGeometry.blend(LumberSceneGeometry.outputPickup(), target,
            view.phaseAgeTicks.toDouble() / LumberTiming.BOARD_PICKUP_TICKS, .9)
        LumberSceneGeometry.boards(at).forEach { part ->
            val location = Location(world, part.center.x.toDouble(), part.center.y.toDouble(), part.center.z.toDouble())
            val handle = handles.getOrPut(part.id) {
                (if (part.itemModel != null) displays.spawnItem(location, CedarSawmillDisplays.item(part.itemModel))
                else displays.spawnBlock(location, Bukkit.createBlockData(requireNotNull(part.block)))).also {
                    it.audienceGroup = audience
                    it.isVisibleByDefault = true
                    it.interpolationDuration = 2
                    it.teleportDuration = 2
                    it.viewRange = 1.25f
                    if (it is PacketItemDisplay) it.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.NONE
                }
            }
            val translation = if (attached) Vector3f(part.center).sub(player.location.x.toFloat(),
                (player.location.y + player.height).toFloat(), player.location.z.toFloat()) else Vector3f()
            if (part.itemModel == null) translation.add(part.rotation.transform(Vector3f(part.size).mul(-.5f)))
            handle.transformation = Transformation(translation, Quaternionf(part.rotation), Vector3f(part.size), Quaternionf())
            if (attached) handle.attachTo(player) else { handle.detach(); handle.teleport(location) }
        }
    }

    fun clear() { handles.values.forEach(PacketDisplay::remove); handles.clear(); round = -1L }

    companion object {
        fun carriedPose(player: Player): LumberPose {
            val at = WorksiteCarryable.carriedLocation(player, .65, player.height)
            val yaw = Math.toRadians(player.location.yaw.toDouble())
            at.add(cos(yaw) * 1.15, 0.0, sin(yaw) * 1.15)
            return LumberPose(player.world.name, at.x, at.y, at.z, player.location.yaw + 180f)
        }
    }
}
