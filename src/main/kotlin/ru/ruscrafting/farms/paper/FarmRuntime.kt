package ru.ruscrafting.farms.paper

import ru.ruscrafting.farms.config.FarmZoneSettings
import ru.ruscrafting.farms.domain.FarmOrder
import ru.ruscrafting.farms.domain.FarmRules
import ru.ruscrafting.farms.domain.FarmShiftState

/** Mutable runtime aggregate for one independently configured farm zone. */
internal data class FarmRuntime(
    var settings: FarmZoneSettings,
    var region: ActivityRegion,
    var orders: Map<String, FarmOrder>,
    var orderList: List<FarmOrder>,
    var rules: FarmRules,
    var state: FarmShiftState,
)

/** Canonical block-index bounds for this farm; shared by lifecycle and admin recovery. */
internal fun FarmRuntime.blockIndexDefinition(): FarmBlockIndexDefinition = FarmBlockIndexDefinition(
    zoneId = settings.id,
    region = region,
    crops = settings.crops,
    blocksPerTick = settings.blockReindexBlocksPerTick,
    maxBlocks = settings.blockReindexMaxBlocks,
    maxOrchardLeaves = settings.appleLeafIndexLimit,
    cropLayout = settings.cropLayout,
)
