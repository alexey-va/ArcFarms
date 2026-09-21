package ru.ruscrafting.farms.paper.mine.expedition

import org.bukkit.Location
import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionKind
import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionPlan

/**
 * Scene-local entry and return geometry. Travel still owns the durable lease;
 * this policy only selects the semantic return stations and gives the factory
 * an indoor arrival view.
 */
internal object MineExpeditionPortalPolicy {
    fun returnStationIds(plan: MineExpeditionPlan): List<String> = when (plan.kind) {
        // Dead Factory has one authored doorway. Keep the old station name as
        // the semantic return identity even when a legacy plan still contains
        // a second, distinct exit station.
        MineExpeditionKind.DEAD_FACTORY -> listOf("entry")
        else -> listOf("entry", "exit").distinctBy { plan.stations.getValue(it) }
    }

    fun arrival(kind: MineExpeditionKind, entry: Location): Location =
        entry.clone().apply {
            if (kind == MineExpeditionKind.DEAD_FACTORY) {
                // The factory portal is on the south wall; put the player in the
                // aisle and look along its -Z axis instead of into the frame.
                z -= 3.0
                yaw = 180f
                pitch = 0f
            }
        }
}
