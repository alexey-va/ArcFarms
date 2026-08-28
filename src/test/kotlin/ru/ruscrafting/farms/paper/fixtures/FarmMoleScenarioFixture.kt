package ru.ruscrafting.farms.paper.fixtures

import io.mockk.every
import io.mockk.mockk
import org.bukkit.NamespacedKey
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.domain.EngineResult
import ru.ruscrafting.farms.domain.FarmCareRole
import ru.ruscrafting.farms.domain.FarmCareTarget
import ru.ruscrafting.farms.domain.FarmCareType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.FarmRules
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.CuboidActivityRegion
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.RuntimeTaskSupervisor
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink
import ru.ruscrafting.farms.paper.farm.care.mole.FarmMoleBurrowController
import ru.ruscrafting.farms.paper.farm.care.mole.FarmMoleBurrowScene
import ru.ruscrafting.farms.paper.farm.care.mole.FarmMoleBurrowWorld
import ru.ruscrafting.farms.paper.farm.care.mole.ownsMoleBurrowRecord
import ru.ruscrafting.farms.persistence.FarmBurrowReturnRepository
import java.nio.file.Files
import java.nio.file.Path

/** Full underground-world fixture for multi-player mole lifecycle scenarios. */
internal class FarmMoleScenarioFixture private constructor(
    val paper: MockBukkitTestRuntime,
    val world: WorldMock,
    val plugin: Plugin,
    val settings: ArcFarmsConfig,
    val locale: ArcFarmsLocale,
    val runtime: FarmRuntime,
    val port: WorksiteRuntimePort,
    val burrowWorld: FarmMoleBurrowWorld,
    val targets: List<FarmCareTarget>,
    private val supervisor: RuntimeTaskSupervisor,
    private val fixtureRoot: Path,
) : AutoCloseable {
    data class AppliedTransition(
        val actor: Player?,
        val result: EngineResult<FarmShiftState>,
    )

    val transitions = mutableListOf<AppliedTransition>()
    var controller: FarmMoleBurrowController = newController()
        private set

    private val roleKey = NamespacedKey(plugin, "farm_mole_role")
    private val burrowKey = NamespacedKey(plugin, "farm_mole_burrow")

    fun prepareAndBuild(): List<FarmMoleBurrowScene> {
        check(controller.prepare(runtime, targets, runtime.state.placementSequence)) { "Could not prepare mole burrows" }
        controller.ensure(runtime)
        drainBlockQueue()
        val scenes = burrowWorld.scenes(runtime)
        check(scenes.size == targets.size) { "Expected ${targets.size} burrows, got ${scenes.size}" }
        check(scenes.all(FarmMoleBurrowScene::ready)) {
            val mismatches = scenes.asSequence().flatMap { it.records.asSequence() }
                .filter { currentBlockData(Triple(it.x, it.y, it.z)) != it.burrowData }
                .take(3)
                .joinToString { record ->
                    val position = Triple(record.x, record.y, record.z)
                    "${record.x},${record.y},${record.z} expected=${record.burrowData} " +
                        "actual=${currentBlockData(position)} " +
                        "allowed=${runtime.ownsMoleBurrowRecord(record)}"
                }
            "Mole burrow build did not converge: $mismatches"
        }
        controller.ensure(runtime)
        return scenes
    }

    fun restartController(): FarmMoleBurrowController = newController().also { controller = it }

    fun interaction(role: String, burrowId: Int): Interaction = world.entities.asSequence()
        .filterIsInstance<Interaction>()
        .first { entity -> role(entity) == role && burrowId(entity) == burrowId }

    fun enter(player: PlayerMock, scene: FarmMoleBurrowScene) {
        player.teleport(scene.surface)
        check(controller.interact(player, interaction("ENTRANCE", scene.burrowId)))
        check(scene.contains(player.location)) { "Player did not enter burrow ${scene.burrowId}" }
    }

    fun finish(player: PlayerMock, scene: FarmMoleBurrowScene) {
        player.teleport(scene.lair)
        check(controller.interact(player, interaction("LAIR", scene.burrowId)))
    }

    fun restoreWorld() {
        controller.clear(runtime, "scenario_complete")
        drainBlockQueue()
    }

    fun pendingReturn(player: Player): Boolean =
        FarmBurrowReturnRepository(plugin.dataFolder.toPath()).load(player.uniqueId) != null

    fun role(entity: Entity): String? = entity.persistentDataContainer.get(roleKey, PersistentDataType.STRING)

    fun burrowId(entity: Entity): Int? = entity.persistentDataContainer.get(burrowKey, PersistentDataType.INTEGER)

    fun currentBlockData(position: Triple<Int, Int, Int>): String =
        world.getBlockAt(position.first, position.second, position.third).blockData.asString

    override fun close() {
        try {
            controller.cleanup("fixture_close")
        } finally {
            try {
                supervisor.close()
            } finally {
                try {
                    paper.close()
                } finally {
                    fixtureRoot.toFile().deleteRecursively()
                }
            }
        }
    }

    private fun newController(): FarmMoleBurrowController = FarmMoleBurrowController(
        plugin = plugin,
        settings = { settings },
        locale = locale,
        debug = ArcFarmsDebug({ false }) {},
        port = port,
        world = burrowWorld,
        transitions = FarmTransitionSink { active, result, actor ->
            transitions += AppliedTransition(actor, result)
            if (result.accepted) active.state = result.state
        },
        runtimes = { listOf(runtime) },
        clock = { 1_000L },
        configureLabel = { entity, text ->
            entity.text(text)
            entity.isPersistent = false
        },
        setRemoveWhenFarAway = { _, _ -> },
    )

    private fun drainBlockQueue(): Int {
        var total = 0
        repeat(1_000) {
            val processed = controller.processBlocks(512)
            total += processed
            if (processed == 0) return total
        }
        error("Mole block queue did not converge")
    }

    companion object {
        fun open(burrowCount: Int = 3): FarmMoleScenarioFixture {
            require(burrowCount in 1..3) { "Fixture supports one to three burrows" }
            val paper = MockBukkitTestRuntime.open()
            val world = paper.server.addSimpleWorld("sp11")
            for (chunkX in -3..2) for (chunkZ in -2..1) world.getChunkAt(chunkX, chunkZ).load()
            for (x in -44..44) for (z in -24..24) for (y in 42..64) {
                world.getBlockAt(x, y, z).type = org.bukkit.Material.STONE
            }

            val plugin = paper.createSimplePlugin("FarmMoleScenario")
            val fixtureRoot = Files.createTempDirectory("arcfarms-mole-scenario")
            val resourceRoot = copyResources(fixtureRoot.resolve("config"))
            val settings = ArcFarmsConfig.inspect(resourceRoot)
            val configured = settings.farms.single { it.id == "communal_farm" }
            val zone = configured.copy(
                moleBurrow = configured.moleBurrow.copy(
                    cells = 5,
                    maxBurrows = 3,
                    blocksPerTick = 512,
                    candidateAttempts = 16,
                    moleCount = 4,
                ),
            )
            val targets = listOf(-30.5, 0.5, 30.5).take(burrowCount).mapIndexed { id, x ->
                val blockX = kotlin.math.floor(x).toInt()
                world.getBlockAt(blockX, 64, 0).type = org.bukkit.Material.FARMLAND
                world.getBlockAt(blockX, 65, 0).type = org.bukkit.Material.WHEAT
                FarmCareTarget(id, FarmCareRole.MOLE_MOUND, FarmPointPosition(world.name, x, 65.0, 0.5))
            }
            val runtime = FarmRuntime(
                settings = zone,
                region = CuboidActivityRegion(world, "surface_farm", CuboidBounds(-44, 63, -24, 44, 67, 24)),
                orders = emptyMap(),
                orderList = emptyList(),
                rules = FarmRules(listOf(50), 1, 1_000L),
                state = FarmShiftState(
                    phase = FarmPhase.CARE,
                    sequence = 88,
                    placementSequence = 31,
                    careType = FarmCareType.MOLES,
                    careTargets = targets,
                    careGoal = targets.size,
                ),
            )
            val supervisor = RuntimeTaskSupervisor(TestTaskScheduler()).apply(RuntimeTaskSupervisor::activate)
            val token = supervisor.token()
            val port = mockk<WorksiteRuntimePort>(relaxed = true) {
                every { isOperational() } returns true
                every { hasAccess(any(), any()) } returns true
                every { allowInteraction(any(), any()) } returns true
                every { lifecycleToken() } returns token
                every { runAsync(token, any()) } answers {
                    secondArg<() -> Unit>().invoke()
                    true
                }
                every { runSync(token, any()) } answers {
                    secondArg<() -> Unit>().invoke()
                    true
                }
            }
            val burrowWorld = FarmMoleBurrowWorld(
                plugin,
                ArcFarmsDebug({ false }) {},
                addChunkTicket = { _, _ -> true },
                removeChunkTicket = { _, _ -> true },
                createBlockData = { raw ->
                    runCatching { org.bukkit.Bukkit.createBlockData(raw) }.getOrElse {
                        requireNotNull(org.bukkit.Material.matchMaterial(raw.substringBefore('['))).createBlockData()
                    }
                },
            )

            return FarmMoleScenarioFixture(
                paper = paper,
                world = world,
                plugin = plugin,
                settings = settings,
                locale = ArcFarmsLocale(resourceRoot) { settings },
                runtime = runtime,
                port = port,
                burrowWorld = burrowWorld,
                targets = targets,
                supervisor = supervisor,
                fixtureRoot = fixtureRoot,
            )
        }

        private fun copyResources(root: Path): Path {
            Files.createDirectories(root.resolve("lang"))
            listOf("config.yml", "lang/ru.yml", "lang/en.yml").forEach { name ->
                val input = requireNotNull(FarmMoleScenarioFixture::class.java.classLoader.getResourceAsStream(name)) {
                    "Missing test resource $name"
                }
                input.use { Files.copy(it, root.resolve(name)) }
            }
            return root
        }
    }
}
