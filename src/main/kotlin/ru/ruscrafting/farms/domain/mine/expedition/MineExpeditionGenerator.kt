package ru.ruscrafting.farms.domain.mine.expedition

/**
 * Pure entry point for reproducible mine scenes. The returned sparse block map
 * is directly serializable as a coordinate/block-data list; absent cells are
 * intentionally left as the world's natural solid stone.
 */
object MineExpeditionGenerator {
    fun plan(kind: MineExpeditionKind, seed: Long): MineExpeditionPlan =
        MineExpeditionLayout.build(kind, seed)
}
