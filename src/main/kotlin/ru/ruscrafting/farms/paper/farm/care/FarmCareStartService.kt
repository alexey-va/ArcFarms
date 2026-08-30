package ru.ruscrafting.farms.paper.farm.care

import ru.ruscrafting.farms.domain.FarmCareType
import ru.ruscrafting.farms.domain.nextPlacementSequence
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import ru.ruscrafting.farms.paper.farm.care.mole.FarmMoleBurrowController
import java.util.logging.Level

/** Prepares and rolls back world-backed care resources around the state transition. */
internal class FarmCareStartService(
    private val port: WorksiteStatePort,
    private val moles: FarmMoleBurrowController,
) {
    fun prepare(runtime: FarmRuntime, plan: FarmCarePlan): Boolean {
        if (plan.type != FarmCareType.MOLES) return true
        val prepared = moles.prepare(runtime, plan.targets, runtime.state.nextPlacementSequence())
        if (!prepared) {
            port.log(
                Level.WARNING,
                "Could not start mole care atomically: zone=${runtime.settings.id} sequence=${runtime.state.sequence} " +
                    "reason=burrow_prepare_failed targets=${plan.targets.size}",
            )
        }
        return prepared
    }

    fun rollback(runtime: FarmRuntime, plan: FarmCarePlan) {
        if (plan.type == FarmCareType.MOLES) moles.discardPrepared(runtime)
    }
}
