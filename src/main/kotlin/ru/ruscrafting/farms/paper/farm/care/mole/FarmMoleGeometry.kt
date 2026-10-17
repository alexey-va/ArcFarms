package ru.ruscrafting.farms.paper.farm.care.mole

import ru.ruscrafting.farms.config.FarmMoleBurrowSettings
import ru.ruscrafting.farms.domain.FarmMoleBurrowDecorationPlanner
import ru.ruscrafting.farms.domain.FarmMoleBurrowPlanner
import ru.ruscrafting.farms.domain.FarmMolePassage
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.worksite.WorksiteDeterministicSeed
import kotlin.math.floor

internal data class MolePaletteEntry(val data: String, val solid: Boolean)
internal data class MoleGeometry(
    val blocks: Map<Triple<Int, Int, Int>, Pair<String, FarmMoleBurrowMarker>>,
    val shaft: Set<Triple<Int, Int, Int>>,
    val start: Triple<Int, Int, Int>,
    val lair: Triple<Int, Int, Int>,
) {
    val chunks = blocks.keys.groupBy { (x, _, z) -> (x shr 4) to (z shr 4) }
}

/** Exact existing maze geometry; all registry-dependent palette values are captured on Paper first. */
internal fun moleGeometry(surface: FarmPointPosition, settings: FarmMoleBurrowSettings,
    placementSequence: Long, burrowId: Int, attempt: Int, palette: Map<String, MolePaletteEntry>): MoleGeometry {
        val layoutSeed = WorksiteDeterministicSeed.gridScore(
            placementSequence xor (burrowId.toLong() shl 40),
            floor(surface.x).toInt(),
            floor(surface.z).toInt(),
        )
        val raw = FarmMoleBurrowPlanner.plan(
            settings.cells,
            layoutSeed,
            settings.lightSpacing,
            settings.chamberCount,
        )
        val firstRotation = Math.floorMod((placementSequence xor surface.x.toLong() xor surface.z.toLong()).toInt(), 4)
        val depthSpan = settings.maxDepth - settings.minDepth + 1
        val firstDepth = Math.floorMod(layoutSeed.toInt(), depthSpan)
            val layout = FarmMoleBurrowPlanner.widen(
                FarmMoleBurrowPlanner.rotate(raw, firstRotation + attempt),
                settings.tunnelWidth,
            )
            val depth = settings.minDepth + Math.floorMod(firstDepth + attempt * 5, depthSpan)
            val startX = floor(surface.x).toInt()
            val startZ = floor(surface.z).toInt()
            val originX = startX - layout.start.x
            val originZ = startZ - layout.start.z
            val feetY = floor(surface.y).toInt() - depth
            val planned = linkedMapOf<Triple<Int, Int, Int>, Pair<String, FarmMoleBurrowMarker>>()
            layout.passages.forEach { passage ->
                repeat(settings.tunnelHeight) { dy ->
                    planned[Triple(originX + passage.x, feetY + dy, originZ + passage.z)] =
                        palette.getValue("AIR").data to FarmMoleBurrowMarker.NONE
                }
            }
            val lairX = originX + layout.lair.x
            val lairZ = originZ + layout.lair.z
            for (dx in -2..2) for (dz in -2..2) repeat(settings.tunnelHeight) { dy ->
                planned[Triple(lairX + dx, feetY + dy, lairZ + dz)] = palette.getValue("AIR").data to FarmMoleBurrowMarker.NONE
            }
            layout.chambers.forEach { chamber ->
                val chamberX = originX + chamber.x
                val chamberZ = originZ + chamber.z
                for (dx in -1..1) for (dz in -1..1) repeat(settings.tunnelHeight) { dy ->
                    planned[Triple(chamberX + dx, feetY + dy, chamberZ + dz)] = palette.getValue("AIR").data to FarmMoleBurrowMarker.NONE
                }
            }
            val startPosition = Triple(startX, feetY, startZ)
            val lairPosition = Triple(lairX, feetY, lairZ)
            planned[startPosition] = palette.getValue("AIR").data to FarmMoleBurrowMarker.START
            planned[lairPosition] = palette.getValue("AIR").data to FarmMoleBurrowMarker.LAIR
            // The entrance is a real, journalled shaft. Its top two blocks may be
            // the crop and farmland of a managed bed; both are restored byte-for-
            // byte with the rest of the burrow after the expedition.
            val surfaceBlockY = floor(surface.y).toInt()
            val shaftPositions = (feetY..surfaceBlockY).mapTo(linkedSetOf()) { y ->
                Triple(startX, y, startZ).also { position ->
                    val marker = planned[position]?.second ?: FarmMoleBurrowMarker.NONE
                    // Keep an invisible safety floor below the click target. Players
                    // enter only after their crash-safe return point is committed.
                    planned[position] = (if (y == surfaceBlockY - 1) palette.getValue("BARRIER").data else palette.getValue("AIR").data) to marker
                }
            }
            decorateMoleGeometry(
                plan = planned,
                feetY = feetY,
                startX = startX,
                startZ = startZ,
                lairX = lairX,
                lairZ = lairZ,
                chamberCenters = layout.chambers.mapTo(linkedSetOf()) { chamber ->
                    FarmMolePassage(originX + chamber.x, originZ + chamber.z)
                },
                tunnelHeight = settings.tunnelHeight,
                seed = layoutSeed,
                percent = settings.decorationPercent,
                palette = palette,
            )
            val lightData = palette.getValue("LIGHT").data
            layout.lights.forEach { passage ->
                val position = Triple(originX + passage.x, feetY + settings.tunnelHeight - 1, originZ + passage.z)
                val marker = planned[position]?.second ?: FarmMoleBurrowMarker.NONE
                planned[position] = lightData to marker
            }

    return MoleGeometry(planned, shaftPositions, startPosition, lairPosition)
}

    private fun decorateMoleGeometry(
        plan: MutableMap<Triple<Int, Int, Int>, Pair<String, FarmMoleBurrowMarker>>,
        feetY: Int,
        startX: Int,
        startZ: Int,
        lairX: Int,
        lairZ: Int,
        chamberCenters: Set<FarmMolePassage>,
        tunnelHeight: Int,
        seed: Long,
        percent: Int,
        palette: Map<String, MolePaletteEntry>,
    ) {
        val openFloor = plan.keys.asSequence()
            .filter { it.second == feetY }
            .mapTo(linkedSetOf()) { FarmMolePassage(it.first, it.third) }
        FarmMoleBurrowDecorationPlanner.plan(
            openFloor = openFloor,
            start = FarmMolePassage(startX, startZ),
            lair = FarmMolePassage(lairX, lairZ),
            chambers = chamberCenters,
            tunnelHeight = tunnelHeight,
            seed = seed,
            accentPercent = percent,
        ).forEach { decoration ->
            val position = Triple(decoration.position.x, feetY + decoration.yOffset, decoration.position.z)
            val previous = plan[position]
            if (decoration.yOffset == 0 && previous?.second in setOf(
                    FarmMoleBurrowMarker.START,
                    FarmMoleBurrowMarker.LAIR,
                )
            ) return@forEach
            val material = palette.getValue(decoration.material)
            // Domain planning already keeps solid accents on room walls. This is a
            // second boundary guard against any future decoration replacing walkable
            // feet/head air and recreating an impassable tunnel.
            if (decoration.yOffset in 0..1 && decoration.position in openFloor && material.solid) return@forEach
            val marker = previous?.second ?: FarmMoleBurrowMarker.NONE
            plan[position] = material.data to marker
        }
    }


internal data class FarmMoleSceneIndex(val positions: Set<Triple<Int, Int, Int>>, val distances: Map<Pair<Int, Int>, Int>) {
    val maximumDistance = distances.values.maxOrNull() ?: 0
}

internal fun moleSceneIndex(records: List<FarmMoleBurrowJournalRecord>, floorY: Int, lairX: Int, lairZ: Int): FarmMoleSceneIndex {
    val positions = records.mapTo(hashSetOf()) { Triple(it.x, it.y, it.z) }
    val walkable = records.asSequence().filter { it.y == floorY &&
        (it.burrowData == "minecraft:air" || it.burrowData.startsWith("minecraft:light")) }
        .mapTo(hashSetOf()) { it.x to it.z }
    val origin = lairX to lairZ
    if (origin !in walkable) return FarmMoleSceneIndex(positions, emptyMap())
    val distances = mutableMapOf(origin to 0)
    val queue = ArrayDeque<Pair<Int, Int>>().apply { add(origin) }
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        val nextDistance = distances.getValue(current) + 1
        listOf(current.first + 1 to current.second, current.first - 1 to current.second,
            current.first to current.second + 1, current.first to current.second - 1).forEach { next ->
            if (next in walkable && distances.putIfAbsent(next, nextDistance) == null) queue.add(next)
        }
    }
    return FarmMoleSceneIndex(positions, distances)
}
