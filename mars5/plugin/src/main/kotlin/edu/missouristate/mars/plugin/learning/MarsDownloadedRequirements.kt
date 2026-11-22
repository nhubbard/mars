package edu.missouristate.mars.plugin.learning

import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** An ordered course forms an acyclic prerequisite graph; all execution evidence stays source-bound. */
internal class MarsDownloadedRequirements(
    private val project: Project, private val content: MarsDownloadedLessonContent, private val step: MarsNativeAssessmentStep,
) {
    private data class Plan(val supported: Boolean, val activities: Set<MarsNativeLessonActivity>)
    private data class Snapshot(val plan: Plan, val sources: Map<String, MarsLearningSource?>)
    @Volatile private var snapshot: Snapshot? = null
    private val policy = project.service<MarsLearningPolicy>()

    init { content.preceding(step) }

    private fun plan(): Plan {
        val state = policy.getState()
        val pending = ArrayDeque<MarsNativeAssessmentStep>().apply { add(step) }
        val required = mutableSetOf<MarsNativeLessonActivity>()
        var supported = true
        while (pending.isNotEmpty()) {
            val assessment = pending.removeFirst()
            val preceding = content.preceding(assessment)
            val candidates = preceding.associateBy { it.id }
            val ids = state.downloadedPrerequisites[content.assessmentIdentity(assessment).key]?.activities.orEmpty()
            val selected = ids.mapNotNull { candidates[it].also { activity -> if (activity == null) supported = false } } +
                if (state.requirePredictions) preceding.filterIsInstance<MarsNativeLessonStep>() else emptyList()
            for (activity in selected) {
                if (required.add(activity) && activity is MarsNativeAssessmentStep) pending.add(activity)
            }
        }
        return Plan(supported, required.toSet())
    }

    private fun entry(activity: MarsNativeLessonActivity): Path = when (activity) {
        is MarsNativeAssessmentStep -> content.installed.directory.resolve(activity.source)
        else -> content.installed.entry
    }

    private data class Execution(val id: String, val entry: Path, val identity: MarsLessonIdentity)
    private fun execution(activity: MarsNativeLessonActivity): Execution? {
        val identity = when (activity) {
            is MarsNativeRunStep -> content.runIdentity(activity)
            is MarsNativeDebugStep -> content.debugIdentity(activity)
            is MarsNativeAssessmentStep -> content.assessmentIdentity(activity)
            is MarsNativeLessonStep -> return null
        }
        return Execution(activity.id, entry(activity), identity)
    }

    /** Refresh VFS outside read access before binding documents, including sources edited while the IDE was closed. */
    suspend fun refresh() {
        val requested = plan()
        val executions = requested.activities.mapNotNull(::execution)
        withContext(Dispatchers.IO) {
            for (path in executions.map { it.entry }.distinct())
                VirtualFileManager.getInstance().refreshAndFindFileByNioPath(path)?.refresh(false, false)
        }
        val sources = readAction {
            executions.associate { activity ->
                val path = activity.entry
                val file = VirtualFileManager.getInstance().findFileByNioPath(path)
                val document = file?.let { FileDocumentManager.getInstance().getDocument(it) }
                activity.id to document?.let { MarsLearningSource(project, path, it, activity.identity) }
            }
        }
        snapshot = Snapshot(requested, sources)
    }

    fun description(): String {
        val required = plan()
        if (!required.supported) return downloadedLessonMessages.getMessage("requirements.unsupported")
        if (required.activities.isEmpty()) return ""
        val names = content.plan.steps.filter { it in required.activities }.map { activity ->
            val kind = when (activity) {
                is MarsNativeLessonStep -> "prediction"
                is MarsNativeRunStep -> "run"
                is MarsNativeDebugStep -> "debug"
                is MarsNativeAssessmentStep -> "assessment"
            }
            downloadedLessonMessages.getMessage("requirements.$kind", activity.id)
        }
        return downloadedLessonMessages.getMessage("requirements.current", names.joinToString(", "))
    }

    fun ready(): Boolean = runReadActionBlocking {
        val current = plan()
        if (!current.supported) return@runReadActionBlocking false
        if (current.activities.isEmpty()) return@runReadActionBlocking true
        val bound = snapshot ?: return@runReadActionBlocking false
        if (bound.plan != current) return@runReadActionBlocking false
        current.activities.all { activity ->
            when (activity) {
                is MarsNativeLessonStep -> MarsInlinePrediction.answered(project, content.identity, activity.prediction)
                else -> bound.sources[activity.id]?.let { it.isBound() && it.passed(activity.id) } == true
            }
        }
    }
}
