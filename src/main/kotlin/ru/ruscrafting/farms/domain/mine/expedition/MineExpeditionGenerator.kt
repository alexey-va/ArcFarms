package ru.ruscrafting.farms.domain.mine.expedition

/**
 * Reproducible scene plans. Version 1 is retained only for restoring old journals;
 * current plans include their enclosing rock and live beside the configured mine.
 */
object MineExpeditionGenerator {
    fun currentGeometryVersion(kind: MineExpeditionKind): Int =
        if (kind == MineExpeditionKind.LAST_DESCENT) 4 else 3

    @JvmOverloads
    fun plan(kind: MineExpeditionKind, seed: Long, geometryVersion: Int = currentGeometryVersion(kind)): MineExpeditionPlan {
        require(geometryVersion != 4 || kind == MineExpeditionKind.LAST_DESCENT) {
            "Geometry v4 is reserved for Last Descent"
        }
        return when (geometryVersion) {
            1 -> MineExpeditionLayout.build(kind, seed)
            2 -> MineCompactExpeditionLayout.build(kind, seed)
            3 -> MinePermanentExpeditionLayout.build(kind, seed)
            4 -> MineLastDescentLayout.build(seed)
            else -> error("Unsupported expedition geometry version: $geometryVersion")
        }
    }
}
