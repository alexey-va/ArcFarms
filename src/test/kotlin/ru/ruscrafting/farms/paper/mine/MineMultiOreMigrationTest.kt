package ru.ruscrafting.farms.paper.mine

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.config.MineOrderSettings
import ru.ruscrafting.farms.config.MineZoneSettings
import ru.ruscrafting.farms.config.ZoneReference
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.MineShiftEngine
import ru.ruscrafting.farms.domain.MineShiftState
import java.util.UUID

class MineMultiOreMigrationTest : FunSpec({
    test("active scalar progress is distributed into the ordered material quotas") {
        val requirements = linkedMapOf("COAL_ORE" to 25, "COPPER_ORE" to 15, "IRON_ORE" to 20)
        val order = MineOrderSettings(
            id = "coal_order",
            prospectingRequired = 3,
            miningRequired = 100,
            loadingRequired = 4,
            incidentTypes = listOf(MineIncidentType.CAVE_IN),
            miningMaterials = requirements.keys,
            miningRequirements = requirements,
        )
        val settings = MineZoneSettings(
            id = "old_shafts",
            priority = 1,
            reference = ZoneReference("mine", null, CuboidBounds(0, 0, 0, 10, 10, 10)),
            permission = "arcfarms.mine",
            cartQuota = 16,
            hazardTrigger = 6,
            supportsRequired = 1,
            restoreSeconds = 60,
            temporaryMaterial = "COBBLESTONE",
            baseMaterial = "STONE",
            materialWeights = linkedMapOf("STONE" to 100, "COAL_ORE" to 10, "COPPER_ORE" to 10, "IRON_ORE" to 10),
            miningOnly = true,
            engineVersion = 2,
            orders = listOf(order),
            incidentCountMin = 1,
            incidentCountMax = 1,
        )

        val migrated = MineRuntimeFactory.migrate(settings, MineShiftState(
            engineVersion = 2,
            phase = MinePhase.MINING,
            sequence = 3,
            orderId = "coal_order",
            mined = 32,
            cart = 32,
        ))

        migrated.mined shouldBe 32
        migrated.phase shouldBe MinePhase.MINING
        migrated.minedByMaterial shouldBe mapOf("COAL" to 25, "COPPER" to 7)
    }

    test("legacy ordinary and deepslate progress merges into one resource bucket") {
        val requirements = linkedMapOf("COAL" to 30, "IRON" to 20)
        val order = MineOrderSettings(
            id = "resource_order",
            prospectingRequired = 1,
            miningRequired = 100,
            loadingRequired = 1,
            incidentTypes = listOf(MineIncidentType.CAVE_IN),
            miningResources = requirements.keys,
            resourceRequirements = requirements,
        )
        val settings = MineZoneSettings(
            id = "old_shafts",
            priority = 1,
            reference = ZoneReference("mine", null, CuboidBounds(0, 0, 0, 10, 10, 10)),
            permission = "arcfarms.mine",
            cartQuota = 16,
            hazardTrigger = 6,
            supportsRequired = 1,
            restoreSeconds = 60,
            temporaryMaterial = "COBBLESTONE",
            baseMaterial = "STONE",
            materialWeights = linkedMapOf("STONE" to 100, "COAL_ORE" to 10, "DEEPSLATE_COAL_ORE" to 10, "IRON_ORE" to 10),
            miningOnly = true,
            engineVersion = 2,
            orders = listOf(order),
            incidentCountMin = 1,
            incidentCountMax = 1,
        )

        val migrated = MineRuntimeFactory.migrate(settings, MineShiftState(
            engineVersion = 2,
            phase = MinePhase.MINING,
            sequence = 1,
            orderId = "resource_order",
            mined = 19,
            cart = 19,
            minedByMaterial = mapOf("COAL_ORE" to 11, "DEEPSLATE_COAL_ORE" to 7, "IRON_ORE" to 1),
        ))

        migrated.mined shouldBe 19
        migrated.cart shouldBe 19
        migrated.minedByMaterial shouldBe mapOf("COAL" to 18, "IRON" to 1)
    }

    test("a reduced mining quota advances the order without discarding an ongoing incident or player credit") {
        val base = mineV2Settings().copy(miningOnly = true)
        val order = base.orders.single().copy(
            miningMaterials = emptySet(),
            resourceRequirements = mapOf("COAL" to 4, "IRON" to 2),
        )
        val settings = base.copy(orders = listOf(order), materialWeights = linkedMapOf("COAL_ORE" to 1, "IRON_ORE" to 1))
        val miner = UUID.randomUUID()
        val saved = MineShiftState(
            engineVersion = 2, phase = MinePhase.MINING, sequence = 4, orderId = order.id,
            mined = 9, cart = 9, minedByMaterial = mapOf("COAL_ORE" to 7, "DEEPSLATE_IRON_ORE" to 2),
            contributors = mapOf(miner to 9), incidentSchedule = listOf(MineIncidentType.GAS_LEAK),
        )
        val ready = MineRuntimeFactory.migrate(settings, saved)
        ready shouldBe saved.copy(phase = MinePhase.EXTRACTION, mined = 6, cart = 6, minedByMaterial = order.resourceRequirements)
        MineRuntimeFactory.migrate(settings, ready) shouldBe ready
        MineRuntimeFactory.migrate(settings, saved.copy(minedByMaterial = emptyMap())) shouldBe ready

        val suspended = MineShiftEngine.startIncident(saved, MineIncidentType.GAS_LEAK, 2, 100L).state
        val worked = MineShiftEngine.workIncident(suspended, miner).state
        val migrated = MineRuntimeFactory.migrate(settings, worked)
        migrated shouldBe worked.copy(resumePhase = MinePhase.EXTRACTION, mined = 6, cart = 6, minedByMaterial = order.resourceRequirements)
        val resolved = MineShiftEngine.resolveIncident(MineShiftEngine.workIncident(migrated, miner).state).state
        resolved.phase shouldBe MinePhase.EXTRACTION
        resolved.incidentCursor shouldBe 1

        val scalar = saved.copy(minedByMaterial = emptyMap())
        val scalarQuota = base.orders.single().miningRequired
        MineRuntimeFactory.migrate(base, scalar) shouldBe scalar.copy(phase = MinePhase.EXTRACTION, mined = scalarQuota, cart = scalarQuota)
    }

    test("a full restart retires orphaned mine state only when no block recovery remains") {
        val active = MineShiftState(
            engineVersion = 2,
            phase = MinePhase.MINING,
            sequence = 2,
            orderId = "retired_order",
        )

        ru.ruscrafting.farms.paper.MineController.validatePersisted(
            configured = emptyList(),
            persisted = mapOf("retired_depth" to active),
        )
        shouldThrow<IllegalArgumentException> {
            ru.ruscrafting.farms.paper.MineController.validatePersisted(
                configured = emptyList(),
                persisted = mapOf("retired_depth" to active),
                pendingRecoveryZoneIds = setOf("retired_depth"),
            )
        }.message shouldBe "Persisted mine zones retired_depth are missing from config with pending block recovery"
    }
})
