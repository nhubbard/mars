package edu.missouristate.mars.plugin.learning

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.wm.ToolWindowAnchor
import edu.missouristate.mars.plugin.dynamicBundleOf
import java.security.MessageDigest
import training.dsl.LessonContext
import training.lang.AbstractLangSupport
import training.learn.course.IftModule
import training.learn.course.KLesson
import training.learn.course.LearningCourseBase
import training.learn.course.LessonProperties
import training.learn.course.LessonType

internal val learningMessages = dynamicBundleOf<MarsLearningCourse>("messages.MarsLearningBundle")
internal const val MARS_LEARNING_LANGUAGE = "MARS_MIPS"

/** Native course host; exercise files belong to the current project and are never restored on restart. */
internal class MarsLearningLanguageSupport : AbstractLangSupport() {
    override val primaryLanguage = MARS_LEARNING_LANGUAGE
    override val contentRootDirectoryName = "MARSLearningProject"
    override val projectResourcePath = "learnProjects/mars_mips/MARSLearningProject"
    override val sampleFilePath = "mars-labs/console/first-program.asm"
    override val useUserProjects = true
    override fun isSdkConfigured(project: Project) = true
    override fun checkSdk(sdk: Sdk?, project: Project) = Unit
    override fun applyToProjectAfterConfigure(): (Project) -> Unit = {}
    override fun cleanupBeforeLessons(project: Project) = Unit
}

internal class MarsLearningCourse : LearningCourseBase(MARS_LEARNING_LANGUAGE) {
    override fun modules(): Collection<IftModule> = listOf(
        object : IftModule(
            "mars.console", learningMessages.getMessage("module.name"),
            learningMessages.getMessage("module.description"), langSupport, LessonType.USER_PROJECT,
            { listOf(MarsRegisterPredictionLesson(), MarsConsoleRunLesson()) },
        ) {
            override val sampleFilePath = "mars-labs/console/first-program.asm"
            override fun preferredLearnWindowAnchor(project: Project) = ToolWindowAnchor.LEFT
        },
    )
}

/** The prediction segment of the first unit; execution observations are a separate lesson. */
internal class MarsRegisterPredictionLesson : KLesson(
    "mars.console.predictions", learningMessages.getMessage("lesson.predictions.name"),
) {
    override val properties = LessonProperties(openFileAtStart = false)
    // The host's ID-only passed flag must not bypass this content edition's own progress.
    override val alwaysRestart = true

    override fun prepare(project: Project) {
        MarsLearningWorkspace.ensureExample(project)
        project.service<MarsLearningProgress>().open(MarsPredictions.identity, MarsPredictions.identity.contentSha256)
    }

    override val lessonContent: LessonContext.() -> Unit = {
        task {
            val task = this
            before { task.addStep(MarsLearningWorkspace.openExample(project)) }
        }
        task {
            text(learningMessages.getMessage("lesson.doubling.text", action("Mars.Learning.PredictDoubling")))
            stateCheck { MarsPredictions.hasCorrectAnswer(project, MarsPredictions.doubling) }
        }
        text(MarsPredictions.doubling.answerExplanation)
        task {
            text(learningMessages.getMessage("lesson.negative.text", action("Mars.Learning.PredictNegative")))
            stateCheck { MarsPredictions.hasCorrectAnswer(project, MarsPredictions.negative) }
        }
        text(MarsPredictions.negative.answerExplanation)
        text(learningMessages.getMessage("lesson.predictions.conclusion"))
    }
}

internal data class MarsPredictionChoice(val id: String, val text: String, val feedback: String)

internal data class MarsPrediction(
    val id: String, val question: String, val choices: List<MarsPredictionChoice>, val correct: String,
) {
    val answerExplanation: String get() = choices.single { it.id == correct }.feedback
}

/** Bundled formative feedback is public client content, distinct from private instructor grading. */
internal object MarsPredictions {
    private fun prediction(id: String, correct: String): MarsPrediction = MarsPrediction(
        id, learningMessages.getMessage("$id.question"),
        listOf("a", "b", "c").map {
            MarsPredictionChoice(it, learningMessages.getMessage("$id.$it"), learningMessages.getMessage("$id.$it.feedback"))
        }, correct,
    )

    val doubling = prediction("doubling", "a")
    val negative = prediction("negative", "c")
    val identity = MarsLessonIdentity(
        "mars-book", "console-predictions", "0.1.0",
        MessageDigest.getInstance("SHA-256").apply {
            update("0.1.0".toByteArray(Charsets.UTF_8))
            for (path in listOf(
                "/messages/MarsLearningBundle.properties",
                "/learnProjects/mars_mips/MARSLearningProject/first-program.asm",
            )) {
                update(0.toByte())
                update(MarsPredictions::class.java.getResourceAsStream(path)!!.use { it.readBytes() })
            }
        }.digest()
            .joinToString("") { "%02x".format(it) },
    )

    fun hasCorrectAnswer(project: Project, prediction: MarsPrediction): Boolean =
        project.service<MarsLearningProgress>().snapshot(identity)?.quizAttempts
            ?.lastOrNull { it.quiz == prediction.id }?.choice == prediction.correct
}

/** The Run observation follows the predictions; Debug and independent grading remain distinct. */
internal class MarsConsoleRunLesson : KLesson("mars.console.run", learningMessages.getMessage("lesson.run.name")) {
    override val properties = LessonProperties(openFileAtStart = false)
    override val alwaysRestart = true

    override fun prepare(project: Project) { MarsLearningWorkspace.ensureExample(project) }

    override val lessonContent: LessonContext.() -> Unit = {
        lateinit var observation: MarsConsoleRunCheckpoint
        task {
            val task = this
            before { task.addStep(MarsLearningWorkspace.openExample(project)) }
        }
        task {
            before {
                observation = project.service<MarsLearningExecution>().observeRun(
                    lessonDisposable, virtualFile.toNioPath(), editor.document,
                )
            }
            text(learningMessages.getMessage("lesson.run.text"))
            stateCheck { observation.passed() }
        }
        text(learningMessages.getMessage("lesson.run.conclusion"))
    }
}
