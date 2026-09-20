package ru.ruscrafting.farms.paper.mine.incident

import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.index.MineBlockIndex

internal data class MineIncidentPlacementReport(
    val type: MineIncidentType,
    val required: Int,
    val usable: Int,
    val considered: Int,
    val rejected: Map<String, Int>,
) {
    fun technical(): String = "required=$required usable=$usable considered=$considered " +
        "rejected=${rejected.entries.joinToString(",", "{", "}") { "${it.key}=${it.value}" }}"
}

internal class MineIncidentPlacementDiagnostics(private val index: MineBlockIndex, private val stock: MineIncidentCandidateStock? = null) {
    fun report(runtime: MineRuntime, type: MineIncidentType, required: Int): MineIncidentPlacementReport {
        return stock?.report(runtime, type, required) ?: MineIncidentPlacementReport(type, required, 0, 0,
            mapOf("preparation_pending" to 1))
    }

    fun describe(runtime: MineRuntime, type: MineIncidentType, required: Int): String = report(runtime, type, required).technical()

}
