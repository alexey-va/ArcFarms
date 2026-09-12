package ru.ruscrafting.farms.paper.mine.mining

import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.PendingMineBlock
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.MaterialRules
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.index.MineAnchorRole
import ru.ruscrafting.farms.paper.mine.index.MineBlockIndex
import ru.ruscrafting.farms.paper.mine.index.MineIndexDefinition
import ru.ruscrafting.farms.paper.mine.recovery.MineBlockRecoveryController
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import java.util.UUID
import java.util.logging.Level

/** Bounded order supply on indexed cave faces; uses the normal durable regeneration journal. */
internal class MineVeinController(
    private val index: MineBlockIndex,
    private val recovery: MineBlockRecoveryController,
    private val state: WorksiteStatePort,
    private val clock: () -> Long,
) {
    private val nextCheck = mutableMapOf<String, Long>()
    private val nextWarning = mutableMapOf<String, Long>()
    private val nextSummary = mutableMapOf<String, Long>()
    private val lastSummary = mutableMapOf<String, String>()
    private val placementRounds = mutableMapOf<String, Long>()

    fun tick(runtime: MineRuntime, now: Long) {
        if (!runtime.settings.miningOnly || runtime.state.phase != MinePhase.MINING ||
            now < nextCheck.getOrDefault(runtime.settings.id, 0)) return
        nextCheck[runtime.settings.id] = now + 5_000L
        val order = runtime.currentOrder() ?: return
        val materials = order.miningMaterials
            .filter { it in runtime.settings.materialWeights }.map(MaterialRules::material)
        if (materials.isEmpty()) return
        val world = runtime.region.world
        fun block(p: WorksitePosition) = world.getBlockAt(p.x, p.y, p.z)
        val positions = index.loadedTargets(runtime.settings.id, MineAnchorRole.SUPPORT)
            .filterTo(linkedSetOf()) { it.world == world.name && runtime.region.contains(block(it).location) }
        val definition = MineIndexDefinition(runtime.settings.id, runtime.region,
            runtime.mineableMaterials, runtime.railMaterials)
        // Recover the index too if a crash occurred after the durable block write.
        val indexedMineables = index.loadedTargets(runtime.settings.id, MineAnchorRole.MINEABLE)
        positions.filter { block(it).type in materials && it !in indexedMineables }
            .take(16).forEach { index.refreshBlock(definition, block(it)) }
        val matching = positions.filterTo(linkedSetOf()) { block(it).type in materials && exposed(block(it)) }
        // Scattered single ores must not suppress creation of a visible vein.
        val connectedMatching = matching.filterTo(linkedSetOf()) { p -> faces.any { face ->
            p.copy(x = p.x + face.modX, y = p.y + face.modY, z = p.z + face.modZ) in matching
        } }
        val available = connectedMatching.size
        val pendingRecords = recovery.records(runtime.settings.id).filter { it.nextMaterial in materials.map(Material::name) }
        val pending = pendingRecords.size
        val shortageByMaterial = if (order.miningRequirements.isEmpty()) emptyMap() else materials.associateWith { material ->
            val required = order.miningRequirements[material.name] ?: 0
            val completed = runtime.state.minedByMaterial[material.name] ?: 0
            val stocked = connectedMatching.count { block(it).type == material }
            val queued = pendingRecords.count { it.nextMaterial == material.name }
            (required - completed - stocked - queued).coerceAtLeast(0)
        }
        val ore = shortageByMaterial.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key
            ?: materials[(runtime.state.sequence % materials.size).toInt()]
        val missing = if (order.miningRequirements.isEmpty()) {
            (runtime.rules().miningQuota - runtime.state.mined - available - pending).coerceAtLeast(0)
        } else shortageByMaterial.values.sum()
        val players = world.players.filter { runtime.region.contains(it.location) }.map { it.location }
        val candidates = if (missing == 0) emptySet() else positions.filterTo(linkedSetOf()) { p ->
            val b = block(p)
            host(b.type) && exposed(b) && !recovery.containsPosition("${p.world}:${p.x}:${p.y}:${p.z}")
        }
        logSummary(runtime, now, materials, positions.size, matching.size, available, pending, missing, candidates.size, players.size)
        if (missing == 0) return
        val bandCount = verticalBandCount(runtime.region.bounds.minY, runtime.region.bounds.maxY)
        val supplyByBand = IntArray(bandCount)
        matching.filter { block(it).type == ore }.forEach {
            supplyByBand[verticalBand(it.y, runtime.region.bounds.minY, runtime.region.bounds.maxY, bandCount)]++
        }
        pendingRecords.filter { it.nextMaterial == ore.name }.forEach {
            supplyByBand[verticalBand(it.y, runtime.region.bounds.minY, runtime.region.bounds.maxY, bandCount)]++
        }
        val candidatesByBand = candidates.groupBy {
            verticalBand(it.y, runtime.region.bounds.minY, runtime.region.bounds.maxY, bandCount)
        }
        val round = placementRounds.getOrDefault(runtime.settings.id, 0L)
        val firstBand = Math.floorMod(runtime.state.sequence + round, bandCount.toLong()).toInt()
        val knownOreBand = matching.asSequence().filter { block(it).type == ore }
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
            val b = block(p)
            val original = b.type
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
        private const val MAX_VERTICAL_BANDS = 5
        private const val MIN_BAND_HEIGHT = 16
        private const val MAX_SEED_CHECKS = 64
        private const val VEIN_BLOCKS = 8
        private val faces = listOf(BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)
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
