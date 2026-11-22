package edu.missouristate.mars.plugin.learning

import com.google.gson.JsonParseException
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.text.StringUtil.escapeXmlEntities
import com.intellij.platform.ide.progress.ModalTaskOwner
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.io.IOException
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Draft selection is local to this native dialog; only Save updates the shareable course policy. */
internal class MarsDownloadedPrerequisitesDialog private constructor(
    project: Project, private val content: MarsDownloadedLessonContent, private val assessments: List<MarsNativeAssessmentStep>,
) : DialogWrapper(project) {
    companion object {
        fun create(project: Project, content: MarsDownloadedLessonContent): MarsDownloadedPrerequisitesDialog {
            val assessments = content.plan.steps.filterIsInstance<MarsNativeAssessmentStep>()
            // Validate before DialogWrapper allocates its lifecycle-owned disposable.
            require(assessments.isNotEmpty())
            return MarsDownloadedPrerequisitesDialog(project, content, assessments)
        }
    }
    private val policy = project.service<MarsLearningPolicy>()
    private val draft = assessments.associate { step -> step.id to policy.downloadedRequirements(content.assessmentIdentity(step))
        .intersect(content.preceding(step).map { it.id }.toSet()) }.toMutableMap()
    private val unknown = assessments.any { policy.downloadedRequirements(content.assessmentIdentity(it)) != draft.getValue(it.id) }
    val activityBox = ComboBox(assessments.map {
        MarsDownloadedActivityChoice(downloadedLessonMessages.getMessage("activity.independent", Path.of(it.source).fileName.toString()), it.id)
    }.toTypedArray())
    val requirementsPanel = JPanel(BorderLayout())
    val requirementsScroll = JBScrollPane(requirementsPanel).apply { preferredSize = JBUI.size(580, 280) }
    private val selected: MarsNativeAssessmentStep get() = assessments[activityBox.selectedIndex]

    init {
        title = downloadedLessonMessages.getMessage("requirements.title")
        setOKButtonText(downloadedLessonMessages.getMessage("requirements.save"))
        activityBox.accessibleContext.accessibleName = downloadedLessonMessages.getMessage("requirements.exercise")
        activityBox.addActionListener { renderRequirements() }
        renderRequirements()
        init()
    }
    private fun renderRequirements() {
        val step = selected
        val preceding = content.preceding(step)
        val fields = panel {
            if (preceding.isEmpty()) row { label(downloadedLessonMessages.getMessage("requirements.none")) }
            for (activity in preceding) {
                val kind = when (activity) {
                    is MarsNativeLessonStep -> "prediction"
                    is MarsNativeRunStep -> "run"
                    is MarsNativeDebugStep -> "debug"
                    is MarsNativeAssessmentStep -> "assessment"
                }
                row {
                    checkBox(downloadedLessonMessages.getMessage("requirements.$kind", activity.id))
                        .applyToComponent { isSelected = activity.id in draft.getValue(step.id); toolTipText = "<html>" + escapeXmlEntities(activity.explanation).replace("\n", "<br>") + "</html>" }
                        .onChanged { box ->
                            val ids = draft.getValue(step.id)
                            draft[step.id] = if (box.isSelected) ids + activity.id else ids - activity.id
                        }
                }
            }
        }
        requirementsPanel.removeAll()
        requirementsPanel.add(fields, BorderLayout.CENTER)
        requirementsPanel.revalidate()
        requirementsPanel.repaint()
    }
    override fun createCenterPanel(): JComponent = panel {
        row { text(escapeXmlEntities(downloadedLessonMessages.getMessage("requirements.edition", content.installed.metadata.title,
            content.installed.metadata.version)), maxLineLength = 65) }
        row(downloadedLessonMessages.getMessage("requirements.exercise")) { cell(activityBox) }
        row { cell(requirementsScroll).align(Align.FILL) }.resizableRow()
        if (unknown) row { text(escapeXmlEntities(downloadedLessonMessages.getMessage("requirements.unknown")), maxLineLength = 65) }
        row { text(escapeXmlEntities(downloadedLessonMessages.getMessage("requirements.explanation")), maxLineLength = 65) }
    }
    public override fun doOKAction() {
        policy.setDownloadedRequirements(content, draft)
        super.doOKAction()
    }
}

internal class MarsConfigureDownloadedPrerequisitesAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project?.service<MarsDownloadedLessonSelection>()?.hasSelection == true
    }
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        try {
            val content = runWithModalProgressBlocking(ModalTaskOwner.project(project), downloadedLessonMessages.getMessage("resume.loading")) {
                withContext(Dispatchers.IO) { project.service<MarsDownloadedLessonSelection>().load() }
            }
            if (content.plan.steps.none { it is MarsNativeAssessmentStep }) {
                Messages.showInfoMessage(project, downloadedLessonMessages.getMessage("requirements.unavailable"),
                    downloadedLessonMessages.getMessage("requirements.title"))
            } else MarsDownloadedPrerequisitesDialog.create(project, content).show()
        } catch (error: IOException) { failure(project, error) }
        catch (error: IllegalArgumentException) { failure(project, error) }
        catch (error: JsonParseException) { failure(project, error) }
    }
    private fun failure(project: Project, error: Exception) = Messages.showErrorDialog(project,
        packageImportFailure(error), downloadedLessonMessages.getMessage("requirements.title"))
}
