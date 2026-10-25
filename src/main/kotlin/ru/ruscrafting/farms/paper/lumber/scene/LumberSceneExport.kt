package ru.ruscrafting.farms.paper.lumber.scene

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import org.joml.Vector3d
import org.joml.Vector3f
import ru.arc.paper.display.DisplayOrientedVolume
import ru.ruscrafting.farms.domain.lumber.*
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.lumber.sawmill.CedarSawmillController
import kotlin.math.cos
import kotlin.math.sin

/** One representative complete round, sampled from the real domain and display functions at 10 Hz. */
internal object LumberSceneExport {
    fun write(fixturePath: Path, output: Path) {
        val fixture = JsonParser.parseString(Files.readString(fixturePath)).asJsonObject
        val world = fixture["world"].asString
        fun position(value: JsonArray) = WorksitePosition(world, value[0].asInt, value[1].asInt, value[2].asInt)
        fun pose(value: JsonArray) = LumberPose(world, value[0].asDouble, value[1].asDouble, value[2].asDouble, value[3].asFloat)
        fun blocks(key: String) = fixture.getAsJsonArray(key).map { it.asJsonArray.let { a -> LumberBlockSnapshot(position(a), a[3].asString) } }
        val home = pose(fixture.getAsJsonArray("cart"))
        val fall = fixture.getAsJsonArray("fall")
        val tree = LumberTreeSnapshot(position(fixture.getAsJsonArray("root")), blocks("logs"), blocks("leaves"), fall[0].asInt, fall[1].asInt, home)
        val cartRoute = fixture.getAsJsonArray("cartRoute").map { pose(it.asJsonArray) }
        val boardRoute = fixture.getAsJsonArray("boardRoute").map { pose(it.asJsonArray) }
        val player = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val branchPositions = LumberSceneGeometry.branchTargets(tree).map { WorksitePosition(world, it.x.toInt(), it.y.toInt(), it.z.toInt()) }
        var state = LumberEngine.start(LumberState(), tree, home, 0)
        val frames = JsonArray()
        val phases = JsonArray()
        var previous = ""
        for (tick in 0..3400 step 2) {
            val now = tick * 50L
            state = LumberEngine.tick(state, now, 60, branchPositions)
            val action = when (tick) {
                12, 24, 36, 48, 60 -> LumberInput(LumberAction.CHOP_TREE)
                180 -> LumberInput(LumberAction.CUT_BRANCH, 0)
                240 -> LumberInput(LumberAction.CUT_BRANCH, 1)
                300 -> LumberInput(LumberAction.CUT_BRANCH, 2)
                340 -> LumberInput(LumberAction.LOAD_CART)
                400 -> LumberInput(LumberAction.TAKE_CART)
                1460 -> LumberInput(LumberAction.LOAD_SAWMILL)
                1520, 2080 -> LumberInput(LumberAction.SAW_LEVER)
                2640 -> LumberInput(LumberAction.COLLECT_BOARDS)
                3380 -> LumberInput(LumberAction.DELIVER_BOARDS)
                else -> null
            }
            if (action != null) state = LumberEngine.act(state, action, player, now, 5)
            if (tick in 400..1460) state = state.copy(cart = route(cartRoute, (tick - 400) / 1060.0))
            val age = ((now - state.phaseStartedAt) / 50L).toInt()
            val view = LumberVisualView(state, age, setOf(player), if (state.sawRunning) age else 0, now)
            val label = state.phase.name + if (state.phase == LumberPhase.SAWING) ":${state.sawPasses}:${state.sawRunning}" else ""
            if (label != previous) { phases.add(Gson().toJsonTree(mapOf("at" to tick / 20.0, "phase" to label))); previous = label }
            if (state.phase == LumberPhase.HAULING && age >= LumberTiming.CART_LOAD_TICKS) {
                LumberSceneGeometry.cartClearance(requireNotNull(state.cart)).forEach { part ->
                    fun Vector3f.double() = Vector3d(x.toDouble(), y.toDouble(), z.toDouble())
                    val volume = DisplayOrientedVolume(part.center.double(),
                        part.rotation.transform(Vector3f(1f, 0f, 0f)).double(),
                        part.rotation.transform(Vector3f(0f, 1f, 0f)).double(),
                        part.rotation.transform(Vector3f(0f, 0f, 1f)).double(),
                        Vector3d(part.size.x / 2.0, part.size.y / 2.0, part.size.z / 2.0))
                    check(LumberSceneGeometry.clearOfMachine(volume)) { "Cart path crosses machine at ${tick / 20.0}s: ${part.id}" }
                }
            }
            val sceneParts = LumberSceneGeometry.sample(view).toMutableList()
            val carrier = if (state.phase == LumberPhase.DELIVERING) route(boardRoute, ((tick - 2660) / 720.0).coerceIn(0.0, 1.0)) else null
            if (carrier != null) {
                val yaw = Math.toRadians(carrier.yaw.toDouble())
                val target = carrier.copy(x = carrier.x - sin(yaw) * .65 + cos(yaw) * 1.15,
                    y = carrier.y + 1.8, z = carrier.z + cos(yaw) * .65 + sin(yaw) * 1.15, yaw = carrier.yaw + 180f)
                sceneParts += LumberSceneGeometry.boards(LumberSceneGeometry.blend(LumberSceneGeometry.outputPickup(), target, age / 20.0, .9))
            }
            val machine = CedarSawmillController.frame(view)
            frames.add(JsonObject().apply {
                addProperty("t", tick / 20.0); addProperty("phase", state.phase.name); addProperty("phaseAge", age)
                addProperty("round", state.roundId); addProperty("passes", state.sawPasses); addProperty("running", state.sawRunning)
                addProperty("branches", state.branches?.completed ?: 0)
                addProperty("maskTree", state.phase !in setOf(LumberPhase.IDLE, LumberPhase.CHOPPING))
                add("cart", Gson().toJsonTree(state.cart)); add("carrier", Gson().toJsonTree(carrier))
                add("parts", Gson().toJsonTree(sceneParts.map(::part)))
                add("machine", Gson().toJsonTree(machine.poses.map { p -> listOf(p.visible, p.center.x, p.center.y, p.center.z,
                    p.size.x, p.size.y, p.size.z, p.rotation.x, p.rotation.y, p.rotation.z, p.rotation.w) }))
            })
        }
        check(state.phase == LumberPhase.IDLE && state.completedRounds == 1L && state.roundId == 1L) { "Preview must finish exactly one genuine round" }
        val document = JsonObject().apply {
            addProperty("schema", 1); addProperty("seconds", 170); addProperty("step", .1)
            add("fixture", fixture); add("beats", phases); add("frames", frames)
            add("evidence", Gson().toJsonTree(listOf("LumberEngine transitions", "LumberSceneGeometry poses", "CedarSawmillController frames", "same five native models", "representative path fixture; not native-client acceptance")))
        }
        Files.writeString(output, Gson().toJson(document))
        println("Exported one complete lumber round / ${frames.size()} source frames to $output")
    }

    private fun part(p: LumberSceneGeometry.Part) = mapOf("id" to p.id,
        "center" to listOf(p.center.x, p.center.y, p.center.z), "size" to listOf(p.size.x, p.size.y, p.size.z),
        "rotation" to listOf(p.rotation.x, p.rotation.y, p.rotation.z, p.rotation.w),
        "block" to p.block, "itemModel" to p.itemModel, "visible" to p.visible,
        "branchTarget" to (p.action?.action == LumberAction.CUT_BRANCH))

    private fun route(points: List<LumberPose>, progress: Double): LumberPose {
        val weights = points.zipWithNext().map { (a, b) ->
            val dx = b.x - a.x; val dy = b.y - a.y; val dz = b.z - a.z
            val yaw = kotlin.math.abs(((b.yaw - a.yaw + 540) % 360) - 180)
            kotlin.math.sqrt(dx * dx + dy * dy + dz * dz) + Math.toRadians(yaw.toDouble()) * 1.5
        }
        var distance = progress.coerceIn(0.0, 1.0) * weights.sum()
        for ((index, weight) in weights.withIndex()) {
            if (distance <= weight || index == weights.lastIndex)
                return LumberSceneGeometry.blend(points[index], points[index + 1], if (weight > 0) distance / weight else 1.0)
            distance -= weight
        }
        return points.last()
    }
}
