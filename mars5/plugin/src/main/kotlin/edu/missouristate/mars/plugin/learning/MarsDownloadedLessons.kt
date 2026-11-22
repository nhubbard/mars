package edu.missouristate.mars.plugin.learning

import com.google.gson.JsonParseException
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil.escapeXmlEntities
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.platform.ide.progress.ModalTaskOwner
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import edu.missouristate.mars.plugin.dynamicBundleOf
import java.io.IOException
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import training.dsl.LessonContext
import training.dsl.TaskContext
import training.dsl.TaskRuntimeContext
import training.lang.LangManager
import training.learn.CourseManager
import training.learn.course.IftModule
import training.learn.course.KLesson
import training.learn.course.LessonProperties
import training.learn.course.LessonType
import training.learn.lesson.LessonManager
import training.statistic.LessonStartingWay
import training.ui.LearningUiHighlightingManager
import training.ui.LearningUiHighlightingManager.HighlightingOptions
import training.util.getCallBackActionId

internal val downloadedLessonMessages = dynamicBundleOf<MarsDownloadedLesson>("messages.MarsDownloadedLessonBundle")

internal data class MarsDownloadedLessonContent(val installed: MarsLessonPackages.Installed, val plan: MarsNativeLessonPlan) {
    val identity = MarsLessonIdentity("mars-book", installed.metadata.id + ".guided", installed.metadata.version, installed.sha256)
    fun runIdentity(step: MarsNativeRunStep) = identity.copy(lesson = installed.metadata.id + ".run." + step.id)
    fun debugIdentity(step: MarsNativeDebugStep) = identity.copy(lesson = installed.metadata.id + ".debug." + step.id)
    fun assessmentIdentity(step: MarsNativeAssessmentStep) = identity.copy(lesson = installed.metadata.id + ".assessment." + step.id)
}

/** Project selection only. The retained archive owns definitions; edited working files own source. */
@Service(Service.Level.PROJECT)
@State(name = "MarsDownloadedLessonSelection", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
internal class MarsDownloadedLessonSelection(private val project: Project) :
    SerializablePersistentStateComponent<MarsDownloadedLessonSelection.Selection>(Selection()) {
    data class Selection(@JvmField var directory: String = "", @JvmField var activity: String = "")
    val hasSelection: Boolean get() = getState().directory.isNotBlank()

    fun remember(installed: MarsLessonPackages.Installed, activity: String = "") {
        updateState { Selection(relative(installed), activity) }
    }

    fun activity(content: MarsDownloadedLessonContent): String {
        if (getState().directory != relative(content.installed)) return ""
        return getState().activity.takeIf { id ->
            content.plan.steps.filterIsInstance<MarsNativeAssessmentStep>().any { it.id == id }
        } ?: ""
    }

    /** Blocking archive validation, called outside EDT. Missing student files are never rebuilt. */
    fun load(): MarsDownloadedLessonContent {
        val root = Path.of(requireNotNull(project.basePath) { "Open a project before continuing a lab" }).toRealPath()
        val selected = Path.of(getState().directory)
        require(getState().directory.isNotBlank() && !selected.isAbsolute && selected.none { it.toString() in setOf(".", "..") }) {
            "Import a native MARS lesson before continuing it"
        }
        val archive = MarsLessonPackages.regular(root, selected.resolve(".mars-package.zip").toString().replace('\\', '/'))
        return MarsLessonPackages.prepare(archive).use { prepared ->
            val plan = requireNotNull(prepared.nativeLesson) { "The selected package has no native lesson" }
            val expected = Path.of("mars-labs", "packages", prepared.metadata.id, prepared.metadata.version, prepared.sha256)
            require(selected == expected) { "The retained lesson archive does not match its selected edition" }
            MarsDownloadedLessonContent(prepared.install(root), plan)
        }
    }

    companion object {
        private fun relative(installed: MarsLessonPackages.Installed) =
            "mars-labs/packages/${installed.metadata.id}/${installed.metadata.version}/${installed.sha256}"
    }
}

/** Native courses are globally cached; directly open this project's immutable content through the public host. */
internal object MarsDownloadedLessons {
    fun open(project: Project, content: MarsDownloadedLessonContent, activity: String = "") {
        require(activity.isEmpty() || content.plan.steps.filterIsInstance<MarsNativeAssessmentStep>().any { it.id == activity })
        require(activity.isEmpty() || project.service<MarsLearningPolicy>().allowFreeWriting) {
            downloadedLessonMessages.getMessage("activity.restricted")
        }
        project.service<MarsDownloadedLessonSelection>().remember(content.installed, activity)
        ToolWindowManager.getInstance(project).invokeLater {
            openNow(project, content, activity)
        }
    }

    fun module(content: MarsDownloadedLessonContent): IftModule {
        val support = checkNotNull(LangManager.getInstance().getLangSupportById(MARS_LEARNING_LANGUAGE))
        return object : IftModule("mars.downloaded", content.installed.metadata.title,
            downloadedLessonMessages.getMessage("module.description"), support, LessonType.USER_PROJECT,
            { listOf(MarsDownloadedLesson(content)) + content.plan.steps.filterIsInstance<MarsNativeAssessmentStep>()
                .map { MarsDownloadedIndependentLesson(content, it) } }) {
            override val sampleFilePath: String? = null
            override fun preferredLearnWindowAnchor(project: Project) = ToolWindowAnchor.LEFT
        }
    }

    internal fun openNow(project: Project, content: MarsDownloadedLessonContent, activity: String = "") {
        if (project.isDisposed) return
        LangManager.getInstance().updateLangSupport(MARS_LEARNING_LANGUAGE)
        val lessons = module(content).lessons
        val lesson = if (activity.isEmpty()) lessons.first()
            else lessons.filterIsInstance<MarsDownloadedIndependentLesson>().single { it.activity == activity }
        CourseManager.instance.openLesson(project, lesson, LessonStartingWay.LEARN_TAB, forceStartLesson = true)
    }
}

internal class MarsDownloadedLesson(private val content: MarsDownloadedLessonContent) : KLesson(
    "mars.downloaded.${content.installed.metadata.id}.${content.installed.sha256}", content.installed.metadata.title,
) {
    override val properties = LessonProperties(openFileAtStart = false)
    override val alwaysRestart = true
    override fun prepare(project: Project) {
        project.service<MarsDownloadedLessonSelection>().remember(content.installed)
        project.service<MarsLearningProgress>().open(content.identity, content.identity.contentSha256)
    }
    override val lessonContent: LessonContext.() -> Unit = {
        task {
            val task = this
            before { task.addStep(project.service<MarsLearningExecution>().openLessonFile(content.installed.entry)) }
        }
        text(escapeXmlEntities(content.plan.introduction))
        val checks = mutableListOf<() -> Boolean>()
        for (step in content.plan.steps) {
            when (step) {
                is MarsNativeLessonStep -> {
                    task { downloadedPrediction(content.identity, step) }
                    task { runtimeText { escapeXmlEntities(step.prediction.answerExplanation) } }
                }
                is MarsNativeRunStep -> {
                    task { downloadedRun(content, step) { checks += it::passed } }
                    task { runtimeText { escapeXmlEntities(step.success) } }
                }
                is MarsNativeDebugStep -> downloadedDebug(content, step) { checks += it::passed }
                is MarsNativeAssessmentStep -> downloadedAssessment(content, step) { checks += it::passed }
            }
        }
        if (content.plan.steps.any { it !is MarsNativeLessonStep }) {
            task {
                runtimeText {
                    if (checks.all { it() }) null
                    else escapeXmlEntities(downloadedLessonMessages.getMessage("run.recheck")) + " " +
                        "<callback id=\"${getCallBackActionId("Mars.Learning.ContinueDownloaded")}\">" +
                        escapeXmlEntities(downloadedLessonMessages.getMessage("run.continue")) + "</callback>"
                }
                timerCheck { checks.all { it() } }
            }
        }
        task { runtimeText { escapeXmlEntities(content.plan.conclusion) } }
    }
}

internal fun LessonContext.downloadedDebug(content: MarsDownloadedLessonContent, step: MarsNativeDebugStep,
                                          observed: (MarsDownloadedDebugCheckpoint) -> Unit) {
    lateinit var checkpoint: MarsDownloadedDebugCheckpoint
    val identity = content.debugIdentity(step)
    for ((index, stop) in step.stops.withIndex()) {
        task {
            if (index == 0) before {
                // Ordered stops span several native tasks; their observer belongs to
                // the lesson, not the first task's retired controls.
                val lifetime = lessonDisposable
                checkpoint = project.service<MarsLearningExecution>().observeDebug(lifetime, content.installed.entry,
                    editor, identity, step) { outcome ->
                    taskInvokeLater {
                        // The native lesson executor discards this callback after the lesson stops.
                        LessonManager.instance.addMessage(escapeXmlEntities(downloadedLessonMessages
                            .getMessage("debug.feedback.${outcome.name.lowercase()}")), isInformer = true)
                    }
                }
                observed(checkpoint)
            }
            runtimeText {
                removeAfterDone = true
                highlightDownloadedInstruction(stop.anchor)
                val active = { !disposed && checkpoint.phase == index }
                val feedback = { text: String -> LessonManager.instance.addMessage(text, isInformer = true) }
                val introduction = if (index == 0) escapeXmlEntities(step.explanation) + "&#10;" +
                    MarsLessonHints.text(project, identity, step.id, step.hints, active, feedback) + "&#10;" else ""
                introduction + escapeXmlEntities(stop.explanation) + "&#10;" +
                    MarsLessonHints.text(project, identity, "${step.id}/${stop.id}", stop.hints, active, feedback)
            }
            timerCheck { checkpoint.phase > index }
        }
        task { runtimeText { escapeXmlEntities(stop.success) } }
    }
    task { runtimeText { escapeXmlEntities(step.success) } }
}

internal fun TaskContext.downloadedPrediction(identity: MarsLessonIdentity, step: MarsNativeLessonStep) {
    val answered = stateCheck { MarsInlinePrediction.answered(project, identity, step.prediction) }
    runtimeText {
        removeAfterDone = true
        highlightDownloadedInstruction(step.anchor)
        MarsInlinePrediction.text(project, identity, step.prediction, step.explanation, step.hints,
            active = { !disposed }, feedback = { LessonManager.instance.addMessage(it, isInformer = true) },
            correct = { answered.complete(true) })
    }
}

internal fun TaskContext.downloadedRun(content: MarsDownloadedLessonContent, step: MarsNativeRunStep,
                                       observed: (MarsDownloadedRunCheckpoint) -> Unit) {
    lateinit var checkpoint: MarsDownloadedRunCheckpoint
    val identity = content.runIdentity(step)
    before {
        checkpoint = project.service<MarsLearningExecution>().observeRun(taskDisposable, content.installed.entry,
            editor.document, identity, step) { outcome ->
            if (outcome != MarsDownloadedRunCheckpoint.Outcome.PASSED) taskInvokeLater {
                if (!disposed) LessonManager.instance.addMessage(escapeXmlEntities(downloadedLessonMessages
                    .getMessage("run.feedback.${outcome.name.lowercase()}")), isInformer = true)
            }
        }
        observed(checkpoint)
    }
    runtimeText {
        removeAfterDone = true
        highlightDownloadedInstruction(step.anchor)
        escapeXmlEntities(step.explanation) + "&#10;" + MarsLessonHints.text(project, identity, step.id, step.hints,
            active = { !disposed && !checkpoint.passed() },
            feedback = { LessonManager.instance.addMessage(it, isInformer = true) })
    }
    timerCheck { checkpoint.passed() }
}

private fun TaskRuntimeContext.highlightDownloadedInstruction(anchor: MarsLessonAnchor?) {
    if (anchor == null) return
    runReadActionBlocking {
        MarsLessonAnchors.range(editor, anchor)?.let { range ->
            editor.caretModel.moveToOffset(range.startOffset)
            editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
        }
    }
    LearningUiHighlightingManager.highlightPartOfComponent(editor.contentComponent,
        HighlightingOptions(highlightInside = false), rectangle = MarsLessonAnchors.highlight(editor, anchor))
    Disposer.register(taskDisposable) { LearningUiHighlightingManager.clearHighlights() }
}

internal class MarsContinueDownloadedLessonAction : DumbAwareAction() {
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
            val activity = project.service<MarsDownloadedLessonSelection>().activity(content)
            MarsDownloadedLessons.open(project, content, activity)
        } catch (error: IOException) { failure(project, error) }
        catch (error: IllegalArgumentException) { failure(project, error) }
        catch (error: JsonParseException) { failure(project, error) }
    }
    private fun failure(project: Project, error: Exception) = Messages.showErrorDialog(project,
        packageImportFailure(error), downloadedLessonMessages.getMessage("resume.error"))
}
