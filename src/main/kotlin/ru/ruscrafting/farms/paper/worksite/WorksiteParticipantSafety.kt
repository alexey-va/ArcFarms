package ru.ruscrafting.farms.paper.worksite

import org.bukkit.entity.Player

internal fun interface WorksiteParticipantOwner {
    fun releasePlayer(player: Player, reason: WorksitePlayerReleaseReason)
}

internal data class WorksiteReleaseReport(
    val removedItems: Int,
    val ownerFailures: List<Throwable> = emptyList(),
)

internal class WorksiteParticipantSafety(
    private val serviceItems: WorksiteServiceItemController,
    owners: Collection<WorksiteParticipantOwner>,
) {
    private val owners = owners.distinct()

    fun release(player: Player, reason: WorksitePlayerReleaseReason): WorksiteReleaseReport {
        val failures = mutableListOf<Throwable>()
        val removedItems = runCatching { serviceItems.cleanupPlayer(player, reason) }
            .getOrElse { failure ->
                failures += failure
                0
            }
        owners.forEach { owner ->
            runCatching { owner.releasePlayer(player, reason) }.exceptionOrNull()?.let(failures::add)
        }
        return WorksiteReleaseReport(removedItems, failures)
    }
}
