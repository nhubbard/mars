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
import com.intellij.ui.dsl.builder.panel
import java.io.IOException
import java.nio.file.Path
import javax.swing.JComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import training.dsl.LessonContext
import training.learn.course.KLesson
import training.learn.course.LessonProperties
import training.util.getCallBackActionId

/** Each independent activity shares its check identity and cases with the guided route. */
internal class MarsDownloadedIndependentLesson(
    private val content: MarsDownloadedLessonContent, private val step: MarsNativeAssessmentStep,
) : KLesson("mars.downloaded.${content.installed.metadata.id}.${content.installed.sha256}.independent.${step.id}",
    downloadedLessonMessages.getMessage("activity.independent", Path.of(step.source).fileName.toString())) {
    val activity: String get() = step.id
    override val properties = LessonProperties(openFileAtStart = false)
    override val alwaysRestart = true
    override fun prepare(project: Project) {
        project.service<MarsDownloadedLessonSelection>().remember(content.installed, step.id)
        project.service<MarsLearningProgress>().open(content.identity, content.identity.contentSha256)
    }
    override val lessonContent: LessonContext.() -> Unit = {
        text(escapeXmlEntities(downloadedLessonMessages.getMessage("activity.independent.introduction")))
        downloadedAssessment(content, step) {}
        task {
            runtimeText {
                "<callback id=\"${getCallBackActionId("Mars.Learning.ChooseDownloaded")}\">" +
                    escapeXmlEntities(downloadedLessonMessages.getMessage("activity.choose")) + "</callback>"
            }
        }
    }
}

internal data class MarsDownloadedActivityChoice(val label: String, val id: String) {
    override fun toString() = label
}

/** The trainer opens individual lessons; this native dialog chooses a project-local destination. */
internal class MarsDownloadedActivityDialog(project: Project, content: MarsDownloadedLessonContent) : DialogWrapper(project) {
    private val policy = project.service<MarsLearningPolicy>()
    private val choices = listOf(MarsDownloadedActivityChoice(downloadedLessonMessages.getMessage("activity.guided"), "")) +
        if (policy.allowFreeWriting) content.plan.steps.filterIsInstance<MarsNativeAssessmentStep>().map {
            MarsDownloadedActivityChoice(downloadedLessonMessages.getMessage("activity.independent", Path.of(it.source).fileName.toString()), it.id)
        } else emptyList()
    val activityBox = ComboBox(choices.toTypedArray())
    val selectedActivity: String get() = (activityBox.selectedItem as MarsDownloadedActivityChoice).id

    init {
        title = downloadedLessonMessages.getMessage("activity.title")
        setOKButtonText(downloadedLessonMessages.getMessage("activity.open"))
        activityBox.selectedItem = choices.firstOrNull { it.id == project.service<MarsDownloadedLessonSelection>().activity(content) } ?: choices.first()
        activityBox.accessibleContext.accessibleName = downloadedLessonMessages.getMessage("activity.label")
        init()
    }
    override fun createCenterPanel(): JComponent = panel {
        row { text(escapeXmlEntities(downloadedLessonMessages.getMessage("activity.explanation")), maxLineLength = 65) }
        row(downloadedLessonMessages.getMessage("activity.label")) { cell(activityBox) }
        row { comment(downloadedLessonMessages.getMessage("activity.policy")) }
    }
    public override fun doOKAction() {
        if (selectedActivity.isNotEmpty() && !policy.allowFreeWriting) {
            setErrorText(downloadedLessonMessages.getMessage("activity.restricted"))
            return
        }
        super.doOKAction()
    }
}

internal class MarsChooseDownloadedActivityAction : DumbAwareAction() {
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
            val dialog = MarsDownloadedActivityDialog(project, content)
            dialog.show()
            if (dialog.isOK) MarsDownloadedLessons.open(project, content, dialog.selectedActivity)
        } catch (error: IOException) { failure(project, error) }
        catch (error: IllegalArgumentException) { failure(project, error) }
        catch (error: JsonParseException) { failure(project, error) }
    }
    private fun failure(project: Project, error: Exception) = Messages.showErrorDialog(project,
        packageImportFailure(error), downloadedLessonMessages.getMessage("resume.error"))
}
