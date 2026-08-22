package ru.ruscrafting.farms.domain

/**
 * Tracks ownership of temporary water blocks so several bucket pours may flow
 * at the same time without one cleanup deleting another flow.
 */
class FarmWaterFlowTracker {
    private val positionsByFlow = linkedMapOf<Long, MutableSet<FarmPlotPosition>>()
    private val flowsByPosition = linkedMapOf<FarmPlotPosition, MutableSet<Long>>()

    fun start(flowId: Long, source: FarmPlotPosition) {
        require(flowId > 0) { "Farm water flow id must be positive" }
        require(flowId !in positionsByFlow) { "Farm water flow $flowId already exists" }
        positionsByFlow[flowId] = linkedSetOf(source)
        flowsByPosition.getOrPut(source, ::linkedSetOf) += flowId
    }

    fun propagate(from: FarmPlotPosition, to: FarmPlotPosition): Set<Long> {
        val owners = flowsByPosition[from].orEmpty().toSet()
        owners.forEach { flowId ->
            positionsByFlow.getValue(flowId) += to
            flowsByPosition.getOrPut(to, ::linkedSetOf) += flowId
        }
        return owners
    }

    fun observe(flowId: Long, position: FarmPlotPosition): Boolean {
        val positions = positionsByFlow[flowId] ?: return false
        positions += position
        flowsByPosition.getOrPut(position, ::linkedSetOf) += flowId
        return true
    }

    fun positions(flowId: Long): Set<FarmPlotPosition> = positionsByFlow[flowId].orEmpty().toSet()

    fun owners(position: FarmPlotPosition): Set<Long> = flowsByPosition[position].orEmpty().toSet()

    fun activeInWorld(world: String): Boolean = positionsByFlow.values.any { positions ->
        positions.any { it.world == world }
    }

    /** Returns temporary blocks no longer owned by any active flow. */
    fun finish(flowId: Long): Set<FarmPlotPosition> {
        val positions = positionsByFlow.remove(flowId).orEmpty()
        val abandoned = linkedSetOf<FarmPlotPosition>()
        positions.forEach { position ->
            val owners = flowsByPosition[position] ?: return@forEach
            owners.remove(flowId)
            if (owners.isEmpty()) {
                flowsByPosition.remove(position)
                abandoned += position
            }
        }
        return abandoned
    }

    fun clear(): Set<FarmPlotPosition> {
        val positions = flowsByPosition.keys.toSet()
        positionsByFlow.clear()
        flowsByPosition.clear()
        return positions
    }

    fun isActive(flowId: Long): Boolean = flowId in positionsByFlow

    fun isEmpty(): Boolean = positionsByFlow.isEmpty()
}
