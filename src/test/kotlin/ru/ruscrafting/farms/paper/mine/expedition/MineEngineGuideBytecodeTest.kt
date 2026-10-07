package ru.ruscrafting.farms.paper.mine.expedition

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.charset.StandardCharsets

class MineEngineGuideBytecodeTest : StringSpec({
    "inspection frame construction avoids Kotlin default bridges across plugin classloaders" {
        val classFile = requireNotNull(
            MineEngineGuide::class.java.getResourceAsStream("MineEngineGuide.class"),
        ).use { String(it.readBytes(), StandardCharsets.ISO_8859_1) }

        classFile.contains("ru/arc/paper/api/ArcInspectionFrame") shouldBe true
        classFile.contains("kotlin/jvm/internal/DefaultConstructorMarker") shouldBe false
    }
})
