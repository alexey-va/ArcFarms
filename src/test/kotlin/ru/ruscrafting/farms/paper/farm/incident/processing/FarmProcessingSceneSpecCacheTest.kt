package ru.ruscrafting.farms.paper.farm.incident.processing

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class FarmProcessingSceneSpecCacheTest : FunSpec({
    test("reuses an unchanged scene spec and rebuilds after invalidation") {
        val cache = FarmProcessingSceneSpecCache<String>()
        var builds = 0

        fun resolve(revision: String): FarmProcessingSceneSpec = cache.resolve("farm", revision) {
            builds++
            FarmProcessingSceneSpec("farm", builds.toLong(), 2f, 4, emptyList())
        }

        val first = resolve("loading")
        val unchanged = resolve("loading")

        unchanged shouldBe first
        (unchanged === first) shouldBe true
        builds shouldBe 1

        cache.invalidate("farm")
        val rebuilt = resolve("loading")

        rebuilt shouldNotBe first
        builds shouldBe 2
    }
})
