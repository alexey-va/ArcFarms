package ru.ruscrafting.farms.hellgreenhouse

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.command.Command
import org.bukkit.entity.Player
import ru.arc.config.Config
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.paper.ArcFarmsCommand
import ru.ruscrafting.farms.paper.ArcFarmsMenu
import ru.ruscrafting.farms.paper.ArcFarmsService
import java.nio.file.Files
import java.nio.file.Path

/** Red contract tests for the HELL_GREENHOUSE config, admin and locale seam. */
class HellGreenhouseContractTest : FunSpec({
    test("bundled greenhouse defaults and incident pools are present") {
        val root = resourceRoot()
        val config = Config(root, "config.yml")
        config.intOrNull("farm-zones.communal_farm.special-incidents.hell-greenhouse.quota") shouldBe 4
        config.intOrNull("farm-zones.communal_farm.special-incidents.hell-greenhouse.grow-seconds") shouldBe 6
        config.intOrNull("farm-zones.communal_farm.special-incidents.hell-greenhouse.hot-seconds") shouldBe 6
        config.intOrNull("farm-zones.communal_farm.special-incidents.hell-greenhouse.heat-limit") shouldBe 100
        config.intOrNull("farm-zones.communal_farm.special-incidents.hell-greenhouse.heat-per-harvest") shouldBe 10
        config.intOrNull("farm-zones.communal_farm.special-incidents.hell-greenhouse.evacuation-seconds") shouldBe 15

        ArcFarmsConfig.inspect(root).farms.single().incidentTypes.map { it.name } shouldContain "HELL_GREENHOUSE"
    }

    test("greenhouse config rejects quota outside 1..8") {
        val root = resourceRoot()
        Config(root, "config.yml").apply {
            setInt("farm-zones.communal_farm.special-incidents.hell-greenhouse.quota", 9)
            saveStrict()
        }
        shouldThrow<IllegalArgumentException> { ArcFarmsConfig.inspect(root) }
    }

    test("admin event alias starts the greenhouse stage") {
        val player = mockk<Player>(relaxed = true) { every { hasPermission("arcfarms.admin") } returns true }
        val service = mockk<ArcFarmsService>(relaxed = true) { every { adminSetFarmStage(any(), any(), any()) } returns true }
        val command = ArcFarmsCommand(service, mockk(relaxed = true), mockk<ArcFarmsMenu>(relaxed = true)) { Result.success(Unit) }

        command.onCommand(player, mockk<Command>(relaxed = true), "arcfarms", arrayOf("admin", "event", "communal_farm", "hell-greenhouse"))

        verify(exactly = 1) { service.adminSetFarmStage(player, "communal_farm", "hell-greenhouse") }
    }

    test("both locale catalogs contain the greenhouse message family") {
        val root = resourceRoot()
        val settings = ArcFarmsConfig.inspect(root)
        ArcFarmsLocale.requiredPaths(settings).filter { it.contains("hell-greenhouse") }.isNotEmpty() shouldBe true
        listOf("ru", "en").forEach { language ->
            val locale = Config(root, "lang/$language.yml")
            listOf(
                "farm.entry-hell-greenhouse",
                "farm.hell-greenhouse.started",
                "farm.hell-greenhouse.started-subtitle",
                "farm.hell-greenhouse.bossbar",
                "farm.hell-greenhouse.required",
                "farm.hell-greenhouse.picked",
                "farm.hell-greenhouse.cooled",
                "farm.hell-greenhouse.too-early",
                "farm.hell-greenhouse.hands-full",
                "farm.hell-greenhouse.empty-hands",
                "farm.hell-greenhouse.hot-burst",
                "farm.hell-greenhouse.evacuate",
                "farm.hell-greenhouse.quota-required",
                "farm.hell-greenhouse.success",
                "farm.hell-greenhouse.partial",
                "farm.hell-greenhouse.timeout",
                "farm.hell-greenhouse.vat",
                "farm.hell-greenhouse.exit",
                "farm.hell-greenhouse.pepper",
                "incident.hell-greenhouse.name",
            ).forEach { key -> locale.stringOrNull(key).orEmpty().isNotBlank() shouldBe true }
        }
    }
}) {
    companion object {
        private fun resourceRoot(): Path {
            val project = Path.of(requireNotNull(System.getProperty("arcfarms.projectDir")))
            val root = Files.createTempDirectory("arcfarms-hell-greenhouse")
            Files.copy(project.resolve("src/main/resources/config.yml"), root.resolve("config.yml"))
            Files.createDirectories(root.resolve("lang"))
            listOf("ru", "en").forEach { language ->
                Files.copy(project.resolve("src/main/resources/lang/$language.yml"), root.resolve("lang/$language.yml"))
            }
            root.toFile().walkBottomUp().forEach { it.deleteOnExit() }
            return root
        }
    }
}
