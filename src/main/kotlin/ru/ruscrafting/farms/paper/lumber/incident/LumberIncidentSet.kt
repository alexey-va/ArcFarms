package ru.ruscrafting.farms.paper.lumber.incident

import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import ru.ruscrafting.farms.paper.lumber.LumberRuntime
import ru.ruscrafting.farms.paper.lumber.incident.beetle.LumberBarkBeetleIncident
import ru.ruscrafting.farms.paper.lumber.incident.conveyor.LumberConveyorIncident
import ru.ruscrafting.farms.paper.lumber.incident.fire.LumberForestFireIncident
import ru.ruscrafting.farms.paper.lumber.incident.jam.LumberSawJamIncident
import ru.ruscrafting.farms.paper.lumber.incident.load.LumberLostLoadIncident
import ru.ruscrafting.farms.paper.lumber.incident.rush.LumberRushOrderIncident
import ru.ruscrafting.farms.paper.lumber.incident.warped.LumberWarpedBatchIncident
import ru.ruscrafting.farms.paper.lumber.incident.windthrow.LumberWindthrowIncident
import ru.ruscrafting.farms.paper.worksite.ServiceItemIdentity
import ru.ruscrafting.farms.paper.worksite.WorksitePlayerReleaseReason
import java.util.UUID

/** Owns the complete lumber-incident lifecycle exposed to the worksite module. */
internal class LumberIncidentSet(
    private val windthrow: LumberWindthrowIncident,
    private val beetles: LumberBarkBeetleIncident,
    private val sawJam: LumberSawJamIncident,
    private val conveyor: LumberConveyorIncident,
    private val lostLoad: LumberLostLoadIncident,
    private val fire: LumberForestFireIncident,
    private val rush: LumberRushOrderIncident,
    private val warped: LumberWarpedBatchIncident,
    private val scheduler: LumberIncidentScheduler,
) {
    fun tick(runtime: LumberRuntime, onlineParticipants: Int, now: Long) {
        beetles.reconcile(runtime)
        lostLoad.reconcile(runtime)
        fire.tick(runtime, onlineParticipants, now)
        rush.tick(runtime, now)
        scheduler.tick(runtime, now, onlineParticipants)
    }

    fun onBreak(event: BlockBreakEvent): Boolean = windthrow.onBreak(event)

    fun onInteract(event: PlayerInteractEvent, clicked: Block, player: Player): Boolean =
        fire.onInteract(event, clicked, player) || conveyor.onInteract(event, clicked, player) ||
            warped.onInteract(event, clicked, player) || beetles.onInteract(event, clicked, player) ||
            sawJam.onInteract(event, clicked, player)

    fun onInteractEntity(event: PlayerInteractEntityEvent): Boolean = lostLoad.onInteractEntity(event)

    fun onMove(to: Location, player: Player): Boolean = lostLoad.onMove(to, player)

    fun updateVisuals() = lostLoad.updateCarried()

    fun releasePlayer(playerId: UUID) = lostLoad.releasePlayer(playerId)

    fun isActive(identity: ServiceItemIdentity): Boolean = conveyor.isActive(identity) || fire.isActive(identity)

    fun release(playerId: UUID, identity: ServiceItemIdentity, reason: WorksitePlayerReleaseReason) {
        conveyor.release(playerId, identity, reason)
        fire.release(playerId, identity, reason)
    }

    fun cleanup() {
        lostLoad.cleanup()
        scheduler.cleanup()
    }
}
