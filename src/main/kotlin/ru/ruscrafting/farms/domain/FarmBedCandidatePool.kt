package ru.ruscrafting.farms.domain

/** Keeps the durable farm index authoritative while accepting newly discovered beds. */
object FarmBedCandidatePool {
    fun merge(
        indexed: Collection<FarmPlotPosition>,
        discovered: Collection<FarmPlotPosition>,
        isAvailable: (FarmPlotPosition) -> Boolean,
    ): Set<FarmPlotPosition> = sequenceOf(indexed.asSequence(), discovered.asSequence())
        .flatten()
        .distinct()
        .filter(isAvailable)
        .toCollection(linkedSetOf())
}
