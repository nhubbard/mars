package edu.missouristate.mars.plugin.learning

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.text.StringUtil.escapeXmlEntities
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.GridLayout
import javax.swing.ButtonGroup
import javax.swing.JComponent
import javax.swing.JPanel

/** Native dialog for a MARS prediction; the Features Trainer DSL has no choice/feedback widget. */
internal class MarsPredictionDialog(
    project: Project,
    private val prediction: MarsPrediction,
) : DialogWrapper(project) {
    private val progress = project.service<MarsLearningProgress>()
    private val choices = prediction.choices.map { it to JBRadioButton(it.text) }
    val panel: JComponent by lazy {
        JPanel(BorderLayout(0, JBUI.scale(12))).apply {
            add(JBLabel("<html>${escapeXmlEntities(prediction.question)}</html>"), BorderLayout.NORTH)
            val group = ButtonGroup()
            add(JPanel(GridLayout(0, 1, 0, JBUI.scale(8))).apply {
                for ((_, button) in choices) {
                    group.add(button)
                    add(button)
                }
            }, BorderLayout.CENTER)
        }
    }

    init {
        title = learningMessages.getMessage("prediction.title")
        setOKButtonText(learningMessages.getMessage("prediction.check"))
        progress.open(MarsPredictions.identity, MarsPredictions.identity.contentSha256)
        init()
    }

    override fun createCenterPanel(): JComponent = panel

    public override fun doOKAction() {
        val choice = choices.firstOrNull { it.second.isSelected }?.first
        if (choice == null) {
            setErrorText(learningMessages.getMessage("prediction.choose"))
            return
        }
        progress.recordChoice(MarsPredictions.identity, prediction.id, choice.id)
        if (choice.id == prediction.correct) super.doOKAction()
        else setErrorText(choice.feedback)
    }
}

internal abstract class MarsPredictionAction(private val prediction: () -> MarsPrediction) : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) { event.presentation.isEnabled = event.project != null }
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        MarsPredictionDialog(project, prediction()).show()
    }
}

internal class MarsPredictDoublingAction : MarsPredictionAction({ MarsPredictions.doubling })
internal class MarsPredictNegativeAction : MarsPredictionAction({ MarsPredictions.negative })

internal class MarsPredictOutputAction : MarsPredictionAction({ MarsPredictions.output })

internal class MarsPredictWrongOutputAction : MarsPredictionAction({ MarsPredictions.wrongOutput })
internal class MarsDiagnoseOutputAction : MarsPredictionAction({ MarsPredictions.diagnosis })
