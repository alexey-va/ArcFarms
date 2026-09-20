package ru.ruscrafting.farms.domain.mine.expedition

/**
 * Reproducible scene plans. Version 1 is retained only for restoring old journals;
 * current plans include their enclosing rock and live beside the configured mine.
 */
object MineExpeditionGenerator {
    @JvmOverloads
    fun plan(kind: MineExpeditionKind, seed: Long, geometryVersion: Int = MineExpeditionPlacement.CURRENT_GEOMETRY_VERSION): MineExpeditionPlan =
        if (geometryVersion == 1) MineExpeditionLayout.build(kind, seed) else MineCompactExpeditionLayout.build(kind, seed)
}
