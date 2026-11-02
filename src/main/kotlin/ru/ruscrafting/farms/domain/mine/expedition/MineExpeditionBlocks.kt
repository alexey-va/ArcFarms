package ru.ruscrafting.farms.domain.mine.expedition

import java.util.AbstractMap.SimpleImmutableEntry

/** Frozen voxel recipe. Runtime lookups allocate nothing; entry views are used only while preparing scenes. */
internal class MineExpeditionBlocks(
    private val bounds: ExpeditionBounds,
    source: Map<ExpeditionPoint, String>,
) : AbstractMap<ExpeditionPoint, String>() {
    private val height = Math.addExact(Math.subtractExact(bounds.max.y, bounds.min.y), 1)
    private val depth = Math.addExact(Math.subtractExact(bounds.max.z, bounds.min.z), 1)
    private val layer = Math.multiplyExact(height, depth)
    private val cells = arrayOfNulls<String>(Math.multiplyExact(
        Math.addExact(Math.subtractExact(bounds.max.x, bounds.min.x), 1), layer,
    ))
    private val order = IntArray(source.size)

    init {
        source.entries.forEachIndexed { ordinal, (point, material) ->
            require(bounds.contains(point)) { "Point $point is outside $bounds" }
            val index = index(point)
            cells[index] = material
            order[ordinal] = index
        }
    }

    override val size: Int get() = order.size

    override fun get(key: ExpeditionPoint): String? = if (bounds.contains(key)) cells[index(key)] else null

    override fun containsKey(key: ExpeditionPoint): Boolean = get(key) != null

    override val entries: Set<Map.Entry<ExpeditionPoint, String>> = object : AbstractSet<Map.Entry<ExpeditionPoint, String>>() {
        override val size: Int get() = order.size
        override fun iterator(): Iterator<Map.Entry<ExpeditionPoint, String>> = object : Iterator<Map.Entry<ExpeditionPoint, String>> {
            private var ordinal = 0
            override fun hasNext(): Boolean = ordinal < order.size
            override fun next(): Map.Entry<ExpeditionPoint, String> {
                if (!hasNext()) throw NoSuchElementException()
                val index = order[ordinal++]
                val point = ExpeditionPoint(bounds.min.x + index / layer,
                    bounds.min.y + index % layer / depth, bounds.min.z + index % depth)
                return SimpleImmutableEntry(point, requireNotNull(cells[index]))
            }
        }
    }

    private fun index(point: ExpeditionPoint): Int =
        (point.x - bounds.min.x) * layer + (point.y - bounds.min.y) * depth + point.z - bounds.min.z
}
