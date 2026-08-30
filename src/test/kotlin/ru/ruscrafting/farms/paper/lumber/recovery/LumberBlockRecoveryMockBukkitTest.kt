package ru.ruscrafting.farms.paper.lumber.recovery

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.config.LumberOrderSettings
import ru.ruscrafting.farms.config.LumberZoneSettings
import ru.ruscrafting.farms.config.ZoneReference
import ru.ruscrafting.farms.domain.LumberIncidentType
import ru.ruscrafting.farms.domain.LumberPhase
import ru.ruscrafting.farms.domain.LumberShiftState
import ru.ruscrafting.farms.domain.PendingLumberBlock
import ru.ruscrafting.farms.paper.CuboidActivityRegion
import ru.ruscrafting.farms.paper.RuntimeTaskSupervisor
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.lumber.LumberRuntime
import ru.ruscrafting.farms.persistence.LumberRecoveryJournal
import java.util.concurrent.CompletableFuture

class LumberBlockRecoveryMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    test("log remains intact until journal prepare succeeds and restores once") {
        val world = paper.server.addSimpleWorld("world")
        world.getChunkAt(0, 0).load()
        val block = world.getBlockAt(2, 64, 3).also { it.type = Material.OAK_LOG }
        val player = paper.server.addPlayer("Logger")
        val journal = ControllableLumberJournal()
        val effects = RecordingLumberEffects()
        val controller = LumberBlockRecoveryController(journal, immediatePort(), { 1_000L }, effects)
        val runtime = runtime(world.name)

        val prepared = controller.prepare(runtime, player, block, ItemStack(Material.IRON_AXE))
        block.type shouldBe Material.OAK_LOG

        journal.completePrepare()
        prepared.join() shouldBe true
        block.type shouldBe Material.AIR
        effects.delivered shouldContainExactly listOf(Material.OAK_LOG)

        controller.processDue(now = 6_001L)
        block.type shouldBe Material.OAK_LOG
        journal.records() shouldBe emptyList()
        controller.processDue(now = 7_000L) shouldBe 0
    }

    test("stale lifecycle callback never mutates the block and recovery retires the intent") {
        val world = paper.server.addSimpleWorld("world")
        world.getChunkAt(0, 0).load()
        val block = world.getBlockAt(2, 64, 3).also { it.type = Material.OAK_LOG }
        val player = paper.server.addPlayer("Logger")
        val journal = ControllableLumberJournal()
        val effects = RecordingLumberEffects()
        val controller = LumberBlockRecoveryController(journal, immediatePort(), { 1_000L }, effects)
        val runtime = runtime(world.name)

        val prepared = controller.prepare(runtime, player, block, ItemStack(Material.IRON_AXE))
        runtime.state = runtime.state.copy(sequence = runtime.state.sequence + 1)
        journal.completePrepare()

        prepared.join() shouldBe false
        block.type shouldBe Material.OAK_LOG
        effects.delivered shouldBe emptyList()
        controller.processDue(now = 6_001L)
        journal.records() shouldBe emptyList()
    }
})

private fun immediatePort(): WorksiteRuntimePort {
    val token = mockk<RuntimeTaskSupervisor.Token>()
    return mockk(relaxed = true) {
        every { lifecycleToken() } returns token
        every { runSync(token, any()) } answers {
            secondArg<() -> Unit>().invoke()
            true
        }
    }
}

private fun runtime(world: String): LumberRuntime {
    val bounds = CuboidBounds(0, 50, 0, 10, 80, 10)
    val reference = ZoneReference(world, null, bounds)
    val settings = LumberZoneSettings(
        id = "sawmill",
        reference = reference,
        station = reference,
        permission = "arcfarms.lumber",
        fellingQuota = 2,
        processingQuota = 2,
        processingPerUse = 1,
        species = listOf("OAK"),
        stationMaterials = setOf("STONECUTTER"),
        engineVersion = 2,
        orders = listOf(
            LumberOrderSettings(
                "oak_contract",
                listOf("OAK"),
                2,
                1,
                2,
                1,
                listOf(LumberIncidentType.WINDTHROW, LumberIncidentType.BARK_BEETLES, LumberIncidentType.SAW_JAM),
            ),
        ),
        incidentCountMin = 3,
        incidentCountMax = 3,
        recoverySeconds = 5,
    )
    val region = CuboidActivityRegion(requireNotNull(Bukkit.getWorld(world)), "test", bounds)
    return LumberRuntime(
        settings,
        region,
        region,
        cooldownMillis = 1_000L,
        state = LumberShiftState(phase = LumberPhase.FELLING, sequence = 1, orderId = "oak_contract", species = "OAK"),
    )
}

private class ControllableLumberJournal : LumberRecoveryJournal {
    private val entries = linkedMapOf<String, PendingLumberBlock>()
    private var pending = CompletableFuture<Unit>()

    override fun records(): List<PendingLumberBlock> = entries.values.toList()
    override fun containsPosition(positionKey: String): Boolean = entries.values.any { it.positionKey == positionKey }
    override fun prepare(record: PendingLumberBlock): CompletableFuture<Unit> {
        entries[record.id] = record
        return pending
    }
    override fun remove(recordId: String): CompletableFuture<Unit> {
        entries.remove(recordId)
        return CompletableFuture.completedFuture(Unit)
    }

    fun completePrepare() = pending.complete(Unit)
}

private class RecordingLumberEffects : LumberBlockEffects {
    val delivered = mutableListOf<Material>()

    override fun drops(block: org.bukkit.block.Block, tool: ItemStack, player: org.bukkit.entity.Player): List<ItemStack> =
        listOf(ItemStack(block.type))

    override fun remove(block: org.bukkit.block.Block) = block.setType(Material.AIR, false)

    override fun deliver(block: org.bukkit.block.Block, drops: List<ItemStack>) {
        delivered += drops.map(ItemStack::getType)
    }

    override fun restore(block: org.bukkit.block.Block, blockData: String) {
        block.setBlockData(Bukkit.createBlockData(blockData), false)
    }
}
