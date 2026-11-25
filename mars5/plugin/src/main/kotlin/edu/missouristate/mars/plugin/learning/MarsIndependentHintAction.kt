@file:Suppress("DialogTitleCapitalization")

package edu.missouristate.mars.plugin.learning

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.UIUtil
import java.awt.Dimension
import java.awt.event.ActionEvent
import javax.swing.Action
import javax.swing.JComponent

internal class MarsIndependentHintAction : DumbAwareAction() {
  override fun getActionUpdateThread() = ActionUpdateThread.BGT

  // Help belongs to the current lesson, including when the Learn pane has focus.
  private fun target(event: AnActionEvent): Pair<Project, MarsIndependentCheckpoint>? {
    val project = event.project ?: return null
    return project.service<MarsLearningExecution>().independent?.let { project to it }
  }

  override fun update(event: AnActionEvent) {
    event.presentation.isEnabled = target(event) != null
  }

  override fun actionPerformed(event: AnActionEvent) {
    val (project, checkpoint) = target(event) ?: return
    MarsIndependentHintDialog(project, checkpoint).show()
  }
}

/**
 * The public lesson DSL has no persisted reveal widget; the dialog owns only MARS hint
 * presentation.
 */
internal class MarsIndependentHintDialog(
    project: Project,
    private val checkpoint: MarsIndependentCheckpoint,
) : DialogWrapper(project) {
  private val body =
      JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        font = UIUtil.getLabelFont()
      }
  val revealAction: Action =
      object : DialogWrapperAction(learningMessages.getMessage("independent.hint.reveal")) {
        override fun doAction(event: ActionEvent) {
          checkpoint.revealNextHint()
          refresh(scrollToLatest = true)
        }
      }
  val report: String
    get() = body.text

  init {
    title = learningMessages.getMessage("independent.hint.title")
    setOKButtonText(learningMessages.getMessage("independent.check.close"))
    body.accessibleContext.accessibleName = title
    init()
    refresh(scrollToLatest = false)
  }

  private fun refresh(scrollToLatest: Boolean) {
    val hints = checkpoint.revealedHints()
    body.text =
        learningMessages.getMessage("independent.hint.intro") +
            "\n\n" +
            hints
                .mapIndexed { index, hint -> "${index + 1}. ${hint.title}\n${hint.text}" }
                .joinToString("\n\n") +
            "\n\n" +
            learningMessages.getMessage(
                "policy.hint.status",
                checkpoint.releasedHintCount(),
                MarsIndependentCheckpoint.hints.size,
            ) +
            "\n\n" +
            learningMessages.getMessage("independent.hint.history")
    revealAction.isEnabled = checkpoint.canRevealNextHint()
    body.caretPosition = if (scrollToLatest) body.document.length else 0
  }

  override fun createCenterPanel(): JComponent =
      JBScrollPane(body).apply { preferredSize = Dimension(640, 340) }

  override fun createActions(): Array<Action> = arrayOf(revealAction, okAction)
}
