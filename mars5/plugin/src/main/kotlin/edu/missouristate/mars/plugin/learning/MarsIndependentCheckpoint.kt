package edu.missouristate.mars.plugin.learning

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import edu.missouristate.mars.cli.grading.ConsoleCheckCase
import edu.missouristate.mars.cli.grading.ConsoleCheckResult
import edu.missouristate.mars.cli.grading.PreparedConsoleChecks
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.plugin.MarsSessionService
import edu.missouristate.mars.plugin.build.MarsAssembledProgram
import edu.missouristate.mars.plugin.build.MarsBuildService
import edu.missouristate.mars.plugin.build.MarsSourceSet
import java.nio.file.Path
import java.util.List.copyOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Behavioral assessment uses the normal build cache and detached, fresh execution sessions. */
internal class MarsIndependentCheckpoint(
    private val project: Project, lifetime: Disposable, private val entry: Path, private val document: Document,
    private val createSession: () -> MarsContext = { MarsSessionService.createMarsComponent(backstepLimit = 1).context() },
) {
    private val source = MarsLearningSource(project, lifetime, entry, document, identity)
    @Volatile var active = true
        private set
    init { Disposer.register(lifetime) { active = false } }

    fun accepts(path: Path, selected: Document) = path == entry && acceptsLessonEditor(selected)
    fun acceptsLessonEditor(selected: Document) = active && selected === document && source.isBound()
    fun passed() = project.service<MarsLearningPolicy>().canStartIndependent() && source.passed(STEP)

    fun revealedHints(): List<LearningHint> {
        val revealed = project.service<MarsLearningProgress>().snapshot(identity)?.revealedHints.orEmpty()
        return hints.filter { it.id in revealed }
    }

    private fun nextHint(): LearningHint? {
        val revealed = project.service<MarsLearningProgress>().snapshot(identity)?.revealedHints.orEmpty()
        return hints.take(project.service<MarsLearningPolicy>().releasedHints).firstOrNull { it.id !in revealed }
    }

    fun canRevealNextHint() = active && nextHint() != null
    fun releasedHintCount() = project.service<MarsLearningPolicy>().releasedHints

    fun revealNextHint(): Boolean {
        if (!active) return false
        source.refreshRevision()
        val next = nextHint() ?: return false
        project.service<MarsLearningProgress>().revealHint(identity, next.id)
        return true
    }

    suspend fun check(): Feedback {
        if (!active) return Feedback.STALE
        if (!project.service<MarsLearningPolicy>().canStartIndependent()) return Feedback.POLICY
        val attempt = source.begin(STEP)
        try {
            val program = project.service<MarsBuildService>().prepare(MarsSourceSet(entry), MarsLearningSource.options, activateBuildView = true)
                ?: return Feedback.ASSEMBLY
            if (!readAction { isCurrent(attempt, program) }) return Feedback.STALE
            val context = currentCoroutineContext()
            val results = withContext(Dispatchers.Default) {
                PreparedConsoleChecks.evaluate(program.image, cases,
                    createSession, context::ensureActive)
            }
            return readAction {
                if (!isCurrent(attempt, program)) Feedback.STALE
                else {
                    source.finish(attempt, results.all(ConsoleCheckResult::passed))
                    Feedback.Checked(results)
                }
            }
        } finally {
            source.finish(attempt, false)
        }
    }

    private fun isCurrent(attempt: MarsLearningSource.Attempt, program: MarsAssembledProgram) =
        active && project.service<MarsLearningPolicy>().canStartIndependent() && source.current(attempt) && source.matches(attempt, program.sources)

    sealed interface Feedback {
        data object POLICY : Feedback
        data object ASSEMBLY : Feedback
        data object STALE : Feedback
        data class Checked(val results: List<ConsoleCheckResult>) : Feedback
    }

    data class LearningHint(val id: String, val title: String, val text: String)

    companion object {
        val hints = copyOf(listOf("plan", "lifetime", "combine", "trace").map { id ->
            LearningHint(id, learningMessages.getMessage("independent.hint.$id.title"),
                learningMessages.getMessage("independent.hint.$id.text"))
        })
        const val STEP = "independent-cases"
        val cases = copyOf(listOf(20, 0, -3, -1000, 1000).map { value ->
            ConsoleCheckCase("input $value", "$value\n",
                "Enter a small integer: Three times your number plus one is: ${3 * value + 1}\n", 2_000)
        })
        val identity = MarsLearningSource.identity("console-independent", cases.toString())
    }
}
