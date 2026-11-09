package edu.missouristate.mars.plugin.debug

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.Executor
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.pollAssertionsAsync
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.breakpoints.SuspendPolicy
import com.intellij.xdebugger.evaluation.EvaluationMode
import com.intellij.xdebugger.frame.XExecutionStack.XStackFrameContainer
import com.intellij.xdebugger.frame.XStackFrame
import edu.missouristate.mars.plugin.language.MipsFileType
import edu.missouristate.mars.plugin.run.MarsRunType
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@TestApplication
class MarsDebugPlatformTest {
    private val projectFixture = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(projectFixture)
    private val project get() = projectFixture.get()
    private val debugger get() = XDebuggerManager.getInstance(project)
    private val breakpointType get() = XDebuggerUtil.getInstance().findBreakpointType(MarsLineBreakpointType::class.java)

    @Test
    fun `native debugger stops in an include before its syscall and steps and runs to the cursor`() = timeoutRunBlocking<Unit> {
        val (entry, include) = withContext(Dispatchers.EDT) {
            val main = fixture.addFileToProject("main.s", ".include \"print.s\"\nnop\nnop\n").virtualFile
            val body = fixture.addFileToProject("print.s", "li \$a0, 42\nli \$v0, 1\nsyscall\nnop\n").virtualFile
            assertFalse(breakpointType.canPutAt(main, 0, project))
            assertTrue(breakpointType.canPutAt(body, 2, project))
            assertFalse(breakpointType.canPutAt(body, 12, project))
            assertFalse(breakpointType.canPutAt(LightVirtualFile("scratch.s", MipsFileType, "nop"), 0, project))
            val text = fixture.addFileToProject("other.txt", "nop").virtualFile
            assertFalse(breakpointType.canPutAt(text, 0, project))
            val directory = WriteAction.compute<VirtualFile, RuntimeException> {
                body.parent.createChildDirectory(this, "directory.s")
            }
            assertFalse(breakpointType.canPutAt(directory, 0, project))
            assertNull(breakpointType.createBreakpointProperties(body, 2))
            XDebuggerUtil.getInstance().toggleLineBreakpoint(project, breakpointType, body, 2)
            addBreakpoint(main, 0)
            addBreakpoint(main, 1).also { point ->
                WriteAction.run<RuntimeException> { point.setCondition("1") }
            }
            addBreakpoint(main, 2).also { point ->
                WriteAction.run<RuntimeException> { point.setLogExpression("1") }
            }
            WriteAction.run<RuntimeException> {
                debugger.breakpointManager.addLineBreakpoint(breakpointType, "${main.url}.missing", 0, null)
            }
            main to body
        }
        pollAssertionsAsync(5.seconds, 10.milliseconds) {
            withContext(Dispatchers.EDT) {
                assertEquals(1, debugger.breakpointManager.findBreakpointsAtLine(breakpointType, include, 2).size)
            }
        }
        withDebug(entry) { debug ->
            debug.assertPausedAt(include, 2)
            assertEquals("", debug.stdout.toString())
            withContext(Dispatchers.EDT) {
                val stack = requireNotNull(requireNotNull(debug.session.suspendContext).activeExecutionStack)
                stack.computeStackFrames(1, object : XStackFrameContainer {
                    override fun addStackFrames(stackFrames: List<XStackFrame>, last: Boolean) {
                        assertTrue(stackFrames.isEmpty())
                        assertTrue(last)
                    }
                    override fun errorOccurred(errorMessage: String) { fail<Unit>(errorMessage) }
                })
                val scratch = LightVirtualFile("scratch.s", MipsFileType, "nop")
                debug.session.runToPosition(XDebuggerUtil.getInstance().createPosition(scratch, 0)!!, false)
            }
            debug.assertPausedAt(include, 2)
            for (invalidLine in listOf(-1, Int.MAX_VALUE)) {
                withContext(Dispatchers.EDT) {
                    debug.session.runToPosition(XDebuggerUtil.getInstance().createPosition(entry, invalidLine)!!, false)
                }
                debug.assertPausedAt(include, 2)
            }
            withContext(Dispatchers.EDT) { debug.session.stepOver(false) }
            debug.assertPausedAt(include, 2)
            withContext(Dispatchers.EDT) { debug.session.stepOut() }
            debug.assertPausedAt(include, 2)
            withContext(Dispatchers.EDT) {
                debug.session.runToPosition(XDebuggerUtil.getInstance().createPosition(entry, 0)!!, false)
            }
            debug.assertPausedAt(include, 2)
            withContext(Dispatchers.EDT) { debug.session.stepInto() }
            debug.assertPausedAt(include, 3)
            assertEquals("42", debug.stdout.toString())
            withContext(Dispatchers.EDT) {
                debug.session.runToPosition(XDebuggerUtil.getInstance().createPosition(entry, 2)!!, false)
            }
            debug.assertPausedAt(entry, 2)
            withContext(Dispatchers.EDT) { debug.session.resume() }
            assertEquals(0, debug.exit.await())
        }
    }

    @Test
    fun `one breakpoint in a macro body stops at each expansion`() = timeoutRunBlocking<Unit> {
        val (entry, definitions) = withContext(Dispatchers.EDT) {
            val main = fixture.addFileToProject("macros.s", ".include \"defs.s\"\nemit (42)\nemit (43)\n").virtualFile
            val macros = fixture.addFileToProject("defs.s", """
                .macro emit (%value)
                  li ${'$'}a0, %value
                  li ${'$'}v0, 1
                  syscall
                .end_macro
            """.trimIndent()).virtualFile
            assertTrue(breakpointType.canPutAt(macros, 3, project))
            addBreakpoint(macros, 3)
            main to macros
        }
        withDebug(entry) { debug ->
            debug.assertPausedAt(definitions, 3)
            assertEquals("", debug.stdout.toString())
            withContext(Dispatchers.EDT) { debug.session.resume() }
            debug.assertPausedAt(definitions, 3)
            assertEquals("42", debug.stdout.toString())
            withContext(Dispatchers.EDT) { debug.session.resume() }
            assertEquals(0, debug.exit.await())
            assertEquals("4243", debug.stdout.toString())
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `suspension tolerates an instruction pointer without an assembled source line`(invalidAddress: Boolean) = timeoutRunBlocking<Unit> {
        val source = withContext(Dispatchers.EDT) {
            val assembly = if (invalidAddress) "jr \$zero\n" else "nop\n"
            fixture.addFileToProject("missing.s", assembly).virtualFile.also { addBreakpoint(it, 0) }
        }
        withDebug(source) { debug ->
            debug.assertPausedAt(source, 0)
            withContext(Dispatchers.EDT) { debug.session.stepInto() }
            assertNull(debug.nextPosition())
            withContext(Dispatchers.EDT) { debug.session.resume() }
            assertEquals(if (invalidAddress) 1 else 0, debug.exit.await())
        }
    }

    @Test
    fun `native logpoint continues and a disabled breakpoint can be enabled again`() = timeoutRunBlocking<Unit> {
        val source = withContext(Dispatchers.EDT) {
            val file = fixture.addFileToProject("loop.s", "nop\nli \$v0, 1\nsyscall\nloop: b loop\nnop\n").virtualFile
            val log = addBreakpoint(file, 0)
            val stop = addBreakpoint(file, 3)
            WriteAction.run<RuntimeException> {
                log.suspendPolicy = SuspendPolicy.NONE
                stop.isEnabled = false
            }
            file to stop
        }
        withDebug(source.first) { debug ->
            debug.output.await()
            withContext(Dispatchers.EDT) { debug.session.pause() }
            debug.assertPausedAt(source.first, 3)
            withContext(Dispatchers.EDT) {
                debug.session.setBreakpointMuted(true)
                WriteAction.run<RuntimeException> { source.second.isEnabled = true }
                debug.session.setBreakpointMuted(false)
                debug.session.resume()
            }
            debug.assertPausedAt(source.first, 3)
            withContext(Dispatchers.EDT) { debug.session.stop() }
            assertEquals(130, debug.exit.await())
        }
    }

    private fun addBreakpoint(file: VirtualFile, line: Int) =
        WriteAction.compute<_, RuntimeException> { debugger.breakpointManager.addLineBreakpoint(breakpointType, file.url, line, null) }

    private suspend fun withDebug(file: VirtualFile, action: suspend (DebugLaunch) -> Unit) {
        val disposable = Disposer.newDisposable()
        val started = CompletableDeferred<DebugLaunch>()
        try {
            withContext(Dispatchers.EDT) {
                val config = ConfigurationTypeUtil.findConfigurationType(MarsRunType::class.java).createTemplateConfiguration(project)
                config.options.sourcePath = file.path
                val environment = ExecutionEnvironmentBuilder.create(DefaultDebugExecutor.getDebugExecutorInstance(), config).build()
                val runner = assertInstanceOf(MarsDebugRunner::class.java, environment.runner)
                assertFalse(runner.canRun(DefaultRunExecutor.EXECUTOR_ID, config))
                assertFalse(runner.canRun(environment.executor.id, object : RunProfile {
                    override fun getName() = "Other"
                    override fun getIcon() = null
                    override fun getState(executor: Executor, environment: ExecutionEnvironment) = null
                }))
                project.messageBus.connect(disposable).subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
                    override fun processStarting(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
                        if (env !== environment) return
                        val session = debugger.debugSessions.single()
                        session.debugProcess.startPausing()
                        session.debugProcess.startStepOver(null)
                        val editors = session.debugProcess.editorsProvider
                        val expression = XDebuggerUtil.getInstance().createExpression("1", null, null, EvaluationMode.EXPRESSION)
                        assertEquals("1", editors.createDocument(project, expression, null, EvaluationMode.EXPRESSION).text)
                        val debug = DebugLaunch(session)
                        session.addSessionListener(debug, disposable)
                        handler.addProcessListener(debug)
                        started.complete(debug)
                    }
                })
                runner.execute(environment)
            }
            action(started.await())
        } finally {
            withContext(Dispatchers.EDT) {
                debugger.debugSessions.forEach { it.stop() }
                Disposer.dispose(disposable)
            }
        }
    }

    private class DebugLaunch(val session: XDebugSession) : ProcessListener, XDebugSessionListener {
        val stdout = StringBuilder()
        val exit = CompletableDeferred<Int>()
        val output = CompletableDeferred<Unit>()
        private val positions = Channel<XSourcePosition?>(Channel.UNLIMITED)

        override fun sessionPaused() { positions.trySend(session.topFramePosition) }

        suspend fun nextPosition() = positions.receive()

        suspend fun assertPausedAt(file: VirtualFile, line: Int) {
            val position = requireNotNull(nextPosition())
            assertEquals(file, position.file)
            assertEquals(line, position.line)
        }

        override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
            if (outputType === ProcessOutputTypes.STDOUT) {
                stdout.append(event.text)
                output.complete(Unit)
            }
        }

        override fun processTerminated(event: ProcessEvent) { exit.complete(event.exitCode) }
    }
}
