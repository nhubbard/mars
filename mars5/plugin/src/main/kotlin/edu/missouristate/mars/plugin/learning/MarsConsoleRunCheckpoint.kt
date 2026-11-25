@file:Suppress("UnstableApiUsage")

package edu.missouristate.mars.plugin.learning

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorOpenRequest
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.platform.ide.progress.withBackgroundProgress
import edu.missouristate.mars.plugin.run.MarsProcessHandler
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.future.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Service(Service.Level.PROJECT)
internal class MarsLearningExecution(
    private val project: Project,
    private val scope: CoroutineScope,
) {
  fun openLessonFile(entry: Path): CompletableFuture<Boolean> =
      scope.future(Dispatchers.EDT) {
        // The native lesson host may call before() from a write-unsafe EDT context.
        // Refresh outside it; the public suspending editor API owns editor placement.
        // Native task futures must also complete on EDT for the host continuation.
        val file =
            withContext(Dispatchers.IO) {
              checkNotNull(VirtualFileManager.getInstance().refreshAndFindFileByNioPath(entry)) {
                "Prepare the lesson source before opening it"
              }
            }
        FileEditorManager.getInstance(project).openFile(file, FileEditorOpenRequest.withFocus(true))
        MarsLearningPresentation.fit(ToolWindowManager.getInstance(project).getToolWindow("Learn"))
        true
      }

  @Volatile
  var independent: MarsIndependentCheckpoint? = null
    private set

  fun observeIndependent(
      lifetime: Disposable,
      entry: Path,
      document: Document,
  ): MarsIndependentCheckpoint =
      MarsIndependentCheckpoint(project, lifetime, entry, document).also { checkpoint ->
        independent = checkpoint
        Disposer.register(lifetime) { if (independent === checkpoint) independent = null }
      }

  fun observeDebug(lifetime: Disposable, entry: Path, document: Document) =
      MarsConsoleDebugCheckpoint(project, lifetime, entry, document)

  fun observeDebug(
      lifetime: Disposable,
      entry: Path,
      editor: Editor,
      identity: MarsLessonIdentity,
      step: MarsNativeDebugStep,
      feedback: (MarsDownloadedDebugCheckpoint.Outcome) -> Unit,
  ) = MarsDownloadedDebugCheckpoint(project, lifetime, entry, editor, identity, step, feedback)

  fun observeOutput(lifetime: Disposable, entry: Path, document: Document) =
      MarsConsoleDebugCheckpoint(
          project,
          lifetime,
          entry,
          document,
          MarsConsoleDebugCheckpoint.Segment.OUTPUT,
      )

  fun observeDiagnosis(lifetime: Disposable, entry: Path, document: Document) =
      MarsConsoleDebugCheckpoint(
          project,
          lifetime,
          entry,
          document,
          MarsConsoleDebugCheckpoint.Segment.DIAGNOSIS,
      )

  fun observeRepair(lifetime: Disposable, entry: Path, document: Document) =
      MarsConsoleRunCheckpoint(
          project,
          scope,
          lifetime,
          entry,
          document,
          MarsConsoleRunCheckpoint.repairIdentity,
      )

  fun observeRun(lifetime: Disposable, entry: Path, document: Document) =
      MarsConsoleRunCheckpoint(project, scope, lifetime, entry, document)

  fun observeRun(
      lifetime: Disposable,
      entry: Path,
      document: Document,
      identity: MarsLessonIdentity,
      step: MarsNativeRunStep,
      feedback: (MarsDownloadedRunCheckpoint.Outcome) -> Unit,
  ) =
      MarsDownloadedRunCheckpoint(
          project,
          scope,
          lifetime,
          entry,
          document,
          identity,
          step,
          feedback,
      )

  fun observeAssessment(
      lifetime: Disposable,
      entry: Path,
      document: Document,
      identity: MarsLessonIdentity,
      step: MarsNativeAssessmentStep,
      requirements: MarsDownloadedRequirements,
  ) =
      MarsDownloadedAssessmentCheckpoint(
          project,
          lifetime,
          entry,
          document,
          identity,
          step,
          requirements::ready,
          refreshRequirements = requirements::refresh,
      )

  fun prepareAssessmentRequirements(
      lifetime: Disposable,
      requirements: MarsDownloadedRequirements,
  ): CompletableFuture<Boolean> =
      scope
          .future(Dispatchers.EDT) {
            requirements.refresh()
            true
          }
          .also { future -> Disposer.register(lifetime) { future.cancel(true) } }

  fun checkAssessment(
      lifetime: Disposable,
      checkpoint: MarsDownloadedAssessmentCheckpoint,
      feedback: (MarsDownloadedAssessmentCheckpoint.Feedback) -> Unit,
  ): Job =
      scope
          .launch(Dispatchers.Default) {
            val result =
                withBackgroundProgress(
                    project,
                    downloadedLessonMessages.getMessage("assessment.progress"),
                ) {
                  checkpoint.check()
                }
            withContext(Dispatchers.EDT) { if (checkpoint.active) feedback(result) }
          }
          .also { job -> Disposer.register(lifetime) { job.cancel() } }
}

/** Observes the first unit's ordinary Run; it never launches or alters a simulated program. */
internal class MarsConsoleRunCheckpoint(
    project: Project,
    private val scope: CoroutineScope,
    private val lifetime: Disposable,
    entry: Path,
    document: Document,
    lessonIdentity: MarsLessonIdentity = identity,
) {
  private val source = MarsLearningSource(project, entry, document, lessonIdentity)
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

  /**
   * Called by the native pre-start event; package visibility also permits real-process fixture
   * tests.
   */
  fun starting(executorId: String, handler: ProcessHandler) {
    if (closed || executorId != DefaultRunExecutor.EXECUTOR_ID || handler !is MarsProcessHandler)
        return
    if (!source.supports(handler)) return
    val attempt = source.begin(STEP)
    val observation = Disposer.newDisposable("MARS learning Run observation")
    Disposer.register(lifetime, observation)
    val output = Transcript()
    val listener =
        object : ProcessListener {
          override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) =
              output.append(event.text, outputType)

          override fun processTerminated(event: ProcessEvent) {
            handler.removeProcessListener(this)
            scope.launch {
              try {
                readAction {
                  source.finish(
                      attempt,
                      event.exitCode == 0 && output.matches() && source.matches(attempt, handler),
                  )
                }
              } finally {
                Disposer.dispose(observation)
              }
            }
          }
        }
    handler.addProcessListener(listener)
    Disposer.register(observation) {
      handler.removeProcessListener(listener)
      source.finish(attempt, false)
    }
  }

  fun passed() = source.passed(STEP)

  /** Bounded transcript storage: excessive output fails the checkpoint instead of retaining it. */
  internal class Transcript {
    private val stdout = StringBuilder()
    private var failed = false

    @Synchronized
    fun append(text: String, type: Key<*>) {
      when (type) {
        ProcessOutputTypes.STDOUT ->
            if (text.length <= EXPECTED.length - stdout.length) stdout.append(text)
            else failed = true
        ProcessOutputTypes.STDERR -> if (text.isNotEmpty()) failed = true
      }
    }

    @Synchronized fun matches() = !failed && stdout.toString() == EXPECTED
  }

  companion object {
    const val STEP = "run-twenty"
    const val EXPECTED = "Enter a small integer: Twice your number plus one is: 41\n"
    val options = MarsLearningSource.options
    val identity = MarsLearningSource.identity("console-run", EXPECTED)
    val repairIdentity = MarsLearningSource.identity("console-repair", EXPECTED)
  }
}
