@file:Suppress("OverrideOnly")

package edu.missouristate.mars.plugin.learning

import com.intellij.execution.ExecutionManager
import com.intellij.execution.configurations.runConfigurationType
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.NopProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugProcessStarter
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.frame.XValueModifier.XModificationCallback
import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.cli.grading.FunctionFileReadCheck
import edu.missouristate.mars.mips.hardware.VectorRegister
import edu.missouristate.mars.plugin.MarsExecutionOptions
import edu.missouristate.mars.plugin.build.MarsAssemblySettings
import edu.missouristate.mars.plugin.debug.MarsDebugEditors
import edu.missouristate.mars.plugin.debug.MarsDebugRunner
import edu.missouristate.mars.plugin.debug.MarsLineBreakpointType
import edu.missouristate.mars.plugin.debug.MarsSuspendContext
import edu.missouristate.mars.plugin.debug.RegisterBank
import edu.missouristate.mars.plugin.debug.RegisterEditRequest
import edu.missouristate.mars.plugin.debug.debugSessionListener
import edu.missouristate.mars.plugin.debug.findBreakpointType
import edu.missouristate.mars.plugin.debug.submitEdit
import edu.missouristate.mars.plugin.learning.MarsDownloadedDebugCheckpoint.Outcome
import edu.missouristate.mars.plugin.run.MarsRunService
import edu.missouristate.mars.plugin.run.MarsRunType
import edu.missouristate.mars.plugin.run.executionListener
import edu.missouristate.mars.plugin.run.runMessages
import edu.missouristate.mars.util.javaClassOf
import java.util.IdentityHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@TestApplication
class MarsDownloadedDebugCheckpointTest {
  private val projects = codeInsightProjectFixture()
  private val fixture by codeInsightFixture(projects)
  private val example =
      javaClass.getResourceAsStream("/learning/byte-inspector.asm")!!.use {
        it.readBytes().toString(Charsets.UTF_8)
      }
  private val identity = MarsLessonIdentity("mars-book", "byte.debug", "0.3.0", "d".repeat(64))
  private var fileNumber = 0

  private fun stop(id: String, label: String, mnemonic: String, registers: Map<String, Long>) =
      MarsNativeDebugStop(
          id,
          "Inspect $id.",
          MarsLessonAnchor(label, mnemonic, 0),
          registers,
          "Explain $id.",
          emptyList(),
      )

  private val contract =
      MarsNativeDebugStep(
          "trace-150",
          "Debug with 150.",
          listOf(150),
          listOf(
              stop("before-mask", "interpret", "andi", mapOf($$"$v0" to 150L)),
              stop("after-mask", "interpret", "seb", mapOf($$"$v0" to 150L, $$"$t0" to 150L)),
              stop("after-extension", "report", "la", mapOf($$"$t0" to 150L, $$"$t1" to -106L)),
          ),
          "Explain the whole register.",
          emptyList(),
      )

  @Test
  fun `normal native Debug records all ordered boundaries and resumes a saved completed observation`() =
      timeoutRunBlocking {
        withDebug { f ->
          val launch = f.start()
          val first = launch.pauses.receive()
          assertEquals(1, f.checkpoint.phase)
          assertNull(first.frame.evaluator.vectorRegisterBits("not-a-register"))
          assertFalse(f.checkpoint.passed())
          step(launch)
          assertEquals(2, f.checkpoint.phase)
          val extended = step(launch)
          assertEquals(-106L, extended.frame.evaluator.integerRegisterBits($$"$t1"))
          assertEquals(3, f.checkpoint.phase)
          assertTrue(f.checkpoint.passed())
          assertTrue(f.feedback.isEmpty())
          val resumed =
              withContext(Dispatchers.EDT) {
                projects.get().service<MarsLearningExecution>().observeDebug(
                    f.lifetime,
                    f.file.toNioPath(),
                    fixture.editor,
                    identity,
                    contract,
                ) {}
              }
          assertTrue(resumed.passed())
          assertEquals(3, resumed.phase)
          // A later launch cannot revoke evidence for an already checked source revision.
          val later = f.start()
          later.pauses.receive()
          assertTrue(f.checkpoint.passed())
        }
      }

  @Test
  fun `a different consumed input cannot substitute matching low byte values`() =
      timeoutRunBlocking {
        withDebug { f ->
          f.start(406).pauses.receive()
          assertEquals(listOf(Outcome.INPUT), f.feedback)
          assertEquals(0, f.checkpoint.phase)
          assertFalse(f.checkpoint.passed())
          val retry = f.start()
          retry.pauses.receive()
          step(retry)
          step(retry)
          assertTrue(f.checkpoint.passed())
        }
      }

  @Test
  fun `only the latest simultaneous Debug launch may advance the current attempt`() =
      timeoutRunBlocking {
        withDebug { f ->
          val first = f.start()
          first.pauses.receive()
          assertEquals(1, f.checkpoint.phase)
          val second = f.start()
          second.pauses.receive()
          assertEquals(1, f.checkpoint.phase)
          step(first)
          assertEquals(1, f.checkpoint.phase)
          step(second)
          assertEquals(2, f.checkpoint.phase)
          step(first)
          assertEquals(2, f.checkpoint.phase)
          assertFalse(f.checkpoint.passed())
          step(second)
          assertTrue(f.checkpoint.passed())
        }
      }

  @Test
  fun `retained native callbacks cannot repeat or advance a replaced or disposed observation`() =
      timeoutRunBlocking {
        withDebug { f ->
          val first = f.start()
          first.pauses.receive()
          val retained = first.learningCallbacks.single()
          withContext(Dispatchers.EDT) { retained.sessionPaused() }
          assertEquals(1, f.checkpoint.phase)
          val second = f.start()
          second.pauses.receive()
          withContext(Dispatchers.EDT) { retained.sessionPaused() }
          assertEquals(1, f.checkpoint.phase)
          val current = second.learningCallbacks.single()
          withContext(Dispatchers.EDT) {
            Disposer.dispose(f.observationLifetime)
            current.sessionPaused()
          }
          assertEquals(0, f.checkpoint.phase)
          assertFalse(f.checkpoint.passed())
        }
      }

  @Test
  fun `source capture containing an additional file is not the single-file activity`() =
      timeoutRunBlocking {
        withContext(Dispatchers.EDT) { fixture.addFileToProject("extra.inc", "# extra input\n") }
        withDebug(source = "$example.include \"extra.inc\"\n") { f ->
          f.start().pauses.receive()
          assertEquals(listOf(Outcome.SOURCE_CHANGED), f.feedback)
          assertFalse(f.checkpoint.passed())
        }
      }

  @Test
  fun `uninvoked macro source has no emitted boundary to accept`() = timeoutRunBlocking {
    val stop = contract.stops.first().copy(anchor = MarsLessonAnchor("unmapped", "nop", 0))
    withDebug(
        contract.copy(stops = listOf(stop)),
        example + "unmapped:\n.macro unused\n    nop\n.end_macro\n",
    ) { f ->
      f.start().pauses.receive()
      assertEquals(listOf(Outcome.ANCHOR), f.feedback)
      assertFalse(f.checkpoint.passed())
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = [false, true])
  fun `source edits and progress resets invalidate a paused sequence`(reset: Boolean) =
      timeoutRunBlocking {
        withDebug { f ->
          val launch = f.start()
          launch.pauses.receive()
          withContext(Dispatchers.EDT) {
            if (reset) projects.get().service<MarsLearningProgress>().reset(identity)
            else
                WriteCommandAction.runWriteCommandAction(projects.get()) {
                  fixture.editor.document.insertString(0, "# changed during Debug\n")
                }
          }
          assertEquals(0, f.checkpoint.phase)
          step(launch)
          assertTrue(Outcome.SOURCE_CHANGED in f.feedback)
          assertFalse(f.checkpoint.passed())
        }
      }

  @ParameterizedTest
  @ValueSource(booleans = [false, true])
  fun `backsteps and applied register changes require a fresh forward Debug session`(
      reverse: Boolean,
  ) = timeoutRunBlocking {
    withDebug { f ->
      val launch = f.start()
      val pause = launch.pauses.receive()
      if (reverse) {
        command(launch.session)
        launch.pauses.receive()
      } else {
        val changed = CompletableDeferred<String?>()
        pause.machineEdits.submitEdit(
            RegisterEditRequest(
                RegisterBank.CPU,
                8,
                "150",
                object : XModificationCallback {
                  override fun valueModified() {
                    changed.complete(null)
                  }

                  override fun errorOccurred(errorMessage: String) {
                    changed.complete(errorMessage)
                  }
                },
            ),
        )
        assertNull(changed.await())
        step(launch)
      }
      assertTrue(Outcome.HISTORY in f.feedback)
      assertEquals(0, f.checkpoint.phase)
      assertFalse(f.checkpoint.passed())
      val retry = f.start()
      retry.pauses.receive()
      step(retry)
      step(retry)
      assertTrue(f.checkpoint.passed())
    }
  }

  @Test
  fun `missing source anchors and wrong full-width values explain why observation cannot pass`() =
      timeoutRunBlocking {
        val invalid =
            listOf(
                contract.copy(
                    stops =
                        listOf(
                            contract.stops
                                .first()
                                .copy(anchor = MarsLessonAnchor("missing", "andi", 0)),
                        ),
                ) to Outcome.ANCHOR,
                contract.copy(
                    stops = listOf(contract.stops.first().copy(registers = mapOf($$"$v0" to 406L))),
                ) to Outcome.VALUES,
            )
        for ((plan, outcome) in invalid) withDebug(plan) { f ->
          val launch = f.start()
          launch.pauses.receive()
          assertEquals(listOf(outcome), f.feedback)
          assertEquals(0, f.checkpoint.phase)
          step(launch) // Repeated diagnostics of the same kind are not repeated on every pause.
          assertEquals(0, f.checkpoint.phase)
          assertFalse(f.checkpoint.passed())
        }
      }

  @Test
  fun `a pause before another operation is feedback rather than evidence for the requested boundary`() =
      timeoutRunBlocking {
        val last = contract.copy(stops = listOf(contract.stops.last()))
        withDebug(last) { f ->
          val launch = f.start()
          launch.pauses.receive()
          step(launch)
          assertEquals(listOf(Outcome.BOUNDARY), f.feedback)
          step(launch)
          assertTrue(f.checkpoint.passed())
        }
      }

  @Test
  fun `foreign unsupported unbound and disposed launches are ignored`() = timeoutRunBlocking {
    withDebug { f ->
      withContext(Dispatchers.EDT) {
        val runs = projects.get().service<MarsRunService>()
        val unbound = runs.createProcess(f.file.toNioPath(), memoryDumps = emptyList())
        val wrong =
            runs.createProcess(
                f.file.toNioPath(),
                options = MarsExecutionOptions(delayedBranching = true),
                memoryDumps = emptyList(),
            )
        try {
          val foreign =
              XDebuggerManager.getInstance(projects.get())
                  .newSessionBuilder(
                      object : XDebugProcessStarter() {
                        override fun start(session: XDebugSession) =
                            object : XDebugProcess(session) {
                              private val handler = NopProcessHandler()

                              override fun doGetProcessHandler() = handler

                              override fun getEditorsProvider() = MarsDebugEditors

                              override fun stop() = handler.destroyProcess()
                            }
                      },
                  )
                  .sessionName("Unrelated debugger")
                  .showTab(true)
                  .startSession()
                  .session
          f.checkpoint.starting(DefaultRunExecutor.EXECUTOR_ID, unbound)
          f.checkpoint.starting(DefaultDebugExecutor.EXECUTOR_ID, NopProcessHandler())
          f.checkpoint.starting(DefaultDebugExecutor.EXECUTOR_ID, wrong)
          f.checkpoint.starting(DefaultDebugExecutor.EXECUTOR_ID, unbound)
          assertEquals(0, f.checkpoint.phase)
          foreign.stop()
          Disposer.dispose(f.lifetime)
          f.checkpoint.starting(DefaultDebugExecutor.EXECUTOR_ID, unbound)
          assertEquals(0, f.checkpoint.phase)
        } finally {
          unbound.destroyProcess()
          wrong.destroyProcess()
        }
      }
    }
  }

  private fun registeredSessionListeners(session: XDebugSession): List<XDebugSessionListener> {
    // Retain the actual registered callback to model delivery that was already
    // in flight when its parent was disposed. Reflection remains in tests.
    val dispatcher =
        session.javaClass
            .getDeclaredField("myDispatcher")
            .apply { isAccessible = true }
            .get(session)
    val listeners = dispatcher.javaClass.getMethod("getListeners").invoke(dispatcher) as List<*>
    return listeners.filterIsInstance<XDebugSessionListener>()
  }

  @Test
  fun `scalar target snapshots report vector state unavailable without inventing zero vectors`() =
      timeoutRunBlocking {
        val settings = projects.get().service<MarsAssemblySettings>()
        val target = settings.target
        try {
          withContext(Dispatchers.EDT) { settings.changeTarget(MarsTarget.MARS32) }
          val source =
              $$"""
              .text
              main:
              observed:
                  andi $t0,$zero,255
                  li $v0,10
                  syscall
              """
                  .trimIndent()
          val plan =
              contract.copy(
                  integerInputs = emptyList(),
                  stops = listOf(stop("scalar", "observed", "andi", mapOf($$"$t0" to 0L))),
              )
          withDebug(plan, source) { f ->
            val first = f.start().pauses.receive().frame.evaluator
            assertNull(first.vectorRegisterBits($$"$w0"))
            assertNull(first.vectorRegisterBits($$"$w32"))
          }
        } finally {
          withContext(Dispatchers.EDT) { settings.changeTarget(target) }
        }
      }

  @Test
  fun `MSA lesson reads immutable vector snapshots through a scalar alias write`() =
      timeoutRunBlocking {
        val settings = projects.get().service<MarsAssemblySettings>()
        val target = settings.target
        try {
          withContext(Dispatchers.EDT) { settings.changeTarget(MarsTarget.MARS64) }
          val source =
              $$"""
              .text
              main:
                  ldi.d $w4,-1
              observed:
                  andi $t0,$zero,255
                  dmtc1 $zero,$f4
              changed:
                  nop
                  li $v0,10
                  syscall
              """
                  .trimIndent()
          val before =
              stop("loaded", "observed", "andi", mapOf($$"$t0" to 0L))
                  .copy(vectorRegisters = mapOf($$"$w4" to VectorRegister(-1, -1)))
          val after =
              stop("alias-written", "changed", "nop", emptyMap())
                  .copy(vectorRegisters = mapOf($$"$w4" to VectorRegister(0, -1)))
          val plan = contract.copy(integerInputs = emptyList(), stops = listOf(before, after))
          withDebug(plan, source) { f ->
            val launch = f.start()
            val first = launch.pauses.receive().frame.evaluator
            assertEquals(VectorRegister(-1, -1), first.vectorRegisterBits($$"$w4"))
            assertNull(first.vectorRegisterBits($$"$w32"))
            assertEquals(1, f.checkpoint.phase)
            step(launch)
            val changed = step(launch).frame.evaluator
            assertEquals(VectorRegister(0, -1), changed.vectorRegisterBits($$"$w4"))
            assertEquals(
                VectorRegister(-1, -1),
                first.vectorRegisterBits($$"$w4"),
                "Retained snapshots must not read the live machine",
            )
            assertEquals(2, f.checkpoint.phase)
            assertTrue(f.checkpoint.passed())
          }
        } finally {
          withContext(Dispatchers.EDT) { settings.changeTarget(target) }
        }
      }

  @Test
  fun `native Debug keeps its short-read fixture after the Learn observation is disposed`() =
      timeoutRunBlocking {
        val source =
            $$"""
            .data
            path: .asciiz "input.bin"
            buffer: .space 7
            .text
            main:
                la $a0,path
                li $a1,0
                li $v0,13
                syscall
                move $t5,$v0
            observed:
                andi $t0,$zero,255
                move $a0,$t5
                la $a1,buffer
                li $a2,7
                li $v0,14
                syscall
                move $s0,$v0
                li $v0,14
                syscall
                move $s1,$v0
                li $v0,14
                syscall
                move $s2,$v0
                li $v0,16
                syscall
            finished:
                nop
                li $v0,10
                syscall
            """
                .trimIndent()
        val plan =
            contract.copy(
                integerInputs = emptyList(),
                stops = listOf(stop("opened", "observed", "andi", mapOf($$"$t5" to 19L))),
                fileRead = FunctionFileReadCheck("input.bin", "313233343536373839", 7, 3, 5, 19),
            )
        withDebug(plan, source) { f ->
          val launch = f.start()
          launch.pauses.receive()
          assertTrue(f.checkpoint.passed())
          withContext(Dispatchers.EDT) {
            val project = projects.get()
            Disposer.dispose(f.observationLifetime)
            assertNull(project.service<MarsLearningFileInputs>().definition(f.file.toNioPath()))
            val type = findBreakpointType<MarsLineBreakpointType>()
            WriteCommandAction.runWriteCommandAction(project) {
              XDebuggerManager.getInstance(project)
                  .breakpointManager
                  .addLineBreakpoint(
                      type,
                      f.file.url,
                      source.lines().indexOfFirst { it.trim() == "finished:" } + 1,
                      null,
                  )
            }
            launch.session.resume()
          }
          val finished = launch.pauses.receive().frame.evaluator
          assertEquals(3L, finished.integerRegisterBits($$"$s0"))
          assertEquals(2L, finished.integerRegisterBits($$"$s1"))
          assertEquals(-1L, finished.integerRegisterBits($$"$s2"))
          withContext(Dispatchers.EDT) { launch.session.resume() }
          launch.stopped.await()
        }
      }

  private suspend fun step(launch: Launch): MarsSuspendContext {
    withContext(Dispatchers.EDT) { launch.session.stepOver(false) }
    return launch.pauses.receive()
  }

  private suspend fun command(session: XDebugSession, key: String = "debug.backstep") =
      withContext(Dispatchers.EDT) {
        val toolbar = DefaultActionGroup()
        session.debugProcess.registerAdditionalActions(
            DefaultActionGroup(),
            toolbar,
            DefaultActionGroup(),
        )
        toolbar
            .getChildren(null)
            .single { it.templatePresentation.text == runMessages.getMessage(key) }
            .actionPerformed(TestActionEvent.createTestEvent())
      }

  private data class Launch(
      val session: XDebugSession,
      val pauses: Channel<MarsSuspendContext>,
      val stopped: CompletableDeferred<Unit>,
      val learningCallbacks: List<XDebugSessionListener>,
  )

  private inner class Fixture(
      val lifetime: Disposable,
      val observationLifetime: Disposable,
      val file: VirtualFile,
      val checkpoint: MarsDownloadedDebugCheckpoint,
      val feedback: MutableList<Outcome>,
      val beforeListeners: IdentityHashMap<ProcessHandler, List<XDebugSessionListener>>,
  ) {
    val launches = mutableListOf<Launch>()

    suspend fun start(input: Int = 150): Launch {
      val project = projects.get()
      val started = CompletableDeferred<XDebugSession>()
      val pauses = Channel<MarsSuspendContext>(Channel.UNLIMITED)
      val stopped = CompletableDeferred<Unit>()
      var learningCallbacks = emptyList<XDebugSessionListener>()
      withContext(Dispatchers.EDT) {
        val config = runConfigurationType<MarsRunType>().createTemplateConfiguration(project)
        config.options.sourcePath = file.path
        val env =
            ExecutionEnvironmentBuilder.create(
                    DefaultDebugExecutor.getDebugExecutorInstance(),
                    config,
                )
                .build()
        project.messageBus
            .connect(lifetime)
            .subscribe(
                ExecutionManager.EXECUTION_TOPIC,
                executionListener {
                  processStarting { _, environment, handler ->
                    if (environment !== env) return@processStarting
                    val session =
                        XDebuggerManager.getInstance(project).debugSessions.single {
                          it.debugProcess.processHandler === handler
                        }
                    val before = beforeListeners.remove(handler).orEmpty()
                    learningCallbacks =
                        registeredSessionListeners(session).filter { candidate ->
                          // The headless fixture can share its classloader with SDK modules.
                          candidate.javaClass.packageName.startsWith(
                              javaClassOf<MarsDownloadedDebugCheckpoint>()
                                  .packageName
                                  .substringBeforeLast('.') + ".",
                          ) && before.none { it === candidate }
                        }
                    session.addSessionListener(
                        debugSessionListener {
                          sessionStopped {
                            stopped.complete(Unit)
                          }
                          sessionPaused {
                            pauses.trySend(
                                assertInstanceOf<MarsSuspendContext>(
                                    session.suspendContext,
                                ),
                            )
                          }
                        },
                        lifetime,
                    )
                    started.complete(session)
                  }
                },
            )
        assertInstanceOf<MarsDebugRunner>(env.runner).execute(env)
      }
      val session = started.await()
      val launch = Launch(session, pauses, stopped, learningCallbacks)
      launches += launch
      withContext(Dispatchers.IO) {
        session.debugProcess.processHandler.processInput!!.apply {
          write("$input\n".toByteArray())
          flush()
        }
      }
      return launch
    }
  }

  private suspend fun withDebug(
      plan: MarsNativeDebugStep = contract,
      source: String = example,
      body: suspend (Fixture) -> Unit,
  ) {
    val project = projects.get()
    val lifetime = Disposer.newDisposable()
    var state: Fixture? = null
    try {
      val current =
          withContext(Dispatchers.EDT) {
            val file =
                fixture.addFileToProject("byte-inspector-${fileNumber++}.asm", source).virtualFile
            fixture.configureFromExistingVirtualFile(file)
            val feedback = mutableListOf<Outcome>()
            val observationLifetime = Disposer.newDisposable(lifetime)
            val beforeListeners = IdentityHashMap<ProcessHandler, List<XDebugSessionListener>>()
            // Observe the native registration boundary before the checkpoint subscribes.
            project.messageBus
                .connect(lifetime)
                .subscribe(
                    ExecutionManager.EXECUTION_TOPIC,
                    executionListener {
                      processStarting { _, _, handler ->
                        val session =
                            XDebuggerManager.getInstance(project).debugSessions.singleOrNull {
                              it.debugProcess.processHandler === handler
                            } ?: return@processStarting
                        beforeListeners[handler] = registeredSessionListeners(session)
                      }
                    },
                )
            val checkpoint =
                project
                    .service<MarsLearningExecution>()
                    .observeDebug(
                        observationLifetime,
                        file.toNioPath(),
                        fixture.editor,
                        identity,
                        plan,
                        feedback::add,
                    )
            val type = findBreakpointType<MarsLineBreakpointType>()
            WriteCommandAction.runWriteCommandAction(project) {
              XDebuggerManager.getInstance(project)
                  .breakpointManager
                  .addLineBreakpoint(
                      type,
                      file.url,
                      source.lines().indexOfFirst { it.trim().startsWith("andi ") },
                      null,
                  )
            }
            Fixture(lifetime, observationLifetime, file, checkpoint, feedback, beforeListeners)
          }
      state = current
      body(current)
    } finally {
      for ((session, pauses, stopped) in state?.launches.orEmpty()) {
        withContext(Dispatchers.EDT) { session.stop() }
        stopped.await()
        pauses.close()
      }
      withContext(Dispatchers.EDT) { Disposer.dispose(lifetime) }
    }
  }
}
