package ru.ruscrafting.farms.paper.fixtures

import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.paper.ArcFarmsPlugin
import ru.ruscrafting.farms.paper.ArcFarmsService
import java.util.concurrent.CompletableFuture

/** Pump native continuations while the real bounded preparation/writer runs. */
internal fun MockBukkitTestRuntime.awaitFarmsReady(plugin: ArcFarmsPlugin) {
    awaitFarmsCondition("plugin readiness") {
        check(plugin.isEnabled) { "ArcFarms failed during asynchronous startup" }
        (plugin.field("service") as? ArcFarmsService)?.isOperational() == true
    }
}

internal fun MockBukkitTestRuntime.awaitFarmsReload(plugin: ArcFarmsPlugin) {
    awaitFarmsCondition("reload completion") { plugin.field("reloading") == false }
    check(plugin.isEnabled) { "ArcFarms failed during asynchronous reload" }
}

internal fun MockBukkitTestRuntime.drainFarmsPlugin() {
    val plugin = server.pluginManager.getPlugin("ArcFarms") as? ArcFarmsPlugin ?: return
    server.pluginManager.disablePlugin(plugin)
    val drain = plugin.field("shutdownDrain") as CompletableFuture<*>
    awaitFarmsCondition("terminal writer drain") { drain.isDone }
    drain.join() // Already complete; preserve exceptional terminal outcomes.
}

private fun MockBukkitTestRuntime.awaitFarmsCondition(label: String, ready: () -> Boolean) {
    val deadline = System.nanoTime() + 10_000_000_000L
    while (!ready()) {
        check(System.nanoTime() < deadline) { "Timed out waiting for ArcFarms $label" }
        performTicks(1)
        Thread.sleep(1)
    }
}

private fun ArcFarmsPlugin.field(name: String): Any? = ArcFarmsPlugin::class.java
    .getDeclaredField(name).also { it.isAccessible = true }.get(this)
