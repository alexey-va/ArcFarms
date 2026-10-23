package ru.ruscrafting.farms.paper.farm.presentation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.World
import org.bukkit.entity.Interaction
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.particle.PaperViewerParticles
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.FarmZoneSettings
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.paper.ActivityRegion
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.platform.PaperFarmTextDisplayRenderer
import ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort
import ru.ruscrafting.farms.paper.worksite.WorksiteAudiencePort
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort
import java.util.UUID

class FarmPortalPacketVisualTest : FunSpec({
    test("portal particles retain viewer-local ring frame and field geometry") {
        val world = mockk<World>()
        val portal = mockk<Interaction> {
            every { location } answers { Location(world, 12.5, 65.0, 12.5) }
            every { interactionWidth } returns 3.6f
            every { interactionHeight } returns 3.0f
        }
        val viewer = mockk<Player>()
        val particles = mockk<PaperViewerParticles>(relaxed = true)
        FarmPortalRenderer.render(portal, listOf(viewer), particles)
        verify(exactly = 74) { particles.emit(viewer, any(), Particle.DUST, 1, 0.0, 0.0, 0.0, 0.0, any()) }
        verify(exactly = 1) {
            particles.emit(viewer, match { it.x == 13.65 && it.y == 65.12 && it.z == 12.5 },
                Particle.DUST, 1, 0.0, 0.0, 0.0, 0.0,
                match<Particle.DustOptions> { it.color.asRGB() == 0xc778ff && it.size == 1.15f })
        }
        verify(exactly = 1) {
            particles.emit(viewer, match { it.x == 12.5 && it.y == 66.5 && it.z == 12.5 }, Particle.REVERSE_PORTAL,
                24, 3.6f * 0.35, 3.0f * 0.38, 3.6f * 0.35, 0.035)
        }
    }

    test("label refresh retains packet handle and native portal identity until clear") {
        mockkStatic(Bukkit::class)
        try {
            val plugin = mockk<Plugin> {
                every { name } returns "FarmPortalTest"
                every { namespace() } returns "farmportaltest"
            }
            val world = mockk<World>(relaxed = true) {
                every { name } returns "farm"
                every { isChunkLoaded(any<Int>(), any<Int>()) } returns true
                every { entities } returns emptyList()
            }
            val inputId = UUID.randomUUID()
            val portal = mockk<Interaction>(relaxed = true) {
                every { uniqueId } returns inputId
                every { isValid } returns true
            }
            every { world.spawn(any<Location>(), Interaction::class.java) } returns portal
            every { Bukkit.getWorld("farm") } returns world
            every { Bukkit.getWorlds() } returns listOf(world)
            every { Bukkit.getEntity(inputId) } returns portal
            every { Bukkit.getConsoleSender() } returns mockk(relaxed = true)
            val label = mockk<PacketTextDisplay>(relaxed = true) {
                every { isValid } returns true
                every { transformation } answers { Transformation(Vector3f(), Quaternionf(), Vector3f(1f), Quaternionf()) }
            }
            val displays = mockk<PaperPacketDisplays> { every { spawnText(any(), any()) } returns label }
            val locale = mockk<ArcFarmsLocale>(relaxed = true) {
                every { render(any(), any(), any()) } returns Component.text("Portal")
            }
            val settings = mockk<FarmZoneSettings> { every { id } returns "farm" }
            val region = mockk<ActivityRegion> { every { this@mockk.world } returns world }
            val runtime = FarmRuntime(settings, region, emptyMap(), emptyList(), mockk(),
                FarmShiftState(phase = FarmPhase.INCIDENT, sequence = 4, incidentType = FarmIncidentType.FOOD_DELIVERY))
            val owner = FarmActivityPortal(plugin, locale, mockk<WorksiteAccessPort>(), mockk<WorksiteAudiencePort>(),
                mockk<WorksiteTaskPort>(), PaperFarmTextDisplayRenderer, "test_portal",
                displays = displays, viewerParticles = mockk())
            val point = FarmPointPosition("farm", 12.5, 65.0, 12.5)
            val style = FarmPortalStyle(3.6f, 3.0f, 3.35, 1f, 3, 2f)
            val destination = object : FarmPortalDestination { override fun enter(player: Player) = true }
            repeat(2) { owner.ensure(runtime, point, style, MessageKey.FARM_ROUTE_PORTAL_LABEL, destination) }
            verify(exactly = 1) { world.spawn(any<Location>(), Interaction::class.java) }
            verify(exactly = 1) { displays.spawnText(any(), any()) }
            verify(exactly = 2) { portal.interactionWidth = 3.6f }
            verify(exactly = 2) { label.teleport(match { it.y == 68.35 && it.yaw == 0f && it.pitch == 0f }) }
            owner.clear("farm")
            verify(exactly = 1) { portal.remove() }
            verify(exactly = 1) { label.remove() }
        } finally { unmockkStatic(Bukkit::class) }
    }
})
