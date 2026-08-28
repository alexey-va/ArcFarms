package ru.ruscrafting.farms.paper.fixtures

import org.opentest4j.TestAbortedException

/** Prevents a large scenario from looking green when MockBukkit aborts an unsupported operation. */
internal inline fun <T> requiredMockBukkitScenario(block: () -> T): T = try {
    block()
} catch (failure: TestAbortedException) {
    throw AssertionError("MockBukkit aborted a required integration scenario", failure)
}
