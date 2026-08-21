package ru.ruscrafting.farms.network

import ru.ruscrafting.farms.domain.ActivityKind
import java.util.UUID

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
}

object NoOpActivityNetworkGateway : ActivityNetworkGateway {
    override fun signal(signal: NetworkSignal, activity: ActivityKind, actorName: String?, excludedPlayers: Set<UUID>) = Unit
    override fun complete(activity: ActivityKind, actorName: String?, excludedPlayers: Set<UUID>) = Unit
    override fun workday(): WorkdayState? = null
}
