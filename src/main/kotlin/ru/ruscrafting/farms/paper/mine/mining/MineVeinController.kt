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

    fun tick(runtime: MineRuntime, now: Long) {
        if (!runtime.settings.miningOnly || runtime.state.phase != MinePhase.MINING ||
            now < nextCheck.getOrDefault(runtime.settings.id, 0)) return
        nextCheck[runtime.settings.id] = now + 5_000L
        val materials = runtime.currentOrder()?.miningMaterials.orEmpty()
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
        val available = matching.count { p -> faces.any { face ->
            p.copy(x = p.x + face.modX, y = p.y + face.modY, z = p.z + face.modZ) in matching
        } }
        val pending = recovery.records(runtime.settings.id).count { it.nextMaterial in materials.map(Material::name) }
        val missing = (runtime.rules().miningQuota - runtime.state.mined - available - pending).coerceAtLeast(0)
        if (missing == 0) return
        val candidates = positions.filterTo(linkedSetOf()) { p ->
            val b = block(p)
            host(b.type) && exposed(b) && !recovery.containsPosition("${p.world}:${p.x}:${p.y}:${p.z}")
        }
        val players = world.players.filter { runtime.region.contains(it.location) }.map { it.location }
        val nearby = candidates.sortedBy { p -> players.minOfOrNull {
            it.distanceSquared(block(p).location)
        } ?: Double.MAX_VALUE }
        val remaining = candidates.toMutableSet()
        var selected = emptyList<WorksitePosition>()
        for (seed in nearby) {
            if (seed !in remaining) continue
            val component = connected(seed, remaining, minOf(16, missing))
            remaining.removeAll(component.toSet())
            if (component.size > selected.size) selected = component
            if (selected.size >= minOf(8, missing)) break
        }
        if (selected.isEmpty()) {
            if (now >= nextWarning.getOrDefault(runtime.settings.id, 0)) {
                nextWarning[runtime.settings.id] = now + 60_000L
                state.log(Level.WARNING, "Mine vein placement unavailable zone=${runtime.settings.id} sequence=${runtime.state.sequence} indexed=${positions.size} hosts=${candidates.size} available=$available pending=$pending missing=$missing")
            }
            return
        }
        val ore = materials[(runtime.state.sequence % materials.size).toInt()]
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
            }
        }
    }

    internal companion object {
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
    }
}
