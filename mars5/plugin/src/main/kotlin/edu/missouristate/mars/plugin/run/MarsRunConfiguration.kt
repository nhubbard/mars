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
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import edu.missouristate.mars.api.StopEvent
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.plugin.MarsExecutionOptions
import edu.missouristate.mars.plugin.build.MemoryExportSelection
import edu.missouristate.mars.plugin.language.MipsFile
import edu.missouristate.mars.plugin.language.MipsFileType
import java.nio.file.Path
import javax.swing.JTextField

internal val runMessages = DynamicBundle(MarsRunConfiguration::class.java, "messages.MarsBundle")

internal class MarsRunOptions : RunConfigurationOptions() {
    var sourcePath: String? by string()
    var delayedBranching: Boolean by property(false)
    var assembleDirectory: Boolean by property(false)
    var selfModifyingCode: Boolean by property(false)
    var captureBitmap: Boolean by property(false)
    var captureReferences: Boolean by property(false)
    var connectDigitalLab: Boolean by property(false)
    var connectKeyboardDisplay: Boolean by property(false)
    var connectMarsBot: Boolean by property(false)
    var connectScavengerHunt: Boolean by property(false)
    var memoryDumps by list<RunMemoryDumpState>()
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

    private fun memoryDumps(entry: Path, invalid: (String) -> Exception): List<MemoryExportSelection> {
        val outputs = mutableSetOf<Path>()
        return options.memoryDumps.mapIndexed { index, state ->
            val selection = state.resolve(project) ?: throw invalid(runMessages.getMessage("run.export.invalid", index + 1))
            if (selection.output == entry) throw invalid(runMessages.getMessage("run.export.source", selection.output))
            if (!outputs.add(selection.output)) throw invalid(runMessages.getMessage("run.export.duplicate", selection.output))
            selection
        }
    }

    private fun checkDeviceConflict(invalid: (String) -> Exception) {
        if (options.connectMarsBot && options.connectScavengerHunt) {
            throw invalid(runMessages.getMessage("run.connect.deviceConflict"))
        }
    }

    override fun checkConfiguration() {
        checkDeviceConflict { RuntimeConfigurationError(it) }
        val entry = runFilePath(project, options.sourcePath) ?: throw RuntimeConfigurationError(runMessages.getMessage("run.source.invalid"))
        memoryDumps(entry) { RuntimeConfigurationError(it) }
    }

    override fun getConfigurationEditor(): SettingsEditor<MarsRunConfiguration> = object : SettingsEditor<MarsRunConfiguration>() {
        private var source = ""
        private var delayedBranching = false
        private var assembleDirectory = false
        private var selfModifyingCode = false
        private var captureBitmap = false
        private var captureReferences = false
        private var connectDigitalLab = false
        private var connectKeyboardDisplay = false
        private var connectMarsBot = false
        private var connectScavengerHunt = false
        private val dumps = RunMemoryDumpEditor(project) { runFilePath(project, sourceField.text.trim()) }
        private lateinit var sourceField: JTextField
        private val form = panel {
            row(runMessages.getMessage("run.source")) { sourceField = textField().bindText(::source).component }
            row { checkBox(runMessages.getMessage("run.assemble.directory")).bindSelected(::assembleDirectory) }
            row { checkBox(runMessages.getMessage("run.delayed.branching")).bindSelected(::delayedBranching) }
            row { checkBox(runMessages.getMessage("run.self.modifying.code")).bindSelected(::selfModifyingCode) }
            row { checkBox(runMessages.getMessage("run.capture.bitmap")).bindSelected(::captureBitmap) }
            row { checkBox(runMessages.getMessage("run.capture.references")).bindSelected(::captureReferences) }
            row { checkBox(runMessages.getMessage("run.connect.digitalLab")).bindSelected(::connectDigitalLab) }
            row { checkBox(runMessages.getMessage("run.connect.keyboardDisplay")).bindSelected(::connectKeyboardDisplay) }
            row { checkBox(runMessages.getMessage("run.connect.marsBot")).bindSelected(::connectMarsBot) }
            row { checkBox(runMessages.getMessage("run.connect.scavengerHunt")).bindSelected(::connectScavengerHunt) }
            row(runMessages.getMessage("run.export.title")) {
                cell(dumps.component).align(AlignX.FILL).comment(runMessages.getMessage("run.export.help"))
            }
        }
        override fun resetEditorFrom(configuration: MarsRunConfiguration) {
            source = configuration.options.sourcePath.orEmpty()
            delayedBranching = configuration.options.delayedBranching
            assembleDirectory = configuration.options.assembleDirectory
            selfModifyingCode = configuration.options.selfModifyingCode
            captureBitmap = configuration.options.captureBitmap
            captureReferences = configuration.options.captureReferences
            connectDigitalLab = configuration.options.connectDigitalLab
            connectKeyboardDisplay = configuration.options.connectKeyboardDisplay
            connectMarsBot = configuration.options.connectMarsBot
            connectScavengerHunt = configuration.options.connectScavengerHunt
            dumps.reset(configuration.options.memoryDumps)
            form.reset()
        }
        override fun applyEditorTo(configuration: MarsRunConfiguration) {
            form.apply()
            configuration.options.sourcePath = source.trim()
            configuration.options.delayedBranching = delayedBranching
            configuration.options.assembleDirectory = assembleDirectory
            configuration.options.selfModifyingCode = selfModifyingCode
            configuration.options.captureBitmap = captureBitmap
            configuration.options.captureReferences = captureReferences
            configuration.options.connectDigitalLab = connectDigitalLab
            configuration.options.connectKeyboardDisplay = connectKeyboardDisplay
            configuration.options.connectMarsBot = connectMarsBot
            configuration.options.connectScavengerHunt = connectScavengerHunt
            configuration.options.memoryDumps = dumps.copyRows()
        }
        override fun createEditor() = form
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        if (!TrustedProjects.isProjectTrusted(project)) {
            throw ExecutionException(runMessages.getMessage("run.project.untrusted"))
        }
        val entry = runFilePath(project, options.sourcePath) ?: throw ExecutionException(runMessages.getMessage("run.source.invalid"))
        checkDeviceConflict { ExecutionException(it) }
        return MarsRunState(
            environment, entry, MarsExecutionOptions(options.delayedBranching, options.selfModifyingCode),
            memoryDumps(entry) { ExecutionException(it) }, options.captureBitmap, options.captureReferences,
            options.connectDigitalLab, options.connectKeyboardDisplay, options.connectMarsBot, options.connectScavengerHunt,
            options.assembleDirectory,
        )
    }
}

internal class MarsRunState(
    private val environment: ExecutionEnvironment, val entry: Path, private val options: MarsExecutionOptions,
    private val memoryDumps: List<MemoryExportSelection>,
    private val captureBitmap: Boolean,
    private val captureReferences: Boolean,
    private val connectDigitalLab: Boolean,
    private val connectKeyboardDisplay: Boolean,
    private val connectMarsBot: Boolean,
    private val connectScavengerHunt: Boolean,
    private val assembleDirectory: Boolean,
) :
    CommandLineState(environment) {
    override fun startProcess() =
        service().createProcess(entry, options, memoryDumps, captureBitmap, captureReferences,
            connectDigitalLab, connectKeyboardDisplay, connectMarsBot, connectScavengerHunt, assembleDirectory)

    fun debugProcess(execute: suspend MarsProcessHandler.(MarsContext) -> StopEvent) =
        service().createProcess(entry, options, memoryDumps, captureBitmap, captureReferences,
            connectDigitalLab, connectKeyboardDisplay, connectMarsBot, connectScavengerHunt, assembleDirectory, execute)

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
