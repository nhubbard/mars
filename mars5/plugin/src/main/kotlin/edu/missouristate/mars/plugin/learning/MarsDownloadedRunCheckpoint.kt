package edu.missouristate.mars.plugin.learning

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import edu.missouristate.mars.api.StepEvent
import edu.missouristate.mars.plugin.run.MarsProcessHandler
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Observes the student's ordinary Run. It does not supply input, launch or alter a machine. */
internal class MarsDownloadedRunCheckpoint(
    project: Project,
    private val scope: CoroutineScope,
    private val lifetime: Disposable,
    entry: Path,
    document: Document,
    identity: MarsLessonIdentity,
    private val step: MarsNativeRunStep,
    private val feedback: (Outcome) -> Unit,
) {
  private val source = MarsLearningSource(project, entry, document, identity)
  @Volatile private var closed = false
  private val observation = AtomicReference<Disposable?>()

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

  fun passed() = source.passed(step.id)

  @Synchronized
  fun starting(executorId: String, handler: ProcessHandler) {
    if (closed || executorId != DefaultRunExecutor.EXECUTOR_ID || handler !is MarsProcessHandler)
        return
    if (!source.supports(handler)) return
    observation.get()?.let(Disposer::dispose)
    val attempt = source.begin(step.id)
    val owned = Disposer.newDisposable("Downloaded MARS Run observation")
    observation.set(owned)
    Disposer.register(lifetime, owned)
    val transcript = Transcript(step.integerInputs, step.stdout)
    // MARS installs the accessor before running its simulator. This callback is
    // synchronous with a completed instruction, so a live register file exists.
    handler.addInstructionListener(
        { event -> transcript.instruction(event, handler.integerRegisterBits(2)!!) },
        owned,
    )
    val listener =
        object : ProcessListener {
          override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) =
              transcript.append(event.text, outputType)

          override fun processTerminated(event: ProcessEvent) {
            handler.removeProcessListener(this)
            scope.launch {
              try {
                val result = readAction {
                  val outcome =
                      when {
                        !source.matches(attempt, handler) -> Outcome.SOURCE_CHANGED
                        event.exitCode != 0 -> Outcome.TERMINATION
                        !transcript.inputsMatch() -> Outcome.INPUT
                        !transcript.outputMatches() -> Outcome.OUTPUT
                        else -> Outcome.PASSED
                      }
                  if (source.finish(attempt, outcome == Outcome.PASSED)) outcome
                  else Outcome.SOURCE_CHANGED
                }
                if (!closed && observation.get() === owned) feedback(result)
              } finally {
                Disposer.dispose(owned)
              }
            }
          }
        }
    handler.addProcessListener(listener)
    Disposer.register(owned) {
      handler.removeProcessListener(listener)
      source.finish(attempt, false)
      observation.compareAndSet(owned, null)
    }
  }

  internal enum class Outcome {
    PASSED,
    INPUT,
    OUTPUT,
    TERMINATION,
    SOURCE_CHANGED,
  }

  /**
   * Bounded storage and completed service-5 results, independent of source instruction spelling.
   */
  internal class Transcript(inputs: List<Int>, private val expected: String) {
    private val stdout = StringBuilder()
    private val input = MarsIntegerInputObservation(inputs)
    private var outputFailure = false

    @Synchronized fun instruction(event: StepEvent, v0: Long) = input.instruction(event, v0)

    @Synchronized
    fun append(text: String, type: Key<*>) {
      when {
        ProcessOutputType.isStdout(type) ->
            if (text.length <= expected.length - stdout.length) stdout.append(text)
            else outputFailure = true
        ProcessOutputType.isStderr(type) -> if (text.isNotEmpty()) outputFailure = true
      }
    }

    @Synchronized fun inputsMatch() = input.matches()

    @Synchronized fun outputMatches() = !outputFailure && stdout.toString() == expected
  }
}
