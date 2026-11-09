package edu.missouristate.mars.plugin.run

import com.intellij.DynamicBundle
import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.configurations.SimpleConfigurationType
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.GenericProgramRunner
import com.intellij.execution.runners.showRunContent
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.components.PathMacroManager
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import edu.missouristate.mars.api.StopEvent
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.plugin.MarsExecutionOptions
import edu.missouristate.mars.plugin.language.MipsFile
import edu.missouristate.mars.plugin.language.MipsFileType
import java.nio.file.InvalidPathException
import java.nio.file.Path

internal val runMessages = DynamicBundle(MarsRunConfiguration::class.java, "messages.MarsBundle")

internal class MarsRunOptions : RunConfigurationOptions() {
    var sourcePath: String? by string()
    var delayedBranching: Boolean by property(false)
    var selfModifyingCode: Boolean by property(false)
}

internal class MarsRunType : SimpleConfigurationType(
    "MarsMipsRun", runMessages.getMessage("run.type"), runMessages.getMessage("run.type.description"),
    NotNullLazyValue.lazy { MipsFileType.icon },
) {
    override fun createTemplateConfiguration(project: Project) = MarsRunConfiguration(project, this, displayName)
    override fun getOptionsClass() = MarsRunOptions::class.java
}

internal class MarsRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    RunConfigurationBase<MarsRunOptions>(project, factory, name) {

    public override fun getOptions() = super.getOptions() as MarsRunOptions

    private fun entryPath(): Path? {
        val value = PathMacroManager.getInstance(project).expandPath(options.sourcePath) ?: return null
        return try {
            Path.of(value).takeIf { it.isAbsolute && it.fileName != null }
        } catch (_: InvalidPathException) {
            null
        }
    }

    override fun checkConfiguration() {
        if (entryPath() == null) throw RuntimeConfigurationError(runMessages.getMessage("run.source.invalid"))
    }

    override fun getConfigurationEditor(): SettingsEditor<MarsRunConfiguration> = object : SettingsEditor<MarsRunConfiguration>() {
        private var source = ""
        private var delayedBranching = false
        private var selfModifyingCode = false
        private val form = panel {
            row(runMessages.getMessage("run.source")) { textField().bindText(::source) }
            row { checkBox(runMessages.getMessage("run.delayed.branching")).bindSelected(::delayedBranching) }
            row { checkBox(runMessages.getMessage("run.self.modifying.code")).bindSelected(::selfModifyingCode) }
        }
        override fun resetEditorFrom(configuration: MarsRunConfiguration) {
            source = configuration.options.sourcePath.orEmpty()
            delayedBranching = configuration.options.delayedBranching
            selfModifyingCode = configuration.options.selfModifyingCode
            form.reset()
        }
        override fun applyEditorTo(configuration: MarsRunConfiguration) {
            form.apply()
            configuration.options.sourcePath = source.trim()
            configuration.options.delayedBranching = delayedBranching
            configuration.options.selfModifyingCode = selfModifyingCode
        }
        override fun createEditor() = form
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        if (!TrustedProjects.isProjectTrusted(project)) {
            throw ExecutionException(runMessages.getMessage("run.project.untrusted"))
        }
        val entry = entryPath() ?: throw ExecutionException(runMessages.getMessage("run.source.invalid"))
        return MarsRunState(environment, entry, MarsExecutionOptions(options.delayedBranching, options.selfModifyingCode))
    }
}

internal class MarsRunState(
    private val environment: ExecutionEnvironment, private val entry: Path, private val options: MarsExecutionOptions,
) :
    CommandLineState(environment) {
    override fun startProcess() = service().createProcess(entry, options)

    fun debugProcess(execute: suspend MarsProcessHandler.(MarsContext) -> StopEvent) =
        service().createProcess(entry, options, execute)

    private fun service() = environment.project.getService(MarsRunService::class.java)
}

internal class MarsRunConfigurationProducer : LazyRunConfigurationProducer<MarsRunConfiguration>() {
    override fun getConfigurationFactory() = ConfigurationTypeUtil.findConfigurationType(MarsRunType::class.java)

    override fun setupConfigurationFromContext(
        configuration: MarsRunConfiguration, context: ConfigurationContext, sourceElement: Ref<PsiElement>,
    ): Boolean {
        val file = entryFile(sourceElement.get()) ?: return false
        configuration.options.sourcePath = file.path
        configuration.name = file.nameWithoutExtension
        return true
    }

    override fun isConfigurationFromContext(configuration: MarsRunConfiguration, context: ConfigurationContext): Boolean {
        val file = entryFile(context.psiLocation) ?: return false
        return PathMacroManager.getInstance(configuration.project).expandPath(configuration.options.sourcePath) == file.path
    }

    private fun entryFile(element: PsiElement?) = (element?.containingFile as? MipsFile)?.virtualFile?.takeIf {
        it.fileSystem === LocalFileSystem.getInstance()
    }
}

internal class MarsRunRunner : GenericProgramRunner<RunnerSettings>() {
    override fun getRunnerId() = "MarsMipsRunner"
    override fun canRun(executorId: String, profile: RunProfile) =
        executorId == DefaultRunExecutor.EXECUTOR_ID && profile is MarsRunConfiguration

    override fun doExecute(state: RunProfileState, environment: ExecutionEnvironment) =
        showRunContent(state.execute(environment.executor, this), environment)
}
