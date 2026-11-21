package edu.missouristate.mars.plugin.learning

import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import training.lang.LangManager
import training.learn.CourseManager
import training.statistic.LessonStartingWay

internal enum class MarsLearningMode { GUIDED, FREE_WRITING }

/** Personal navigation only; choosing a mode never awards or resets learning evidence. */
@Service(Service.Level.PROJECT)
@State(name = "MarsLearningNavigation", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
internal class MarsLearningNavigation(private val project: Project) :
    SerializablePersistentStateComponent<MarsLearningNavigation.Navigation>(Navigation()) {
    data class Navigation(
        @JvmField var mode: String = MarsLearningMode.GUIDED.name,
        @JvmField var guidedLesson: String = FIRST_GUIDED,
    )

    val mode: MarsLearningMode get() = if (getState().mode == MarsLearningMode.FREE_WRITING.name)
        MarsLearningMode.FREE_WRITING else MarsLearningMode.GUIDED
    val guidedLesson: String get() = getState().guidedLesson.takeIf { it in guidedIds } ?: FIRST_GUIDED
    fun destination(mode: MarsLearningMode): String = if (mode == MarsLearningMode.FREE_WRITING) INDEPENDENT else guidedLesson

    fun opened(lesson: String) {
        require(lesson == INDEPENDENT || lesson in guidedIds)
        updateState {
            if (lesson == INDEPENDENT) it.copy(mode = MarsLearningMode.FREE_WRITING.name)
            else Navigation(MarsLearningMode.GUIDED.name, lesson)
        }
    }

    fun open(mode: MarsLearningMode) {
        // The public queue waits for native toolwindow/layout initialization on reopened projects.
        ToolWindowManager.getInstance(project).invokeLater { openNow(mode) }
    }

    internal fun openNow(mode: MarsLearningMode) {
        if (project.isDisposed) return
        LangManager.getInstance().updateLangSupport(MARS_LEARNING_LANGUAGE)
        val course = CourseManager.instance
        val lesson = checkNotNull(course.findLessonById(destination(mode)))
        course.openLesson(project, lesson, LessonStartingWay.LEARN_TAB, forceStartLesson = true)
    }

    companion object {
        const val FIRST_GUIDED = "mars.console.predictions"
        const val INDEPENDENT = "mars.console.independent"
        private val guidedIds = setOf(FIRST_GUIDED, "mars.console.run", "mars.console.debug", "mars.console.output",
            "mars.console.diagnosis", "mars.console.repair")
    }
}
