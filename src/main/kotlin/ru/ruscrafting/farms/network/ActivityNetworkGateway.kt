package ru.ruscrafting.farms.network

import ru.ruscrafting.farms.domain.ActivityKind
import java.util.UUID
import java.util.concurrent.CompletableFuture

interface ActivityNetworkGateway {
    fun signal(
        signal: NetworkSignal,
        activity: ActivityKind,
        actorName: String? = null,
        excludedPlayers: Set<UUID> = emptySet(),
    )

    fun complete(
        activity: ActivityKind,
        actorName: String? = null,
        excludedPlayers: Set<UUID> = emptySet(),
    )

    fun workday(): WorkdayState?

    fun createTravelTicket(
        playerId: UUID,
        activity: ActivityKind,
        destinationServer: String,
    ): CompletableFuture<Boolean>

    fun claimTravelTicket(playerId: UUID, currentServer: String): CompletableFuture<TravelTicket?>
}

object NoOpActivityNetworkGateway : ActivityNetworkGateway {
    override fun signal(signal: NetworkSignal, activity: ActivityKind, actorName: String?, excludedPlayers: Set<UUID>) = Unit
    override fun complete(activity: ActivityKind, actorName: String?, excludedPlayers: Set<UUID>) = Unit
    override fun workday(): WorkdayState? = null
    override fun createTravelTicket(playerId: UUID, activity: ActivityKind, destinationServer: String) =
        CompletableFuture.completedFuture(false)
    override fun claimTravelTicket(playerId: UUID, currentServer: String): CompletableFuture<TravelTicket?> =
        CompletableFuture.completedFuture(null)
}
