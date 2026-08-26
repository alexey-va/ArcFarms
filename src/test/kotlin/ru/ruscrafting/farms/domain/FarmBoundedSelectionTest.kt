package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe

class FarmBoundedSelectionTest : FunSpec({
    test("giant crop placement checks stop at the configured budget") {
        val candidates = (0 until 1_000).map { index ->
            FarmGiantCropCandidate(FarmPlotPosition("world", index, 64, index % 17), "WHEAT")
        }

        val result = FarmGiantCropCandidateSelector.select(candidates, sequence = 31L, maxChecks = 64) { "occupied" }

        result.candidate shouldBe null
        result.considered shouldBe 1_000
        result.checked shouldBe 64
        result.rejected shouldBe mapOf("occupied" to 64)
    }

    test("giant crop selection is deterministic and stops on the first valid candidate") {
        val candidates = (0 until 200).map { index ->
            FarmGiantCropCandidate(FarmPlotPosition("world", index, 64, 0), "WHEAT")
        }
        fun select() = FarmGiantCropCandidateSelector.select(candidates, sequence = 9L, maxChecks = 32) { candidate ->
            "occupied".takeUnless { candidate.block.x % 11 == 0 }
        }

        val first = select()
        val second = select()
        first shouldBe second
        first.candidate?.block?.x?.rem(11) shouldBe 0
        first.checked shouldBeLessThanOrEqual 32
    }

    test("successive giant crop selections rotate crop families and positions") {
        val candidates = listOf("WHEAT", "MELON", "PUMPKIN").flatMapIndexed { cropIndex, crop ->
            (0 until 5).map { position ->
                FarmGiantCropCandidate(FarmPlotPosition("world", cropIndex * 100 + position * 8, 64, cropIndex * 12), crop)
            }
        }

        val selected = (0L until 6L).map { selectionKey ->
            FarmGiantCropCandidateSelector.select(candidates, selectionKey, 32) { null }.candidate
                ?: error("No giant crop candidate selected")
        }

        selected.take(3).map(FarmGiantCropCandidate::crop).toSet().size shouldBe 3
        selected[0].crop shouldBe selected[3].crop
        (selected[0].block == selected[3].block) shouldBe false
    }

    test("spaced selection uses a quadratic budget based only on the requested result size") {
        val candidates = (0 until 10_000).map { index ->
            FarmMatureCrop(FarmPlotPosition("world", index % 500, 64, index / 500), "WHEAT")
        }

        val result = FarmSpacedPlotSelector.select(candidates, count = 24, minimumSpacing = 8.0)
        val repeated = FarmSpacedPlotSelector.select(candidates, count = 24, minimumSpacing = 8.0)

        result.values shouldBe repeated.values
        result.values.size shouldBe 24
        (result.distanceChecks <= candidates.size.toLong() * 24L) shouldBe true
    }
})
