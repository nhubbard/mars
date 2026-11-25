package edu.missouristate.mars.plugin.learning

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManager
import edu.missouristate.mars.plugin.debug.MarsDebugProcess
import edu.missouristate.mars.plugin.debug.MarsSuspendContext
import edu.missouristate.mars.plugin.run.MarsProcessHandler
import java.nio.file.Path

/** Observes stopped debugger snapshots; it never adds breakpoints, steps or edits a machine. */
internal class MarsConsoleDebugCheckpoint(
    private val project: Project,
    private val lifetime: Disposable,
    entry: Path,
    document: Document,
    private val segment: Segment = Segment.ARITHMETIC,
) {
  private val source = MarsLearningSource(project, entry, document, segment.identity)

  private data class Observation(val trace: Trace, val attempt: MarsLearningSource.Attempt)

  @Volatile private var active: Observation? = null
  @Volatile private var closed = false

  init {
    Disposer.register(lifetime) { closed = true }
    project.messageBus
        .connect(lifetime)
        .subscribe(
            ExecutionManager.EXECUTION_TOPIC,
            object : ExecutionListener {
              override fun processStarting(
                  executorId: String,
                  env: ExecutionEnvironment,
                  handler: ProcessHandler,
              ) {
                starting(executorId, handler)
              }
            },
        )
  }

  fun starting(executorId: String, process: ProcessHandler) {
    if (closed || executorId != DefaultDebugExecutor.EXECUTOR_ID || process !is MarsProcessHandler)
        return
    if (!source.supports(process)) return
    val session =
        XDebuggerManager.getInstance(project).debugSessions.firstOrNull {
          it.debugProcess is MarsDebugProcess && it.debugProcess.processHandler === process
        } ?: return
    val attempt = source.begin(STEP)
    val trace = Trace(segment)
    val state = Observation(trace, attempt)
    active = state
    val observation = Disposer.newDisposable("MARS learning debug observation")
    Disposer.register(lifetime, observation)
    Disposer.register(observation) {
      source.finish(attempt, false)
      if (active === state) active = null
    }
    session.addSessionListener(
        object : XDebugSessionListener {
          override fun sessionPaused() = runReadActionBlocking {
            if (active !== state || !source.current(attempt) || !source.matches(attempt, process))
                return@runReadActionBlocking
            // The native pause notification follows suspend-context installation. This
            // session is bound to MarsDebugProcess, which always installs MarsSuspendContext.
            val context = session.suspendContext as MarsSuspendContext
            if (!source.matchesPosition(context.frame.sourcePosition)) return@runReadActionBlocking
            val evaluator = context.frame.evaluator
            trace.observe(
                Pause(
                    context.number,
                    evaluator.integerRegisterBits("pc"),
                    evaluator.instructionWord,
                    evaluator.integerRegisterBits($$"$v0"),
                    evaluator.integerRegisterBits("\$t0"),
                    evaluator.integerRegisterBits($$"$a0"),
                ),
            )
            if (trace.phase == 3) source.finish(attempt, true)
          }

          override fun sessionStopped() {
            Disposer.dispose(observation)
          }
        },
        observation,
    )
  }

  val phase: Int
    get() {
      if (source.passed(STEP)) return 3
      val state = active ?: return 0
      return if (source.current(state.attempt)) state.trace.phase else 0
    }

  internal data class Pause(
      val number: Long,
      val pc: Long,
      val instruction: Int?,
      val input: Long,
      val result: Long,
      val argument: Long = 0,
  )

  /** Numeric instruction boundaries avoid parsing student source or depending on line numbers. */
  internal class Trace(private val segment: Segment = Segment.ARITHMETIC) {
    @Volatile
    var phase = 0
      private set

    private var lastPause = 0L
    private var firstPc = 0L

    fun observe(pause: Pause) {
      if (pause.number <= lastPause) return
      lastPause = pause.number
      when (segment) {
        Segment.ARITHMETIC -> observeArithmetic(pause)
        Segment.OUTPUT,
        Segment.DIAGNOSIS -> observeOutput(pause)
      }
    }

    private fun observeArithmetic(pause: Pause) {
      when (phase) {
        0 ->
            if (pause.instruction == ADDU_T0_V0_V0 && pause.input == 20L) {
              firstPc = pause.pc
              phase = 1
            }
        1 ->
            if (
                pause.pc == firstPc + 4 &&
                    pause.instruction == ADDIU_T0_T0_ONE &&
                    pause.input == 20L &&
                    pause.result == 40L
            )
                phase = 2
        2 -> if (pause.pc == firstPc + 8 && pause.input == 20L && pause.result == 41L) phase = 3
      }
    }

    private fun observeOutput(pause: Pause) {
      val faulty = segment == Segment.DIAGNOSIS
      val copyInstruction = if (faulty) MOVE_A0_V0 else MOVE_A0_T0
      val expectedArgument = if (faulty) 4L else 41L
      when (phase) {
        0 ->
            if (pause.instruction == copyInstruction && pause.input == 4L && pause.result == 41L) {
              firstPc = pause.pc
              phase = 1
            }
        1 ->
            if (
                pause.pc == firstPc + 4 &&
                    pause.instruction == LI_V0_ONE &&
                    pause.input == 4L &&
                    pause.result == 41L &&
                    pause.argument == expectedArgument
            )
                phase = 2
        2 ->
            if (
                pause.pc == firstPc + 8 &&
                    pause.instruction == SYSCALL &&
                    pause.input == 1L &&
                    pause.result == 41L &&
                    pause.argument == expectedArgument
            )
                phase = 3
      }
    }
  }

  internal enum class Segment(val identity: MarsLessonIdentity) {
    ARITHMETIC(identity),
    OUTPUT(
        MarsLearningSource.identity(
            "console-output",
            "ordered output pauses: retained 41; argument 41; selector 1",
        ),
    ),
    DIAGNOSIS(
        MarsLearningSource.identity(
            "console-diagnosis",
            "ordered faulty copy: retained 41; argument 4; selector 1",
        ),
    ),
  }

  companion object {
    const val STEP = "trace-twenty"
    // Basic MIPS word encodings: addu $t0,$v0,$v0 and addiu $t0,$t0,1.
    const val ADDU_T0_V0_V0 = 0x00424021
    const val ADDIU_T0_T0_ONE = 0x25080001
    // The MIPS64 move pseudo-instruction expands to or $a0,$t0,$zero.
    const val MOVE_A0_T0 = 0x01002025
    const val MOVE_A0_V0 = 0x00402025
    // The first matching signed 16-bit li template uses addiu.
    const val LI_V0_ONE = 0x24020001
    const val SYSCALL = 0x0000000c
    val identity =
        MarsLearningSource.identity(
            "console-debug",
            "ordered pauses: input 20; doubled 40; incremented 41",
        )
  }
}
