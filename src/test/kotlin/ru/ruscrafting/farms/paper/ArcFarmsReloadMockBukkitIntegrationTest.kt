package ru.ruscrafting.farms.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Horse
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.mockbukkit.mockbukkit.scoreboard.ObjectiveMock
import org.mockbukkit.mockbukkit.scoreboard.ScoreMock
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import ru.arc.config.Config
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.ArcFarmsState
import ru.ruscrafting.farms.domain.FarmDeliveryRoute
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmRouteState
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.domain.FarmSpecialIncidentState
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.persistence.ArcFarmsStateRepository
import ru.ruscrafting.farms.persistence.FarmRouteRepository
import ru.ruscrafting.farms.paper.platform.PaperFarmTextDisplayRenderer
import ru.ruscrafting.farms.paper.platform.PaperFarmMobDespawnPolicy
import ru.ruscrafting.farms.paper.platform.PaperFarmRouteChunkLoader
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path

/** Exercises the real plugin command -> config -> ArcFarmsService.reload lifecycle. */
class ArcFarmsReloadMockBukkitIntegrationTest : FunSpec({
    test("food delivery runtime, participant gear and HUD survive hot reload") {
        val paper = MockBukkitTestRuntime.open()
        try {
            val world = paper.server.addSimpleWorld("sp11")
            paper.server.addSimpleWorld("world")
            val plugin = paper.server.pluginManager.loadPlugin(ArcFarmsPlugin::class.java) as ArcFarmsPlugin
            prepareReloadData(plugin.dataFolder.toPath())
            seedFoodDelivery(plugin.dataFolder.toPath())
            world.getChunkAt(12, 28).load()
            for (x in 195..210) for (z in 450..456) world.getBlockAt(x, 48, z).type = Material.STONE
            mockkConstructor(FarmContractSceneManager::class)
            every { anyConstructed<FarmContractSceneManager>().ensure(any()) } just Runs
            mockkObject(PaperFarmMobDespawnPolicy)
            every { PaperFarmMobDespawnPolicy.setRemoveWhenFarAway(any(), any()) } just Runs
            mockkObject(PaperFarmRouteChunkLoader)
            every { PaperFarmRouteChunkLoader.retain(any(), any()) } returns true
            mockkObject(PaperFarmTextDisplayRenderer)
            every { PaperFarmTextDisplayRenderer.render(any(), any(), any()) } just Runs
            mockkConstructor(ObjectiveMock::class, ScoreMock::class)
            every { anyConstructed<ObjectiveMock>().numberFormat(any()) } just Runs
            every { anyConstructed<ScoreMock>().customName(any()) } just Runs
            paper.server.pluginManager.enablePlugin(plugin)

            val participant = paper.addPlayer("RouteDriver")
            participant.isOp = true
            participant.teleport(Location(world, 201.0, 49.0, 453.0))
            paper.performTicks(25)

            val service = privateProperty(plugin, "service") as ArcFarmsService
            val runtimeBefore = runtime(service)
            val stateBefore = privateProperty(runtimeBefore, "state") as FarmShiftState
            stateBefore.phase shouldBe FarmPhase.INCIDENT
            stateBefore.incidentType shouldBe FarmIncidentType.FOOD_DELIVERY
            val horse = world.entities.filterIsInstance<Horse>().single()
            val mount = PlayerInteractEntityEvent(participant, horse, org.bukkit.inventory.EquipmentSlot.HAND)
            service.onInteractEntityLowest(mount)
            service.onInteractEntity(mount)
            paper.performTicks(2)
            horse.passengers.singleOrNull() shouldBe participant
            participant.inventory.contents.any { it?.type == Material.CROSSBOW } shouldBe true

            val horseId = horse.uniqueId
            val progressBefore = stateBefore.incidentProgress
            val gearBefore = participant.inventory.contents.map { it?.clone() }
            val speedBefore = horse.getAttribute(org.bukkit.attribute.Attribute.MOVEMENT_SPEED)?.baseValue
            val periodicBefore = (privateProperty(service, "periodicTaskSupervisor") as RuntimeTaskSupervisor).trackedCount()
            var staleLifecycleCallbackRan = false
            var activeGameplayDelayRan = false
            (privateProperty(service, "lifecycleTaskSupervisor") as RuntimeTaskSupervisor).runLater(10L) {
                staleLifecycleCallbackRan = true
            }
            (privateProperty(service, "gameplayTaskSupervisor") as RuntimeTaskSupervisor).runLater(10L) {
                activeGameplayDelayRan = true
            }

            val configPath = plugin.dataFolder.toPath().resolve("config.yml")
            val config = YamlConfiguration.loadConfiguration(configPath.toFile())
            config.set("farm-zones.communal_farm.route-delivery.horse-speed", 0.31)
            config.save(configPath.toFile())
            setLocaleValue(plugin.dataFolder.toPath(), "scoreboard.title", "<green>Reloaded farm</green>")
            participant.performCommand("arcfarms reload") shouldBe true
            paper.performTicks(22)

            val runtimeAfter = runtime(service)
            runtimeAfter shouldBe runtimeBefore
            val stateAfter = privateProperty(runtimeAfter, "state") as FarmShiftState
            stateAfter.phase shouldBe FarmPhase.INCIDENT
            stateAfter.incidentType shouldBe FarmIncidentType.FOOD_DELIVERY
            stateAfter.incidentProgress shouldBe progressBefore
            stateAfter.specialIncident?.routeName shouldBe "main"
            val horseAfter = world.entities.filterIsInstance<Horse>().single()
            horseAfter.uniqueId shouldBe horseId
            horseAfter.passengers.singleOrNull() shouldBe participant
            participant.inventory.contents.map { it?.clone() } shouldBe gearBefore
            staleLifecycleCallbackRan shouldBe false
            activeGameplayDelayRan shouldBe true
            privateProperty(runtimeAfter, "settings").let { zoneSettings ->
                privateProperty(zoneSettings, "routeDelivery")
                    .let { privateProperty(it, "horseSpeed") as Double } shouldBe 0.31
            }
            horseAfter.getAttribute(org.bukkit.attribute.Attribute.MOVEMENT_SPEED)?.baseValue shouldNotBe speedBefore
            horseAfter.getAttribute(org.bukkit.attribute.Attribute.MOVEMENT_SPEED)?.baseValue shouldBe 0.31
            (privateProperty(service, "periodicTaskSupervisor") as RuntimeTaskSupervisor).trackedCount() shouldBe periodicBefore
            service.farmScoreboardTitle(participant.uniqueId) shouldBe "§aReloaded farm"
            PlainTextComponentSerializer.plainText().serialize(
                requireNotNull(participant.scoreboard.getObjective("arcfarms_work")).displayName(),
            ) shouldBe "Reloaded farm"

            val locale = privateProperty(plugin, "locale") as ArcFarmsLocale
            val plain = PlainTextComponentSerializer.plainText()
            val acceptedLocale = plain.serialize(locale.render(MessageKey.RELOAD_OK))
            Config(plugin.dataFolder.toPath(), "lang/ru.yml").also { russian ->
                russian.setString(MessageKey.RELOAD_OK.path, "<red>Не публиковать</red>")
                russian.saveStrict()
            }
            setLocaleValue(plugin.dataFolder.toPath(), "scoreboard.title", "<red>Rejected farm</red>")
            YamlConfiguration.loadConfiguration(configPath.toFile()).also { rejected ->
                rejected.set("farm-zones.communal_farm.bounds.min", listOf(186, 35, 420))
                rejected.save(configPath.toFile())
            }

            participant.performCommand("arcfarms reload") shouldBe true
            paper.performTicks(2)

            plain.serialize(locale.render(MessageKey.RELOAD_OK)) shouldBe acceptedLocale
            runtime(service) shouldBe runtimeBefore
            world.entities.filterIsInstance<Horse>().single().uniqueId shouldBe horseId
            horseAfter.passengers.singleOrNull() shouldBe participant
            service.farmScoreboardTitle(participant.uniqueId) shouldBe "§aReloaded farm"
            plain.serialize(requireNotNull(participant.scoreboard.getObjective("arcfarms_work")).displayName()) shouldBe
                "Reloaded farm"
        } finally {
            unmockkAll()
            paper.close()
        }
    }
})

private fun setLocaleValue(dataRoot: Path, path: String, value: String) {
    listOf("lang/ru.yml", "lang/en.yml").forEach { file ->
        Config(dataRoot, file).also { catalog ->
            catalog.setString(path, value)
            catalog.saveStrict()
        }
    }
}

private fun prepareReloadData(dataRoot: Path) {
    Files.createDirectories(dataRoot.resolve("lang"))
    listOf("lang/ru.yml", "lang/en.yml").forEach { name ->
        val input = requireNotNull(ArcFarmsReloadMockBukkitIntegrationTest::class.java.classLoader.getResourceAsStream(name))
        input.use { Files.copy(it, dataRoot.resolve(name)) }
    }
    val input = requireNotNull(ArcFarmsReloadMockBukkitIntegrationTest::class.java.classLoader.getResourceAsStream("config.yml"))
    val config = input.use { YamlConfiguration.loadConfiguration(InputStreamReader(it)) }
    config.set("network.enabled", false)
    config.set("ui.bossbars", true)
    config.set("ui.particles", false)
    config.set("ui.sounds", false)
    config.set("ui.farm-scoreboard.enabled", true)
    config.set("farm-zones.communal_farm.region", null)
    config.set("farm-zones.communal_farm.bounds.min", listOf(185, 35, 420))
    config.set("farm-zones.communal_farm.bounds.max", listOf(230, 80, 490))
    config.set("lumber-zones.communal_lumbermill.enabled", false)
    config.getConfigurationSection("mine-zones")?.getKeys(false).orEmpty().forEach { id ->
        config.set("mine-zones.$id.enabled", false)
    }
    config.save(dataRoot.resolve("config.yml").toFile())
}

private fun seedFoodDelivery(dataRoot: Path) {
    val stateRepository = ArcFarmsStateRepository(dataRoot)
    stateRepository.saveBlocking(
        ArcFarmsState(
            farms = mapOf(
                "communal_farm" to FarmShiftState(
                    phase = FarmPhase.INCIDENT,
                    sequence = 41,
                    orderId = "bakery_supply",
                    progress = mapOf("WHEAT" to 12, "BEETROOTS" to 4),
                    incidentCrop = "WHEAT",
                    incidentType = FarmIncidentType.FOOD_DELIVERY,
                    incidentProgress = 2,
                    incidentRequired = 12,
                    specialIncident = FarmSpecialIncidentState(routeName = "main"),
                    startedAt = 1,
                ),
            ),
        ),
    )
    stateRepository.close()

    val points = (0..11).map { index ->
        FarmPointPosition("sp11", 201.0 + index * 4.0, 49.0, 453.0)
    }
    FarmRouteRepository(dataRoot).also { repository ->
        repository.saveBlocking(FarmRouteState(routes = mapOf("communal_farm" to FarmDeliveryRoute(points))))
        repository.close()
    }
}

private fun runtime(service: ArcFarmsService): Any {
    val farm = privateProperty(service, "farm")
    val registry = privateProperty(farm, "runtimes")
    return requireNotNull(invoke(registry, "byId", "communal_farm"))
}

private fun privateProperty(target: Any, name: String): Any {
    var type: Class<*>? = target.javaClass
    while (type != null) {
        runCatching { type.getDeclaredField(name) }.getOrNull()?.let { field ->
            field.isAccessible = true
            return requireNotNull(field.get(target)) { "$name is null" }
        }
        type = type.superclass
    }
    error("No field $name on ${target.javaClass.name}")
}

private fun invoke(target: Any, name: String, vararg args: Any?): Any? {
    val method = target.javaClass.methods.firstOrNull { candidate ->
        candidate.name == name && candidate.parameterCount == args.size
    } ?: target.javaClass.declaredMethods.first { candidate ->
        candidate.name == name && candidate.parameterCount == args.size
    }.also { it.isAccessible = true }
    return method.invoke(target, *args)
}
