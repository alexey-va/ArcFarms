package ru.ruscrafting.farms.paper

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import ru.arc.network.BackendServerId
import ru.arc.paper.network.BungeeConnectPayload

class BackendTransferTest : StringSpec({
    "BungeeCord connect payload contains only the bounded destination server" {
        DataInputStream(ByteArrayInputStream(BungeeConnectPayload.encode(BackendServerId.of("spawn")))).use { input ->
            input.readUTF() shouldBe "Connect"
            input.readUTF() shouldBe "spawn"
            input.available() shouldBe 0
        }
        shouldThrow<IllegalArgumentException> { BackendServerId.of("spawn;op") }
    }
})
