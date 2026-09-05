package ru.ruscrafting.farms.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.configuration.file.YamlConfiguration
import java.io.InputStreamReader

class WorksiteEnterpriseMenuTest : FunSpec({
    test("enterprise money uses the same grouped typography as the visual proof") {
        formatEnterpriseMoney(24_875_000) shouldBe "248 750"
        formatEnterpriseMoney(248_750) shouldBe "2 487.5"
        formatEnterpriseMoney(100) shouldBe "1"
    }

    test("enterprise participation locale keeps contract placeholders scoped to their rows") {
        val yaml = requireNotNull(WorksiteEnterpriseMenuTest::class.java.classLoader.getResourceAsStream("lang/ru.yml"))
            .use { YamlConfiguration.loadConfiguration(InputStreamReader(it)) }
        yaml.getString("companies.participation.summary") shouldBe "<color:#707a76>План участия:</color> <color:#f2fff7><plan></color>"
        yaml.getString("companies.participation.project-progress")!!.let {
            it.contains("<stage>") shouldBe true
            it.contains("<orders>") shouldBe true
            it.contains("<target>") shouldBe true
        }
    }
})
