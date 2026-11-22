package edu.missouristate.mars.plugin.learning

import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.text.StringUtil.escapeXmlEntities
import edu.missouristate.mars.plugin.learning.MarsDownloadedAssessmentCheckpoint.Feedback
import kotlinx.coroutines.Job
import training.dsl.LessonContext
import training.learn.lesson.LessonManager
import training.ui.LearningUiManager

/** Native task text and callbacks; the grader never runs downloaded Kotlin check scripts. */
internal fun LessonContext.downloadedAssessment(content: MarsDownloadedLessonContent, step: MarsNativeAssessmentStep,
                                               observed: (MarsDownloadedAssessmentCheckpoint) -> Unit) {
    val entry = content.installed.directory.resolve(step.source)
    val identity = content.assessmentIdentity(step)
    task {
        val task = this
        before { task.addStep(project.service<MarsLearningExecution>().openLessonFile(entry)) }
    }
    task {
        lateinit var checkpoint: MarsDownloadedAssessmentCheckpoint
        var job: Job? = null
        before {
            checkpoint = project.service<MarsLearningExecution>().observeAssessment(taskDisposable, entry, editor.document, identity, step) {
                content.plan.steps.takeWhile { it.id != step.id }.filterIsInstance<MarsNativeLessonStep>()
                    .all { MarsInlinePrediction.answered(project, content.identity, it.prediction) }
            }
            observed(checkpoint)
        }
        runtimeText {
            removeAfterDone = true
            val show: (String) -> Unit = { LessonManager.instance.addMessage(it, isInformer = true) }
            val callback = LearningUiManager.addCallback {
                if (!disposed && job?.isActive != true) {
                    if (!checkpoint.accepts(FileEditorManager.getInstance(project).selectedTextEditor?.document)) {
                        show(escapeXmlEntities(downloadedLessonMessages.getMessage("assessment.editor")))
                    } else {
                        job = project.service<MarsLearningExecution>().checkAssessment(taskDisposable, checkpoint) { result ->
                            show(MarsAssessmentFeedback.text(result))
                        }
                    }
                }
            }
            escapeXmlEntities(step.explanation) + "&#10;<callback id=\"$callback\">" +
                // Keep the line feed inside the link: the host discards a whitespace-only text node.
                escapeXmlEntities(downloadedLessonMessages.getMessage("assessment.check")) + "&#10;</callback>" +
                MarsLessonHints.text(project, identity, step.id, step.hints, active = { !disposed && !checkpoint.passed() }, feedback = show)
        }
        timerCheck { checkpoint.passed() }
    }
    task { runtimeText { escapeXmlEntities(step.success) } }
}

internal object MarsAssessmentFeedback {
    fun text(feedback: Feedback): String {
        val message = when (feedback) {
            Feedback.ASSEMBLY -> downloadedLessonMessages.getMessage("assessment.assembly")
            Feedback.STALE -> downloadedLessonMessages.getMessage("assessment.stale")
            Feedback.POLICY -> downloadedLessonMessages.getMessage("assessment.policy")
            Feedback.LIMIT -> downloadedLessonMessages.getMessage("assessment.limit")
            is Feedback.Checked -> {
                val failed = feedback.results.firstOrNull { !it.passed }
                val summary = downloadedLessonMessages.getMessage("assessment.summary", feedback.results.count { it.passed }, feedback.results.size)
                val detail = if (failed == null) "" else "\n" + failed.name + ":\n" + failed.failure!!.take(4096)
                val count = if (feedback.limit == 0) "" else "\n" + downloadedLessonMessages.getMessage("assessment.attempts", feedback.completed, feedback.limit)
                summary + detail + count
            }
        }
        // Expected/actual program output can contain control characters. Preserve
        // their meaning visibly before the native host parses its XML message.
        val printable = buildString {
            message.codePoints().forEach { code ->
                // UTF-16 codePoints() never produces a value above 0x10ffff.
                if (code == 10 || code in 32..0xd7ff || code in 0xe000..0xfffd || code >= 0x10000) {
                    appendCodePoint(code)
                } else append("\\u" + code.toString(16).padStart(4, '0'))
            }
        }
        return escapeXmlEntities(printable).replace("\n", "&#10;")
    }
}
