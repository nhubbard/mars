package edu.missouristate.mars.plugin.learning

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.text.StringUtil.escapeXmlEntities
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/** Only the bundled first unit's current content editions belong to this reset. */
internal object MarsLearningUnit {
    val identities: Set<MarsLessonIdentity> get() = setOf(
        MarsPredictions.identity, MarsConsoleRunCheckpoint.identity,
        MarsConsoleDebugCheckpoint.identity, MarsConsoleDebugCheckpoint.Segment.OUTPUT.identity,
        MarsConsoleDebugCheckpoint.Segment.DIAGNOSIS.identity, MarsConsoleRunCheckpoint.repairIdentity,
        MarsIndependentCheckpoint.identity,
    )
}

internal class MarsLearningResetAction : MarsLearningHistoryAction(false)
internal class MarsLearningRecoverAction : MarsLearningHistoryAction(true)

internal abstract class MarsLearningHistoryAction(private val recover: Boolean) : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) {
        val progress = event.project?.service<MarsLearningProgress>()
        event.presentation.isEnabled = progress != null && available(progress)
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val progress = project.service<MarsLearningProgress>()
        if (!available(progress)) return
        val dialog = MarsLearningHistoryDialog(project, recover)
        dialog.show()
        if (!dialog.isOK) return
        val changed = if (recover) progress.recover(MarsLearningUnit.identities)
        else progress.resetRecoverably(MarsLearningUnit.identities)
        if (changed) {
            val navigation = project.service<MarsLearningNavigation>()
            navigation.opened(MarsLearningNavigation.FIRST_GUIDED)
            navigation.open(MarsLearningMode.GUIDED)
        }
    }

    private fun available(progress: MarsLearningProgress): Boolean = if (recover)
        progress.canRecover(MarsLearningUnit.identities) else progress.canReset(MarsLearningUnit.identities)
}

/** The host has no recovery transaction for MARS's source-bound evidence; use its native dialog UI. */
internal class MarsLearningHistoryDialog(project: Project, recover: Boolean) : DialogWrapper(project) {
    private val prefix = if (recover) "history.recover" else "history.reset"
    val panel: JComponent by lazy {
        panel { row { text(escapeXmlEntities(learningMessages.getMessage("$prefix.explanation")), maxLineLength = 65) } }
    }
    init {
        title = learningMessages.getMessage("$prefix.title")
        setOKButtonText(learningMessages.getMessage("$prefix.confirm"))
        init()
    }
    override fun createCenterPanel(): JComponent = panel
}
