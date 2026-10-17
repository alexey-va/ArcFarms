package ru.ruscrafting.farms.paper.worksite.scene

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

class WorksitePreparedSceneRecoveryFailureTest : FunSpec({
    test("successful and cancelled loaded chunk recovery stays quiet") {
        val logger = RecoveryLogger()
        val cancelled = CancellationException("Native work context changed")
        listOf(null, cancelled, CompletionException(CompletionException(cancelled))).forEach {
            logLoadedSceneRecoveryFailure(logger, it)
        }
        logger.records shouldBe emptyList()
    }

    test("genuine loaded chunk recovery failures retain the warning and original stack") {
        val logger = RecoveryLogger()
        val failure = CompletionException(IllegalArgumentException("Invalid scene journal"))
        logLoadedSceneRecoveryFailure(logger, failure)
        val record = logger.records.single()
        record.level shouldBe Level.WARNING
        record.message shouldBe "Could not recover loaded scene chunk"
        record.thrown shouldBe failure
    }
})

private class RecoveryLogger : Logger("scene-recovery-test", null) {
    val records = mutableListOf<LogRecord>()
    override fun log(record: LogRecord) { records += record }
}
