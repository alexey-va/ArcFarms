package ru.ruscrafting.farms.paper.lumber.scene

import com.google.gson.JsonParser
import io.kotest.core.spec.style.FunSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.util.BoundingBox
import org.bukkit.util.VoxelShape
import org.joml.Vector3d
import org.joml.Vector3f
import ru.arc.paper.display.DisplayOrientedVolume
import ru.arc.paper.scene.PaperSpatialSpace
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.domain.lumber.LumberPhase
import ru.ruscrafting.farms.domain.lumber.LumberPose
import ru.ruscrafting.farms.domain.lumber.LumberState
import ru.ruscrafting.farms.domain.lumber.LumberTiming
import ru.ruscrafting.farms.domain.lumber.LumberVisualView
import ru.ruscrafting.farms.paper.lumber.LumberCartClearance
import java.nio.file.Files
import java.nio.file.Path
import java.lang.reflect.Proxy
import kotlin.math.ceil
import kotlin.math.hypot

/** Real captured solids, with native top-slab/doorway shapes; all other solids use a conservative full cube. */
class LumberCapturedRouteTest : FunSpec({
    test("captured south approach clears conservative blocks and the exact shared station boundary") {
        MockBukkitTestRuntime.open().use {
            val fixture = JsonParser.parseString(Files.readString(Path.of("scripts/sawmill-preview/lumber-tree.snapshot.json"))).asJsonObject
            val captured = JsonParser.parseString(Files.readString(Path.of("scripts/sawmill-preview/lumber-area.snapshot.json"))).asJsonObject
            val readback = JsonParser.parseString(Files.readString(Path.of("scripts/sawmill-preview/lumber-live-readback.json"))).asJsonObject
            check(readback["baseCaptureSha256"] == captured["sourceSha256"])
            val confirmedAir = readback.getAsJsonArray("air").mapTo(hashSetOf()) { it.asJsonArray.let { a ->
                Triple(a[0].asInt, a[1].asInt, a[2].asInt)
            } }
            val worldName = fixture["world"].asString
            val blockStates = captured.getAsJsonArray("blocks").associate { element -> element.asJsonArray.let { a ->
                Triple(a[0].asInt, a[1].asInt, a[2].asInt) to a[3].asString
            } }
            val states = captured.getAsJsonArray("blocks").associate { element -> element.asJsonArray.let { a ->
                Triple(a[0].asInt, a[1].asInt, a[2].asInt) to Material.valueOf(a[3].asString.substringAfter(':').substringBefore('[').uppercase())
            } }.filterKeys { it !in confirmedAir }
            val solidTypes = states.values.distinct().filterTo(hashSetOf()) { it.isSolid }
            val solid = states.filterValues { it in solidTypes }.keys
            fun block(boxes: List<BoundingBox>): Block {
                val shape = Proxy.newProxyInstance(VoxelShape::class.java.classLoader, arrayOf(VoxelShape::class.java)) { _, method, _ ->
                    when (method.name) { "getBoundingBoxes" -> boxes; else -> error("Unexpected shape call ${method.name}") }
                } as VoxelShape
                return Proxy.newProxyInstance(Block::class.java.classLoader, arrayOf(Block::class.java)) { _, method, _ ->
                    when (method.name) { "getCollisionShape" -> shape; else -> error("Unexpected block call ${method.name}") }
                } as Block
            }
            val occupied = block(listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)))
            val topSlab = block(listOf(BoundingBox(0.0, .5, 0.0, 1.0, 1.0, 1.0)))
            val topTrapdoor = block(listOf(BoundingBox(0.0, .8125, 0.0, 1.0, 1.0, 1.0)))
            // Native straight east/top stair: upper half plus east lower quarter.
            val eastTopStair = block(listOf(BoundingBox(0.0, .5, 0.0, 1.0, 1.0, 1.0),
                BoundingBox(.5, 0.0, 0.0, 1.0, .5, 1.0)))
            val empty = block(emptyList())
            val solidVisits = ArrayDeque<String>()
            val world = Proxy.newProxyInstance(World::class.java.classLoader, arrayOf(World::class.java)) { _, method, args ->
                when (method.name) {
                    "getName" -> worldName
                    "getMinHeight" -> 71
                    "getMaxHeight" -> 93
                    "isChunkLoaded" -> true
                    "getBlockAt" -> {
                        val x = args[0] as Int; val y = args[1] as Int; val z = args[2] as Int
                        check(x in 135..235 && y in 71..92 && z in 160..220) { "Route escapes captured evidence at $x,$y,$z" }
                        if (Triple(x,y,z) in solid) {
                            solidVisits.addLast("$x,$y,$z:${states[Triple(x,y,z)]}")
                            if (solidVisits.size > 6) solidVisits.removeFirst()
                            val state = blockStates.getValue(Triple(x,y,z))
                            when {
                                "_slab[type=top," in state -> topSlab
                                "_trapdoor[" in state && "half=top,open=false" in state -> topTrapdoor
                                "_stairs[facing=east,half=top,shape=straight," in state -> eastTopStair
                                else -> occupied
                            }
                        } else empty
                    }
                    "hashCode" -> 15
                    "toString" -> worldName
                    else -> error("Unexpected world call ${method.name}")
                }
            } as World
            mockkStatic(Bukkit::class)
            try {
                every { Bukkit.getWorld(worldName) } returns world
                every { Bukkit.isPrimaryThread() } returns true
                val route = fixture.getAsJsonArray("cartRoute").map { it.asJsonArray.let { a ->
                    LumberPose(worldName, a[0].asDouble, a[1].asDouble, a[2].asDouble, a[3].asFloat)
                } }
                val oracle = LumberCartClearance()
                for ((index, pair) in route.zipWithNext().withIndex()) {
                    val steps = ceil(maxOf(hypot(pair.second.x - pair.first.x, pair.second.z - pair.first.z) * 2, 45.0)).toInt()
                    var previous = pair.first
                    for (step in 1..steps) {
                        val next = LumberSceneGeometry.blend(pair.first, pair.second, step.toDouble() / steps)
                        check(oracle.allows(previous, next)) { "Captured route blocked: leg=$index step=$step/$steps pose=$next solids=$solidVisits" }
                        previous = next
                    }
                }
                // The cart path does not prove the raised stock clears the existing doorway.
                // Sample the actual loading choreography, sweeping the full native 7.48m hull
                // between every adjacent server tick (including the exact carriage endpoint).
                val space = PaperSpatialSpace()
                var previous: LumberSceneGeometry.Part? = null
                for (tick in 0..LumberTiming.SAWMILL_LOAD_TICKS) {
                    val view = LumberVisualView(LumberState(phase = LumberPhase.LOADING_SAWMILL,
                        cart = LumberSceneGeometry.sawCartDock()), tick, emptySet(), 0, 0)
                    val stock = LumberSceneGeometry.sample(view).first { it.id == "timber-2-raw" }
                    fun Vector3f.double() = Vector3d(x.toDouble(), y.toDouble(), z.toDouble())
                    val start = previous ?: stock
                    val volume = DisplayOrientedVolume(start.center.double(),
                        start.rotation.transform(Vector3f(1f, 0f, 0f)).double(),
                        start.rotation.transform(Vector3f(0f, 1f, 0f)).double(),
                        start.rotation.transform(Vector3f(0f, 0f, 1f)).double(),
                        Vector3d(.816, .612, LumberSceneGeometry.STOCK_LENGTH / 2))
                        .swept(stock.center.double().sub(start.center.double()))
                    check(space.clear(world, volume)) {
                        "Captured saw transfer blocked: tick=$tick center=${stock.center} solids=$solidVisits"
                    }
                    previous = stock
                }
            } finally { unmockkStatic(Bukkit::class) }
        }
    }
})
