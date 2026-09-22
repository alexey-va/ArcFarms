package ru.ruscrafting.farms.paper.mine.expedition

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.joml.Vector3f
import ru.arc.paper.api.ArcInspectionProvider
import ru.arc.paper.api.ArcInspectionService
import ru.ruscrafting.farms.config.ArcFarmsLocale

class MineEngineGuideTest : FunSpec({
    test("ARC reload closes the retired source and registers once with the replacement host") {
        val plugin = mockk<Plugin>(relaxed = true)
        val first = mockk<ArcInspectionService>()
        val second = mockk<ArcInspectionService>()
        val oldHandle = mockk<AutoCloseable>(relaxed = true)
        val newHandle = mockk<AutoCloseable>(relaxed = true)
        var current: ArcInspectionService? = first
        every { plugin.server.servicesManager.load(ArcInspectionService::class.java) } answers { current }
        every { first.register(plugin, "mine-engine-guide", 100, any()) } returns oldHandle
        every { second.register(plugin, "mine-engine-guide", 100, any()) } returns newHandle
        val guide = MineEngineGuide(plugin, mockk(), null)

        guide.reconcile()
        guide.reconcile()
        current = second
        guide.reconcile()
        guide.reconcile()
        verify(exactly = 1) { first.register(plugin, "mine-engine-guide", 100, any()); oldHandle.close() }
        verify(exactly = 1) { second.register(plugin, "mine-engine-guide", 100, any()) }
        current = null
        guide.reconcile()
        guide.close()
        verify(exactly = 1) { newHandle.close() }
    }

    test("miss falls through, an unlabelled casing suppresses blocks behind it, and a part explains itself") {
        val plugin = mockk<Plugin>(relaxed = true)
        val service = mockk<ArcInspectionService>()
        val provider = slot<ArcInspectionProvider>()
        every { plugin.server.servicesManager.load(ArcInspectionService::class.java) } returns service
        every { service.register(plugin, "mine-engine-guide", 100, capture(provider)) } returns AutoCloseable {}
        val player = mockk<Player>()
        val markers = mockk<MineExpeditionMarkers>()
        val locale = mockk<ArcFarmsLocale>()
        val part = MineDisplayBlueprints.Part(Material.IRON_BLOCK, Vector3f(), Vector3f(1f), inspection = "piston")
        var hit: MineDisplayInspection.Hit? = null
        every { markers.inspectEngine(player, any()) } answers { hit }
        every { locale.renderPath("mine.expedition.engine-guide.piston.title", player, any()) } returns Component.text("Поршень")
        every { locale.renderPath("mine.expedition.engine-guide.piston.description", player, any()) } returns Component.text("Передаёт усилие шатуну.")
        val guide = MineEngineGuide(plugin, markers, locale)
        guide.reconcile()

        provider.captured.resolve(player) shouldBe null
        hit = MineDisplayInspection.Hit(part.copy(inspection = null), 2f)
        provider.captured.resolve(player)?.suppressesLowerSources shouldBe true
        hit = MineDisplayInspection.Hit(part, 2f)
        val frame = provider.captured.resolve(player)!!
        frame.hologram shouldBe Component.text("Поршень").append(Component.newline()).append(Component.text("Передаёт усилие шатуну."))
        frame.bossbar shouldBe Component.text("Поршень")
        guide.close()
    }
})
