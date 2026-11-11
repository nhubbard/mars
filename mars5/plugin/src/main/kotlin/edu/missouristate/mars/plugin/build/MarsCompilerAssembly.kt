package edu.missouristate.mars.plugin.build

import com.intellij.build.events.MessageEvent
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import edu.missouristate.mars.plugin.run.MarsRunConfiguration

/** Assembly selection and diagnostics shared with the optional JVM compiler module. */
class MarsCompilerAssembly private constructor(private val project: Project, private val sources: MarsSourceSet) {
    val entries get() = sources.entries

    suspend fun assemble(buildId: Any, report: suspend (List<MessageEvent>) -> Unit): Boolean =
        project.service<MarsBuildService>().assembleForCompiler(sources) { diagnostics, revisions ->
            val messages = readAction {
                diagnostics.map { diagnosticEvent(project, buildId, it, revisions) }
            }
            report(messages)
        }

    companion object {
        fun select(project: Project, configuration: RunConfiguration?): MarsCompilerAssembly? {
            val settings = project.service<MarsAssemblySettings>()
            if (!settings.assembleBeforeJvmCompile) return null
            // A MIPS launch already assembles through Before Launch or at process start.
            if (configuration is MarsRunConfiguration) return null
            val sources = settings.projectSourceSet() ?: return null
            return MarsCompilerAssembly(project, sources)
        }
    }
}
