package ru.ruscrafting.farms.paper.lumber.scene

import org.bukkit.util.BoundingBox
import ru.arc.paper.display.DisplayOrientedVolume
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.ruscrafting.farms.domain.lumber.LumberAction
import ru.ruscrafting.farms.domain.lumber.LumberInput
import ru.ruscrafting.farms.domain.lumber.LumberPhase
import ru.ruscrafting.farms.domain.lumber.LumberPose
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
    const val CART_FOLLOW_DISTANCE = 4.0
    const val BRANCH_COUNT = 3
    val STOCK_LENGTH = CedarSawmillAnimation.LOG_LENGTH * CedarSawmillGeometry.SCENE_SCALE
    private val raw = CedarSawmillAnimation.sample(0.0).poses.filter { it.partId.endsWith("-raw") }
    private val output = CedarSawmillAnimation.sample(25.0, 1).poses.filter { pose ->
        pose.visible && CedarSawmillGeometry.parts.first { it.id == pose.partId }.motion.startsWith("timber-")
    }
    private val rawCenter = Vector3f(0f, (CedarSawmillAnimation.LOG_BOTTOM + CedarSawmillAnimation.LOG_HEIGHT / 2).toFloat(), -5.8f)
        .mul(CedarSawmillGeometry.SCENE_SCALE).rotate(CedarSawmillGeometry.sceneRotation).add(CedarSawmillGeometry.origin)
    private val outputCenter = Vector3f(1.1325f, (CedarSawmillAnimation.LOG_BOTTOM + .175 + .28).toFloat(), 3.6f)
        .mul(CedarSawmillGeometry.SCENE_SCALE).rotate(CedarSawmillGeometry.sceneRotation).add(CedarSawmillGeometry.origin)

    data class Part(
        val id: String, val center: Vector3f, val size: Vector3f, val rotation: Quaternionf = Quaternionf(),
        val block: String? = null, val itemModel: String? = null, val visible: Boolean = true,
        val action: LumberInput? = null,
    )

    fun sawIntake(): LumberPose = pose(rawCenter, CedarSawmillGeometry.sceneYaw)
    fun sawCartDock(): LumberPose = LumberPose("rc_atelier_cedar_sawmill", 190.387, 73.0, 195.12, 180f)
    fun outputPickup(): LumberPose = pose(outputCenter, CedarSawmillGeometry.sceneYaw)

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

    fun sample(view: LumberVisualView): List<Part> = buildList {
        val state = view.state
        val tree = state.tree
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
                add(Part("tree-$index", center, size, Quaternionf(rotation), block.blockData))
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
        state.cart?.let { cart ->
            if (state.phase in setOf(LumberPhase.LOADING_CART, LumberPhase.HAULING, LumberPhase.LOADING_SAWMILL)) {
                addAll(cart(cart))
                if (state.phase != LumberPhase.LOADING_CART) {
                    val start = tree?.let(::felledStock) ?: cart
                    val target = cart.copy(y = cart.y + .912)
                    val stock = when (state.phase) {
                        LumberPhase.HAULING -> blend(start, target, view.phaseAgeTicks.toDouble() / LumberTiming.CART_LOAD_TICKS, .55)
                        LumberPhase.LOADING_SAWMILL -> {
                            val progress = view.phaseAgeTicks.toDouble() / LumberTiming.SAWMILL_LOAD_TICKS
                            // Lift before the leading end reaches the interior stock rack. Keep the
                            // height monotonic so the full native hull stays below the top-slab lintel.
                            blend(target, sawIntake(), progress).copy(y = blend(target, sawIntake(), progress / .45).y)
                        }
                        else -> target
                    }
                    addAll(rawStock(stock, if (state.phase == LumberPhase.HAULING) view.phaseAgeTicks.toDouble() / LumberTiming.CART_LOAD_TICKS else 1.0))
                    if (state.phase == LumberPhase.LOADING_SAWMILL) for (side in listOf(-1f, 1f)) {
                        val from = Vector3f(rawCenter).add(side * 1.1f, -.4f, 0f)
                        val to = vector(stock).add(side * .9f, .05f, 0f)
                        val line = Vector3f(to).sub(from)
                        add(Part("loading-chain-$side", Vector3f(from).add(to).mul(.5f), Vector3f(.55f, line.length(), .55f),
                            Quaternionf().rotationTo(Vector3f(0f, 1f, 0f), line.normalize()), "minecraft:iron_chain[axis=y]"))
                    }
                }
            }
        }
    }

    fun rawStock(at: LumberPose, roundedProfile: Double = 1.0): List<Part> {
        val turn = Quaternionf().rotationY(Math.toRadians(-at.yaw.toDouble()).toFloat())
        val profile = smooth(roundedProfile).toFloat()
        return raw.map { source ->
            val center = Vector3f(source.center).add(CedarSawmillGeometry.origin).sub(rawCenter).rotate(Quaternionf(CedarSawmillGeometry.sceneRotation).conjugate()).rotate(turn).add(vector(at))
            // Closed-beta stylisation: the square captured stem rolls into the existing rounded profile;
            // Longitudinal length and section ordering remain unchanged throughout this three-second lift.
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

    /** Conservative native-model hull plus exact chassis cuboids for the shared real-block sweep. */
    fun cartClearance(at: LumberPose): List<Part> = cart(at) + Part("cart-stock-hull",
        vector(at.copy(y = at.y + .912)), Vector3f(1.632f, 1.224f, STOCK_LENGTH.toFloat()),
        Quaternionf().rotationY(Math.toRadians(-at.yaw.toDouble()).toFloat()))

    private val stationaryMachineBounds: List<BoundingBox> by lazy {
        val fixed = CedarSawmillGeometry.parts.filter { it.motion == "fixed" }.mapTo(hashSetOf()) { it.id }
        CedarSawmillAnimation.sample(0.0).poses.filter { it.partId in fixed }.map { part ->
            val low = Vector3f(Float.POSITIVE_INFINITY)
            val high = Vector3f(Float.NEGATIVE_INFINITY)
            for (x in listOf(-.5f, .5f)) for (y in listOf(-.5f, .5f)) for (z in listOf(-.5f, .5f)) {
                val point = Vector3f(x, y, z).mul(part.size).rotate(part.rotation).add(part.center).add(CedarSawmillGeometry.origin)
                low.min(point); high.max(point)
            }
            BoundingBox(low.x.toDouble(), low.y.toDouble(), low.z.toDouble(), high.x.toDouble(), high.y.toDouble(), high.z.toDouble())
        }
    }

    /** Same shared OBB test as real blocks; conservative only for the two sloping drive belts. */
    fun clearOfMachine(volume: DisplayOrientedVolume): Boolean = stationaryMachineBounds.none(volume::overlaps)

    private fun cart(at: LumberPose): List<Part> = buildList {
        val turn = Quaternionf().rotationY(Math.toRadians(-at.yaw.toDouble()).toFloat())
        fun part(id: String, x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float, material: String) {
            add(Part("cart-$id", Vector3f(x, y, z).rotate(turn).add(vector(at)), Vector3f(sx, sy, sz), Quaternionf(turn), material,
                action = LumberInput(LumberAction.TAKE_CART)))
        }
        part("bed", 0f, .22f, 0f, 1.45f, .08f, 2.3f, "minecraft:spruce_planks")
        for (z in listOf(-.8f, .8f)) {
            part("bolster-$z", 0f, .26f, z, 1.55f, .08f, .18f, "minecraft:stripped_spruce_log[axis=x]")
            part("axle-$z", 0f, .16f, z, 1.55f, .10f, .10f, "minecraft:polished_deepslate")
            for (x in listOf(-.72f, .72f)) part("wheel-$x-$z", x, .18f, z, .14f, .36f, .36f, "minecraft:dark_oak_log[axis=x]")
        }
        for (x in listOf(-.55f, .55f)) part("shaft-$x", x, .60f, -2.45f, .09f, .09f, 2.8f, "minecraft:spruce_planks")
        part("handle", 0f, .60f, -3.85f, 1.2f, .10f, .10f, "minecraft:stripped_spruce_log[axis=x]")
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
