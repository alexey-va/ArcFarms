package ru.ruscrafting.farms.paper.lumber.scene

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import ru.ruscrafting.farms.domain.lumber.*
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.domain.worksite.WorksiteCrankState
import ru.ruscrafting.farms.domain.worksite.WorksiteCrankTracker
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetStatus
import ru.ruscrafting.farms.paper.lumber.sawmill.CedarSawmillController
import ru.ruscrafting.farms.paper.worksite.WorksiteWaterJet
import org.bukkit.Location
import kotlin.math.PI
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
        val logCarrierRoute = fixture.getAsJsonArray("logCarrierRoute").map { pose(it.asJsonArray) }
        val warehouse = pose(fixture.getAsJsonArray("warehouse"))
        val player = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val branchPositions = LumberSceneGeometry.branchTargets(tree).map { WorksitePosition(world, it.x.toInt(), it.y.toInt(), it.z.toInt()) }
        var state = LumberEngine.start(LumberState(), tree, home, 0)
        val frames = JsonArray()
        val phases = JsonArray()
        var previous = ""
        val firstSaw = 1460 + LumberTiming.SAWMILL_LOAD_TICKS
        val secondSaw = firstSaw + LumberTiming.SAW_PASS_TICKS + 40
        val craneStart = secondSaw + LumberTiming.SAW_PASS_TICKS + LumberTiming.OUTPUT_SETTLE_TICKS
        val deliver = craneStart + LumberTiming.CRANE_TRANSFER_TICKS
        val end = deliver + 80
        var deliveredAt: Long? = null
        var jamBoundary: LumberState? = null
        var smolderBoundary: LumberState? = null
        var acceptedChopAt: Long? = null
        for (tick in 0..end) {
            val now = tick * 50L
            val previousRounds = state.completedRounds
            state = LumberEngine.tick(state, now, 60, branchPositions)
            if (state.completedRounds > previousRounds) deliveredAt = now
            if (state.phase == LumberPhase.SAWING && state.sawPasses == 1 && !state.sawRunning) jamBoundary = state
            if (state.phase == LumberPhase.COLLECTING_BOARDS && !state.boardCartLoaded) smolderBoundary = state
            val action = when (tick) {
                12, 24, 36, 48, 60 -> LumberInput(LumberAction.CHOP_TREE)
                180 -> LumberInput(LumberAction.CUT_BRANCH, 0)
                240 -> LumberInput(LumberAction.CUT_BRANCH, 1)
                300 -> LumberInput(LumberAction.CUT_BRANCH, 2)
                340 -> LumberInput(LumberAction.LOAD_CART)
                1460 -> LumberInput(LumberAction.LOAD_SAWMILL)
                firstSaw, secondSaw -> LumberInput(LumberAction.SAW_LEVER)
                else -> null
            }
            if (action != null) {
                val previousHits = state.chopHits
                state = LumberEngine.act(state, action, player, now, 5)
                if (state.chopHits > previousHits) acceptedChopAt = now
            }
            val logCarrier = if (tick in 340 until 1460) route(logCarrierRoute, (tick - 340) / 1119.0) else null
            if (logCarrier != null) state = state.copy(cart = LumberLogMotion.advance(requireNotNull(state.cart),
                logCarrier, LumberSceneGeometry.LOG_FOLLOW_DISTANCE))
            if (tick % 2 != 0) continue
            val age = ((now - state.phaseStartedAt) / 50L).toInt()
            val view = LumberVisualView(state, age, setOf(player), if (state.sawRunning) age else 0, now,
                chopHitAgeTicks = acceptedChopAt?.let { ((now - it) / 50L).toInt() } ?: Int.MAX_VALUE)
            val label = state.phase.name + if (state.phase == LumberPhase.SAWING) ":${state.sawPasses}:${state.sawRunning}" else ""
            if (label != previous) { phases.add(Gson().toJsonTree(mapOf("at" to tick / 20.0, "phase" to label))); previous = label }
            val sceneParts = LumberSceneGeometry.sample(view, warehouse, deliveredAt?.let { (now - it) / 4000.0 } ?: 1.0).toMutableList()
            if (state.completedRounds > 0) sceneParts += LumberSceneGeometry.storedBoards(warehouse)
            frames.add(frame(view, sceneParts, logCarrier))
        }
        check(state.phase == LumberPhase.IDLE && state.completedRounds == 1L && state.roundId == 1L) { "Preview must finish exactly one genuine round" }
        val document = JsonObject().apply {
            addProperty("schema", 1); addProperty("seconds", end / 20.0); addProperty("step", .1)
            add("fixture", fixture); add("beats", phases); add("frames", frames)
            add("incidents", JsonObject().apply {
                add("feed-jam", incident(requireNotNull(jamBoundary), LumberIncidentType.FEED_JAM, fixture, warehouse, player))
                add("sawdust-smolder", incident(requireNotNull(smolderBoundary), LumberIncidentType.SAWDUST_SMOLDER, fixture, warehouse, player))
            })
            add("evidence", Gson().toJsonTree(listOf("LumberEngine transitions", "LumberLogMotion follower", "LumberSceneGeometry poses", "CedarSawmillController frames", "same five native models", "representative path fixture; not native-client acceptance")))
        }
        Files.writeString(output, Gson().toJson(document))
        println("Exported one complete lumber round / ${frames.size()} source frames to $output")
    }

    private fun incident(baseline: LumberState, type: LumberIncidentType, fixture: JsonObject, warehouse: LumberPose, player: UUID): JsonObject {
        val targets = LumberSceneGeometry.smolderTargets()
        val positions = targets.map { WorksitePosition(it.world, it.x.toInt(), it.y.toInt(), it.z.toInt()) }
        var state = LumberIncidentEngine.start(baseline, type, positions, force = true)
        val generation = requireNotNull(state.incident).generation
        val frames = JsonArray(); val beats = JsonArray()
        var previous = ""
        var physical: WorksiteCrankState? = null
        var radians = 0.0
        var deliveredAt: Long? = null
        val end = if (type == LumberIncidentType.FEED_JAM) 340 else 600
        for (tick in 0..end) {
            val previousRounds = state.completedRounds
            state = LumberEngine.tick(state, tick * 50L, LumberTiming.FALL_TICKS, emptyList())
            if (state.completedRounds > previousRounds) deliveredAt = tick * 50L
            val at = LumberSceneGeometry.incidentCrank()
            val angle = ((tick - 40) / 120.0).coerceIn(0.0, 2.0) * PI * 2
            val approachRadius = 2.3 - .95 * (tick / 40.0).coerceIn(0.0, 1.0)
            val actor = if (type == LumberIncidentType.FEED_JAM)
                at.copy(x = at.x + cos(angle) * approachRadius, z = at.z + sin(angle) * approachRadius, yaw = Math.toDegrees(angle).toFloat() + 90f)
            else at.copy(x = 188.9, z = 170.0 + ((tick - 40) / 180.0).coerceIn(0.0, 1.0) * 5, yaw = 90f)
            var sprayAt: LumberPose? = null
            if (type == LumberIncidentType.FEED_JAM && tick >= 40 && state.incident != null) {
                if (tick == 40) state = LumberIncidentEngine.startCrank(state, player)
                val sample = WorksiteCrankTracker.sample(physical, actor.x, actor.z, at.x, at.z,
                    LumberSceneGeometry.CRANK_INNER_RADIUS, LumberSceneGeometry.CRANK_OUTER_RADIUS, maxStepDistance = .8)
                physical = sample.state
                radians += sample.acceptedRadians
                if (radians + 1e-6 >= PI * 2) {
                    state = LumberIncidentEngine.completeTurn(state, player, generation, tick * 50L)
                    radians = (radians - PI * 2).coerceAtLeast(0.0)
                }
            }
            if (type == LumberIncidentType.SAWDUST_SMOLDER) {
                val shot = listOf(60, 140, 220).indexOfFirst { tick in it..it + 6 }
                if (shot >= 0) sprayAt = targets[shot]
                if (tick in listOf(60, 140, 220)) {
                    val target = targets[listOf(60, 140, 220).indexOf(tick)]
                    val start = Location(null, actor.x, actor.y + 1.62, actor.z)
                    val direction = Location(null, target.x, target.y, target.z).toVector().subtract(start.toVector()).normalize()
                    val hits = WorksiteWaterJet.hitTargets(targets.mapIndexed { index, p -> "pile_$index" to Location(null, p.x, p.y, p.z) },
                        start, direction, LumberSceneGeometry.HOSE_RANGE, .45)
                    check(hits.isNotEmpty()) { "Representative hose ray must reach its target" }
                    hits.forEach { state = LumberIncidentEngine.extinguish(state, it, player, generation, tick * 50L) }
                }
            }
            if (tick % 2 != 0) continue
            val age = ((tick * 50L - state.phaseStartedAt) / 50L).toInt().coerceAtLeast(0)
            val view = LumberVisualView(state, age, setOf(player), 0, tick * 50L, physical?.angle ?: 0.0)
            val label = state.incident?.type?.name ?: state.phase.name
            if (label != previous) { beats.add(Gson().toJsonTree(mapOf("at" to tick / 20.0, "phase" to label))); previous = label }
            val parts = LumberSceneGeometry.sample(view, warehouse, deliveredAt?.let { (tick * 50L - it) / 4000.0 } ?: 1.0).toMutableList()
            if (state.completedRounds > baseline.completedRounds) parts += LumberSceneGeometry.storedBoards(warehouse)
            frames.add(frame(view, parts, eventActor = actor).apply {
                add("sprayAt", Gson().toJsonTree(sprayAt))
            })
        }
        check(state.incident == null && state.roundId == baseline.roundId)
        if (type == LumberIncidentType.FEED_JAM) check(state.sawPasses == baseline.sawPasses && state.phase == baseline.phase)
        else check(state.phase == LumberPhase.IDLE && state.completedRounds == baseline.completedRounds + 1)
        return JsonObject().apply {
            addProperty("seconds", end / 20.0); addProperty("step", .1)
            add("fixture", fixture); add("frames", frames); add("beats", beats)
        }
    }

    private fun frame(view: LumberVisualView, parts: List<LumberSceneGeometry.Part>, logCarrier: LumberPose? = null,
                      boardCarrier: LumberPose? = null, eventActor: LumberPose? = null): JsonObject {
        val state = view.state
        val machine = CedarSawmillController.frame(view)
        return JsonObject().apply {
            addProperty("t", view.now / 1000.0); addProperty("phase", state.phase.name); addProperty("phaseAge", view.phaseAgeTicks)
            addProperty("round", state.roundId); addProperty("passes", state.sawPasses); addProperty("running", state.sawRunning)
            addProperty("branches", state.branches?.completed ?: 0); addProperty("boardCartLoaded", state.boardCartLoaded)
            addProperty("maskTree", LumberSceneGeometry.maskedTreeBlocks(state).isNotEmpty())
            addProperty("maskStump", state.phase == LumberPhase.CHOPPING)
            addProperty("chopHits", state.chopHits); addProperty("chopHitAgeTicks", view.chopHitAgeTicks)
            addProperty("incident", state.incident?.type?.name); addProperty("crankHeld", state.incident?.crankOperator != null)
            addProperty("crankTurns", state.incident?.crankTurns ?: 0)
            addProperty("hotspotsCompleted", state.incident?.hotspots?.targets?.count { it.status == ObjectiveTargetStatus.COMPLETED } ?: 0)
            add("cart", Gson().toJsonTree(state.cart)); add("logCarrier", Gson().toJsonTree(logCarrier))
            add("leash", Gson().toJsonTree(if (state.cartCarrier != null) state.cart?.let(LumberSceneGeometry::leashAnchor) else null))
            add("boardCarrier", Gson().toJsonTree(boardCarrier)); add("eventActor", Gson().toJsonTree(eventActor))
            add("parts", Gson().toJsonTree(parts.map(::part)))
            add("machineGlowing", Gson().toJsonTree(CedarSawmillController.highlightedParts(view)))
            add("machine", Gson().toJsonTree(machine.poses.map { p -> listOf(p.visible, p.center.x, p.center.y, p.center.z,
                p.size.x, p.size.y, p.size.z, p.rotation.x, p.rotation.y, p.rotation.z, p.rotation.w) }))
        }
    }

    private fun part(p: LumberSceneGeometry.Part) = mapOf("id" to p.id,
        "center" to listOf(p.center.x, p.center.y, p.center.z), "size" to listOf(p.size.x, p.size.y, p.size.z),
        "rotation" to listOf(p.rotation.x, p.rotation.y, p.rotation.z, p.rotation.w),
        "block" to p.block, "itemModel" to p.itemModel, "visible" to p.visible,
        "glowing" to (p.action != null || p.highlight))

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
