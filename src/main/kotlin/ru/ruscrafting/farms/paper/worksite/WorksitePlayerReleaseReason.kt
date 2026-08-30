package ru.ruscrafting.farms.paper.worksite

internal enum class WorksitePlayerReleaseReason {
    QUIT,
    DEATH,
    ZONE_EXIT,
    TELEPORT_OUT,
    PORTAL_OUT,
    BACKEND_TRANSFER,
    RELOAD,
    SHUTDOWN,
    JOIN_STALE,
    OBJECTIVE_REPLACED,
}
