@file:Suppress("DialogTitleCapitalization")

package edu.missouristate.mars.plugin.learning

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.text.StringUtil.escapeXmlEntities
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

internal class MarsLearningOpenAction : DumbAwareAction() {
  override fun getActionUpdateThread() = ActionUpdateThread.BGT

  override fun update(event: AnActionEvent) {
    event.presentation.isEnabled = event.project != null
  }

  override fun actionPerformed(event: AnActionEvent) {
    val project = event.project ?: return
    val dialog = MarsLearningModeDialog(project)
    dialog.show()
    if (dialog.isOK) project.service<MarsLearningNavigation>().open(dialog.selectedMode)
  }
}

/** The native course has lessons, but no project-specific guided/free-writing selection dialog. */
internal class MarsLearningModeDialog(project: Project) : DialogWrapper(project) {
  private val policy = project.service<MarsLearningPolicy>()
  private val navigation = project.service<MarsLearningNavigation>()
  private val guided = JBRadioButton(learningMessages.getMessage("mode.guided"))
  private val free = JBRadioButton(learningMessages.getMessage("mode.free"))
  val selectedMode: MarsLearningMode
    get() = if (free.isSelected) MarsLearningMode.FREE_WRITING else MarsLearningMode.GUIDED

  val panel: JComponent by lazy {
    panel {
      row {
        text(escapeXmlEntities(learningMessages.getMessage("mode.explanation")), maxLineLength = 65)
      }
      buttonsGroup {
        row { cell(guided) }
        row { cell(free) }
      }
      val lesson =
          learningMessages.getMessage(
              "lesson.${navigation.guidedLesson.substringAfterLast('.')}.name",
          )
      row {
        text(
            escapeXmlEntities(learningMessages.getMessage("mode.resume", lesson)) +
                "<br>" +
                escapeXmlEntities(
                    if (policy.allowFreeWriting) learningMessages.getMessage("mode.preserve")
                    else policy.independentRestriction(),
                ),
            maxLineLength = 65,
        )
      }
    }
  }

  init {
    title = learningMessages.getMessage("mode.title")
    setOKButtonText(learningMessages.getMessage("mode.open"))
    free.isEnabled = policy.allowFreeWriting
    if (navigation.mode == MarsLearningMode.FREE_WRITING && free.isEnabled) free.isSelected = true
    else guided.isSelected = true
    init()
  }

  override fun createCenterPanel(): JComponent = panel

  public override fun doOKAction() {
    if (selectedMode == MarsLearningMode.FREE_WRITING && !policy.allowFreeWriting) {
      setErrorText(policy.independentRestriction())
      return
    }
    super.doOKAction()
  }
}
