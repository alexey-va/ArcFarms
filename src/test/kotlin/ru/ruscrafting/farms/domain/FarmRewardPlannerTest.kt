package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import ru.ruscrafting.farms.config.FarmBundleItemSettings
import ru.ruscrafting.farms.config.FarmCommandRewardSettings
import ru.ruscrafting.farms.config.FarmExperienceRewardSettings
import ru.ruscrafting.farms.config.FarmItemRewardSettings
import ru.ruscrafting.farms.config.FarmMoneyRewardSettings
import ru.ruscrafting.farms.config.FarmRandomBundleSettings
import ru.ruscrafting.farms.config.FarmRewardBundleSettings
import ru.ruscrafting.farms.config.FarmRewardSettings
import java.util.UUID

class FarmRewardPlannerTest : FunSpec({
    val recipient = FarmRewardRecipient(UUID(0, 42), "Farmer", contribution = 321, rank = 2)

    test("a player with no recorded work cannot receive a completion reward") {
        shouldThrow<IllegalArgumentException> {
            FarmRewardPlanner.plan(
                settings = FarmRewardSettings(
                    experience = FarmExperienceRewardSettings(0, 0),
                    money = FarmMoneyRewardSettings(0, 0),
                    items = emptyList(),
                    commands = emptyList(),
                    randomBundles = FarmRandomBundleSettings(0, 0, emptyList()),
                ),
                zoneId = "communal_farm",
                sequence = 1,
                recipient = FarmRewardRecipient(UUID(0, 99), "Idle", contribution = 0, rank = 1),
            )
        }
    }

    test("resolved reward is deterministic and expands bounded command placeholders") {
        val settings = FarmRewardSettings(
            experience = FarmExperienceRewardSettings(75, 100),
            money = FarmMoneyRewardSettings(50_000, 100),
            items = listOf(FarmItemRewardSettings("apple", "APPLE", 3, 100)),
            commands = listOf(
                FarmCommandRewardSettings(
                    "crate",
                    "crate give %player% farm %rank% %contribution% %zone% %sequence% %uuid% %grant_id%",
                    100,
                ),
            ),
            randomBundles = FarmRandomBundleSettings(
                rolls = 1,
                chancePercent = 100,
                entries = listOf(
                    FarmRewardBundleSettings("lunch", 1, listOf(FarmBundleItemSettings("BREAD", 8))),
                ),
            ),
        )

        val first = FarmRewardPlanner.plan(settings, "communal_farm", 7, recipient)
        val second = FarmRewardPlanner.plan(settings, "communal_farm", 7, recipient)

        first shouldBe second
        first.experience shouldBe 75
        first.moneyCents shouldBe 50_000
        first.items shouldContainExactly listOf(FarmRewardItem("APPLE", 3), FarmRewardItem("BREAD", 8))
        first.fixedItemUnits shouldBe 3
        first.bundleIds shouldContainExactly listOf("lunch")
        first.commands.single() shouldBe
            "crate give Farmer farm 2 321 communal_farm 7 00000000-0000-0000-0000-00000000002a " + first.id
    }

    test("zero chances produce a persisted empty grant instead of rerolling") {
        val settings = FarmRewardSettings(
            experience = FarmExperienceRewardSettings(75, 0),
            money = FarmMoneyRewardSettings(50_000, 0),
            items = listOf(FarmItemRewardSettings("apple", "APPLE", 3, 0)),
            commands = listOf(FarmCommandRewardSettings("crate", "say %player%", 0)),
            randomBundles = FarmRandomBundleSettings(
                rolls = 1,
                chancePercent = 0,
                entries = listOf(FarmRewardBundleSettings("lunch", 1, listOf(FarmBundleItemSettings("BREAD", 8)))),
            ),
        )

        val reward = FarmRewardPlanner.plan(settings, "communal_farm", 8, recipient)

        reward.experience shouldBe 0
        reward.moneyCents shouldBe 0
        reward.items shouldBe emptyList()
        reward.fixedItemUnits shouldBe 0
        reward.commands shouldBe emptyList()
        reward.bundleIds shouldBe emptyList()
    }

    test("accepted market order scales only the deterministic money reward") {
        val settings = FarmRewardSettings(
            experience = FarmExperienceRewardSettings(75, 100),
            money = FarmMoneyRewardSettings(50_000, 100),
            items = emptyList(),
            commands = emptyList(),
            randomBundles = FarmRandomBundleSettings(0, 0, emptyList()),
        )

        val reward = FarmRewardPlanner.plan(settings, "communal_farm", 9, recipient, moneyMultiplierPercent = 125)

        reward.moneyCents shouldBe 62_500
        reward.experience shouldBe 75
    }
})
