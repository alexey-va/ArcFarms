package ru.ruscrafting.farms.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

class ArcFarmsArchitectureContractTest : FunSpec({
    val repositoryRoot = Path.of(requireNotNull(System.getProperty("arcfarms.projectDir")))
    val servicePath = repositoryRoot.resolve(
        "src/main/kotlin/ru/ruscrafting/farms/paper/ArcFarmsService.kt",
    )
    val domainPath = repositoryRoot.resolve(
        "src/main/kotlin/ru/ruscrafting/farms/domain/ActivityDomain.kt",
    )
    val farmRoot = repositoryRoot.resolve(
        "src/main/kotlin/ru/ruscrafting/farms/paper/farm",
    )
    val farmModulePath = farmRoot.resolve("FarmModule.kt")
    val farmEventRouterPath = farmRoot.resolve("FarmEventRouter.kt")
    val farmRegistryPath = farmRoot.resolve("FarmRuntimeRegistry.kt")
    val worksiteModulePath = repositoryRoot.resolve(
        "src/main/kotlin/ru/ruscrafting/farms/paper/WorksiteModule.kt",
    )
    val worksitePortsPath = repositoryRoot.resolve(
        "src/main/kotlin/ru/ruscrafting/farms/paper/worksite/WorksitePorts.kt",
    )

    test("shift engines do not share one global event enum") {
        val source = Files.readString(domainPath)

        source.contains("enum class ShiftEvent") shouldBe false
    }

    test("worksite modules expose complete lifecycle through narrow runtime ports") {
        val module = Files.readString(worksiteModulePath)
        val farm = Files.readString(farmModulePath)

        Files.exists(worksitePortsPath) shouldBe true
        module.contains("interface WorksiteModule<S> : RuntimeComponent") shouldBe true
        listOf(
            "override fun activateLoadedState()",
            "override fun reconcileChunk(chunk: Chunk)",
            "override fun cleanup(reason: String)",
        ).forEach { contract -> farm.contains(contract) shouldBe true }
    }

    test("service callbacks cannot bypass the reload-aware task supervisor") {
        val source = Files.readString(servicePath)

        source.contains("Tasks.scheduler") shouldBe false
        source.contains("private val taskSupervisor = RuntimeTaskSupervisor()") shouldBe true
        source.contains("private val worksitePort = PaperWorksiteRuntimePort") shouldBe true
    }

    test("gameplay code cannot schedule directly through Bukkit") {
        val productionRoot = repositoryRoot.resolve("src/main/kotlin")
        val offenders = Files.walk(productionRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .filter { Files.readString(it).contains("Bukkit.getScheduler()") }
                .map(productionRoot::relativize)
                .toList()
        }

        offenders shouldBe emptyList()
    }

    test("food delivery hot path does not scan every entity in the world") {
        val source = Files.readString(
            farmRoot.resolve("incident/route/FarmFoodDeliveryIncident.kt"),
        )

        source.contains("horse.world.entities") shouldBe false
    }

    test("moisture events use the cached irrigation plot index") {
        val source = Files.readString(
            farmRoot.resolve("care/irrigation/FarmIrrigationController.kt"),
        )
        val handler = source.substringAfter("fun onMoistureChange").substringBefore("fun clear(")

        handler.contains("plotAssignments(runtime)[plot]") shouldBe true
        handler.contains("dryPlots(runtime)") shouldBe false
    }

    test("animal rescue is planned only from indexed outdoor beds") {
        val source = Files.readString(farmRoot.resolve("care/FarmCarePlanService.kt"))

        source.contains("placement.bedCandidates(runtime, sources") shouldBe true
        source.contains("placement.openSkyGroundCandidates(runtime, sources") shouldBe false
    }

    test("gameplay owners cannot block the server thread on the state store") {
        val offenders = Files.walk(farmRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .filter { Files.readString(it).contains("persistBlocking") }
                .map(farmRoot::relativize)
                .toList()
        }

        offenders shouldBe emptyList()
    }

    test("application facade stays below its final size ceiling") {
        val lines = Files.readAllLines(servicePath).size

        lines.shouldBeLessThanOrEqual(600)
    }

    test("service does not reclaim lumber or mine state machines") {
        val source = Files.readString(servicePath)

        listOf(
            "LumberRuntime",
            "MineRuntime",
            "LumberShiftEngine",
            "MineShiftEngine",
            "mineReservations",
            "pendingPositions",
            "restoreMineBlocks",
        ).forEach { forbidden -> source.contains(forbidden) shouldBe false }
        source.contains("private val worksites = WorksiteModuleRegistry(listOf(farm.module, lumbermillModule, mineController))") shouldBe true
        source.contains("private val runtimeValidator = ArcFarmsRuntimeValidator") shouldBe true
        source.contains("private val worksitePort = PaperWorksiteRuntimePort") shouldBe true
        source.contains("lumbermillController.onBreak") shouldBe false
        source.contains("lumbermillController.onInteract") shouldBe false
        source.contains("mineController.onBreak") shouldBe false
        source.contains("mineController.onInteract") shouldBe false
        source.contains("mineController.onMove") shouldBe false
    }

    test("extracted farm features own their state and entity identities") {
        val service = Files.readString(servicePath)
        val featurePaths = Files.walk(farmRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.toList()
        }

        listOf(
            "FarmRewardLedger()",
            "farm_supply_zone",
            "farm_supply_kind",
            "farm_service_item",
            "supplyEntities",
            "supplyVisualMaterials",
            "farm_delivery_zone",
            "deliveryEntities",
            "deliveryCarriers",
            "carriedDisplays",
            "farm_pest_zone",
            "farm_pest_nest_zone",
            "pestEntities",
            "pestNestEntities",
            "waterFlows",
            "droughtGrowth",
            "pendingIncidentRestore",
            "specialIncidentScene",
            "nightShift",
            "marketMenu",
            "NamespacedKey",
            "PersistentDataType",
            "FarmShiftEngine.",
            "private var farms",
            "fixedCropRestoreQueue",
            "adminPausedFarmZones",
        ).forEach { forbidden -> service.contains(forbidden) shouldBe false }
        featurePaths.isNotEmpty() shouldBe true
        featurePaths.forEach { path ->
            Files.exists(path) shouldBe true
            Files.readAllLines(path).size.shouldBeLessThanOrEqual(800)
        }
    }

    test("composition graph stays behavior-free") {
        val graph = Files.readString(farmRoot.resolve("FarmComponentGraph.kt"))

        listOf(
            "fun tick(",
            "fun onBreak",
            "fun onInteract",
            "FarmShiftEngine.",
            ".spawn(",
            ".setType(",
        ).forEach { forbidden -> graph.contains(forbidden) shouldBe false }
    }

    test("MockBukkit gaps use one-purpose ports instead of callbacks or platform grab-bags") {
        val forbidden = listOf(
            "blockPassable:",
            "surfacePassable:",
            "surfaceSpawn:",
            "setRemoveWhenFarAway:",
            "ejectPassengers:",
            "configureTextDisplay:",
            "configureLabel:",
            "addChunkTicket:",
            "removeChunkTicket:",
            "createBlockData:",
        )
        val offenders = Files.walk(farmRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .filter { path -> forbidden.any(Files.readString(path)::contains) }
                .map(farmRoot::relativize)
                .toList()
        }

        offenders shouldBe emptyList()
        listOf("FarmBlockPlatform", "FarmEntityPlatform", "FarmChunkLeaseManager").forEach { forbiddenType ->
            Files.walk(repositoryRoot.resolve("src/main/kotlin")).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                    .noneMatch { Files.readString(it).contains(forbiddenType) } shouldBe true
            }
        }
        listOf(
            "FarmBlockPassability.kt",
            "FarmBlockDataDecoder.kt",
            "FarmTextDisplayRenderer.kt",
            "FarmMobDespawnPolicy.kt",
            "FarmVehiclePassengerControl.kt",
        ).forEach { filename ->
            Files.exists(repositoryRoot.resolve("src/main/kotlin/ru/ruscrafting/farms/paper/platform/$filename")) shouldBe true
        }
        Files.exists(repositoryRoot.resolve(
            "src/main/kotlin/ru/ruscrafting/farms/paper/farm/care/mole/MoleBurrowChunkRetention.kt",
        )) shouldBe true
        Files.exists(repositoryRoot.resolve(
            "src/test/kotlin/ru/ruscrafting/farms/paper/fixtures/MockBukkitFarmPaperPorts.kt",
        )) shouldBe true
    }

    test("farm follows the shared worksite contract and has one runtime collection owner") {
        val module = Files.readString(farmModulePath)
        val registry = Files.readString(farmRegistryPath)

        module.contains(": WorksiteModule<FarmShiftState>") shouldBe true
        module.contains("private var runtimes") shouldBe false
        registry.contains("private var runtimes: List<FarmRuntime>") shouldBe true
    }

    test("farm event routing has no knowledge of mine or lumber modules") {
        val source = Files.readString(farmEventRouterPath)

        source.contains("ActivityKind.MINE") shouldBe false
        source.contains("ActivityKind.LUMBER") shouldBe false
        source.contains("WorksiteModuleRegistry") shouldBe false
    }
})
