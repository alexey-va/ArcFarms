package ru.ruscrafting.farms.paper

import ru.ruscrafting.farms.config.FarmZoneSettings
import ru.ruscrafting.farms.domain.FarmOrder
import ru.ruscrafting.farms.domain.FarmRules
import ru.ruscrafting.farms.domain.FarmShiftState

/** Mutable runtime aggregate for one independently configured farm zone. */
internal data class FarmRuntime(
    val settings: FarmZoneSettings,
    val region: ActivityRegion,
    val orders: Map<String, FarmOrder>,
    val orderList: List<FarmOrder>,
    val rules: FarmRules,
    var state: FarmShiftState,
)
