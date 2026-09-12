package ru.ruscrafting.farms.paper.mine.index

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MineIndexCodecTest : FunSpec({
    val world = "world"
    val chunkX = -4
    val chunkZ = 7

    test("round trips negative coordinates, Y bounds, and every stable role bit") {
        val allRoles = enumValues<MineAnchorRole>().toSet()
        val targets = listOf(
            MineIndexedTarget(WorksitePosition(world, -64, -2_048, 112), allRoles),
            MineIndexedTarget(
                WorksitePosition(world, -49, 2_048, 127),
                setOf(MineAnchorRole.MINEABLE, MineAnchorRole.POWER),
            ),
        )

        val encoded = MineIndexCodec.encode(chunkX, chunkZ, targets)

        encoded.size shouldBe 8 + targets.size * 4
        MineIndexCodec.isCompact(encoded) shouldBe true
        MineIndexCodec.decode(world, chunkX, chunkZ, encoded)
            .shouldContainExactlyInAnyOrder(targets)
    }

    test("reads both legacy strings and transitional UTF-8 byte arrays") {
        val legacy = "-63,-2048,113,MINEABLE+POWER;-64,2048,127,RAIL"
        val expected = listOf(
            MineIndexedTarget(
                WorksitePosition(world, -63, -2_048, 113),
                setOf(MineAnchorRole.MINEABLE, MineAnchorRole.POWER),
            ),
            MineIndexedTarget(WorksitePosition(world, -64, 2_048, 127), setOf(MineAnchorRole.RAIL)),
        )

        MineIndexCodec.decodeLegacy(world, chunkX, chunkZ, legacy).shouldContainExactlyInAnyOrder(expected)
        MineIndexCodec.decode(world, chunkX, chunkZ, legacy.toByteArray()).shouldContainExactlyInAnyOrder(expected)
    }

    test("compact entries materially reduce a dense legacy index") {
        val targets = (0 until 4_096).map { index ->
            val x = -64 + (index and 0xF)
            val z = 112 + ((index ushr 4) and 0xF)
            val y = -2_048 + (index ushr 8)
            MineIndexedTarget(WorksitePosition(world, x, y, z), setOf(MineAnchorRole.MINEABLE))
        }
        val legacy = targets.joinToString(";") { target ->
            "${target.position.x},${target.position.y},${target.position.z},MINEABLE"
        }.toByteArray()

        val encoded = MineIndexCodec.encode(chunkX, chunkZ, targets)

        encoded.size shouldBe 8 + targets.size * 4
        (encoded.size < legacy.size) shouldBe true
        MineIndexCodec.decode(world, chunkX, chunkZ, encoded).size shouldBe targets.size
    }

    test("rejects truncated, trailing, unknown-version, and empty-role binary payloads") {
        val target = MineIndexedTarget(
            WorksitePosition(world, -63, 0, 113),
            setOf(MineAnchorRole.MINEABLE),
        )
        val encoded = MineIndexCodec.encode(chunkX, chunkZ, listOf(target))

        shouldThrow<IllegalArgumentException> {
            MineIndexCodec.decode(world, chunkX, chunkZ, encoded.copyOf(encoded.size - 1))
        }
        shouldThrow<IllegalArgumentException> {
            MineIndexCodec.decode(world, chunkX, chunkZ, encoded + byteArrayOf(0))
        }
        shouldThrow<IllegalArgumentException> {
            MineIndexCodec.decode(world, chunkX, chunkZ, encoded.copyOf().also { it[2] = 2 })
        }
        MineIndexCodec.isCompact(encoded.copyOf().also { it[2] = 2 }) shouldBe false

        val emptyRoles = encoded.copyOf()
        ByteBuffer.wrap(emptyRoles).order(ByteOrder.BIG_ENDIAN).putInt(8, 0)
        shouldThrow<IllegalArgumentException> {
            MineIndexCodec.decode(world, chunkX, chunkZ, emptyRoles)
        }
    }

    test("rejects cross-chunk targets and malformed legacy entries") {
        val target = MineIndexedTarget(
            WorksitePosition(world, -48, 0, 113),
            setOf(MineAnchorRole.MINEABLE),
        )
        shouldThrow<IllegalArgumentException> {
            MineIndexCodec.encode(chunkX, chunkZ, listOf(target))
        }
        shouldThrow<IllegalArgumentException> {
            MineIndexCodec.decodeLegacy(world, chunkX, chunkZ, "-63,0,113,UNKNOWN")
        }
    }
})
