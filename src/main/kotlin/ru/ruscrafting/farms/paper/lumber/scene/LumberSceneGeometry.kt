package ru.ruscrafting.farms.paper.lumber.scene

import org.joml.Quaternionf
import org.joml.Vector3f
import ru.ruscrafting.farms.domain.lumber.LumberAction
import ru.ruscrafting.farms.domain.lumber.LumberInput
import ru.ruscrafting.farms.domain.lumber.LumberPhase
import ru.ruscrafting.farms.domain.lumber.LumberPose
import ru.ruscrafting.farms.domain.lumber.LumberState
import ru.ruscrafting.farms.domain.lumber.LumberTreeSnapshot
import ru.ruscrafting.farms.domain.lumber.LumberVisualView
import ru.ruscrafting.farms.domain.lumber.LumberTiming
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetStatus
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.lumber.sawmill.CedarSawmillAnimation
import ru.ruscrafting.farms.paper.lumber.sawmill.CedarSawmillGeometry
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** The canonical, source-exportable choreography. Positions are world-space cuboid/model centres. */
internal object LumberSceneGeometry {
    const val FALL_TICKS = LumberTiming.FALL_TICKS
    private const val FALL_TURN_TICKS = FALL_TICKS * 11 / 15
    const val LOG_FOLLOW_DISTANCE = 5.25
    const val BOARD_CART_FOLLOW_DISTANCE = 4.8
    private const val BOARD_CART_DECK_HEIGHT = .10
    const val RECEIVING_RADIUS = 3.0
    const val BRANCH_COUNT = 3
    val STOCK_LENGTH = CedarSawmillAnimation.LOG_LENGTH * CedarSawmillGeometry.SCENE_SCALE
    private val raw = CedarSawmillAnimation.sample(0.0).poses.filter { it.partId.endsWith("-raw") }
    private val output = CedarSawmillAnimation.sample(25.0, 1).poses.filter { pose ->
        pose.visible && CedarSawmillGeometry.parts.first { it.id == pose.partId }.motion.startsWith("timber-")
    }
    private val rawCenter = Vector3f(0f, (CedarSawmillAnimation.LOG_BOTTOM + CedarSawmillAnimation.LOG_HEIGHT / 2).toFloat(), -5.8f)
        .mul(CedarSawmillGeometry.SCENE_SCALE).rotate(CedarSawmillGeometry.sceneRotation).add(CedarSawmillGeometry.origin)
    private val outputCenter = Vector3f(CedarSawmillAnimation.OUTPUT_X.toFloat(), (CedarSawmillAnimation.LOG_BOTTOM + .175 + .28).toFloat(), 3.6f)
        .mul(CedarSawmillGeometry.SCENE_SCALE).rotate(CedarSawmillGeometry.sceneRotation).add(CedarSawmillGeometry.origin)

    data class Part(
        val id: String, val center: Vector3f, val size: Vector3f, val rotation: Quaternionf = Quaternionf(),
        val block: String? = null, val itemModel: String? = null, val visible: Boolean = true,
        val action: LumberInput? = null,
    )

    fun sawIntake(): LumberPose = pose(rawCenter, CedarSawmillGeometry.sceneYaw)
    fun receivingCenter(): LumberPose = LumberPose("rc_atelier_cedar_sawmill", 189.5, 73.0, 199.5)
    fun groundStock(tree: LumberTreeSnapshot): LumberPose = felledStock(tree).copy(y = tree.root.y.toDouble())
    fun draggedStock(at: LumberPose): LumberPose = at.copy(y = at.y + .612)
    fun leashAnchor(at: LumberPose): LumberPose {
        val yaw = Math.toRadians(at.yaw.toDouble())
        return at.copy(x = at.x - sin(yaw) * 3.5, y = at.y + .25, z = at.z + cos(yaw) * 3.5)
    }
    fun outputPickup(): LumberPose = pose(outputCenter, CedarSawmillGeometry.sceneYaw)
    fun boardCartHome(): LumberPose = LumberPose("rc_atelier_cedar_sawmill", 194.6, 73.0, 172.68, 180f)
    fun boardCartStock(at: LumberPose): LumberPose = at.copy(y = at.y + BOARD_CART_DECK_HEIGHT + (.175 + .28) * CedarSawmillGeometry.SCENE_SCALE)
    fun boardCartPickup(state: LumberState): LumberPose =
        if (state.boardCartLoaded) boardCartStock(state.cart ?: boardCartHome()) else outputPickup()
    fun boardLoadingStock(anchor: LumberPose, progress: Double): LumberPose {
        val end = boardCartStock(anchor)
        val above = end.copy(y = outputPickup().y)
        return if (progress < .55) blend(outputPickup(), above, progress / .55, .15)
        else blend(above, end, (progress - .55) / .45)
    }
    fun warehouseStock(at: LumberPose): LumberPose = at.copy(
        y = at.y + .16 + (.175 + .28) * CedarSawmillGeometry.SCENE_SCALE, yaw = CedarSawmillGeometry.sceneYaw)

    /** One representative delivered bundle. Completion remains wholly owned by the durable domain. */
    fun storedBoards(warehouse: LumberPose, fromCart: LumberPose? = null, progress: Double = 1.0): List<Part> = buildList {
        for (z in listOf(-2.5f, 2.5f)) add(Part("storage-bearer-$z", vector(warehouse).add(0f, .08f, z),
            Vector3f(1.8f, .16f, .24f), block = "minecraft:stripped_spruce_log[axis=x]"))
        if (fromCart != null && progress < 1.0) addAll(boardCart(fromCart))
        val target = warehouseStock(warehouse)
        val at = if (fromCart == null) target else blend(boardCartStock(fromCart), target, progress, .15)
        addAll(boards(at).map { it.copy(id = "stored-${it.id}") })
    }

    /** A forgiving full-log hull, independent of each ItemDisplay section's model scale. */
    fun pickupTarget(at: LumberPose, action: LumberAction): Part = Part("pickup-log", vector(at),
        Vector3f(if (action == LumberAction.LOAD_CART) 1.3f else 1.8f,
            if (action == LumberAction.LOAD_CART) 1.3f else 1.5f, STOCK_LENGTH.toFloat() + .3f),
        Quaternionf().rotationY(Math.toRadians(-at.yaw.toDouble()).toFloat()), action = LumberInput(action))

    fun felledStock(tree: LumberTreeSnapshot): LumberPose = LumberPose(tree.root.world,
        tree.root.x + .5 + tree.fallX * (STOCK_LENGTH / 2 + .5),
        tree.root.y + .5,
        tree.root.z + .5 + tree.fallZ * (STOCK_LENGTH / 2 + .5),
        Math.toDegrees(atan2(-tree.fallX.toDouble(), tree.fallZ.toDouble())).toFloat())

    fun branchTargets(tree: LumberTreeSnapshot): List<LumberPose> = (0 until BRANCH_COUNT).map { index ->
        val along = 1.85 + index * 2.2
        val side = if (index % 2 == 0) .92 else -.92
        LumberPose(tree.root.world, tree.root.x + .5 + tree.fallX * along + tree.fallZ * side,
            tree.root.y + .85, tree.root.z + .5 + tree.fallZ * along - tree.fallX * side,
            felledStock(tree).yaw)
    }

    fun sample(view: LumberVisualView, warehouse: LumberPose? = null): List<Part> = buildList {
        val state = view.state
        val tree = state.tree
        if (tree != null && state.phase == LumberPhase.CHOPPING) tree.logs.filter {
            it.position.x == tree.root.x && it.position.z == tree.root.z && it.position.y < tree.root.y + 2
        }.forEachIndexed { index, block ->
            add(Part("tree-mark-$index", Vector3f(block.position.x + .5f, block.position.y + .5f, block.position.z + .5f),
                Vector3f(1.004f), block = block.blockData, action = LumberInput(LumberAction.CHOP_TREE)))
        }
        if (tree != null && state.phase in setOf(LumberPhase.FALLING, LumberPhase.DELIMBING, LumberPhase.LOADING_CART)) {
            val falling = state.phase == LumberPhase.FALLING
            val age = if (falling) view.phaseAgeTicks.toDouble() else FALL_TICKS.toDouble()
            val rotation = Quaternionf().rotationAxis(fallAngle(age).toFloat(), tree.fallZ.toFloat(), 0f, -tree.fallX.toFloat())
            // The forward cut edge keeps the rotating wood above the untouched stump. Only
            // once the quarter-turn finishes does the broken hinge settle beside it.
            val hinge = cutHinge(tree)
            val drop = fallDrop(age).toFloat()
            val completed = state.branches?.targets.orEmpty().mapIndexedNotNull { index, target ->
                index.takeIf { target.status == ObjectiveTargetStatus.COMPLETED }
            }.toSet()
            val woodPositions = tree.logs.mapTo(hashSetOf()) { it.position }
            for ((index, block) in tree.maskedBlocks.withIndex()) {
                val relative = Vector3f(block.position.x + .5f, block.position.y + .5f, block.position.z + .5f).sub(hinge)
                val wood = block.position in woodPositions
                val selectedStem = wood && block.position.x == tree.root.x && block.position.z == tree.root.z && relative.y - .5 < STOCK_LENGTH
                val group = ((relative.y / 2).toInt().coerceAtLeast(0) % BRANCH_COUNT)
                if (!falling && (!selectedStem && group in completed || state.phase == LumberPhase.LOADING_CART && !selectedStem)) continue
                val size = Vector3f(1f)
                if (!falling && selectedStem && relative.y + .5 > STOCK_LENGTH) {
                    size.y = (STOCK_LENGTH - (relative.y - .5)).toFloat()
                    relative.y -= (1f - size.y) / 2
                }
                val center = relative.rotate(rotation).add(hinge).add(0f, -drop, 0f)
                add(Part("tree-$index", center, size, Quaternionf(rotation), block.blockData,
                    action = if (state.phase == LumberPhase.LOADING_CART && selectedStem) LumberInput(LumberAction.LOAD_CART) else null))
            }
            if (!falling && state.phase == LumberPhase.DELIMBING) branchTargets(tree).forEachIndexed { index, at ->
                if (index !in completed) {
                    val direction = Quaternionf().rotationY(Math.toRadians(at.yaw.toDouble()).toFloat())
                        .rotateZ(if (index % 2 == 0) -.35f else .35f)
                    add(Part("branch-$index", vector(at), Vector3f(1.3f, .32f, .32f), direction,
                        "minecraft:oak_log[axis=x]", action = LumberInput(LumberAction.CUT_BRANCH, index)))
                }
            }
        }
        if (state.phase == LumberPhase.HAULING && state.cartCarrier != null || state.phase == LumberPhase.LOADING_SAWMILL) {
            addAll(receivingMarkers().map { if (state.phase == LumberPhase.HAULING) it else it.copy(action = null) })
        }
        state.cart?.let { anchor ->
            if (state.phase in setOf(LumberPhase.HAULING, LumberPhase.LOADING_SAWMILL)) {
                val stock = if (state.phase == LumberPhase.LOADING_SAWMILL)
                    loadingStock(anchor, view.phaseAgeTicks.toDouble() / LumberTiming.SAWMILL_LOAD_TICKS)
                else draggedStock(anchor)
                val pickup = state.phase == LumberPhase.HAULING && state.cartCarrier == null
                addAll(rawStock(stock).map { if (pickup) it.copy(action = LumberInput(LumberAction.TAKE_CART)) else it })
                if (state.phase == LumberPhase.LOADING_SAWMILL) for (side in listOf(-1f, 1f)) {
                    val from = Vector3f(rawCenter).add(side * 1.1f, -.4f, 0f)
                    val to = vector(stock).add(side * .9f, .05f, 0f)
                    val line = Vector3f(to).sub(from)
                    add(Part("loading-chain-$side", Vector3f(from).add(to).mul(.5f), Vector3f(.55f, line.length(), .55f),
                        Quaternionf().rotationTo(Vector3f(0f, 1f, 0f), line.normalize()), "minecraft:iron_chain[axis=y]"))
                }
            }
        }
        if (state.phase in setOf(LumberPhase.COLLECTING_BOARDS, LumberPhase.DELIVERING)) {
            val anchor = state.cart.takeIf { state.phase == LumberPhase.DELIVERING || state.boardCartLoaded } ?: boardCartHome()
            val free = state.phase == LumberPhase.COLLECTING_BOARDS && state.boardCartLoaded
            addAll(boardCart(anchor).map { if (free) it.copy(action = LumberInput(LumberAction.COLLECT_BOARDS)) else it })
            if (state.phase == LumberPhase.DELIVERING || state.boardCartLoaded) {
                val stock = if (state.boardCartLoaded) boardCartStock(anchor) else
                    boardLoadingStock(anchor, view.phaseAgeTicks.toDouble() / LumberTiming.BOARD_PICKUP_TICKS)
                addAll(boards(stock).map { if (free) it.copy(action = LumberInput(LumberAction.COLLECT_BOARDS)) else it })
            }
        }
        if (warehouse != null && state.phase == LumberPhase.DELIVERING && state.boardCartLoaded) {
            for (z in listOf(-1f, 1f)) add(Part("storage-marker-$z", vector(warehouse).add(0f, .035f, z * 1.45f),
                Vector3f(2.6f, .07f, .12f), block = "minecraft:yellow_terracotta", action = LumberInput(LumberAction.DELIVER_BOARDS)))
        }
    }

    /** The actual trailing pose is retained; alignment and lift finish outside the doorway. */
    fun loadingStock(from: LumberPose, progress: Double): LumberPose {
        val start = draggedStock(from)
        val intake = sawIntake()
        val aligned = intake.copy(x = receivingCenter().x, y = receivingCenter().y + .612, z = 200.5)
        val raised = aligned.copy(y = intake.y)
        val inside = raised.copy(z = 191.0)
        return when {
            progress < .30 -> blend(start, aligned, progress / .30, .12)
            progress < .45 -> blend(aligned, raised, (progress - .30) / .15)
            progress < .85 -> blend(raised, inside, (progress - .45) / .40)
            else -> blend(inside, intake, (progress - .85) / .15)
        }
    }

    private fun receivingMarkers(): List<Part> = buildList {
        val at = receivingCenter()
        fun strip(id: String, x: Double, z: Double, sx: Float, sz: Float, yaw: Float = 0f) {
            add(Part("receiving-$id", Vector3f(x.toFloat(), (at.y + .035).toFloat(), z.toFloat()),
                Vector3f(sx, .07f, sz), Quaternionf().rotationY(yaw), "minecraft:yellow_terracotta",
                action = LumberInput(LumberAction.LOAD_SAWMILL)))
        }
        for (x in listOf(-1, 1)) for (z in listOf(-1, 1)) {
            strip("corner-$x-$z-x", at.x + x * 2.45, at.z + z * 2.93, 1.1f, .14f)
            strip("corner-$x-$z-z", at.x + x * 2.93, at.z + z * 2.45, .14f, 1.1f)
        }
        for (z in listOf(at.z + 2.5, at.z + 1.7)) for (side in listOf(-1, 1))
            strip("arrow-$z-$side", at.x + side * .20, z, .12f, .60f, side * -.65f)
    }

    fun rawStock(at: LumberPose, roundedProfile: Double = 1.0): List<Part> {
        val turn = Quaternionf().rotationY(Math.toRadians(-at.yaw.toDouble()).toFloat())
        val profile = smooth(roundedProfile).toFloat()
        return raw.map { source ->
            val center = Vector3f(source.center).add(CedarSawmillGeometry.origin).sub(rawCenter).rotate(Quaternionf(CedarSawmillGeometry.sceneRotation).conjugate()).rotate(turn).add(vector(at))
            // Closed-beta stylisation keeps the captured stem's length and section order
            // when it becomes the existing rounded stock model at pickup.
            val size = Vector3f(source.size).mul(1f / 1.632f + (1f - 1f / 1.632f) * profile,
                1f / 1.224f + (1f - 1f / 1.224f) * profile, 1f)
            Part(source.partId, center, size, Quaternionf(turn).mul(Quaternionf(CedarSawmillGeometry.sceneRotation).conjugate()).mul(source.rotation),
                itemModel = "arc_farms:cedar_sawmill_raw")
        }
    }

    /** Exact swept LOG-cell bounds of the same 61 fall poses; leaves are not solid timber. */
    fun fallCollisionCells(tree: LumberTreeSnapshot): Set<WorksitePosition> = buildSet {
        val dx = tree.fallX.toDouble(); val dz = tree.fallZ.toDouble()
        require(kotlin.math.abs(dx) + kotlin.math.abs(dz) == 1.0)
        val hinge = cutHinge(tree)
        val logs = tree.logs.filter { it.position.y >= tree.root.y + 2 }
        for (log in logs) for (tick in 0 until FALL_TICKS) {
            val low = DoubleArray(3) { Double.POSITIVE_INFINITY }
            val high = DoubleArray(3) { Double.NEGATIVE_INFINITY }
            val first = fallAngle(tick.toDouble()); val last = fallAngle(tick + 1.0)
            for (x in 0..1) for (y in 0..1) for (z in 0..1) {
                val rx = log.position.x + x - hinge.x.toDouble()
                val ry = log.position.y + y - hinge.y.toDouble()
                val rz = log.position.z + z - hinge.z.toDouble()
                val along = rx * dx + rz * dz
                val cross = -rx * dz + rz * dx
                fun include(angle: Double, drop: Double) {
                    val c = if (angle == PI / 2) 0.0 else cos(angle)
                    val s = if (angle == PI / 2) 1.0 else sin(angle)
                    val forward = along * c + ry * s
                    val point = doubleArrayOf(hinge.x + dx * forward - dz * cross,
                        hinge.y + ry * c - along * s - drop,
                        hinge.z + dz * forward + dx * cross)
                    for (axis in 0..2) { low[axis] = minOf(low[axis], point[axis]); high[axis] = maxOf(high[axis], point[axis]) }
                }
                include(first, fallDrop(tick.toDouble()))
                include(last, fallDrop(tick + 1.0))
                // Rotated corners may reach an extremum between ticks. The turn and descent
                // meet exactly on tick44, so these analytic extrema have no translation term.
                if (first != last) for (candidate in listOf(atan2(ry, along), atan2(-along, ry)))
                    for (angle in listOf(candidate, candidate + PI, candidate - PI))
                        if (angle > first && angle < last) include(angle, 0.0)
            }
            for (x in floor(low[0] + .0001).toInt()..floor(high[0] - .0001).toInt())
                for (y in floor(low[1] + .0001).toInt()..floor(high[1] - .0001).toInt())
                    for (z in floor(low[2] + .0001).toInt()..floor(high[2] - .0001).toInt())
                        add(WorksitePosition(tree.root.world, x, y, z))
        }
    }

    private fun cutHinge(tree: LumberTreeSnapshot) = Vector3f(tree.root.x + .5f + tree.fallX * .5f,
        tree.root.y + 2f, tree.root.z + .5f + tree.fallZ * .5f)
    private fun fallAngle(ticks: Double) = PI / 2 * smooth(ticks / FALL_TURN_TICKS)
    private fun fallDrop(ticks: Double) = 2 * smooth((ticks - FALL_TURN_TICKS) / (FALL_TICKS - FALL_TURN_TICKS))

    fun boards(at: LumberPose): List<Part> {
        val turn = Quaternionf().rotationY(Math.toRadians(-at.yaw.toDouble()).toFloat())
        return output.map { source ->
            val original = CedarSawmillGeometry.parts.first { it.id == source.partId }
            Part(source.partId, Vector3f(source.center).add(CedarSawmillGeometry.origin).sub(outputCenter).rotate(Quaternionf(CedarSawmillGeometry.sceneRotation).conjugate()).rotate(turn).add(vector(at)),
                Vector3f(source.size), Quaternionf(turn).mul(Quaternionf(CedarSawmillGeometry.sceneRotation).conjugate()).mul(source.rotation), original.blockData, original.itemModel)
        }
    }

    private fun boardCart(at: LumberPose): List<Part> = buildList {
        val turn = Quaternionf().rotationY(Math.toRadians(-at.yaw.toDouble()).toFloat())
        fun part(id: String, x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float, material: String) {
            add(Part("board-cart-$id", Vector3f(x, y, z).rotate(turn).add(vector(at)), Vector3f(sx, sy, sz), Quaternionf(turn), material))
        }
        part("bed", 0f, .04f, 0f, 1.55f, .04f, 3.8f, "minecraft:spruce_planks")
        for (z in listOf(-1.5f, 1.5f)) {
            part("bolster-$z", 0f, .08f, z, 1.6f, .04f, .24f, "minecraft:stripped_spruce_log[axis=x]")
            part("axle-$z", 0f, .05f, z, 1.72f, .04f, .12f, "minecraft:polished_deepslate")
            for (x in listOf(-.82f, .82f)) part("wheel-$x-$z", x, .15f, z, .16f, .3f, .3f, "minecraft:dark_oak_log[axis=x]")
        }
        for (x in listOf(-.95f, .95f)) part("shaft-$x", x, .85f, 3.1f, .10f, .10f, 2.7f, "minecraft:spruce_planks")
        part("handle", 0f, .85f, 4.45f, 2f, .12f, .12f, "minecraft:stripped_spruce_log[axis=x]")
    }

    fun blend(from: LumberPose, to: LumberPose, progress: Double, lift: Double = 0.0): LumberPose {
        val p = smooth(progress)
        val yaw = ((to.yaw - from.yaw + 540) % 360) - 180
        return LumberPose(to.world, from.x + (to.x - from.x) * p,
            from.y + (to.y - from.y) * p + sin(PI * p) * lift,
            from.z + (to.z - from.z) * p, (from.yaw + yaw * p).toFloat())
    }
    fun vector(at: LumberPose): Vector3f = Vector3f(at.x.toFloat(), at.y.toFloat(), at.z.toFloat())
    private fun pose(at: Vector3f, yaw: Float): LumberPose = LumberPose("rc_atelier_cedar_sawmill", at.x.toDouble(), at.y.toDouble(), at.z.toDouble(), yaw)
    private fun smooth(value: Double): Double = value.coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }
}
