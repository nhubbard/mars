package edu.missouristate.mars.plugin.run

import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import edu.missouristate.mars.plugin.MarsExecutionOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@TestApplication
class MarsRunIntegerObservationTest {
    private val projects = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(projects)

    @Test
    fun `instruction listeners read complete integer values without consuming or changing input`() = timeoutRunBlocking {
        val file = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("observe.asm", """
                .text
                main:
                    dli ${'$'}t0, 0xfedcba9876543210
                    li ${'$'}v0, 5
                    syscall
                    move ${'$'}t1, ${'$'}v0
                    li ${'$'}v0, 10
                    syscall
            """.trimIndent()).virtualFile
        }
        val handler = projects.get().service<MarsRunService>().createProcess(file.toNioPath(),
            options = MarsExecutionOptions(), memoryDumps = emptyList())
        assertNull(handler.integerRegisterBits(8))
        assertThrows<IllegalArgumentException> { handler.integerRegisterBits(-1) }
        assertThrows<IllegalArgumentException> { handler.integerRegisterBits(32) }
        val lifetime = Disposer.newDisposable()
        val observed = mutableListOf<Pair<Int, Long>>()
        handler.addInstructionListener({ event -> observed += event.instruction to handler.integerRegisterBits(2)!! }, lifetime)
        val stopped = CompletableDeferred<Int>()
        handler.addProcessListener(object : ProcessListener {
            override fun processTerminated(event: ProcessEvent) { stopped.complete(event.exitCode) }
        })
        try {
            withContext(Dispatchers.EDT) { handler.startNotify() }
            withContext(Dispatchers.IO) { handler.processInput.write("150\n".toByteArray()); handler.processInput.flush() }
            assertEquals(0, stopped.await())
            assertEquals(listOf(150L, 10L), observed.filter { it.first == 12 }.map { it.second })
            assertEquals(150L, handler.integerRegisterBits(9))
            assertEquals(0xfedcba9876543210uL.toLong(), handler.integerRegisterBits(8))
            assertEquals(0L, handler.integerRegisterBits(0))
        } finally { Disposer.dispose(lifetime); if (!handler.isProcessTerminated) handler.destroyProcess() }
    }

    @Test
    fun `a cancelled input wait leaves a detached register snapshot`() = timeoutRunBlocking {
        val file = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("cancel.asm", ".text\nli ${'$'}v0, 5\nsyscall\n").virtualFile
        }
        val handler = projects.get().service<MarsRunService>().createProcess(file.toNioPath(),
            options = MarsExecutionOptions(), memoryDumps = emptyList())
        val waiting = CompletableDeferred<Unit>(); val stopped = CompletableDeferred<Unit>()
        val lifetime = Disposer.newDisposable()
        handler.addInstructionListener({ if (handler.integerRegisterBits(2) == 5L) waiting.complete(Unit) }, lifetime)
        handler.addProcessListener(object : ProcessListener {
            override fun processTerminated(event: ProcessEvent) { stopped.complete(Unit) }
        })
        try {
            withContext(Dispatchers.EDT) { handler.startNotify() }
            waiting.await(); handler.destroyProcess(); stopped.await()
            assertEquals(5L, handler.integerRegisterBits(2))
        } finally { Disposer.dispose(lifetime); if (!handler.isProcessTerminated) handler.destroyProcess() }
    }
}
