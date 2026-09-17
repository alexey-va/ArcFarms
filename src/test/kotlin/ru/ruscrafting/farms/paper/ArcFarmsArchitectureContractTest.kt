package ru.ruscrafting.farms.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.farms.paper.mine.MineModule
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
    val lumberVersionedPath = repositoryRoot.resolve(
        "src/main/kotlin/ru/ruscrafting/farms/paper/lumber/LumbermillVersionedModule.kt",
    )
    val mineVersionedPath = repositoryRoot.resolve(
        "src/main/kotlin/ru/ruscrafting/farms/paper/mine/MineVersionedModule.kt",
    )
    val mineFactoryPath = repositoryRoot.resolve(
        "src/main/kotlin/ru/ruscrafting/farms/paper/mine/MineRuntimeFactory.kt",
    )
    val enterpriseKernelPath = repositoryRoot.resolve(
        "src/main/kotlin/ru/ruscrafting/farms/domain/enterprise/WorksiteEnterprise.kt",
    )
    val farmEnterpriseAdapterPath = farmRoot.resolve("enterprise/FarmEnterpriseAdapter.kt")
    val networkServicePath = repositoryRoot.resolve(
        "src/main/kotlin/ru/ruscrafting/farms/paper/ArcFarmsNetworkService.kt",
    )
    val inventoryTransitionPath = repositoryRoot.resolve(
        "src/main/kotlin/ru/ruscrafting/farms/paper/InventoryViewTransition.kt",
    )
    val greenhouseIncidentPath = farmRoot.resolve("incident/greenhouse/FarmHellGreenhouseIncident.kt")
    val moleControllerPath = farmRoot.resolve("care/mole/FarmMoleBurrowController.kt")

    test("shift engines do not share one global event enum") {
        val source = Files.readString(domainPath)

        source.contains("enum class ShiftEvent") shouldBe false
    }

    test("enterprise accounting is worksite-agnostic and farm owns only an adapter") {
        val kernel = Files.readString(enterpriseKernelPath)
        val adapter = Files.readString(farmEnterpriseAdapterPath)

        listOf("org.bukkit", ".paper.", ".config.", "net.milkbowl.vault").forEach { forbidden ->
            kernel.lineSequence().filter { it.startsWith("import ") }.any { forbidden in it } shouldBe false
        }
        listOf("FarmRuntime", "FarmShiftState").forEach { forbidden ->
            kernel.contains(forbidden) shouldBe false
        }
        kernel.contains("val activity: ActivityKind") shouldBe true
        kernel.contains("class WorksiteEnterpriseLedger") shouldBe true
        adapter.contains("class FarmEnterpriseAdapter") shouldBe true
        adapter.contains("ActivityKind.MINE") shouldBe false
        adapter.contains("ActivityKind.LUMBER") shouldBe false
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

    test("production components cannot depend on the composite worksite runtime port") {
        val productionRoot = repositoryRoot.resolve("src/main/kotlin")
        val offenders = Files.walk(productionRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .filter { Files.readString(it).contains("WorksiteRuntimePort") }
                .map(productionRoot::relativize)
                .toList()
        }

        offenders shouldBe emptyList()
    }

    test("underground activities compose one expedition owner") {
        val greenhouse = Files.readString(greenhouseIncidentPath)
        val moles = Files.readString(moleControllerPath)
        listOf(greenhouse, moles).forEach { source ->
            source.contains("FarmUndergroundExpedition") shouldBe true
            source.contains("FarmBurrowReturnRepository") shouldBe false
            source.contains("ScopedTeleportAuthorizer") shouldBe false
        }
    }

    test("mine module receives one incident set instead of every incident") {
        val mineDependencies = MineModule::class.java.declaredConstructors.single().parameterTypes
            .map(Class<*>::getSimpleName)

        mineDependencies.contains("MineIncidentSet") shouldBe true
        mineDependencies.none { it.endsWith("Incident") || it == "MineIncidentScheduler" } shouldBe true
    }

    test("service callbacks cannot bypass the reload-aware task supervisor") {
        val source = Files.readString(servicePath)

        source.contains("Tasks.scheduler") shouldBe false
        source.contains("private val worksiteAdapter = PaperWorksiteAdapter") shouldBe true
    }

    test("reload publishes structural settings only after replacement and queued network events recheck the active generation") {
        val service = Files.readString(servicePath)
        val reload = service.substringAfter("fun reload(").substringBefore("fun isOperational()")
        val network = Files.readString(networkServicePath)
        val receive = network.substringAfter("private fun receive(").substringBefore("private fun originAllowed(")

        (reload.indexOf("replaceRuntime(candidate") < reload.indexOf("publishSettings(candidate)")) shouldBe true
        receive.contains("if (!started || !originAllowed(origin))") shouldBe true
    }

    test("reload releases players from temporary worksite scenes before replacing runtimes") {
        val service = Files.readString(servicePath)
        val reconfigure = service.substringAfter("private fun reconfigureRuntime(").substringBefore("fun onBreakLowest(")

        val release = reconfigure.indexOf("worksiteEvents.release(Bukkit.getOnlinePlayers(), WorksitePlayerReleaseReason.RELOAD)")
        val beforeReload = reconfigure.indexOf("worksites.beforeReload(reason)")
        (release >= 0 && release < beforeReload) shouldBe true
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

    test("replayable worksite randomization owns every mixer constant") {
        val productionRoot = repositoryRoot.resolve("src/main/kotlin")
        val canonical = productionRoot.resolve(
            "ru/ruscrafting/farms/domain/worksite/WorksiteDeterministicSeed.kt",
        )
        val mixerConstants = listOf(
            "-7046029254386353131",
            "-4658895280553007687",
            "-7723592293110705685",
            "-49064778989728563",
            "-4265267296055464877",
            "0x9E3779B97F4A7C15",
            "0xBF58476D1CE4E5B9",
            "0x94D049BB133111EB",
        )
        val offenders = Files.walk(productionRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") && it != canonical }
                .filter { path -> Files.readString(path).let { source -> mixerConstants.any(source::contains) } }
                .map(productionRoot::relativize)
                .toList()
        }

        Files.exists(canonical) shouldBe true
        offenders shouldBe emptyList()
    }

    test("temporary worksite items use one player inventory owner") {
        val productionRoot = repositoryRoot.resolve("src/main/kotlin")
        val migratedOwners = listOf(
            "ru/ruscrafting/farms/paper/worksite/WorksiteServiceItemController.kt",
            "ru/ruscrafting/farms/paper/farm/supply/FarmSupplyController.kt",
            "ru/ruscrafting/farms/paper/farm/incident/frost/FarmFrostIncident.kt",
            "ru/ruscrafting/farms/paper/farm/incident/route/FarmFoodDeliveryGear.kt",
        ).map(productionRoot::resolve).map(Files::readString)

        migratedOwners.forEach { source ->
            source.contains("WorksitePlayerItems") shouldBe true
            source.contains("storageContents.forEachIndexed") shouldBe false
        }
    }

    test("seeder instruction path has one presentation owner") {
        val productionRoot = repositoryRoot.resolve("src/main/kotlin")
        val occurrences = Files.walk(productionRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .mapToInt { path -> "care.seeder.tilling-instruction".toRegex().findAll(Files.readString(path)).count() }
                .sum()
        }

        occurrences shouldBe 1
    }

    test("inventory click view changes are deferred and stale-safe") {
        val transition = Files.readString(inventoryTransitionPath)
        val rootClick = Files.readString(repositoryRoot.resolve(
            "src/main/kotlin/ru/ruscrafting/farms/paper/ArcFarmsMenu.kt",
        )).substringAfter("private fun content(").substringBefore("private fun activityEntry(")
        val enterpriseSource = Files.readString(repositoryRoot.resolve(
            "src/main/kotlin/ru/ruscrafting/farms/paper/WorksiteEnterpriseMenu.kt",
        ))
        val enterpriseNavigation = enterpriseSource.substringAfter("fun openOverview(").substringBefore("private fun openFarmOrTravel(")
        val marketDecision = Files.readString(farmRoot.resolve(
            "incident/special/FarmSpecialIncidentController.kt",
        )).substringAfter("private fun handleMarketDecision(").substringBefore("private fun marketDurationMillis(")

        transition.contains("runLater(lifecycle, 1L)") shouldBe true
        transition.contains("player.openInventory.topInventory !== expectedTop") shouldBe true
        val menuPlatform = Files.readString(repositoryRoot.resolve(
            "src/main/kotlin/ru/ruscrafting/farms/paper/ArcFarmsMenuPlatform.kt",
        ))
        menuPlatform.contains("tasks.runLater(1L)") shouldBe true
        menuPlatform.contains("session(player) !== expected") shouldBe true
        listOf(rootClick, enterpriseNavigation, marketDecision).forEach { handler ->
            handler.contains("menus.transition") shouldBe true
            handler.contains("player.openInventory(") shouldBe false
            handler.contains("player.closeInventory()") shouldBe false
        }
        enterpriseSource.contains("event.rawSlot") shouldBe false
        enterpriseSource.contains("createInventory") shouldBe false
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

    test("ditch rescue owns a procedural deep uneven indexed-bed footprint") {
        val planner = Files.readString(farmRoot.resolve("care/FarmCarePlanService.kt"))
        val world = Files.readString(farmRoot.resolve("care/FarmDitchRescueWorld.kt"))

        planner.contains("explicit(FarmPointKind.DITCH)") shouldBe false
        planner.contains("proceduralDitchSpawnPoints(runtime, farmBeds, placementSequence, salt)") shouldBe true
        planner.contains("FarmDitchLayout.cells(placementSequence)") shouldBe true
        planner.contains("DITCH_MAX_SURFACE_STEP") shouldBe true
        planner.contains("soil.type == Material.FARMLAND") shouldBe true
        world.contains("ledger.captureAll(carved, runtime.settings.id)") shouldBe true
        world.contains("ledger.removeTransient(block)") shouldBe true
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
        source.contains("private val worksites = WorksiteModuleRegistry(listOf(farm.module, lumbermillModule, mineModule))") shouldBe true
        source.contains("private val runtimeValidator = ArcFarmsRuntimeValidator") shouldBe true
        source.contains("private val worksiteAdapter = PaperWorksiteAdapter") shouldBe true
        source.contains("lumbermillController.onBreak") shouldBe false
        source.contains("lumbermillController.onInteract") shouldBe false
        source.contains("mineModule.onBreak") shouldBe false
        source.contains("mineModule.onInteract") shouldBe false
        source.contains("mineModule.onMove") shouldBe false
    }

    test("lumbermill remains an explicitly empty facade") {
        val versioned = Files.readString(lumberVersionedPath)
        val lumberSources = Files.walk(lumberVersionedPath.parent).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.toList()
        }

        lumberSources shouldBe listOf(lumberVersionedPath)
        Files.exists(repositoryRoot.resolve("src/main/kotlin/ru/ruscrafting/farms/config/LumberConfig.kt")) shouldBe false
        Files.exists(repositoryRoot.resolve("src/main/kotlin/ru/ruscrafting/farms/domain/LumberShift.kt")) shouldBe false
        versioned.contains("class LumbermillVersionedModule") shouldBe true
        versioned.contains("override val zoneCount: Int = 0") shouldBe true
        versioned.contains("override fun isAvailable(): Boolean = false") shouldBe true
        versioned.contains("LumbermillComponentGraph") shouldBe false
        versioned.contains("LumberShiftEngine") shouldBe false
    }

    test("mine V2 cannot enter the legacy runtime") {
        val versioned = Files.readString(mineVersionedPath)
        val factory = Files.readString(mineFactoryPath)

        versioned.contains("if (engineVersion == 2)") shouldBe true
        versioned.contains("MineComponentGraph(") shouldBe true
        versioned.contains("rewardGrants = rewardGrants") shouldBe true
        factory.contains("require(settings.engineVersion == 2)") shouldBe true
        versioned.contains("WorksiteEntityDamageHandler") shouldBe true
        versioned.contains("override fun onEntityDamage(event: EntityDamageEvent)") shouldBe true
    }

    test("shared sidebar boundary never invokes a Kotlin default-argument bridge across plugins") {
        val sidebar = Files.readString(repositoryRoot.resolve(
            "src/main/kotlin/ru/ruscrafting/farms/paper/worksite/WorksiteSidebarController.kt",
        ))

        sidebar.contains("ArcSidebarFrame(title, visibleRows, emptySet())") shouldBe true
        sidebar.contains("ArcSidebarFrame(title, visibleRows)") shouldBe false
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
