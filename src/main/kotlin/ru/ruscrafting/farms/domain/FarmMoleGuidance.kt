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
}
