package edu.missouristate.mars.plugin.learning

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import java.awt.Dimension
import javax.swing.Action
import javax.swing.JComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class MarsIndependentCheckAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    private fun target(event: AnActionEvent): Pair<Project, MarsIndependentCheckpoint>? {
        val project = event.project ?: return null
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE) ?: return null
        if (!file.isInLocalFileSystem) return null
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return null
        val checkpoint = project.service<MarsLearningExecution>().independent ?: return null
        return if (checkpoint.accepts(file.toNioPath(), editor.document)) project to checkpoint else null
    }
    override fun update(event: AnActionEvent) { event.presentation.isEnabled = target(event) != null }
    override fun actionPerformed(event: AnActionEvent) {
        val (project, checkpoint) = target(event) ?: return
        runCheck(project, checkpoint, event.coroutineScope)
    }

    internal fun runCheck(project: Project, checkpoint: MarsIndependentCheckpoint, scope: CoroutineScope) =
        scope.launch(Dispatchers.Default) {
            val feedback = withBackgroundProgress(project, learningMessages.getMessage("independent.check.progress")) { checkpoint.check() }
            withContext(Dispatchers.EDT) {
                if (checkpoint.active) MarsIndependentFeedbackDialog(project, feedback).show()
            }
        }
}

internal class MarsIndependentFeedbackDialog(project: Project, feedback: MarsIndependentCheckpoint.Feedback) : DialogWrapper(project) {
    val report: String = when (feedback) {
        MarsIndependentCheckpoint.Feedback.ASSEMBLY -> learningMessages.getMessage("independent.check.assembly")
        MarsIndependentCheckpoint.Feedback.STALE -> learningMessages.getMessage("independent.check.stale")
        is MarsIndependentCheckpoint.Feedback.Checked -> feedback.results.joinToString("\n\n") {
            if (it.passed) "PASS ${it.name}" else "FAIL ${it.name}\n${it.failure}"
        } + "\n\n" + learningMessages.getMessage("independent.check.scope")
    }
    init {
        title = learningMessages.getMessage("independent.check.title")
        setOKButtonText(learningMessages.getMessage("independent.check.close"))
        init()
    }
    override fun createCenterPanel(): JComponent = JBScrollPane(JBTextArea(report).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        accessibleContext.accessibleName = title
        caretPosition = 0
    }).apply { preferredSize = Dimension(640, 320) }
    override fun createActions(): Array<Action> = arrayOf(okAction)
}
