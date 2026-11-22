@file:Suppress("OverrideOnly")

package edu.missouristate.mars.plugin.debug

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.frame.XValueModifier.XModificationCallback
import edu.missouristate.mars.api.InstructionSourceMap.Location
import edu.missouristate.mars.plugin.run.MarsRunType
import edu.missouristate.mars.plugin.run.runMessages
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** Exercise the real debugger rather than constructing synthetic suspension notifications. */
@TestApplication
class MarsDebugObservationPlatformTest {
    private val projects = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(projects)
    private val example = javaClass.getResourceAsStream("/learning/byte-inspector.asm")!!
        .use { it.readBytes().toString(Charsets.UTF_8) }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `history revision distinguishes forward stepping from edits and both reverse commands`(sourceReverse: Boolean) = timeoutRunBlocking {
        withDebug("andi") { launch, _ ->
            val before = launch.pauses.receive()
            val original = before.frame.evaluator
            assertEquals(0L, before.executionRevision)
            assertEquals(150L, original.integerRegisterBits("\$v0"))
            assertEquals(0L, original.integerRegisterBits("\$t0"))

            // Failed and no-op modifications cannot create a fictitious history change.
            assertTrue(edit(before, "unknown_symbol") != null)
            assertEquals(0L, before.executionRevision)
            assertNull(edit(before, "0"))
            assertEquals(0L, before.executionRevision)
            assertNull(edit(before, "7"))
            assertEquals(1L, before.executionRevision)
            assertEquals(7L, before.frame.evaluator.integerRegisterBits("\$t0"))
            assertEquals(0L, original.integerRegisterBits("\$t0"), "Retained snapshots must not change after a machine edit")

            withContext(Dispatchers.EDT) { launch.session.stepOver(false) }
            val masked = launch.pauses.receive()
            assertTrue(masked.number > before.number)
            assertEquals(1L, masked.executionRevision)
            assertEquals(150L, masked.frame.evaluator.integerRegisterBits("\$t0"))

            command(launch.session, if (sourceReverse) "debug.backstep.source" else "debug.backstep")
            val reversed = launch.pauses.receive()
            assertTrue(reversed.number > masked.number)
            assertEquals(2L, reversed.executionRevision)
            assertEquals(7L, reversed.frame.evaluator.integerRegisterBits("\$t0"))
            assertEquals(150L, masked.frame.evaluator.integerRegisterBits("\$t0"))
            withContext(Dispatchers.EDT) { launch.session.stepOver(false) }
            assertEquals(2L, launch.pauses.receive().executionRevision, "Forward stepping does not erase history")
        }
    }

    @Test
    fun `source map identifies the first emitted instruction of a multi-instruction pseudo operation`() = timeoutRunBlocking {
        withDebug("la", label = "report:") { launch, file ->
            val first = launch.pauses.receive()
            val evaluator = first.frame.evaluator
            val line = first.frame.sourcePosition!!.line
            val location = Location(file.toNioPath(), line + 1)
            val addresses = evaluator.instructionSources.breakpointsByAddress.getValue(location)
            assertEquals(listOf(evaluator.integerRegisterBits("pc")), addresses)
            assertThrows<UnsupportedOperationException> { (addresses as MutableList).clear() }
            assertThrows<UnsupportedOperationException> { (evaluator.instructionSources.breakpointsByAddress as MutableMap).clear() }

            command(launch.session, "debug.step.instruction")
            val second = launch.pauses.receive()
            assertEquals(line, second.frame.sourcePosition!!.line)
            assertEquals(evaluator.integerRegisterBits("pc") + 4, second.frame.evaluator.integerRegisterBits("pc"))
            assertEquals(addresses, second.frame.evaluator.instructionSources.breakpointsByAddress.getValue(location))
            assertEquals(0L, second.executionRevision)

            withContext(Dispatchers.EDT) { launch.session.stepOver(false) }
            val next = launch.pauses.receive()
            assertTrue(next.frame.sourcePosition!!.line > line)
            assertEquals(0L, next.executionRevision)
            assertEquals(evaluator.instructionSources, next.frame.evaluator.instructionSources)
        }
    }

    @Test
    fun `overlapping sessions keep their execution revisions and register snapshots independent`() = timeoutRunBlocking {
        withDebug("andi") { first, file ->
            val firstPause = first.pauses.receive()
            assertNull(edit(firstPause, "7"))
            val lifetime = Disposer.newDisposable()
            var second: Launch? = null
            try {
                second = start(file, lifetime)
                val secondPause = second.pauses.receive()
                assertEquals(0L, secondPause.executionRevision)
                assertEquals(0L, secondPause.frame.evaluator.integerRegisterBits("\$t0"))
                assertEquals(1L, firstPause.executionRevision)
                assertEquals(7L, firstPause.frame.evaluator.integerRegisterBits("\$t0"))
                assertEquals(firstPause.frame.evaluator.instructionSources, secondPause.frame.evaluator.instructionSources)
                withContext(Dispatchers.EDT) { second.session.stepOver(false) }
                assertEquals(0L, second.pauses.receive().executionRevision)
                assertEquals(1L, firstPause.executionRevision)
            } finally {
                second?.let { stop(it) }
                withContext(Dispatchers.EDT) { Disposer.dispose(lifetime) }
            }
        }
    }

    private suspend fun edit(context: MarsSuspendContext, expression: String): String? {
        val result = CompletableDeferred<String?>()
        context.machineEdits.submitEdit(RegisterEditRequest(RegisterBank.CPU, 8, expression, object : XModificationCallback {
            override fun valueModified() { result.complete(null) }
            override fun errorOccurred(errorMessage: String) { result.complete(errorMessage) }
        }))
        return result.await()
    }

    private suspend fun command(session: XDebugSession, key: String) = withContext(Dispatchers.EDT) {
        val toolbar = DefaultActionGroup()
        session.debugProcess.registerAdditionalActions(DefaultActionGroup(), toolbar, DefaultActionGroup())
        toolbar.getChildren(null).single { it.templatePresentation.text == runMessages.getMessage(key) }
            .actionPerformed(TestActionEvent.createTestEvent())
    }

    private data class Launch(val session: XDebugSession, val pauses: Channel<MarsSuspendContext>, val stopped: CompletableDeferred<Unit>)

    private suspend fun start(file: VirtualFile, lifetime: Disposable): Launch {
        val project = projects.get()
        val started = CompletableDeferred<XDebugSession>()
        val pauses = Channel<MarsSuspendContext>(Channel.UNLIMITED)
        val stopped = CompletableDeferred<Unit>()
        withContext(Dispatchers.EDT) {
            val config = ConfigurationTypeUtil.findConfigurationType(MarsRunType::class.java).createTemplateConfiguration(project)
            config.options.sourcePath = file.path
            val env = ExecutionEnvironmentBuilder.create(DefaultDebugExecutor.getDebugExecutorInstance(), config).build()
            project.messageBus.connect(lifetime).subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
                override fun processStarting(executorId: String, environment: ExecutionEnvironment, handler: ProcessHandler) {
                    if (environment !== env) return
                    val session = XDebuggerManager.getInstance(project).debugSessions.single { it.debugProcess.processHandler === handler }
                    session.addSessionListener(object : XDebugSessionListener {
                        override fun sessionStopped() { stopped.complete(Unit) }
                        override fun sessionPaused() {
                            pauses.trySend(assertInstanceOf(MarsSuspendContext::class.java, session.suspendContext))
                        }
                    }, lifetime)
                    started.complete(session)
                }
            })
            assertInstanceOf(MarsDebugRunner::class.java, env.runner).execute(env)
        }
        val session = started.await()
        withContext(Dispatchers.IO) {
            session.debugProcess.processHandler.processInput!!.apply { write("150\n".toByteArray()); flush() }
        }
        return Launch(session, pauses, stopped)
    }

    private suspend fun stop(launch: Launch) {
        withContext(Dispatchers.EDT) { launch.session.stop() }
        launch.stopped.await()
        launch.pauses.close()
    }

    private suspend fun withDebug(mnemonic: String, label: String = "interpret:", body: suspend (Launch, VirtualFile) -> Unit) {
        val project = projects.get()
        val lifetime = Disposer.newDisposable()
        var launch: Launch? = null
        try {
            val file = withContext(Dispatchers.EDT) {
                val file = fixture.addFileToProject("byte-inspector.asm", example).virtualFile
                fixture.configureFromExistingVirtualFile(file)
                val start = example.indexOf(label)
                val offset = example.indexOf("$mnemonic ", start)
                assertTrue(offset >= 0)
                val line = example.take(offset).count { it == '\n' }
                val type = XDebuggerUtil.getInstance().findBreakpointType(MarsLineBreakpointType::class.java)
                WriteCommandAction.runWriteCommandAction(project) {
                    XDebuggerManager.getInstance(project).breakpointManager.addLineBreakpoint(type, file.url, line, null)
                }
                file
            }
            val current = start(file, lifetime)
            launch = current
            body(current, file)
        } finally {
            launch?.let { stop(it) }
            withContext(Dispatchers.EDT) { Disposer.dispose(lifetime) }
        }
    }
}
