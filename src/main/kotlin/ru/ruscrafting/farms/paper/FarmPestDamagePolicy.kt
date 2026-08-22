package ru.ruscrafting.farms.paper

internal object FarmPestDamagePolicy {
    fun allows(
        hasAccess: Boolean,
        insideRegion: Boolean,
        pestIncidentActive: Boolean,
        sequenceMatches: Boolean,
    ): Boolean = hasAccess && insideRegion && pestIncidentActive && sequenceMatches
}
