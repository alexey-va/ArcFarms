package ru.ruscrafting.farms.paper

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import org.bukkit.World
import org.bukkit.generator.ChunkGenerator
import java.util.concurrent.CompletionException
import ru.ruscrafting.farms.paper.mine.expedition.MineExpeditionWorldGenerator

class WorldGeneratorHotloadTest : FunSpec({
    test("every retained plugin generator blocks code replacement") {
        for (owned in listOf(MineExpeditionWorldGenerator())) {
            val world = mockk<World> {
                every { generator } returns owned
                every { name } returns "retained-world"
            }
            shouldThrow<CompletionException> { mineGeneratorReplacementBarrier(listOf(world)).join() }
                .cause?.message.orEmpty() shouldContain "retained-world"
        }
    }
    test("ordinary worlds and absence of retained generators permit preparation") {
        val foreign = mockk<ChunkGenerator>()
        val world = mockk<World> { every { generator } returns foreign }
        mineGeneratorReplacementBarrier(listOf(world)).join() shouldBe Unit
        mineGeneratorReplacementBarrier(emptyList()).join() shouldBe Unit
    }
})
