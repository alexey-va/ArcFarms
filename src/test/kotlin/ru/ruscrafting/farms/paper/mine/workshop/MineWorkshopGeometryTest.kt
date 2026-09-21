package ru.ruscrafting.farms.paper.mine.workshop

import com.google.gson.Gson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.bukkit.Location
import org.joml.Vector3f
import ru.ruscrafting.farms.domain.MineLocations

class MineWorkshopGeometryTest : FunSpec({
    test("authored station anchors stay aligned with the compact map fixture") {
        val stream = requireNotNull(
            MineWorkshopGeometry::class.java.classLoader
                .getResourceAsStream("mine/compact-map-points.json"),
        )
        val locations = stream.use { input ->
            Gson().fromJson(input.reader(Charsets.UTF_8), MineLocations::class.java)
        }
        val workshop = locations.zones.getValue("old_shafts").workshop
        val sourceKeys = mapOf(
            "ore" to "ore_input",
            "crusher" to "ore_crusher",
            "furnace" to "ore_furnace",
            "output" to "ore_output",
            "shipping" to "ore_shipping",
        )

        MineWorkshopGeometry.STATIONS.size shouldBe sourceKeys.size
        sourceKeys.forEach { (role, sourceKey) ->
            val authored = workshop.getValue(sourceKey)
            val runtime = MineWorkshopGeometry.STATIONS.getValue(role)
            runtime.x shouldBe authored.x
            runtime.y shouldBe authored.y
            runtime.z shouldBe authored.z
        }
    }

    test("control centers remain at reachable physical panel positions") {
        val anchor = Location(null, 40.5, 111.0, 17.5)
        val feed = MineWorkshopGeometry.controlLocation(anchor, MineWorkshopMachines.FEED)
        val drive = MineWorkshopGeometry.controlLocation(anchor, MineWorkshopMachines.DRIVE)

        feed.x shouldBe (40.5 plusOrMinus 0.00001)
        feed.y shouldBe (112.0 plusOrMinus 0.00001)
        feed.z shouldBe (20.20 plusOrMinus 0.00001)
        drive.x shouldBe (42.22 plusOrMinus 0.00001)
        drive.y shouldBe (112.48 plusOrMinus 0.00001)
        drive.z shouldBe (19.44 plusOrMinus 0.00001)
        feed.z shouldNotBe drive.z
        MineWorkshopGeometry.captionLocation(drive).y shouldBe (113.28 plusOrMinus .00001)
    }

    test("pour stages hand the billet from the casting path to the rack") {
        val pourEnd = MineWorkshopGeometry.FURNACE_TO_CASTING.last()
        val coolingStart = MineWorkshopGeometry.CASTING_TO_RACK.first()
        pourEnd.x shouldBe coolingStart.x
        pourEnd.z shouldBe coolingStart.z
        // Half-heights differ: liquid and billet meet at the same support level.
        (pourEnd.y - .08f).toDouble() shouldBe (coolingStart.y - .19f).toDouble().plusOrMinus(.0001)
        MineWorkshopGeometry.CASTING_TO_RACK.last().z shouldBe 4.55f

        MineWorkshopAnimation.hotVisible(0f) shouldBe true
        MineWorkshopAnimation.hotVisible(.39f) shouldBe true
        MineWorkshopAnimation.hotVisible(.4f) shouldBe false
        MineWorkshopAnimation.coolingVisible(.4f) shouldBe true
        MineWorkshopAnimation.hotProgress(.4f) shouldBe 1f
        MineWorkshopAnimation.coolingProgress(.4f) shouldBe 0f
        MineWorkshopAnimation.coolingProgress(1f) shouldBe 1f
        MineWorkshopAnimation.feedVisible(.079f) shouldBe false
        MineWorkshopAnimation.feedVisible(.08f) shouldBe true
        MineWorkshopAnimation.feedVisible(.92f) shouldBe true
        MineWorkshopAnimation.feedVisible(.921f) shouldBe false
        MineWorkshopAnimation.feedProgress(.3f, .8f).toDouble() shouldBe (0.1 plusOrMinus .00001)
    }

    test("conveyor cargo path resolves to the line and keeps chunks staggered") {
        fun resolve(point: Vector3f): Vector3f =
            Vector3f(point).mul(.45f).add(2.8f, 0f, 0f)

        val route = MineWorkshopGeometry.CONVEYOR_CARGO.map(::resolve)
        val authoredRoute = MineWorkshopGeometry.CRUSHER_TO_FURNACE
        route.first().x.toDouble() shouldBe (authoredRoute.first().x.toDouble() plusOrMinus .0001)
        route.last().x.toDouble() shouldBe (authoredRoute.last().x.toDouble() plusOrMinus .0001)
        route.first().y.toDouble() shouldBe (authoredRoute.first().y.toDouble() plusOrMinus .0001)
        route.last().y.toDouble() shouldBe (authoredRoute.last().y.toDouble() plusOrMinus .0001)
        route.first().x.toDouble() shouldBe (route.last().x.toDouble() - 5.6 plusOrMinus .0001)

        val cargo = MineWorkshopMachines.previewVisuals("crusher")
            .filter { it.animation == MineWorkshopMachines.Animation.CARGO }
        cargo.size shouldBe 5
        cargo.all { it.path.first().x < it.path.last().x } shouldBe true
        cargo.map { resolve(it.path.first()).x }.distinct().size shouldBe cargo.size
    }

    test("transient reset keeps a two tick hidden window before reappearing") {
        val hidden = MineWorkshopAnimation.hiddenUntil(1_000L)
        MineWorkshopAnimation.canReappear(1_099L, hidden) shouldBe false
        MineWorkshopAnimation.canReappear(1_100L, hidden) shouldBe true
        MineWorkshopAnimation.canReappear(1_100L, null) shouldBe true
    }

    test("casting and rack join at a seam rather than occupying the same volume") {
        val boxes = MineWorkshopModelValidation.sceneBoxes()
        val output = boxes.filter { it.id.startsWith("output:") }
        val rack = boxes.filter { it.id.startsWith("shipping:") }
        val outputEnd = output.maxOf { it.center.z + it.size.z / 2 }
        val rackStart = rack.minOf { it.center.z - it.size.z / 2 }
        (rackStart - outputEnd).toDouble() shouldBe (.02 plusOrMinus .001)
        val pickup = MineWorkshopGeometry.PICKUP_OFFSET
        val rackAnchor = MineWorkshopGeometry.STATIONS.getValue("shipping")
        val outputAnchor = MineWorkshopGeometry.STATIONS.getValue("output")
        (outputAnchor.x + pickup.x) shouldBe rackAnchor.x
        (outputAnchor.z + pickup.z < MineWorkshopGeometry.SERVICE_AISLE_MIN_Z) shouldBe true
        (outputAnchor.z + pickup.z > rackAnchor.z) shouldBe true
    }

    test("workshop contains only the three real levers and no suspended indicator lanterns") {
        val visuals = MineWorkshopGeometry.STATIONS.keys.flatMap(MineWorkshopMachines::previewVisuals)
        val leverControls = visuals.filter { it.part.motion == "lever" }.map { it.control }.toSet()
        leverControls shouldBe setOf(MineWorkshopMachines.DRIVE, MineWorkshopMachines.AIR, MineWorkshopMachines.TAP)
        visuals.none { it.part.material == org.bukkit.Material.SEA_LANTERN } shouldBe true
        visuals.none { it.part.motion == "signal" } shouldBe true
    }

    test("runtime workshop validator samples scaled composite poses") {
        MineWorkshopModelValidation.samples.isNotEmpty() shouldBe true
        MineWorkshopModelValidation.samples.any { it.role == "crusher" && it.transfer == .5f } shouldBe true
        MineWorkshopModelValidation.samples.any { it.role == "output" && it.pouring == .4f } shouldBe true
        MineWorkshopModelValidation.boxes(MineWorkshopModelValidation.Sample("output", pouring = .6f))
            .isNotEmpty() shouldBe true
    }
})
