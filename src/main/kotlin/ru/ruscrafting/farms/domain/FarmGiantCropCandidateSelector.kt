package ru.ruscrafting.farms.domain

internal data class FarmGiantCropCandidateSelection(
    val candidate: FarmGiantCropCandidate?,
    val considered: Int,
    val checked: Int,
    val rejected: Map<String, Int>,
)

/** Bounds expensive Paper placement checks while preserving deterministic rotation. */
internal object FarmGiantCropCandidateSelector {
    fun select(
        candidates: Collection<FarmGiantCropCandidate>,
        sequence: Long,
        maxChecks: Int,
        issue: (FarmGiantCropCandidate) -> String?,
    ): FarmGiantCropCandidateSelection {
        require(maxChecks >= 1) { "Giant crop candidate check limit must be positive" }
        val ordered = candidates.filter { FarmGiantCropBlueprint.supports(it.crop) }
            .distinctBy(FarmGiantCropCandidate::block)
            .sortedWith(
                compareBy<FarmGiantCropCandidate> { it.block.x }
                    .thenBy { it.block.z }
                    .thenBy { it.block.y }
                    .thenBy { it.crop },
            ).rotate(sequence + 173L)
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
        val offset = java.lang.Math.floorMod((salt xor (salt ushr 32)).toInt(), size)
        return drop(offset) + take(offset)
    }
}
