package edu.missouristate.mars.plugin.run

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareToggleAction
import javax.swing.JComponent

internal val runSpeeds = listOf(0f, 0.05f, 0.1f, 0.2f, 0.3f, 0.4f, 0.5f) + (1..30).map(Int::toFloat)

internal fun runSpeedTitle(rate: Float) =
    if (rate == 0f) runMessages.getMessage("run.speed.unlimited") else runMessages.getMessage("run.speed.rate", rate)

internal class MarsRunSpeedAction(private val handler: MarsProcessHandler) : ComboBoxAction(), DumbAware {
    init {
        val title = runMessages.getMessage("run.speed.title")
        setPopupTitle(title)
        templatePresentation.text = title
        templatePresentation.description = title
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.text = runMessages.getMessage("run.speed.current", runSpeedTitle(handler.runSpeed))
        e.presentation.isEnabled = !handler.isProcessTerminating && !handler.isProcessTerminated
    }

    public override fun createPopupActionGroup(button: JComponent, dataContext: DataContext) = DefaultActionGroup().apply {
        for (rate in runSpeeds) {
            add(object : DumbAwareToggleAction(runSpeedTitle(rate)) {
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
                override fun isSelected(e: AnActionEvent) = handler.runSpeed == rate
                override fun setSelected(e: AnActionEvent, state: Boolean) {
                    handler.runSpeed = rate
                }
            })
        }
    }
}
