package ru.ruscrafting.farms.domain

internal data class FarmGiantCropCandidateSelection(
    val candidate: FarmGiantCropCandidate?,
    val considered: Int,
    val checked: Int,
    val rejected: Map<String, Int>,
)

/**
 * Bounds expensive Paper placement checks while rotating crop families before
 * positions. This prevents a large contiguous wheat field from starving every
 * other supported crop and keeps successive incidents spatially varied.
 */
internal object FarmGiantCropCandidateSelector {
    fun select(
        candidates: Collection<FarmGiantCropCandidate>,
        sequence: Long,
        maxChecks: Int,
        issue: (FarmGiantCropCandidate) -> String?,
    ): FarmGiantCropCandidateSelection {
        require(maxChecks >= 1) { "Giant crop candidate check limit must be positive" }
        val grouped = candidates.filter { FarmGiantCropBlueprint.supports(it.crop) }
            .distinctBy(FarmGiantCropCandidate::block)
            .groupBy(FarmGiantCropCandidate::crop)
            .toSortedMap()
        val cropOrder = grouped.keys.toList().rotateSequentially(sequence + 173L)
        val queues = cropOrder.map { crop ->
            grouped.getValue(crop).sortedWith(CANDIDATE_ORDER).rotate(sequence * 31L + stableHash(crop))
        }
        val ordered = interleave(queues)
        val rejected = linkedMapOf<String, Int>()
        var checked = 0
        ordered.take(maxChecks).forEach { candidate ->
            checked++
            val problem = issue(candidate)
            if (problem == null) {
                return FarmGiantCropCandidateSelection(candidate, ordered.size, checked, rejected)
            }
            rejected[problem] = rejected.getOrDefault(problem, 0) + 1
        }
        return FarmGiantCropCandidateSelection(null, ordered.size, checked, rejected)
    }

    private fun <T> List<T>.rotate(salt: Long): List<T> {
        if (isEmpty()) return this
        val mixed = FarmSpatialSeed.mix(salt, 0x4749414e54L)
        val offset = java.lang.Math.floorMod((mixed xor (mixed ushr 32)).toInt(), size)
        return drop(offset) + take(offset)
    }

    private fun <T> List<T>.rotateSequentially(sequence: Long): List<T> {
        if (isEmpty()) return this
        val offset = java.lang.Math.floorMod(sequence, size.toLong()).toInt()
        return drop(offset) + take(offset)
    }

    private fun <T> interleave(groups: List<List<T>>): List<T> = buildList {
        val maxSize = groups.maxOfOrNull(List<T>::size) ?: 0
        repeat(maxSize) { index -> groups.forEach { group -> group.getOrNull(index)?.let(::add) } }
    }

    private fun stableHash(value: String): Long = value.fold(1_125_899_906_842_597L) { hash, character ->
        hash * 31L + character.code
    }

    private val CANDIDATE_ORDER = compareBy<FarmGiantCropCandidate> { it.block.x }
        .thenBy { it.block.z }
        .thenBy { it.block.y }
}
