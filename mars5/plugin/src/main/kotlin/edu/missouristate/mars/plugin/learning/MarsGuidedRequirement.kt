package edu.missouristate.mars.plugin.learning

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import edu.missouristate.mars.plugin.learning.MarsLearningWorkspace.Exercise
import java.nio.file.Path

/** The first unit's required observations are public teaching contracts, not private grading. */
internal enum class MarsGuidedRequirement(
    val id: String,
    val exercise: Exercise,
    val step: String,
    private val content: () -> MarsLessonIdentity,
    private val questions: () -> List<MarsPrediction> = { emptyList() },
) {
  RUN(
      "run",
      Exercise.EXAMPLE,
      MarsConsoleRunCheckpoint.STEP,
      { MarsConsoleRunCheckpoint.identity },
  ),
  DEBUG(
      "debug",
      Exercise.EXAMPLE,
      MarsConsoleDebugCheckpoint.STEP,
      { MarsConsoleDebugCheckpoint.identity },
  ),
  OUTPUT(
      "output",
      Exercise.EXAMPLE,
      MarsConsoleDebugCheckpoint.STEP,
      { MarsConsoleDebugCheckpoint.Segment.OUTPUT.identity },
      { listOf(MarsPredictions.output) },
  ),
  DIAGNOSIS(
      "diagnosis",
      Exercise.DIAGNOSIS,
      MarsConsoleDebugCheckpoint.STEP,
      { MarsConsoleDebugCheckpoint.Segment.DIAGNOSIS.identity },
      { listOf(MarsPredictions.wrongOutput, MarsPredictions.diagnosis) },
  ),
  REPAIR(
      "repair",
      Exercise.REPAIR,
      MarsConsoleRunCheckpoint.STEP,
      { MarsConsoleRunCheckpoint.repairIdentity },
  );

  val identity: MarsLessonIdentity
    get() = content()

  val predictions: List<MarsPrediction>
    get() = questions()

  val title: String
    get() = learningMessages.getMessage("lesson.$id.name")

  fun source(project: Project): Path? =
      project.basePath?.let { Path.of(it).resolve("mars-labs/console").resolve(exercise.fileName) }

  fun passed(project: Project): Boolean = runReadActionBlocking {
    val entry = source(project) ?: return@runReadActionBlocking false
    val file =
        VirtualFileManager.getInstance().findFileByNioPath(entry)
            ?: return@runReadActionBlocking false
    project.service<MarsLearningSourceTracker>().track(identity, entry, file)
    val progress = project.service<MarsLearningProgress>()
    val record = progress.snapshot(identity) ?: return@runReadActionBlocking false
    if (step !in record.successfulChecks) return@runReadActionBlocking false
    if (!predictions.all { MarsPredictions.hasCorrectAnswer(project, it) })
        return@runReadActionBlocking false
    val document =
        FileDocumentManager.getInstance().getDocument(file) ?: return@runReadActionBlocking false
    val revision = MarsLearningSource.revision(document.text)
    if (record.sourceRevision != revision) {
      progress.open(identity, revision)
      return@runReadActionBlocking false
    }
    true
  }
}
