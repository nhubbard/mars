package edu.missouristate.mars.plugin.learning

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.NopProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.testFramework.TestActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.Disposable
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugProcessStarter
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import edu.missouristate.mars.plugin.MarsExecutionOptions
import edu.missouristate.mars.plugin.debug.MarsDebugRunner
import edu.missouristate.mars.plugin.debug.MarsDebugEditors
import edu.missouristate.mars.plugin.debug.MarsLineBreakpointType
import edu.missouristate.mars.plugin.debug.MarsSuspendContext
import edu.missouristate.mars.plugin.run.runMessages
import edu.missouristate.mars.plugin.run.MarsRunService
import edu.missouristate.mars.plugin.run.MarsRunType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@TestApplication
class MarsConsoleDebugCheckpointTest {
    private val projectFixture = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(projectFixture)
    private val example = MarsLearningWorkspace::class.java
        .getResourceAsStream("/learnProjects/mars_mips/MARSLearningProject/first-program.asm")!!.use { it.readBytes().toString(Charsets.UTF_8) }

    @Test
    fun `native Debug observes input doubling and increment in one session without mutating it`() = timeoutRunBlocking {
        debug { checkpoint, session, pauses ->
            val before = pauses.receive()
            assertEquals(1, checkpoint.phase)
            assertEquals(MarsConsoleDebugCheckpoint.ADDU_T0_V0_V0, before.frame.evaluator.instructionWord)
            assertEquals(20L, before.frame.evaluator.integerRegisterBits("\$v0"))
            assertEquals(0L, before.frame.evaluator.integerRegisterBits("\$zero"))
            assertThrows<IllegalStateException> { before.frame.evaluator.integerRegisterBits("\$f0") }
            assertThrows<NoSuchElementException> { before.frame.evaluator.integerRegisterBits("unknown") }
            withContext(Dispatchers.EDT) { session.stepOver(false) }
            val doubled = pauses.receive()
            assertEquals(2, checkpoint.phase)
            assertEquals(40L, doubled.frame.evaluator.integerRegisterBits("\$t0"))
            withContext(Dispatchers.EDT) { session.stepOver(false) }
            val incremented = pauses.receive()
            assertEquals(3, checkpoint.phase)
            assertEquals(41L, incremented.frame.evaluator.integerRegisterBits("\$t0"))
            assertEquals(0L, before.frame.evaluator.integerRegisterBits("\$t0"))
            withContext(Dispatchers.EDT) { session.resume() }
        }
    }

    @Test
    fun `native output pauses distinguish the retained answer argument and selector`() = timeoutRunBlocking {
        debug(segment = MarsConsoleDebugCheckpoint.Segment.OUTPUT) { checkpoint, session, pauses ->
            val before = pauses.receive()
            assertEquals(MarsConsoleDebugCheckpoint.MOVE_A0_T0, before.frame.evaluator.instructionWord)
            assertEquals(1, checkpoint.phase, "v0=${before.frame.evaluator.integerRegisterBits("\$v0")} t0=${before.frame.evaluator.integerRegisterBits("\$t0")}")
            assertEquals(4L, before.frame.evaluator.integerRegisterBits("\$v0"))
            assertEquals(41L, before.frame.evaluator.integerRegisterBits("\$t0"))
            withContext(Dispatchers.EDT) { session.stepOver(false) }
            val argument = pauses.receive()
            assertEquals(MarsConsoleDebugCheckpoint.LI_V0_ONE, argument.frame.evaluator.instructionWord)
            assertEquals(2, checkpoint.phase)
            assertEquals(41L, argument.frame.evaluator.integerRegisterBits("\$a0"))
            assertEquals(4L, argument.frame.evaluator.integerRegisterBits("\$v0"))
            withContext(Dispatchers.EDT) { session.stepOver(false) }
            val selector = pauses.receive()
            assertEquals(3, checkpoint.phase)
            assertEquals(1L, selector.frame.evaluator.integerRegisterBits("\$v0"))
            assertEquals(41L, selector.frame.evaluator.integerRegisterBits("\$a0"))
            assertEquals(41L, selector.frame.evaluator.integerRegisterBits("\$t0"))
            assertEquals(4L, before.frame.evaluator.integerRegisterBits("\$v0"))
        }
    }

    @Test
    fun `edited source invalidates partial observations even after text is restored`() = timeoutRunBlocking {
        debug { checkpoint, session, pauses ->
            pauses.receive()
            assertEquals(1, checkpoint.phase)
            withContext(Dispatchers.EDT) {
                val document = fixture.editor.document
                WriteCommandAction.runWriteCommandAction(projectFixture.get()) { document.setText(example + "\n# edited\n") }
                assertEquals(0, checkpoint.phase)
                WriteCommandAction.runWriteCommandAction(projectFixture.get()) { document.setText(example) }
                assertEquals(0, checkpoint.phase)
                session.stepOver(false)
            }
            pauses.receive()
            assertEquals(0, checkpoint.phase)
        }
    }

    @Test
    fun `irrelevant handlers executors and unsupported launches do not become Debug observations`() = timeoutRunBlocking {
        val project = projectFixture.get()
        val lifetime = Disposer.newDisposable()
        withContext(Dispatchers.EDT) {
            val file = fixture.addFileToProject("ignored.asm", example).virtualFile
            val document = FileDocumentManager.getInstance().getDocument(file)!!
            val checkpoint = project.service<MarsLearningExecution>().observeDebug(lifetime, file.toNioPath(), document)
            val runs = project.service<MarsRunService>()
            val handler = runs.createProcess(file.toNioPath(), memoryDumps = emptyList())
            val wrongOptions = runs.createProcess(file.toNioPath(), options = MarsExecutionOptions(delayedBranching = true), memoryDumps = emptyList())
            try {
                val foreign = XDebuggerManager.getInstance(project).newSessionBuilder(object : XDebugProcessStarter() {
                    override fun start(session: XDebugSession) = object : XDebugProcess(session) {
                        private val nop = NopProcessHandler()
                        override fun doGetProcessHandler() = nop
                        override fun getEditorsProvider() = MarsDebugEditors
                        override fun stop() = nop.destroyProcess()
                    }
                }).sessionName("Unrelated debugger").showTab(true).startSession().session
                checkpoint.starting(DefaultRunExecutor.EXECUTOR_ID, handler)
                checkpoint.starting(DefaultDebugExecutor.EXECUTOR_ID, NopProcessHandler())
                checkpoint.starting(DefaultDebugExecutor.EXECUTOR_ID, wrongOptions)
                checkpoint.starting(DefaultDebugExecutor.EXECUTOR_ID, handler) // No debugger session owns it.
                assertEquals(0, checkpoint.phase)
                foreign.stop()
                Disposer.dispose(lifetime)
                checkpoint.starting(DefaultDebugExecutor.EXECUTOR_ID, handler)
                assertEquals(0, checkpoint.phase)
            } finally {
                handler.destroyProcess()
                wrongOptions.destroyProcess()
                Disposer.dispose(lifetime)
            }
        }
    }

    @Test
    fun `an additional include cannot satisfy the worked single-file trace`() = timeoutRunBlocking {
        withContext(Dispatchers.EDT) { fixture.addFileToProject("mars-labs/console/extra.inc", "# extra source\n") }
        debug(source = example + ".include \"extra.inc\"\n") { checkpoint, _, pauses ->
            pauses.receive()
            assertEquals(0, checkpoint.phase)
        }
    }

    @Test
    fun `an instruction pause beyond the last source operation is not a source observation`() = timeoutRunBlocking {
        debug(source = example.substringBefore("report:")) { checkpoint, session, pauses ->
            pauses.receive()
            withContext(Dispatchers.EDT) { session.stepOver(false) }
            pauses.receive()
            assertEquals(2, checkpoint.phase)
            withContext(Dispatchers.EDT) {
                val top = DefaultActionGroup()
                session.debugProcess.registerAdditionalActions(DefaultActionGroup(), top, DefaultActionGroup())
                val action = top.getChildren(null).single { it.templatePresentation.text == runMessages.getMessage("debug.step.instruction") }
                action.actionPerformed(TestActionEvent.createTestEvent())
            }
            val beyond = pauses.receive()
            assertEquals(null, beyond.frame.sourcePosition)
            assertEquals(2, checkpoint.phase)
        }
    }

    @Test
    fun `a new Debug attempt supersedes the first without mixing their pauses or cleanup`() = timeoutRunBlocking {
        val project = projectFixture.get()
        val lifetime = Disposer.newDisposable()
        val launches = mutableListOf<Launch>()
        try {
            val (file, checkpoint) = withContext(Dispatchers.EDT) {
                val file = fixture.addFileToProject("mars-labs/console/first-program.asm", example).virtualFile
                fixture.configureFromExistingVirtualFile(file)
                val type = XDebuggerUtil.getInstance().findBreakpointType(MarsLineBreakpointType::class.java)
                WriteCommandAction.runWriteCommandAction(project) {
                    XDebuggerManager.getInstance(project).breakpointManager.addLineBreakpoint(type, file.url,
                        example.lines().indexOfFirst { it.trim().startsWith("addu ") }, null)
                }
                file to project.service<MarsLearningExecution>().observeDebug(lifetime, file.toNioPath(), fixture.editor.document)
            }
            val first = start(file, lifetime).also(launches::add)
            first.pauses.receive()
            assertEquals(1, checkpoint.phase)
            val second = start(file, lifetime).also(launches::add)
            second.pauses.receive()
            assertEquals(1, checkpoint.phase)
            withContext(Dispatchers.EDT) { first.session.stepOver(false) }
            first.pauses.receive()
            assertEquals(1, checkpoint.phase)
            withContext(Dispatchers.EDT) { first.session.stop() }
            first.stopped.await()
            assertEquals(1, checkpoint.phase)
            withContext(Dispatchers.EDT) { second.session.stepOver(false) }
            second.pauses.receive()
            assertEquals(2, checkpoint.phase)
            withContext(Dispatchers.EDT) { second.session.stepOver(false) }
            second.pauses.receive()
            assertEquals(3, checkpoint.phase)
        } finally {
            withContext(Dispatchers.EDT) { XDebuggerManager.getInstance(project).debugSessions.forEach { it.stop() } }
            for (launch in launches) launch.stopped.await()
            withContext(Dispatchers.EDT) { Disposer.dispose(lifetime) }
        }
    }

    private data class Launch(
        val session: XDebugSession, val pauses: Channel<MarsSuspendContext>, val stopped: CompletableDeferred<Unit>,
    )

    private suspend fun start(file: VirtualFile, lifetime: Disposable): Launch {
        val project = projectFixture.get()
        val started = CompletableDeferred<XDebugSession>()
        val pauses = Channel<MarsSuspendContext>(Channel.UNLIMITED)
        val stopped = CompletableDeferred<Unit>()
        val debugger = XDebuggerManager.getInstance(project)
        withContext(Dispatchers.EDT) {
            val config = ConfigurationTypeUtil.findConfigurationType(MarsRunType::class.java).createTemplateConfiguration(project)
            config.options.sourcePath = file.path
            val env = ExecutionEnvironmentBuilder.create(DefaultDebugExecutor.getDebugExecutorInstance(), config).build()
            project.messageBus.connect(lifetime).subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
                override fun processStarting(executorId: String, environment: ExecutionEnvironment, handler: ProcessHandler) {
                    if (environment !== env) return
                    val session = debugger.debugSessions.single { it.debugProcess.processHandler === handler }
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
            session.debugProcess.processHandler.processInput!!.write("20\n".toByteArray())
            session.debugProcess.processHandler.processInput!!.flush()
        }
        return Launch(session, pauses, stopped)
    }

    private suspend fun debug(source: String = example, segment: MarsConsoleDebugCheckpoint.Segment = MarsConsoleDebugCheckpoint.Segment.ARITHMETIC, body: suspend (MarsConsoleDebugCheckpoint, XDebugSession, Channel<MarsSuspendContext>) -> Unit) {
        val project = projectFixture.get()
        val lifetime = Disposer.newDisposable()
        var launch: Launch? = null
        try {
            val (file, checkpoint) = withContext(Dispatchers.EDT) {
                val file = fixture.addFileToProject("mars-labs/console/first-program.asm", source).virtualFile
                fixture.configureFromExistingVirtualFile(file)
                val checkpoint = if (segment == MarsConsoleDebugCheckpoint.Segment.OUTPUT)
                    project.service<MarsLearningExecution>().observeOutput(lifetime, file.toNioPath(), fixture.editor.document)
                else project.service<MarsLearningExecution>().observeDebug(lifetime, file.toNioPath(), fixture.editor.document)
                assertEquals(0, checkpoint.phase)
                val type = XDebuggerUtil.getInstance().findBreakpointType(MarsLineBreakpointType::class.java)
                WriteCommandAction.runWriteCommandAction(project) {
                    XDebuggerManager.getInstance(project).breakpointManager.addLineBreakpoint(type, file.url,
                        source.lines().indexOfFirst { it.trim().startsWith(if (segment == MarsConsoleDebugCheckpoint.Segment.OUTPUT) "move " else "addu ") }, null)
                }
                file to checkpoint
            }
            val current = start(file, lifetime)
            launch = current
            body(checkpoint, current.session, current.pauses)
        } finally {
            withContext(Dispatchers.EDT) { XDebuggerManager.getInstance(project).debugSessions.forEach { it.stop() } }
            launch?.stopped?.await()
            withContext(Dispatchers.EDT) { Disposer.dispose(lifetime) }
            launch?.pauses?.close()
        }
    }
}
