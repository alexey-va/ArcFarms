package ru.ruscrafting.farms.paper.lumber

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Material
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.config.LumbermillSettings
import ru.ruscrafting.farms.domain.lumber.LumberPose
import ru.ruscrafting.farms.domain.lumber.LumberTreeSnapshot
import ru.ruscrafting.farms.paper.RuntimeTaskSupervisor
import ru.ruscrafting.farms.paper.lumber.scene.LumberSceneGeometry
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort
import java.util.concurrent.CompletableFuture

class LumberTreeIndexTest : FunSpec({
    test("captured forest discover and select accept the existing north-falling tree without a separate cart footprint") {
        MockBukkitTestRuntime.open().use { paper ->
            val captured = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(
                java.nio.file.Path.of("scripts/sawmill-preview/lumber-area.snapshot.json"))).asJsonObject
            captured["missingCells"].asInt shouldBe 0
            val worldName = captured["world"].asString
            val world = paper.server.addSimpleWorld(worldName)
            // The capture starts at x135; this bounded extract contains the complete selected tree and cart footprint.
            for (x in 8..9) for (z in 10..13) world.getChunkAt(x, z)
            captured.getAsJsonArray("blocks").forEach { element ->
                val block = element.asJsonArray
                val x = block[0].asInt; val y = block[1].asInt; val z = block[2].asInt
                if (x in 135..152 && y in 71..92 && z in 160..215) {
                    world.getBlockAt(x, y, z).type = Material.valueOf(
                        block[3].asString.substringAfter(':').substringBefore('[').uppercase())
                }
            }
            val token = mockk<RuntimeTaskSupervisor.Token>()
            val tasks = mockk<WorksiteTaskPort>(relaxed = true) {
                every { lifecycleToken() } returns token
                every { runAsync(token, any()) } answers { secondArg<() -> Unit>().invoke(); true }
                every { runSync(token, any()) } answers { secondArg<() -> Unit>().invoke(); true }
                every { capture(token, any(), any()) } answers {
                    val valid = secondArg<() -> Boolean>(); val step = thirdArg<() -> Boolean>()
                    while (valid() && !step()) Unit
                    CompletableFuture.completedFuture(Unit)
                }
            }
            val rules = LumbermillSettings().copy(forest = CuboidBounds(135, 71, 160, 147, 92, 215))
            val index = LumberTreeIndex(tasks)
            var count = 0
            index.rebuild(world, rules) { count = it.getOrThrow() }
            io.kotest.assertions.withClue("Captured forest rejection counters: ${index.report}; candidates=$count") {
                (count > 0) shouldBe true
            }
            var selected: LumberTreeSnapshot? = null
            index.select(world, 1) { selected = it.getOrThrow() }
            io.kotest.assertions.withClue("Captured current-world selection: ${index.report}; candidates=$count") {
                (selected != null) shouldBe true
            }
            selected!!.root shouldBe ru.ruscrafting.farms.domain.worksite.WorksitePosition(worldName, 139, 73, 202)
            selected!!.fallX shouldBe 0
            selected!!.fallZ shouldBe -1
            val stock = LumberSceneGeometry.felledStock(selected!!)
            selected!!.cartHome shouldBe stock.copy(y = selected!!.root.y.toDouble())
            selected!!.logs.size shouldBe 15
            selected!!.leaves.size shouldBe 114
            val brush = ru.ruscrafting.farms.domain.worksite.WorksitePosition(worldName, 139, 76, 200)
            selected!!.maskedBlocks.any { it.position == brush } shouldBe false
            world.getBlockAt(brush.x, brush.y, brush.z).type shouldBe Material.OAK_LEAVES
            for (obstacle in listOf(Material.OAK_LOG, Material.STONE)) {
                world.getBlockAt(brush.x, brush.y, brush.z).type = obstacle
                var rejected: LumberTreeSnapshot? = selected
                index.select(world, 2) { rejected = it.getOrThrow() }
                rejected shouldBe null
                world.getBlockAt(brush.x, brush.y, brush.z).type = Material.OAK_LEAVES
            }
            world.getBlockAt(139, 73, 202).type shouldBe Material.OAK_LOG
        }
    }

    test("canopy ownership preserves neighbouring trunks and ignores obsolete cart headroom") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.server.addSimpleWorld("cedar")
            for (x in 5..8) for (z in 10..12) world.getChunkAt(x, z)
            for (x in 100..130) for (z in 170..195) world.getBlockAt(x, 72, z).type = Material.DIRT
            for (y in 73..82) world.getBlockAt(110, y, 180).type = Material.OAK_LOG
            for (y in 73..78) world.getBlockAt(109, y, 181).type = Material.OAK_LOG
            world.getBlockAt(111, 82, 180).type = Material.OAK_LEAVES
            world.getBlockAt(109, 80, 181).type = Material.OAK_LEAVES
            // This controlled fixture keeps E as its valid direction; captured acceptance above exercises N.
            world.getBlockAt(110, 73, 178).type = Material.STONE
            // Third clearance cell belongs to a neighbour; it is above both the cart and the worker.
            world.getBlockAt(110, 75, 184).type = Material.OAK_LEAVES
            val token = mockk<RuntimeTaskSupervisor.Token>()
            val tasks = mockk<WorksiteTaskPort>(relaxed = true) {
                every { lifecycleToken() } returns token
                every { runAsync(token, any()) } answers { secondArg<() -> Unit>().invoke(); true }
                every { runSync(token, any()) } answers { secondArg<() -> Unit>().invoke(); true }
                every { capture(token, any(), any()) } answers {
                    val valid = secondArg<() -> Boolean>(); val step = thirdArg<() -> Boolean>()
                    while (valid() && !step()) Unit
                    CompletableFuture.completedFuture(Unit)
                }
            }
            val pose = LumberPose("cedar", 114.5, 73.0, 183.5)
            val rules = LumbermillSettings(world = "cedar", forest = CuboidBounds(105, 70, 175, 120, 87, 190),
                worksite = CuboidBounds(100, 64, 170, 130, 100, 195), cart = pose, warehouse = pose)
            val index = LumberTreeIndex(tasks)
            var count = -1
            index.rebuild(world, rules) { count = it.getOrThrow() }
            count shouldBe 1
            var tree: LumberTreeSnapshot? = null
            index.select(world, 1) { tree = it.getOrThrow() }
            tree!!.logs.any { it.position.x == 109 && it.position.z == 181 } shouldBe false
            tree!!.leaves.any { it.position.x == 109 && it.position.z == 181 } shouldBe false
            val stock = LumberSceneGeometry.felledStock(tree!!)
            val cart = stock.copy(y = tree!!.root.y.toDouble())
            tree!!.cartHome shouldBe cart
            for (height in 73..74) {
                world.getBlockAt(114, height, 183).type = Material.STONE
                index.select(world, 2) { tree = it.getOrThrow() }
                tree!!.cartHome shouldBe cart
                world.getBlockAt(114, height, 183).type = Material.AIR
            }
            index.select(world, 3) { tree = it.getOrThrow() }
            tree!!.cartHome shouldBe cart
            world.getBlockAt(110, 75, 184).type shouldBe Material.OAK_LEAVES
            world.getBlockAt(110, 77, 180).type = Material.STONE
            index.select(world, 2) { tree = it.getOrThrow() }
            tree shouldBe null
            world.getBlockAt(110, 77, 180).type shouldBe Material.STONE
            world.getBlockAt(109, 73, 181).type shouldBe Material.OAK_LOG
        }
    }
})
