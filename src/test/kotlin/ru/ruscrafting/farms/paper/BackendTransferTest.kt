package ru.ruscrafting.farms.paper

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.io.DataInputStream

class BackendTransferTest : StringSpec({
    "BungeeCord connect payload contains only the bounded destination server" {
        DataInputStream(ByteArrayInputStream(BungeeBackendTransfer.encodeConnectMessage("spawn"))).use { input ->
            input.readUTF() shouldBe "Connect"
            input.readUTF() shouldBe "spawn"
            input.available() shouldBe 0
        }
        shouldThrow<IllegalArgumentException> { BungeeBackendTransfer.encodeConnectMessage("spawn;op") }
    }
})
