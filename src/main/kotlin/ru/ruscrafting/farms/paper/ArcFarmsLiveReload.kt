package ru.ruscrafting.farms.paper

internal class ArcFarmsAmbiguousLiveReloadException(cause: Throwable) : IllegalStateException(
    "ArcFarms could not confirm the live-reload state write; the plugin must stop until the pending write is resolved",
    cause,
)
