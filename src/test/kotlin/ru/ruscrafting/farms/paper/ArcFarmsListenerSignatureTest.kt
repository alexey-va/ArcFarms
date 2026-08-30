package ru.ruscrafting.farms.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.bukkit.event.EventHandler

class ArcFarmsListenerSignatureTest : FunSpec({
    test("every Bukkit event handler returns void") {
        val handlers = ArcFarmsListener::class.java.declaredMethods
            .filter { it.isAnnotationPresent(EventHandler::class.java) }

        handlers.shouldNotBeEmpty()
        handlers.forEach { method ->
            method.returnType shouldBe Void.TYPE
        }
    }
})
