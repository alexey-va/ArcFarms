package ru.ruscrafting.farms.domain

enum class FarmMoleProximity { FAR, CLOSER, VERY_CLOSE }

object FarmMoleGuidance {
    fun proximity(pathDistance: Int, closeDistance: Int, farDistance: Int): FarmMoleProximity {
        require(pathDistance >= 0)
        require(closeDistance >= 1 && farDistance > closeDistance)
        return when {
            pathDistance <= closeDistance -> FarmMoleProximity.VERY_CLOSE
            pathDistance <= farDistance -> FarmMoleProximity.CLOSER
            else -> FarmMoleProximity.FAR
        }
    }

    fun progress(pathDistance: Int, maxPathDistance: Int): Float {
        require(pathDistance >= 0)
        require(maxPathDistance >= 0)
        if (maxPathDistance == 0) return 1f
        return (1.0 - pathDistance.toDouble() / maxPathDistance).coerceIn(0.0, 1.0).toFloat()
    }
}
