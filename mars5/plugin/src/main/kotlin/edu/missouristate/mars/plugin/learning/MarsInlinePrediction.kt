package edu.missouristate.mars.plugin.learning

import com.intellij.openapi.components.service
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil.escapeXmlEntities
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import edu.missouristate.mars.plugin.dynamicBundleOf
import edu.missouristate.mars.plugin.language.MipsTypes
import edu.missouristate.mars.plugin.language.operationHead
import java.awt.Rectangle
import javax.swing.JComponent
import training.dsl.TaskContext
import training.learn.lesson.LessonManager
import training.ui.LearningUiHighlightingManager
import training.ui.LearningUiHighlightingManager.HighlightingOptions
import training.ui.LearningUiManager
import training.util.getCallBackActionId

private val inlineMessages = dynamicBundleOf<MarsInlinePrediction>("messages.MarsInlinePredictionBundle")

/** The host provides callback links and lifecycle-owned highlighting, but no quiz widget. */
internal fun TaskContext.prediction(prediction: MarsPrediction) {
    // The public state-check future explicitly permits completion by a task. A lesson-link
    // click is not an IDE action/key event, so it must complete this check directly.
    val answered = stateCheck { MarsPredictions.hasCorrectAnswer(project, prediction) }
    runtimeText {
        removeAfterDone = true
        MarsInlinePrediction.instructionRange(editor, prediction)?.let { range ->
            editor.caretModel.moveToOffset(range.startOffset)
            editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
        }
        LearningUiHighlightingManager.highlightPartOfComponent(
            editor.contentComponent, HighlightingOptions(highlightInside = false),
            rectangle = MarsInlinePrediction.instructionHighlight(editor, prediction),
        )
        Disposer.register(taskDisposable) { LearningUiHighlightingManager.clearHighlights() }
        MarsInlinePrediction.text(
            project, prediction, active = { !disposed },
            feedback = { LessonManager.instance.addMessage(it, isInformer = true) },
            correct = { answered.complete(true) },
        )
    }
}

/** Use the native trainer's action callback and Learn-pane focus context. */
internal fun lessonActionLink(actionId: String, messageKey: String): String =
    "<callback id=\"${getCallBackActionId(actionId)}\">${escapeXmlEntities(inlineMessages.getMessage(messageKey))}</callback>"

internal object MarsInlinePrediction {
    fun text(
        project: Project,
        prediction: MarsPrediction,
        active: () -> Boolean,
        feedback: (String) -> Unit,
        correct: () -> Unit,
    ): String = text(project, MarsPredictions.identity, prediction,
        inlineMessages.getMessage("${prediction.id}.intro"), emptyList(), active, feedback, correct)

    fun answered(project: Project, identity: MarsLessonIdentity, prediction: MarsPrediction): Boolean {
        val history = project.service<MarsLearningProgress>().snapshot(identity) ?: return false
        return history.quizAttempts.any { it.quiz == prediction.id && it.choice == prediction.correct }
    }

    fun text(
        project: Project, identity: MarsLessonIdentity, prediction: MarsPrediction,
        introduction: String, hints: List<MarsLessonHint>, active: () -> Boolean,
        feedback: (String) -> Unit, correct: () -> Unit,
    ): String {
        val progress = project.service<MarsLearningProgress>()
        progress.open(identity, identity.contentSha256)
        var completed = answered(project, identity, prediction)
        val lines = mutableListOf(
            escapeXmlEntities(introduction) + "&#10;",
            "<strong>${escapeXmlEntities(prediction.question)}&#10;</strong>",
        )
        for ((id, text, feedback1) in prediction.choices) {
            val callback = LearningUiManager.addCallback {
                if (!project.isDisposed && active() && !completed) {
                    progress.recordChoice(identity, prediction.id, id)
                    if (id == prediction.correct) {
                        completed = true
                        correct()
                    } else {
                        feedback(escapeXmlEntities(feedback1))
                    }
                }
            }
            lines += "<callback id=\"$callback\">${escapeXmlEntities(id.uppercase() + ". " + text)}&#10;</callback>"
        }
        if (hints.isNotEmpty()) {
            val revealed = progress.snapshot(identity)!!.revealedHints
            for (hint in hints) if (hintKey(prediction.id, hint.id) in revealed) {
                lines += escapeXmlEntities(hint.text) + "&#10;"
            }
            val callback = LearningUiManager.addCallback {
                if (!project.isDisposed && active() && !completed) {
                    val history = progress.snapshot(identity)!!.revealedHints
                    val next = hints.take(project.service<MarsLearningPolicy>().releasedHints)
                        .firstOrNull { hintKey(prediction.id, it.id) !in history }
                    if (next == null) feedback(escapeXmlEntities(downloadedLessonMessages.getMessage("hint.none")))
                    else {
                        progress.revealHint(identity, hintKey(prediction.id, next.id))
                        feedback(escapeXmlEntities(next.text))
                    }
                }
            }
            lines += "<callback id=\"$callback\">${escapeXmlEntities(downloadedLessonMessages.getMessage("hint.open"))}</callback>"
        }
        // The trainer removes a message by paragraph index. Keep the quiz in one
        // message, with XML line feeds for its visible choice lines, so all controls
        // retire together rather than acquiring completion marks on wrong choices.
        return lines.joinToString("")
    }

    private fun hintKey(step: String, hint: String) = "hints/$step/$hint"

    fun instructionRange(editor: Editor, prediction: MarsPrediction): TextRange? = runReadActionBlocking {
        val manager = PsiDocumentManager.getInstance(editor.project ?: return@runReadActionBlocking null)
        if (!manager.isCommitted(editor.document)) return@runReadActionBlocking null
        val file = manager.getPsiFile(editor.document) ?: return@runReadActionBlocking null
        val mnemonic = when (prediction.id) {
            "doubling" -> "addu"
            "negative" -> "addiu"
            else -> "move"
        }
        var result: TextRange? = null
        file.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                if (result != null) return
                if (element.node.elementType == MipsTypes.psi("INSTRUCTION") && element.operationHead.text == mnemonic) {
                    result = element.textRange
                } else {
                    super.visitElement(element)
                }
            }
        })
        result
    }

    /** Native highlighting queries this geometry again when the editor scrolls. */
    fun instructionHighlight(editor: Editor, prediction: MarsPrediction): (JComponent) -> Rectangle? = {
        // The trainer's Swing timer paints without the task's read context.
        runReadActionBlocking {
            val range = instructionRange(editor, prediction) ?: return@runReadActionBlocking null
            val start = editor.offsetToXY(range.startOffset)
            val end = editor.offsetToXY(range.endOffset)
            Rectangle(start.x, start.y, maxOf(1, end.x - start.x), editor.lineHeight)
        }
    }
}
