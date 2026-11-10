package edu.missouristate.mars.plugin.run

import com.intellij.execution.BeforeRunTask
import com.intellij.execution.BeforeRunTaskProvider
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.runBlockingMaybeCancellable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import edu.missouristate.mars.plugin.MarsExecutionOptions
import edu.missouristate.mars.plugin.build.MarsAssembledProgram
import edu.missouristate.mars.plugin.build.MarsSourceSet

internal val launchAssemblyKey = Key.create<MarsLaunchAssembly>("MARS launch assembly")

/** Shared by the original execution environment and the copy used by Before Launch. */
internal class MarsLaunchAssembly(
    val sources: MarsSourceSet, val options: MarsExecutionOptions, prepared: MarsAssembledProgram? = null,
) : Disposable {
    private var program = prepared
    private var disposed = false

    @Synchronized
    fun hasProgram() = program != null

    @Synchronized
    fun store(assembled: MarsAssembledProgram): Boolean {
        if (disposed) return false
        program = assembled
        return true
    }

    fun take(): MarsAssembledProgram? {
        val assembled = synchronized(this) {
            disposed = true
            removeProgram()
        }
        Disposer.dispose(this)
        return assembled
    }

    @Synchronized
    override fun dispose() {
        disposed = true
        program = null
    }

    private fun removeProgram() = program.also { program = null }
}

internal class MarsBeforeLaunchTask : BeforeRunTask<MarsBeforeLaunchTask>(MarsBeforeLaunchTaskProvider.ID)

internal class MarsBeforeLaunchTaskProvider : BeforeRunTaskProvider<MarsBeforeLaunchTask>() {
    override fun getId() = ID
    override fun getName() = runMessages.getMessage("run.before.launch.assemble")
    override fun isSingleton() = true
    override fun isDumbAware() = true

    override fun createTask(runConfiguration: RunConfiguration) =
        if (runConfiguration is MarsRunConfiguration) MarsBeforeLaunchTask() else null

    override fun executeTask(
        context: DataContext, configuration: RunConfiguration, environment: ExecutionEnvironment, task: MarsBeforeLaunchTask,
    ): Boolean {
        val assembly = environment.getUserData(launchAssemblyKey) ?: return false
        val service = environment.project.service<MarsRunService>()
        // The platform also invokes Before Launch providers on pooled threads without a progress indicator.
        return runBlockingMaybeCancellable {
            val preparation = service.prepareLaunch(assembly)
            try {
                preparation.await()
            } finally {
                preparation.cancel()
            }
        }
    }

    companion object {
        val ID = Key.create<MarsBeforeLaunchTask>("Mars.AssembleBeforeLaunch")
    }
}
