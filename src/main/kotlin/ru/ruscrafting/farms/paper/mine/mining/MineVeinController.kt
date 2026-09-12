package ru.ruscrafting.farms.paper.mine.mining

import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.Location
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.MineResource
import ru.ruscrafting.farms.domain.PendingMineBlock
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.MaterialRules
import ru.ruscrafting.farms.paper.WorksiteTickBudget
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.index.MineAnchorRole
import ru.ruscrafting.farms.paper.mine.index.MineBlockIndex
import ru.ruscrafting.farms.paper.mine.index.MineIndexDefinition
import ru.ruscrafting.farms.paper.mine.recovery.MineBlockRecoveryController
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import java.util.IdentityHashMap
import java.util.UUID
import java.util.logging.Level

private val SURFACE_FACES = listOf(
    BlockFace.UP,
    BlockFace.DOWN,
    BlockFace.NORTH,
    BlockFace.SOUTH,
    BlockFace.EAST,
    BlockFace.WEST,
)

internal data class MineVeinSurfaceSnapshot(
    val position: WorksitePosition,
    val type: Material,
    val exposed: Boolean,
)

/** Incrementally captures one type and six neighbor states per indexed position. */
internal class MineVeinSurfaceSurvey(
    private val positions: List<WorksitePosition>,
    private val isNeighborLoaded: (WorksitePosition) -> Boolean,
    private val readType: (WorksitePosition) -> Material,
) {
    private val targetPositions = positions.toHashSet()
    private val knownTypes = HashMap<WorksitePosition, Material>(positions.size)

    private data class Pending(
        val position: WorksitePosition,
        val type: Material,
        var nextFace: Int = 0,
        var exposed: Boolean = false,
    )

    private var cursor = 0
    private var pending: Pending? = null
    val snapshots = LinkedHashMap<WorksitePosition, MineVeinSurfaceSnapshot>()

    val complete: Boolean
        get() = cursor >= positions.size && pending == null

    fun advance(budget: ru.ruscrafting.farms.paper.WorksiteTickBudget): Boolean {
        while (cursor < positions.size) {
            val position = positions[cursor]
            val current = pending ?: run {
                if (!budget.tryConsume()) return false
                val type = knownTypes[position]
                when {
                    type != null -> Pending(position, type).also { pending = it }
                    !isNeighborLoaded(position) -> {
                        snapshots[position] = MineVeinSurfaceSnapshot(position, Material.AIR, false)
                        cursor++
                        null
                    }
                    else -> Pending(position, readType(position).also { knownTypes[position] = it }).also { pending = it }
                }
            }
            if (current == null) continue
            while (current.nextFace < SURFACE_FACES.size && !current.exposed) {
                if (!budget.tryConsume()) return false
                val face = SURFACE_FACES[current.nextFace]
                val neighbor = current.position.copy(
                    x = current.position.x + face.modX,
                    y = current.position.y + face.modY,
                    z = current.position.z + face.modZ,
                )
                if (isNeighborLoaded(neighbor)) {
                    val neighborType = knownTypes[neighbor] ?: readType(neighbor).also {
                        if (neighbor in targetPositions) knownTypes[neighbor] = it
                    }
                    current.exposed = current.exposed || neighborType.isAir
                }
                current.nextFace++
            }
            snapshots[current.position] = MineVeinSurfaceSnapshot(current.position, current.type, current.exposed)
            pending = null
            cursor++
        }
        return true
    }
}

/** Bounded order supply on indexed cave faces; uses the normal durable regeneration journal. */
internal class MineVeinController(
    private val index: MineBlockIndex,
    private val recovery: MineBlockRecoveryController,
    private val state: WorksiteStatePort,
    private val clock: () -> Long,
) {
    private val nextCheck = IdentityHashMap<MineRuntime, Long>()
    private val surveys = IdentityHashMap<MineRuntime, SurveyState>()
    private val nextWarning = mutableMapOf<String, Long>()
    private val nextSummary = mutableMapOf<String, Long>()
    private val lastSummary = mutableMapOf<String, String>()
    private val placementRounds = mutableMapOf<String, Long>()

    fun tick(runtime: MineRuntime, now: Long, tickBudget: WorksiteTickBudget = WorksiteTickBudget(SURVEY_BLOCK_READS_PER_TICK)) {
        if (!runtime.settings.miningOnly || runtime.state.phase != MinePhase.MINING) {
            surveys.remove(runtime)
            nextCheck.remove(runtime)
            return
        }
        val order = runtime.currentOrder() ?: run {
            surveys.remove(runtime)
            nextCheck.remove(runtime)
            return
        }
        val acceptedByResource = order.requestedResources.associateWith { resource ->
            MineResource.variants(resource).map(MaterialRules::material)
        }.filterValues { it.isNotEmpty() }
        val generationByResource = acceptedByResource.mapValues { (_, variants) ->
            variants.filter { it.name in runtime.settings.materialWeights }
        }.filterValues { it.isNotEmpty() }
        val materials = acceptedByResource.values.flatten().distinct()
        if (materials.isEmpty()) {
            surveys.remove(runtime)
            nextCheck.remove(runtime)
            return
        }
        val world = runtime.region.world

        val key = SurveyKey(runtime.settings, runtime.region, runtime.state.sequence, runtime.state.orderId)
        var survey = surveys[runtime]
        if (survey?.key != key) {
            surveys.remove(runtime)
            nextCheck.remove(runtime)
            survey = null
        }
        if (survey == null || survey.scan.complete) {
            if (now < nextCheck.getOrDefault(runtime, 0L)) return
            val positions = index.loadedTargets(runtime.settings.id, MineAnchorRole.SUPPORT)
                .filterTo(linkedSetOf()) {
                    it.world == world.name && runtime.region.contains(Location(world, it.x.toDouble(), it.y.toDouble(), it.z.toDouble()))
                }
                .toList()
            val indexedMineables = index.loadedTargets(runtime.settings.id, MineAnchorRole.MINEABLE)
            survey = SurveyState(
                key = key,
                positions = positions,
                indexedMineables = indexedMineables,
                scan = MineVeinSurfaceSurvey(
                    positions = positions,
                    isNeighborLoaded = { p -> world.isChunkLoaded(p.x shr 4, p.z shr 4) },
                    readType = { p -> world.getBlockAt(p.x, p.y, p.z).type },
                ),
            ).also { surveys[runtime] = it }
        }
        val activeSurvey = survey ?: return
        if (!activeSurvey.scan.advance(tickBudget)) return
        nextCheck[runtime] = now + SURVEY_INTERVAL_MILLIS

        val snapshots = activeSurvey.scan.snapshots
        val positions = activeSurvey.positions
        val definition = MineIndexDefinition(runtime.settings.id, runtime.region,
            runtime.mineableMaterials, runtime.railMaterials)
        // Recover the index too if a crash occurred after the durable block write.
        positions.asSequence()
            .mapNotNull { snapshots[it]?.takeIf { snapshot -> snapshot.type in materials } }
            .filter { it.position !in activeSurvey.indexedMineables }
            .take(16)
            .forEach { snapshot -> index.refreshBlock(definition, world.getBlockAt(snapshot.position.x, snapshot.position.y, snapshot.position.z)) }
        val matching = positions.filterTo(linkedSetOf()) { position ->
            snapshots[position]?.let { it.type in materials && it.exposed } == true
        }
        // Scattered single ores must not suppress creation of a visible vein.
        val connectedMatching = matching.filterTo(linkedSetOf()) { p -> faces.any { face ->
            p.copy(x = p.x + face.modX, y = p.y + face.modY, z = p.z + face.modZ) in matching
        } }
        val available = connectedMatching.size
        val materialNames = materials.mapTo(linkedSetOf(), Material::name)
        val pendingRecords = recovery.records(runtime.settings.id).filter { it.nextMaterial in materialNames }
        val pending = pendingRecords.size
        val shortageByResource = if (order.normalizedRequirements.isEmpty()) emptyMap() else generationByResource.mapValues { (resource, generatedVariants) ->
            val required = order.normalizedRequirements[resource] ?: 0
            val completed = runtime.state.minedByMaterial[resource] ?: 0
            val acceptedVariants = acceptedByResource.getValue(resource)
            val stocked = connectedMatching.count { snapshots.getValue(it).type in acceptedVariants }
            val queued = pendingRecords.count { it.nextMaterial in acceptedVariants.map(Material::name) }
            (required - completed - stocked - queued).coerceAtLeast(0)
        }
        val resource = shortageByResource.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key
            ?: generationByResource.keys.elementAt((runtime.state.sequence % generationByResource.size).toInt())
        val resourceMaterials = generationByResource.getValue(resource)
        val acceptedResourceMaterials = acceptedByResource.getValue(resource)
        val ore = matching.asSequence().map { snapshots.getValue(it).type }.firstOrNull { it in resourceMaterials }
            ?: resourceMaterials[(runtime.state.sequence % resourceMaterials.size).toInt()]
        val missing = if (order.normalizedRequirements.isEmpty()) {
            (runtime.rules().miningQuota - runtime.state.mined - available - pending).coerceAtLeast(0)
        } else shortageByResource.values.sum()
        val players = world.players.filter { runtime.region.contains(it.location) }.map { it.location }
        val candidates = if (missing == 0) emptySet() else positions.filterTo(linkedSetOf()) { p ->
            val snapshot = snapshots.getValue(p)
            host(snapshot.type) && snapshot.exposed && !recovery.containsPosition("${p.world}:${p.x}:${p.y}:${p.z}")
        }
        logSummary(runtime, now, materials, positions.size, matching.size, available, pending, missing, candidates.size, players.size)
        if (missing == 0) return
        val bandCount = verticalBandCount(runtime.region.bounds.minY, runtime.region.bounds.maxY)
        val supplyByBand = IntArray(bandCount)
        matching.filter { snapshots.getValue(it).type in acceptedResourceMaterials }.forEach {
            supplyByBand[verticalBand(it.y, runtime.region.bounds.minY, runtime.region.bounds.maxY, bandCount)]++
        }
        pendingRecords.filter { it.nextMaterial in acceptedResourceMaterials.map(Material::name) }.forEach {
            supplyByBand[verticalBand(it.y, runtime.region.bounds.minY, runtime.region.bounds.maxY, bandCount)]++
        }
        val candidatesByBand = candidates.groupBy {
            verticalBand(it.y, runtime.region.bounds.minY, runtime.region.bounds.maxY, bandCount)
        }
        val round = placementRounds.getOrDefault(runtime.settings.id, 0L)
        val firstBand = Math.floorMod(runtime.state.sequence + round, bandCount.toLong()).toInt()
        val knownOreBand = matching.asSequence().filter { snapshots.getValue(it).type in acceptedResourceMaterials }
            .groupingBy { verticalBand(it.y, runtime.region.bounds.minY, runtime.region.bounds.maxY, bandCount) }
            .eachCount().filterKeys(candidatesByBand::containsKey).maxByOrNull { it.value }?.key
        val targetBand = knownOreBand ?: candidatesByBand.keys.minWithOrNull(
            compareBy<Int> { supplyByBand[it] }.thenBy { Math.floorMod(it - firstBand, bandCount) },
        ) ?: return
        val bandCandidates = candidatesByBand.getValue(targetBand).toCollection(linkedSetOf())
        val orderedSeeds = bandCandidates.toList()
        val seedOffset = Math.floorMod(runtime.state.sequence * 31L + round * 104_729L, orderedSeeds.size.toLong()).toInt()
        val seedStep = (orderedSeeds.size / MAX_SEED_CHECKS).coerceAtLeast(1)
        var selected = emptyList<WorksitePosition>()
        for (check in 0 until minOf(MAX_SEED_CHECKS, orderedSeeds.size)) {
            val seed = orderedSeeds[(seedOffset + check * seedStep) % orderedSeeds.size]
            val component = connected(seed, bandCandidates, minOf(VEIN_BLOCKS, missing))
            if (component.size > selected.size) selected = component
            if (selected.size >= minOf(VEIN_BLOCKS, missing)) break
        }
        if (selected.isEmpty()) {
            if (now >= nextWarning.getOrDefault(runtime.settings.id, 0)) {
                nextWarning[runtime.settings.id] = now + 60_000L
                state.log(Level.WARNING, "Mine vein placement unavailable zone=${runtime.settings.id} sequence=${runtime.state.sequence} indexed=${positions.size} hosts=${candidates.size} available=$available pending=$pending missing=$missing")
            }
            return
        }
        val sequence = runtime.state.sequence
        val orderId = runtime.state.orderId
        state.log(Level.INFO, "Mine vein placement scheduled zone=${runtime.settings.id} sequence=$sequence " +
            "order=$orderId ore=$ore blocks=${selected.size} indexed=${positions.size} candidates=${candidates.size} " +
            "available=$available pending=$pending missing=$missing players=${players.size} " +
            "band=${targetBand + 1}/$bandCount bandSupply=${supplyByBand[targetBand]} " +
            "positions=${selected.joinToString(",") { "${it.x}:${it.y}:${it.z}" }}")
        placementRounds[runtime.settings.id] = round + 1L
        selected.forEach { p ->
            val snapshot = snapshots[p] ?: return@forEach
            val b = world.getBlockAt(p.x, p.y, p.z)
            val original = b.type
            if (original != snapshot.type || !host(original) || !exposed(b) || recovery.containsPosition("${p.world}:${p.x}:${p.y}:${p.z}")) {
                return@forEach
            }
            val record = PendingMineBlock("vein:${UUID.randomUUID()}", runtime.settings.id, p.world, p.x, p.y, p.z,
                original.name, original.name, ore.name, clock().coerceAtLeast(1))
            // This is a permanent managed deposit, like ordinary regenerated ore, not a temporary event scene.
            recovery.prepare(record, b, original, stillValid = {
                runtime.region.contains(b.location) && exposed(b)
            }) {
                b.setType(ore, false)
                index.refreshBlock(definition, b)
            }.whenComplete { _, failure ->
                if (failure != null) state.log(Level.WARNING,
                    "Mine vein journal failed zone=${runtime.settings.id} position=${record.positionKey} ore=$ore", failure)
            }.whenComplete { accepted, failure ->
                if (failure == null) state.log(
                    if (accepted == true) Level.INFO else Level.WARNING,
                    "Mine vein block ${if (accepted == true) "placed" else "rejected"} zone=${runtime.settings.id} " +
                        "sequence=$sequence order=$orderId ore=$ore original=$original " +
                        "position=${record.positionKey} record=${record.id}",
                )
            }
        }
    }

    /** Drops in-progress snapshots when the module lifecycle or runtime collection is rebuilt. */
    fun clear() {
        surveys.clear()
        nextCheck.clear()
        nextWarning.clear()
        nextSummary.clear()
        lastSummary.clear()
        placementRounds.clear()
    }

    private data class SurveyKey(
        val settings: ru.ruscrafting.farms.config.MineZoneSettings,
        val region: ru.ruscrafting.farms.paper.ActivityRegion,
        val sequence: Long,
        val orderId: String?,
    )

    private data class SurveyState(
        val key: SurveyKey,
        val positions: List<WorksitePosition>,
        val indexedMineables: Set<WorksitePosition>,
        val scan: MineVeinSurfaceSurvey,
    )

    private fun logSummary(
        runtime: MineRuntime,
        now: Long,
        materials: List<Material>,
        indexed: Int,
        matching: Int,
        available: Int,
        pending: Int,
        missing: Int,
        candidates: Int,
        players: Int,
    ) {
        val signature = listOf(runtime.state.sequence, runtime.state.orderId, runtime.state.mined, indexed,
            matching, available, pending, missing, candidates, players).joinToString("|")
        if (lastSummary[runtime.settings.id] == signature && now < nextSummary.getOrDefault(runtime.settings.id, 0L)) return
        lastSummary[runtime.settings.id] = signature
        nextSummary[runtime.settings.id] = now + SUMMARY_INTERVAL_MILLIS
        state.log(Level.INFO, "Mine vein state zone=${runtime.settings.id} sequence=${runtime.state.sequence} " +
            "order=${runtime.state.orderId} phase=${runtime.state.phase} materials=${materials.joinToString(",")} " +
            "progress=${runtime.state.mined}/${runtime.rules().miningQuota} indexed=$indexed matching=$matching " +
            "availableConnected=$available pending=$pending missing=$missing candidates=$candidates players=$players")
    }

    internal companion object {
        private const val SUMMARY_INTERVAL_MILLIS = 60_000L
        private const val SURVEY_INTERVAL_MILLIS = 5_000L
        private const val SURVEY_BLOCK_READS_PER_TICK = 1_024
        private const val MAX_VERTICAL_BANDS = 5
        private const val MIN_BAND_HEIGHT = 16
        private const val MAX_SEED_CHECKS = 64
        private const val VEIN_BLOCKS = 8
        private val faces = SURFACE_FACES
        fun exposed(block: Block): Boolean = faces.any {
            block.world.isChunkLoaded((block.x + it.modX) shr 4, (block.z + it.modZ) shr 4) &&
                block.getRelative(it).type.isAir
        }
        fun host(material: Material): Boolean {
            val name = material.name
            return material in setOf(Material.STONE, Material.GRANITE, Material.DIORITE, Material.ANDESITE,
                Material.DEEPSLATE, Material.TUFF, Material.CALCITE, Material.NETHERRACK, Material.BASALT,
                Material.SMOOTH_BASALT, Material.BLACKSTONE, Material.END_STONE, Material.DRIPSTONE_BLOCK,
                Material.COBBLESTONE, Material.MOSSY_COBBLESTONE, Material.TERRACOTTA, Material.DIRT,
                Material.COARSE_DIRT, Material.ROOTED_DIRT, Material.MOSS_BLOCK, Material.MUD,
                Material.PACKED_MUD, Material.GRAVEL, Material.CLAY, Material.SAND, Material.RED_SAND,
                Material.BONE_BLOCK, Material.SOUL_SAND, Material.SOUL_SOIL, Material.MAGMA_BLOCK,
                Material.CRIMSON_NYLIUM, Material.WARPED_NYLIUM) ||
                name.endsWith("_TERRACOTTA") && !name.endsWith("_GLAZED_TERRACOTTA") || name.endsWith("_CONCRETE") ||
                name in setOf("SANDSTONE", "SMOOTH_SANDSTONE", "CUT_SANDSTONE", "CHISELED_SANDSTONE",
                    "RED_SANDSTONE", "SMOOTH_RED_SANDSTONE", "CUT_RED_SANDSTONE", "CHISELED_RED_SANDSTONE",
                    "POLISHED_ANDESITE", "POLISHED_DIORITE", "POLISHED_GRANITE", "POLISHED_BASALT",
                    "COBBLED_DEEPSLATE", "POLISHED_DEEPSLATE", "DEEPSLATE_BRICKS", "CRACKED_DEEPSLATE_BRICKS",
                    "DEEPSLATE_TILES", "CRACKED_DEEPSLATE_TILES", "STONE_BRICKS", "MOSSY_STONE_BRICKS",
                    "CRACKED_STONE_BRICKS", "CHISELED_STONE_BRICKS", "PACKED_MUD", "MUD_BRICKS",
                    "POLISHED_TUFF", "TUFF_BRICKS", "CHISELED_TUFF_BRICKS", "POLISHED_BLACKSTONE",
                    "POLISHED_BLACKSTONE_BRICKS", "CRACKED_POLISHED_BLACKSTONE_BRICKS", "NETHER_BRICKS",
                    "RED_NETHER_BRICKS", "CRACKED_NETHER_BRICKS", "END_STONE_BRICKS", "PURPUR_BLOCK", "BRICKS")
        }
        fun connected(seed: WorksitePosition, candidates: Set<WorksitePosition>, limit: Int): List<WorksitePosition> {
            val queue = ArrayDeque<WorksitePosition>()
            val seen = linkedSetOf<WorksitePosition>()
            queue.add(seed)
            while (queue.isNotEmpty() && seen.size < limit) {
                val p = queue.removeFirst()
                if (p !in candidates || !seen.add(p)) continue
                faces.forEach { queue.add(p.copy(x = p.x + it.modX, y = p.y + it.modY, z = p.z + it.modZ)) }
            }
            return seen.toList()
        }

        fun verticalBandCount(minY: Int, maxY: Int): Int {
            val height = (maxY - minY + 1).coerceAtLeast(1)
            return minOf(MAX_VERTICAL_BANDS, ((height + MIN_BAND_HEIGHT - 1) / MIN_BAND_HEIGHT).coerceAtLeast(1))
        }

        fun verticalBand(y: Int, minY: Int, maxY: Int, bands: Int): Int {
            require(bands > 0)
            val height = (maxY - minY + 1).coerceAtLeast(1)
            return (((y - minY).coerceIn(0, height - 1).toLong() * bands) / height).toInt().coerceAtMost(bands - 1)
        }
    }
}
