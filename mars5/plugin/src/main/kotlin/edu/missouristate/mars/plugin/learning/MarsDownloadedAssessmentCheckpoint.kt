package edu.missouristate.mars.plugin.learning

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import edu.missouristate.mars.cli.grading.ConsoleCheckResult
import edu.missouristate.mars.cli.grading.PreparedFunctionChecks
import edu.missouristate.mars.cli.grading.PreparedConsoleChecks
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.plugin.MarsSessionService
import edu.missouristate.mars.plugin.build.MarsAssembledProgram
import edu.missouristate.mars.plugin.build.MarsBuildService
import edu.missouristate.mars.plugin.build.MarsSourceSet
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Checks immutable public cases against the selected source in detached, fresh machines. */
internal class MarsDownloadedAssessmentCheckpoint(
    private val project: Project, lifetime: Disposable, private val entry: Path, private val document: Document,
    private val identity: MarsLessonIdentity, private val step: MarsNativeAssessmentStep,
    private val requirementsReady: () -> Boolean,
    private val createSession: () -> MarsContext = { MarsSessionService.createMarsComponent(backstepLimit = 1).context() },
    private val refreshRequirements: suspend () -> Unit = {},
) {
    private val source = MarsLearningSource(project, entry, document, identity)
    private val progress = project.service<MarsLearningProgress>()
    private val policy = project.service<MarsLearningPolicy>()
    @Volatile var active = true
        private set

    init { Disposer.register(lifetime) { active = false } }

    fun accepts(selected: Document?) = active && selected === document && source.isBound()
    private fun permitted() = policy.allowFreeWriting && requirementsReady()
    fun passed() = permitted() && source.passed(step.id)
    private fun completed() = progress.snapshot(identity)?.completedAssessments ?: 0
    private fun available(limit: Int) = limit == 0 || completed() < limit

    suspend fun check(): Feedback {
        if (!active) return Feedback.STALE
        refreshRequirements()
        if (!active) return Feedback.STALE
        if (!permitted()) return Feedback.POLICY
        if (!available(policy.maxIndependentAssessments)) return Feedback.LIMIT
        val attempt = source.begin(step.id)
        try {
            val program = project.service<MarsBuildService>().prepare(MarsSourceSet(entry), MarsLearningSource.options, activateBuildView = true)
                ?: return Feedback.ASSEMBLY
            if (!readAction { current(attempt, program) }) return Feedback.STALE
            val context = currentCoroutineContext()
            val results = withContext(Dispatchers.Default) {
                if (step.functionCases.isEmpty()) {
                    (step.consoleCaseBatches.ifEmpty { listOf(step.cases) }).flatMap { batch ->
                        PreparedConsoleChecks.evaluate(program.image, batch, createSession, context::ensureActive)
                    }
                } else {
                    (step.functionCaseBatches.ifEmpty { listOf(step.functionCases) }).flatMap { batch ->
                        PreparedFunctionChecks.evaluate(program.image, batch, createSession, context::ensureActive)
                    }
                }
            }
            return readAction {
                synchronized(progress) {
                    val limit = policy.maxIndependentAssessments
                    if (!current(attempt, program) || !available(limit)) Feedback.STALE
                    else {
                        // The same progress lock protects current(), the count and ticket consumption.
                        source.finishAssessment(attempt, results.all(ConsoleCheckResult::passed), limit)
                        Feedback.Checked(results, completed(), limit)
                    }
                }
            }
        } finally { source.finish(attempt, false) }
    }

    private fun current(attempt: MarsLearningSource.Attempt, program: MarsAssembledProgram) =
        active && permitted() && source.current(attempt) && source.matches(attempt, program.sources)

    sealed interface Feedback {
        data object POLICY : Feedback
        data object LIMIT : Feedback
        data object ASSEMBLY : Feedback
        data object STALE : Feedback
        data class Checked(val results: List<ConsoleCheckResult>, val completed: Int, val limit: Int) : Feedback
    }
}
