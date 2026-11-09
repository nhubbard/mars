package edu.missouristate.mars.plugin.build

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.task.BuildTask
import com.intellij.task.ProjectTask
import com.intellij.task.ProjectTaskContext
import com.intellij.task.ProjectTaskRunner
import org.jetbrains.concurrency.Promise

internal class MarsBuildTask(val sources: MarsSourceSet) : BuildTask {
    override fun getPresentableName() = buildMessages.getMessage("build.title", sources.entry.fileName.toString())
    override fun isIncrementalBuild() = false
}

internal class MarsProjectTaskRunner : ProjectTaskRunner() {
    override fun canRun(project: Project, projectTask: ProjectTask, context: ProjectTaskContext?): Boolean =
        projectTask is MarsBuildTask

    override fun run(project: Project, context: ProjectTaskContext, vararg tasks: ProjectTask): Promise<Result> =
        project.service<MarsBuildService>().assembleTasks(tasks.map { it as MarsBuildTask })
}
