package ru.ruscrafting.farms.paper.mine.expedition

import ru.ruscrafting.farms.paper.worksite.WorksiteDisplayGeometryValidator
import kotlin.math.PI

/** Build/export entry point. Never performs geometry audits on the gameplay thread. */
internal object MineDisplayModelValidation {
    fun validate(): List<String> = buildList {
        for (kind in MineDisplayBlueprints.kinds) {
            val parts = MineDisplayBlueprints.model(kind)
            val seen = hashSetOf<String>()
            // Rotation: full revolution. Lever: two revolutions. Press: full down/up cycle.
            for (step in 0..64) {
                val phase = (step * PI / 16).toFloat()
                val boxes = parts.mapIndexed { index, part ->
                    WorksiteDisplayGeometryValidator.Box("$index:${part.material}",
                        MineDisplayBlueprints.center(part, phase), part.size, MineDisplayBlueprints.rotation(part, phase))
                }
                for (conflict in WorksiteDisplayGeometryValidator.conflicts(boxes)) {
                    val pair = "${conflict.first}/${conflict.firstFace} <-> ${conflict.second}/${conflict.secondFace}"
                    if (seen.add(pair)) add("$kind phase=$step*pi/16 $pair area=${conflict.area} gap=${conflict.separation}")
                }
                if (parts.none { it.moving }) break
            }
        }
    }

    @JvmStatic fun main(args: Array<String>) {
        val issues = validate()
        check(issues.isEmpty()) { "Display z-fighting (${issues.size}):\n${issues.joinToString("\n")}" }
        println("Display geometry: ${MineDisplayBlueprints.kinds.size} models, 65 animation poses, no coplanar overlaps")
    }
}
